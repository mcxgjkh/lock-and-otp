package com.mcxgjkh.lockscreen.core;

import android.content.Context;

/**
 * 凭证的写入与校验。存储格式：v1$iterations$saltBase64$hashBase64，永不存明文。
 * 失败次数与锁定截止时间同样落盘，杀进程/重启都无法重置，避免"强杀绕过"。
 */
public final class CredentialStore {

    public static final int MIN_PIN = 4;
    public static final int MAX_PIN = 12;
    public static final int MIN_PATTERN = 4;

    private static final int MAX_TRIES = 5;
    private static final long BASE_LOCKOUT_MS = 30_000L;
    private static final long MAX_LOCKOUT_MS = 30 * 60_000L;
    private static final String PREFIX = "v1$";

    private CredentialStore() {
    }

    public static boolean setPin(Context ctx, String pin) {
        if (pin == null || pin.length() < MIN_PIN || pin.length() > MAX_PIN) {
            return false;
        }
        Prefs p = Prefs.get(ctx);
        byte[] salt = CryptoUtil.randomBytes(16);
        byte[] hash = CryptoUtil.pbkdf2(pin.toCharArray(), salt, CryptoUtil.ITERATIONS, CryptoUtil.KEY_BITS);
        String rec = PREFIX + CryptoUtil.ITERATIONS + "$" + CryptoUtil.b64(salt) + "$" + CryptoUtil.b64(hash);
        p.putPin(rec, pin.length());
        // 切换凭证类型时清掉旧图案哈希，避免"旧凭证仍可解锁"
        p.clearPattern();
        p.setFailed(0);
        p.setLockoutUntil(0L);
        return true;
    }

    public static boolean setPattern(Context ctx, int[] seq) {
        if (seq == null || seq.length < MIN_PATTERN) {
            return false;
        }
        Prefs p = Prefs.get(ctx);
        byte[] salt = CryptoUtil.randomBytes(16);
        byte[] hash = CryptoUtil.pbkdf2(encodePattern(seq).toCharArray(), salt,
                CryptoUtil.ITERATIONS, CryptoUtil.KEY_BITS);
        String rec = PREFIX + CryptoUtil.ITERATIONS + "$" + CryptoUtil.b64(salt) + "$" + CryptoUtil.b64(hash);
        p.putPattern(rec);
        p.clearPin();
        p.setFailed(0);
        p.setLockoutUntil(0L);
        return true;
    }

    /** 时钟密码：只用于进入"只显示时间"界面，独立哈希、独立长度。 */
    public static boolean setClockPin(Context ctx, String pin) {
        if (pin == null || pin.length() < MIN_PIN || pin.length() > MAX_PIN) {
            return false;
        }
        // 与主密码相同会让"双密码"失去意义，直接拒绝
        if (verifyPin(ctx, pin)) {
            return false;
        }
        Prefs p = Prefs.get(ctx);
        byte[] salt = CryptoUtil.randomBytes(16);
        byte[] hash = CryptoUtil.pbkdf2(pin.toCharArray(), salt, CryptoUtil.ITERATIONS, CryptoUtil.KEY_BITS);
        String rec = PREFIX + CryptoUtil.ITERATIONS + "$" + CryptoUtil.b64(salt) + "$" + CryptoUtil.b64(hash);
        p.putClock(rec, pin.length());
        return true;
    }

    public static boolean verifyClockPin(Context ctx, String pin) {
        if (pin == null || pin.length() == 0 || isLockedOut(ctx)) {
            return false;
        }
        return verifyRecord(Prefs.get(ctx).clockHash(), pin.toCharArray());
    }

    /** 只有当前凭证类型是"数字密码"时，PIN 才能作为主密码使用。 */
    private static boolean pinIsPrimary(Context ctx) {
        return "pin".equals(Prefs.get(ctx).mode());
    }

    private static boolean patternIsPrimary(Context ctx) {
        return "pattern".equals(Prefs.get(ctx).mode());
    }

    public static boolean hasClockPin(Context ctx) {
        return Prefs.get(ctx).clockHash() != null;
    }

    public static void clearClockPin(Context ctx) {
        Prefs.get(ctx).clearClock();
    }

    public static boolean verifyPin(Context ctx, String pin) {
        if (pin == null || pin.length() == 0 || isLockedOut(ctx)) {
            return false;
        }
        if (!pinIsPrimary(ctx)) {
            return false;
        }
        return verifyRecord(Prefs.get(ctx).pinHash(), pin.toCharArray());
    }

    public static boolean verifyPattern(Context ctx, int[] seq) {
        if (seq == null || seq.length < MIN_PATTERN || isLockedOut(ctx)) {
            return false;
        }
        if (!patternIsPrimary(ctx)) {
            return false;
        }
        return verifyRecord(Prefs.get(ctx).patternHash(), encodePattern(seq).toCharArray());
    }

    /** 记录一次失败，返回累计失败次数；达到阈值后写入指数退避的锁定截止时间。 */
    public static int registerFailure(Context ctx) {
        Prefs p = Prefs.get(ctx);
        int failed = p.failed() + 1;
        p.setFailed(failed);
        if (failed >= MAX_TRIES) {
            int step = Math.min(failed - MAX_TRIES, 6);
            long delay = Math.min(BASE_LOCKOUT_MS << step, MAX_LOCKOUT_MS);
            p.setLockoutUntil(System.currentTimeMillis() + delay);
        }
        return failed;
    }

    public static void resetFailures(Context ctx) {
        Prefs p = Prefs.get(ctx);
        p.setFailed(0);
        p.setLockoutUntil(0L);
    }

    public static boolean isLockedOut(Context ctx) {
        return lockoutRemainingMs(ctx) > 0;
    }

    public static long lockoutRemainingMs(Context ctx) {
        long until = Prefs.get(ctx).lockoutUntil();
        long left = until - System.currentTimeMillis();
        return left > 0 ? left : 0L;
    }

    public static int remainingTries(Context ctx) {
        int left = MAX_TRIES - Prefs.get(ctx).failed();
        return left > 0 ? left : 0;
    }

    public static String encodePattern(int[] seq) {
        StringBuilder sb = new StringBuilder(seq.length * 2);
        for (int i = 0; i < seq.length; i++) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(seq[i]);
        }
        return sb.toString();
    }

    public static String modeLabel(Context ctx) {
        String m = Prefs.get(ctx).mode();
        if ("pin".equals(m)) {
            return "pin";
        }
        if ("pattern".equals(m)) {
            return "pattern";
        }
        return "";
    }

    private static boolean verifyRecord(String record, char[] input) {
        if (record == null || record.isEmpty()) {
            return false;
        }
        String[] parts = record.split("\\$", 4);
        if (parts.length != 4) {
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        byte[] salt = CryptoUtil.unb64(parts[2]);
        byte[] expected = CryptoUtil.unb64(parts[3]);
        if (salt == null || expected == null || iterations < 1000) {
            return false;
        }
        byte[] actual = CryptoUtil.pbkdf2(input, salt, iterations, expected.length * 8);
        return CryptoUtil.constantTimeEquals(expected, actual);
    }
}
