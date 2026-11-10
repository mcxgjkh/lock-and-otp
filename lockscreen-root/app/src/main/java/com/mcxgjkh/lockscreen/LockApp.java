package com.mcxgjkh.lockscreen;

import android.app.Application;

import com.mcxgjkh.lockscreen.core.Clips;

/**
 * 进程入口：只在主线程做一次轻量初始化（缓存 ClipboardManager）。
 * 不注册任何监听、不起线程、不占内存 —— 保持"零常驻"的取舍。
 */
public class LockApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Clips.init(this);
    }
}
