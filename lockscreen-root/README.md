# 锁屏 · lockscreen-root

Root 关屏 + 密码/图案解锁的锁屏工具，附带**无障碍服务管理**与**系统级悬浮球**。
不使用无障碍服务模拟点击，默认零后台常驻。

- 应用名：锁屏
- 包名：`com.mcxgjkh.lockscreen`
- 最低版本：Android 4.4（API 19）；targetSdk 28；compileSdk 34
- 依赖：无（纯 Framework API）
- 权限：`DISABLE_KEYGUARD`、`VIBRATE`、`SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`

## 构建

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 新增功能

| 功能 | 入口 | 说明 |
| --- | --- | --- |
| 双密码 | 主界面「时钟密码（第二密码）」 | 主密码进系统；时钟密码只进入**只显示时间**的界面，不解除系统锁屏、不显示悬浮球 |
| 锁屏入口统一 | 悬浮球 / 小组件 / 通知 / 主界面 | 都先拉起锁屏界面再关屏；锁屏期间自动停掉悬浮球，防止被"主页"绕过 |
| 无障碍管理 | 主界面「无障碍服务管理」 | 三路来源枚举（PackageManager / 系统列表 / 全部应用），可直接开关、手动添加组件，适合没有无障碍设置页的手机/手表 |
| 系统级悬浮窗 | 主界面「悬浮球」开关 + 「悬浮球模式」 | 拖动小球，点开**居中面板**：返回 / 主页 / 最近任务 / 通知栏 / 锁屏 / 收起；动作不收起面板，可连点；全能 / 超级省电两档 |
| 系统级超级省电 | 同上，选「超级省电」 | 电池省电 + 断网（Wi-Fi/蓝牙/数据）+ 清后台 + 降亮度 + 关动画；退出/重启后按快照还原 |
| 极速按键 | 主界面「极速按键」开关 | 常驻 root 注入器替代 `input keyevent`，返回/主页毫秒级；失败自动回退 |
| 接管剪贴板 | 主界面「接管系统剪贴板」+「剪贴板管理」+ 悬浮球「粘贴」 | 三层捕获（回调/轮询/Root 通道）、回写系统剪贴板、Ctrl+V 注入，30 条历史 |
| 手表适配 | 主界面「屏幕形状」 | 自动/方形/圆形；极小屏自动隐藏时钟、键盘弹性行高 |

## 代码导航

| 关注点 | 文件 |
| --- | --- |
| Root 会话与命令白名单 | `core/RootShell.java` |
| 关屏/解锁策略与降级 | `core/LockEngine.java` |
| 无障碍枚举与开关 | `core/AccessibilityTool.java` + `core/SecureSettings.java` |
| 悬浮球前台服务 | `service/OverlayService.java` |
| 系统按键动作 | `core/GlobalActions.java` |
| 屏幕形态判定 | `core/WatchUi.java` |
| 锁屏界面与亮灭屏时序 | `ui/LockScreenActivity.java` |
| 双密码与时钟模式 | `ui/LockScreenActivity.java` + `ui/ClockActivity.java` + `core/CredentialStore.java` |
| 系统级省电 | `core/PowerSaver.java` + `core/RootShell.java`（白名单命令） |
| 极速按键 | `core/FastKeys.java` + `shell/Injector.java` |
| 剪贴板接管 | `core/Clips.java` + `core/ClipRoot.java` + `shell/ClipTool.java` + `service/ClipMonitorService.java` + `ui/ClipboardActivity.java` |
| 开机还原 | `receiver/BootReceiver.java` |
| 图案控件（自绘） | `ui/PatternView.java` |

完整功能范围、模块表、后台/电池/Root/加密约束与已知限制，见 [`../README.md`](../README.md) 第二节。
