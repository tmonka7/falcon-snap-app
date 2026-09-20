package com.falcon.snap.ui;

import android.content.Context;

import com.falcon.snap.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Simple informational dialogs shared by several screens. */
public final class Dialogs {
    private Dialogs() {
    }

    public static void showInfo(Context context, int titleRes, int messageRes) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    public static void showHelp(Context context) {
        showInfo(context, R.string.nav_help, R.string.help_text);
    }

    /** Asks before a destructive action; onConfirm runs only if the user taps Delete. */
    public static void confirmDelete(Context context, int titleRes, Runnable onConfirm) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> onConfirm.run())
                .show();
    }
}
