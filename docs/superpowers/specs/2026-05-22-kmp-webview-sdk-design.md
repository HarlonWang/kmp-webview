# kmp-webview SDK 设计方案

- **日期**：2026-05-22
- **作者**：HarlanWang
- **状态**：Draft（待 spec 审查）
- **目标**：开源、面向个人与公司使用的 Kotlin Multiplatform WebView SDK

## 1. 目标与范围

提供一个开箱即用的 KMP WebView 组件，业务方在 Compose Multiplatform 项目中通过一个 `@Composable` 入口即可呈现：

- 顶部 Material3 `TopAppBar`：动态标题、返回/关闭、右侧"更多"菜单
- TopAppBar 下方的加载状态条（indeterminate `LinearProgressIndicator`）
- 中间区域：原生 WebView（Android `WebView` / iOS `WKWebView`）
- 底部导航栏：后退、前进、刷新/停止
- 加载失败的内置错误页（可被业务方完全替换）

**支持平台**：Android、iOS（不支持 Desktop / Web）

**MVP 范围内**：错误页、User-Agent 自定义（追加 / 覆盖）

**本期不做**：JSBridge、文件上传/下载、Cookie/Header 注入、相机权限、原生 Activity / UIViewController 便捷入口

## 2. 工程结构

```
kmp-webview/
├── settings.gradle.kts          # include(":library", ":androidApp", ":iosApp")
├── gradle/libs.versions.toml    # 所有依赖坐标走 version catalog
├── library/
│   ├── build.gradle.kts         # androidLibrary + composeMultiplatform + mavenPublish
│   └── src/
│       ├── commonMain/kotlin/wang/harlon/webview/
│       │   ├── WebViewScreen.kt          # 唯一公开 @Composable 入口
│       │   ├── core/
│       │   │   ├── WebViewState.kt       # rememberWebViewState() + class WebViewState
│       │   │   ├── WebViewConfig.kt      # 配置数据类
│       │   │   ├── LoadingState.kt       # sealed: Idle / Loading / Finished / Error
│       │   │   ├── MenuAction.kt
│       │   │   └── UserAgentStrategy.kt
│       │   ├── platform/
│       │   │   └── PlatformWebView.kt    # expect @Composable
│       │   └── ui/
│       │       ├── WebViewTopBar.kt      # internal
│       │       ├── WebViewBottomBar.kt   # internal
│       │       ├── WebViewProgressBar.kt # internal
│       │       └── WebViewErrorView.kt   # internal
│       ├── androidMain/kotlin/wang/harlon/webview/platform/
│       │   ├── PlatformWebView.android.kt
│       │   ├── SdkWebViewClient.kt
│       │   └── SdkWebChromeClient.kt
│       └── iosMain/kotlin/wang/harlon/webview/platform/
│           ├── PlatformWebView.ios.kt
│           └── SdkNavigationDelegate.kt
├── androidApp/                  # 演示工程
└── iosApp/                      # 演示工程（SwiftUI 或 UIKit 任一）
```

**关键约定**：

- `core/` 公开；`ui/` 与 `platform/` 全部标 `internal`，业务方只能用 `WebViewScreen` + `WebViewConfig` + `rememberWebViewState`
- 所有依赖通过 `gradle/libs.versions.toml` 声明，禁止硬编码坐标字符串
- 输出：Android AAR + iOS XCFramework（静态），通过 vanniktech maven-publish 发布
- 发布坐标：**`wang.harlon:kmp-webview:0.1.0`**
- iOS XCFramework 名：**`KmpWebView`**

## 3. 公开 API

`commonMain` 对业务方暴露的全部公开符号：

