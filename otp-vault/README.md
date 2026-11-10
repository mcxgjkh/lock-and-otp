# authenticator · otp-vault

TOTP / HOTP 两步验证器，支持查看原始密钥与一键导出全部密钥。
无任何权限、无网络、无后台服务，已适配方屏/圆屏/极小屏手表。

- 应用名：authenticator
- 包名：`com.mcxgjkh.otpvault`
- 最低版本：Android 4.4（API 19）；targetSdk 28；compileSdk 34
- 依赖：无（自写 Base32 / HMAC-OTP / otpauth 解析）
- 权限：**0 个**（没有 INTERNET，也没有存储/相机权限）

## 构建

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 判断未知算法

密钥（Base32）本身不含算法信息，6~8 位截断也看不出用的是哪种 HMAC。判断方式：

1. 详情页顶部会显示 `类型 · 算法 · 位数 · 周期`（来自导入的 otpauth 链接，缺省为 SHA1 / 6 位 / 30s）；
2. 点「试算三种算法」，同一时间步同时给出 SHA1 / SHA256 / SHA512 三行验证码，
   与服务端或已验证正常的验证器比对，哪行一致就把算法改成哪个（编辑页下拉切换）；
3. 绝大多数服务是 SHA1；企业自建（如部分 SSO、银行内网）才常见 SHA256/SHA512。

## 手表适配

- 默认 dimens 即手表档，`values-w320dp` 为手机档；
- 账户行两套布局：`layout/item_account.xml`（纵向，手表）/ `layout-w320dp/item_account.xml`（横向，手机）；
- 极小屏自动显示「添加/导入/导出/设置」按钮条（手表没有菜单键）；
- 设置里可强制“屏幕形状：自动/方形/圆形”；圆屏加 5.5% 屏宽安全内边距；
- 无文件选择器的手表，导出自动降级为复制到剪贴板。

## 代码导航

| 关注点 | 文件 |
| --- | --- |
| HOTP/TOTP 算法 | `otp/Otp.java` |
| Base32 编解码 | `otp/Base32.java` |
| otpauth 解析/生成 | `otp/OtpauthUri.java` |
| 加密落盘（AES+HMAC / Keystore 包裹） | `data/VaultCrypto.java`、`data/VaultStore.java` |
| 导出（单个 JSON 文件） | `util/ExportHelper.java` |
| 导入（JSON / otpauth） | `util/ImportHelper.java` |
| 屏幕形态 | `util/WatchUi.java` |
| 列表刷新策略 | `ui/AccountAdapter.java`、`ui/MainActivity.java` |

完整功能范围、模块表、后台/电池/加密/导出约束与已知限制，见 [`../README.md`](../README.md) 第三节。
