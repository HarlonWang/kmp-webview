# kmp-webview 设备能力支持设计方案

- **日期**：2026-05-22
- **作者**：HarlanWang
- **状态**：Draft（待 spec 审查）
- **基础 spec**：[2026-05-22-kmp-webview-sdk-design.md](./2026-05-22-kmp-webview-sdk-design.md)
- **分支**：`feat/device-capabilities`

## 1. 目标与范围

让承载在 SDK 内的 WebView 能正确响应 H5 端调起本机能力的 API，开箱即用、零业务方接入代码（仅需声明权限）。本期覆盖：

1. **文件选择**：`<input type=file>` 选本地文件/图片，支持 `accept` mime 过滤、`multiple` 多选
2. **拍照**：`<input type=file capture=camera>` 或 `accept=image/*` 直接拉起相机
3. **getUserMedia**：`navigator.mediaDevices.getUserMedia({audio, video})` 摄像头/麦克风

**明确不做（本期外）**：

- Geolocation 定位
- 文件压缩 / 裁剪 / EXIF 处理
- 系统设置引导弹窗（权限被拒后）
- 自定义文件选择器 UI（未来通过新增 `fileChooserHandler` 槽位扩展）

## 2. 平台支持矩阵

| 能力 | Android（minSdk 24） | iOS |
|------|----------------------|-----|
| 文件选择 | ✅ SDK 内置；开关 `allowFileChooser` 生效 | ✅ WKWebView 原生支持，零代码；**开关在 iOS 不生效** |
| 拍照 | ✅ SDK 内置；开关 `allowCameraCapture` 生效 | ✅ WKWebView 原生支持，零代码；**开关在 iOS 不生效** |
| getUserMedia | ✅ SDK 内置（含运行时权限申请） | ✅ **iOS 14.5+**，更低版本不可用；开关两端均生效 |

**iOS 端硬性约束**：

- iOS < 14.5：`getUserMedia` 在 WKWebView 内不支持，SDK 不做 polyfill。文档明示。
- 业务方必须在自己的 `Info.plist` 中添加：
    - `NSCameraUsageDescription`（任何用相机的能力都需要）
    - `NSMicrophoneUsageDescription`（getUserMedia 音频需要）
    - `NSPhotoLibraryUsageDescription`（部分相册访问场景需要）

## 3. 公开 API 变化

`WebViewConfig` 新增三个字段：

```kotlin
data class WebViewConfig(
    // ...既有字段...
    val allowFileChooser: Boolean = true,      // <input type=file>
    val allowCameraCapture: Boolean = true,    // <input capture=camera>
    val allowMediaCapture: Boolean = true,     // navigator.mediaDevices.getUserMedia
)
```

**默认值全部 `true`**。业务方零配置即可拥有所有能力；不需要的能力可单独关闭。

**对 H5 的契约**：

- `allowFileChooser = false`（**仅 Android 生效**）：`onShowFileChooser` 直接 `onReceiveValue(null)` 并返回 `true`，H5 input 不卡死也不弹 picker。iOS 上 WKWebView 标准行为，SDK 不拦截。
- `allowCameraCapture = false`（**仅 Android 生效**）：拍照入口降级到文件选择（accept 仅生效 mime，不再启动相机）。iOS 上同样由 WKWebView 决定。
- `allowMediaCapture = false`（两端均生效）：`getUserMedia()` Promise reject `NotAllowedError`（明确错误，不 hang）。

**为什么 iOS 上前两个开关不生效**：WKWebView 把文件选择/拍照视为标准浏览器行为，没有提供稳定的拒绝 API（`runOpenPanelWithParameters:` 是 macOS 公开 API，iOS 上未公开）。需要在 iOS 上禁用文件选择的业务方，应在 H5 层面控制（不渲染 input）或者不使用本 SDK。

## 4. Android 实现

### 4.1 文件选择 / 拍照

