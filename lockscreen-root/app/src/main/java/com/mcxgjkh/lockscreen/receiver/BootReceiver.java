package com.mcxgjkh.lockscreen.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mcxgjkh.lockscreen.core.PowerSaver;
import com.mcxgjkh.lockscreen.core.Prefs;
import com.mcxgjkh.lockscreen.service.ClipMonitorService;

/**
 * 开机还原：超级省电是系统级设置（关 Wi-Fi/蓝牙/数据、降亮度、关动画），
 * 若不还原，重启后设备会一直处于断网、暗屏状态。
 *
 * 约束：
 * · 只在 `ecoApplied == true` 时干活，平时收到广播立刻返回，不启服务、不占内存；
 * · 开机时 su 可能还没就绪，失败会重试（最多 3 次，间隔 1.5s）；
 * · 顺带清掉 clockMode：重启后不可能还停留在时钟界面，留着会永久压制悬浮球。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Prefs prefs = Prefs.get(app);
        if (prefs.clockMode()) {
            prefs.setClockMode(false);
        }
        if (prefs.clipMonitor()) {
            ClipMonitorService.start(app);
        }
        if (!prefs.ecoApplied()) {
            return;
        }
        final BroadcastReceiver.PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = false;
                    for (int i = 0; i < 2 && !ok; i++) {
                        ok = PowerSaver.disable(app, true);
                        if (!ok) {
                            try {
                                Thread.sleep(1000L);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    if (ok) {
                        // 回到全能模式，避免下次开机又按省电样式启动悬浮球
                        prefs.setOverlayMode(0);
                    }
                } finally {
                    pending.finish();
                }
            }
        }, "boot-eco-restore").start();
    }
}
