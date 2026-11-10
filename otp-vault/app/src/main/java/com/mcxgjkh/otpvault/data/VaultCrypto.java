package com.mcxgjkh.otpvault.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 密钥保险箱的密码学底座（全部使用 API19 可用的原语）：
 *
 *  · 主密钥：32 字节随机数，**不落盘**；落盘的是被它加密后的"数据密钥包裹"。
 *  · 包裹方式（按可用性降级）：
 *      1) AndroidKeyStore 里的 RSA-2048 公钥加密（私钥不可导出，API18+ 可用）；
 *      2) 若个别 4.4 机器 Keystore 异常，退回 PBKDF2(ANDROID_ID) 派生的 AES 包裹
 *         —— 安全性弱于 Keystore，仅作可用性兜底，日志里会明确标注。
 *  · 数据：AES-256-CBC + PKCS5 填充，随机 IV；再做 HMAC-SHA256（encrypt-then-MAC），
 *    校验失败一律拒绝解密，避免填充预言攻击与静默篡改。
 *
 * 注：API19 没有 AES/GCM 的稳定实现、也没有 Keystore AES（API23 才有），
 *     所以这里用 CBC+HMAC 这套等价强度的组合。
 */
public final class VaultCrypto {

    private static final String TAG = "VaultCrypto";
    private static final String PREF = "vault_crypto";
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "otpvault.kek";
    private static final String K_RSA = "wrap_rsa";
    private static final String K_FB_SALT = "wrap_fb_salt";
    private static final String K_FB = "wrap_fb";

    private static final byte[] MAGIC = {'O', 'V', '1'};
    private static final int IV_LEN = 16;
    private static final int MAC_LEN = 32;
    private static final int PBKDF2_ITER = 20000;

    private static final SecureRandom RNG = new SecureRandom();

    private VaultCrypto() {
    }

    // ------------------------------------------------------------ 主密钥

