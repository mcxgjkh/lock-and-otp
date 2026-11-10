package com.mcxgjkh.lockscreen.core;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 自带剪贴板：不依赖系统剪贴板 UI（很多手表/精简 ROM 里根本没有复制/粘贴菜单）。
 *
 * 设计取舍：
 * · Android 10+ 禁止后台应用**读取**系统剪贴板，所以"当前内容"以本应用自己的存储为准，
 *   系统剪贴板只作为"写出去"的通道（写入不受限制）；
 * · 粘贴到别的 App 有两条路：ASCII 用 `input text` 直接敲进去（4.4 起可用）；
 *   其它字符则先写系统剪贴板，再注入 KEYCODE_PASTE(279，Android 7.0+)；
 * · 历史最多 30 条，去重后置顶，存 SharedPreferences（几十 KB 量级）。
 */
public final class Clips {

    private static final String TAG = "Clips";
    private static final String PREF = "clip_store";
    private static final String K_CURRENT = "current";
    private static final String K_HISTORY = "history";
    private static final String K_LAST_AT = "last_at";
    private static final String K_CAPTURES = "captures";
    private static final int MAX_HISTORY = 30;
    private static final int MAX_INPUT_CHARS = 200;

    /** 最近一次剪贴板操作的错误（仅内存，供诊断页显示）。 */
    public static volatile String lastError = "";

    /**
     * 缓存的 ClipboardManager。
     * 必须**在主线程**取：部分 ROM 的 ContextImpl 在创建它时会 new Handler()，
     * 在无 Looper 的工作线程上调用会抛 "Can't create handler inside thread..."。
     */
    private static ClipboardManager sCM;
    private static boolean sCMInit;

    private Clips() {
    }

