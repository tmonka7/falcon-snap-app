package com.falcon.snap.model;

import android.content.Context;
import android.text.TextUtils;

import com.falcon.snap.R;

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
    /** PP-OCRv5_mobile_rec: Chinese, English, Japanese. */
    public static final String OCR_MAIN = "main";
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
    /** Name in the app's interface language, shown in lists. See {@link #name(Context)}. */
    private final int nameRes;
    /** Native name, shown in the compact language bars. Deliberately not localized. */
    public final String shortName;
    /** Recognition models able to read this language, in order of preference. */
    public final String[] ocrKeys;

    private Language(String code, String nllbCode, int nameRes, String shortName, String... ocrKeys) {
        this.code = code;
        this.nllbCode = nllbCode;
        this.nameRes = nameRes;
        this.shortName = shortName;
        this.ocrKeys = ocrKeys;
    }

    private static final Language[] CATALOG = {
            new Language("en", "eng_Latn", R.string.lang_en, "English", OCR_MAIN, OCR_LATIN),
            new Language("zh", "zho_Hans", R.string.lang_zh, "中文", OCR_MAIN),
            new Language("ru", "rus_Cyrl", R.string.lang_ru, "Русский", OCR_ESLAV, OCR_CYRILLIC),
            new Language("ja", "jpn_Jpan", R.string.lang_ja, "日本語", OCR_MAIN),
            new Language("uk", "ukr_Cyrl", R.string.lang_uk, "Українська", OCR_ESLAV, OCR_CYRILLIC),
            // The main model reads unaccented Latin text, so it is a usable fallback for these.
            new Language("fr", "fra_Latn", R.string.lang_fr, "Français", OCR_LATIN, OCR_MAIN),
            new Language("de", "deu_Latn", R.string.lang_de, "Deutsch", OCR_LATIN, OCR_MAIN),
            new Language("es", "spa_Latn", R.string.lang_es, "Español", OCR_LATIN, OCR_MAIN),
            new Language("it", "ita_Latn", R.string.lang_it, "Italiano", OCR_LATIN, OCR_MAIN),
            new Language("pt", "por_Latn", R.string.lang_pt, "Português", OCR_LATIN, OCR_MAIN),
            new Language("nl", "nld_Latn", R.string.lang_nl, "Nederlands", OCR_LATIN, OCR_MAIN),
            new Language("pl", "pol_Latn", R.string.lang_pl, "Polski", OCR_LATIN, OCR_MAIN),
            new Language("sv", "swe_Latn", R.string.lang_sv, "Svenska", OCR_LATIN, OCR_MAIN),
            new Language("tr", "tur_Latn", R.string.lang_tr, "Türkçe", OCR_LATIN, OCR_MAIN),
            new Language("vi", "vie_Latn", R.string.lang_vi, "Tiếng Việt", OCR_LATIN, OCR_MAIN),
            new Language("id", "ind_Latn", R.string.lang_id, "Indonesia", OCR_LATIN, OCR_MAIN),
            new Language("ms", "zsm_Latn", R.string.lang_ms, "Melayu", OCR_LATIN, OCR_MAIN),
            new Language("tl", "tgl_Latn", R.string.lang_tl, "Filipino", OCR_LATIN, OCR_MAIN),
            new Language("hi", "hin_Deva", R.string.lang_hi, "हिन्दी", OCR_DEVANAGARI),
            new Language("th", "tha_Thai", R.string.lang_th, "ไทย", OCR_THAI),
            new Language("ar", "arb_Arab", R.string.lang_ar, "العربية", OCR_ARABIC),
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
    public static String languagesFor(Context context, String ocrKey) {
        List<String> names = new ArrayList<>();
        for (Language language : CATALOG) {
            if (language.ocrKeys[0].equals(ocrKey)) {
                names.add(language.name(context));
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

    /** The language's name in the app's current interface language. Pass an Activity, not the Application. */
    public String name(Context context) {
        return context.getString(nameRes);
    }

    /** Scripts written without spaces: lines (and translated sentences) are joined directly. */
    public boolean joinsWithoutSpaces() {
        return code.equals("zh") || code.equals("ja") || code.equals("th");
    }

    public Locale locale() {
        return Locale.forLanguageTag(code);
    }
}
