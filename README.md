# 两个独立 Android 应用（minSdk 19 / Android 4.4）

两个工程彼此完全独立：独立 Gradle 工程、独立 applicationId、独立数据目录、零共享代码，
可分别打包、分别安装、分别卸载，互不影响。均已适配**方屏 / 圆屏 / 极小屏（手表）**。

| 工程 | 应用名 | applicationId | 权限 |
| --- | --- | --- | --- |
| `lockscreen-root/` | **锁屏** | `com.mcxgjkh.lockscreen` | DISABLE_KEYGUARD / VIBRATE / SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE |
| `otp-vault/` | **authenticator** | `com.mcxgjkh.otpvault` | **无任何权限**（含 INTERNET 也没有） |

技术基线：`minSdk 19`、`targetSdk 28`、`compileSdk 34`、Java 8、**零第三方依赖**。
构建链：Gradle 8.5 + AGP 8.3.2 + JDK 17（`compileSdk 34` / `buildTools 36.0.0`）。

---

## 一、构建与安装

```bash
# 方式 A：Android Studio
#   File → Open → 分别打开 lockscreen-root / otp-vault（两个独立窗口），Sync 后 Run

# 方式 B：命令行（本机已装 SDK 与 JDK 17）
cd lockscreen-root      # 或 otp-vault
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties` 里的 `sdk.dir` 指向本机 SDK（`F:/AndroidSDK`），换机器时改成自己的路径。
- `gradle.properties` 用 `org.gradle.java.home` 固定了 JDK 17；JDK 路径不同请改这一行。
- 打包产物（已实测生成）：
  - `lockscreen-root/app/build/outputs/apk/debug/app-debug.apk`（191 KB）
  - `otp-vault/app/build/outputs/apk/debug/app-debug.apk`（84 KB）
- 仓库内 `dist/` 已放这两份调试版 APK（`lockscreen-debug.apk`、`authenticator-debug.apk`，v1.1.0），
  不装 SDK 也能直接下载安装；升级用 `adb install -r`（同签名，数据保留）。
- 两者均为调试签名，可直接 `adb install`；上架需自行配置 release 签名。
- `targetSdk 28` 是刻意的：保留 Android 4.4 时代的运行时语义。lint 会提示
  `ExpiredTargetSdkVersion`（Google Play 要求 33+），这是策略提示而非代码问题。

---

## 二、应用一：锁屏（`lockscreen-root`）

### 2.1 核心功能范围

| 能力 | 说明 |
| --- | --- |
| 关屏锁屏 | **先拉起锁屏界面、再由界面关屏**（无 keyguard 的设备也不会"只是黑屏"）；Root 不可用时降级 `lockNow()` |
| 密码解锁 | 4–12 位数字，自定义键盘（圆角矩形按键，行高不足时也不会被压成扁椭圆），输入满位自动校验 |
| 图案解锁 | 3×3 九点、≥4 点，自绘 `PatternView`，错误态高亮 |
| **双密码** | 主密码 → 正常解锁进系统；时钟密码 → **只显示时间**（不解除系统锁屏、不显示悬浮球、不进主界面），长按时间可回到解锁页 |
| **无障碍管理** | 列出本机所有无障碍服务并直接开关，**不需要系统设置页**；可切「全部应用的服务」排查被系统漏掉的服务，也可手动输入组件 |
| **系统级悬浮窗** | 可拖拽悬浮球 + **居中独立面板**：返回 / 主页 / 最近任务 / 通知栏 / 锁屏 / 收起；按钮固定高度、点动作不收起面板；松手吸附边缘；全能 / 超级省电两档 |
| **系统级超级省电** | 一键：电池省电模式 + 关 Wi-Fi/蓝牙/移动数据 + `am kill-all` 清后台 + 降亮度 + 关动画；退出按快照还原，**重启后自动还原** |
| **极速按键** | 常驻 root 注入器（`app_process` + `InputManager.injectInputEvent`）替代 `input keyevent`，返回/主页从 0.5–1.5s 降到毫秒级；不支持时自动回退 |
| **接管剪贴板** | 三层捕获（事件回调 + 亮屏轮询 + Root 通道）收进**所有应用**的复制内容；打开剪贴板页会主动同步一次；悬浮球「粘贴」用 Ctrl+V 注入 |
| **诊断报告** | 一键把 Root（逐条 su 路径与输出）、剪贴板服务/写入读回、ClipTool 原始返回、监控计数、无障碍统计打成文本，可存 `/sdcard/Android/data/.../diag.txt` |
| 触发入口 | 桌面 1×1 小组件、应用内“立即锁屏”、可选通知栏按钮 |
| 防暴力 | 5 次失败后指数退避（30s→30min），计数落盘不可强杀重置 |
| 防绕过 | 屏蔽返回/菜单键、`FLAG_SECURE` 防截屏、严格模式切走自动重拉 |
| 无无障碍依赖 | 全工程不声明、不实现 `AccessibilityService`；悬浮球的系统按键走 Root，不走无障碍 |
| 手表适配 | 方屏/圆屏/极小屏；圆屏自动加安全内边距；时钟与提示在极小屏自动隐藏 |

