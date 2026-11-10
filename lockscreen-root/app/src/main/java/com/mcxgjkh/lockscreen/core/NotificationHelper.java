package com.mcxgjkh.lockscreen.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.receiver.LockReceiver;

/**
 * 可选的通知栏快捷锁屏入口。注意：这是一条**普通通知**，不是前台服务，
 * 不需要常驻进程，也不参与任何唤醒；关掉开关即刻移除，零后台开销。
 */
public final class NotificationHelper {

    private static final int ID = 1001;
    private static final String CHANNEL_ID = "lock_shortcut";

    private NotificationHelper() {
    }

    public static void show(Context ctx) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(ctx, LockReceiver.class);
        intent.setAction(LockReceiver.ACTION_LOCK);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        // 使用系统自带的锁图标，不额外引入资源
        b.setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(ctx.getString(R.string.notif_title))
                .setContentText(ctx.getString(R.string.notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_MIN);
        nm.notify(ID, b.build());
    }

    public static void hide(Context ctx) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(ID);
        }
    }
}
