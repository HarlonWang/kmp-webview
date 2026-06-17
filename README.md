# kmp-webview

> A batteries-included WebView SDK for Kotlin Multiplatform (Android + iOS) — UI scaffolding, a JS bridge, and file/camera support, all out of the box.

[![Maven Central](https://img.shields.io/maven-central/v/wang.harlon/kmp-webview?color=blue&label=Maven%20Central)](https://central.sonatype.com/artifact/wang.harlon/kmp-webview)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-brightgreen)](https://kotlinlang.org/docs/multiplatform.html)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

English | [中文](./README_ZH.md)

> ⚠️ Early access — the API may still change. See [Releases](https://github.com/HarlonWang/kmp-webview/releases).

## Features

- 🧩 **Drop-in UI** — TopAppBar, bottom navigation, progress bar, and error page out of the box.
- 🔌 **JSBridge** — two-way calls between Kotlin and JS via `window.KmpBridge`.
- 📷 **Device capabilities** — file picker, photo capture, and `getUserMedia`.
- 🐞 **In-app log panel** — console, JS errors, and bridge traffic (debug only).
- 📷 **QR scanner** — optional module that opens scanned URLs (Android).

## Install

Published on Maven Central — make sure `mavenCentral()` is in your repositories.

```kotlin
// KMP — declare once in commonMain; Gradle metadata resolves the platform artifacts
commonMain.dependencies {
    implementation("wang.harlon:kmp-webview:0.1.3")
}

// Android-only project
implementation("wang.harlon:kmp-webview:0.1.3")
```

| Platform | Requirement |
| --- | --- |
| Android | `minSdk 24` |
| iOS | Integrate via KMP (`iosArm64` / `iosSimulatorArm64`); `getUserMedia` needs iOS 14.5+ |

> No standalone XCFramework yet — open an issue if you need one.

## Quick start

```kotlin
@Composable
fun MyScreen(onClose: () -> Unit) {
    val state = rememberWebViewState("https://example.com")
    WebViewScreen(state = state, onCloseRequest = onClose)
}
```

Behavior is tuned through [`WebViewConfig`](library/src/commonMain/kotlin/wang/harlon/webview/core/WebViewConfig.kt): UA strategy, `allow*` toggles, a custom error-page slot, and bottom-bar / progress-bar visibility.

## JSBridge

Register native handlers in Kotlin and call them from JS:

```kotlin
val state = rememberWebViewState("https://example.com")
state.jsBridge.registerHandler("getToken") { params -> """{"token":"${authRepo.fetchToken()}"}""" }
state.jsBridge.emit("auth.changed", """{"loggedIn":true}""")
```

```js
const token = await KmpBridge.call('getToken', { scope: 'user' });
const off = KmpBridge.on('auth.changed', payload => { /* ... */ });
```

Handlers are `suspend (String?) -> String?`; throw `JsBridgeException(code, message)` to propagate an error code. The JS entry point (`window.KmpBridge`) can be renamed via `rememberWebViewState(bridgeNamespace = "...")`.

See the [JSBridge design](docs/superpowers/specs/2026-05-25-jsbridge-design.md), [custom naming](docs/superpowers/specs/2026-05-25-jsbridge-custom-naming-design.md), and the [frontend guide](docs/frontend-bridge.md).

## Setup

The SDK declares only `INTERNET`. Declare camera/microphone permissions yourself, only when you use those features.

**Android** — `FileProvider` is built in (no setup needed). For `http://` URLs, enable `usesCleartextTraffic` or a network-security-config.

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

**iOS** — add the usage-description keys you need to `Info.plist`: `NSCameraUsageDescription`, `NSMicrophoneUsageDescription`, `NSPhotoLibraryUsageDescription`. `getUserMedia` is iOS 14.5+ only (no polyfill).

## QR scanner (optional, Android)

`wang.harlon:kmp-webview-scanner` is a separate module — the core library does not depend on it.

```kotlin
implementation("wang.harlon:kmp-webview-scanner:0.1.3")
```

```kotlin
QrScannerScreen(
    onResult = { url -> state.loadUrl(url); showScanner = false },  // only valid http/https URLs
    onCancel = { showScanner = false },
)
```

Built on CameraX + ZXing (offline, no Google Play Services). Only `http`/`https` URLs with a host are accepted; the module declares `CAMERA` itself and requests it on entry. See the [design doc](docs/superpowers/specs/2026-05-29-qr-scanner-design.md).

## Log panel (debug)

Set `WebViewConfig(enableLogPanel = true)` to inject an on-device, filterable log panel that captures JS `console.*`, uncaught JS errors, WebView load/resource errors, and JSBridge traffic.

> **Debug only — never enable in production.** Bridge payloads enter the panel verbatim and may carry tokens or other sensitive data. The SDK does not redact them. When `false` (the default), nothing is injected. See the [design doc](docs/superpowers/specs/2026-05-28-webview-log-panel-design.md).

## License

[Apache-2.0](LICENSE)
