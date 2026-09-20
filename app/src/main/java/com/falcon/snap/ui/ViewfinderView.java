package com.falcon.snap.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Decorative red corner brackets drawn over the camera preview. */
public class ViewfinderView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public ViewfinderView(Context context) {
        this(context, null);
    }

    public ViewfinderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFFE8112D);
        paint.setStrokeWidth(3f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float insetX = getWidth() * 0.08f;
        float insetY = getHeight() * 0.18f;
        float left = insetX;
        float top = insetY;
        float right = getWidth() - insetX;
        float bottom = getHeight() - insetY;
        float arm = 28f * density;

        canvas.drawLine(left, top, left + arm, top, paint);
        canvas.drawLine(left, top, left, top + arm, paint);
        canvas.drawLine(right, top, right - arm, top, paint);
        canvas.drawLine(right, top, right, top + arm, paint);
        canvas.drawLine(left, bottom, left + arm, bottom, paint);
        canvas.drawLine(left, bottom, left, bottom - arm, paint);
        canvas.drawLine(right, bottom, right - arm, bottom, paint);
        canvas.drawLine(right, bottom, right, bottom - arm, paint);
    }
}
