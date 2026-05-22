# kmp-webview

Kotlin Multiplatform WebView SDK，覆盖 Android 与 iOS。开箱即用，自带顶部 `TopAppBar`、底部导航栏（后退 / 前进 / 刷新-停止）、加载状态条、错误页，以及 `<input type=file>` 文件选择、拍照、`navigator.mediaDevices.getUserMedia` 摄像头/麦克风等设备能力。

> 状态：early access (`0.1.0`)。API 可能在 `1.0` 之前调整。

- groupId：`wang.harlon`
- artifactId：`kmp-webview`
- 最低支持：Android `minSdk 24`、iOS 14.5+（仅 `getUserMedia` 需要）
- License：Apache-2.0

## 快速接入（Compose Multiplatform）

```kotlin
@Composable
fun MyWebViewScreen(onClose: () -> Unit) {
    val state = rememberWebViewState("https://example.com")
    WebViewScreen(state = state, onCloseRequest = onClose)
}
```

通过 `WebViewConfig` 调整行为：

```kotlin
WebViewScreen(
    state = state,
    config = WebViewConfig(
        titleOverride = null,                       // 跟随 document.title
        userAgent = UserAgentStrategy.Append(" MyApp/1.0"),
        showBottomBar = true,
        showProgressBar = true,
        allowFileChooser = true,
        allowCameraCapture = true,
        allowMediaCapture = true,
    ),
    onCloseRequest = onClose,
)
```

## 接入注意事项

SDK 出于权限污染防御的考虑，只声明最小必要权限。需要更多能力时，请在业务方工程里按需补充：

### Android

1. **运行时权限**：如果业务方启用了相机 / 麦克风相关能力，需要在自己的 `AndroidManifest.xml` 中按需声明：

   ```xml
   <!-- 拍照（capture=camera）+ getUserMedia 视频 -->
   <uses-permission android:name="android.permission.CAMERA" />
   <uses-feature android:name="android.hardware.camera" android:required="false" />

   <!-- getUserMedia 音频 -->
   <uses-permission android:name="android.permission.RECORD_AUDIO" />
   ```

   未声明时拍照路径会降级到普通文件选择；运行时授权由 SDK 自动处理。

2. **cleartext HTTP**：Android 9 (API 28) 起默认禁止 `http://` 明文流量，WebView 加载会报 `net::ERR_CLEARTEXT_NOT_PERMITTED`。如果业务方确实需要走 `http://`（例如内网联调地址），可二选一：

   - 粗放开关，所有域名都放行（适合调试）：

     ```xml
     <application android:usesCleartextTraffic="true">
     ```

   - 按域名白名单放行（推荐生产）：在 `res/xml/network_security_config.xml` 中：

     ```xml
     <network-security-config>
         <domain-config cleartextTrafficPermitted="true">
             <domain includeSubdomains="true">your-internal.example.com</domain>
         </domain-config>
     </network-security-config>
     ```

     `AndroidManifest.xml` 引用：

     ```xml
     <application android:networkSecurityConfig="@xml/network_security_config">
     ```

3. **FileProvider**：SDK 已内置 `FileProvider`（`authorities = ${applicationId}.kmpwebview.fileprovider`），业务方无需自配。

### iOS

1. **Info.plist 权限说明**：以下字段按需添加，缺失时 iOS 会直接拒绝调起对应硬件：

   - `NSCameraUsageDescription`：访问相机（拍照、`getUserMedia` 视频）
   - `NSMicrophoneUsageDescription`：访问麦克风（`getUserMedia` 音频）
   - `NSPhotoLibraryUsageDescription`：访问相册（部分 `<input type=file>` 场景）

2. **`getUserMedia` 仅 iOS 14.5+**：低于此版本 WKWebView 不支持，`getUserMedia()` 会抛 `NotSupportedError`，SDK 不做 polyfill。

## 当前限制

- **`getUserMedia` 自动播放**：为了让视频流在 `<video>` 标签里直接预览，Android `mediaPlaybackRequiresUserGesture = false`、iOS `mediaTypesRequiringUserActionForPlayback = none` 当前**默认开启**，未提供开关。若有禁用诉求，可提 issue。

## License

```
Copyright 2026 HarlanWang

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```
