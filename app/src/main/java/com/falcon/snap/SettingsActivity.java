package com.falcon.snap;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.falcon.snap.ui.Dialogs;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Locale;

public class SettingsActivity extends BaseActivity {
    /**
     * Interface languages: one per res/values-xx folder, and the same list as res/xml/locales_config.xml.
     * Index 0 (empty tag) means "follow the system". Native names, so they are not translated.
     */
    private static final String[] APP_LANGUAGE_TAGS = {"", "en", "zh", "ru", "ja"};
    private static final String[] APP_LANGUAGE_NAMES = {"", "English", "中文", "Русский", "日本語"};

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

        ViewGroup app = SettingRows.addGroup(this, container);
        TextView appLanguageValue = SettingRows.addRow(this, app, R.string.setting_app_language, v -> chooseAppLanguage());
        appLanguageValue.setText(appLanguageLabel(currentAppLanguage()));
        appLanguageValue.setVisibility(View.VISIBLE);
        SettingRows.addRow(this, app, R.string.setting_models, v -> startActivity(new Intent(this, ModelsActivity.class)));

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
        ocrLanguageValue.setText(prefs.source().name(this));
        ocrLanguageValue.setVisibility(View.VISIBLE);
        targetLanguageValue.setText(prefs.target().name(this));
        targetLanguageValue.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- interface language

    /** Index into APP_LANGUAGE_TAGS of the language the user picked for the app; 0 = follow the system. */
    private static int currentAppLanguage() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        Locale locale = locales.isEmpty() ? null : locales.get(0);
        if (locale != null) {
            for (int i = 1; i < APP_LANGUAGE_TAGS.length; i++) {
                if (APP_LANGUAGE_TAGS[i].equals(locale.getLanguage())) {
                    return i;
                }
            }
        }
        return 0;
    }

    private String appLanguageLabel(int index) {
        return index == 0 ? getString(R.string.app_language_system) : APP_LANGUAGE_NAMES[index];
    }

    private void chooseAppLanguage() {
        String[] labels = new String[APP_LANGUAGE_TAGS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = appLanguageLabel(i);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_app_language)
                .setSingleChoiceItems(labels, currentAppLanguage(), (dialog, which) -> {
                    dialog.dismiss();
                    // AppCompat stores the choice and recreates every open screen in the new language.
                    AppCompatDelegate.setApplicationLocales(which == 0
                            ? LocaleListCompat.getEmptyLocaleList()
                            : LocaleListCompat.forLanguageTags(APP_LANGUAGE_TAGS[which]));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
