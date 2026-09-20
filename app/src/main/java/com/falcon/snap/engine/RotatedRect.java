package com.falcon.snap.engine;

import java.util.Arrays;

/**
 * A rectangle at an arbitrary angle, stored as a frame (unit axis u, with v perpendicular to it)
 * plus the extent of the shape along each axis.
 */
final class RotatedRect {
    /** Unit vector of the u axis. The v axis is (-uy, ux). */
    float ux;
    float uy;
    float uMin;
    float uMax;
    float vMin;
    float vMax;

    float width() {
        return uMax - uMin;
    }

    float height() {
        return vMax - vMin;
    }

    /** The four corners (x0,y0 .. x3,y3), walking around the rectangle, grown by {@code expand} on every side. */
    float[] corners(float expand) {
        float u0 = uMin - expand, u1 = uMax + expand, v0 = vMin - expand, v1 = vMax + expand;
        return new float[]{
                point(u0, v0, true), point(u0, v0, false),
                point(u1, v0, true), point(u1, v0, false),
                point(u1, v1, true), point(u1, v1, false),
                point(u0, v1, true), point(u0, v1, false),
        };
    }

    private float point(float u, float v, boolean x) {
        return x ? u * ux - v * uy : u * uy + v * ux;
    }

    /** Bounding rectangle of the points in the frame whose u axis points along (ux, uy). */
    static RotatedRect inFrame(float[] xs, float[] ys, int count, float ux, float uy) {
        RotatedRect rect = new RotatedRect();
        rect.ux = ux;
        rect.uy = uy;
        rect.uMin = rect.vMin = Float.MAX_VALUE;
        rect.uMax = rect.vMax = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float u = xs[i] * ux + ys[i] * uy;
            float v = -xs[i] * uy + ys[i] * ux;
            rect.uMin = Math.min(rect.uMin, u);
            rect.uMax = Math.max(rect.uMax, u);
            rect.vMin = Math.min(rect.vMin, v);
            rect.vMax = Math.max(rect.vMax, v);
        }
        return rect;
    }

    /**
     * Minimum-area enclosing rectangle (what OpenCV calls minAreaRect): one side of the optimal
     * rectangle always lies along an edge of the convex hull, so every hull edge is tried.
     */
    static RotatedRect minArea(float[] xs, float[] ys, int count) {
        int[] hull = convexHull(xs, ys, count);
        float[] hx = new float[hull.length];
        float[] hy = new float[hull.length];
        for (int i = 0; i < hull.length; i++) {
            hx[i] = xs[hull[i]];
            hy[i] = ys[hull[i]];
        }
        RotatedRect best = inFrame(hx, hy, hull.length, 1f, 0f);
        float bestArea = best.width() * best.height();
        for (int i = 0; i < hull.length; i++) {
            int j = (i + 1) % hull.length;
            float dx = hx[j] - hx[i];
            float dy = hy[j] - hy[i];
            float length = (float) Math.hypot(dx, dy);
            if (length < 1e-6f) {
                continue;
            }
            RotatedRect candidate = inFrame(hx, hy, hull.length, dx / length, dy / length);
            float area = candidate.width() * candidate.height();
            if (area < bestArea) {
                best = candidate;
                bestArea = area;
            }
        }
        return best;
    }

    /** Andrew's monotone chain. Returns indices of the hull points, counter-clockwise. */
    private static int[] convexHull(float[] xs, float[] ys, int count) {
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> xs[a] != xs[b] ? Float.compare(xs[a], xs[b]) : Float.compare(ys[a], ys[b]));
        if (count < 3) {
            int[] all = new int[count];
            for (int i = 0; i < count; i++) {
                all[i] = order[i];
            }
            return all;
        }
        int[] hull = new int[count * 2];
        int size = 0;
        for (int pass = 0; pass < 2; pass++) {
            // A pop needs two points of the current chain below the candidate.
            int floor = size + 2;
            for (int n = 0; n < count; n++) {
                int p = order[pass == 0 ? n : count - 1 - n];
                while (size >= floor && cross(xs, ys, hull[size - 2], hull[size - 1], p) <= 0) {
                    size--;
                }
                hull[size++] = p;
            }
            // The last point of each pass is the first point of the next.
            size--;
        }
        return Arrays.copyOf(hull, Math.max(size, 1));
    }

    private static float cross(float[] xs, float[] ys, int o, int a, int b) {
        return (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o]);
    }
}
