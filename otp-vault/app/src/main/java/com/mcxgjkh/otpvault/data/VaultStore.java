package com.mcxgjkh.otpvault.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 账户仓库：整个仓库序列化为一个 JSON，再整体加密成单文件 vault.dat。
 *
 * 为什么不做 SQLite：几十条记录的读放大可以忽略，而"单文件 + 整体加密"能保证
 * 不残留明文 WAL/journal，也让"整库备份/彻底销毁"变成一次文件操作。
 * 写入用 临时文件 + fsync + rename，避免写到一半掉电导致密钥库损坏。
 */
public final class VaultStore {

    private static final String TAG = "VaultStore";
    private static final String FILE = "vault.dat";
    private static final String TMP = "vault.tmp";
    private static final int VERSION = 1;

    private static VaultStore sInstance;

    private final Context app;
    private final List<Account> items = new ArrayList<Account>();
    private boolean loaded;
    private boolean corrupt;
    private boolean keyUnavailable;

    private VaultStore(Context ctx) {
        this.app = ctx;
    }

    public static synchronized VaultStore get(Context ctx) {
        if (sInstance == null) {
            sInstance = new VaultStore(ctx.getApplicationContext());
        }
        return sInstance;
    }

    public synchronized List<Account> all() {
        ensureLoaded();
        return new ArrayList<Account>(items);
    }

    public synchronized Account find(String id) {
        ensureLoaded();
        if (id == null) {
            return null;
        }
        for (Account a : items) {
            if (id.equals(a.id)) {
                return a;
            }
        }
        return null;
    }

    public synchronized int size() {
        ensureLoaded();
        return items.size();
    }

    public synchronized boolean hadCorruption() {
        ensureLoaded();
        return corrupt;
    }

    /** Keystore 主密钥不可用（如系统被重置）：数据仍在，但本次无法解密。 */
    public synchronized boolean isKeyUnavailable() {
        ensureLoaded();
        return keyUnavailable;
    }

    public synchronized boolean upsert(Account account) {
        ensureLoaded();
        if (account == null || account.secret == null || account.secret.length() == 0) {
            return false;
        }
        if (account.id == null || account.id.length() == 0) {
            account.id = UUID.randomUUID().toString();
            account.createdAt = System.currentTimeMillis();
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(account.id)) {
                items.set(i, account);
                return persist();
            }
        }
        items.add(account);
        return persist();
    }

    public synchronized int importAll(List<Account> list) {
        ensureLoaded();
        int added = 0;
        for (Account a : list) {
            if (a == null || a.secret == null) {
                continue;
            }
            boolean dup = false;
            for (Account exist : items) {
                if (exist.secret.equals(a.secret) && exist.account.equals(a.account)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            if (a.id == null || a.id.length() == 0) {
                a.id = UUID.randomUUID().toString();
            }
            if (a.createdAt == 0L) {
                a.createdAt = System.currentTimeMillis();
            }
            items.add(a);
            added++;
        }
        if (added > 0) {
            persist();
        }
        return added;
    }

    public synchronized boolean delete(String id) {
        ensureLoaded();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                items.remove(i);
                return persist();
            }
        }
        return false;
    }

    /** HOTP 每用一次要把计数器写回，否则会与服务器不同步。 */
    public synchronized boolean bumpCounter(String id) {
        ensureLoaded();
        for (Account a : items) {
            if (a.id.equals(id)) {
                a.counter = a.counter + 1L;
                return persist();
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 持久化

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        File f = new File(app.getFilesDir(), FILE);
        if (!f.exists()) {
            return;
        }
        try {
            byte[] sealed = readAll(f);
            byte[] master = VaultCrypto.masterKey(app);
            byte[] plain = VaultCrypto.open(master, sealed);
            JSONObject root = new JSONObject(new String(plain, "UTF-8"));
            JSONArray arr = root.optJSONArray("accounts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        items.add(Account.fromJson(o));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "vault read failed", e);
            corrupt = true;
            items.clear();
            // 密钥不可用（Keystore 被重置等）时不动原文件，避免把还能恢复的数据改名隔离
            if (e instanceof IllegalStateException) {
                keyUnavailable = true;
                return;
            }
            File broken = new File(app.getFilesDir(), FILE + ".corrupt." + System.currentTimeMillis());
            if (!f.renameTo(broken)) {
                Log.w(TAG, "cannot quarantine corrupt vault");
            }
        }
    }

    private boolean persist() {
        File tmp = new File(app.getFilesDir(), TMP);
        File dst = new File(app.getFilesDir(), FILE);
        FileOutputStream fos = null;
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("updatedAt", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            for (Account a : items) {
                arr.put(a.toJson());
            }
            root.put("accounts", arr);

            byte[] master = VaultCrypto.masterKey(app);
            byte[] sealed = VaultCrypto.seal(master, root.toString().getBytes("UTF-8"));

            fos = new FileOutputStream(tmp);
            fos.write(sealed);
            fos.flush();
            fos.getFD().sync();
            fos.close();
            fos = null;

            // rename(2) 在同一文件系统上是原子的，优先直接覆盖；不支持时再退回删旧改名
            if (tmp.renameTo(dst)) {
                return true;
            }
            if (dst.delete() && tmp.renameTo(dst)) {
                return true;
            }
            Log.e(TAG, "rename tmp -> vault failed");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "vault write failed", e);
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
