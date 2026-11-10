package com.mcxgjkh.lockscreen.core;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * 写系统安全设置（Settings.Secure）的三级通道：
 *   1) 直接写 —— 若本应用已持有 WRITE_SECURE_SETTINGS；
 *   2) 通过 Root 执行 `pm grant` 授予该权限后再写；
 *   3) 仍失败则用 `settings put secure` 命令兜底（等价于 adb 的写法）。
 *
 * 为什么不用无障碍服务去"点"系统设置页：那需要无障碍权限，本应用明确不申请。
 */
public final class SecureSettings {

    private static final String TAG = "SecureSettings";
    private static final String PERM = "android.permission.WRITE_SECURE_SETTINGS";

    private static volatile boolean sGrantTried;

    private SecureSettings() {
    }

    public static boolean put(Context ctx, String key, String value) {
        if (write(ctx, key, value)) {
            return true;
        }
        if (!sGrantTried) {
            sGrantTried = true;
            try {
                boolean granted = RootShell.get().grantSecureSettings(ctx.getPackageName());
                Log.i(TAG, "pm grant result=" + granted);
            } catch (Throwable t) {
                Log.w(TAG, "pm grant failed: " + t.getMessage());
            }
        }
        if (write(ctx, key, value)) {
            return true;
        }
        return RootShell.get().runSecureSetting(key, value);
    }

    private static boolean write(Context ctx, String key, String value) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            return Settings.Secure.putString(cr, key, value);
        } catch (Throwable t) {
            // SecurityException: 没有 WRITE_SECURE_SETTINGS；继续走 Root 通道
            return false;
        }
    }
}
