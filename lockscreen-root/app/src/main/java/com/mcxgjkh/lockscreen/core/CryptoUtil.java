package com.mcxgjkh.lockscreen.core;

import android.util.Base64;

import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 凭证哈希工具。API19 上没有 PBKDF2WithHmacSHA256（API26 才提供），
 * 因此统一使用 PBKDF2WithHmacSHA1 + 30000 轮 + 128bit 随机盐，
 * 对 4~12 位本地 PIN 来说足以抵抗离线暴力破解的性价比区间。
 */
public final class CryptoUtil {

    public static final int ITERATIONS = 30000;
    public static final int KEY_BITS = 256;

    private static final SecureRandom RNG = new SecureRandom();

    private CryptoUtil() {
    }

    public static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 定长比较，避免通过耗时差异推断哈希前缀。 */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    public static String b64(byte[] raw) {
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    public static byte[] unb64(String s) {
        try {
            return Base64.decode(s, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
