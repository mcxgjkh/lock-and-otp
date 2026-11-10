package com.mcxgjkh.otpvault.util;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

import com.mcxgjkh.otpvault.data.Prefs;

import java.lang.reflect.Field;

/**
 * 方屏 / 圆屏 / 极小屏适配。判定顺序：用户设置 > API23 的 isScreenRound > 反射 > 方形。
 * 老手表 ROM 没有 isScreenRound，所以在设置里保留"屏幕形状"强制项。
 */
public final class WatchUi {

    public static final int SHAPE_AUTO = 0;
    public static final int SHAPE_SQUARE = 1;
    public static final int SHAPE_ROUND = 2;

    private WatchUi() {
    }

    public static boolean isRound(Context c) {
        int shape = Prefs.get(c).screenShape();
        if (shape == SHAPE_SQUARE) {
            return false;
        }
        if (shape == SHAPE_ROUND) {
            return true;
        }
        Configuration cfg = c.getResources().getConfiguration();
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                return cfg.isScreenRound();
            } catch (Throwable ignored) {
                // 个别 ROM 缺失实现
            }
        }
        try {
            Field f = Configuration.class.getField("isScreenRound");
            Object v = f.get(cfg);
            if (v instanceof Boolean) {
                return ((Boolean) v).booleanValue();
            }
        } catch (Throwable ignored) {
            // 字段不存在
        }
        return false;
    }

    public static boolean isCompact(Context c) {
        int[] wh = screenSize(c);
        float density = c.getResources().getDisplayMetrics().density;
        int shortDp = Math.round(Math.min(wh[0], wh[1]) / Math.max(density, 0.1f));
        return shortDp <= 260;
    }

    public static int[] screenSize(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && Build.VERSION.SDK_INT >= 13) {
            try {
                Point p = new Point();
                wm.getDefaultDisplay().getSize(p);
                return new int[]{p.x, p.y};
            } catch (Throwable ignored) {
                // 退回 DisplayMetrics
            }
        }
        return new int[]{
                c.getResources().getDisplayMetrics().widthPixels,
                c.getResources().getDisplayMetrics().heightPixels
        };
    }

    public static void applyRoundInset(View root, Context c) {
        if (root == null || !isRound(c)) {
            return;
        }
        int w = screenSize(c)[0];
        int pad = Math.round(w * 0.055f);
        root.setPadding(root.getPaddingLeft() + pad, root.getPaddingTop() + pad,
                root.getPaddingRight() + pad, root.getPaddingBottom() + pad);
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