```kotlin
@Composable
fun WebViewScreen(
    state: WebViewState,
    config: WebViewConfig = WebViewConfig(),
    modifier: Modifier = Modifier,
    onCloseRequest: () -> Unit,
)

@Composable
fun rememberWebViewState(initialUrl: String): WebViewState

class WebViewState internal constructor(initialUrl: String) {
    val currentUrl: String         // 当前真实 URL
    val title: String              // 跟随 document.title（iOS 仅在 didFinish 时同步）
    val loading: LoadingState
    val canGoBack: Boolean
    val canGoForward: Boolean

    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()
    fun loadUrl(url: String)
}

data class WebViewConfig(
    val titleOverride: String? = null,
    val userAgent: UserAgentStrategy = UserAgentStrategy.Default,
    val showBottomBar: Boolean = true,
    val showProgressBar: Boolean = true,
    val overflowMenu: List<MenuAction> = defaultMenuActions(),
    val errorContent: (@Composable (LoadingState.Error, retry: () -> Unit) -> Unit)? = null,
)

sealed interface UserAgentStrategy {
    object Default : UserAgentStrategy
    data class Append(val suffix: String) : UserAgentStrategy
    data class Override(val value: String) : UserAgentStrategy
}

data class MenuAction(
    val id: String,
    val label: String,
    val onClick: (WebViewState) -> Unit,
)

sealed interface LoadingState {
    object Idle : LoadingState
    object Loading : LoadingState
    object Finished : LoadingState
    data class Error(
        val code: Int,
        val description: String,
        val failingUrl: String?,
    ) : LoadingState
}
```

**设计要点**：

- 业务方只需要 4 个核心类型：`WebViewScreen` / `WebViewState` / `WebViewConfig` / `LoadingState`，加 `UserAgentStrategy`、`MenuAction` 辅助类型
- `onCloseRequest` **必填**：SDK 不假设导航容器，把"何时关闭页面"交回业务方
- `WebViewState` 内部用 Compose `mutableStateOf` 驱动，对外只读；用 `class` 而非 `interface`
- 默认菜单项：刷新、复制链接、在系统浏览器打开（业务方可整体替换或追加）
- 主题跟随调用方的 `MaterialTheme`，SDK 不提供颜色参数

## 4. UI 组件（内部）

`WebViewScreen` 用 `Scaffold` 组装：

```kotlin
Scaffold(
    topBar = {
        Column {
            WebViewTopBar(state, config, onCloseRequest)
            if (config.showProgressBar) WebViewProgressBar(state.loading)
        }
    },
    bottomBar = { if (config.showBottomBar) WebViewBottomBar(state) },
) { padding ->
    Box(Modifier.padding(padding)) {
        PlatformWebView(state, config)
        if (state.loading is LoadingState.Error) {
            val errorView = config.errorContent ?: { e, retry -> DefaultErrorView(e, retry) }
            errorView(state.loading as LoadingState.Error) { state.reload() }
        }
    }
}
```

### 4.1 WebViewTopBar

- Material3 `TopAppBar`
- 标题：`config.titleOverride ?: state.title`，单行省略
- `navigationIcon`：`state.canGoBack` 时显示返回箭头并调 `state.goBack()`；否则显示关闭叉号并调 `onCloseRequest()`
- `actions`：单个"更多"`IconButton`，点开 `DropdownMenu` 渲染 `config.overflowMenu`

### 4.2 WebViewProgressBar

- TopAppBar 下方独立一条 `LinearProgressIndicator`（indeterminate）
- 仅 `LoadingState.Loading` 时显示
- `Finished` / `Error` 后 200ms 渐隐（`animateFloatAsState` 控制 alpha）

### 4.3 WebViewBottomBar

- 容器：`BottomAppBar`，3 个 `IconButton` 均匀分布：
  1. **后退**：`enabled = state.canGoBack`，点击 `state.goBack()`
  2. **前进**：`enabled = state.canGoForward`，点击 `state.goForward()`
  3. **刷新 / 停止**：`Loading` 时显示停止图标→点击 `stopLoading()`；否则显示刷新图标→点击 `reload()`
- 不可用按钮 `enabled = false` 灰显

### 4.4 DefaultErrorView

- 垂直居中：图标 + 错误文案 + "重试"按钮
- 点击重试调用 `state.reload()`
- 遮盖在 WebView 上方（同一个 `Box`）

### 4.5 图标与文案

