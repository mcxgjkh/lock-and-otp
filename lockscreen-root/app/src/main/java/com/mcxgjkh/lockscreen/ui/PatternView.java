package com.mcxgjkh.lockscreen.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 零依赖的 3x3 图案解锁控件（不引用任何 support/AndroidX 库）。
 * - 触摸采样：点中即选中，移动过程中按"点到线段距离"补选被划过的点；
 * - 回调只返回点位序号（0~8），不含任何明文，调用方直接交给 CredentialStore 做哈希比对；
 * - 使用固定颜色/固定半径绘制，无动画、无定时刷新，空闲时不重绘。
 */
public class PatternView extends View {

    public interface OnPatternListener {
        void onPatternDetected(int[] pattern);
    }

    private static final int DOT_COUNT = 9;
    private static final int MIN_LENGTH = 4;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Integer> selected = new ArrayList<Integer>(DOT_COUNT);
    private final float[] cx = new float[DOT_COUNT];
    private final float[] cy = new float[DOT_COUNT];

    private OnPatternListener listener;
    private float step;
    private float dotRadius;
    private float touchX;
    private float touchY;
    private boolean drawing;
    private boolean errorState;

    public PatternView(Context context) {
        this(context, null);
    }

    public PatternView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dotRadius = dp(7f);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(Color.parseColor("#40FFFFFF"));

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2f));
        ringPaint.setColor(Color.parseColor("#4FC3F7"));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(4f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(Color.parseColor("#4FC3F7"));
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setOnPatternListener(OnPatternListener l) {
        this.listener = l;
    }

    public void reset() {
        selected.clear();
        drawing = false;
        errorState = false;
        invalidate();
    }

    public void setErrorState(boolean error) {
        errorState = error;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int size = Math.min(w, h);
        super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float side = Math.min(w, h);
        step = side / 3f;
        for (int i = 0; i < DOT_COUNT; i++) {
            cx[i] = step * 0.5f + step * (i % 3);
            cy[i] = step * 0.5f + step * (i / 3);
        }
        dotRadius = Math.max(dp(6f), step * 0.09f);
        linePaint.setStrokeWidth(Math.max(dp(3f), step * 0.055f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int accent = errorState ? Color.parseColor("#FF5252") : Color.parseColor("#4FC3F7");
        linePaint.setColor(accent);
        ringPaint.setColor(accent);

        // 连线
        for (int i = 1; i < selected.size(); i++) {
            int a = selected.get(i - 1);
            int b = selected.get(i);
            canvas.drawLine(cx[a], cy[a], cx[b], cy[b], linePaint);
        }
        if (drawing && !selected.isEmpty()) {
            int last = selected.get(selected.size() - 1);
            canvas.drawLine(cx[last], cy[last], touchX, touchY, linePaint);
        }

        // 圆点
        for (int i = 0; i < DOT_COUNT; i++) {
            boolean on = selected.contains(Integer.valueOf(i));
            if (on) {
                ringPaint.setAlpha(255);
                canvas.drawCircle(cx[i], cy[i], dotRadius * 2.2f, ringPaint);
                dotPaint.setColor(accent);
            } else {
                dotPaint.setColor(Color.parseColor("#40FFFFFF"));
            }
            canvas.drawCircle(cx[i], cy[i], dotRadius, dotPaint);
        }
        ringPaint.setAlpha(255);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                selected.clear();
                errorState = false;
                drawing = true;
                touchX = event.getX();
                touchY = event.getY();
                tryAddDot(touchX, touchY);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!drawing) {
                    return true;
                }
                float x = event.getX();
                float y = event.getY();
                if (!selected.isEmpty()) {
                    addCrossedDots(selected.get(selected.size() - 1), x, y);
                }
                touchX = x;
                touchY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drawing) {
                    drawing = false;
                    if (selected.size() >= MIN_LENGTH) {
                        int[] result = new int[selected.size()];
                        for (int i = 0; i < result.length; i++) {
                            result[i] = selected.get(i);
                        }
                        if (listener != null) {
                            listener.onPatternDetected(result);
                        }
                    } else {
                        selected.clear();
                    }
                    invalidate();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void tryAddDot(float x, float y) {
        for (int i = 0; i < DOT_COUNT; i++) {
            float dx = x - cx[i];
            float dy = y - cy[i];
            if (dx * dx + dy * dy <= (step * 0.30f) * (step * 0.30f)) {
                addIfAbsent(i);
                return;
            }
        }
    }

    private void addCrossedDots(int lastIndex, float x, float y) {
        float threshold = step * 0.28f;
        for (int i = 0; i < DOT_COUNT; i++) {
            if (selected.contains(Integer.valueOf(i))) {
                continue;
            }
            float d = distanceToSegment(cx[i], cy[i], cx[lastIndex], cy[lastIndex], x, y);
            if (d < threshold) {
                addIfAbsent(i);
            }
        }
    }

    private void addIfAbsent(int index) {
        if (!selected.contains(Integer.valueOf(index))) {
            selected.add(Integer.valueOf(index));
        }
    }

    private static float distanceToSegment(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len2 = dx * dx + dy * dy;
        if (len2 <= 0.0001f) {
            return (float) Math.hypot(px - x1, py - y1);
        }
        float t = ((px - x1) * dx + (py - y1) * dy) / len2;
        if (t < 0f) {
            t = 0f;
        } else if (t > 1f) {
            t = 1f;
        }
        float projX = x1 + t * dx;
        float projY = y1 + t * dy;
        return (float) Math.hypot(px - projX, py - projY);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
