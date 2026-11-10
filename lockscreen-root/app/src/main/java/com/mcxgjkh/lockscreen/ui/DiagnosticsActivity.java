package com.mcxgjkh.lockscreen.ui;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.AccessibilityTool;
import com.mcxgjkh.lockscreen.core.ClipRoot;
import com.mcxgjkh.lockscreen.core.Clips;
import com.mcxgjkh.lockscreen.core.Prefs;
import com.mcxgjkh.lockscreen.core.RootShell;
import com.mcxgjkh.lockscreen.core.WatchUi;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;

/**
 * 诊断页：把"为什么 root 用不了 / 剪贴板为什么是空的"变成一段可读文本。
 *
 * 全部检查在后台线程跑（su 探测可能等到用户点授权），结果同时显示在界面上并写入
 * /sdcard/Android/data/<pkg>/files/diag.txt，可以直接 adb pull 出来。
 */
public class DiagnosticsActivity extends Activity {

    private TextView output;
    private Button refresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        WatchUi.applyRoundInset(findViewById(R.id.root), this);

        output = (TextView) findViewById(R.id.tv_output);
        refresh = (Button) findViewById(R.id.btn_refresh);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runChecks();
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveReport();
            }
        });
        runChecks();
    }

    private void runChecks() {
        refresh.setEnabled(false);
        output.setText(R.string.diag_running);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = buildReport();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        output.setText(report);
                        refresh.setEnabled(true);
                    }
                });
            }
        }, "diag").start();
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("==== 锁屏诊断 ====\n");
        sb.append("model=").append(Build.MODEL)
                .append("  brand=").append(Build.BRAND)
                .append("  sdk=").append(Build.VERSION.SDK_INT)
                .append("  release=").append(Build.VERSION.RELEASE).append('\n');
        sb.append("pkg=").append(getPackageName())
                .append("  version=").append(versionName()).append('\n');
        sb.append("screen=").append(WatchUi.screenSize(this)[0]).append('x')
                .append(WatchUi.screenSize(this)[1])
                .append("  round=").append(WatchUi.isRound(this)).append('\n');
        sb.append("overlayPermission=")
                .append(Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))
                .append('\n');

        // ---------- Root ----------
        sb.append("\n---- Root ----\n");
        RootShell.Probe probe = RootShell.get().probe();
        sb.append("rooted=").append(probe.rooted)
                .append("  su=").append(probe.suPath).append('\n');
        sb.append(probe.detail);

        // ---------- 剪贴板 ----------
        sb.append("\n---- 系统服务 ----\n");
        sb.append("serviceCount=").append(serviceCount()).append('\n');
        sb.append("binder(clipboard)=").append(binderState("clipboard")).append('\n');
        sb.append("binder(accessibility)=").append(binderState("accessibility")).append('\n');
        sb.append("binder(input)=").append(binderState("input")).append('\n');
        sb.append("binder(window)=").append(binderState("window")).append('\n');

        sb.append("\n---- 剪贴板 ----\n");

        ClipboardManager cm = null;
        try {
            cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (Throwable t) {
            sb.append("getSystemService: ").append(t).append('\n');
        }
        sb.append("ClipboardManager=").append(cm == null ? "null" : cm.getClass().getName()).append('\n');
        if (cm != null) {
            try {
                sb.append("hasPrimaryClip=").append(cm.hasPrimaryClip()).append('\n');
            } catch (Throwable t) {
                sb.append("hasPrimaryClip 抛异常: ").append(t).append('\n');
            }
        }

        Clips.init(this);
        String current = Clips.readSystem(this);
        sb.append("readSystem len=").append(current.length())
                .append("  lastError=").append(Clips.lastError).append('\n');
        sb.append("readAny len=").append(Clips.readAny(this).length())
                .append("  lastError=").append(Clips.lastError).append('\n');

        String probeText = "wb-clip-" + System.currentTimeMillis();
        boolean wrote = Clips.writeSystem(this, probeText);
        String back = Clips.readSystem(this);
        sb.append("writeSystem=").append(wrote)
                .append("  readback=").append(probeText.equals(back))
                .append("  lastError=").append(Clips.lastError).append('\n');

        sb.append("ClipTool(raw)=").append(ClipRoot.rawProbe(this)).append('\n');

        Prefs prefs = Prefs.get(this);
        sb.append("monitorEnabled=").append(prefs.clipMonitor())
                .append("  captures=").append(Clips.captureCount(this))
                .append("  lastCaptureAt=").append(Clips.lastCaptureAt(this)).append('\n');

        // ---------- 无障碍 ----------
        sb.append("\n---- 无障碍 ----\n");
        try {
            int listed = AccessibilityTool.list(this, false).size();
            int all = AccessibilityTool.list(this, true).size();
            int on = AccessibilityTool.enabledSet(this).size();
            sb.append("detected=").append(listed)
                    .append("  allServices=").append(all)
                    .append("  enabled=").append(on).append('\n');
            String flag = "";
            try {
                flag = Settings.Secure.getString(getContentResolver(), "accessibility_enabled");
            } catch (Throwable ignored) {
                // 读不到按空处理
            }
            sb.append("accessibility_enabled=").append(flag).append('\n');
            for (AccessibilityTool.Item it : AccessibilityTool.list(this, false)) {
                sb.append("  ").append(it.flatten)
                        .append("  declared=").append(it.declared)
                        .append("  recognized=").append(it.recognized)
                        .append("  component=").append(componentState(it.flatten))
                        .append('\n');
            }
            Set<String> raw = AccessibilityTool.enabledSet(this);
            Set<String> sys = AccessibilityTool.systemEnabledSet(this);
            sb.append("rawSetting=").append(raw).append('\n');
            sb.append("systemAccepted=").append(sys).append('\n');
            if (!raw.isEmpty() && !sys.containsAll(raw)) {
                sb.append("注意：rawSetting 里有、systemAccepted 里没有的服务，系统没有真正启用它。\n");
            }
        } catch (Throwable t) {
            sb.append("enumerate failed: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static Object binder(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return sm.getMethod("getService", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String binderState(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object b = sm.getMethod("getService", String.class).invoke(null, name);
            return b == null ? "null（系统没有该服务）" : "ok";
        } catch (Throwable t) {
            return "EX " + t.getClass().getSimpleName();
        }
    }

    private static int serviceCount() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            String[] all = (String[]) sm.getMethod("listServices").invoke(null);
            return all == null ? -1 : all.length;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 组件启用状态：0 默认 / 1 启用 / 2 禁用 / 3、4 被禁用 */
    private String componentState(String flatten) {
        try {
            int slash = flatten.indexOf('/');
            android.content.ComponentName cn = new android.content.ComponentName(
                    flatten.substring(0, slash), flatten.substring(slash + 1));
            int st = getPackageManager().getComponentEnabledSetting(cn);
            switch (st) {
                case 1:
                    return "enabled";
                case 2:
                case 3:
                case 4:
                    return "DISABLED(" + st + ")";
                default:
                    return "default";
            }
        } catch (Throwable t) {
            return "EX";
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void saveReport() {
        String text = output.getText() == null ? "" : output.getText().toString();
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            Toast.makeText(this, R.string.diag_save_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        File f = new File(dir, "diag.txt");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
            Toast.makeText(this, getString(R.string.diag_saved, f.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.diag_save_fail, Toast.LENGTH_SHORT).show();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
