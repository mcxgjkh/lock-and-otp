package com.mcxgjkh.lockscreen.core;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * 系统级超级省电。进入时：
 *   1) 打开系统电池省电模式（low_power + cmd power set-mode）；
 *   2) 关闭 Wi-Fi / 蓝牙 / 移动数据；
 *   3) `am kill-all` 结束后台（缓存）进程 —— 前台应用与前台服务不受影响；
 *   4) 屏幕亮度压到不超过 40 并关闭自动亮度；
 *   5) 三个系统动画倍率归零，减少合成与重绘。
 * 退出时按进入前记录的快照精确还原（网络只恢复原本就开着的）。
 *
 * 所有写入都走 RootShell 的强类型白名单：只有固定 key 与整数/布尔值，不拼接任何外部字符串。
 * 所有方法阻塞，必须在工作线程调用。
 */
public final class PowerSaver {

    private static final String TAG = "PowerSaver";

    private PowerSaver() {
    }

    public static boolean isActive(Context ctx) {
        return Prefs.get(ctx).ecoApplied();
    }

    public static boolean enable(Context ctx) {
        Prefs p = Prefs.get(ctx);
        if (p.ecoApplied()) {
            return true;
        }
        ContentResolver cr = ctx.getContentResolver();
        // 先快照，失败也能还原
        int brightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
        int brightnessMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1);
        int wifi = Settings.Global.getInt(cr, Settings.Global.WIFI_ON, 1);
        int bt = Settings.Global.getInt(cr, Settings.Global.BLUETOOTH_ON, 0);
        int data = Settings.Global.getInt(cr, "mobile_data", 1);
        p.saveEcoSnapshot(brightness, brightnessMode, wifi, bt, data);

        RootShell sh = RootShell.get();
        boolean any = false;
        any |= sh.runSetting("global", "low_power", 1);
        any |= sh.runPowerSaveMode(true);
        sh.runSetting("system", "screen_brightness_mode", 0);
        any |= sh.runSetting("system", "screen_brightness", Math.max(10, Math.min(brightness, 40)));
        sh.runSetting("global", "window_animation_scale", 0);
        sh.runSetting("global", "transition_animation_scale", 0);
        sh.runSetting("global", "animator_duration_scale", 0);
        sh.runSvc("wifi", false);
        sh.runSvc("bluetooth", false);
        sh.runSvc("data", false);
        sh.runKillBackground();

        if (!any) {
            Log.w(TAG, "no root, nothing applied");
            return false;
        }
        p.setEcoApplied(true);
        return true;
    }

    public static boolean disable(Context ctx) {
        return disable(ctx, false);
    }

    /**
     * @param fast 开机广播专用：缩短 su 握手超时，并在第一条命令就失败时立刻放弃，
     *             避免 su 未就绪时把 BroadcastReceiver 拖到超时被杀。
     */
    public static boolean disable(Context ctx, boolean fast) {
        Prefs p = Prefs.get(ctx);
        RootShell sh = RootShell.get();
        if (fast) {
            sh.setOpenTimeout(2500L);
        }
        try {
            boolean any = false;
            any |= sh.runSetting("global", "low_power", 0);
            if (fast && !any) {
                return false;
            }
            any |= sh.runPowerSaveMode(false);
            any |= sh.runSetting("system", "screen_brightness_mode", p.ecoBrightnessMode());
            any |= sh.runSetting("system", "screen_brightness", p.ecoBrightness());
            sh.runSetting("global", "window_animation_scale", 1);
            sh.runSetting("global", "transition_animation_scale", 1);
            sh.runSetting("global", "animator_duration_scale", 1);
            if (p.ecoWifi() == 1) {
                any |= sh.runSvc("wifi", true);
            }
            if (p.ecoBt() == 1) {
                any |= sh.runSvc("bluetooth", true);
            }
            if (p.ecoData() == 1) {
                any |= sh.runSvc("data", true);
            }
            p.setEcoApplied(false);
            return any;
        } finally {
            if (fast) {
                sh.resetOpenTimeout();
            }
        }
    }
}
