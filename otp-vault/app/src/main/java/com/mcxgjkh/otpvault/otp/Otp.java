package com.mcxgjkh.otpvault.otp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 4226 (HOTP) / RFC 6238 (TOTP) 的纯实现。
 * 无网络、无时钟服务、无第三方库；一次 HMAC 计算是微秒级，因此界面每秒刷新也不会有可测量的耗电。
 */
public final class Otp {

    public static final String SHA1 = "SHA1";
    public static final String SHA256 = "SHA256";
    public static final String SHA512 = "SHA512";

    private Otp() {
    }

    public static byte[] hmac(String algorithm, byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("Hmac" + normalizeAlgorithm(algorithm));
            mac.init(new SecretKeySpec(key, mac.getAlgorithm()));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable: " + algorithm, e);
        }
    }

    /**
     * @param counter HOTP 计数器 / TOTP 时间步数
     * @return 指定位数的十进制验证码（左侧补零）
     */
    public static String generate(byte[] key, long counter, int digits, String algorithm) {
        if (key == null || key.length == 0) {
            return "";
        }
        int len = digits >= 8 ? 8 : 6;
        byte[] hash = hmac(algorithm, key, counterBytes(counter));
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int mod = (len == 8) ? 100000000 : 1000000;
        String s = Integer.toString(binary % mod);
        StringBuilder sb = new StringBuilder(len);
        for (int i = s.length(); i < len; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    public static long totpCounter(long timeMillis, int periodSeconds, int offsetSeconds) {
        int period = periodSeconds > 0 ? periodSeconds : 30;
        long t = timeMillis / 1000L + offsetSeconds;
        if (t < 0L) {
            t = 0L;
        }
        return t / period;
    }

    /** 当前时间步剩余秒数（1..period），用于倒计时与进度条。 */
    public static int remainingSeconds(long timeMillis, int periodSeconds, int offsetSeconds) {
        int period = periodSeconds > 0 ? periodSeconds : 30;
        long t = timeMillis / 1000L + offsetSeconds;
        if (t < 0L) {
            t = 0L;
        }
        int left = (int) (period - (t % period));
        return left <= 0 ? period : left;
    }

    /** 6 位显示成 "123 456"，8 位显示成 "1234 5678"。 */
    public static String format(String code) {
        if (code == null) {
            return "";
        }
        int n = code.length();
        if (n == 6) {
            return code.substring(0, 3) + " " + code.substring(3);
        }
        if (n == 8) {
            return code.substring(0, 4) + " " + code.substring(4);
        }
        return code;
    }

    public static String normalizeAlgorithm(String algorithm) {
        if (algorithm == null) {
            return SHA1;
        }
        String a = algorithm.trim().toUpperCase();
        if (a.startsWith("HMAC")) {
            a = a.substring(4);
        }
        if ("SHA256".equals(a) || "SHA512".equals(a)) {
            return a;
        }
        return SHA1;
    }

    public static boolean isSupportedAlgorithm(String algorithm) {
        String a = normalizeAlgorithm(algorithm);
        return SHA1.equals(a) || SHA256.equals(a) || SHA512.equals(a);
    }

    private static byte[] counterBytes(long counter) {
        byte[] b = new byte[8];
        long v = counter;
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (v & 0xFF);
            v >>= 8;
        }
        return b;
    }
}
