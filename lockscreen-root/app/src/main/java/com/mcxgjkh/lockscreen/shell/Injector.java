package com.mcxgjkh.lockscreen.shell;

import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * 常驻按键注入器：由 root 通过 app_process 启动，从 stdin 读键码、直接调
 * InputManager.injectInputEvent 注入，避免每次 `input keyevent` 都新起一个虚拟机。
 *
 * 协议：启动成功打印 READY；每注入一个键打印 OK；失败打印 FAIL 后退出。
 * 说明：InputManager 相关方法是 hidden API，先尝试 setHiddenApiExemptions 放行；
 * 任何一步失败都只打印 FAIL，让上层回退到 `input keyevent`，不会影响功能可用性。
 */
public final class Injector {

    private Injector() {
    }

    public static void main(String[] args) {
        try {
            exemptHiddenApi();
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Object im = imClass.getMethod("getInstance").invoke(null);
            Method inject = imClass.getMethod("injectInputEvent", InputEvent.class, int.class);

            say("READY");
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                int code;
                int meta = 0;
                try {
                    code = Integer.parseInt(parts[0]);
                    if (parts.length > 1) {
                        meta = Integer.parseInt(parts[1]);
                    }
                } catch (NumberFormatException e) {
                    continue;
                }
                if (code < 0 || code > 300) {
                    continue;
                }
                long now = SystemClock.uptimeMillis();
                // ASYNC(0)：不等分发完成，延迟最低；按键事件本身有序
                inject.invoke(im, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta), 0);
                inject.invoke(im, new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta), 0);
                say("OK");
            }
        } catch (Throwable t) {
            say("FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void say(String s) {
        try {
            System.out.println(s);
            System.out.flush();
        } catch (Throwable ignored) {
            // stdout 关闭，进程即将退出
        }
    }

    /** Android 9+ 的 hidden API 限制：整包豁免，失败也不影响后续 try/catch。 */
    private static void exemptHiddenApi() {
        try {
            Class<?> vmClass = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmClass.getMethod("getRuntime").invoke(null);
            vmClass.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
            // 低版本没有该 API
        }
    }
}
