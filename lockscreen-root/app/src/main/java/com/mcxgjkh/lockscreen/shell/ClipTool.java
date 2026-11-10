package com.mcxgjkh.lockscreen.shell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Root 剪贴板通道：由 app_process 以 system 上下文身份读写系统剪贴板。
 *
 * 用途：个别 ROM 只允许"当前获得焦点的应用"读取剪贴板，普通应用在后台永远读到空。
 * 以 system（uid 0）身份访问时不受该限制，因此用它做兜底读取。
 *
 * 协议（stdout 单行）：
 *   CLIP:&lt;base64&gt; 读到的文本
 *   EMPTY           剪贴板为空
 *   OK              set 成功
 *   ERR ...         失败（调用方回退到普通通道）
 */
public final class ClipTool {

    private ClipTool() {
    }

    public static void main(String[] args) {
        try {
            exemptHiddenApi();
            Context ctx = systemContext();
            if (ctx == null) {
                say("ERR no-context");
                return;
            }
            ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                say("ERR no-service");
                return;
            }
            String mode = (args != null && args.length > 0) ? args[0] : "get";
            if ("set".equals(mode)) {
                String text = new String(readAll(System.in), "UTF-8");
                cm.setPrimaryClip(ClipData.newPlainText("lockscreen", text));
                say("OK");
            } else {
                if (!cm.hasPrimaryClip()) {
                    say("EMPTY");
                    return;
                }
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) {
                    say("EMPTY");
                    return;
                }
                CharSequence cs = clip.getItemAt(0).coerceToText(ctx);
                String text = cs == null ? "" : cs.toString();
                say("CLIP:" + Base64.encodeToString(text.getBytes("UTF-8"), Base64.NO_WRAP));
            }
        } catch (Throwable t) {
            say("ERR " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        System.exit(0);
    }

    // ---------------------------------------------------------------- 系统上下文

    private static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getDeclaredMethod("systemMain").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }

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

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > 1 << 20) {
                break;
            }
        }
        return bos.toByteArray();
    }

    private static void say(String s) {
        try {
            System.out.println(s);
            System.out.flush();
        } catch (Throwable ignored) {
            // stdout 已关闭
        }
    }
}
