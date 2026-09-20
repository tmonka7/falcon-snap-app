package com.falcon.snap.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.falcon.snap.model.Language;
import com.falcon.snap.model.TextBlockItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline OCR. Runs PaddleOCR ({@link PaddleOcr}) with the models found by {@link ModelStore} -
 * bundled in the APK's assets, or on storage - groups the lines into paragraphs
 * ({@link LineGrouper}) and samples the
 * background / text colors needed to paint a translation over each block.
 */
public final class OcrEngine {
    public interface Callback {
        void onSuccess(List<TextBlockItem> blocks);

        /** A {@link ModelsMissingException} means the OCR models are not installed. */
        void onError(Exception e);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Only touched on WORKER. */
    private static final PaddleOcr OCR = new PaddleOcr();

    private OcrEngine() {
    }

    /** Callbacks are delivered on the main thread. */
    public static void recognize(Context context, Bitmap bitmap, Language source, Callback callback) {
        Context appContext = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                ModelSource detector = ModelStore.detector(appContext);
                String key = ModelStore.recognizerKeyFor(appContext, source);
                if (detector == null || key == null) {
                    throw new ModelsMissingException(detector == null
                            ? "OCR detection model (assets/*_det.onnx or models/ocr/" + ModelStore.OCR_DET + ")"
                            : "OCR recognition model \"" + source.ocrKeys[0] + "\" for " + source.shortName
                            + " (models/ocr/rec_" + source.ocrKeys[0] + ".onnx)");
                }
                List<PaddleOcr.Line> lines = OCR.run(appContext, bitmap, detector,
                        ModelStore.recognizer(appContext, key), ModelStore.dictionary(appContext, key));
                List<TextBlockItem> blocks = LineGrouper.group(lines, source);
                for (TextBlockItem block : blocks) {
                    int[] colors = sampleColors(bitmap, block.corners, block);
                    block.bgColor = colors[0];
                    block.textColor = colors[1];
                }
                MAIN.post(() -> callback.onSuccess(blocks));
            } catch (Exception | OutOfMemoryError e) {
                Exception error = e instanceof Exception ? (Exception) e : new RuntimeException(e);
                MAIN.post(() -> callback.onError(error));
            }
        });
    }

    /** Frees the OCR sessions (a few tens of MB). They are reloaded on the next use. */
    public static void release() {
        WORKER.execute(OCR::release);
    }

    /**
     * Estimates {background, text} colors for a block.
     * Background = median of a ring of pixels just outside the quad. Text = mean of the pixels
     * inside the quad that differ most from that background.
     */
    private static int[] sampleColors(Bitmap bitmap, float[] corners, TextBlockItem item) {
        float margin = Math.max(2f, item.lineHeight * 0.15f);
        float mu = margin / item.w;
        float mv = margin / item.h;

        List<Integer> ring = new ArrayList<>();
        int steps = 24;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            addSample(ring, bitmap, corners, t, -mv);
            addSample(ring, bitmap, corners, t, 1f + mv);
            addSample(ring, bitmap, corners, -mu, t);
            addSample(ring, bitmap, corners, 1f + mu, t);
        }

        List<Integer> inside = new ArrayList<>();
        int cols = 48, rows = 16;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                addSample(inside, bitmap, corners, (c + 0.5f) / cols, (r + 0.5f) / rows);
            }
        }
        if (inside.isEmpty()) {
            return new int[]{Color.WHITE, Color.BLACK};
        }

        // A block that fills the whole photo has no ring; text covers well under half of a block,
        // so the median of the inside is still the background.
        int bg = median(ring.isEmpty() ? inside : ring);

        float maxDistance = 0f;
        for (int color : inside) {
            maxDistance = Math.max(maxDistance, distance(color, bg));
        }
        int fallback = luminance(bg) > 140 ? Color.BLACK : Color.WHITE;
        if (maxDistance < 40f) {
            return new int[]{bg, fallback};
        }
        float threshold = maxDistance * 0.6f;
        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int color : inside) {
            if (distance(color, bg) >= threshold) {
                r += Color.red(color);
                g += Color.green(color);
                b += Color.blue(color);
                count++;
            }
        }
        int fg = count == 0 ? fallback : Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
        if (distance(fg, bg) < 90f) {
            fg = fallback;
        }
        return new int[]{bg, fg};
    }

    /** Samples the pixel at (u, v) in the quad's own coordinate system, if it is inside the bitmap. */
    private static void addSample(List<Integer> out, Bitmap bitmap, float[] q, float u, float v) {
        float x = q[0] * (1 - u) * (1 - v) + q[2] * u * (1 - v) + q[4] * u * v + q[6] * (1 - u) * v;
        float y = q[1] * (1 - u) * (1 - v) + q[3] * u * (1 - v) + q[5] * u * v + q[7] * (1 - u) * v;
        int px = Math.round(x);
        int py = Math.round(y);
        if (px >= 0 && py >= 0 && px < bitmap.getWidth() && py < bitmap.getHeight()) {
            out.add(bitmap.getPixel(px, py));
        }
    }

    private static int median(List<Integer> colors) {
        int n = colors.size();
        int[] r = new int[n], g = new int[n], b = new int[n];
        for (int i = 0; i < n; i++) {
            int color = colors.get(i);
            r[i] = Color.red(color);
            g[i] = Color.green(color);
            b[i] = Color.blue(color);
        }
        Arrays.sort(r);
        Arrays.sort(g);
        Arrays.sort(b);
        return Color.rgb(r[n / 2], g[n / 2], b[n / 2]);
    }

    private static float distance(int a, int b) {
        int dr = Color.red(a) - Color.red(b);
        int dg = Color.green(a) - Color.green(b);
        int db = Color.blue(a) - Color.blue(b);
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int luminance(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
    }
}
