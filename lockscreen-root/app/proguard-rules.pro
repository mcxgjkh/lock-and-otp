# 反射调用的系统类不裁剪
-keep class com.mcxgjkh.lockscreen.core.RootShell { *; }
-keep class com.mcxgjkh.lockscreen.LockAdminReceiver { *; }
-keep class com.mcxgjkh.lockscreen.widget.** { *; }
-keep class com.mcxgjkh.lockscreen.receiver.** { *; }

# 保持 Activity/Receiver 构造器（部分 ROM 通过反射实例化）
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.appwidget.AppWidgetProvider

# app_process 反射调用的入口类：名字不能混淆，main 不能删
-keep class com.mcxgjkh.lockscreen.shell.Injector {
    public static void main(java.lang.String[]);
}

# 剪贴板 root 通道入口：由 app_process 反射调用
-keep class com.mcxgjkh.lockscreen.shell.ClipTool {
    public static void main(java.lang.String[]);
}
