# 扫一扫加载网页 — 设计文档

- **日期**：2026-05-29
- **状态**：设计已确认，待实现
- **目标**：为 kmp-webview 增加「Native 扫码 → 拿到 URL → 用 SDK 打开网页」的开发能力，作为独立的可选扩展模块。

## 背景与诉求

核心 `library` 当前很克制：仅声明 `INTERNET` 权限，开箱即用地提供 WebView 能力（`WebViewScreen` / `rememberWebViewState` / JSBridge / 设备能力）。

本期要支持「扫一扫加载网页」的典型路径：

> App 内有一个「扫一扫」入口 → 点击弹出全屏扫码界面 → 扫到二维码里的 URL → 用本 SDK 的 `WebViewScreen` 打开该网页。

关键约束（用户确认）：

1. 扫码能力作为 SDK 的**可选扩展模块**，核心 `library` 不依赖它，不污染核心库的依赖与体积。
2. 本期**仅支持 Android**。
3. 扫码界面由 SDK 提供（开箱即用），而非只给接入方一个解码引擎。

## 技术选型（基于实际调研）

二维码解码引擎选用 **ZXing**（`com.google.zxing:core`），相机用 **CameraX**，扫码 UI 用 **Compose** 自研。

选型依据（调研 GitHub 真实数据 + ML Kit vs ZXing 对比）：

- ZXing 离线、无 Google Play 服务依赖、体积轻（`zxing-core` 几百 KB），契合「轻量可选扩展模块」定位；对「近距离主动对准二维码读 URL」这一窄场景识别率完全够用。
- 不直接依赖 `zxing-android-embedded` 这类封装库：它们是 View 体系 + 老 Camera 栈，与本 SDK 的 Compose/Kotlin 现代调性割裂，且引入整套传递依赖。自研 CameraX + `zxing-core` 依赖最干净、UI 风格与 `WebViewScreen` 统一、完全可控。
- 放弃 ML Kit 路线：识别体验虽好，但 bundled 版 +2~3MB、unbundled 版强依赖 GMS，不符合轻量与部署环境诉求。

## 模块结构

- 新增独立 Gradle module `:scanner`，坐标 `wang.harlon:kmp-webview-scanner`。
- **纯 Android library**（`com.android.library` + Compose），不套 KMP `expect/actual` 壳。未来真要扩 iOS 时再升级为 KMP module（YAGNI）。
- 核心 `library` **不依赖** `:scanner`；接入方按需引入。两模块零耦合，仅通过「URL 字符串」衔接 —— `:scanner` 不反向依赖 core。
- 包名 `wang.harlon.webview.scanner`。
- `settings.gradle.kts` 注册新 module。

## 依赖（统一走 version catalog `gradle/libs.versions.toml`）

- `androidx.camera:camera-core / camera-camera2 / camera-lifecycle / camera-view` —— CameraX 相机预览 + 帧分析。
- `com.google.zxing:core` —— 纯解码引擎，无 GMS。
- Compose —— 复用核心库一致的版本条目。
- `androidx.activity` 的 `ActivityResultContracts.RequestPermission` —— 申请相机权限，不额外引入 accompanist。

新增依赖时同步更新 `[versions]` / `[libraries]` / `[plugins]` 三处区块；坐标禁止在 `build.gradle.kts` 硬编码。

## 对外 API

```kotlin
@Composable
fun QrScannerScreen(
    onResult: (url: String) -> Unit,   // 仅在识别到合法 http/https URL 时回调
    onCancel: () -> Unit,              // 用户返回 / 放弃扫码
    modifier: Modifier = Modifier,
)
```

接入方在自己的导航里放置 `QrScannerScreen`，扫到结果回调 `url`，自行决定跳转：

```kotlin
QrScannerScreen(
    onResult = { url -> navigateToWebView(url) },  // 内部 rememberWebViewState(url) + WebViewScreen
    onCancel = { navigateBack() },
)
```

两个模块只通过「URL 字符串」解耦，扫码模块不感知 WebView。

## 内部组件与数据流

```
CameraX Preview ──► ImageAnalysis(YUV 帧)
                         │
                         ▼
                 ZxingAnalyzer（QRCodeReader 解码，失败静默）
                         │ 识别出文本
                         ▼
                 UrlValidator（仅 http/https 通过）
              ┌──────────┴──────────┐
          通过 → onResult(url)   不通过 → 提示「非有效网址」+ 继续扫描
```

组件拆分（各有单一职责、可独立理解与测试）：

- `QrScannerScreen` —— UI 编排：权限状态、相机预览、取景框、手电筒、结果分发。
- `CameraPreview` —— CameraX 的 Compose 封装：绑定生命周期、预览 + ImageAnalysis 用例、手电筒开关。
- `ZxingAnalyzer` —— 实现 `ImageAnalysis.Analyzer`，把相机帧（YUV → `PlanarYUVLuminanceSource`）喂给 ZXing `QRCodeReader` 解码；解不出静默忽略。
- `UrlValidator` —— 纯函数，输入任意字符串，输出是否为合法 http/https URL；不依赖 Android，可单测。

扫描策略：**单次扫描** —— 识别到合法 URL 即回调 `onResult` 并停止分析，避免重复触发。

## UI

- 全屏相机预览 + 半透明遮罩 + 中央方形取景框 + 扫描线动画。
- 顶部返回按钮（触发 `onCancel`）。
- 右上角手电筒开关（弱光场景实用，调用 CameraX `enableTorch`）。
- 整体观感沿用核心库 Compose 风格。

## 权限处理

- 模块自带 `AndroidManifest.xml` 声明 `<uses-permission android:name="android.permission.CAMERA" />` + `<uses-feature android:name="android.hardware.camera" android:required="false" />`，merge 到接入方 app。
  - 与核心库「只声明 INTERNET、相机权限留给业务方按需声明」的策略不同，但合理：引入 `:scanner` 即意味着使用相机，由模块声明更内聚。
- 进入 `QrScannerScreen` 自动申请 CAMERA 权限：
  - 已授权 → 直接预览；
  - 未授权 → 发起申请；
  - 被拒 → 占位 UI（权限说明 + 「去设置」/「重试」按钮）。

## 错误处理

- 解码失败：静默重试，每帧分析，识别不到不报错。
- 相机打不开（被占用 / 无摄像头）：错误占位 UI + 重试入口。
- 非 http/https 结果（纯文本、`tel:`、`javascript:`、`file:` 等）：提示「非有效网址」并继续扫描，**不回调** —— 默认即挡掉危险 scheme，保证回调出去的一定是合法 http(s) URL。

本期不做域名白名单 / 自定义校验器（YAGNI），如后续有安全需求再扩展 `QrScannerScreen` 参数。

## 测试

- `UrlValidator` 纯函数单测：http/https 通过；`javascript:` / `file:` / `tel:` / 纯文本 / 空串 拦截。
- 相机 / CameraX 部分依赖真机，不做单测，靠 sample app 手动验证。
- sample `androidApp` 增加入口演示完整链路「扫码 → 打开 WebView」。

## 文档

- 实现完成后，在 `README.md` 增加「扫一扫加载网页（扩展模块）」小节：模块坐标、`QrScannerScreen` 用法、权限说明、与 `WebViewScreen` 的衔接示例。

## 未来扩展（不在本期范围）

- iOS 支持（升级为 KMP module，AVFoundation 解码）。
- 域名白名单 / 自定义结果校验器。
- 多码制（条形码等）/ 连续扫描模式。
- ActivityResultContract 形态，方便非 Compose 接入方。
