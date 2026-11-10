package com.mcxgjkh.lockscreen.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.Clips;
import com.mcxgjkh.lockscreen.core.FastKeys;
import com.mcxgjkh.lockscreen.core.GlobalActions;
import com.mcxgjkh.lockscreen.core.LockEngine;
import com.mcxgjkh.lockscreen.core.PowerSaver;
import com.mcxgjkh.lockscreen.core.Prefs;
import com.mcxgjkh.lockscreen.core.RootShell;
import com.mcxgjkh.lockscreen.core.WatchUi;
import com.mcxgjkh.lockscreen.ui.ClipboardActivity;
import com.mcxgjkh.lockscreen.ui.LockScreenActivity;
import com.mcxgjkh.lockscreen.ui.MainActivity;

/**
 * 系统级悬浮球 + 功能面板（返回 / 主页 / 最近任务 / 通知栏 / 锁屏）。
 *
 * 交互约定（本轮修正）：
 * · 面板是**独立窗口、居中显示**，按钮固定高度，不再依附小球向上展开 ——
 *   小球靠近屏幕底部时旧的"向上展开"会把最后一个按钮挤出屏幕，出现点不到的情况；
 * · 点动作**不收起**面板，可以连点（返回→主页→锁屏）；只有「收起」或再点小球才关闭；
 * · 打开面板/执行动作都会提前预热 su 会话，锁屏走 `lockScreenFast`（不做 800ms 熄屏轮询），
 *   所以按下到熄屏只差一条命令的往返时间；
 * · 省电模式不再"每点一次就关掉 su 会话"（那是延迟的主要来源），改为空闲 25s 后自动释放。
 */
public class OverlayService extends Service {

    public static final String ACTION_STOP = "com.mcxgjkh.lockscreen.action.STOP_OVERLAY";
    public static final String ACTION_RESTORE_POWER = "com.mcxgjkh.lockscreen.action.RESTORE_POWER";

    private static final int NOTIF_ID = 2001;
    private static final String CHANNEL = "overlay_ball";
    private static final String CHANNEL_ECO = "overlay_ball_eco";

    private static final int ACT_BACK = 1;
    private static final int ACT_HOME = 2;
    private static final int ACT_RECENTS = 3;
    private static final int ACT_NOTIFY = 4;
    private static final int ACT_LOCK = 5;
    private static final int ACT_CLOSE = 6;
    private static final int ACT_PASTE = 7;
    private static final int ACT_CLIP = 8;

    private static final long ECO_ROOT_IDLE_MS = 25_000L;

    private WindowManager wm;
    private Handler handler;
    private View ballView;
    private WindowManager.LayoutParams ballParams;
    private View panelView;

    private boolean expanded;
    private boolean eco;
    private int ballSize;
    private int slop;

    private float downRawX;
    private float downRawY;
    private int startX;
    private int startY;
    private boolean dragging;
    private boolean panelWasOpenOnDown;
    /** 拖动节流：每帧最多提交一次窗口位移，避免每个 MOVE 都走一趟 WindowManager IPC。 */
    private boolean movePending;
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;

