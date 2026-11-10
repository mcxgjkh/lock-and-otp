package com.mcxgjkh.otpvault.otp;

import java.io.ByteArrayOutputStream;

/**
 * RFC 4648 Base32（无填充）。手写实现而非依赖第三方库：
 * 验证器只需要这 60 行代码，省一个库就省一份体积与一份供应链风险。
 *
 * 容错：忽略空格/制表/换行/短横/等号；大小写不敏感；
 * 兼容常见手抄错误 0→O、1→L、8→B。
 */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int[] MAP = new int[128];

    static {
        for (int i = 0; i < MAP.length; i++) {
            MAP[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            MAP[ALPHABET[i]] = i;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            MAP[c] = MAP[Character.toUpperCase(c)];
        }
        MAP['0'] = 14; // 'O'
        MAP['1'] = 11; // 'L'
        MAP['8'] = 1;  // 'B'
    }

    private Base32() {
    }

    public static byte[] decode(String input) {
        if (input == null) {
            return null;
        }
        int buffer = 0;
        int bits = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '-' || c == '=' || c == '_') {
                continue;
            }
            if (c >= MAP.length || MAP[c] < 0) {
                return null;
            }
            buffer = (buffer << 5) | MAP[c];
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(ALPHABET[(buffer >> bits) & 0x1F]);
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        return sb.toString();
    }

    /** 统一成大写、去掉分隔符，用于存储与展示比对。 */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '-' || c == '=' || c == '_') {
                continue;
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /** 每 4 个字符插空格，肉眼核对时不易看错。 */
    public static String group(String normalized) {
        if (normalized == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(normalized.length() + normalized.length() / 4);
        for (int i = 0; i < normalized.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append(' ');
            }
            sb.append(normalized.charAt(i));
        }
        return sb.toString();
    }

    public static boolean isValid(String input) {
        byte[] raw = decode(input);
        return raw != null && raw.length > 0;
    }
}
