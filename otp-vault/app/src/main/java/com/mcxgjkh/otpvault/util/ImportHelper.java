package com.mcxgjkh.otpvault.util;

import com.mcxgjkh.otpvault.data.Account;
import com.mcxgjkh.otpvault.otp.Base32;
import com.mcxgjkh.otpvault.otp.OtpauthUri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入解析：两种输入统一走这里。
 *
 *   1. JSON 文件 —— 支持本应用导出的 {accounts:[...]}、裸数组 [...]、单个账户对象，
 *      以及数组里混入 otpauth 字符串的情况；每个对象若没有 secret，会尝试用其中的
 *      otpauth / uri 字段反解。
 *   2. otpauth 链接 —— 支持多行、空格分隔的批量粘贴。
 *
 * 所有条目都会校验密钥是否为合法 Base32，非法条目直接丢弃，避免污染密钥库。
 */
public final class ImportHelper {

    public static final int KIND_NONE = 0;
    public static final int KIND_OK = 1;
    public static final int KIND_MIGRATION = 2;

    private ImportHelper() {
    }

    /** 自动识别 JSON / otpauth 文本。 */
    public static List<Account> parse(String raw) {
        if (raw == null) {
            return new ArrayList<Account>();
        }
        String text = raw.trim();
        if (text.length() == 0) {
            return new ArrayList<Account>();
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            List<Account> json = parseJson(text);
            if (!json.isEmpty()) {
                return json;
            }
        }
        return parseUris(text);
    }

    /** 是否是 Google Authenticator 的迁移包（暂不支持，用于给出明确提示）。 */
    public static boolean isMigration(String raw) {
        return raw != null && raw.contains("otpauth-migration://");
    }

    // ---------------------------------------------------------------- JSON

    public static List<Account> parseJson(String raw) {
        List<Account> out = new ArrayList<Account>();
        try {
            String text = raw.trim();
            if (text.startsWith("[")) {
                collect(new JSONArray(text), out);
            } else {
                JSONObject root = new JSONObject(text);
                JSONArray arr = root.optJSONArray("accounts");
                if (arr == null) {
                    arr = root.optJSONArray("items");
                }
                if (arr != null) {
                    collect(arr, out);
                } else {
                    Account single = fromObject(root);
                    if (single != null) {
                        out.add(single);
                    }
                }
                if (out.isEmpty()) {
                    JSONArray uris = root.optJSONArray("uris");
                    if (uris == null) {
                        uris = root.optJSONArray("otpauth");
                    }
                    if (uris != null) {
                        for (int i = 0; i < uris.length(); i++) {
                            Account a = OtpauthUri.parse(uris.optString(i, ""));
                            if (a != null) {
                                out.add(a);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 交给调用方按"无有效条目"处理
        }
        return out;
    }

    private static void collect(JSONArray arr, List<Account> out) {
        for (int i = 0; i < arr.length(); i++) {
            Object el = arr.opt(i);
            if (el instanceof JSONObject) {
                Account a = fromObject((JSONObject) el);
                if (a != null) {
                    out.add(a);
                }
            } else if (el instanceof String) {
                Account a = OtpauthUri.parse((String) el);
                if (a != null) {
                    out.add(a);
                }
            }
        }
    }

    private static Account fromObject(JSONObject o) {
        if (o == null) {
            return null;
        }
        String secret = normalizeSecret(o.optString("secret", ""));
        if (!isValidSecret(secret)) {
            // 只有 otpauth / uri 字段的条目（部分第三方导出格式）
            String uri = o.optString("otpauth", "");
            if (uri.length() == 0) {
                uri = o.optString("uri", "");
            }
            return OtpauthUri.parse(uri);
        }
        Account a = new Account();
        a.issuer = o.optString("issuer", o.optString("service", ""));
        a.account = o.optString("account",
                o.optString("email", o.optString("name", "")));
        a.secret = secret;
        a.type = "HOTP".equalsIgnoreCase(o.optString("type", "TOTP")) ? "HOTP" : "TOTP";
        a.algorithm = normalizeAlgorithm(o.optString("algorithm", "SHA1"));
        a.digits = clampDigits(o.optInt("digits", 6));
        a.period = clampPeriod(o.optInt("period", 30));
        a.counter = Math.max(0L, o.optLong("counter", 0L));
        a.createdAt = o.optLong("createdAt", 0L);
        String id = o.optString("id", "");
        if (id.length() > 0) {
            a.id = id;
        }
        if (a.issuer.length() == 0 && a.account.length() == 0) {
            return null;
        }
        return a;
    }

    // ---------------------------------------------------------------- otpauth

    public static List<Account> parseUris(String raw) {
        List<Account> out = new ArrayList<Account>();
        if (raw == null) {
            return out;
        }
        for (String token : raw.split("\\s+")) {
            Account a = OtpauthUri.parse(token);
            if (a != null) {
                out.add(a);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 校验

    public static boolean isValidSecret(String secret) {
        if (secret == null || secret.length() < 8) {
            return false;
        }
        byte[] raw = Base32.decode(secret);
        return raw != null && raw.length > 0;
    }

    public static String normalizeSecret(String secret) {
        if (secret == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(secret.length());
        for (int i = 0; i < secret.length(); i++) {
            char c = secret.charAt(i);
            if (c == ' ' || c == '-' || c == '\t') {
                continue;
            }
            sb.append(Character.toUpperCase(c));
        }
        int pad = sb.indexOf("=");
        if (pad >= 0) {
            sb.setLength(pad);
        }
        return sb.toString();
    }

    public static String normalizeAlgorithm(String alg) {
        if (alg == null) {
            return "SHA1";
        }
        String s = alg.replace("-", "").replace("_", "").trim().toUpperCase();
        if ("SHA256".equals(s) || "SHA512".equals(s)) {
            return s;
        }
        return "SHA1";
    }

    private static int clampDigits(int d) {
        return (d == 8) ? 8 : 6;
    }

    private static int clampPeriod(int p) {
        if (p < 10 || p > 300) {
            return 30;
        }
        return p;
    }
}
