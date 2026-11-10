package com.mcxgjkh.otpvault.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

/**
 * 把文本送进「锁屏」应用的剪贴板（同签名 ContentProvider）。
 *
 * 这台设备的 ROM 没有 ClipboardService，系统剪贴板写不进去；
 * 但锁屏应用有 root 注入能力，可以把这段文本直接敲进当前输入框，
 * 因此"复制"改成"交给锁屏的剪贴板"，再由悬浮球的「粘贴」完成注入。
 */
public final class ClipBridge {

    private static final Uri URI = Uri.parse("content://com.mcxgjkh.lockscreen.clip/current");

    private ClipBridge() {
    }

    public static boolean push(Context ctx, String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put("text", text);
            return ctx.getContentResolver().insert(URI, cv) != null;
        } catch (Throwable t) {
            // 没装锁屏、或签名不同被权限拦下
            return false;
        }
    }
}
