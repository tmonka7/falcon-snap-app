package com.falcon.snap.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns the PP-OCRv5 detector's probability map into text-line boxes. This is PaddleOCR's
 * DBPostProcess (threshold, connected regions, minimum-area box, score filter, "unclip" growth)
 * written without OpenCV. The constants are the ones in the model's inference.yml.
 */
final class DbPostProcessor {
    private static final float THRESH = 0.3f;
    private static final float BOX_THRESH = 0.6f;
    private static final float UNCLIP_RATIO = 1.5f;
    private static final float MIN_SIZE = 3f;
    private static final int MAX_CANDIDATES = 1000;
    /** PaddleOCR treats a crop at least this many times taller than wide as vertical text. */
    private static final float VERTICAL_RATIO = 1.5f;

    private DbPostProcessor() {
    }

    /**
     * @param prob   row-major probability map, mapW x mapH
     * @param scaleX source pixels per map pixel, horizontally (and scaleY vertically)
     * @return one quad per text line in source-image pixels: x0,y0 .. x3,y3 = top-left, top-right,
     * bottom-right, bottom-left <i>of the text</i>, so the edge 0-1 runs along the reading direction
     */
    static List<float[]> boxes(float[] prob, int mapW, int mapH, float scaleX, float scaleY, int srcW, int srcH) {
        List<float[]> boxes = new ArrayList<>();
        boolean[] seen = new boolean[mapW * mapH];
        int[] stack = new int[mapW * mapH];
        FloatList edgeX = new FloatList();
        FloatList edgeY = new FloatList();

        for (int start = 0; start < prob.length && boxes.size() < MAX_CANDIDATES; start++) {
            if (seen[start] || prob[start] <= THRESH) {
                continue;
            }
            // Flood-fill one 8-connected region, keeping only its outline for the geometry.
            edgeX.clear();
            edgeY.clear();
            double sum = 0;
            int count = 0;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int index = stack[--top];
                int x = index % mapW;
                int y = index / mapW;
                sum += prob[index];
                count++;
                boolean onEdge = x == 0 || y == 0 || x == mapW - 1 || y == mapH - 1
                        || prob[index - 1] <= THRESH || prob[index + 1] <= THRESH
                        || prob[index - mapW] <= THRESH || prob[index + mapW] <= THRESH;
                if (onEdge) {
                    edgeX.add(x);
                    edgeY.add(y);
                }
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= mapW || ny >= mapH) {
                            continue;
                        }
                        int neighbor = ny * mapW + nx;
                        if (!seen[neighbor] && prob[neighbor] > THRESH) {
                            seen[neighbor] = true;
                            stack[top++] = neighbor;
                        }
                    }
                }
            }

            RotatedRect rect = RotatedRect.minArea(edgeX.values, edgeY.values, edgeX.size);
            // Pixel centers span one pixel less than the region they cover.
            float width = rect.width() + 1f;
            float height = rect.height() + 1f;
            if (Math.min(width, height) < MIN_SIZE || sum / count < BOX_THRESH) {
                continue;
            }
            // The detector is trained on shrunken text regions; grow the box back ("unclip").
            float grow = width * height * UNCLIP_RATIO / (2f * (width + height));
            if (Math.min(width, height) + 2f * grow < MIN_SIZE + 2f) {
                continue;
            }
            float[] quad = rect.corners(grow + 0.5f);
            for (int i = 0; i < 4; i++) {
                quad[i * 2] = clamp(quad[i * 2] * scaleX, 0f, srcW - 1f);
                quad[i * 2 + 1] = clamp(quad[i * 2 + 1] * scaleY, 0f, srcH - 1f);
            }
            boxes.add(orderForReading(quad));
        }
        return boxes;
    }

    /**
     * PaddleOCR's corner convention: of the two leftmost corners the upper one is top-left, of the
     * two rightmost the upper one is top-right. A box much taller than wide is vertical text, which
     * PaddleOCR rotates a quarter turn counter-clockwise before recognition; starting the quad at
     * the old top-right corner produces exactly that crop, and makes edge 0-1 the reading direction.
     */
    private static float[] orderForReading(float[] quad) {
        Integer[] byX = {0, 1, 2, 3};
        Arrays.sort(byX, (a, b) -> Float.compare(quad[a * 2], quad[b * 2]));
        int tl = byX[0], bl = byX[1], tr = byX[2], br = byX[3];
        if (quad[tl * 2 + 1] > quad[bl * 2 + 1]) {
            int swap = tl;
            tl = bl;
            bl = swap;
        }
        if (quad[tr * 2 + 1] > quad[br * 2 + 1]) {
            int swap = tr;
            tr = br;
            br = swap;
        }
        float width = (float) Math.hypot(quad[tr * 2] - quad[tl * 2], quad[tr * 2 + 1] - quad[tl * 2 + 1]);
        float height = (float) Math.hypot(quad[bl * 2] - quad[tl * 2], quad[bl * 2 + 1] - quad[tl * 2 + 1]);
        int[] order = height >= width * VERTICAL_RATIO ? new int[]{tr, br, bl, tl} : new int[]{tl, tr, br, bl};
        float[] ordered = new float[8];
        for (int i = 0; i < 4; i++) {
            ordered[i * 2] = quad[order[i] * 2];
            ordered[i * 2 + 1] = quad[order[i] * 2 + 1];
        }
        return ordered;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Growable float array; boxing a region outline through a List would be far too slow. */
    private static final class FloatList {
        float[] values = new float[256];
        int size;

        void add(float value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        void clear() {
            size = 0;
        }
    }
}
