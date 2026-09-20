package com.falcon.snap;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/** Builds the grouped "title + chevron" rows used by the Settings and About screens. */
final class SettingRows {
    private SettingRows() {
    }

    /** Adds an empty card to the container and returns the layout that rows go into. */
    static ViewGroup addGroup(Activity activity, ViewGroup container) {
        View card = activity.getLayoutInflater().inflate(R.layout.item_setting_group, container, false);
        container.addView(card);
        return card.findViewById(R.id.group_content);
    }

    /** Adds a clickable row and returns its (initially hidden) value label. */
    static TextView addRow(Activity activity, ViewGroup group, int titleRes, View.OnClickListener listener) {
        addDividerIfNeeded(activity, group);
        View row = activity.getLayoutInflater().inflate(R.layout.item_setting_row, group, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(titleRes);
        row.setOnClickListener(listener);
        group.addView(row);
        return row.findViewById(R.id.row_value);
    }

    static void addDividerIfNeeded(Activity activity, ViewGroup group) {
        if (group.getChildCount() == 0) {
            return;
        }
        View divider = new View(activity);
        divider.setBackgroundColor(ContextCompat.getColor(activity, R.color.divider));
        int height = Math.max(1, Math.round(activity.getResources().getDisplayMetrics().density));
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        int inset = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        params.setMargins(inset, 0, inset, 0);
        group.addView(divider, params);
    }
}