    public static byte[] masterKey(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String rsaWrapped = sp.getString(K_RSA, null);
        if (rsaWrapped != null) {
            try {
                return rsaUnwrap(Base64.decode(rsaWrapped, Base64.NO_WRAP));
            } catch (Exception e) {
                Log.w(TAG, "keystore unwrap failed: " + e.getMessage());
            }
        }
        String fbWrapped = sp.getString(K_FB, null);
        String fbSalt = sp.getString(K_FB_SALT, null);
        if (fbWrapped != null && fbSalt != null) {
            try {
                return fallbackUnwrap(Base64.decode(fbWrapped, Base64.NO_WRAP),
                        Base64.decode(fbSalt, Base64.NO_WRAP), ctx);
            } catch (Exception e) {
                Log.w(TAG, "fallback unwrap failed: " + e.getMessage());
            }
        }
        byte[] key = new byte[32];
        RNG.nextBytes(key);
        try {
            sp.edit().putString(K_RSA, Base64.encodeToString(rsaWrap(key), Base64.NO_WRAP)).apply();
            Log.i(TAG, "master key created, wrapped by AndroidKeyStore");
        } catch (Exception e) {
            Log.w(TAG, "keystore wrap unavailable, using PBKDF2 fallback: " + e.getMessage());
            byte[] salt = new byte[16];
            RNG.nextBytes(salt);
            try {
                byte[] wrapped = fallbackWrap(key, salt, ctx);
                sp.edit()
                        .putString(K_FB_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                        .putString(K_FB, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                        .apply();
            } catch (Exception e2) {
                // 两种包裹方式都不可用：宁可失败也不要把无法解密的密钥库写下去
                throw new IllegalStateException("no usable key storage", e2);
            }
        }
        return key;
    }

    // ------------------------------------------------------------ 数据封装

    public static byte[] seal(byte[] master, byte[] plain) throws Exception {
        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);
        byte[] ct = aes(Cipher.ENCRYPT_MODE, derive(master, "enc"), iv, plain);

        ByteArrayOutputStream out = new ByteArrayOutputStream(MAGIC.length + IV_LEN + ct.length + MAC_LEN);
        out.write(MAGIC);
        out.write(iv);
        out.write(ct);
        byte[] mac = hmac(derive(master, "mac"), out.toByteArray());
        out.write(mac);
        return out.toByteArray();
    }

    public static byte[] open(byte[] master, byte[] sealed) throws Exception {
        int min = MAGIC.length + IV_LEN + MAC_LEN + 16;
        if (sealed == null || sealed.length < min) {
            throw new IllegalArgumentException("vault too short");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (sealed[i] != MAGIC[i]) {
                throw new IllegalArgumentException("bad vault header");
            }
        }
        int ctLen = sealed.length - MAGIC.length - IV_LEN - MAC_LEN;
        byte[] head = Arrays.copyOfRange(sealed, 0, MAGIC.length + IV_LEN + ctLen);
        byte[] mac = Arrays.copyOfRange(sealed, head.length, sealed.length);
        byte[] expect = hmac(derive(master, "mac"), head);
        if (!constantTimeEquals(expect, mac)) {
            throw new SecurityException("vault mac mismatch");
        }
        byte[] iv = Arrays.copyOfRange(sealed, MAGIC.length, MAGIC.length + IV_LEN);
        byte[] ct = Arrays.copyOfRange(sealed, MAGIC.length + IV_LEN, MAGIC.length + IV_LEN + ctLen);
        return aes(Cipher.DECRYPT_MODE, derive(master, "enc"), iv, ct);
    }

    // ------------------------------------------------------------ 内部实现

    private static byte[] derive(byte[] master, String label) throws Exception {
        return hmac(master, ("otpvault/" + label).getBytes("UTF-8"));
    }

    private static byte[] aes(int mode, byte[] key, byte[] iv, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static KeyPair keyPair() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA", KS);
            if (Build.VERSION.SDK_INT >= 23) {
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                        .build();
                g.initialize(spec);
            } else {
                g.initialize(2048);
            }
            g.generateKeyPair();
            ks.load(null);
        }
        Certificate cert = ks.getCertificate(ALIAS);
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(ALIAS, null);
        if (cert == null || entry == null) {
            throw new IllegalStateException("keystore entry missing");
        }
        return new KeyPair(cert.getPublicKey(), entry.getPrivateKey());
    }

    private static byte[] rsaWrap(byte[] raw) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, keyPair().getPublic());
        return c.doFinal(raw);
    }

    private static byte[] rsaUnwrap(byte[] wrapped) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.DECRYPT_MODE, keyPair().getPrivate());
        return c.doFinal(wrapped);
    }

    private static byte[] fallbackKek(byte[] salt, Context ctx) throws Exception {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null) {
            id = "no-android-id";
        }
        String material = id + "|" + ctx.getPackageName() + "|otpvault";
        PBEKeySpec spec = new PBEKeySpec(material.toCharArray(), salt, PBKDF2_ITER, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] fallbackWrap(byte[] raw, byte[] salt, Context ctx) throws Exception {
        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);
        byte[] ct = aes(Cipher.ENCRYPT_MODE, fallbackKek(salt, ctx), iv, raw);
        byte[] out = new byte[IV_LEN + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_LEN);
        System.arraycopy(ct, 0, out, IV_LEN, ct.length);
        return out;
    }

    private static byte[] fallbackUnwrap(byte[] wrapped, byte[] salt, Context ctx) throws Exception {
        if (wrapped.length <= IV_LEN) {
            throw new IllegalArgumentException("bad fallback blob");
        }
        byte[] iv = Arrays.copyOfRange(wrapped, 0, IV_LEN);
        byte[] ct = Arrays.copyOfRange(wrapped, IV_LEN, wrapped.length);
        return aes(Cipher.DECRYPT_MODE, fallbackKek(salt, ctx), iv, ct);
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
