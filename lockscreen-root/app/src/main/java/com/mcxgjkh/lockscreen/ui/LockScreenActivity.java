package com.mcxgjkh.lockscreen.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.CredentialStore;
import com.mcxgjkh.lockscreen.core.LockEngine;
import com.mcxgjkh.lockscreen.core.Prefs;
import com.mcxgjkh.lockscreen.core.RootShell;
import com.mcxgjkh.lockscreen.core.WatchUi;
import com.mcxgjkh.lockscreen.service.OverlayService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 锁屏界面。它同时承担三件事，因此不需要任何后台服务：
 *   1) 被小组件/通知拉起时，先在前台占位，再把屏幕关掉 —— 灭屏后本界面即"活的锁屏层"，
 *      用户再亮屏时它通过 FLAG_SHOW_WHEN_LOCKED 显示在系统锁屏之上；
 *   2) 承载密码/图案校验（纯本地哈希比对，无网络、无无障碍）；
 *   3) 校验通过则以 FLAG_DISMISS_KEYGUARD + root 命令解除系统锁屏并退出。
 * 生命周期：灭屏 → onStop（此时不重拉，因为屏幕是灭的）→ 亮屏 → onStart/onResume。
 */
public class LockScreenActivity extends Activity implements PatternView.OnPatternListener {

    public static final String EXTRA_LOCK_NOW = "lock_now";

    private static final int[] KEY_IDS = {
            R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
            R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9
    };

    private Prefs prefs;
    private Handler handler;
    private TextView tvMsg;
    private TextView tvHint;
    private TextView tvSwitch;
    private LinearLayout dotsBox;
    private View pinPad;
    private PatternView patternView;

    private final StringBuilder pin = new StringBuilder(12);
    private String mode = "";
    private boolean unlocked;
    private boolean clockEnabled;
    private boolean lockInProgress;
    private boolean relaunched;

    private Drawable dotOn;
    private Drawable dotOff;

    private final Runnable lockRunnable = new Runnable() {
        @Override
        public void run() {
            startLock();
        }
    };

    private final Runnable lockoutTicker = new Runnable() {
        @Override
        public void run() {
            long left = CredentialStore.lockoutRemainingMs(LockScreenActivity.this);
            if (left <= 0L) {
                tvMsg.setText("");
                return;
            }
            tvMsg.setText(getString(R.string.too_many_try, (int) (left / 1000L) + 1));
            handler.postDelayed(this, 1000L);
        }
    };

