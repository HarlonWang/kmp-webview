# kmp-webview

> Kotlin Multiplatform 的开箱即用 WebView SDK（Android + iOS）—— 内置 UI 脚手架、JS 桥、文件与相机能力。

[![Maven Central](https://img.shields.io/maven-central/v/wang.harlon/kmp-webview?color=blue&label=Maven%20Central)](https://central.sonatype.com/artifact/wang.harlon/kmp-webview)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-brightgreen)](https://kotlinlang.org/docs/multiplatform.html)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[English](./README.md) | 中文

> ⚠️ Early access —— API 可能调整。最新版本见 [Releases](https://github.com/HarlonWang/kmp-webview/releases)。

## 功能

- 🧩 **开箱即用 UI** —— TopAppBar、底部导航、加载进度条、错误页。
- 🔌 **JSBridge** —— 通过 `window.KmpBridge` 实现 Kotlin 与 JS 双向调用。
- 📷 **设备能力** —— 文件选择、拍照、`getUserMedia`。
- 🐞 **设备内日志面板** —— 采集 console、JS 异常、桥调用（仅调试）。
- 📷 **扫一扫** —— 可选模块，扫码打开网页（Android）。

## 接入

已发布到 Maven Central —— 确认仓库列表含 `mavenCentral()`。

```kotlin
// KMP —— commonMain 一处声明，平台产物由 Gradle 元数据自动解析
commonMain.dependencies {
    implementation("wang.harlon:kmp-webview:0.1.3")
}

// 纯 Android 工程
implementation("wang.harlon:kmp-webview:0.1.3")
```

| 平台 | 要求 |
| --- | --- |
| Android | `minSdk 24` |
| iOS | 通过 KMP 接入（`iosArm64` / `iosSimulatorArm64`）；`getUserMedia` 需 iOS 14.5+ |

> 暂未发布独立 XCFramework，有需要请提 issue。

## 快速上手

```kotlin
@Composable
fun MyScreen(onClose: () -> Unit) {
    val state = rememberWebViewState("https://example.com")
    WebViewScreen(state = state, onCloseRequest = onClose)
}
```

行为通过 [`WebViewConfig`](library/src/commonMain/kotlin/wang/harlon/webview/core/WebViewConfig.kt) 配置：UA 策略、`allow*` 开关、自定义错误页 slot、底部栏 / 进度条显隐。

## JSBridge

在 Kotlin 注册 Native handler，JS 端调用：

```kotlin
val state = rememberWebViewState("https://example.com")
state.jsBridge.registerHandler("getToken") { params -> """{"token":"${authRepo.fetchToken()}"}""" }
state.jsBridge.emit("auth.changed", """{"loggedIn":true}""")
```

```js
const token = await KmpBridge.call('getToken', { scope: 'user' });
const off = KmpBridge.on('auth.changed', payload => { /* ... */ });
```

handler 签名为 `suspend (String?) -> String?`；抛 `JsBridgeException(code, message)` 透传错误码。JS 入口（`window.KmpBridge`）可通过 `rememberWebViewState(bridgeNamespace = "...")` 自定义。

详见 [JSBridge 设计](docs/superpowers/specs/2026-05-25-jsbridge-design.md)、[自定义命名](docs/superpowers/specs/2026-05-25-jsbridge-custom-naming-design.md)、[前端接入说明](docs/frontend-bridge.md)。

## 接入配置

SDK 仅声明 `INTERNET`。相机 / 麦克风权限按需自行声明，避免污染未使用相机的应用上架描述。

**Android** —— 已内置 `FileProvider`（无需配置）。加载 `http://` URL 时自行开启 `usesCleartextTraffic` 或配置 network-security-config。

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

**iOS** —— 按需在 `Info.plist` 添加用途说明：`NSCameraUsageDescription`、`NSMicrophoneUsageDescription`、`NSPhotoLibraryUsageDescription`。`getUserMedia` 仅 iOS 14.5+ 可用（不做 polyfill）。

## 扫一扫（可选模块，仅 Android）

`wang.harlon:kmp-webview-scanner` 是独立模块 —— 核心库**不依赖**它。

```kotlin
implementation("wang.harlon:kmp-webview-scanner:0.1.3")
```

```kotlin
QrScannerScreen(
    onResult = { url -> state.loadUrl(url); showScanner = false },  // 仅回调合法 http/https URL
    onCancel = { showScanner = false },
)
```

基于 CameraX + ZXing（离线、无 Google Play 服务依赖）。仅放行带 host 的 `http`/`https` URL；模块自带 `CAMERA` 权限声明并在进入时申请。详见 [设计文档](docs/superpowers/specs/2026-05-29-qr-scanner-design.md)。

## 日志面板（调试用）

设 `WebViewConfig(enableLogPanel = true)` 注入设备内、可过滤的日志面板，采集 JS `console.*`、未捕获异常、WebView 加载/资源错误、JSBridge 调用。

> **仅供调试，切勿在生产启用。** 桥 payload 会原样进入面板，可能携带 token 等敏感信息，SDK 不做脱敏。默认 `false` 时不注入任何内容。详见 [设计文档](docs/superpowers/specs/2026-05-28-webview-log-panel-design.md)。

## License

[Apache-2.0](LICENSE)
