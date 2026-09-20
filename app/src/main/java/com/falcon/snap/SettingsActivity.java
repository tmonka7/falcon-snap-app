package com.falcon.snap;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.falcon.snap.ui.Dialogs;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends BaseActivity {
    private TextView ocrLanguageValue;
    private TextView targetLanguageValue;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setupTopBar(R.string.title_settings);

        ViewGroup container = findViewById(R.id.settings_container);

        ViewGroup languages = SettingRows.addGroup(this, container);
        ocrLanguageValue = SettingRows.addRow(this, languages, R.string.setting_ocr_language, v -> pickLanguage(true));
        targetLanguageValue = SettingRows.addRow(this, languages, R.string.setting_target_language, v -> pickLanguage(false));

        ViewGroup behavior = SettingRows.addGroup(this, container);
        addSwitch(behavior, R.string.setting_auto_replace, prefs.autoReplace(),
                (button, checked) -> prefs.setAutoReplace(checked));
        addSwitch(behavior, R.string.setting_save_original, prefs.saveOriginal(),
                (button, checked) -> prefs.setSaveOriginal(checked));

        ViewGroup more = SettingRows.addGroup(this, container);
        SettingRows.addRow(this, more, R.string.nav_help, v -> Dialogs.showHelp(this));
        SettingRows.addRow(this, more, R.string.nav_about, v -> startActivity(new Intent(this, AboutActivity.class)));

        showLanguages();
    }

    @Override
    protected void onLanguagesChanged() {
        super.onLanguagesChanged();
        showLanguages();
    }

    private void showLanguages() {
        ocrLanguageValue.setText(prefs.source().name);
        ocrLanguageValue.setVisibility(View.VISIBLE);
        targetLanguageValue.setText(prefs.target().name);
        targetLanguageValue.setVisibility(View.VISIBLE);
    }

    private void addSwitch(ViewGroup group, int titleRes, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        SettingRows.addDividerIfNeeded(this, group);
        View row = getLayoutInflater().inflate(R.layout.item_setting_switch, group, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(titleRes);
        SwitchMaterial toggle = row.findViewById(R.id.row_switch);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.setOnClickListener(v -> toggle.toggle());
        group.addView(row);
    }
}
