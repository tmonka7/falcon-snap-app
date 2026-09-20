package com.falcon.snap.model;

import android.graphics.Color;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One block of recognized text: where it sits in the image, what it says, what it translates to,
 * and the colors needed to paint the translation over it.
 *
 * All geometry is in bitmap pixels. The block is a rotated rectangle described by its center,
 * size and angle; {@link #corners} keeps the raw OCR quad (tl, tr, br, bl) for hit-testing.
 */
public final class TextBlockItem {
    public float[] corners = new float[8];
    public float cx;
    public float cy;
    public float w;
    public float h;
    /** Clockwise rotation of the text baseline, in degrees. */
    public float angle;
    /** Average height of one source line; drives the size of the replacement text. */
    public float lineHeight;
    public int lineCount;
    /** True when the source lines look center-aligned (or there is only one line). */
    public boolean centered;

    public String sourceText = "";
    /** null = not translated yet. Empty, or equal to the source, = leave the image untouched. */
    public String translatedText;

    public int bgColor = Color.WHITE;
    public int textColor = Color.BLACK;

    /** True when there is a translation worth drawing over the image. */
    public boolean hasOverlay() {
        return !TextUtils.isEmpty(translatedText) && !translatedText.equals(sourceText);
    }

    /** Text to show for this block in lists: the translation if there is one, else the source. */
    public String displayTranslation() {
        return TextUtils.isEmpty(translatedText) ? sourceText : translatedText;
    }

    public static String joinSource(List<TextBlockItem> blocks) {
        List<String> parts = new ArrayList<>();
        for (TextBlockItem block : blocks) {
            if (!TextUtils.isEmpty(block.sourceText)) {
                parts.add(block.sourceText);
            }
        }
        return TextUtils.join("\n", parts);
    }

    public static String joinTranslated(List<TextBlockItem> blocks) {
        List<String> parts = new ArrayList<>();
        for (TextBlockItem block : blocks) {
            if (!TextUtils.isEmpty(block.sourceText)) {
                parts.add(block.displayTranslation());
            }
        }
        return TextUtils.join("\n", parts);
    }

    public static String listToJson(List<TextBlockItem> blocks) {
        JSONArray array = new JSONArray();
        try {
            for (TextBlockItem block : blocks) {
                JSONObject o = new JSONObject();
                JSONArray c = new JSONArray();
                for (float v : block.corners) {
                    c.put((double) v);
                }
                o.put("corners", c);
                o.put("cx", (double) block.cx);
                o.put("cy", (double) block.cy);
                o.put("w", (double) block.w);
                o.put("h", (double) block.h);
                o.put("angle", (double) block.angle);
                o.put("lineHeight", (double) block.lineHeight);
                o.put("lineCount", block.lineCount);
                o.put("centered", block.centered);
                o.put("source", block.sourceText);
                o.put("translated", block.translatedText == null ? JSONObject.NULL : block.translatedText);
                o.put("bg", block.bgColor);
                o.put("fg", block.textColor);
                array.put(o);
            }
        } catch (JSONException e) {
            // Only thrown for NaN/infinite numbers, which OCR geometry never produces.
            return "[]";
        }
        return array.toString();
    }

    public static List<TextBlockItem> listFromJson(String json) {
        List<TextBlockItem> blocks = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return blocks;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                TextBlockItem block = new TextBlockItem();
                JSONArray c = o.getJSONArray("corners");
                for (int k = 0; k < 8 && k < c.length(); k++) {
                    block.corners[k] = (float) c.getDouble(k);
                }
                block.cx = (float) o.getDouble("cx");
                block.cy = (float) o.getDouble("cy");
                block.w = (float) o.getDouble("w");
                block.h = (float) o.getDouble("h");
                block.angle = (float) o.getDouble("angle");
                block.lineHeight = (float) o.getDouble("lineHeight");
                block.lineCount = o.getInt("lineCount");
                block.centered = o.getBoolean("centered");
                block.sourceText = o.getString("source");
                block.translatedText = o.isNull("translated") ? null : o.getString("translated");
                block.bgColor = o.getInt("bg");
                block.textColor = o.getInt("fg");
                blocks.add(block);
            }
        } catch (JSONException e) {
            blocks.clear();
        }
        return blocks;
    }
}
