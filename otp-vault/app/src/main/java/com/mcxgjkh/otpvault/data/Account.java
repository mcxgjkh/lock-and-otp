package com.mcxgjkh.otpvault.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一个 2FA 账户。序列化后整体加密落盘，字段名与 otpauth:// 参数保持一一对应，
 * 便于导出后直接被其他验证器识别。
 */
public class Account {

    public String id = "";
    public String issuer = "";
    public String account = "";
    public String secret = "";        // 归一化后的 Base32（大写、无分隔符）
    public String type = "TOTP";      // TOTP | HOTP
    public String algorithm = "SHA1"; // SHA1 | SHA256 | SHA512
    public int digits = 6;
    public int period = 30;
    public long counter = 0L;
    public long createdAt = 0L;

    public boolean isHotp() {
        return "HOTP".equalsIgnoreCase(type);
    }

    public String displayTitle() {
        if (issuer != null && issuer.length() > 0) {
            return issuer;
        }
        return account;
    }

    public Account copy() {
        Account a = new Account();
        a.id = id;
        a.issuer = issuer;
        a.account = account;
        a.secret = secret;
        a.type = type;
        a.algorithm = algorithm;
        a.digits = digits;
        a.period = period;
        a.counter = counter;
        a.createdAt = createdAt;
        return a;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("issuer", issuer);
        o.put("account", account);
        o.put("secret", secret);
        o.put("type", type);
        o.put("algorithm", algorithm);
        o.put("digits", digits);
        o.put("period", period);
        o.put("counter", counter);
        o.put("createdAt", createdAt);
        return o;
    }

    public static Account fromJson(JSONObject o) {
        Account a = new Account();
        a.id = o.optString("id", "");
        a.issuer = o.optString("issuer", "");
        a.account = o.optString("account", "");
        a.secret = o.optString("secret", "");
        a.type = o.optString("type", "TOTP");
        a.algorithm = o.optString("algorithm", "SHA1");
        a.digits = o.optInt("digits", 6);
        a.period = o.optInt("period", 30);
        a.counter = o.optLong("counter", 0L);
        a.createdAt = o.optLong("createdAt", 0L);
        return a;
    }
}