### 2.2 模块划分

```
app/src/main/java/com/mcxgjkh/lockscreen/
├── core/
│   ├── RootShell.java          常驻 su 会话 + 命令白名单（含按键/安全设置/手势的受限参数入口）
│   ├── LockEngine.java         关屏编排：Root 优先 → DPM 降级 → 800ms 结果校验
│   ├── CredentialStore.java    PBKDF2 哈希、失败计数与指数退避
│   ├── CryptoUtil.java         随机盐、定长比较、Base64
│   ├── Prefs.java              状态存储（凭证、开关、屏幕形状）
│   ├── WatchUi.java            方/圆/极小屏判定、圆屏内边距、屏幕尺寸
│   ├── AccessibilityTool.java  无障碍服务枚举 / 启用状态 / 开关（写 Secure Settings）
│   ├── SecureSettings.java     三级写入通道：直接写 → pm grant 后写 → settings 命令
│   ├── GlobalActions.java      返回/主页/最近任务/通知栏/锁屏/粘贴（极速通道 + input 兜底）
│   ├── FastKeys.java           常驻注入器客户端：握手/超时/自动回退
│   ├── Clips.java              剪贴板核心：当前内容、历史、外部复制入库、注入粘贴
│   ├── ClipRoot.java           Root 剪贴板通道客户端（读/写都走 system 身份）
│   └── NotificationHelper.java 可选通知入口（普通通知，非服务）
├── ui/
│   ├── MainActivity.java       配置台：凭证、Root、权限、悬浮球、屏幕形状
│   ├── SetupActivity.java      两步录入（输入 → 确认）
│   ├── LockScreenActivity.java 锁屏层：亮灭屏时序、双密码校验、极小屏收敛
│   ├── ClockActivity.java      时钟模式：只显示时间，盖在 keyguard 之上
│   ├── PatternView.java        3×3 图案控件（Canvas 自绘）
│   ├── AccessibilityActivity.java  无障碍服务开关页
│   └── ClipboardActivity.java  剪贴板管理页
├── shell/Injector.java         由 root 通过 app_process 启动的按键注入器
├── shell/ClipTool.java         由 root 通过 app_process 启动的剪贴板读写器
├── view/FloatingBallView.java  悬浮球自绘（圆 + 三点）
├── service/OverlayService.java 悬浮球前台服务（可随时关闭）
├── service/ClipMonitorService.java 剪贴板接管服务（监听所有复制，默认开启）
├── receiver/LockReceiver.java  小组件/通知入口
├── widget/LockWidgetProvider.java
└── LockAdminReceiver.java      设备管理器（仅 force-lock）
```

### 2.3 关键实现约束

**后台常驻**
- 默认 0 个服务：锁屏、小组件、通知按钮全部是事件驱动的 Activity/Receiver。
- 新增悬浮球是本应用**唯一**的前台服务，只在打开开关时运行；关闭开关立即 `stopSelf()`，
  通知栏保留“关闭悬浮球”出口。不用悬浮球 = 回到完全零常驻。
