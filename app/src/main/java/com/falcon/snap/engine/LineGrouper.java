package com.falcon.snap.engine;

import com.falcon.snap.model.Language;
import com.falcon.snap.model.TextBlockItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PaddleOCR returns individual text lines. Translating line by line gives poor results because a
 * sentence is often split over several lines, so neighbouring lines that look like one paragraph
 * (same angle, same size, stacked or side by side) are merged into one {@link TextBlockItem}.
 */
final class LineGrouper {
    private static final float MAX_ANGLE_DIFF = 8f;
    private static final float MAX_HEIGHT_RATIO = 1.4f;

    private LineGrouper() {
    }

    private static final class Item {
        PaddleOcr.Line line;
        float cx, cy, w, h;
        /** Unit vector along the reading direction. */
        float ux, uy;
        float angle;
        /** Position in the block's frame; filled in while a block is assembled. */
        float u, v;
        int parent;
    }

    static List<TextBlockItem> group(List<PaddleOcr.Line> lines, Language source) {
        List<Item> items = new ArrayList<>();
        for (PaddleOcr.Line line : lines) {
            items.add(measure(line, items.size()));
        }
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                if (belongTogether(items.get(i), items.get(j))) {
                    items.get(find(items, j)).parent = find(items, i);
                }
            }
        }

        List<TextBlockItem> blocks = new ArrayList<>();
        for (int root = 0; root < items.size(); root++) {
            List<Item> members = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if (find(items, i) == root) {
                    members.add(items.get(i));
                }
            }
            if (!members.isEmpty()) {
                blocks.add(toBlock(members, source));
            }
        }
        // Reading order for the text shown under the image.
        Collections.sort(blocks, (a, b) -> Float.compare(a.cy, b.cy));
        return blocks;
    }

    private static Item measure(PaddleOcr.Line line, int index) {
        float[] c = line.corners;
        Item item = new Item();
        item.line = line;
        item.parent = index;
        item.cx = (c[0] + c[2] + c[4] + c[6]) / 4f;
        item.cy = (c[1] + c[3] + c[5] + c[7]) / 4f;
        item.w = ((float) Math.hypot(c[2] - c[0], c[3] - c[1]) + (float) Math.hypot(c[4] - c[6], c[5] - c[7])) / 2f;
        item.h = ((float) Math.hypot(c[6] - c[0], c[7] - c[1]) + (float) Math.hypot(c[4] - c[2], c[5] - c[3])) / 2f;
        float dx = c[2] - c[0];
        float dy = c[3] - c[1];
        float length = Math.max(1e-3f, (float) Math.hypot(dx, dy));
        item.ux = dx / length;
        item.uy = dy / length;
        item.angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        return item;
    }

    private static int find(List<Item> items, int index) {
        while (items.get(index).parent != index) {
            index = items.get(index).parent;
        }
        return index;
    }

    private static boolean belongTogether(Item a, Item b) {
        float angleDiff = Math.abs(a.angle - b.angle) % 360f;
        if (Math.min(angleDiff, 360f - angleDiff) > MAX_ANGLE_DIFF) {
            return false;
        }
        float minHeight = Math.min(a.h, b.h);
        if (Math.max(a.h, b.h) / minHeight > MAX_HEIGHT_RATIO) {
            return false;
        }
        // b's center in a's frame: along the text (du) and across it (dv).
        float du = (b.cx - a.cx) * a.ux + (b.cy - a.cy) * a.uy;
        float dv = -(b.cx - a.cx) * a.uy + (b.cy - a.cy) * a.ux;

        if (Math.abs(dv) < 0.4f * minHeight) {
            // Same row: pieces of one line that the detector split.
            float gap = Math.abs(du) - (a.w + b.w) / 2f;
            return gap < minHeight;
        }
        float rowGap = Math.abs(dv) - (a.h + b.h) / 2f;
        if (rowGap > 0.7f * minHeight) {
            return false;
        }
        // Stacked rows: require that they line up (overlap, common left edge, or common center).
        float overlap = Math.min(a.w / 2f, du + b.w / 2f) - Math.max(-a.w / 2f, du - b.w / 2f);
        boolean leftAligned = Math.abs((du - b.w / 2f) + a.w / 2f) < minHeight;
        boolean centered = Math.abs(du) < minHeight;
        return overlap > 0.3f * Math.min(a.w, b.w) || leftAligned || centered;
    }

    private static TextBlockItem toBlock(List<Item> members, Language source) {
        // The longest line gives the most reliable angle; it defines the block's frame.
        Item reference = members.get(0);
        for (Item item : members) {
            if (item.w > reference.w) {
                reference = item;
            }
        }
        float ux = reference.ux;
        float uy = reference.uy;
        float heightSum = 0f;
        float[] xs = new float[members.size() * 4];
        float[] ys = new float[members.size() * 4];
        int n = 0;
        for (Item item : members) {
            item.u = item.cx * ux + item.cy * uy;
            item.v = -item.cx * uy + item.cy * ux;
            heightSum += item.h;
            for (int k = 0; k < 4; k++) {
                xs[n] = item.line.corners[k * 2];
                ys[n] = item.line.corners[k * 2 + 1];
                n++;
            }
        }
        float lineHeight = heightSum / members.size();

        // Rows top to bottom, and left to right within a row.
        Collections.sort(members, (a, b) -> Float.compare(a.v, b.v));
        List<List<Item>> rows = new ArrayList<>();
        for (Item item : members) {
            List<Item> row = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (row == null || item.v - row.get(0).v > 0.5f * lineHeight) {
                row = new ArrayList<>();
                rows.add(row);
            }
            row.add(item);
        }

        StringBuilder text = new StringBuilder();
        float minLeft = Float.MAX_VALUE, maxLeft = -Float.MAX_VALUE;
        float minCenter = Float.MAX_VALUE, maxCenter = -Float.MAX_VALUE;
        for (List<Item> row : rows) {
            Collections.sort(row, (a, b) -> Float.compare(a.u, b.u));
            float left = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            for (Item item : row) {
                appendLine(text, item.line.text, source);
                left = Math.min(left, item.u - item.w / 2f);
                right = Math.max(right, item.u + item.w / 2f);
            }
            minLeft = Math.min(minLeft, left);
            maxLeft = Math.max(maxLeft, left);
            minCenter = Math.min(minCenter, (left + right) / 2f);
            maxCenter = Math.max(maxCenter, (left + right) / 2f);
        }

        RotatedRect rect = RotatedRect.inFrame(xs, ys, n, ux, uy);
        TextBlockItem block = new TextBlockItem();
        block.corners = rect.corners(0f);
        block.w = rect.width();
        block.h = rect.height();
        float centerU = (rect.uMin + rect.uMax) / 2f;
        float centerV = (rect.vMin + rect.vMax) / 2f;
        block.cx = centerU * ux - centerV * uy;
        block.cy = centerU * uy + centerV * ux;
        block.angle = (float) Math.toDegrees(Math.atan2(uy, ux));
        block.lineHeight = lineHeight;
        block.lineCount = rows.size();
        block.sourceText = text.toString();
        float leftSpread = maxLeft - minLeft;
        float centerSpread = maxCenter - minCenter;
        // Ragged left edges that share a common center = centered text.
        block.centered = rows.size() <= 1 || (leftSpread > lineHeight * 0.3f && centerSpread < leftSpread * 0.5f);
        return block;
    }

    /** Joins lines back into sentences: undo hyphenation, and no spaces for scripts that do not use them. */
    private static void appendLine(StringBuilder sb, String line, Language source) {
        String text = line.trim();
        if (text.isEmpty()) {
            return;
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
}
