package com.falcon.snap;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.falcon.snap.model.TextBlockItem;
import com.falcon.snap.ui.BlockImageView;

import java.util.List;

/**
 * Lets the user correct the recognized text and/or the translation, one text block at a time.
 * Changes are written back to the {@link Session}; the result screen then re-translates whatever
 * needs it and repaints the image.
 */
public class EditActivity extends BaseActivity {
    public static final String EXTRA_BLOCK_INDEX = "block_index";

    private BlockImageView imageView;
    private EditText originalInput;
    private EditText translatedInput;
    private TextView counter;

    private List<TextBlockItem> blocks;
    /** Working copies, so leaving with Back discards everything. */
    private String[] sources;
    private String[] translations;
    private int index;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.canEdit()) {
            // The process was restarted and the session (bitmaps, blocks) is gone.
            finish();
            return;
        }
        setContentView(R.layout.activity_edit);
        setupTopBar(R.string.title_edit);
        setTopAction(R.drawable.ic_check, R.string.cd_apply, v -> apply());

        imageView = findViewById(R.id.edit_image);
        originalInput = findViewById(R.id.edit_original);
        translatedInput = findViewById(R.id.edit_translated);
        counter = findViewById(R.id.block_counter);

        blocks = Session.blocks;
        sources = new String[blocks.size()];
        translations = new String[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            sources[i] = blocks.get(i).sourceText;
            translations[i] = orEmpty(blocks.get(i).translatedText);
        }
        index = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_BLOCK_INDEX, 0), blocks.size() - 1));

        imageView.setImageBitmap(Session.rendered != null ? Session.rendered : Session.original);
        imageView.setBlocks(blocks);

        findViewById(R.id.block_nav).setVisibility(blocks.size() > 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.block_prev).setOnClickListener(v -> show(index - 1));
        findViewById(R.id.block_next).setOnClickListener(v -> show(index + 1));
        findViewById(R.id.clear_original).setOnClickListener(v -> originalInput.setText(""));
        findViewById(R.id.clear_translated).setOnClickListener(v -> translatedInput.setText(""));
        findViewById(R.id.btn_apply).setOnClickListener(v -> apply());

        // A history entry keeps the languages it was made with.
        findViewById(R.id.lang_bar).setVisibility(Session.fromHistory ? View.GONE : View.VISIBLE);
        bindLanguageBar();
        show(index);
    }

    /** Stores what is in the fields, then moves to another block (wrapping around). */
    private void show(int newIndex) {
        keepFields();
        index = (newIndex + blocks.size()) % blocks.size();
        originalInput.setText(sources[index]);
        translatedInput.setText(translations[index]);
        counter.setText(getString(R.string.block_counter_fmt, index + 1, blocks.size()));
        imageView.setHighlighted(index);
    }

    private void keepFields() {
        // Nothing to keep before the first block has been shown.
        if (counter.length() > 0) {
            sources[index] = originalInput.getText().toString();
            translations[index] = translatedInput.getText().toString();
        }
    }

    private void apply() {
        keepFields();
        for (int i = 0; i < blocks.size(); i++) {
            TextBlockItem block = blocks.get(i);
            String source = sources[i].trim();
            String translation = translations[i].trim();
            boolean sourceChanged = !source.equals(block.sourceText);
            boolean translationChanged = !translation.equals(orEmpty(block.translatedText).trim());

            block.sourceText = source;
            if (source.isEmpty()) {
                // Text removed: leave this part of the photo as it was.
                block.translatedText = "";
            } else if (translationChanged && !translation.isEmpty()) {
                block.translatedText = translation;
            } else if (sourceChanged || translation.isEmpty()) {
                // null = "translate this again" (see TranslatorEngine).
                block.translatedText = null;
            }
        }
        setResult(RESULT_OK);
        finish();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