- 全部使用 `androidx.compose.material.icons.filled` / `outlined`，零图片资源
- 内置中英两份文案，跟随系统 locale；业务方可在外层覆盖（通过 `stringResource`）

## 5. 平台实现（expect / actual）

`commonMain` 只声明：

```kotlin
@Composable
internal expect fun PlatformWebView(
    state: WebViewState,
    config: WebViewConfig,
    modifier: Modifier = Modifier,
)
```

### 5.1 Android（`androidMain`）

基于 `android.webkit.WebView` + Compose `AndroidView`：

```kotlin
@Composable
internal actual fun PlatformWebView(state, config, modifier) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                applyUserAgent(settings, config.userAgent)
                webViewClient = SdkWebViewClient(state)
                webChromeClient = SdkWebChromeClient(state)
                state.bind(this)
            }
        },
        update = { /* URL 变化时 loadUrl */ },
        onRelease = { it.destroy(); state.unbind() },
        modifier = modifier,
    )
}
```

- `SdkWebViewClient`：覆写 `onPageStarted` / `onPageFinished` / `onReceivedError` / `onReceivedSslError`（默认 `cancel()`）/ `doUpdateVisitedHistory`，把状态写回 `WebViewState`
- `SdkWebChromeClient`：覆写 `onReceivedTitle`
- `state.bind(webView)`：内部持有 `WebView?` 引用，`goBack/forward/reload/stop/loadUrl` 转发；`onRelease` 中解绑避免泄漏
- 不引入 `MutableContextWrapper`，靠 `onRelease` + `destroy()` 控制生命周期
- 不读取 `onProgressChanged`（与 iOS 行为对称）

### 5.2 iOS（`iosMain`）

基于 `WKWebView` + Compose `UIKitView`：

```kotlin
@Composable
internal actual fun PlatformWebView(state, config, modifier) {
    val coordinator = remember { WebViewCoordinator(state) }
    UIKitView(
        factory = {
            val webView = WKWebView(frame = CGRectZero.readValue(), configuration = makeConfig(config))
            webView.navigationDelegate = coordinator.delegate
            state.bind(webView)
            webView
        },
        onRelease = { coordinator.dispose(); state.unbind() },
        modifier = modifier,
    )
}
```

- `SdkNavigationDelegate`：`NSObject` 子类实现 `WKNavigationDelegateProtocol`
    - `didStartProvisionalNavigation` → `state.onLoadStarted()` + 同步 `canGoBack/Forward`
    - `didFinishNavigation` → `state.onLoadFinished()` + 读 `webView.title` + 同步 `canGoBack/Forward`
    - `didFailNavigation` / `didFailProvisionalNavigation` → `state.onLoadFailed(NSError → Error)`
    - `webViewWebContentProcessDidTerminate` → `Error(-1, "process_terminated", null)`
- **不使用 KVO**：title / canGoBack / canGoForward 都在导航事件边界同步刷新
- User-Agent：`Override` 直接设 `webView.customUserAgent`；`Append` 先 `evaluateJavaScript("navigator.userAgent")` 拼接后设回；`Default` 不动
- `dispose()` 清空 delegate 引用

### 5.3 状态机契约

不论 Android 还是 iOS，平台层职责只有一个：**把原生 WebView 回调翻译成对 `WebViewState` 内部 `mutableStateOf` 的写入**。`WebViewState` 是真源（single source of truth），UI 层只读取它。

## 6. 数据流

```
原生 WebView 回调（主线程）
   ↓
WebViewState（mutableStateOf 集合）
   ↓ Compose 订阅 snapshot
WebViewTopBar / WebViewBottomBar / DefaultErrorView
   ↓ 用户点击
WebViewState.goBack / reload / loadUrl / ...
   ↓ 转发到绑定的原生 WebView 实例
原生 WebView 执行动作
```

- 业务方通过 `state.loadUrl()` 等指令式方法主动控制，不持有原生 WebView 句柄
- `state.bind(webView)` 在 `AndroidView.factory` / `UIKitView.factory` 中完成，`onRelease` 中解绑
- 同一个 `WebViewState` 在 Composition 生命周期内保持不变，绑定 / 解绑只与 `factory` / `onRelease` 配对

