package com.mcxgjkh.lockscreen.core;

import android.content.Context;
import android.os.Build;

/**
 * 悬浮球的系统级动作：返回 / 主页 / 最近任务 / 通知栏 / 锁屏。
 *
 * 两条通道：
 *   1) FastKeys：常驻注入器（毫秒级）；
 *   2) RootShell + `input keyevent`：注入器不可用时的兜底（单次 0.5~1.5s，但一定能用）。
 * 全都走 Root，不使用无障碍服务。
 *
 * 约定：所有方法阻塞，必须在工作线程调用。
 */
public final class GlobalActions {

    public static final int KEY_HOME = 3;
    public static final int KEY_BACK = 4;
    public static final int KEY_APP_SWITCH = 187;
    public static final int KEY_POWER = 26;
    public static final int KEY_SLEEP = 223;

    private GlobalActions() {
    }

    public static boolean back(Context ctx) {
        return send(ctx, KEY_BACK);
    }

    public static boolean home(Context ctx) {
        return send(ctx, KEY_HOME);
    }

    public static boolean recents(Context ctx) {
        return send(ctx, KEY_APP_SWITCH);
    }

    /** 展开通知栏：优先用系统命令（原生 cmd，很快），老系统退回下拉手势。 */
    public static boolean expandNotifications(Context ctx) {
        if (Build.VERSION.SDK_INT >= 23
                && RootShell.get().run("cmd statusbar expand-notifications")) {
            return true;
        }
        int[] wh = WatchUi.screenSize(ctx);
        int x = wh[0] / 2;
        int bottom = Math.max(wh[1] / 2, (int) (wh[1] * 0.75f));
        return RootShell.get().runSwipe(x, 2, x, bottom, 220);
    }

    public static int lockScreen(Context ctx) {
        if (!LockEngine.isScreenOn(ctx)) {
            return LockEngine.OK;
        }
        int code = Build.VERSION.SDK_INT >= 20 ? KEY_SLEEP : KEY_POWER;
        if (Prefs.get(ctx).fastKeys() && FastKeys.send(code)) {
            return LockEngine.OK;
        }
        return LockEngine.lockScreenFast(ctx);
    }

    private static boolean send(Context ctx, int code) {
        if (Prefs.get(ctx).fastKeys() && FastKeys.send(code)) {
            return true;
        }
        return RootShell.get().runKeyEvent(code);
    }
}
