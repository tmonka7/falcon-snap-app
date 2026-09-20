package com.falcon.snap.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.falcon.snap.model.TextBlockItem;

import java.util.List;

/**
 * Replaces text in place: for every translated block, paints the block's background color over
 * the original text and draws the translation inside the same rotated rectangle, sized to fit.
 */
public final class OverlayRenderer {
    private static final float MIN_TEXT_SIZE = 5f;

    private OverlayRenderer() {
    }

    /** Returns a new bitmap; the original is left untouched. Call off the UI thread. */
    public static Bitmap render(Bitmap original, List<TextBlockItem> blocks) {
        Bitmap out = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(out);
        for (TextBlockItem block : blocks) {
            if (block.hasOverlay()) {
                drawBlock(canvas, block);
            }
        }
        return out;
    }

    private static void drawBlock(Canvas canvas, TextBlockItem block) {
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        textPaint.setColor(block.textColor);
        // Short blocks are usually signs and headings, which read better bold.
        textPaint.setTypeface(block.lineCount <= 2 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

        int width = Math.max(1, Math.round(block.w));
        Layout.Alignment alignment = block.centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        StaticLayout layout = fitText(block.translatedText, textPaint, width, block.h, block.lineHeight, alignment);

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(block.bgColor);
        float pad = Math.max(2f, block.lineHeight * 0.12f);
        // If the translation cannot fit even at the smallest size, let the patch grow with it.
        float halfHeight = Math.max(block.h, layout.getHeight()) / 2f;
        RectF patch = new RectF(block.cx - block.w / 2f - pad, block.cy - halfHeight - pad,
                block.cx + block.w / 2f + pad, block.cy + halfHeight + pad);

        canvas.save();
        canvas.rotate(block.angle, block.cx, block.cy);
        canvas.drawRoundRect(patch, pad, pad, bgPaint);
        canvas.translate(block.cx - block.w / 2f, block.cy - layout.getHeight() / 2f);
        layout.draw(canvas);
        canvas.restore();
    }

    /** Largest text size, capped at roughly the original line height, whose layout fits the block. */
    private static StaticLayout fitText(String text, TextPaint paint, int width, float maxHeight,
                                        float lineHeight, Layout.Alignment alignment) {
        float hi = Math.max(MIN_TEXT_SIZE, Math.min(lineHeight * 0.9f, maxHeight));
        float lo = MIN_TEXT_SIZE;
        float allowedHeight = maxHeight * 1.05f;

        StaticLayout best = layoutAt(text, paint, width, hi, alignment);
        if (best.getHeight() <= allowedHeight) {
            return best;
        }
        best = layoutAt(text, paint, width, lo, alignment);
        for (int i = 0; i < 8; i++) {
            float mid = (lo + hi) / 2f;
            StaticLayout candidate = layoutAt(text, paint, width, mid, alignment);
            if (candidate.getHeight() <= allowedHeight) {
                best = candidate;
                lo = mid;
            } else {
                hi = mid;
            }
        }
        // StaticLayout reads the paint when it draws, so leave the paint at the winning size.
        paint.setTextSize(lo);
        return best;
    }

    @SuppressWarnings("deprecation") // StaticLayout.Builder needs API 23; minSdk is 21.
    private static StaticLayout layoutAt(String text, TextPaint paint, int width, float size,
                                         Layout.Alignment alignment) {
        paint.setTextSize(size);
        return new StaticLayout(text, paint, width, alignment, 1.0f, 0f, false);
    }
}