- 有一个 `BootReceiver`，但**只在"超级省电仍生效"或"剪贴板接管开启"时才干活**；平时收到
  开机广播立即返回，不启动服务、不占内存。它也会顺手清掉 `clockMode`，避免重启后悬浮球被永久压制。
- **剪贴板接管**是第二个前台服务（可关，通知优先级 MIN）。三条捕获路径：
  1. `OnPrimaryClipChangedListener`，回调里延迟 120ms 读一次（事件驱动，最省）；
  2. 亮屏期间 2.5s 轮询一次，熄屏立即停 —— 兜底那些不投递回调的 ROM；
  3. Root 通道（`app_process` + system 上下文）—— 只在"确实发生了复制、但普通读取为空"时用。
  它必须常驻，否则应用进程被回收后就收不到别的应用的复制事件。

**电池与性能**
- 不申请电池优化白名单，不持有 WakeLock，无轮询、无网络、无心跳。
- 每次锁屏一条 root 命令；悬浮球空闲时不重绘、不刷新、不定位（拖动才 `updateViewLayout`）。
- 悬浮球与面板是两个独立的 `WRAP_CONTENT` 小窗口，无动画、无帧循环；面板按需 add/remove，
  不做 measure 补偿，也不跟着球移动 —— 这样球贴到屏幕底部时面板也不会被裁掉。
- 悬浮球启动即**异步预热 su 会话与按键注入器**，面板展开时再预热一次；锁屏走 `lockScreenFast`
  快路径（只发命令，不做 800ms 熄屏轮询）。
- **极速按键**：`input keyevent` 每次都要新起一个 app_process(JVM)，手表上单次 0.5–1.5s；
  改用常驻注入器后单次只剩毫秒级。注入器握手 5s、单次注入 400ms，任一环节失败立即置位
  `unavailable` 并回退到 `input keyevent`，功能不受影响。OOM/被杀后下次预热重新拉起。

**Root 权限调用方式**
- 常驻 `su` 会话：一次授权多次执行，`echo __WB_RC__$?` 取真实退出码；空闲 60s 自动关闭。
- 命令白名单：关屏 2 条、解锁 2 条、按键（0–300）、通知栏 1 条、`settings put secure` 两个 key、
  `pm grant`（仅本应用包名）。所有“带参数”入口都有正则/范围校验，拒绝换行与 shell 元字符。
- 关屏前先判屏幕状态，灭屏绝不发电源键；API 20+ 用单向 `KEYCODE_SLEEP`，4.4 才用 `KEYCODE_POWER`。
- 悬浮球动作：`input keyevent 4/3/187`（返回/主页/最近任务），通知栏优先
  `cmd statusbar expand-notifications`，老系统退回 `input swipe`（坐标由屏幕尺寸计算）。
- **锁屏只有一条入口**：悬浮球/小组件/通知/主界面都走 `LockScreenActivity`（带 `EXTRA_LOCK_NOW`），
  由界面在前台时关屏。早期悬浮球直接发关屏命令，在"无系统锁屏"的 ROM 上点亮就回桌面，等于没锁。
- 进入锁屏界面即 `stopService(OverlayService)`：悬浮球浮在锁屏之上时，点"主页"可以直接绕过锁；
  主密码解锁后会按开关自动恢复悬浮球。
- 亮屏自愈：`LockScreenActivity` 注册 `ACTION_SCREEN_ON`，若个别 ROM 唤醒直接回桌面，
  150ms 后把锁屏层重新拉到前台；悬浮球也监听 `ACTION_SCREEN_OFF` 自动收起面板。
- **不使用无障碍服务模拟点击**，因此不受“无障碍被禁用 / 无管理器”影响。

**无障碍管理（新增）**
- 数据源三路合并，解决"某个无障碍应用在系统列表里看不见"：
  1. `PackageManager.queryIntentServices(accessibilityservice)` —— 不经过系统无障碍列表过滤；
  2. `AccessibilityManager.getInstalledAccessibilityServiceList()` —— 系统已识别，标记 `recognized`；
  3. 勾选「显示全部应用的服务」时枚举**所有已安装应用的所有服务**，用于极端情况。
