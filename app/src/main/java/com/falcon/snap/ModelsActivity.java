package com.falcon.snap;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.documentfile.provider.DocumentFile;

import com.falcon.snap.engine.ModelSource;
import com.falcon.snap.engine.ModelStore;
import com.falcon.snap.engine.OcrEngine;
import com.falcon.snap.engine.TranslatorEngine;
import com.falcon.snap.model.Language;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows which model files are installed and where the app looks for them, and imports model files
 * from a folder the user picks (USB stick, SD card, Downloads...). The app never downloads models.
 */
public class ModelsActivity extends BaseActivity {
    private static final int MAX_FOLDER_DEPTH = 3;
    private static final long PROGRESS_STEP_BYTES = 8L << 20;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ViewGroup container;
    private View importButton;
    private TextView progressView;
    private ActivityResultLauncher<Uri> folderLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_models);
        setupTopBar(R.string.title_models);

        folderLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::importFrom);
        container = findViewById(R.id.models_container);
        importButton = findViewById(R.id.btn_import_models);
        progressView = findViewById(R.id.import_progress);
        importButton.setOnClickListener(v -> folderLauncher.launch(null));

        TextView folder = findViewById(R.id.models_folder);
        folder.setText(ModelStore.primaryRoot(this).getAbsolutePath());
        showStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A running import is allowed to finish.
        io.shutdown();
    }

    // ---------------------------------------------------------------- status

    private void showStatus() {
        container.removeAllViews();

        ViewGroup ocr = addGroup(R.string.models_group_ocr);
        addRow(ocr, getString(R.string.model_det), ModelStore.detector(this), false);
        for (String key : Language.allOcrKeys()) {
            String languages = Language.languagesFor(this, key);
            String title = getString(R.string.model_rec_fmt, key) + (languages.isEmpty() ? "" : "  ·  " + languages);
            // Only the main recognizer is needed for the default language pair.
            addRow(ocr, title, ModelStore.recognizer(this, key), !Language.OCR_MAIN.equals(key));
        }

        ModelStore.NllbFiles nllb = ModelStore.nllbStatus(this);
        ViewGroup translation = addGroup(R.string.models_group_nllb);
        addRow(translation, getString(R.string.model_encoder), ModelSource.of(nllb.encoder), false);
        addRow(translation, getString(R.string.model_decoder), ModelSource.of(nllb.decoder), false);
        if (nllb.decoderWithPast != null) {
            addRow(translation, getString(R.string.model_decoder_with_past), ModelSource.of(nllb.decoderWithPast), false);
        }
        addRow(translation, getString(R.string.model_tokenizer), ModelSource.of(nllb.tokenizer), false);
    }

    private ViewGroup addGroup(int titleRes) {
        TextView title = (TextView) getLayoutInflater().inflate(R.layout.item_language_header, container, false);
        title.setText(titleRes);
        container.addView(title);
        return SettingRows.addGroup(this, container);
    }

    private void addRow(ViewGroup group, String title, @Nullable ModelSource model, boolean optional) {
        SettingRows.addDividerIfNeeded(this, group);
        View row = getLayoutInflater().inflate(R.layout.item_model_row, group, false);
        ((TextView) row.findViewById(R.id.model_title)).setText(title);
        TextView detail = row.findViewById(R.id.model_detail);
        ImageView icon = row.findViewById(R.id.model_icon);
        if (model != null) {
            long size = model.size(this);
            String text = model.name() + (size < 0 ? "" : "  ·  " + Formatter.formatShortFileSize(this, size));
            // Bundled = shipped inside the APK (assets); otherwise it is a file in the models folder.
            detail.setText(model.isBundled() ? text + "  ·  " + getString(R.string.model_bundled) : text);
            icon.setImageResource(R.drawable.ic_check_circle);
            ImageViewCompat.setImageTintList(icon, ContextCompat.getColorStateList(this, R.color.status_ok));
        } else {
            detail.setText(optional ? R.string.model_optional : R.string.model_missing);
            icon.setImageResource(optional ? R.drawable.ic_info : R.drawable.ic_close);
            ImageViewCompat.setImageTintList(icon, ContextCompat.getColorStateList(this,
                    optional ? R.color.text_secondary : R.color.brand_red));
        }
        group.addView(row);
    }

    // ---------------------------------------------------------------- import

    private void importFrom(@Nullable Uri treeUri) {
        DocumentFile root = treeUri == null ? null : DocumentFile.fromTreeUri(this, treeUri);
        if (root == null) {
            return;
        }
        importButton.setEnabled(false);
        progressView.setVisibility(View.VISIBLE);
        progressView.setText(R.string.import_scanning);
        // Let go of the loaded models so their files can be replaced.
        TranslatorEngine.release();
        OcrEngine.release();

        Context appContext = getApplicationContext();
        File target = ModelStore.primaryRoot(this);
        io.execute(() -> {
            String message;
            try {
                List<DocumentFile> files = new ArrayList<>();
                collect(root, files, 0);
                for (DocumentFile file : files) {
                    copy(appContext, file, target);
                }
                message = files.isEmpty() ? getString(R.string.import_none)
                        : getString(R.string.import_done_fmt, files.size());
            } catch (IOException | RuntimeException e) {
                message = getString(R.string.import_failed_fmt, String.valueOf(e.getMessage()));
            }
            String result = message;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                importButton.setEnabled(true);
                progressView.setText(result);
                showStatus();
            });
        });
    }

    /** Finds model files by name, whether the folder is a models/ocr + models/nllb tree or flat. */
    private static void collect(DocumentFile dir, List<DocumentFile> out, int depth) {
        for (DocumentFile file : dir.listFiles()) {
            String name = file.getName();
            if (file.isDirectory()) {
                if (depth < MAX_FOLDER_DEPTH) {
                    collect(file, out, depth + 1);
                }
            } else if (name != null && ModelStore.folderFor(name) != null) {
                out.add(file);
            }
        }
    }

    private void copy(Context context, DocumentFile source, File modelsRoot) throws IOException {
        String name = source.getName();
        File folder = new File(modelsRoot, ModelStore.folderFor(name));
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Cannot create " + folder);
        }
        File target = new File(folder, name);
        // Copy under a temporary name so an interrupted import never leaves a truncated model behind.
        File partial = new File(folder, name + ".part");
        long total = source.length();
        long copied = 0;
        long nextReport = 0;
        try (InputStream in = context.getContentResolver().openInputStream(source.getUri());
             OutputStream out = new FileOutputStream(partial)) {
            if (in == null) {
                throw new IOException("Cannot read " + name);
            }
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                copied += read;
                if (copied >= nextReport) {
                    nextReport = copied + PROGRESS_STEP_BYTES;
                    // Strings come from the Activity: only it follows the in-app language on older Android versions.
                    String progress = getString(R.string.import_progress_fmt, name,
                            Formatter.formatShortFileSize(this, copied), Formatter.formatShortFileSize(this, total));
                    main.post(() -> progressView.setText(progress));
                }
            }
        } catch (IOException e) {
            partial.delete();
            throw e;
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace " + target);
        }
        if (!partial.renameTo(target)) {
            throw new IOException("Cannot write " + target);
        }
    }
}