新增 `FileChooserLauncher`，封装两个 `ActivityResultLauncher`：

```kotlin
internal class FileChooserLauncher(
    private val getContent: ActivityResultLauncher<String>,
    private val takePicture: ActivityResultLauncher<Uri>,
    private val context: Context,
    private val config: WebViewConfig,
) {
    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null

    fun launch(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        pendingCallback = callback
        when {
            wantsCamera(params) && config.allowCameraCapture -> launchCamera()
            config.allowFileChooser -> launchPicker(params.acceptTypes.firstOrNull() ?: "*/*")
            else -> callback.onReceiveValue(null)
        }
    }

    fun onPickerResult(uri: Uri?) { pendingCallback?.onReceiveValue(uri?.let { arrayOf(it) }); pendingCallback = null }
    fun onCameraResult(success: Boolean) {
        val uri = pendingCameraUri.takeIf { success }
        pendingCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        pendingCallback = null
        pendingCameraUri = null
    }
}
```

在 `PlatformWebView.android.kt` 的 `@Composable` 内创建：

```kotlin
val launcher = run {
    val context = LocalContext.current
    val pickerLauncher = rememberLauncherForActivityResult(GetContent()) { fileChooserLauncher?.onPickerResult(it) }
    val cameraLauncher = rememberLauncherForActivityResult(TakePicture()) { fileChooserLauncher?.onCameraResult(it) }
    remember { FileChooserLauncher(pickerLauncher, cameraLauncher, context, config) }
}
```

`SdkWebChromeClient` 新增：

```kotlin
override fun onShowFileChooser(
    webView: WebView,
    filePathCallback: ValueCallback<Array<Uri>>,
    fileChooserParams: FileChooserParams,
): Boolean {
    if (!config.allowFileChooser && !config.allowCameraCapture) return false
    fileChooserLauncher.launch(filePathCallback, fileChooserParams)
    return true
}
```

**取消处理**：任何未完成路径都必须调用 `filePathCallback.onReceiveValue(null)`，否则 H5 `<input>` 永久 pending。

### 4.2 拍照路径细节

- 临时文件：`File(context.cacheDir, "kmpwebview/photo_${UUID}.jpg")`
- `Uri`：通过 SDK 内置 FileProvider 获取
- FileProvider 在 SDK manifest 中声明：

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.kmpwebview.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/kmp_webview_file_paths" />
</provider>
```

`res/xml/kmp_webview_file_paths.xml`：

```xml
<paths>
    <cache-path name="kmp_webview_cache" path="kmpwebview/" />
