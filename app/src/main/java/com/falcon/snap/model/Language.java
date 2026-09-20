package com.falcon.snap.model;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A language the app can translate from and to.
 *
 * Translation: every language maps to an NLLB-200 language token ({@link #nllbCode}).
 * OCR: PP-OCRv5 has one recognition model per script family; {@link #ocrKeys} lists the ones that
 * can read this language, best first. A language can be used as a source when one of them is
 * installed as {@code models/ocr/rec_KEY.onnx} (see MODELS.md).
 */
public final class Language {
    /** PP-OCRv5_mobile_rec: Simplified and Traditional Chinese, English, Japanese. */
    public static final String OCR_MAIN = "main";
    public static final String OCR_KOREAN = "korean";
    public static final String OCR_LATIN = "latin";
    /** East Slavic: Russian, Ukrainian, Belarusian. */
    public static final String OCR_ESLAV = "eslav";
    public static final String OCR_CYRILLIC = "cyrillic";
    public static final String OCR_THAI = "th";
    public static final String OCR_ARABIC = "arabic";
    public static final String OCR_DEVANAGARI = "devanagari";

    /** App-level code, stored in prefs and history. */
    public final String code;
    /** NLLB-200 (FLORES-200) language token, e.g. "eng_Latn". */
    public final String nllbCode;
    /** English name, shown in lists. */
    public final String name;
    /** Native name, shown in the compact language bars. */
    public final String shortName;
    /** ISO country code used to build the flag emoji. */
    private final String country;
    /** Recognition models able to read this language, in order of preference. */
    public final String[] ocrKeys;

    private Language(String code, String nllbCode, String name, String shortName, String country, String... ocrKeys) {
        this.code = code;
        this.nllbCode = nllbCode;
        this.name = name;
        this.shortName = shortName;
        this.country = country;
        this.ocrKeys = ocrKeys;
    }

    private static final Language[] CATALOG = {
            new Language("en", "eng_Latn", "English", "English", "GB", OCR_MAIN, OCR_LATIN),
            new Language("zh", "zho_Hans", "Chinese (Simplified)", "中文", "CN", OCR_MAIN),
            new Language("zh-TW", "zho_Hant", "Chinese (Traditional)", "繁體中文", "CN", OCR_MAIN),
            new Language("ja", "jpn_Jpan", "Japanese", "日本語", "JP", OCR_MAIN),
            new Language("ko", "kor_Hang", "Korean", "한국어", "KR", OCR_KOREAN),
            // The main model reads unaccented Latin text, so it is a usable fallback for these.
            new Language("fr", "fra_Latn", "French", "Français", "FR", OCR_LATIN, OCR_MAIN),
            new Language("de", "deu_Latn", "German", "Deutsch", "DE", OCR_LATIN, OCR_MAIN),
            new Language("es", "spa_Latn", "Spanish", "Español", "ES", OCR_LATIN, OCR_MAIN),
            new Language("it", "ita_Latn", "Italian", "Italiano", "IT", OCR_LATIN, OCR_MAIN),
            new Language("pt", "por_Latn", "Portuguese", "Português", "PT", OCR_LATIN, OCR_MAIN),
            new Language("nl", "nld_Latn", "Dutch", "Nederlands", "NL", OCR_LATIN, OCR_MAIN),
            new Language("pl", "pol_Latn", "Polish", "Polski", "PL", OCR_LATIN, OCR_MAIN),
            new Language("sv", "swe_Latn", "Swedish", "Svenska", "SE", OCR_LATIN, OCR_MAIN),
            new Language("tr", "tur_Latn", "Turkish", "Türkçe", "TR", OCR_LATIN, OCR_MAIN),
            new Language("vi", "vie_Latn", "Vietnamese", "Tiếng Việt", "VN", OCR_LATIN, OCR_MAIN),
            new Language("id", "ind_Latn", "Indonesian", "Indonesia", "ID", OCR_LATIN, OCR_MAIN),
            new Language("ms", "zsm_Latn", "Malay", "Melayu", "MY", OCR_LATIN, OCR_MAIN),
            new Language("tl", "tgl_Latn", "Filipino", "Filipino", "PH", OCR_LATIN, OCR_MAIN),
            new Language("hi", "hin_Deva", "Hindi", "हिन्दी", "IN", OCR_DEVANAGARI),
            new Language("ru", "rus_Cyrl", "Russian", "Русский", "RU", OCR_ESLAV, OCR_CYRILLIC),
            new Language("uk", "ukr_Cyrl", "Ukrainian", "Українська", "UA", OCR_ESLAV, OCR_CYRILLIC),
            new Language("th", "tha_Thai", "Thai", "ไทย", "TH", OCR_THAI),
            new Language("ar", "arb_Arab", "Arabic", "العربية", "SA", OCR_ARABIC),
    };

    public static List<Language> all() {
        return new ArrayList<>(Arrays.asList(CATALOG));
    }

    /** Every recognition-model key the catalog can use, for the model status screen. */
    public static List<String> allOcrKeys() {
        List<String> keys = new ArrayList<>();
        for (Language language : CATALOG) {
            for (String key : language.ocrKeys) {
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /** Names of the languages a recognition model serves, e.g. "Russian, Ukrainian". */
    public static String languagesFor(String ocrKey) {
        List<String> names = new ArrayList<>();
        for (Language language : CATALOG) {
            if (language.ocrKeys[0].equals(ocrKey)) {
                names.add(language.name);
            }
        }
        return TextUtils.join(", ", names);
    }

    /** Looks a language up by its app-level code; unknown codes fall back to English. */
    public static Language byCode(String code) {
        for (Language language : CATALOG) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        return CATALOG[0];
    }

    /** Scripts written without spaces: lines (and translated sentences) are joined directly. */
    public boolean joinsWithoutSpaces() {
        return code.equals("zh") || code.equals("zh-TW") || code.equals("ja") || code.equals("th");
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
        return "zh-TW".equals(code) ? Locale.TRADITIONAL_CHINESE : Locale.forLanguageTag(code);
    }
}