    private final Runnable flushMove = new Runnable() {
        @Override
        public void run() {
            movePending = false;
            safeUpdateBall();
        }
    };

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                hidePanel();
            }
        }
    };

    private final Runnable closeRoot = new Runnable() {
        @Override
        public void run() {
            RootShell.get().close();
        }
    };

    public static void start(Context c) {
        c.startService(new Intent(c, OverlayService.class));
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, OverlayService.class));
        } catch (Throwable ignored) {
            // 已经停止
        }
    }

    public static boolean canOverlay(Context c) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        FastKeys.init(this);
        eco = Prefs.get(this).overlayMode() == 1;
        // 时钟模式（第二密码）下不显示任何悬浮窗
        if (Prefs.get(this).clockMode()) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(NOTIF_ID, buildNotification());
        if (wm == null || !canOverlay(this)) {
            stopSelf();
            return;
        }
        showBall();
        warmUpRoot();
        try {
            registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        } catch (Throwable ignored) {
            // 注册失败不影响悬浮球
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESTORE_POWER.equals(intent.getAction())) {
            restorePower();
            return START_NOT_STICKY;
        }
        if (!canOverlay(this) || Prefs.get(this).clockMode()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ballView == null) {
            showBall();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        hidePanel();
        if (ballView != null && wm != null) {
            try {
                wm.removeView(ballView);
            } catch (Throwable ignored) {
                // 视图可能已随窗口销毁
            }
            ballView = null;
        }
        try {
            unregisterReceiver(screenOffReceiver);
        } catch (Throwable ignored) {
            // 未注册或已注销
        }
        FastKeys.close();
        stopForeground(true);
        super.onDestroy();
    }

    // ------------------------------------------------------------ 悬浮球

    private void showBall() {
        ballView = LayoutInflater.from(this).inflate(R.layout.overlay_ball, null);
        View ball = ballView.findViewById(R.id.ball);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        ballParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;

        ballSize = getResources().getDimensionPixelSize(R.dimen.overlay_ball);
        if (eco) {
            ballSize = dp(36);
            ViewGroup.LayoutParams lp = ball.getLayoutParams();
            if (lp != null) {
                lp.width = ballSize;
                lp.height = ballSize;
                ball.setLayoutParams(lp);
            }
        }

        int[] wh = WatchUi.screenSize(this);
        ballParams.x = Math.max(0, wh[0] - ballSize - dp(6));
        ballParams.y = Math.max(dp(24), wh[1] / 3);
        slop = ViewConfiguration.get(this).getScaledTouchSlop();

        try {
            wm.addView(ballView, ballParams);
        } catch (Throwable t) {
            stopSelf();
            return;
        }
        bindGestures(ball);
        // 预建面板视图：把"点开面板"从两次布局inflate降到一次窗口添加
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (panelView == null && ballView != null) {
                    panelView = buildPanel();
                }
            }
        }, 400L);
    }

    private void bindGestures(final View ball) {
        ball.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = ballParams.x;
                        startY = ballParams.y;
                        dragging = false;
                        v.setPressed(true);
                        panelWasOpenOnDown = expanded;
                        if (expanded) {
                            hidePanel();
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            int[] wh = WatchUi.screenSize(OverlayService.this);
                            int w = ballView.getWidth() > 0 ? ballView.getWidth() : ballSize;
                            int h = ballView.getHeight() > 0 ? ballView.getHeight() : ballSize;
                            ballParams.x = clamp(startX + dx, 0, Math.max(0, wh[0] - w));
                            ballParams.y = clamp(startY + dy, 0, Math.max(0, wh[1] - h));
                            // 合并同一帧内的多次移动，交给下一个动画帧统一提交
                            if (!movePending && ballView != null) {
                                movePending = true;
                                ballView.postOnAnimation(flushMove);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        if (ballView != null) {
                            ballView.removeCallbacks(flushMove);
                        }
                        movePending = false;
                        if (dragging) {
                            snapToEdge();
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            v.performClick();
                            // DOWN 时若面板是开着的，已经关掉了，这里不要再打开
                            if (!panelWasOpenOnDown) {
                                showPanel();
                            }
                        }
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void snapToEdge() {
        int[] wh = WatchUi.screenSize(this);
        int w = ballView.getWidth() > 0 ? ballView.getWidth() : ballSize;
        int h = ballView.getHeight() > 0 ? ballView.getHeight() : ballSize;
        int centerX = ballParams.x + w / 2;
        ballParams.x = centerX < wh[0] / 2 ? 0 : Math.max(0, wh[0] - w);
        ballParams.y = clamp(ballParams.y, 0, Math.max(0, wh[1] - h));
        safeUpdateBall();
    }

    private void safeUpdateBall() {
        if (ballParams.x == lastX && ballParams.y == lastY) {
            return;
        }
        try {
            if (ballView != null && wm != null) {
                wm.updateViewLayout(ballView, ballParams);
                lastX = ballParams.x;
                lastY = ballParams.y;
            }
        } catch (Throwable ignored) {
            // 窗口被系统回收时忽略
        }
    }

    // ------------------------------------------------------------ 功能面板

    private void togglePanel() {
        if (expanded) {
            hidePanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        if (panelView == null) {
            panelView = buildPanel();
        }
        if (panelView.getParent() != null) {
            expanded = true;
            return;
        }
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        boolean compact = WatchUi.isCompact(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(compact ? 150 : 190),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        try {
            wm.addView(panelView, lp);
            expanded = true;
        } catch (Throwable ignored) {
            expanded = false;
        }
        // 面板马上要用：提前把 su 会话捂热，第一条命令不用等进程创建
        warmUpRoot();
    }

    private void hidePanel() {
        if (panelView != null && panelView.getParent() != null && wm != null) {
            try {
                wm.removeView(panelView);
            } catch (Throwable ignored) {
                // 已经移除
            }
        }
        expanded = false;
    }

    /** 按钮固定高度、按行列排布；手表 2 列 × 3 行，手机 1 列。 */
    private View buildPanel() {
        boolean compact = WatchUi.isCompact(this);
        int cols = compact ? 3 : 2;
        int btnH = compact ? dp(40) : getResources().getDimensionPixelSize(R.dimen.overlay_btn);

        int[] actions = eco
                ? new int[]{ACT_BACK, ACT_HOME, ACT_LOCK, ACT_CLOSE}
                : new int[]{ACT_BACK, ACT_HOME, ACT_RECENTS, ACT_NOTIFY,
                        ACT_LOCK, ACT_PASTE, ACT_CLIP, ACT_CLOSE};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_panel);
        int pad = dp(4);
        root.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < actions.length; i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                View child;
                if (idx < actions.length) {
                    child = makeButton(actions[idx]);
                } else {
                    child = new View(this); // 占位，保证最后一行按钮宽度一致
                }
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(0, btnH, 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(child, lp);
            }
            root.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, btnH));
        }
        return root;
    }

    private TextView makeButton(final int act) {
        TextView b = new TextView(this);
        b.setText(labelOf(act));
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.overlay_text));
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_overlay_btn);
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (act == ACT_CLOSE) {
                    hidePanel();
                } else {
                    runAction(act);
                }
            }
        });
        return b;
    }

    private int labelOf(int act) {
        switch (act) {
            case ACT_BACK:
                return R.string.ov_back;
            case ACT_HOME:
                return R.string.ov_home;
            case ACT_RECENTS:
                return R.string.ov_recents;
            case ACT_NOTIFY:
                return R.string.ov_notify;
            case ACT_LOCK:
                return R.string.ov_lock;
            case ACT_PASTE:
                return R.string.ov_paste;
            case ACT_CLIP:
                return R.string.ov_clip;
            default:
                return R.string.ov_close;
        }
    }

    private void runAction(final int act) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long started = SystemClock.uptimeMillis();
                boolean ok;
                int errRes = R.string.ov_failed;
                switch (act) {
                    case ACT_BACK:
                        ok = GlobalActions.back(getApplicationContext());
                        break;
                    case ACT_HOME:
                        ok = GlobalActions.home(getApplicationContext());
                        break;
                    case ACT_RECENTS:
                        ok = GlobalActions.recents(getApplicationContext());
                        break;
                    case ACT_NOTIFY:
                        ok = GlobalActions.expandNotifications(getApplicationContext());
                        break;
                    case ACT_LOCK:
                        hidePanel();
                        ok = lockViaActivity();
                        break;
                    case ACT_PASTE:
                        ok = Clips.pasteIntoFocused(getApplicationContext());
                        errRes = R.string.clip_paste_fail;
                        break;
                    case ACT_CLIP:
                        try {
                            Intent clip = new Intent(OverlayService.this, ClipboardActivity.class);
                            clip.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(clip);
                            ok = true;
                        } catch (Throwable t) {
                            ok = false;
                        }
                        break;
                    default:
                        ok = false;
                }
                Log.d("OverlayAction", "act=" + act + " ok=" + ok
                        + " cost=" + (SystemClock.uptimeMillis() - started) + "ms");
                if (!ok) {
                    toast(errRes);
                }
                // 省电模式：不立刻关 su（那是点击变慢的主因），空闲 25s 后再释放
                handler.removeCallbacks(closeRoot);
                if (eco && Prefs.get(OverlayService.this).persistentShell()) {
                    handler.postDelayed(closeRoot, ECO_ROOT_IDLE_MS);
                }
            }
        }, "overlay-action").start();
    }

    // ------------------------------------------------------------ 锁屏

    /**
     * 锁屏必须"先拉起锁屏界面、再由界面关屏"：
     * 只发关屏命令的话，系统没有 keyguard 的设备点亮后就直接回到桌面，等于没锁。
     */
    private boolean lockViaActivity() {
        try {
            Intent i = new Intent(this, LockScreenActivity.class);
            i.putExtra(LockScreenActivity.EXTRA_LOCK_NOW, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return GlobalActions.lockScreen(getApplicationContext()) != LockEngine.FAILED;
        }
    }

    // ------------------------------------------------------------ 省电恢复

    private void restorePower() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                PowerSaver.disable(getApplicationContext());
                Prefs.get(getApplicationContext()).setOverlayMode(0);
                toast(R.string.eco_disabled);
                stopSelf();
                startService(new Intent(OverlayService.this, OverlayService.class));
            }
        }, "eco-restore").start();
    }

    // ------------------------------------------------------------ 其它

    private void warmUpRoot() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (Prefs.get(OverlayService.this).fastKeys()) {
                    FastKeys.warmUp();
                }
                if (Prefs.get(OverlayService.this).persistentShell()) {
                    RootShell.get().ensureOpen();
                }
            }
        }, "root-warmup").start();
    }

    private Notification buildNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String channelId = eco ? CHANNEL_ECO : CHANNEL;
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(channelId,
                    getString(R.string.overlay_running),
                    eco ? NotificationManager.IMPORTANCE_MIN : NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent psi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Intent restore = new Intent(this, OverlayService.class).setAction(ACTION_RESTORE_POWER);
        PendingIntent pri = PendingIntent.getService(this, 2, restore,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(getString(eco ? R.string.overlay_eco_short : R.string.overlay_running))
                .setContentText(getString(eco ? R.string.eco_active : R.string.overlay_stop))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_MIN);
        if (eco) {
            b.addAction(android.R.drawable.ic_menu_revert,
                    getString(R.string.eco_restore), pri);
        } else {
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.overlay_stop), psi);
        }
        return b.build();
    }

    private void toast(final int res) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), res, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        return Math.min(v, max);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
