package com.falcon.snap;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.falcon.snap.data.Prefs;

/**
 * Shared plumbing: the red top bar and the "source ⇄ target" language bar. A screen opts in by
 * including views with the ids top_back/top_title or lang_source/lang_swap/lang_target.
 */
public abstract class BaseActivity extends AppCompatActivity {
    protected Prefs prefs;
    private ActivityResultLauncher<Intent> languageLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        languageLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                onLanguagesChanged();
            }
        });
    }

    protected void setupTopBar(int titleRes) {
        ((TextView) findViewById(R.id.top_title)).setText(titleRes);
        findViewById(R.id.top_back).setOnClickListener(v -> finish());
    }

    protected void setTopAction(int iconRes, int descriptionRes, View.OnClickListener listener) {
        ImageButton action = findViewById(R.id.top_action);
        action.setImageResource(iconRes);
        action.setContentDescription(getString(descriptionRes));
        action.setOnClickListener(listener);
        action.setVisibility(View.VISIBLE);
    }

    /** Wires up the language bar in the current layout and shows the current pair. */
    protected void bindLanguageBar() {
        findViewById(R.id.lang_source).setOnClickListener(v -> pickLanguage(true));
        findViewById(R.id.lang_target).setOnClickListener(v -> pickLanguage(false));
        findViewById(R.id.lang_swap).setOnClickListener(v -> swapLanguages());
        refreshLanguageBar();
    }

    protected void refreshLanguageBar() {
        TextView source = findViewById(R.id.lang_source);
        TextView target = findViewById(R.id.lang_target);
        if (source != null && target != null) {
            source.setText(prefs.source().label());
            target.setText(prefs.target().label());
        }
    }

    /** Screens that are busy with the current pair can temporarily refuse language changes. */
    protected boolean canChangeLanguages() {
        return true;
    }

    protected void pickLanguage(boolean pickSource) {
        if (!canChangeLanguages()) {
            return;
        }
        Intent intent = new Intent(this, LanguageSelectActivity.class);
        intent.putExtra(LanguageSelectActivity.EXTRA_PICK_SOURCE, pickSource);
        languageLauncher.launch(intent);
    }

    private void swapLanguages() {
        if (!canChangeLanguages()) {
            return;
        }
        prefs.setLanguages(prefs.target(), prefs.source());
        onLanguagesChanged();
    }

    /** Called after the user changed the language pair. The new pair is already in prefs. */
    protected void onLanguagesChanged() {
        refreshLanguageBar();
    }
}