- 未声明 `BIND_ACCESSIBILITY_SERVICE` 的组件会标出来并二次确认：系统会拒绝绑定，写入通常不生效。
- 开关后做**双重校验**：读回 `enabled_accessibility_services` 确认写进去了，再用
  `AccessibilityManager.getEnabledAccessibilityServiceList()` 确认系统真的接受了；
  只写进去但系统没绑定（服务未导出 / 未声明权限 / ROM 白名单）时会弹窗说明原因，
  列表里标成「已写入·系统未启用」，不再假装成功。
- 还提供「手动添加组件」，直接输入 `pkg/.Cls`，组件名走字符白名单 + 存在性校验。
- 开关：读写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 与 `ACCESSIBILITY_ENABLED`，
  与系统设置页完全等价；组件统一规范成 `pkg/full.Cls`，避免 `.Service` 简写与全名重复。
- 写入三级通道：已有 `WRITE_SECURE_SETTINGS` → 直接写；否则 `su -c pm grant <本应用>` 后重写；
  仍失败则 `su -c settings put secure ...`。组件名只允许字母/数字/点/下划线，且必须是列表里的组件
  或用户手动输入并通过存在性校验的组件。
- 启用前弹确认框（无障碍可读取屏幕内容），对高风险权限做显式告知。

**双密码（新增）**
- 两份独立的 PBKDF2 记录：主密码 + 时钟密码；设置任一方时清空另一类型的旧哈希，
  且校验时严格按当前 `mode` 判定，**杜绝"切换凭证后旧密码仍能解锁"的绕过**。
- 时钟密码不允许与主密码相同（设置时用同一哈希校验拦掉）。
- 命中时钟密码后：`Prefs.clockMode = true` → 停掉悬浮球服务 → 启动 `ClockActivity` →
  `finish()` 锁屏界面，**全程不调用 `FLAG_DISMISS_KEYGUARD`**，系统锁屏保持有效，
  所以 Home / 最近任务都被 keyguard 挡住，不需要当 Launcher、也不需要无障碍。
- 退出路径：长按时间回到解锁页（同时清除 clockMode），输入主密码正常进系统并恢复悬浮球。
- 悬浮球服务在 `onCreate`/`onStartCommand` 都会检查 `clockMode`，即使被 START_STICKY 拉起也会自我停止。
- 设了时钟密码后关闭"按主密码长度自动提交"（两者长度可能不同），改为按 ✓ 提交。

**数据安全存储**
- 凭证 `v1$迭代$盐$哈希`：PBKDF2WithHmacSHA1、30000 轮、16B 盐、256 位（4.4 无 PBKDF2-SHA256）。
- 失败计数与锁定截止时间落盘；`FLAG_SECURE` 防截屏；`allowBackup="false"`。
- 悬浮球不含任何凭证信息，也不读取屏幕内容（只发按键）。

### 2.4 手表适配（方屏 / 圆屏 / 极小屏）

- 尺寸分级：`values/dimens.xml`（默认＝手表）与 `values-w320dp/dimens.xml`（手机）两档，
  时钟、按键、内边距、悬浮球尺寸全部走 dimen。
- 圆屏：`Configuration.isScreenRound()`（API 23+）→ 反射兜底 → 用户在“屏幕形状”里强制指定；
  命中圆屏时给内容根视图加 5.5% 屏宽的安全内边距。
- 极小屏：短边 < 230dp 时隐藏时钟/日期，< 200dp 再隐藏底部提示；数字键盘改为**弹性行高**
  （4 行 weight 各 1），任何高度都不溢出；图案控件按剩余区域动态求正方形边长。
- 悬浮球在手表上 40dp、面板按钮 44dp（手机 48dp），拖拽后吸附最近边，不会停在圆角外。
- 超级省电模式下悬浮球本身也收敛：球缩到 36dp、只保留返回/主页/锁屏、通知降到 MIN；
  su 会话改为**空闲 25 秒后**才释放（之前"每点一次就关"是点击延迟的主因）。

