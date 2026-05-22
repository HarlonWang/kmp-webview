# kmp-webview

> Kotlin Multiplatform WebView SDK，Android + iOS。开箱即用：TopAppBar、底部导航、加载条、错误页，以及文件选择、拍照、`getUserMedia` 设备能力。

- **状态**：early access `0.1.0`，API 可能调整
- **坐标**：`wang.harlon:kmp-webview:0.1.0`
- **最低**：Android `minSdk 24`、iOS 14.5+（仅 `getUserMedia`）
- **License**：Apache-2.0

## 使用

```kotlin
@Composable
fun MyScreen(onClose: () -> Unit) {
    val state = rememberWebViewState("https://example.com")
    WebViewScreen(state = state, onCloseRequest = onClose)
}
```

可配置项见 [`WebViewConfig`](library/src/commonMain/kotlin/wang/harlon/webview/core/WebViewConfig.kt)：UA 策略、`allow*` 开关、错误页 slot、底部栏/进度条显隐。

## 接入注意

SDK 仅声明 `INTERNET`；相机/麦克风权限按需由业务方声明，避免污染未使用相机能力的应用上架权限描述。

### Android

```xml
<!-- 拍照 / getUserMedia 视频 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<!-- getUserMedia 音频 -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- `FileProvider` 已内置（authority `${applicationId}.kmpwebview.fileprovider`），业务方无需配置。
- 需要加载 `http://` URL 时自行配置 `android:usesCleartextTraffic="true"`，或在 [`networkSecurityConfig`](https://developer.android.com/training/articles/security-config) 里按域名白名单放行。

### iOS

`Info.plist` 按需添加用途说明字符串：

- `NSCameraUsageDescription`
- `NSMicrophoneUsageDescription`
- `NSPhotoLibraryUsageDescription`

`getUserMedia` 仅 iOS 14.5+ 可用，更低版本会抛 `NotSupportedError`，SDK 不做 polyfill。

## 当前限制

`<video>` 自动播放在两端默认开启（支撑 `getUserMedia` 视频预览），暂未暴露开关；如需要禁用请提 issue。