</paths>
```

**业务方零配置**：FileProvider 通过 `${applicationId}.kmpwebview.fileprovider` 拼接，自动隔离不同 host app；manifest merge 后业务方 manifest 不需要写任何东西。

### 4.3 getUserMedia / WebRTC

`SdkWebChromeClient` 新增：

```kotlin
override fun onPermissionRequest(request: PermissionRequest) {
    if (!config.allowMediaCapture) { request.deny(); return }
    mediaPermissionLauncher.handle(request)
}
```

`MediaPermissionLauncher`：

```kotlin
internal class MediaPermissionLauncher(
    private val systemPermissionLauncher: ActivityResultLauncher<Array<String>>,
    private val context: Context,
) {
    private var pendingRequest: PermissionRequest? = null

    fun handle(request: PermissionRequest) {
        val webResources = request.resources  // RESOURCE_VIDEO_CAPTURE / RESOURCE_AUDIO_CAPTURE
        val systemPerms = webResources.mapNotNull { mapToSystemPermission(it) }.toTypedArray()
        val ungranted = systemPerms.filter { ContextCompat.checkSelfPermission(context, it) != GRANTED }
        if (ungranted.isEmpty()) {
            request.grant(webResources)
        } else {
            pendingRequest = request
            systemPermissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    fun onSystemPermissionResult(result: Map<String, Boolean>) {
        val req = pendingRequest ?: return
        pendingRequest = null
        if (result.values.all { it }) req.grant(req.resources) else req.deny()
    }
}
```

`@Composable` 内创建系统权限 launcher：

```kotlin
val sysLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
    mediaPermissionLauncher?.onSystemPermissionResult(it)
}
```

**Manifest 权限声明（业务方自加）**：

```xml
<!-- 业务方 manifest，按需 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

SDK manifest 不声明这两个权限，避免污染未使用此能力的业务方应用的 Play Store 权限描述。

### 4.4 Android Manifest 整体

SDK `library/src/androidMain/AndroidManifest.xml`：

```xml
<manifest>
    <uses-permission android:name="android.permission.INTERNET" />
    <application>
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.kmpwebview.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/kmp_webview_file_paths" />
        </provider>
    </application>
</manifest>
```

## 5. iOS 实现

### 5.1 文件选择 / 拍照

WKWebView 原生支持 `<input type=file>`、`capture=camera`，**SDK 无代码改动**。需要业务方在 `Info.plist` 添加权限说明字符串，否则 iOS 系统会直接拒绝访问相机/相册。

**`allowFileChooser` / `allowCameraCapture` 开关在 iOS 不生效**（见第 3 节契约说明）。

### 5.2 getUserMedia（iOS 14.5+）

新增 `SdkUIDelegate`，实现 `WKUIDelegateProtocol`：

```kotlin
@OptIn(ExperimentalForeignApi::class)
internal class SdkUIDelegate(
    private val config: WebViewConfig,
) : NSObject(), WKUIDelegateProtocol {

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        if (config.allowMediaCapture) {
            decisionHandler(WKPermissionDecision.WKPermissionDecisionGrant)
        } else {
            decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
        }
    }
}
```

`PlatformWebView.ios.kt` 在 `factory` 中 `webView.UIDelegate = coordinator.uiDelegate`。

**版本兼容**：`requestMediaCapturePermissionFor` 在 iOS 14.5 起可用。低于此版本时 selector 不会被调用，`getUserMedia` 直接走 WKWebView 的旧行为（不可用）。SDK 不做版本探测，文档明示。

### 5.3 WKWebViewConfiguration 调整

在 `makeConfig(config)` 中：

```kotlin
val cfg = WKWebViewConfiguration()
cfg.allowsInlineMediaPlayback = true
cfg.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
return cfg
```

让 `<video autoplay>`、getUserMedia 预览流在页面内播放而非全屏。

## 6. 数据流

```
H5 调用（input.click 或 getUserMedia）
  ↓
WKWebView / WebView 触发回调
  ↓
SdkWebChromeClient.onShowFileChooser / onPermissionRequest（Android）
SdkUIDelegate.requestMediaCapturePermission（iOS）
  ↓
config 开关检查 → 不允许直接 deny / null
  ↓ 允许
Android：FileChooserLauncher / MediaPermissionLauncher（可能触发系统权限对话）
iOS：WKWebView + 系统权限对话框（系统自动）
  ↓
用户操作 → 系统结果回调
  ↓
回写 filePathCallback / PermissionRequest / decisionHandler
  ↓
H5 input.files 拿到 File / getUserMedia Promise resolve
```

## 7. 错误处理

| 场景 | 处理 |
|------|------|
| 用户取消选择 | `onReceiveValue(null)`，H5 input 进入未选择状态 |
| 系统权限被拒（首次或永久） | Android `PermissionRequest.deny()`；iOS `decisionHandler(.deny)`；JS Promise reject `NotAllowedError` |
| 拍照临时文件创建失败 | 同"取消"处理（onReceiveValue(null)），并 Log.w；不抛异常 |
| FileProvider 配置错误（业务方覆盖 manifest） | 启动 SDK 时 fail-fast：捕获 `IllegalArgumentException`、记录原因；不退化为崩溃 |
| iOS 低于 14.5 调 getUserMedia | WKWebView 行为不变，JS 收到与浏览器一致的 `NotSupportedError`，SDK 不介入 |
| config 关闭时 H5 调用 | `<input file>` 无响应（Android）/ 不弹（iOS）；getUserMedia reject `NotAllowedError` |

## 8. 测试策略

| 层 | 工具 | 覆盖内容 |
|----|------|----------|
| `commonTest` | `kotlin.test` | `WebViewConfig` 默认值；开关对 H5 契约状态机的影响 |
| `androidUnitTest` | Robolectric + ShadowWebView | `SdkWebChromeClient.onShowFileChooser` 在 config 各组合下的返回值；`MediaPermissionLauncher` 系统权限 grant/deny 路径 |
| `androidInstrumentedTest`（可选） | Espresso + 演示工程加载本地 HTML | 真实 input file picker / getUserMedia 端到端，验证 Promise 状态 |
| iOS 单测 | `iosTest` + 假 `WKUIDelegate` 调用 | `SdkUIDelegate.requestMediaCapturePermission` 在 config 各组合下的 decision |
| 手动验收 | 演示工程 + 真机 | 三类 H5 demo 页面：file input、camera input、getUserMedia |

## 9. 文件清单（变更）

```
library/
├── build.gradle.kts                                   修改：commonMain 依赖加 androidx.activity.compose（拿 launcher）
├── src/androidMain/AndroidManifest.xml                新增：INTERNET + FileProvider
├── src/androidMain/res/xml/kmp_webview_file_paths.xml 新增
├── src/androidMain/kotlin/wang/harlon/webview/platform/
│   ├── PlatformWebView.android.kt                     修改：装配 launcher
│   ├── SdkWebChromeClient.kt                          修改：override 两个回调
│   ├── FileChooserLauncher.kt                         新增
│   └── MediaPermissionLauncher.kt                     新增
├── src/commonMain/kotlin/wang/harlon/webview/core/
│   └── WebViewConfig.kt                               修改：3 个 Boolean
└── src/iosMain/kotlin/wang/harlon/webview/platform/
    ├── PlatformWebView.ios.kt                         修改：装配 uiDelegate + cfg
    ├── WebViewCoordinator.kt                          修改：暴露 uiDelegate
    └── SdkUIDelegate.kt                               新增
```

演示工程 `:androidApp` 改示例 URL 为带文件选择 + getUserMedia 的测试页（任选公开测试页或本地 assets），方便手动验证。

## 10. 文档（README 提示）

发布前在 README 增加"接入注意事项"章节，明示：

- Android：业务方需要在自己的 `AndroidManifest.xml` 声明 `CAMERA` / `RECORD_AUDIO`（按需）
- iOS：业务方需要在 `Info.plist` 配置 `NSCameraUsageDescription` / `NSMicrophoneUsageDescription` / `NSPhotoLibraryUsageDescription`
- iOS 14.5+ 才支持 getUserMedia

## 11. 验收清单

- [ ] `WebViewConfig` 新增三个 Boolean 字段，默认 true，可单独关闭
- [ ] Android：`<input type=file>` 能弹出系统文件选择器，选完文件 H5 拿到 File；点取消 H5 input 不卡死
- [ ] Android：`<input capture=camera>` 能拉起系统相机，拍照后 H5 拿到 jpg
- [ ] Android：`getUserMedia({video:true})` 在未授权时弹系统权限对话，授权后视频流可见
- [ ] Android：`allowFileChooser = false` 时 input 点击无反应；`allowMediaCapture = false` 时 getUserMedia 立即 reject NotAllowedError
- [ ] Android：业务方 manifest 不需要写 FileProvider，即可工作
- [ ] iOS（14.5+）：`getUserMedia` 弹系统权限框，授权后视频流可见；`allowMediaCapture = false` 时立即 deny
- [ ] iOS：`<input type=file>` 弹系统选择器，结果回写 H5
- [ ] 三端测试套件全部通过