    /** 亮屏自愈：个别 ROM（尤其无 keyguard 的设备）唤醒会直接回桌面，这里把锁屏层拉回前台。 */
    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                    && !unlocked && !isFinishing()) {
                handler.postDelayed(bringToFront, 150L);
            }
        }
    };

    private final Runnable bringToFront = new Runnable() {
        @Override
        public void run() {
            if (unlocked || isFinishing()) {
                return;
            }
            // 已经在前台就不用再拉一次，避免亮屏时闪一下
            if (hasWindowFocus()) {
                return;
            }
            Intent i = new Intent(LockScreenActivity.this, LockScreenActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(i);
            } catch (Exception ignored) {
                // 后台启动被限制时依赖严格模式重拉
            }
        }
    };

    private final Runnable relaunchRunnable = new Runnable() {
        @Override
        public void run() {
            if (unlocked || isFinishing()) {
                return;
            }
            // 已经在前台就不用再拉一次，避免亮屏时闪一下
            if (hasWindowFocus()) {
                return;
            }
            Intent i = new Intent(LockScreenActivity.this, LockScreenActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(i);
            } catch (Exception ignored) {
                // Android 10+ 若无悬浮窗权限会拒绝后台启动，此时依赖系统锁屏兜底
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        mode = CredentialStore.modeLabel(this);
        if (TextUtils.isEmpty(mode)) {
            Toast.makeText(this, R.string.setup_first, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_SECURE);

        // 锁屏时不能留着悬浮球：它浮在锁屏之上，点"主页"就能绕过
        OverlayService.stop(getApplicationContext());
        try {
            registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (Throwable ignored) {
            // 注册失败不影响主流程
        }

        setContentView(R.layout.activity_lock);
        handler = new Handler(Looper.getMainLooper());

        tvMsg = (TextView) findViewById(R.id.tv_msg);
        tvHint = (TextView) findViewById(R.id.tv_hint);
        dotsBox = (LinearLayout) findViewById(R.id.dots);
        pinPad = findViewById(R.id.pin_pad);
        patternView = (PatternView) findViewById(R.id.pattern_view);
        tvSwitch = (TextView) findViewById(R.id.tv_switch);

        TextView tvDate = (TextView) findViewById(R.id.tv_date);
        tvDate.setText(new SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(new Date()));

        dotOn = oval(0xFF4FC3F7);
        dotOff = oval(0x33FFFFFF);

        clockEnabled = CredentialStore.hasClockPin(this);
        tvSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleInput();
            }
        });

        if ("pattern".equals(mode)) {
            pinPad.setVisibility(View.GONE);
            dotsBox.setVisibility(View.GONE);
            patternView.setVisibility(View.VISIBLE);
            patternView.setOnPatternListener(this);
            tvHint.setText(R.string.draw_pattern);
            tvMsg.setText("");
            // 图案为主密码时，时钟密码仍需要键盘入口
            tvSwitch.setVisibility(clockEnabled ? View.VISIBLE : View.GONE);
        } else {
            patternView.setVisibility(View.GONE);
            pinPad.setVisibility(View.VISIBLE);
            dotsBox.setVisibility(View.VISIBLE);
            bindKeypad();
            tvHint.setText(R.string.enter_pin);
            buildDots(dotsCount());
        }

        WatchUi.applyRoundInset(findViewById(R.id.root), this);
        tuneForScreen();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_LOCK_NOW, false)) {
            // 先让界面真正可见，再关屏（顺序反了会被系统拒绝后台启动）
            handler.postDelayed(lockRunnable, 220L);
        }
    }

    /** 图案为主时，切到数字键盘输入"时钟密码"。 */
    private void toggleInput() {
        boolean toPin = pinPad.getVisibility() != View.VISIBLE;
        if (toPin) {
            patternView.setVisibility(View.GONE);
            patternView.reset();
            pinPad.setVisibility(View.VISIBLE);
            dotsBox.setVisibility(View.VISIBLE);
            pin.setLength(0);
            buildDots(dotsCount());
            tvSwitch.setText(R.string.switch_to_pattern);
            tvHint.setText(R.string.enter_pin);
        } else {
            pinPad.setVisibility(View.GONE);
            dotsBox.setVisibility(View.GONE);
            patternView.setVisibility(View.VISIBLE);
            tvSwitch.setText(R.string.switch_to_pin);
            tvHint.setText(R.string.draw_pattern);
        }
    }

    /** 手表/极小屏：隐藏时钟与提示，把高度全部让给键盘或图案；图案按可用区域动态定尺寸。 */
    private void tuneForScreen() {
        if (WatchUi.isCompact(this)) {
            int[] wh = WatchUi.screenSize(this);
            int shortDp = Math.round(Math.min(wh[0], wh[1])
                    / Math.max(getResources().getDisplayMetrics().density, 0.1f));
            if (shortDp < 230) {
                findViewById(R.id.clock).setVisibility(View.GONE);
                findViewById(R.id.tv_date).setVisibility(View.GONE);
            }
            if (shortDp < 200) {
                tvHint.setVisibility(View.GONE);
            }
        }
        final View area = findViewById(R.id.cred_area);
        area.post(new Runnable() {
            @Override
            public void run() {
                int side = Math.min(area.getWidth(), area.getHeight());
                if (side <= 0) {
                    return;
                }
                int size = Math.round(side * (WatchUi.isRound(LockScreenActivity.this) ? 0.94f : 0.88f));
                ViewGroup.LayoutParams lp = patternView.getLayoutParams();
                if (lp != null) {
                    lp.width = size;
                    lp.height = size;
                    patternView.setLayoutParams(lp);
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_LOCK_NOW, false)) {
            handler.removeCallbacks(lockRunnable);
            handler.postDelayed(lockRunnable, 220L);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        relaunched = false;
        handler.removeCallbacks(relaunchRunnable);
        handler.removeCallbacks(lockoutTicker);
        long left = CredentialStore.lockoutRemainingMs(this);
        if (left > 0L) {
            handler.post(lockoutTicker);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 用户按 Home/切走时把锁屏层拉回前台；灭屏（screen off）不触发，
        // 否则会与系统亮屏流程打架。
        if (!unlocked && !isFinishing() && !relaunched
                && prefs.strictMode() && LockEngine.isScreenOn(this)) {
            relaunched = true;
            handler.postDelayed(relaunchRunnable, 400L);
        }
    }

    @Override
    protected void onDestroy() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        try {
            unregisterReceiver(screenOnReceiver);
        } catch (Throwable ignored) {
            // 未注册或已注销
        }
        if (!prefs.persistentShell()) {
            // 非常驻模式：退出即释放 su 进程
            new Thread(new Runnable() {
                @Override
                public void run() {
                    RootShell.get().close();
                }
            }, "root-close").start();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 锁屏界面禁止返回键退出
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ 密码键盘

    private void bindKeypad() {
        View.OnClickListener digitClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDigit(Integer.parseInt((String) v.getTag()));
            }
        };
        for (int d = 0; d <= 9; d++) {
            Button b = (Button) findViewById(KEY_IDS[d]);
            b.setTag(String.valueOf(d));
            b.setOnClickListener(digitClick);
        }
        findViewById(R.id.key_del).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pin.length() > 0) {
                    pin.setLength(pin.length() - 1);
                    updateDots();
                }
            }
        });
        findViewById(R.id.key_ok).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitPin();
            }
        });
    }

    /** 指示点数量要覆盖"主密码"和"时钟密码"里更长的那个，否则输入时钟密码时点不够用。 */
    private int dotsCount() {
        int n = Math.max(prefs.pinLen(), CredentialStore.MIN_PIN);
        if (clockEnabled) {
            n = Math.max(n, Math.max(prefs.clockLen(), CredentialStore.MIN_PIN));
        }
        return n;
    }

    private void buildDots(int count) {
        dotsBox.removeAllViews();
        int size = (int) (9 * getResources().getDisplayMetrics().density);
        int margin = (int) (5 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.leftMargin = margin;
            lp.rightMargin = margin;
            dotsBox.addView(dot, lp);
        }
        updateDots();
    }

    private void updateDots() {
        int filled = pin.length();
        for (int i = 0; i < dotsBox.getChildCount(); i++) {
            dotsBox.getChildAt(i).setBackgroundDrawable(i < filled ? dotOn : dotOff);
        }
    }

    private void onDigit(int digit) {
        if (unlocked) {
            return;
        }
        if (CredentialStore.isLockedOut(this)) {
            handler.removeCallbacks(lockoutTicker);
            handler.post(lockoutTicker);
            return;
        }
        if (pin.length() >= CredentialStore.MAX_PIN) {
            return;
        }
        pin.append((char) ('0' + digit));
        updateDots();
        int expected = prefs.pinLen();
        // 设了时钟密码后长度可能不同，不能再按主密码长度自动提交
        if (!clockEnabled && expected >= CredentialStore.MIN_PIN && pin.length() == expected) {
            submitPin();
        }
    }

    private void submitPin() {
        if (pin.length() == 0 || unlocked) {
            return;
        }
        String candidate = pin.toString();
        // 先判时钟密码：命中则进入"只显示时间"，不解除系统锁屏
        if (clockEnabled && CredentialStore.verifyClockPin(this, candidate)) {
            enterClockMode();
            return;
        }
        if (CredentialStore.verifyPin(this, candidate)) {
            onUnlockSuccess();
        } else {
            pin.setLength(0);
            updateDots();
            onFailure(R.string.pin_wrong);
        }
    }

    // ------------------------------------------------------------ 图案

    @Override
    public void onPatternDetected(int[] pattern) {
        if (unlocked) {
            return;
        }
        if (CredentialStore.isLockedOut(this)) {
            patternView.setErrorState(true);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    patternView.reset();
                }
            }, 300L);
            handler.removeCallbacks(lockoutTicker);
            handler.post(lockoutTicker);
            return;
        }
        if (CredentialStore.verifyPattern(this, pattern)) {
            onUnlockSuccess();
        } else {
            patternView.setErrorState(true);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    patternView.reset();
                }
            }, 300L);
            onFailure(R.string.pattern_wrong);
        }
    }

    // ------------------------------------------------------------ 结果处理

    private void onFailure(int reasonRes) {
        vibrate();
        long left = CredentialStore.lockoutRemainingMs(this);
        if (left > 0L) {
            handler.removeCallbacks(lockoutTicker);
            handler.post(lockoutTicker);
            return;
        }
        String reason = getString(reasonRes);
        int remain = CredentialStore.remainingTries(this);
        tvMsg.setText(reason + "，" + getString(R.string.left_try, remain));
        if (remain <= 0) {
            handler.removeCallbacks(lockoutTicker);
            handler.post(lockoutTicker);
        }
    }

    /** 时钟密码通过：保持系统锁屏、隐藏悬浮球、只显示时间。 */
    private void enterClockMode() {
        unlocked = true;
        CredentialStore.resetFailures(this);
        Prefs.get(this).setClockMode(true);
        handler.removeCallbacksAndMessages(null);
        OverlayService.stop(getApplicationContext());
        Intent i = new Intent(this, ClockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (Exception ignored) {
            // 拉起失败则留在锁屏界面，不会暴露系统桌面
        }
        finish();
    }

    private void onUnlockSuccess() {
        unlocked = true;
        CredentialStore.resetFailures(this);
        Prefs.get(this).setClockMode(false);
        handler.removeCallbacksAndMessages(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                LockEngine.dismissKeyguard(app);
            }
        }, "unlock-worker").start();
        if (Prefs.get(this).overlayEnabled() && OverlayService.canOverlay(this)) {
            OverlayService.start(app);
        }
        finish();
    }

    private void startLock() {
        if (lockInProgress || unlocked) {
            return;
        }
        lockInProgress = true;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int result = LockEngine.lockScreen(app);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        lockInProgress = false;
                        if (result == LockEngine.FAILED) {
                            tvMsg.setText(R.string.lock_failed);
                        }
                    }
                });
            }
        }, "lock-worker").start();
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(45L);
            }
        } catch (Throwable ignored) {
            // 部分 ROM 禁用马达，忽略
        }
    }

    private static Drawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }
}