## 7. 错误处理

| 来源 | 处理 |
|------|------|
| 网络 / 页面加载失败 | `LoadingState.Error(code, description, failingUrl)`，UI 覆盖 `DefaultErrorView`（或业务方 `errorContent`） |
| SSL 错误 | Android `onReceivedSslError` 默认 `cancel()` → 落 `Error`；不内置"信任所有证书"开关 |
| 操作时 `webView == null`（未绑定 / 已 release） | `goBack` / `reload` 等静默 no-op，不抛异常 |
| iOS WKWebView 进程崩溃 | `webViewWebContentProcessDidTerminate` → `Error(-1, "process_terminated", null)` |
| 业务方传入非法 URL | `loadUrl()` 最小校验（非空 + scheme 合法），不合法静默忽略并 `Logger.w` |

不内置全局错误上报。业务方需要监控可在外层 `LaunchedEffect(state.loading)` 中观察 `LoadingState.Error`。

## 8. 测试策略

| 层 | 工具 | 覆盖内容 |
|----|------|----------|
| `commonTest` | `kotlin.test` | `WebViewState` 状态机：bind/unbind、按钮 enabled 计算、loadUrl 校验、Error 状态转换 |
| `commonTest`（UI 单测） | `compose-multiplatform-uitest`（`runComposeUiTest`） | `WebViewTopBar` / `WebViewBottomBar` / `DefaultErrorView` 在不同 state 下的渲染：返回按钮态、菜单展开、加载条显隐 |
| `androidUnitTest` | Robolectric | `SdkWebViewClient` 回调 → state 写入正确 |
| `androidInstrumentedTest`（可选 / 后续） | Espresso + 演示工程 | 真机集成：加载本地 HTML、点击底部后退/前进 |
| iOS 单测 | KMP `iosTest` + 假 `WKNavigationDelegate` 调用 | navigation delegate 回调 → state 写入。WKWebView 本体不在单测里跑，由演示工程手动验收 |

**CI 流水线**：`commonTest` + `androidUnitTest` + `iosSimulatorArm64Test`。Instrumented test 不进默认 CI。

## 9. 依赖与版本（version catalog 计划）

`gradle/libs.versions.toml` 至少需要：

- `[versions]`：`kotlin`、`agp`、`composeMultiplatform`、`androidx-webkit`（如启用 androidx WebView 兼容包则添加）、`android-compileSdk`、`android-minSdk`、`android-targetSdk`、`vanniktech-mavenPublish`
- `[libraries]`：`compose.runtime / foundation / material3 / ui / animation / components.resources`、`kotlin-test`、（可选）`androidx-webkit`
- `[plugins]`：`kotlinMultiplatform`、`android-kotlin-multiplatform-library`、`kotlinCompose`、`composeMultiplatform`、`vanniktech-mavenPublish`

`commonMain.dependencies` 与 payment-sdk 一致，仅引入 Compose Multiplatform 自身依赖。

## 10. 后续路线（不在本期）

- JSBridge（双向通信）
- 文件上传 / 下载 + 相机相册权限
- Cookie / Header 注入 API
- 持久化历史 / 多 Tab
- Desktop 与 Web 目标支持

## 11. 验收清单

- [ ] `commonMain` 公开 API 与本文档第 3 节一致
- [ ] Android `:androidApp` 演示工程能加载 https URL，TopAppBar 标题随页面变化，底部后退/前进/刷新按钮可用
- [ ] iOS `:iosApp` 演示工程能加载 https URL，标题在加载完成后刷新，底部按钮可用
- [ ] 加载失败时显示 `DefaultErrorView`，点击重试可恢复
- [ ] User-Agent `Append` 在两端均生效
- [ ] 切换 `MaterialTheme` 时 TopAppBar / BottomBar 颜色随之变化
- [ ] `commonTest` + `androidUnitTest` + `iosSimulatorArm64Test` 全部通过
- [ ] 发布坐标 `wang.harlon:kmp-webview:0.1.0` 可本地 publishToMavenLocal 成功
