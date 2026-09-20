package com.falcon.snap;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.falcon.snap.data.HistoryDb;
import com.falcon.snap.data.HistoryEntry;
import com.falcon.snap.engine.BitmapUtils;
import com.falcon.snap.engine.ModelsMissingException;
import com.falcon.snap.engine.OcrEngine;
import com.falcon.snap.engine.OverlayRenderer;
import com.falcon.snap.engine.TranslatorEngine;
import com.falcon.snap.model.Language;
import com.falcon.snap.model.TextBlockItem;
import com.falcon.snap.ui.BlockImageView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Processing + result screen. Runs the pipeline
 * load image -> OCR -> translate -> paint translation over the photo
 * and then shows the replaced image with the original / translated text underneath.
 *
 * Opened either with an image Uri as intent data, or with {@link #EXTRA_HISTORY_ID}.
 */
public class ResultActivity extends BaseActivity {
    public static final String EXTRA_HISTORY_ID = "history_id";

    /** Longest image side we process: enough detail for OCR, small enough to keep three copies in memory. */
    private static final int MAX_IMAGE_DIM = 1920;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private BlockImageView imageView;
    private View processingOverlay;
    private TextView processingText;
    private View resultSheet;
    private ImageView statusIcon;
    private TextView statusText;
    private TextView labelOriginal;
    private TextView labelTranslated;
    private TextView textOriginal;
    private TextView textTranslated;
    private TextView compareToggle;
    private View editButton;
    private View saveButton;

    /** Bumped whenever processing (re)starts, so callbacks from an abandoned run can be ignored. */
    private int runId;
    private boolean processing;
    private boolean showingTranslated = true;
    /** Failure of the current run, shown to the user once the result sheet is visible. */
    private Exception pendingError;

    private String sourceBeforeEdit;
    private String targetBeforeEdit;
    private ActivityResultLauncher<Intent> editLauncher;
    private ActivityResultLauncher<String> galleryLauncher;

    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;
    private Language pendingSpeechLanguage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        editLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> onEditFinished(result.getResultCode() == RESULT_OK));
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                startFromUri(uri);
            }
        });

        imageView = findViewById(R.id.result_image);
        processingOverlay = findViewById(R.id.processing_overlay);
        processingText = findViewById(R.id.processing_text);
        resultSheet = findViewById(R.id.result_sheet);
        statusIcon = findViewById(R.id.status_icon);
        statusText = findViewById(R.id.status_text);
        labelOriginal = findViewById(R.id.label_original);
        labelTranslated = findViewById(R.id.label_translated);
        textOriginal = findViewById(R.id.text_original);
        textTranslated = findViewById(R.id.text_translated);
        compareToggle = findViewById(R.id.compare_toggle);
        editButton = findViewById(R.id.btn_edit);
        saveButton = findViewById(R.id.btn_save);

        findViewById(R.id.top_back).setOnClickListener(v -> finish());
        findViewById(R.id.result_gallery).setOnClickListener(v -> {
            if (!processing) {
                galleryLauncher.launch("image/*");
            }
        });
        editButton.setOnClickListener(v -> openEdit(0));
        saveButton.setOnClickListener(v -> save());
        imageView.setOnBlockTapListener(this::openEdit);
        compareToggle.setOnClickListener(v -> {
            showingTranslated = !showingTranslated;
            updateImage();
        });
        findViewById(R.id.speak_original).setOnClickListener(v ->
                speak(TextBlockItem.joinSource(Session.blocks), Session.source));
        findViewById(R.id.speak_translated).setOnClickListener(v ->
                speak(TextBlockItem.joinTranslated(Session.blocks), Session.target));
        findViewById(R.id.box_original).setOnLongClickListener(v -> copy(textOriginal.getText()));
        findViewById(R.id.box_translated).setOnLongClickListener(v -> copy(textTranslated.getText()));
        bindLanguageBar();

        long historyId = getIntent().getLongExtra(EXTRA_HISTORY_ID, -1);
        Uri image = getIntent().getData();
        if (historyId >= 0) {
            startFromHistory(historyId);
        } else if (image != null) {
            startFromUri(image);
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        runId++;
        io.shutdown();
        if (tts != null) {
            tts.shutdown();
        }
        if (isFinishing() && Session.owner == System.identityHashCode(this)) {
            Session.reset();
        }
    }

    // ---------------------------------------------------------------- pipeline

    private void startFromUri(Uri uri) {
        int run = beginRun(R.string.processing_loading);
        Session.reset();
        Session.owner = System.identityHashCode(this);
        showingTranslated = prefs.autoReplace();
        imageView.setImageDrawable(null);
        Context appContext = getApplicationContext();
        io.execute(() -> {
            Bitmap bitmap = BitmapUtils.decodeScaled(appContext, uri, MAX_IMAGE_DIM);
            main.post(() -> {
                if (isStale(run)) {
                    return;
                }
                if (bitmap == null) {
                    showResult(R.string.status_load_failed);
                    return;
                }
                Session.original = bitmap;
                Session.source = prefs.source();
                Session.target = prefs.target();
                imageView.setImageBitmap(bitmap);
                runOcr(run);
            });
        });
    }

    private void startFromHistory(long historyId) {
        int run = beginRun(R.string.processing_loading);
        Session.reset();
        Session.owner = System.identityHashCode(this);
        showingTranslated = true;
        HistoryDb db = HistoryDb.get(this);
        io.execute(() -> {
            LoadedEntry loaded = LoadedEntry.load(db, historyId);
            main.post(() -> {
                if (isStale(run)) {
                    return;
                }
                if (loaded == null) {
                    showResult(R.string.status_load_failed);
                    return;
                }
                Session.original = loaded.original;
                Session.rendered = loaded.rendered;
                Session.blocks = loaded.blocks;
                Session.source = Language.byCode(loaded.entry.sourceLang);
                Session.target = Language.byCode(loaded.entry.targetLang);
                Session.historyId = loaded.entry.id;
                Session.fromHistory = true;
                showResult(R.string.status_completed);
            });
        });
    }

    private void runOcr(int run) {
        showProcessing(R.string.processing_extracting);
        OcrEngine.recognize(this, Session.original, Session.source, new OcrEngine.Callback() {
            @Override
            public void onSuccess(List<TextBlockItem> blocks) {
                if (isStale(run)) {
                    return;
                }
                Session.blocks = blocks;
                if (blocks.isEmpty()) {
                    showResult(R.string.status_no_text);
                } else {
                    runTranslate(run, true);
                }
            }

            @Override
            public void onError(Exception e) {
                if (!isStale(run)) {
                    Session.blocks = new ArrayList<>();
                    pendingError = e;
                    showResult(R.string.status_ocr_failed);
                }
            }
        });
    }

    /** @param all re-translate every block (language changed) rather than only the untranslated ones */
    private void runTranslate(int run, boolean all) {
        showProcessing(R.string.processing_translating);
        TranslatorEngine.translate(this, Session.blocks, Session.source, Session.target, all, new TranslatorEngine.Callback() {
            @Override
            public void onLoadingModel() {
                if (!isStale(run)) {
                    processingText.setText(R.string.processing_loading_model);
                }
            }

            @Override
            public void onProgress(int done, int total) {
                if (!isStale(run)) {
                    processingText.setText(getString(R.string.processing_translating_fmt, done, total));
                }
            }

            @Override
            public void onDone() {
                if (!isStale(run)) {
                    render(run, R.string.status_completed);
                }
            }

            @Override
            public void onError(Exception e) {
                if (!isStale(run)) {
                    // Still show the recognized text; the reason is reported once the screen is up.
                    pendingError = e;
                    render(run, R.string.status_failed);
                }
            }
        });
    }

    private void render(int run, int statusRes) {
        showProcessing(R.string.processing_rendering);
        Bitmap original = Session.original;
        List<TextBlockItem> blocks = Session.blocks;
        io.execute(() -> {
            Bitmap rendered;
            try {
                rendered = OverlayRenderer.render(original, blocks);
            } catch (OutOfMemoryError e) {
                rendered = original;
            }
            Bitmap result = rendered;
            main.post(() -> {
                if (!isStale(run)) {
                    Session.rendered = result;
                    showResult(statusRes);
                }
            });
        });
    }

    private int beginRun(int messageRes) {
        runId++;
        showProcessing(messageRes);
        return runId;
    }

    private boolean isStale(int run) {
        return run != runId || isFinishing() || isDestroyed();
    }

    // ---------------------------------------------------------------- UI state

    private void showProcessing(int messageRes) {
        processing = true;
        processingText.setText(messageRes);
        processingOverlay.setVisibility(View.VISIBLE);
        resultSheet.setVisibility(View.GONE);
        compareToggle.setVisibility(View.GONE);
    }

    private void showResult(int statusRes) {
        processing = false;
        processingOverlay.setVisibility(View.GONE);
        resultSheet.setVisibility(View.VISIBLE);

        boolean completed = statusRes == R.string.status_completed;
        statusText.setText(statusRes);
        statusIcon.setImageResource(completed ? R.drawable.ic_check_circle : R.drawable.ic_info);
        ImageViewCompat.setImageTintList(statusIcon, ContextCompat.getColorStateList(this,
                completed ? R.color.brand_red : R.color.text_secondary));

        Language source = Session.source != null ? Session.source : prefs.source();
        Language target = Session.target != null ? Session.target : prefs.target();
        labelOriginal.setText(getString(R.string.label_original_fmt, source.shortName));
        labelTranslated.setText(getString(R.string.label_translated_fmt, target.shortName));
        boolean hasText = !Session.blocks.isEmpty();
        textOriginal.setText(hasText ? TextBlockItem.joinSource(Session.blocks) : "—");
        textTranslated.setText(hasText ? TextBlockItem.joinTranslated(Session.blocks) : "—");

        // A history entry keeps the languages it was made with, so its language bar is hidden.
        findViewById(R.id.lang_bar).setVisibility(Session.fromHistory ? View.INVISIBLE : View.VISIBLE);
        editButton.setEnabled(Session.canEdit());
        saveButton.setEnabled(hasText && Session.rendered != null);
        imageView.setBlocks(Session.canEdit() ? Session.blocks : new ArrayList<>());
        updateImage();
        reportPendingError();
    }

    /** Explains an OCR / translation failure. Missing models get a shortcut to the model manager. */
    private void reportPendingError() {
        Exception error = pendingError;
        pendingError = null;
        if (error == null) {
            return;
        }
        if (error instanceof ModelsMissingException) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.models_missing_title)
                    .setMessage(getString(R.string.models_missing_message, error.getMessage()))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.open_model_manager,
                            (dialog, which) -> startActivity(new Intent(this, ModelsActivity.class)))
                    .show();
        } else {
            // Models are user-supplied and may be retrained, so show the real reason, not a generic apology.
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.model_error_title)
                    .setMessage(String.valueOf(error.getMessage() != null ? error.getMessage() : error))
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }
    }

    /** Shows the replaced or the untouched photo, and the toggle between them when both exist. */
    private void updateImage() {
        boolean hasOverlay = false;
        for (TextBlockItem block : Session.blocks) {
            hasOverlay |= block.hasOverlay();
        }
        boolean canCompare = hasOverlay && Session.original != null && Session.rendered != null;
        compareToggle.setVisibility(canCompare ? View.VISIBLE : View.GONE);
        compareToggle.setText(showingTranslated ? R.string.show_original : R.string.show_translated);

        Bitmap bitmap;
        if (canCompare) {
            bitmap = showingTranslated ? Session.rendered : Session.original;
        } else {
            bitmap = Session.rendered != null ? Session.rendered : Session.original;
        }
        imageView.setImageBitmap(bitmap);
    }

    @Override
    protected boolean canChangeLanguages() {
        // Blocks are translated in place; switching languages mid-run would race with that.
        return !processing;
    }

    @Override
    protected void onLanguagesChanged() {
        super.onLanguagesChanged();
        if (Session.original == null || Session.source == null) {
            return;
        }
        Language source = prefs.source();
        boolean sourceChanged = !source.code.equals(Session.source.code);
        Session.source = source;
        Session.target = prefs.target();
        int run = beginRun(R.string.processing_translating);
        if (sourceChanged || Session.blocks.isEmpty()) {
            // A different source language may need a different recognizer.
            runOcr(run);
        } else {
            runTranslate(run, true);
        }
    }

    // ---------------------------------------------------------------- edit

    private void openEdit(int blockIndex) {
        if (processing || !Session.canEdit()) {
            return;
        }
        sourceBeforeEdit = prefs.source().code;
        targetBeforeEdit = prefs.target().code;
        Intent intent = new Intent(this, EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_BLOCK_INDEX, blockIndex);
        editLauncher.launch(intent);
    }

    private void onEditFinished(boolean applied) {
        refreshLanguageBar();
        boolean languagesChanged = !prefs.source().code.equals(sourceBeforeEdit)
                || !prefs.target().code.equals(targetBeforeEdit);
        if (!Session.canEdit() || (!applied && !languagesChanged)) {
            return;
        }
        if (languagesChanged) {
            // The user told us what language the text is, so keep their text and skip a new OCR pass.
            Session.source = prefs.source();
            Session.target = prefs.target();
        }
        runTranslate(beginRun(R.string.processing_translating), languagesChanged);
    }

    // ---------------------------------------------------------------- save

    private void save() {
        if (processing || Session.rendered == null) {
            return;
        }
        saveButton.setEnabled(false);
        Bitmap original = prefs.saveOriginal() ? Session.original : null;
        Bitmap rendered = Session.rendered;
        long existingId = Session.historyId;
        HistoryEntry draft = new HistoryEntry();
        draft.sourceLang = Session.source.code;
        draft.targetLang = Session.target.code;
        draft.sourceText = TextBlockItem.joinSource(Session.blocks);
        draft.translatedText = TextBlockItem.joinTranslated(Session.blocks);
        draft.blocksJson = TextBlockItem.listToJson(Session.blocks);
        HistoryDb db = HistoryDb.get(this);
        Context appContext = getApplicationContext();
        int run = runId;

        io.execute(() -> {
            long savedId = -1;
            boolean exported = false;
            try {
                HistoryEntry entry = existingId >= 0 ? db.find(existingId) : null;
                if (entry == null) {
                    entry = new HistoryEntry();
                    entry.createdAt = System.currentTimeMillis();
                }
                String stamp = String.valueOf(System.currentTimeMillis());
                File renderedFile = entry.renderedPath != null
                        ? new File(entry.renderedPath) : new File(db.imageDir(), stamp + "_t.jpg");
                BitmapUtils.saveJpeg(rendered, renderedFile);
                entry.renderedPath = renderedFile.getAbsolutePath();
                if (entry.originalPath == null && original != null) {
                    File originalFile = new File(db.imageDir(), stamp + "_o.jpg");
                    BitmapUtils.saveJpeg(original, originalFile);
                    entry.originalPath = originalFile.getAbsolutePath();
                }
                entry.sourceLang = draft.sourceLang;
                entry.targetLang = draft.targetLang;
                entry.sourceText = draft.sourceText;
                entry.translatedText = draft.translatedText;
                entry.blocksJson = draft.blocksJson;
                savedId = db.save(entry);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        BitmapUtils.exportToGallery(appContext, rendered, "SnapTranslate_" + stamp + ".jpg");
                        exported = true;
                    } catch (IOException | RuntimeException e) {
                        // History is the primary copy; the gallery export is best effort.
                    }
                }
            } catch (IOException | RuntimeException e) {
                savedId = -1;
            }
            long id = savedId;
            boolean inGallery = exported;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                saveButton.setEnabled(true);
                if (id < 0) {
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                if (run == runId) {
                    Session.historyId = id;
                }
                Toast.makeText(this, inGallery ? R.string.saved_to_history_and_gallery : R.string.saved_to_history,
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ---------------------------------------------------------------- speech / clipboard

    private void speak(String text, Language language) {
        if (TextUtils.isEmpty(text) || language == null) {
            return;
        }
        if (tts == null) {
            pendingSpeech = text;
            pendingSpeechLanguage = language;
            tts = new TextToSpeech(getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady && pendingSpeech != null) {
                    speakNow(pendingSpeech, pendingSpeechLanguage);
                } else if (!ttsReady) {
                    Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_SHORT).show();
                }
                pendingSpeech = null;
            });
        } else if (ttsReady) {
            speakNow(text, language);
        }
    }

    private void speakNow(String text, Language language) {
        int availability = tts.setLanguage(language.locale());
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "snap");
    }

    private boolean copy(CharSequence text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && !TextUtils.isEmpty(text)) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /** A history entry with its bitmaps decoded, loaded off the UI thread. */
    private static final class LoadedEntry {
        HistoryEntry entry;
        Bitmap original;
        Bitmap rendered;
        List<TextBlockItem> blocks;

        /** Returns null when the entry or its image is gone. */
        static LoadedEntry load(HistoryDb db, long id) {
            LoadedEntry loaded = new LoadedEntry();
            loaded.entry = db.find(id);
            if (loaded.entry == null) {
                return null;
            }
            loaded.blocks = TextBlockItem.listFromJson(loaded.entry.blocksJson);
            try {
                if (loaded.entry.originalPath != null) {
                    loaded.original = BitmapFactory.decodeFile(loaded.entry.originalPath);
                }
                if (loaded.original != null) {
                    // Re-render from the original so the entry stays editable.
                    loaded.rendered = OverlayRenderer.render(loaded.original, loaded.blocks);
                } else if (loaded.entry.renderedPath != null) {
                    loaded.rendered = BitmapFactory.decodeFile(loaded.entry.renderedPath);
                }
            } catch (OutOfMemoryError e) {
                return null;
            }
            return loaded.rendered == null ? null : loaded;
        }
    }
}
