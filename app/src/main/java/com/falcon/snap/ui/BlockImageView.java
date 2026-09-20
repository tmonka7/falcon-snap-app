package com.falcon.snap.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.appcompat.widget.AppCompatImageView;

import com.falcon.snap.model.TextBlockItem;

import java.util.ArrayList;
import java.util.List;

/**
 * ImageView that knows where the text blocks are in its bitmap: it reports taps on a block and
 * can outline one block. Block geometry is in bitmap pixels and is mapped through the image matrix.
 */
public class BlockImageView extends AppCompatImageView {
    public interface OnBlockTapListener {
        void onBlockTapped(int index);
    }

    private final List<float[]> quads = new ArrayList<>();
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path highlightPath = new Path();
    private final Matrix inverse = new Matrix();
    private int highlighted = -1;
    private OnBlockTapListener listener;

    public BlockImageView(Context context) {
        this(context, null);
    }

    public BlockImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setColor(0xFFE8112D);
        highlightPaint.setStrokeWidth(3f * getResources().getDisplayMetrics().density);
        highlightPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setBlocks(List<TextBlockItem> blocks) {
        quads.clear();
        for (TextBlockItem block : blocks) {
            quads.add(block.corners);
        }
        invalidate();
    }

    /** Outlines the block at this index; -1 clears the outline. */
    public void setHighlighted(int index) {
        highlighted = index;
        invalidate();
    }

    public void setOnBlockTapListener(OnBlockTapListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (highlighted < 0 || highlighted >= quads.size() || getDrawable() == null) {
            return;
        }
        float[] points = quads.get(highlighted).clone();
        getImageMatrix().mapPoints(points);
        highlightPath.reset();
        highlightPath.moveTo(points[0], points[1]);
        for (int i = 1; i < 4; i++) {
            highlightPath.lineTo(points[i * 2], points[i * 2 + 1]);
        }
        highlightPath.close();
        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        canvas.drawPath(highlightPath, highlightPaint);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (listener == null || quads.isEmpty()) {
            return super.onTouchEvent(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            int index = blockAt(event.getX(), event.getY());
            performClick();
            if (index >= 0) {
                listener.onBlockTapped(index);
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int blockAt(float viewX, float viewY) {
        if (getDrawable() == null || !getImageMatrix().invert(inverse)) {
            return -1;
        }
        float[] point = {viewX - getPaddingLeft(), viewY - getPaddingTop()};
        inverse.mapPoints(point);
        for (int i = 0; i < quads.size(); i++) {
            if (contains(quads.get(i), point[0], point[1])) {
                return i;
            }
        }
        return -1;
    }

    /** Point-in-convex-quad: the point is on the same side of all four edges. */
    private static boolean contains(float[] q, float x, float y) {
        boolean positive = false;
        boolean negative = false;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float cross = (q[j * 2] - q[i * 2]) * (y - q[i * 2 + 1]) - (q[j * 2 + 1] - q[i * 2 + 1]) * (x - q[i * 2]);
            if (cross > 0) {
                positive = true;
            } else if (cross < 0) {
                negative = true;
            }
        }
        return !(positive && negative);
    }
}
