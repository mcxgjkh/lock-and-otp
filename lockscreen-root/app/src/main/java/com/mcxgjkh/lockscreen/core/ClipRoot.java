package com.mcxgjkh.lockscreen.core;

import android.content.Context;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Root 剪贴板兜底通道的客户端。
 *
 * 只在"普通通道读到空、但确实发生了复制事件"或用户手动点「Root 读取」时调用，
 * 因此不会长期消耗 CPU/电量（每次都会新起一个 app_process，约 0.3~1s）。
 */
public final class ClipRoot {

    private static final String TAG = "ClipRoot";
    private static final String MAIN = "com.mcxgjkh.lockscreen.shell.ClipTool";
    private static final long TIMEOUT_SEC = 5L;

    private ClipRoot() {
    }

    /** 以 system 身份读取剪贴板；失败返回 null（与"空"区分开）。 */
    public static String read(Context ctx) {
        String line = run(ctx, "get", null);
        if (line == null) {
            return null;
        }
        if (line.startsWith("CLIP:")) {
            try {
                byte[] raw = Base64.decode(line.substring(5), Base64.NO_WRAP);
                return new String(raw, "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }
        if ("EMPTY".equals(line)) {
            return "";
        }
        Log.w(TAG, "read: " + line);
        return null;
    }

    /** 诊断用：返回 ClipTool 的原始输出（含 ERR 原因）。 */
    public static String rawProbe(Context ctx) {
        String line = run(ctx, "get", null);
        return line == null ? "null(超时或未授权)" : line;
    }

    /** 以 system 身份写入剪贴板。 */
    public static boolean write(Context ctx, String text) {
        if (text == null) {
            return false;
        }
        return "OK".equals(run(ctx, "set", text));
    }

    // ---------------------------------------------------------------- internal

    private static String run(Context ctx, String mode, String stdin) {
        // 起进程 + 等 su 授权可能耗时数百毫秒，绝不能在主线程做
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return null;
        }
        Process proc = null;
        try {
            String cmd = "CLASSPATH=" + ctx.getPackageCodePath()
                    + " app_process /system/bin " + MAIN + " " + mode;
            proc = new ProcessBuilder(RootShell.suBinary(), "-c", cmd)
                    .redirectErrorStream(true).start();
            if (stdin != null) {
                OutputStream os = proc.getOutputStream();
                os.write(stdin.getBytes("UTF-8"));
                os.flush();
                os.close();
            } else {
                proc.getOutputStream().close();
            }
            final Process target = proc;
            FutureTask<String> task = new FutureTask<String>(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(target.getInputStream(), "UTF-8"));
                    return r.readLine();
                }
            });
            Thread t = new Thread(task, "clip-root-read");
            t.setDaemon(true);
            t.start();
            return task.get(TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "run failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Exception ignored) {
                    // 已退出
                }
            }
        }
    }
}
