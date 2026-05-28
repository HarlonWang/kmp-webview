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

## JSBridge

`WebViewState` 自带 `jsBridge` 实例，业务方注册 Native handler，JS 端通过 `window.KmpBridge` 调用：

```kotlin
val state = rememberWebViewState("https://example.com")
state.jsBridge.registerHandler("getToken") { paramsJson ->
    """{"token":"${authRepo.fetchToken()}"}"""
}
state.jsBridge.emit("auth.changed", """{"loggedIn":true}""")
state.jsBridge.setAllowedOrigins(setOf("https://example.com", "https://*.example.com"))  // 可选
```

```js
// JS 端
const token = await KmpBridge.call('getToken', { scope: 'user' });
const off = KmpBridge.on('auth.changed', payload => { /* ... */ });
```

handler 是 `suspend (String?) -> String?`，返回 JSON 字符串、抛 `JsBridgeException(code, message)` 透传错误码；不限制 JSON 库。详见 [设计文档](docs/superpowers/specs/2026-05-25-jsbridge-design.md)。

前端 H5 侧的使用说明详见 [docs/frontend-bridge.md](docs/frontend-bridge.md)。

### 自定义命名（可选）

默认 JS 入口 `window.KmpBridge`、就绪事件 `KmpBridgeReady`。多 App 共享 SDK、回避 H5 上已被占用的默认名、对接既有 H5 协议等场景，可在 `rememberWebViewState` 传入自定义 namespace：

```kotlin
val state = rememberWebViewState(
    initialUrl = "https://example.com",
    bridgeNamespace = "CustomBridge",
)
```

```js
const user = await window.CustomBridge.call('getUserInfo', null);
window.addEventListener('CustomBridgeReady', () => { /* bridge 就绪 */ });
```

需是合法 JS 标识符（`[A-Za-z_$][A-Za-z0-9_$]*`），非法值在构造期抛 `IllegalArgumentException`。Native 消息通道名由此值派生为 `__{首字母小写}Native`（例如 `CustomBridge` → `__customBridgeNative`），暂不开放单独自定义。详见 [设计文档](docs/superpowers/specs/2026-05-25-jsbridge-custom-naming-design.md)。

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

## 日志面板（调试用）

`WebViewConfig.enableLogPanel = true` 时 SDK 注入设备内日志面板：右下角悬浮按钮可拖动，点击展开抽屉，按 source/level 过滤、关键字搜索、一键清空。采集四类来源：

- JS `console.log/info/warn/error`
- JS 未捕获异常 + `unhandledrejection`
- WebView 加载与资源错误（主框架 → Error / 二级资源 → Verbose 默认隐藏）
- JSBridge `call` / `emit` / 异常（含 method、params、result、耗时）

```kotlin
WebViewScreen(
    state = state,
    config = WebViewConfig(enableLogPanel = true),
    onCloseRequest = onClose,
)
```

> 仅供调试，**不要在生产暴露给终端用户**：JSBridge payload 会原样进入面板 detail 字段，可能携带 token 等敏感信息。SDK 不做脱敏，由业务方通过开关默认 `false` 控制启用范围。

关闭路径（默认）下：不向 WebView 注入 shim、不注册 `__kmpLog` channel、不重写 H5 侧 `console.*` / 不监听 `onerror` / `unhandledrejection`、所有采集挂钩 `logStore?.append(...)` 短路。详见 [设计文档](docs/superpowers/specs/2026-05-28-webview-log-panel-design.md)。

## 当前限制

`<video>` 自动播放在两端默认开启（支撑 `getUserMedia` 视频预览），暂未暴露开关；如需要禁用请提 issue。
