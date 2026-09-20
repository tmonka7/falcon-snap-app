package com.falcon.snap.engine;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import com.falcon.snap.model.Language;
import com.falcon.snap.model.TextBlockItem;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs ML Kit text recognition and turns each recognized block into a {@link TextBlockItem}:
 * its rotated rectangle, its text, and the background / text colors sampled from the photo.
 */
public final class OcrEngine {
    public interface Callback {
        void onSuccess(List<TextBlockItem> blocks);

        void onError(Exception e);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private OcrEngine() {
    }

    /** Callbacks are delivered on the main thread. */
    public static void recognize(Bitmap bitmap, Language source, Callback callback) {
        TextRecognizer recognizer = createRecognizer(source.script);
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    recognizer.close();
                    // Color sampling reads a few thousand pixels per block; keep it off the UI thread.
                    WORKER.execute(() -> {
                        List<TextBlockItem> blocks = toBlocks(text, bitmap, source);
                        MAIN.post(() -> callback.onSuccess(blocks));
                    });
                })
                .addOnFailureListener(e -> {
                    recognizer.close();
                    callback.onError(e);
                });
    }

    private static TextRecognizer createRecognizer(int script) {
        switch (script) {
            case Language.SCRIPT_CHINESE:
                return TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            case Language.SCRIPT_JAPANESE:
                return TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            case Language.SCRIPT_KOREAN:
                return TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            case Language.SCRIPT_DEVANAGARI:
                return TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
            default:
                return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
    }

    private static List<TextBlockItem> toBlocks(Text text, Bitmap bitmap, Language source) {
        List<TextBlockItem> blocks = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            List<Text.Line> lines = block.getLines();
            float[] corners = cornersOf(block.getCornerPoints(), block.getBoundingBox());
            if (lines.isEmpty() || corners == null) {
                continue;
            }
            TextBlockItem item = new TextBlockItem();
            item.corners = corners;
            item.cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4f;
            item.cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4f;
            item.w = (dist(corners, 0, 1) + dist(corners, 3, 2)) / 2f;
            item.h = (dist(corners, 0, 3) + dist(corners, 1, 2)) / 2f;
            if (item.w < 4f || item.h < 4f) {
                continue;
            }
            item.angle = (float) Math.toDegrees(Math.atan2(corners[3] - corners[1], corners[2] - corners[0]));
            item.lineCount = lines.size();
            measureLines(item, lines);
            item.sourceText = joinLines(lines, source);
            int[] colors = sampleColors(bitmap, corners, item);
            item.bgColor = colors[0];
            item.textColor = colors[1];
            blocks.add(item);
        }
        return blocks;
    }

    /** Returns the quad as x0,y0 .. x3,y3 (tl, tr, br, bl), or null when ML Kit gave no geometry. */
    private static float[] cornersOf(Point[] points, Rect box) {
        if (points != null && points.length == 4) {
            float[] corners = new float[8];
            for (int i = 0; i < 4; i++) {
                corners[i * 2] = points[i].x;
                corners[i * 2 + 1] = points[i].y;
            }
            return corners;
        }
        if (box != null) {
            return new float[]{box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom};
        }
        return null;
    }

    private static float dist(float[] corners, int a, int b) {
        return (float) Math.hypot(corners[b * 2] - corners[a * 2], corners[b * 2 + 1] - corners[a * 2 + 1]);
    }

    /** Fills in the average line height and whether the lines look center-aligned. */
    private static void measureLines(TextBlockItem item, List<Text.Line> lines) {
        // Unit vector along the block's baseline; line edges are projected onto it.
        float ux = (item.corners[2] - item.corners[0]) / item.w;
        float uy = (item.corners[3] - item.corners[1]) / item.w;
        float heightSum = 0f;
        int measured = 0;
        float minLeft = Float.MAX_VALUE, maxLeft = -Float.MAX_VALUE;
        float minCenter = Float.MAX_VALUE, maxCenter = -Float.MAX_VALUE;
        for (Text.Line line : lines) {
            float[] c = cornersOf(line.getCornerPoints(), line.getBoundingBox());
            if (c == null) {
                continue;
            }
            heightSum += (dist(c, 0, 3) + dist(c, 1, 2)) / 2f;
            measured++;
            float left = (c[0] - item.corners[0]) * ux + (c[1] - item.corners[1]) * uy;
            float right = (c[2] - item.corners[0]) * ux + (c[3] - item.corners[1]) * uy;
            float center = (left + right) / 2f;
            minLeft = Math.min(minLeft, left);
            maxLeft = Math.max(maxLeft, left);
            minCenter = Math.min(minCenter, center);
            maxCenter = Math.max(maxCenter, center);
        }
        item.lineHeight = measured > 0 ? heightSum / measured : item.h / Math.max(1, item.lineCount);
        if (measured <= 1) {
            item.centered = true;
        } else {
            float leftSpread = maxLeft - minLeft;
            float centerSpread = maxCenter - minCenter;
            // Ragged left edges that share a common center = centered text.
            item.centered = leftSpread > item.lineHeight * 0.3f && centerSpread < leftSpread * 0.5f;
        }
    }

    /** Rebuilds a block's sentence(s) from its lines so the translator sees whole phrases. */
    private static String joinLines(List<Text.Line> lines, Language source) {
        StringBuilder sb = new StringBuilder();
        for (Text.Line line : lines) {
            String text = line.getText().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                boolean hyphenated = sb.charAt(sb.length() - 1) == '-' && Character.isLowerCase(text.charAt(0));
                if (hyphenated) {
                    sb.setLength(sb.length() - 1);
                } else if (!source.joinsWithoutSpaces()) {
                    sb.append(' ');
                }
            }
            sb.append(text);
        }
        return sb.toString();
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
