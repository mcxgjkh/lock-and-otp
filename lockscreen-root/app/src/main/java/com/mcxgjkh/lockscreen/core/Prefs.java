package com.mcxgjkh.lockscreen.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 唯一的状态存储点：SharedPreferences（MODE_PRIVATE，随卸载清除）。
 * 不写任何文件、不建数据库 —— 单测/排障成本最低，也避免文件句柄常驻。
 */
public final class Prefs {

    private static final String FILE = "lock_prefs";

    public static final String K_MODE = "cred_mode";           // "" | "pin" | "pattern"
    public static final String K_PIN_HASH = "pin_hash";
    public static final String K_PIN_LEN = "pin_len";
    public static final String K_PATTERN_HASH = "pattern_hash";
    public static final String K_FAILED = "failed_count";
    public static final String K_LOCKOUT = "lockout_until";
    public static final String K_NOTIFY = "notify_enabled";
    public static final String K_STRICT = "strict_mode";
    public static final String K_PERSIST = "root_persistent";
    public static final String K_SHAPE = "screen_shape";
    public static final String K_OVERLAY = "overlay_enabled";
    public static final String K_OVERLAY_MODE = "overlay_mode";
    public static final String K_CLOCK_HASH = "clock_hash";
    public static final String K_CLOCK_LEN = "clock_len";
    public static final String K_CLOCK_MODE = "clock_mode";
    public static final String K_CLOCK_HINT = "clock_hint";
    public static final String K_FAST_KEYS = "fast_keys";
    public static final String K_CLIP_MONITOR = "clip_monitor";
    public static final String K_ECO_APPLIED = "eco_applied";
    public static final String K_ECO_BRIGHTNESS = "eco_brightness";
    public static final String K_ECO_BRIGHTNESS_MODE = "eco_brightness_mode";
    public static final String K_ECO_WIFI = "eco_wifi";
    public static final String K_ECO_BT = "eco_bt";
    public static final String K_ECO_DATA = "eco_data";

    private static Prefs sInstance;

    private final SharedPreferences sp;

