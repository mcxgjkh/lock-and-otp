package com.mcxgjkh.lockscreen;

import android.app.admin.DeviceAdminReceiver;

/**
 * 仅用于 Root 不可用时的 lockNow() 降级。device_admin.xml 里只声明 force-lock，
 * 因此本应用无法擦除数据、无法改密码、无法锁屏密码策略。
 */
public class LockAdminReceiver extends DeviceAdminReceiver {
}
