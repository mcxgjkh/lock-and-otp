package com.mcxgjkh.otpvault.ui;

/**
 * 进程内会话标记：PIN 验证通过后本次进程内不再重复拦截。
 * 进程被系统回收（内存极低占用策略下很常见）后标记自然丢失，下次启动需重新验证 —— 
 * 这正好是"零后台常驻"带来的安全副作用，不需要额外的超时逻辑。
 */
public final class Session {

    private static boolean unlocked;

    private Session() {
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void unlock() {
        unlocked = true;
    }

    public static void lock() {
        unlocked = false;
    }
}
