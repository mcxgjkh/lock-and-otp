package com.mcxgjkh.lockscreen.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.Prefs;
import com.mcxgjkh.lockscreen.ui.LockScreenActivity;

/**
 * 小组件与通知栏按钮的统一入口。
 * 这里刻意不做任何耗时动作：仅拉起锁屏界面，真正的关屏由界面在前台时执行
 * （Android 10+ 对后台启动 Activity 有限制，先占前台再关屏是唯一稳的顺序）。
 */
public class LockReceiver extends BroadcastReceiver {

    public static final String ACTION_LOCK = "com.mcxgjkh.lockscreen.action.LOCK";
    private static final String TAG = "LockReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_LOCK.equals(intent.getAction())) {
            return;
        }
        if (!Prefs.get(context).hasCredential()) {
            Toast.makeText(context, R.string.need_cred, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(context, LockScreenActivity.class);
        i.putExtra(LockScreenActivity.EXTRA_LOCK_NOW, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "start lock activity failed: " + e.getMessage());
            Toast.makeText(context, R.string.cannot_start, Toast.LENGTH_SHORT).show();
        }
    }
}
