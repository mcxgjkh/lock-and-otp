package com.mcxgjkh.lockscreen.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.DialogInterface;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.lockscreen.LockAdminReceiver;
import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.CredentialStore;
import com.mcxgjkh.lockscreen.core.LockEngine;
import com.mcxgjkh.lockscreen.core.NotificationHelper;
import com.mcxgjkh.lockscreen.core.PowerSaver;
import com.mcxgjkh.lockscreen.core.Prefs;
import com.mcxgjkh.lockscreen.core.RootShell;
import com.mcxgjkh.lockscreen.core.WatchUi;
import com.mcxgjkh.lockscreen.service.ClipMonitorService;
import com.mcxgjkh.lockscreen.service.OverlayService;

/**
 * 主界面：只做配置与状态展示，不参与锁屏流程，因此可以随时被系统回收，
 * 不会因为"应用被杀"导致锁屏失效（锁屏界面是独立任务栈的 Activity）。
 */
public class MainActivity extends Activity {

    private static final int REQ_ADMIN = 1001;

    private Prefs prefs;
    private TextView tvCred;
    private TextView tvRoot;
    private CheckBox cbNotify;
    private CheckBox cbStrict;
    private CheckBox cbPersist;
    private CheckBox cbOverlay;
    private Button btnShape;
    private volatile boolean rootChecking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        setContentView(R.layout.activity_main);

        WatchUi.applyRoundInset(findViewById(R.id.root), this);

        tvCred = (TextView) findViewById(R.id.tv_cred_status);
        tvRoot = (TextView) findViewById(R.id.tv_root_status);
        tvRoot.setText("Root：" + getString(R.string.s_root_unknown));

        cbNotify = (CheckBox) findViewById(R.id.cb_notify);
        cbStrict = (CheckBox) findViewById(R.id.cb_strict);
        cbPersist = (CheckBox) findViewById(R.id.cb_persist);
        cbOverlay = (CheckBox) findViewById(R.id.cb_overlay);
        btnShape = (Button) findViewById(R.id.btn_shape);