**系统级超级省电（新增）**
- 入口：主界面「悬浮球模式」→ 超级省电（二次确认，明确列出会关闭什么）。
- 进入时执行（全部经 Root，命令为强类型白名单，只有固定 key 与整数/布尔值）：
  1. `settings put global low_power 1` + `cmd power set-mode 1`（Android 6+）打开系统电池省电；
  2. `svc wifi/bluetooth/data disable` 关闭三张网；
  3. `am kill-all` 结束后台（缓存）进程 —— 前台应用与前台服务不受影响；
  4. 亮度压到 `min(原亮度, 40)` 并关闭自动亮度；
  5. `window/transition/animator` 三个动画倍率归零。
- 进入前先把亮度、亮度模式、Wi-Fi/蓝牙/移动数据的开关状态存入 SharedPreferences；
  退出时逐项还原，**网络只恢复原本就开着的**，不会擅自把用户关掉的 Wi-Fi 打开。
- 退出通道有三条：主界面切回「全能模式」、通知栏「恢复」按钮、或重新打开应用再切回全能。
  从通知栏恢复后会自动以全能模式重建悬浮球，不需要手动重开。
- **重启自动还原**：`BOOT_COMPLETED` 广播里若检测到 `ecoApplied`，会在后台线程调
  `PowerSaver.disable()` 还原亮度/网络/动画，并把悬浮球模式切回全能。
  开机时 su 往往还没就绪，所以最多重试 3 次（间隔 1.5s）；失败则保留标记，下次开机再试。

### 2.5 已知限制

- Root 工具，非系统锁屏替代品；不同 ROM 对 `input keyevent`、`FLAG_SHOW_WHEN_LOCKED` 行为有差异。
- Android 10+ 必须授予“显示在其他应用上层”，否则小组件/通知/悬浮球都无法正常显示。
- 无障碍开关依赖 Root；没有 Root 时只能跳转系统设置页。
- 悬浮球是前台服务，通知栏会常驻一条通知（系统对前台服务的硬性要求，无法隐藏）。
- 未拦截 Home 键，靠“严格模式重拉”兜底；未做开机自启。
- 时钟模式依赖系统 keyguard 遮挡：如果设备**没有设置系统锁屏**，个别 ROM 上划掉 keyguard 后
  按 Home 可能短暂看到桌面，本应用会在 250ms 内把时钟层拉回（严格模式开启时）。
- 设了时钟密码后，解锁需要按 ✓ 确认，不再按长度自动提交（两者长度可能不同）。
- 时钟界面的「长按返回解锁」提示默认**不显示**（开关：主界面「时钟界面显示提示」）；长按时间照样有效。
- **无法真正"劫持" `ClipboardManager`**：别的应用调用的仍是系统剪贴板服务，要重定向必须做
  Xposed/LSPosed 模块改 `system_server`。本应用用的是"监听 + 回写 + Root 兜底"：
  别人复制 → 进本应用；本应用选中/复制 → 写回系统，两个方向的数据保持一致。
- 剪贴板有三种"读到"的路径，任一可用即可工作：前台界面直接读（最可靠）、常驻服务读、
  Root 通道以 system 身份读（`shell/ClipTool`，不受"只允许焦点应用读取"的限制）。
  都失败时界面会提示"系统剪贴板是空的，或当前 ROM 不允许读取"，可点「Root 读取」再试。
- 剪贴板接管依赖前台服务存活：被系统清理（省电策略/手动强行停止）后会漏事件，
  重新打开应用（`onResume` 会主动同步一次）或重启（已开机自启）即可恢复。
- 极速按键依赖 `app_process` + hidden API：个别 ROM 的 SELinux/隐藏 API 策略会导致注入器起不来，
  此时会自动回退到 `input keyevent`（速度回到原样，但功能正常）；logcat 过滤 `FastKeys` 可确认。
