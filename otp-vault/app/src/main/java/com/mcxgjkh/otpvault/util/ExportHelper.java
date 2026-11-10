package com.mcxgjkh.otpvault.util;

import com.mcxgjkh.otpvault.data.Account;
import com.mcxgjkh.otpvault.otp.OtpauthUri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 一键导出：JSON / CSV / otpauth 链接三种格式。
 * 三种格式都包含"原始密钥 + 账户名 + 发行方 + 类型/算法/位数/周期/计数器"，
 * 因此导出文件可以被其他验证器（或 Excel）直接消费。
 * 注意：导出内容是明文密钥，界面必须先给出明确警示。
 */
public final class ExportHelper {

    public static final int FMT_JSON = 0;
    public static final int FMT_CSV = 1;
    public static final int FMT_URI = 2;

    private ExportHelper() {
    }

    public static String build(List<Account> list, int fmt) {
        if (fmt == FMT_CSV) {
            return buildCsv(list);
        }
        if (fmt == FMT_URI) {
            return buildUris(list);
        }
        return buildJson(list);
    }

    public static String mime(int fmt) {
        if (fmt == FMT_JSON) {
            return "application/json";
        }
        if (fmt == FMT_CSV) {
            return "text/csv";
        }
        return "text/plain";
    }

    public static String extension(int fmt) {
        if (fmt == FMT_JSON) {
            return "json";
        }
        if (fmt == FMT_CSV) {
            return "csv";
        }
        return "txt";
    }

    public static String fileName(int fmt) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US);
        return "otpvault-" + f.format(new Date()) + "." + extension(fmt);
    }

    // ---------------------------------------------------------------- formats

    private static String buildJson(List<Account> list) {
        try {
            JSONObject root = new JSONObject();
            root.put("application", "OtpVault");
            root.put("version", 1);
            root.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                    .format(new Date()));
            root.put("count", list.size());
            JSONArray arr = new JSONArray();
            for (Account a : list) {
                JSONObject o = a.toJson();
                // 额外带上标准 otpauth 链接，方便其他客户端一条条导入
                o.put("otpauth", OtpauthUri.build(a));
                arr.put(o);
            }
            root.put("accounts", arr);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String buildCsv(List<Account> list) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("issuer,account,secret,type,algorithm,digits,period,counter,otpauth\r\n");
        for (Account a : list) {
            sb.append(csv(a.issuer)).append(',')
                    .append(csv(a.account)).append(',')
                    .append(csv(a.secret)).append(',')
                    .append(csv(a.isHotp() ? "HOTP" : "TOTP")).append(',')
                    .append(csv(a.algorithm)).append(',')
                    .append(a.digits).append(',')
                    .append(a.period).append(',')
                    .append(a.counter).append(',')
                    .append(csv(OtpauthUri.build(a)))
                    .append("\r\n");
        }
        return sb.toString();
    }

    private static String buildUris(List<Account> list) {
        StringBuilder sb = new StringBuilder(256);
        for (Account a : list) {
            sb.append(OtpauthUri.build(a)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String raw) {
        if (raw == null) {
            return "";
        }
        boolean needQuote = raw.indexOf(',') >= 0 || raw.indexOf('"') >= 0
                || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0;
        if (!needQuote) {
            return raw;
        }
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }
}