        findViewById(R.id.btn_lock_now).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lockNow();
            }
        });
        findViewById(R.id.btn_set_pin).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSetup(SetupActivity.MODE_PIN);
            }
        });
        findViewById(R.id.btn_set_pattern).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSetup(SetupActivity.MODE_PATTERN);
            }
        });
        findViewById(R.id.btn_check_root).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkRoot();
            }
        });
        findViewById(R.id.btn_overlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlay();
            }
        });
        findViewById(R.id.btn_accessibility).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AccessibilityActivity.class));
            }
        });
        findViewById(R.id.btn_clock_pin).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showClockPinDialog();
            }
        });
        findViewById(R.id.btn_overlay_mode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverlayModeDialog();
            }
        });
        findViewById(R.id.btn_clipboard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ClipboardActivity.class));
            }
        });
        btnShape.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showShapeDialog();
            }
        });
        CheckBox cbClipMonitor = (CheckBox) findViewById(R.id.cb_clip_monitor);
        cbClipMonitor.setChecked(prefs.clipMonitor());
        cbClipMonitor.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setClipMonitor(isChecked);
                if (isChecked) {
                    ClipMonitorService.start(MainActivity.this);
                } else {
                    ClipMonitorService.stop(MainActivity.this);
                }
            }
        });

        CheckBox cbFastKeys = (CheckBox) findViewById(R.id.cb_fast_keys);
        cbFastKeys.setChecked(prefs.fastKeys());
        cbFastKeys.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setFastKeys(isChecked);
            }
        });

        CheckBox cbClockHint = (CheckBox) findViewById(R.id.cb_clock_hint);
        cbClockHint.setChecked(prefs.clockHint());
        cbClockHint.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setClockHint(isChecked);
            }
        });

        cbOverlay.setChecked(prefs.overlayEnabled());
        cbOverlay.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setOverlayEnabled(isChecked);
                if (!isChecked) {
                    OverlayService.stop(MainActivity.this);
                    Toast.makeText(MainActivity.this, R.string.overlay_stopped,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!OverlayService.canOverlay(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, R.string.overlay_need_permission,
                            Toast.LENGTH_LONG).show();
                    requestOverlay();
                    return;
                }
                OverlayService.start(MainActivity.this);
                Toast.makeText(MainActivity.this, R.string.overlay_started,
                        Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_admin).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestAdmin();
            }
        });

        cbNotify.setChecked(prefs.notifyEnabled());
        cbNotify.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setNotifyEnabled(isChecked);
                if (isChecked) {
                    NotificationHelper.show(MainActivity.this);
                } else {
                    NotificationHelper.hide(MainActivity.this);
                }
            }
        });
        cbStrict.setChecked(prefs.strictMode());
        cbStrict.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setStrictMode(isChecked);
            }
        });
        cbPersist.setChecked(prefs.persistentShell());
        cbPersist.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setPersistentShell(isChecked);
                if (!isChecked) {
                    RootShell.get().close();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 能回到主界面，说明当前不在时钟模式
        prefs.setClockMode(false);
        updateCredStatus();
        updatePermissionButton();
        updateShapeButton();
        updateOverlayModeButton();
        // 从系统设置授权返回后，若开关仍为开，则把悬浮球拉起来
        if (prefs.overlayEnabled() && OverlayService.canOverlay(this)) {
            OverlayService.start(this);
        }
        // 剪贴板接管默认开启：每次回到应用确保服务在跑
        if (prefs.clipMonitor()) {
            ClipMonitorService.start(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!prefs.persistentShell()) {
            RootShell.get().close();
        }
    }

    private void updateCredStatus() {
        String mode = CredentialStore.modeLabel(this);
        String label;
        if ("pin".equals(mode)) {
            label = getString(R.string.cred_pin) + "（" + prefs.pinLen() + " 位）";
        } else if ("pattern".equals(mode)) {
            label = getString(R.string.cred_pattern);
        } else {
            label = getString(R.string.cred_none);
        }
        String clock = getString(CredentialStore.hasClockPin(this)
                ? R.string.clock_pin_on : R.string.clock_pin_none);
        tvCred.setText("解锁方式：" + label + "\n" + clock);
    }

    private void updatePermissionButton() {
        Button b = (Button) findViewById(R.id.btn_overlay);
        boolean granted = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        b.setText(granted ? "悬浮窗权限：已具备" : getString(R.string.btn_overlay));
    }

    private void updateShapeButton() {
        String label;
        int shape = prefs.screenShape();
        if (shape == WatchUi.SHAPE_SQUARE) {
            label = getString(R.string.shape_square);
        } else if (shape == WatchUi.SHAPE_ROUND) {
            label = getString(R.string.shape_round);
        } else {
            label = getString(R.string.shape_auto);
        }
        btnShape.setText(getString(R.string.btn_shape, label));
    }

    private void showClockPinDialog() {
        if (!CredentialStore.hasClockPin(this)) {
            startSetup(SetupActivity.MODE_CLOCK);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_clock_pin)
                .setItems(new CharSequence[]{
                        getString(R.string.clock_pin_edit),
                        getString(R.string.clock_pin_clear)
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            startSetup(SetupActivity.MODE_CLOCK);
                        } else {
                            CredentialStore.clearClockPin(MainActivity.this);
                            Toast.makeText(MainActivity.this, R.string.clock_pin_cleared,
                                    Toast.LENGTH_SHORT).show();
                            updateCredStatus();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateOverlayModeButton() {
        Button b = (Button) findViewById(R.id.btn_overlay_mode);
        String label = prefs.overlayMode() == 1
                ? getString(R.string.mode_eco_short) : getString(R.string.mode_full_short);
        if (PowerSaver.isActive(this)) {
            label = label + " · " + getString(R.string.eco_state_short);
        }
        b.setText(getString(R.string.btn_overlay_mode, label));
    }

    private void showOverlayModeDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_mode_title)
                .setSingleChoiceItems(new CharSequence[]{
                        getString(R.string.overlay_mode_full),
                        getString(R.string.overlay_mode_eco)
                }, prefs.overlayMode(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 1) {
                            confirmEco();
                        } else {
                            applyOverlayMode(0);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmEco() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.eco_confirm_title)
                .setMessage(R.string.eco_confirm_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applyOverlayMode(1);
                    }
                })
                .show();
    }

    /** 切换模式并在后台线程真正下发/还原系统设置。 */
    private void applyOverlayMode(final int mode) {
        prefs.setOverlayMode(mode);
        updateOverlayModeButton();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = mode == 1 ? PowerSaver.enable(app) : PowerSaver.disable(app);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            Toast.makeText(MainActivity.this, R.string.eco_need_root,
                                    Toast.LENGTH_LONG).show();
                            if (mode == 1) {
                                prefs.setOverlayMode(0);
                                updateOverlayModeButton();
                            }
                        } else {
                            Toast.makeText(MainActivity.this,
                                    mode == 1 ? R.string.eco_enabled : R.string.eco_disabled,
                                    Toast.LENGTH_SHORT).show();
                        }
                        restartOverlay();
                    }
                });
            }
        }, "eco-apply").start();
    }

    private void restartOverlay() {
        if (prefs.overlayEnabled() && OverlayService.canOverlay(this)) {
            OverlayService.stop(this);
            OverlayService.start(this);
        }
    }

    private void showShapeDialog() {
        final String[] labels = new String[]{
                getString(R.string.shape_auto),
                getString(R.string.shape_square),
                getString(R.string.shape_round)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.shape_title)
                .setSingleChoiceItems(labels, prefs.screenShape(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setScreenShape(which);
                        dialog.dismiss();
                        recreate();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startSetup(String mode) {
        Intent i = new Intent(this, SetupActivity.class);
        i.putExtra(SetupActivity.EXTRA_MODE, mode);
        startActivity(i);
    }

    private void lockNow() {
        if (!prefs.hasCredential()) {
            Toast.makeText(this, R.string.setup_first, Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, LockScreenActivity.class);
        i.putExtra(LockScreenActivity.EXTRA_LOCK_NOW, true);
        startActivity(i);
    }

    private void checkRoot() {
        if (rootChecking) {
            return;
        }
        rootChecking = true;
        tvRoot.setText("Root：" + getString(R.string.root_checking));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RootShell.get().isRooted();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        rootChecking = false;
                        if (isFinishing()) {
                            return;
                        }
                        tvRoot.setText("Root：" + getString(ok ? R.string.s_root_ok : R.string.s_root_no));
                    }
                });
            }
        }, "root-check").start();
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT < 23) {
            Toast.makeText(this, "Android 6.0 以下无需该权限", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已具备悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开悬浮窗设置，请手动开启", Toast.LENGTH_LONG).show();
        }
    }

    private void requestAdmin() {
        if (LockEngine.isDeviceAdminActive(this)) {
            Toast.makeText(this, "设备管理器已启用", Toast.LENGTH_SHORT).show();
            return;
        }
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            Toast.makeText(this, "设备管理器不可用", Toast.LENGTH_LONG).show();
            return;
        }
        ComponentName cn = new ComponentName(this, LockAdminReceiver.class);
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn);
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_desc));
        try {
            startActivityForResult(i, REQ_ADMIN);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开设备管理器设置", Toast.LENGTH_LONG).show();
        }
    }
}
