package com.mcxgjkh.otpvault.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 轻量设置：时间校准偏移 + 可选的应用访问 PIN。
 * PIN 只存 PBKDF2 哈希（不参与密钥派生，见 README 的取舍说明）。
 */
public final class Prefs {

    private static final String FILE = "otpvault_prefs";
    private static final String K_OFFSET = "time_offset_sec";
    private static final String K_PIN = "pin_record";
    private static final String K_SHAPE = "screen_shape";
    private static final int PIN_ITER = 20000;

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

    public int timeOffset() {
        return sp.getInt(K_OFFSET, 0);
    }

    public void setTimeOffset(int seconds) {
        sp.edit().putInt(K_OFFSET, seconds).apply();
    }

    public int screenShape() {
        return sp.getInt(K_SHAPE, 0);
    }

    public void setScreenShape(int shape) {
        sp.edit().putInt(K_SHAPE, shape).apply();
    }

    public boolean hasPin() {
        return sp.getString(K_PIN, null) != null;
    }

    public boolean setPin(String pin) {
        if (pin == null || pin.length() < 4 || pin.length() > 8) {
            return false;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(pin.toCharArray(), salt, PIN_ITER);
        if (hash == null) {
            return false;
        }
        String record = "v1$" + PIN_ITER + "$"
                + Base64.encodeToString(salt, Base64.NO_WRAP) + "$"
                + Base64.encodeToString(hash, Base64.NO_WRAP);
        sp.edit().putString(K_PIN, record).apply();
        return true;
    }

    public boolean verifyPin(String pin) {
        String record = sp.getString(K_PIN, null);
        if (record == null || pin == null) {
            return false;
        }
        String[] parts = record.split("\\$", 4);
        if (parts.length != 4) {
            return false;
        }
        int iter;
        try {
            iter = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        byte[] salt = Base64.decode(parts[2], Base64.NO_WRAP);
        byte[] expect = Base64.decode(parts[3], Base64.NO_WRAP);
        byte[] actual = pbkdf2(pin.toCharArray(), salt, iter);
        return constantTimeEquals(expect, actual);
    }

    public void clearPin() {
        sp.edit().remove(K_PIN).apply();
    }

    private static byte[] pbkdf2(char[] pin, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            return null;
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
