# FaceBiometricFix

一个面向 ColorOS（OPPO/一加/真我）的 [Xposed](https://github.com/rovo89/XposedBridge) / LSPosed 模块，用于修正系统人脸传感器上报的非标准强度值（`4095`），使其被识别为标准 Class 3（强认证）强度，从而让面容解锁能够正常用于应用的 `BiometricPrompt`。

## 背景

Android中生物识别分为三个安全级，Class1(4095,最弱),Class2(255,弱)，Class3(15,强)

ColorOS 的人脸传感器向 `system_server` 上报 `4095`。因此当普通应用通过 `BiometricPrompt` 请求 Class 3 强认证（如网银、密码管理器）甚至CLass2弱认证时，系统判定人脸强度不足（`STATUS_INSUFFICIENT_STRENGTH`），面容解锁不会被纳入可选认证方式。

## 功能

- 在 `system_server` 中hook `PreAuthInfo` 的资格预检流程，将普通应用请求的 `4095` 人脸强度修改为标准 Class 3 强度（15）。
- 仅影响普通 App 的预检调用链，`SystemUI`、系统锁屏等路径仍保持 ColorOS 原生实现不变。
- 排除 `com.coloros.codebook` 等系统应用，(尽量)避免影响系统自带的认证判定。

## 工作原理



1. **`com.android.server.biometrics.PreAuthInfo#getStatusForBiometricAuthenticator`**
   `system_server` 内部执行强度判定时无法可靠取得原始调用者的 UID，因此利用 `PreAuthInfo` 的参数把调用上下文（`opPackageName`）记录到 `ThreadLocal` 中，供后续判定使用。

2. **`com.android.server.biometrics.Utils#isAtLeastStrength`**
   当检测到处于普通 App 的预检查调用链、传感器强度为 `4095`、且请求的是强认证（`15`）或弱认证（`255`）时，直接返回 `true`；其余所有路径沿用 ColorOS 原生实现。

## 兼容性

- 系统：ColorOS（OPPO / 一加 / 真我）， LSPosed。
- 依赖 Xposed API legacy 及以上。
- 构建环境：AGP 9.3.2、Gradle 9.5.0、compileSdk 37、minSdk 35、targetSdk 37。
- 仅在oneplus ace3v + coloros 16 测试通过

## 构建

```bash
./gradlew :app:assembleRelease
```

或直接使用 Android Studio 打开项目。

生成的 APK 位于 `app/build/outputs/apk/release/`。

## 安装与使用

1. 安装编译出的 APK（可能需关闭系统签名校验或使用支持的方式安装模块 APK）。
2. 在 LSPosed 中启用本模块。
3. 将模块作用域勾选为 **System Framework（系统框架）**。
4. 重启系统。
5. 在任意使用 `BiometricPrompt` 的应用中即可看到面容解锁选项。

## 说明与免责声明

- 本模块通过 Hook 系统进程实现，存在风险，请掌握基本救砖操作；
- **Vibe Coding**而成，能用就行；
- 不同 ColorOS 版本的系统内部实现可能有所差异，如失效可查看 Xposed 日志（Tag：`FaceBiometricFix`）定位原因。
- 本项目仅供学习研究使用，请自行评估风险。

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 开源发布。

## CI 自动构建与发布

项目通过 [GitHub Actions](.github/workflows/build-release.yml) 在推送时自动构建：

- **推送到 `main` 分支**：自动构建 Release APK（以 debug 密钥签名），并作为构建产物（Artifact）上传，可在 Actions 页面下载。
- **推送 `v*` 标签**（如 `v1.0.0`）：自动构建、签名，并创建 GitHub Release 附上 APK。

使用方法：在仓库页面点击 *Create a new release*，输入类似 `v1.0.0` 的标签，即可触发自动构建并生成 Release。
