package com.falcon.snap.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.falcon.snap.model.Language;

import java.util.ArrayList;
import java.util.List;

/** User settings: the language pair, recently used languages and the two behavior switches. */
public final class Prefs {
    private static final String FILE = "snap_prefs";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_TARGET = "target";
    private static final String KEY_RECENTS = "recents";
    private static final String KEY_AUTO_REPLACE = "auto_replace";
    private static final String KEY_SAVE_ORIGINAL = "save_original";
    private static final int MAX_RECENTS = 4;

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public Language source() {
        return Language.byCode(prefs.getString(KEY_SOURCE, "en"));
    }

    public Language target() {
        return Language.byCode(prefs.getString(KEY_TARGET, "zh"));
    }

    public void setLanguages(Language source, Language target) {
        prefs.edit().putString(KEY_SOURCE, source.code).putString(KEY_TARGET, target.code).apply();
    }

    /** Most recently used first. */
    public List<Language> recents() {
        List<Language> result = new ArrayList<>();
        String stored = prefs.getString(KEY_RECENTS, "en,zh");
        for (String code : TextUtils.split(stored, ",")) {
            Language language = Language.byCode(code);
            if (language.code.equals(code) && !result.contains(language)) {
                result.add(language);
            }
        }
        return result;
    }

    public void addRecent(Language language) {
        List<String> codes = new ArrayList<>();
        codes.add(language.code);
        for (Language recent : recents()) {
            if (!recent.code.equals(language.code) && codes.size() < MAX_RECENTS) {
                codes.add(recent.code);
            }
        }
        prefs.edit().putString(KEY_RECENTS, TextUtils.join(",", codes)).apply();
    }

    /** When on, the result screen opens showing the image with the text already replaced. */
    public boolean autoReplace() {
        return prefs.getBoolean(KEY_AUTO_REPLACE, true);
    }

    public void setAutoReplace(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_REPLACE, value).apply();
    }

    /** When on, history keeps the untouched photo too, so saved entries stay editable. */
    public boolean saveOriginal() {
        return prefs.getBoolean(KEY_SAVE_ORIGINAL, true);
    }

    public void setSaveOriginal(boolean value) {
        prefs.edit().putBoolean(KEY_SAVE_ORIGINAL, value).apply();
    }
}
