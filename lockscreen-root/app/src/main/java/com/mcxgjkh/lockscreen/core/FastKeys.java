package com.mcxgjkh.lockscreen.core;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 极速按键通道：root 起一个常驻的 app_process 注入器，直接注入按键事件。
 *
 * 为什么需要它：`input keyevent` 每次都要新起一个 app_process(JVM)，在手表/低端机上
 * 单次 0.5~1.5 秒；返回、主页这类"随手按"的操作会明显延迟。常驻注入器把单次开销压到毫秒级。
 *
 * 失败策略：启动握手 5s、单次注入 400ms，超时或输出 FAIL 就把 unavailable 置位并回退到
 * `input keyevent`（RootShell），功能可用性不受影响，只是回到原来的速度。
 */
public final class FastKeys {

    private static final String TAG = "FastKeys";
    private static final ReentrantLock LOCK = new ReentrantLock();

    private static Context app;
    private static Process proc;
    private static BufferedWriter out;
    private static BufferedReader in;
    private static boolean unavailable;

    private FastKeys() {
    }

    public static void init(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public static boolean isUnavailable() {
        return unavailable;
    }

    /** 预热：在后台线程调用，失败也只是标记不可用。 */
    public static void warmUp() {
        LOCK.lock();
        try {
            if (proc != null || unavailable || app == null) {
                return;
            }
            start();
        } finally {
            LOCK.unlock();
        }
    }

    private static void asyncWarmUp() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                warmUp();
            }
        }, "fastkeys-warmup").start();
    }

    public static boolean send(int keyCode) {
        return send(keyCode, 0);
    }

    /** 注入一个带修饰键的按键（如 Ctrl+V 的 meta=4096）；失败返回 false。 */
    public static boolean send(int keyCode, int metaState) {
        if (unavailable || app == null || keyCode < 0 || keyCode > 300) {
            return false;
        }
        boolean locked = false;
        try {
            // 预热/另一个按键正在用通道时，不为这一次点击排队等待，直接走旧通道
            locked = LOCK.tryLock(60L, TimeUnit.MILLISECONDS);
            if (!locked) {
                return false;
            }
            if (proc == null) {
                // 注入器还没起来：本次先用 input，后台把注入器拉起来，下一次就快
                asyncWarmUp();
                return false;
            }
            out.write(metaState == 0
                    ? Integer.toString(keyCode)
                    : keyCode + " " + metaState);
            out.write('\n');
            out.flush();
            if (awaitOk(400L)) {
                return true;
            }
            // 协议错位或进程异常：放弃本通道，避免后续每次都在这里等 400ms
            kill();
            unavailable = true;
            return false;
        } catch (Exception e) {
            Log.w(TAG, "inject failed: " + e.getMessage());
            kill();
            unavailable = true;
            return false;
        } finally {
            if (locked) {
                LOCK.unlock();
            }
        }
    }

    /** 服务销毁时释放注入器进程。 */
    public static void close() {
        LOCK.lock();
        try {
            kill();
            unavailable = false;
        } finally {
            LOCK.unlock();
        }
    }

    // ---------------------------------------------------------------- internal

    private static boolean start() {
        try {
            String cmd = "CLASSPATH=" + app.getPackageCodePath()
                    + " app_process /system/bin com.mcxgjkh.lockscreen.shell.Injector";
            proc = new ProcessBuilder(RootShell.suBinary(), "-c", cmd).redirectErrorStream(true).start();
            out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
            in = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));

            long deadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < deadline) {
                if (in.ready()) {
                    String line = in.readLine();
                    if (line == null) {
                        break;
                    }
                    if ("READY".equals(line)) {
                        Log.i(TAG, "injector ready");
                        return true;
                    }
                    if (line.startsWith("FAIL")) {
                        Log.w(TAG, "injector refused: " + line);
                        break;
                    }
                } else {
                    Thread.sleep(10L);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "injector start failed: " + e.getMessage());
        }
        kill();
        unavailable = true;
        return false;
    }

    private static boolean awaitOk(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (in.ready()) {
                    String line = in.readLine();
                    if (line == null) {
                        return false;
                    }
                    if ("OK".equals(line)) {
                        return true;
                    }
                    if (line.startsWith("FAIL")) {
                        return false;
                    }
                } else {
                    Thread.sleep(5L);
                }
            }
        } catch (Exception ignored) {
            // 进程结束
        }
        return false;
    }

    private static void kill() {
        if (out != null) {
            try {
                out.write("quit\n");
                out.flush();
            } catch (Exception ignored) {
                // 进程已结束
            }
        }
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) {
                // 同上
            }
        }
        proc = null;
        out = null;
        in = null;
    }
}
