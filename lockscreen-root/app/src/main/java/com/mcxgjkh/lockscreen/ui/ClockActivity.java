package com.mcxgjkh.lockscreen.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.LockEngine;
import com.mcxgjkh.lockscreen.core.Prefs;
import com.mcxgjkh.lockscreen.core.WatchUi;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 时钟模式：用"时钟密码"解锁后进入的界面，只显示时间。
 *
 * 关键实现：**不解除系统锁屏**。本界面以 FLAG_SHOW_WHEN_LOCKED 盖在 keyguard 之上，
 * 因此 Home / 最近任务都被系统锁屏挡住，用户只能看到时间——不需要把自己注册成 Launcher，
 * 也不需要拦截任何系统按键。长按时间可回到密码/图案界面重新用主密码解锁。
 */
public class ClockActivity extends Activity {

    private Prefs prefs;
    private Handler handler;
    private boolean leaving;
    private boolean relaunched;

    private final Runnable relaunch = new Runnable() {
        @Override
        public void run() {
            if (leaving || isFinishing()) {
                return;
            }
            Intent i = new Intent(ClockActivity.this, ClockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(i);
            } catch (Exception ignored) {
                // 后台启动被限制时由系统锁屏兜底
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        if (!prefs.hasCredential()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_clock);
        handler = new Handler(Looper.getMainLooper());
        WatchUi.applyRoundInset(findViewById(R.id.root), this);

        TextView date = (TextView) findViewById(R.id.tv_date);
        date.setText(new SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(new Date()));

        // 默认隐藏提示：长按依然有效，只是不告诉别人
        findViewById(R.id.tv_hint).setVisibility(
                prefs.clockHint() ? View.VISIBLE : View.GONE);

        View.OnLongClickListener back = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                backToUnlock();
                return true;
            }
        };
        findViewById(R.id.clock).setOnLongClickListener(back);
        findViewById(R.id.root).setOnLongClickListener(back);
    }

    @Override
    protected void onResume() {
        super.onResume();
        relaunched = false;
        if (handler != null) {
            handler.removeCallbacks(relaunch);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 若被切走（个别 ROM 的 Home 会穿透），把时钟层拉回来；灭屏不触发
        if (!leaving && !isFinishing() && !relaunched
                && prefs.strictMode() && LockEngine.isScreenOn(this)) {
            relaunched = true;
            handler.postDelayed(relaunch, 250L);
        }
    }

    @Override
    protected void onDestroy() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (leaving) {
            // 用户主动返回解锁：解除时钟模式，悬浮球可再次启用
            prefs.setClockMode(false);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 时钟模式不允许返回
    }

    private void backToUnlock() {
        leaving = true;
        Intent i = new Intent(this, LockScreenActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (Exception ignored) {
            // 拉起失败就留在这里，避免暴露系统界面
        }
        finish();
    }
}