- 粘贴到别的应用：纯 ASCII 用 `input text` 直接敲（4.4 起可用）；其它字符先写系统剪贴板再注入
  Ctrl+V（`KEYCODE_V` + `META_CTRL_ON`），失败再退 `KEYCODE_PASTE`（Android 7.0+）。
  两者都失败时提示"系统不支持注入"，此时仍可在本应用内复制、到目标应用里手动长按粘贴。
- Android 10+ 禁止后台读系统剪贴板：本应用的"当前内容"以自身存储为准，系统复制事件的自动收录
  在 10+ 上可能收不到（老手表/4.4–9 正常）。
- 开机还原依赖系统 `BOOT_COMPLETED` 广播：部分深度定制 ROM（或被"自启动管理"拦截时）
  可能收不到，需要在系统里允许本应用自启动；收不到时仍可回应用手动切回全能。
- 开机瞬间 su 可能未就绪，已做 3 次重试；若仍失败，标记会保留，下次开机会继续还原。
- `am kill-all` 是"清一次"后台进程，不是常驻的内存清理服务；之后新启动的后台应用不受影响。

---

## 三、应用二：authenticator（`otp-vault`）

### 3.1 核心功能范围

| 能力 | 说明 |
| --- | --- |
| TOTP | RFC 6238，周期可改；SHA1/SHA256/SHA512；6/8 位 |
| HOTP | RFC 4226，计数器可编辑，用后自动 +1 并落盘 |
| 实时验证码 | 列表显示验证码 + 倒计时，剩余 ≤5s 变橙 |
| 查看原始密钥 | 详情页默认打码，点“显示密钥”才展示；可复制（30s 后自动清空剪贴板） |
| **算法/位数判断** | 详情页显示「类型 · 算法 · 位数 · 周期」；「试算三种算法」同屏给出 SHA1/256/512 的验证码，哪行对得上就是哪个 |
| 一键导出全部 | **始终导出一个 JSON 文件**（含账户名、发行方、密钥、类型、算法、位数、周期、计数器，以及每条的 otpauth 链接）；系统无 DocumentsUI 时自动切**内置文件选择器** |
| 导入 | ① 从 JSON 文件导入（本应用导出格式 / 裸数组 / 单对象 / 混排 URI 均可）② 从 `otpauth://` 链接导入（支持多行批量）；也支持手动录入 |
| 应用锁 | 可选 4–8 位访问 PIN（PBKDF2 哈希） |
| 手表适配 | 方屏/圆屏/极小屏；极小屏底部提供“添加/导入/导出/设置”按钮条（手表没有菜单键） |

### 3.2 模块划分

```
app/src/main/java/com/mcxgjkh/otpvault/
├── otp/            Base32 / Otp(HOTP,TOTP) / OtpauthUri —— 纯 Java，可脱离 Android 单测
├── data/           Account / VaultStore / VaultCrypto / Prefs
├── util/           ExportHelper（JSON/CSV/URI 生成）、ImportHelper（JSON/otpauth 解析）、WatchUi（屏幕形态）
└── ui/             MainActivity / AccountAdapter / DetailActivity / EditActivity / PinActivity / Session
```

### 3.3 关键实现约束

**后台常驻**：无 Service、无 Alarm、无 JobScheduler、无网络；1s ticker 只在 `onResume` 存在，
`onPause` 立即移除；刷新只改可见行的两个控件，不调用 `notifyDataSetChanged()`。

**电池与性能**：每次刷新 1 次 HMAC（微秒级）；无动画、无图片解码、无 WakeLock；
加密只在“启动读一次、增删改各写一次”时发生。

**数据安全**：`vault.dat` = `MAGIC|IV|密文|HMAC`，AES-256-CBC + HMAC-SHA256（先加密后 MAC）；
主密钥 32B 随机数不落盘，由 AndroidKeyStore RSA-2048 包裹（API23+ 用 `KeyGenParameterSpec`，
4.4–5.1 用 `initialize(2048)`），Keystore 不可用时降级 PBKDF2(ANDROID_ID) 并在日志标注；
`fsync + rename` 原子替换；无 INTERNET 权限；`allowBackup="false"`；
导出走 SAF（无文件选择器的手表自动退回剪贴板）；详情/编辑/PIN 页 `FLAG_SECURE`。

