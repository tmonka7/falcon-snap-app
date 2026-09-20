package com.falcon.snap.model;

import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A language the app can translate from and/or to.
 *
 * Every language here can be a translation target. Only languages whose script has an ML Kit
 * text recognizer (script != SCRIPT_NONE) can be a source, because the source has to be OCR'd.
 */
public final class Language {
    public static final int SCRIPT_NONE = 0;
    public static final int SCRIPT_LATIN = 1;
    public static final int SCRIPT_CHINESE = 2;
    public static final int SCRIPT_JAPANESE = 3;
    public static final int SCRIPT_KOREAN = 4;
    public static final int SCRIPT_DEVANAGARI = 5;

    public static final String TRADITIONAL_CHINESE = "zh-TW";

    /** App-level code, stored in prefs and history. */
    public final String code;
    /** ML Kit TranslateLanguage code. Differs from {@link #code} only for Traditional Chinese. */
    public final String mlKitCode;
    /** English name, shown in lists. */
    public final String name;
    /** Native name, shown in the compact language bars. */
    public final String shortName;
    /** ISO country code used to build the flag emoji. */
    private final String country;
    public final int script;

    private Language(String code, String mlKitCode, String name, String shortName, String country, int script) {
        this.code = code;
        this.mlKitCode = mlKitCode;
        this.name = name;
        this.shortName = shortName;
        this.country = country;
        this.script = script;
    }

    private static final Language[] CATALOG = {
            new Language("en", "en", "English", "English", "GB", SCRIPT_LATIN),
            new Language("zh", "zh", "Chinese (Simplified)", "中文", "CN", SCRIPT_CHINESE),
            new Language(TRADITIONAL_CHINESE, "zh", "Chinese (Traditional)", "繁體中文", "CN", SCRIPT_CHINESE),
            new Language("ja", "ja", "Japanese", "日本語", "JP", SCRIPT_JAPANESE),
            new Language("ko", "ko", "Korean", "한국어", "KR", SCRIPT_KOREAN),
            new Language("fr", "fr", "French", "Français", "FR", SCRIPT_LATIN),
            new Language("de", "de", "German", "Deutsch", "DE", SCRIPT_LATIN),
            new Language("es", "es", "Spanish", "Español", "ES", SCRIPT_LATIN),
            new Language("it", "it", "Italian", "Italiano", "IT", SCRIPT_LATIN),
            new Language("pt", "pt", "Portuguese", "Português", "PT", SCRIPT_LATIN),
            new Language("nl", "nl", "Dutch", "Nederlands", "NL", SCRIPT_LATIN),
            new Language("pl", "pl", "Polish", "Polski", "PL", SCRIPT_LATIN),
            new Language("sv", "sv", "Swedish", "Svenska", "SE", SCRIPT_LATIN),
            new Language("tr", "tr", "Turkish", "Türkçe", "TR", SCRIPT_LATIN),
            new Language("vi", "vi", "Vietnamese", "Tiếng Việt", "VN", SCRIPT_LATIN),
            new Language("id", "id", "Indonesian", "Indonesia", "ID", SCRIPT_LATIN),
            new Language("ms", "ms", "Malay", "Melayu", "MY", SCRIPT_LATIN),
            new Language("tl", "tl", "Filipino", "Filipino", "PH", SCRIPT_LATIN),
            new Language("hi", "hi", "Hindi", "हिन्दी", "IN", SCRIPT_DEVANAGARI),
            new Language("ru", "ru", "Russian", "Русский", "RU", SCRIPT_NONE),
            new Language("uk", "uk", "Ukrainian", "Українська", "UA", SCRIPT_NONE),
            new Language("th", "th", "Thai", "ไทย", "TH", SCRIPT_NONE),
            new Language("ar", "ar", "Arabic", "العربية", "SA", SCRIPT_NONE),
    };

    /** All languages usable on this device. */
    public static List<Language> all() {
        List<Language> result = new ArrayList<>();
        for (Language language : CATALOG) {
            // Traditional Chinese relies on the ICU transliterator, which is API 29+.
            if (TRADITIONAL_CHINESE.equals(language.code) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                continue;
            }
            result.add(language);
        }
        return result;
    }

    /** Looks a language up by its app-level code; unknown codes fall back to English. */
    public static Language byCode(String code) {
        for (Language language : all()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        return CATALOG[0];
    }

    public boolean canBeSource() {
        return script != SCRIPT_NONE;
    }

    /** Chinese and Japanese lines are joined without a space when a block is reassembled. */
    public boolean joinsWithoutSpaces() {
        return script == SCRIPT_CHINESE || script == SCRIPT_JAPANESE;
    }

    public boolean isTraditionalChinese() {
        return TRADITIONAL_CHINESE.equals(code);
    }

    /** Flag emoji built from the two regional-indicator code points of the country code. */
    public String flag() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < country.length(); i++) {
            sb.appendCodePoint(0x1F1E6 + (country.charAt(i) - 'A'));
        }
        return sb.toString();
    }

    /** "flag + native name", used by the language bars. */
    public String label() {
        return flag() + "  " + shortName;
    }

    public Locale locale() {
        if (isTraditionalChinese()) {
            return Locale.TRADITIONAL_CHINESE;
        }
        return Locale.forLanguageTag(code);
    }
}
