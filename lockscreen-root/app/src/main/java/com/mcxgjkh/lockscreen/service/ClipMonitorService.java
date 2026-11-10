package com.mcxgjkh.lockscreen.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.Clips;
import com.mcxgjkh.lockscreen.ui.ClipboardActivity;

/**
 * 剪贴板接管服务：常驻监听系统剪贴板变化，把**其它应用复制的内容**收进本应用。
 *
 * 三条捕获路径，按代价从低到高：
 *   1. OnPrimaryClipChangedListener —— 事件驱动，最省；
 *   2. 亮屏期间低频轮询（2.5s）—— 兜底：部分 ROM 不投递回调，或回调被系统丢掉；
 *   3. Root 通道（app_process + system 上下文）—— 只在该事件确实发生、但普通通道
 *      读到空时使用（有些 ROM 只允许"当前焦点应用"读剪贴板）。
 *
 * 读取限制说明：Android 10+ 禁止后台应用读取剪贴板，回调能收到但内容为空，
 * 此时会自动落到 Root 通道；没有 Root 就只在打开本应用界面时同步（界面在前台可读）。
 */
public class ClipMonitorService extends Service {

    public static final String ACTION_STOP = "com.mcxgjkh.lockscreen.action.STOP_CLIP_MONITOR";

    private static final int NOTIF_ID = 2002;
    private static final String CHANNEL = "clip_monitor";
    /** 部分 ROM 在回调里立刻读取会拿到旧值，稍等一下再读。 */
    private static final long READ_DELAY_MS = 120L;
    /** 兜底轮询间隔；只在亮屏时跑。 */
    private static final long POLL_MS = 2500L;

    private ClipboardManager clipboard;
    private Handler handler;
    private boolean screenOn = true;

    private final Runnable capture = new Runnable() {
        @Override
        public void run() {
            // 事件触发的捕获：普通通道读到空时允许走 Root 通道
            String text = Clips.readAny(ClipMonitorService.this);
            if (text != null && text.length() > 0) {
                Clips.onExternalCopy(ClipMonitorService.this, text);
            }
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!screenOn) {
                return;
            }
            // 轮询只用普通通道：没有复制事件时读到的多半是空，不值得为它起 root 进程
            String text = Clips.readSystem(ClipMonitorService.this);
            if (text != null && text.length() > 0) {
                Clips.onExternalCopy(ClipMonitorService.this, text);
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final ClipboardManager.OnPrimaryClipChangedListener listener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    handler.removeCallbacks(capture);
                    handler.postDelayed(capture, READ_DELAY_MS);
                }
            };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                handler.removeCallbacks(poll);
                handler.postDelayed(poll, 500L);
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                handler.removeCallbacks(poll);
            }
        }
    };

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, ClipMonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignored) {
            // 后台启动被限制时忽略，下次打开应用会再拉起
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, ClipMonitorService.class));
        } catch (Throwable ignored) {
            // 已停止
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        startForeground(NOTIF_ID, buildNotification());
        try {
            clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.addPrimaryClipChangedListener(listener);
            }
        } catch (Throwable ignored) {
            // 个别 ROM 禁止注册，忽略
        }
        try {
            screenOn = isScreenOn();
            registerReceiver(screenReceiver,
                    new IntentFilter(Intent.ACTION_SCREEN_ON));
            registerReceiver(screenReceiver,
                    new IntentFilter(Intent.ACTION_SCREEN_OFF));
        } catch (Throwable ignored) {
            // 注册失败只会让轮询停不下来，不影响事件通道
        }
        if (screenOn) {
            handler.postDelayed(poll, 1500L);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
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
        if (clipboard != null) {
            try {
                clipboard.removePrimaryClipChangedListener(listener);
            } catch (Throwable ignored) {
                // 已注销
            }
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
            // 未注册或已注销
        }
        stopForeground(true);
        super.onDestroy();
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isScreenOn();
        } catch (Throwable t) {
            return true;
        }
    }

    private Notification buildNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.clip_monitor_running),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, ClipboardActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ClipMonitorService.class).setAction(ACTION_STOP);
        PendingIntent psi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(getString(R.string.clip_monitor_running))
                .setContentText(getString(R.string.clip_monitor_desc))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_MIN);
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.clip_monitor_stop), psi);
        return b.build();
    }
}
