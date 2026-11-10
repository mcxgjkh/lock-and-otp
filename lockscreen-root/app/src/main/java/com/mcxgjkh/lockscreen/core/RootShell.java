package com.mcxgjkh.lockscreen.core;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Root 命令通道。设计要点：
 * 1) 常驻 su 会话：一次授权、多次执行，避免每次锁屏都 fork 进程（省电、省时、无重复授权弹窗）；
 * 2) 命令白名单：只有 LockEngine 里写死的 4 条命令会进入这里，且显式拒绝换行符，杜绝命令拼接注入；
 * 3) 空闲 60s 自动回收：不做后台守护线程/定时器，空闲时进程被关闭，内存与电量占用归零；
 * 4) 所有调用都是阻塞的，必须在工作线程执行（LockEngine 已保证）。
 */
public final class RootShell {

    private static final String TAG = "RootShell";
    private static final String MARK = "__WB_RC__";
    private static final String EOF = "\u0000EOF";

    private static final long IDLE_CLOSE_MS = 60_000L;
    private static final long OPEN_TIMEOUT_MS = 8_000L;   // su 授权弹窗需要用户点击
    private static final long CMD_TIMEOUT_MS = 3_000L;

    /** 唯一允许的"带参数"命令入口使用的白名单：包名 / 无障碍组件列表 */
    private static final Pattern SAFE_PKG =
            Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$");
    private static final Pattern SAFE_SETTING_VALUE =
            Pattern.compile("^[A-Za-z0-9_.$/:=-]{0,8192}$");