    /** 进程启动时在主线程调用一次（LockApp.onCreate）。 */
    public static void init(Context ctx) {
        if (sCMInit) {
            return;
        }
        sCMInit = true;
        try {
            sCM = (ClipboardManager) ctx.getApplicationContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (sCM == null) {
                lastError = "init: ClipboardManager is null（系统没有剪贴板服务）";
            }
        } catch (Throwable t) {
            lastError = "init: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 取缓存的实例；工作线程上不再现场创建，没初始化就返回 null。 */
    private static ClipboardManager cm(Context ctx) {
        if (sCM != null) {
            return sCM;
        }
        if (!sCMInit && Looper.myLooper() == Looper.getMainLooper()) {
            init(ctx);
        }
        return sCM;
    }

    // ------------------------------------------------------------ 当前内容

    public static String current(Context ctx) {
        String s = prefs(ctx).getString(K_CURRENT, "");
        if (s != null && s.length() > 0) {
            return s;
        }
        return readSystem(ctx);
    }

    /** 设为当前内容：同时写入系统剪贴板，方便仍有粘贴菜单的应用使用。 */
    public static boolean setCurrent(Context ctx, String text) {
        if (text == null) {
            return false;
        }
        prefs(ctx).edit().putString(K_CURRENT, text).apply();
        addHistory(ctx, text);
        return writeSystem(ctx, text);
    }

    /** 只写本应用存储，不碰系统剪贴板（供同签名应用投递 / 无剪贴板服务的设备）。 */
    public static void setLocal(Context ctx, String text) {
        if (text == null) {
            return;
        }
        prefs(ctx).edit().putString(K_CURRENT, text).apply();
        addHistory(ctx, text);
        noteCapture(ctx);
    }

    /**
     * 监听到"别的应用复制"：更新当前内容并入库。
     * 不写回系统剪贴板（内容本来就在系统里），避免监听回调来回触发。
     */
    public static void onExternalCopy(Context ctx, String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        String cur = prefs(ctx).getString(K_CURRENT, "");
        if (text.equals(cur)) {
            return;
        }
        prefs(ctx).edit().putString(K_CURRENT, text).apply();
        addHistory(ctx, text);
        noteCapture(ctx);
    }

    /** 记录一次成功捕获，供界面显示"接管是否真的在工作"。 */
    public static void noteCapture(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        sp.edit()
                .putLong(K_LAST_AT, System.currentTimeMillis())
                .putInt(K_CAPTURES, sp.getInt(K_CAPTURES, 0) + 1)
                .apply();
    }

    public static long lastCaptureAt(Context ctx) {
        return prefs(ctx).getLong(K_LAST_AT, 0L);
    }

    public static int captureCount(Context ctx) {
        return prefs(ctx).getInt(K_CAPTURES, 0);
    }

    // ------------------------------------------------------------ 历史

    public static List<String> history(Context ctx) {
        List<String> out = new ArrayList<String>();
        String raw = prefs(ctx).getString(K_HISTORY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (s.length() > 0) {
                    out.add(s);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "bad history: " + e.getMessage());
        }
        return out;
    }

    public static void addHistory(Context ctx, String text) {
        if (text == null || text.trim().length() == 0) {
            return;
        }
        List<String> list = history(ctx);
        list.remove(text);
        list.add(0, text);
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
        saveHistory(ctx, list);
    }

    public static void removeHistory(Context ctx, String text) {
        List<String> list = history(ctx);
        list.remove(text);
        saveHistory(ctx, list);
    }

    public static void clearHistory(Context ctx) {
        saveHistory(ctx, new ArrayList<String>());
        prefs(ctx).edit().putString(K_CURRENT, "").apply();
    }

    // ------------------------------------------------------------ 粘贴

    /**
     * 把"当前内容"粘贴到系统焦点输入框。
     * 返回 false 表示当前系统/内容不支持直接注入（调用方提示用户）。
     */
    public static boolean pasteIntoFocused(Context ctx) {
        String text = current(ctx);
        if (text == null || text.length() == 0) {
            return false;
        }
        // 纯可打印 ASCII：用 input text 直接敲，4.4 也能用
        if (text.length() <= MAX_INPUT_CHARS && isPrintableAscii(text)
                && RootShell.get().runText(text)) {
            return true;
        }
        // 其它情况：确保系统剪贴板里是这段，再注入粘贴
        if (!writeSystem(ctx, text)) {
            return false;
        }
        // Ctrl+V 兼容性最好（EditText / WebView 都认），KEYCODE_PASTE 只作补充
        if (FastKeys.send(50, 4096)) {
            return true;
        }
        if (RootShell.get().runKeyEvent(279)) {
            return true;
        }
        return FastKeys.send(279);
    }

    // ------------------------------------------------------------ 系统通道

    public static String readSystem(Context ctx) {
        try {
            ClipboardManager cm = cm(ctx);
            if (cm == null) {
                lastError = "read: ClipboardManager is null";
                return "";
            }
            if (!cm.hasPrimaryClip()) {
                return "";
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return "";
            }
            CharSequence cs = clip.getItemAt(0).coerceToText(ctx);
            return cs == null ? "" : cs.toString();
        } catch (Throwable t) {
            // Android 10+ 后台读剪贴板会返回 null / 抛异常，都按空处理
            lastError = "read: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return "";
        }
    }

    /**
     * 普通通道读不到时走 Root 通道（以 system 身份读，不受"只允许焦点应用读取"的限制）。
     * 只有确实发生了复制事件或用户主动刷新时才调用，避免频繁起进程。
     */
    public static String readAny(Context ctx) {
        String text = readSystem(ctx);
        if (text != null && text.length() > 0) {
            return text;
        }
        String root = ClipRoot.read(ctx);
        return root == null ? "" : root;
    }

    /** 把系统剪贴板当前内容同步进来（普通通道）；返回是否拿到了新内容。 */
    public static boolean syncFromSystem(Context ctx) {
        return mergeToCurrent(ctx, readSystem(ctx));
    }

    /** 强制经 Root 通道读取并同步（界面上的「Root 读取」按钮）。 */
    public static boolean syncFromRoot(Context ctx) {
        return mergeToCurrent(ctx, ClipRoot.read(ctx));
    }

    private static boolean mergeToCurrent(Context ctx, String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        String cur = prefs(ctx).getString(K_CURRENT, "");
        if (text.equals(cur)) {
            return false;
        }
        onExternalCopy(ctx, text);
        return true;
    }

    public static boolean writeSystem(Context ctx, String text) {
        try {
            ClipboardManager cm = cm(ctx);
            if (cm == null) {
                lastError = "write: ClipboardManager is null";
                return false;
            }
            cm.setPrimaryClip(ClipData.newPlainText("lockscreen", text == null ? "" : text));
            return true;
        } catch (Throwable t) {
            lastError = "write: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "write clipboard failed: " + t.getMessage());
        }
        // 普通通道写不进去（ROM 限制）：以 system 身份再写一次
        return ClipRoot.write(ctx, text == null ? "" : text);
    }

    // ------------------------------------------------------------ internal

    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static void saveHistory(Context ctx, List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) {
            arr.put(s);
        }
        prefs(ctx).edit().putString(K_HISTORY, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
