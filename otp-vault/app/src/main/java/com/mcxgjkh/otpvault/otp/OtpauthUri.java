package com.mcxgjkh.otpvault.otp;

import com.mcxgjkh.otpvault.data.Account;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * otpauth:// URI 的解析与生成（Key Uri Format）。
 * 手写解析而非用 android.net.Uri，是为了让这段逻辑可以脱离 Android 单测。
 */
public final class OtpauthUri {

    private OtpauthUri() {
    }

    public static Account parse(String uri) {
        if (uri == null) {
            return null;
        }
        String u = uri.trim();
        if (u.length() < 12 || !u.regionMatches(true, 0, "otpauth://", 0, 10)) {
            return null;
        }
        String rest = u.substring(10);
        int q = rest.indexOf('?');
        String pathPart = q >= 0 ? rest.substring(0, q) : rest;
        String query = q >= 0 ? rest.substring(q + 1) : "";
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        int slash = pathPart.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String type = pathPart.substring(0, slash).toUpperCase();
        if (!"TOTP".equals(type) && !"HOTP".equals(type)) {
            return null;
        }
        String label = decode(pathPart.substring(slash + 1)).trim();
        Map<String, String> p = parseQuery(query);
        String secret = p.get("secret");
        if (secret == null || !Base32.isValid(secret)) {
            return null;
        }

        Account a = new Account();
        a.id = UUID.randomUUID().toString();
        a.type = type;
        a.secret = Base32.normalize(secret);
        a.algorithm = Otp.normalizeAlgorithm(p.get("algorithm"));
        a.digits = parseInt(p.get("digits"), 6) == 8 ? 8 : 6;
        a.period = parseInt(p.get("period"), 30);
        if (a.period <= 0) {
            a.period = 30;
        }
        a.counter = parseLong(p.get("counter"), 0L);
        a.createdAt = System.currentTimeMillis();

        String paramIssuer = p.get("issuer");
        int colon = label.indexOf(':');
        if (colon > 0) {
            String prefix = label.substring(0, colon).trim();
            a.account = label.substring(colon + 1).trim();
            a.issuer = (paramIssuer != null && paramIssuer.trim().length() > 0) ? paramIssuer.trim() : prefix;
        } else {
            a.account = label;
            a.issuer = paramIssuer == null ? "" : paramIssuer.trim();
        }
        return a;
    }

    public static String build(Account a) {
        if (a == null) {
            return "";
        }
        String label = (a.issuer == null || a.issuer.length() == 0)
                ? (a.account == null ? "" : a.account)
                : a.issuer + ":" + (a.account == null ? "" : a.account);
        StringBuilder sb = new StringBuilder(128);
        sb.append("otpauth://").append(a.isHotp() ? "hotp" : "totp").append('/').append(encode(label));
        sb.append("?secret=").append(a.secret);
        if (a.issuer != null && a.issuer.length() > 0) {
            sb.append("&issuer=").append(encode(a.issuer));
        }
        String alg = Otp.normalizeAlgorithm(a.algorithm);
        if (!Otp.SHA1.equals(alg)) {
            sb.append("&algorithm=").append(alg);
        }
        sb.append("&digits=").append(a.digits == 8 ? 8 : 6);
        if (a.isHotp()) {
            sb.append("&counter=").append(a.counter);
        } else {
            sb.append("&period=").append(a.period > 0 ? a.period : 30);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- internal

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<String, String>();
        if (query == null || query.length() == 0) {
            return map;
        }
        String[] parts = query.split("&");
        for (String part : parts) {
            if (part.length() == 0) {
                continue;
            }
            int eq = part.indexOf('=');
            String k = eq >= 0 ? part.substring(0, eq) : part;
            String v = eq >= 0 ? part.substring(eq + 1) : "";
            map.put(decode(k).toLowerCase(), decode(v));
        }
        return map;
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