    private Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context ctx) {
        if (sInstance == null) {
            sInstance = new Prefs(ctx);
        }
        return sInstance;
    }

    public String mode() {
        return sp.getString(K_MODE, "");
    }

    public void setMode(String mode) {
        sp.edit().putString(K_MODE, mode).apply();
    }

    public String pinHash() {
        return sp.getString(K_PIN_HASH, null);
    }

    public int pinLen() {
        return sp.getInt(K_PIN_LEN, 0);
    }

    public void putPin(String hash, int len) {
        sp.edit().putString(K_PIN_HASH, hash).putInt(K_PIN_LEN, len).putString(K_MODE, "pin").apply();
    }

    public String patternHash() {
        return sp.getString(K_PATTERN_HASH, null);
    }

    public void clearPin() {
        sp.edit().remove(K_PIN_HASH).remove(K_PIN_LEN).apply();
    }

    public void clearPattern() {
        sp.edit().remove(K_PATTERN_HASH).apply();
    }

    public void putPattern(String hash) {
        sp.edit().putString(K_PATTERN_HASH, hash).putString(K_MODE, "pattern").apply();
    }

    public int failed() {
        return sp.getInt(K_FAILED, 0);
    }

    public void setFailed(int n) {
        sp.edit().putInt(K_FAILED, n).apply();
    }

    public long lockoutUntil() {
        return sp.getLong(K_LOCKOUT, 0L);
    }

    public void setLockoutUntil(long ts) {
        sp.edit().putLong(K_LOCKOUT, ts).apply();
    }

    public boolean notifyEnabled() {
        return sp.getBoolean(K_NOTIFY, false);
    }

    public void setNotifyEnabled(boolean v) {
        sp.edit().putBoolean(K_NOTIFY, v).apply();
    }

    public boolean strictMode() {
        return sp.getBoolean(K_STRICT, true);
    }

    public void setStrictMode(boolean v) {
        sp.edit().putBoolean(K_STRICT, v).apply();
    }

    /** 常驻 su 会话：默认开（解锁更快），关掉则每次锁屏/解锁现起一次 su。 */
    public boolean persistentShell() {
        return sp.getBoolean(K_PERSIST, true);
    }

    public void setPersistentShell(boolean v) {
        sp.edit().putBoolean(K_PERSIST, v).apply();
    }

    public int screenShape() {
        return sp.getInt(K_SHAPE, 0);
    }

    public void setScreenShape(int shape) {
        sp.edit().putInt(K_SHAPE, shape).apply();
    }

    public boolean overlayEnabled() {
        return sp.getBoolean(K_OVERLAY, false);
    }

    public void setOverlayEnabled(boolean v) {
        sp.edit().putBoolean(K_OVERLAY, v).apply();
    }

    /** 悬浮球模式：0 = 全能，1 = 超级省电。 */
    public int overlayMode() {
        return sp.getInt(K_OVERLAY_MODE, 0);
    }

    public void setOverlayMode(int mode) {
        sp.edit().putInt(K_OVERLAY_MODE, mode).apply();
    }

    public String clockHash() {
        return sp.getString(K_CLOCK_HASH, null);
    }

    public int clockLen() {
        return sp.getInt(K_CLOCK_LEN, 0);
    }

    public void putClock(String hash, int len) {
        sp.edit().putString(K_CLOCK_HASH, hash).putInt(K_CLOCK_LEN, len).apply();
    }

    public void clearClock() {
        sp.edit().remove(K_CLOCK_HASH).remove(K_CLOCK_LEN).apply();
    }

    /** 是否处于"只显示时间"的时钟模式（用于压制悬浮球）。 */
    public boolean clockMode() {
        return sp.getBoolean(K_CLOCK_MODE, false);
    }

    public void setClockMode(boolean v) {
        sp.edit().putBoolean(K_CLOCK_MODE, v).apply();
    }

    /** 时钟界面是否显示"长按返回"提示，默认关闭（更隐蔽）。 */
    /** 极速按键：用常驻注入器代替 input keyevent，失败自动回退。 */
    public boolean fastKeys() {
        return sp.getBoolean(K_FAST_KEYS, true);
    }

    public void setFastKeys(boolean v) {
        sp.edit().putBoolean(K_FAST_KEYS, v).apply();
    }

    /** 接管剪贴板：常驻监听所有复制事件。 */
    public boolean clipMonitor() {
        return sp.getBoolean(K_CLIP_MONITOR, true);
    }

    public void setClipMonitor(boolean v) {
        sp.edit().putBoolean(K_CLIP_MONITOR, v).apply();
    }

    public boolean clockHint() {
        return sp.getBoolean(K_CLOCK_HINT, false);
    }

    public void setClockHint(boolean v) {
        sp.edit().putBoolean(K_CLOCK_HINT, v).apply();
    }

    public boolean ecoApplied() {
        return sp.getBoolean(K_ECO_APPLIED, false);
    }

    public void setEcoApplied(boolean v) {
        sp.edit().putBoolean(K_ECO_APPLIED, v).apply();
    }

    public int ecoBrightness() {
        return sp.getInt(K_ECO_BRIGHTNESS, 128);
    }

    public int ecoBrightnessMode() {
        return sp.getInt(K_ECO_BRIGHTNESS_MODE, 1);
    }

    public int ecoWifi() {
        return sp.getInt(K_ECO_WIFI, 1);
    }

    public int ecoBt() {
        return sp.getInt(K_ECO_BT, 0);
    }

    public int ecoData() {
        return sp.getInt(K_ECO_DATA, 1);
    }

    public void saveEcoSnapshot(int brightness, int mode, int wifi, int bt, int data) {
        sp.edit()
                .putInt(K_ECO_BRIGHTNESS, brightness)
                .putInt(K_ECO_BRIGHTNESS_MODE, mode)
                .putInt(K_ECO_WIFI, wifi)
                .putInt(K_ECO_BT, bt)
                .putInt(K_ECO_DATA, data)
                .apply();
    }

    public boolean hasCredential() {
        String m = mode();
        if ("pin".equals(m)) {
            return pinHash() != null;
        }
        if ("pattern".equals(m)) {
            return patternHash() != null;
        }
        return false;
    }
}
