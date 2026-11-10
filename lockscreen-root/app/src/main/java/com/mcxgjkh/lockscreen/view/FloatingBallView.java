package com.mcxgjkh.lockscreen.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 悬浮球：Canvas 自绘（无图片资源、无动画），一圈描边 + 三个点。
 * 尺寸由布局里的 @dimen/overlay_ball 决定，手表上自动变小。
 */
public class FloatingBallView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public FloatingBallView(Context context) {
        this(context, null);
    }

    public FloatingBallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.parseColor("#D91B2A41"));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1.4f));
        ring.setColor(Color.parseColor("#66FFFFFF"));
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(Color.WHITE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(1.5f);
        canvas.drawCircle(cx, cy, r, fill);
        canvas.drawCircle(cx, cy, r, ring);
        float gap = r * 0.34f;
        float dr = Math.max(dp(1.6f), r * 0.11f);
        canvas.drawCircle(cx - gap, cy, dr, dot);
        canvas.drawCircle(cx, cy, dr, dot);
        canvas.drawCircle(cx + gap, cy, dr, dot);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        setAlpha(pressed ? 0.65f : 1f);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
