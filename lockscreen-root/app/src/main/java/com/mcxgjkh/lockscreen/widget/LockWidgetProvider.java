package com.mcxgjkh.lockscreen.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.receiver.LockReceiver;

/**
 * 1x1 桌面小组件：点击 → 广播 → 锁屏界面。
 * updatePeriodMillis = 0，系统不会为它唤醒进程，桌面刷新本身也不启动本应用进程。
 */
public class LockWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context));
        }
    }

    private RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_lock);
        Intent intent = new Intent(context, LockReceiver.class);
        intent.setAction(LockReceiver.ACTION_LOCK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        return views;
    }
}