### 3.4 手表适配

- `values/dimens.xml`（默认＝手表）与 `values-w320dp/dimens.xml`（手机）两档字号/内边距。
- 账户行两套布局：`layout/item_account.xml` 纵向（手表，验证码独占一行）与
  `layout-w320dp/item_account.xml` 横向两栏（手机），资源系统按可用宽度自动选择。
- 极小屏自动显示底部 4 按钮快捷条（`quick_bar`），因为手表没有菜单键；手机上隐藏，沿用右上角菜单。
- 圆屏：同 App1，5.5% 屏宽安全内边距，可在“设置 → 屏幕形状”强制方形/圆形。
- 导出在无 DocumentsUI 的手表上自动降级为复制到剪贴板；导入在无文件选择器时提示改用 otpauth 链接。
- 导入的 JSON 若为 Google Authenticator 迁移包（`otpauth-migration://`）会明确提示不支持。

### 3.5 已知限制

- 不含扫码（需相机权限与图像库）；用“粘贴 otpauth 链接”替代。
- 时间偏移需手动设置；HOTP 多设备共用会失步；忘记访问 PIN 只能清应用数据后重新导入。
- 手表小屏上编辑表单需滚动，文字输入体验受系统输入法限制。

---

## 四、独立安装与运行

1. **工程独立**：各自 `settings.gradle` / `build.gradle` / `app` 模块，无共享 module。
2. **身份独立**：`com.mcxgjkh.lockscreen` 与 `com.mcxgjkh.otpvault` 互不相关，可同时安装。
3. **数据独立**：两个私有目录互不可见；卸载其一不影响另一个。
4. **权限独立**：锁屏应用的 4 项权限与验证器无关；验证器 0 权限，装上即用。
5. **运行独立**：无相互 Intent、无 ContentProvider、无 AIDL、无共享 SharedPreferences。

---

## 五、已执行的验证

| 检查 | 方法 | 结果 |
| --- | --- | --- |
| 真实构建 | Gradle 8.5 + AGP 8.3.2 + JDK 17 + SDK 34 | 两个 APK **BUILD SUCCESSFUL** |
| APK 元数据 | `aapt2 dump badging` | 包名/应用名/minSdk 19/targetSdk 28/权限 全部符合 |
| APK 体积 | — | 锁屏 191 KB，authenticator 84 KB（v1.1.0）
| 静态检查 | `lintDebug` | 无 NewApi/MissingPermission 等功能性错误；仅 1 条 `ExpiredTargetSdkVersion` 策略提示 |
| HOTP | RFC 4226 附录 D（计数器 0–9） | 10/10 |
| TOTP | RFC 6238 附录 B（SHA1/256/512） | 8/8 |
| Base32 | 编解码往返、空格/短横/小写、非法字符 | 4/4 |
| otpauth | 解析、生成、往返、HOTP counter、非法链接 | 20/20 |
| 倒计时 | 边界、偏移、格式化 | 6/6 |
| XML | 全部资源格式解析 | 0 错误 |

合计 **42 项算法断言全部通过**（`PASS=42 FAIL=0`）。

**仍未执行**：真机/模拟器安装与交互测试（悬浮球拖拽、无障碍开关的实际生效、锁屏在具体 ROM 上的表现）。
本机没有连接设备，也没有可用的 Root 环境，这部分需按第六节清单在目标机型上逐条验证。

---

## 六、自测清单

**锁屏**
1. 设 PIN/图案 → 授权悬浮窗 → 检测 Root → “立即锁屏”应灭屏；亮屏后是本应用锁屏层。
2. 打开“悬浮球”开关：出现小球 + 通知栏常驻通知；拖动应跟手、松手吸附边缘。
3. 点小球展开**居中面板**：返回/主页/任务/通知/锁屏 逐个点，面板应保持展开可连点；
   点「锁屏」应立即出现锁屏界面并随后灭屏；点亮屏幕后必须仍是锁屏界面（不是桌面）；
   解锁后悬浮球应自动恢复。
   按「收起」或再点小球才关闭；把球拖到屏幕最底部再展开，最下方的「收起」也必须完整可点。
