package com.falcon.snap;

import android.graphics.Bitmap;

import com.falcon.snap.model.Language;
import com.falcon.snap.model.TextBlockItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The translation currently on screen, shared between the result and edit screens.
 * Bitmaps are far too large for an Intent, so they live here instead. If the process is killed
 * the session is gone and those screens simply close.
 */
public final class Session {
    /** Untouched photo. Null for history entries saved without their original. */
    public static Bitmap original;
    /** Photo with the text replaced. */
    public static Bitmap rendered;
    public static List<TextBlockItem> blocks = new ArrayList<>();
    public static Language source;
    public static Language target;
    /** Row id once this translation has been saved, else -1. */
    public static long historyId = -1;
    /** True when the session was opened from history rather than freshly captured. */
    public static boolean fromHistory;
    /**
     * Identity of the result screen that owns this session. Activities are destroyed lazily, so an
     * old result screen may be torn down after a new one has already started a fresh session; it
     * must only clear the session if it still owns it.
     */
    public static int owner;

    private Session() {
    }

    public static void reset() {
        original = null;
        rendered = null;
        blocks = new ArrayList<>();
        source = null;
        target = null;
        historyId = -1;
        fromHistory = false;
    }

    /** Editing needs the untouched photo to repaint over. */
    public static boolean canEdit() {
        return original != null && !blocks.isEmpty();
    }
}
