package com.falcon.snap.engine;

import android.os.Build;

import androidx.annotation.RequiresApi;

/**
 * Simplified <-> Traditional Chinese conversion using the platform ICU transliterator (API 29+).
 * On older devices, or if the transliterator is missing, text is returned unchanged.
 */
public final class ChineseConverter {
    private ChineseConverter() {
    }

    public static String toTraditional(String text) {
        return convert(text, "Simplified-Traditional", "Hans-Hant");
    }

    public static String toSimplified(String text) {
        return convert(text, "Traditional-Simplified", "Hant-Hans");
    }

    private static String convert(String text, String id, String alternateId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || text == null) {
            return text;
        }
        try {
            return Api29.transliterate(text, id);
        } catch (RuntimeException e) {
            try {
                return Api29.transliterate(text, alternateId);
            } catch (RuntimeException e2) {
                return text;
            }
        }
    }

    /** Kept in its own class so older devices never have to resolve android.icu.text.Transliterator. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private static final class Api29 {
        static String transliterate(String text, String id) {
            return android.icu.text.Transliterator.getInstance(id).transliterate(text);
        }
    }
}
