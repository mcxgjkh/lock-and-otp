# wb-clipboard · Magisk 模块

给**缺少 `ClipboardService` 的 ROM** 补一个剪贴板服务。

## 它做了什么

以 root 启动 `app_process`，向 servicemanager 注册名为 `clipboard` 的 binder，
按 API 25 的 `IClipboard` 事务号实现 7 个方法（set/get/has/描述/监听/hasText）。

**不修改 `/system`**，不 hook、不改 `services.jar`、不动 `system_server`。

## 安全边界

| 情况 | 结果 |
| --- | --- |
| 系统本来就有 clipboard 服务 | 守护进程检测到后直接退出，零影响 |
| 守护进程没起来 / 崩溃 | 回到"没有剪贴板"的原状，不会影响开机 |
| 应用调用时解析异常 | 返回空结果，不抛给调用方，不会导致应用崩溃 |
| 想彻底移除 | 删除 `/data/adb/modules/wbclip` 后重启 |

数据落在 `/data/adb/wbclip/clip.txt`（纯文本，重启保留）。

## 安装

```bash
adb push wb-clipboard /data/local/tmp/
adb shell "su -c 'rm -rf /data/adb/modules/wbclip && cp -r /data/local/tmp/wb-clipboard /data/adb/modules/wbclip && chmod 755 /data/adb/modules/wbclip/*.sh'"
adb reboot
```

验证：

```bash
adb shell "service check clipboard"     # 应输出 found
adb shell "cat /data/adb/wbclip/daemon.log"
```

## 卸载

```bash
adb shell "su -c 'rm -rf /data/adb/modules/wbclip /data/adb/wbclip'"
adb reboot
```
