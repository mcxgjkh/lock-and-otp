package com.mcxgjkh.lockscreen.core;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import com.mcxgjkh.lockscreen.LockAdminReceiver;

/**
 * 锁屏动作的唯一出口。所有方法都是阻塞的，调用方必须放在工作线程。
 *
 * 关屏策略（按优先级）：
 *   1. Root: input keyevent 223 (API20+ KEYCODE_SLEEP) —— 单向休眠，不会误点亮；
 *      API19 上没有 SLEEP 键码，退回 KEYCODE_POWER(26)，但**必须先确认屏幕是亮的**，否则会反手点亮屏幕。
 *   2. 设备管理器 lockNow()（无 Root 时的降级路径，仅需 force-lock 权限）。
 * 执行后都会轮询 800ms 校验屏幕确实熄灭，避免"命令返回 0 但系统没执行"的假成功。
 */
public final class LockEngine {

    public static final int OK = 0;
    public static final int OK_FALLBACK_DPM = 1;
    public static final int FAILED = 2;

    private static final String TAG = "LockEngine";
    private static final String CMD_SLEEP = "input keyevent 223";
    private static final String CMD_POWER = "input keyevent 26";
    private static final String CMD_DISMISS_KEYGUARD = "wm dismiss-keyguard";
    private static final String CMD_MENU = "input keyevent 82";

    private LockEngine() {
    }

    public static boolean isScreenOn(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 20) {
            return pm.isInteractive();
        }
        return pm.isScreenOn();
    }

    public static int lockScreen(Context ctx) {
        if (!isScreenOn(ctx)) {
            return OK;
        }
        if (rootLock(ctx) && verifyScreenOff(ctx)) {
            releaseShellIfNeeded(ctx);
            return OK;
        }
        if (dpmLock(ctx)) {
            releaseShellIfNeeded(ctx);
            return OK_FALLBACK_DPM;
        }
        releaseShellIfNeeded(ctx);
        return FAILED;
    }

    /**
     * 悬浮球专用快路径：只发命令，不做 800ms 熄屏轮询。
     * 悬浮球是"随手一按"的场景，多等 200~800ms 体感很差；
     * 命令失败仍会走 DPM 兜底，真失败时用户会看到屏幕没灭、再点一次即可。
     */
    public static int lockScreenFast(Context ctx) {
        if (!isScreenOn(ctx)) {
            return OK;
        }
        if (rootLock(ctx)) {
            releaseShellIfNeeded(ctx);
            return OK;
        }
        if (dpmLock(ctx)) {
            releaseShellIfNeeded(ctx);
            return OK_FALLBACK_DPM;
        }
        releaseShellIfNeeded(ctx);
        return FAILED;
    }

    /** 验证通过后调用：先让系统锁屏消失，再由 Activity 收尾。 */
    public static void dismissKeyguard(Context ctx) {
        boolean needShell = Prefs.get(ctx).persistentShell() || RootShell.get().isOpen();
        if (!needShell) {
            return;
        }
        String cmd = Build.VERSION.SDK_INT >= 23 ? CMD_DISMISS_KEYGUARD : CMD_MENU;
        if (!RootShell.get().run(cmd) && Build.VERSION.SDK_INT >= 23) {
            RootShell.get().run(CMD_MENU);
        }
        releaseShellIfNeeded(ctx);
    }

    public static boolean dpmLock(Context ctx) {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName cn = new ComponentName(ctx, LockAdminReceiver.class);
            if (dpm != null && dpm.isAdminActive(cn)) {
                dpm.lockNow();
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "dpm lock failed: " + t.getMessage());
        }
        return false;
    }

    public static boolean isDeviceAdminActive(Context ctx) {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(new ComponentName(ctx, LockAdminReceiver.class));
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- internal

    private static boolean rootLock(Context ctx) {
        RootShell sh = RootShell.get();
        if (sh.run(CMD_SLEEP)) {
            return true;
        }
        // 兜底：仅当屏幕仍然亮着才按电源键，避免把刚熄灭的屏幕又点亮
        return isScreenOn(ctx) && sh.run(CMD_POWER);
    }

    private static boolean verifyScreenOff(Context ctx) {
        for (int i = 0; i < 8; i++) {
            if (!isScreenOn(ctx)) {
                return true;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(TAG, "screen still on after lock command");
        return false;
    }

    private static void releaseShellIfNeeded(Context ctx) {
        if (!Prefs.get(ctx).persistentShell()) {
            RootShell.get().close();
        }
    }
}
