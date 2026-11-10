#!/system/bin/sh
# 开机早期就把守护进程拉起来：越早注册，应用越不会缓存到"没有剪贴板"
MODDIR=${0%/*}
DATA=/data/adb/wbclip
mkdir -p "$DATA"
chmod 700 "$DATA"
CLASSPATH="$MODDIR/clip.jar" app_process /system/bin com.mcxgjkh.clip.ClipDaemon \
    "$DATA/clip.txt" >> "$DATA/daemon.log" 2>&1 &