3.0 冷启动后第一次点「锁屏」应在一瞬间熄屏（预热生效），不应出现明显等待；
   连续点「返回」应逐次即时响应；`adb logcat -s OverlayAction` 里 cost 应为个位数毫秒。
3.5 「粘贴」：先在剪贴板页放一段英文，点悬浮球「粘贴」应直接敲进当前输入框；
   换成中文时 Android 7.0+ 应触发"粘贴"键，7.0 以下会提示需手动长按粘贴。
3.1 切到“超级省电”：确认后应看到 Wi-Fi/蓝牙/移动数据关闭、屏幕变暗、系统动画消失；
   球变小、面板只剩返回/主页/锁屏，通知栏显示「超级省电中」并带「恢复」按钮。
3.2 点通知栏「恢复」：网络回到进入前的状态、亮度与动画还原，悬浮球恢复全能模式。
3.3 不带 Root 时切省电：应提示“需要 Root”，且模式自动退回全能。
3.4 进入省电后重启手机：开机后 Wi-Fi/亮度/动画应自动回到进入前的状态，悬浮球模式为全能。
4. 「无障碍服务管理」：默认列出声明了无障碍的服务；打开开关后若弹「设置已写入，但系统没有接受」，
   说明该服务本身不满足系统绑定条件（应用问题，不是开关问题），详细原因在弹窗底部。
4.0 「诊断报告」：进去后等 10~20 秒（可能弹 Root 授权），把内容存文件后 `adb pull` 出来；排查剪贴板就靠它。
   找不到某个无障碍应用时，勾选「显示全部应用的服务」——它应出现在列表里（可能标注"未声明无障碍权限"），
   或用「手动添加组件」直接写 `pkg/.Cls`。
4.1 设「时钟密码」（须与主密码不同）→ 锁屏输时钟密码：应只看到大时钟，无悬浮球、无桌面；
   默认不显示任何“长按返回”字样，但长按时间应能回到解锁页；输主密码正常进系统且悬浮球恢复。
   打开「时钟界面显示提示」后，时钟下方才出现淡提示。
4.2 在锁屏界面用旧密码试一次：切过凭证类型（密码↔图案）后，旧密码必须失效。
4.3 「剪贴板管理」：输入文字 → 复制到剪贴板 → 历史出现该条；点历史条目可装载；长按可删。
4.4 接管验证（Android 7.1 实测项）：在别的应用里复制一段文字 → 打开「剪贴板管理」，
   「当前内容」与历史应立刻出现该段文字；状态行显示「接管：已开启 · 已捕获 N 次」。
   若没有，点「Root 读取」——它用 system 身份绕过"只允许焦点应用读取"的限制。
4.5 粘贴验证：在别的应用输入框里点悬浮球「粘贴」→ 英文应逐字打入；中文应通过 Ctrl+V 粘贴。
5. 手表：把“屏幕形状”设为圆形，锁屏九个点应完整落在圆内；极小屏时钟自动隐藏、键盘不溢出。

**authenticator**
1. 粘贴一个真实 `otpauth://totp/...` 导入，验证码应与服务端一致。
2. 详情页“显示密钥”→ 复制 → 30s 后剪贴板自动清空。
3. 导出 → 生成 `otpvault-*.json`，用文本编辑器核对字段；没有 DocumentsUI 的设备会弹出内置选择器，
   文件默认写到 `/sdcard/Android/data/com.mcxgjkh.otpvault/files/`（`adb pull` 或文件管理器可取）。
3.1 导入 → 选「从 JSON 文件导入」挑刚导出的文件，账户应全部回来（重复条目自动跳过）。
3.2 导入 → 选「从 otpauth 链接导入」粘贴一条新链接，应新增 1 条。
4. 手表：列表应为纵向布局（验证码独占一行），底部四个按钮可完成添加/导入/导出/设置。
