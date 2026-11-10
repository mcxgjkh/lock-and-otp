#!/system/bin/sh
# late_start：确认服务已就位，没有就再拉一次（post-fs-data 那次可能起太早）
MODDIR=${0%/*}
DATA=/data/adb/wbclip
mkdir -p "$DATA"
i=0
while [ $i -lt 20 ]; do
    if service check clipboard 2>/dev/null | grep -q "not found"; then
        CLASSPATH="$MODDIR/clip.jar" app_process /system/bin com.mcxgjkh.clip.ClipDaemon \
            "$DATA/clip.txt" >> "$DATA/daemon.log" 2>&1 &
        sleep 3
    else
        exit 0
    fi
    i=$((i+1))
done