    /** 允许写入的系统设置：命名空间/键 白名单，值只接受整数。 */
    private static final Set<String> ALLOWED_SETTINGS = new HashSet<String>(Arrays.asList(
            "global/low_power",
            "system/screen_brightness",
            "system/screen_brightness_mode",
            "global/window_animation_scale",
            "global/transition_animation_scale",
            "global/animator_duration_scale"));

    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su"
    };

    private static RootShell sInstance;

    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<String>();

    private Process proc;
    private BufferedWriter out;
    private BufferedReader in;
    private Thread reader;
    private volatile boolean alive;
    private long lastUsed;
    /** 握手超时：正常 8s（等用户点授权），开机还原等场景可临时调小。 */
    private long openTimeout = OPEN_TIMEOUT_MS;

    private RootShell() {
    }

    /** 临时调整 su 握手超时，用完请还原（BootReceiver 用短超时避免拖死广播）。 */
    public void setOpenTimeout(long ms) {
        openTimeout = Math.max(800L, Math.min(ms, OPEN_TIMEOUT_MS));
    }

    public void resetOpenTimeout() {
        openTimeout = OPEN_TIMEOUT_MS;
    }

    public static synchronized RootShell get() {
        if (sInstance == null) {
            sInstance = new RootShell();
        }
        return sInstance;
    }

    /** 一次性探测，不复用会话。阻塞，需在工作线程调用。 */
    public boolean isRooted() {
        Process p = null;
        try {
            p = new ProcessBuilder(suPath(), "-c", "id").redirectErrorStream(true).start();
            if (!awaitExit(p, 6000L)) {
                return false;
            }
            String outText = readAll(p.getInputStream());
            return outText != null && outText.contains("uid=0");
        } catch (Exception e) {
            Log.w(TAG, "probe failed: " + e.getMessage());
            return false;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /** 执行一条白名单命令，返回 exitCode == 0。 */
    public boolean run(String cmd) {
        if (cmd == null || cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0) {
            return false;
        }
        lock.lock();
        try {
            if (!open()) {
                return false;
            }
            String rc = execLocked(cmd, CMD_TIMEOUT_MS);
            lastUsed = System.currentTimeMillis();
            if (rc == null) {
                close();
                return false;
            }
            return "0".equals(rc);
        } catch (Exception e) {
            Log.w(TAG, "run failed: " + e.getMessage());
            close();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 发送一个按键事件。keyCode 限制在 0..300，杜绝把任意字符串拼进 shell。
     * 这是悬浮球"返回/主页/最近任务"的实现方式，不依赖无障碍服务。
     */
    public boolean runKeyEvent(int keyCode) {
        if (keyCode < 0 || keyCode > 300) {
            return false;
        }
        return run("input keyevent " + keyCode);
    }

    /**
     * 往当前焦点输入框敲一段纯可打印 ASCII 文本（`input text`）。
     * 只接受 0x20~0x7E，单引号做 shell 转义，长度上限 200，避免命令过长卡住。
     */
    public boolean runText(String text) {
        if (text == null || text.length() == 0 || text.length() > 200) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        String escaped = text.replace("'", "'\\''");
        return run("input text '" + escaped + "'");
    }

    /**
     * 写系统安全设置（目前只开放两个无障碍相关 key）。
     * 值只允许包名/类名/冒号/斜杠等字符，长度受限。
     */
    public boolean runSecureSetting(String key, String value) {
        boolean allowedKey = "enabled_accessibility_services".equals(key)
                || "accessibility_enabled".equals(key);
        if (!allowedKey || value == null || !SAFE_SETTING_VALUE.matcher(value).matches()) {
            return false;
        }
        // 空值必须显式传 ""：否则 settings 命令会因为少一个参数直接失败（关掉最后一个服务时就是空值）
        String v = value.length() == 0 ? "\"\"" : value;
        return run("settings put secure " + key + " " + v);
    }

    /**
     * 下拉通知栏的手势兜底（Android 6 以下没有 cmd statusbar）。
     * 坐标全部来自屏幕尺寸，取值范围受屏幕约束，不接受外部字符串。
     */
    public boolean runSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 || x1 > 10000 || x2 > 10000
                || y1 > 10000 || y2 > 10000) {
            return false;
        }
        int ms = Math.max(50, Math.min(durationMs, 2000));
        return run("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
    }

    /** 写系统设置（仅白名单键、仅整数值），用于系统级省电。 */
    public boolean runSetting(String namespace, String key, int value) {
        if (namespace == null || key == null) {
            return false;
        }
        if (!ALLOWED_SETTINGS.contains(namespace + "/" + key)) {
            return false;
        }
        if (value < 0 || value > 100000) {
            return false;
        }
        return run("settings put " + namespace + " " + key + " " + value);
    }

    /** 开关无线连接：只允许 wifi / bluetooth / data 三个服务名。 */
    public boolean runSvc(String service, boolean enable) {
        if (!"wifi".equals(service) && !"bluetooth".equals(service) && !"data".equals(service)) {
            return false;
        }
        return run("svc " + service + " " + (enable ? "enable" : "disable"));
    }

    /** 结束后台（缓存）进程，前台应用与前台服务不受影响。 */
    public boolean runKillBackground() {
        return run("am kill-all");
    }

    /** 系统电池省电模式（Android 6+ 才有 cmd 命令）。 */
    public boolean runPowerSaveMode(boolean on) {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        return run("cmd power set-mode " + (on ? 1 : 0));
    }

    /** 授予本应用 WRITE_SECURE_SETTINGS（部分 ROM 不允许运行时授予，失败即走 settings 命令兜底）。 */
    public boolean grantSecureSettings(String pkg) {
        if (pkg == null || !SAFE_PKG.matcher(pkg).matches()) {
            return false;
        }
        return run("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
    }

    /**
     * 预热：提前把 su 会话打开，让第一次"返回/锁屏"不用等进程创建。
     * 阻塞，必须在工作线程调用。
     */
    public boolean ensureOpen() {
        lock.lock();
        try {
            return open();
        } finally {
            lock.unlock();
        }
    }

    /** 供其它通道复用同一套 su 路径探测。 */
    public static String suBinary() {
        return suPath();
    }

    /** 是否已经持有一个可用的 su 会话（不触发启动）。 */
    public boolean isOpen() {
        return alive && proc != null;
    }

    public void close() {
        lock.lock();
        try {
            BufferedWriter o = out;
            Process p = proc;
            out = null;
            in = null;
            proc = null;
            alive = false;
            if (o != null) {
                try {
                    o.write("exit\n");
                    o.flush();
                } catch (Exception ignored) {
                    // 会话已经断开，无需处理
                }
            }
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                    // 同上
                }
            }
            reader = null;
            lines.clear();
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- internal

    private boolean open() {
        if (alive && proc != null) {
            if (System.currentTimeMillis() - lastUsed > IDLE_CLOSE_MS) {
                close();
            } else {
                return true;
            }
        }
        String su = suPath();
        if (tryOpen(new String[]{su})
                || tryOpen(new String[]{su, "-c", "sh"})) {
            return true;
        }
        Log.w(TAG, "no usable root shell");
        return false;
    }

    private boolean tryOpen(String[] argv) {
        close();
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), "UTF-8"));
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            proc = p;
            out = w;
            in = r;
            alive = true;
            startReader(r);
            // 握手：只有真正的 uid=0 才判定成功（su 被拒绝时进程通常直接退出）
            String rc = execLocked("[ \"$(id -u)\" = \"0\" ]", openTimeout);
            if ("0".equals(rc)) {
                lastUsed = System.currentTimeMillis();
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "spawn " + argv[0] + " failed: " + e.getMessage());
        }
        close();
        return false;
    }

    private void startReader(final BufferedReader r) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lines.offer(line);
                    }
                } catch (Exception ignored) {
                    // 进程结束
                } finally {
                    alive = false;
                    lines.offer(EOF);
                }
            }
        }, "root-shell-reader");
        t.setDaemon(true);
        t.start();
        reader = t;
    }

    private String execLocked(String cmd, long timeoutMs) throws Exception {
        if (out == null) {
            return null;
        }
        lines.clear();
        out.write(cmd);
        out.write('\n');
        out.write("echo " + MARK + "$?\n");
        out.flush();

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long wait = deadline - System.currentTimeMillis();
            if (wait <= 0L) {
                Log.w(TAG, "timeout: " + cmd);
                return null;
            }
            String line = lines.poll(wait, TimeUnit.MILLISECONDS);
            if (line == null || EOF.equals(line)) {
                return null;
            }
            if (line.startsWith(MARK)) {
                return line.substring(MARK.length()).trim();
            }
        }
    }

    private static boolean awaitExit(Process p, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 8192) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // 读取失败按空处理
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
        return sb.toString();
    }

    private static String suPath() {
        for (String path : SU_CANDIDATES) {
            try {
                if (new File(path).canExecute()) {
                    return path;
                }
            } catch (Exception ignored) {
            }
        }
        return "su";
    }
}
