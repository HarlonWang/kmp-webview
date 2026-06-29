package wang.harlon.webview.core

import androidx.compose.runtime.Composable

/**
 * 顶栏根页（[wang.harlon.webview.core.WebViewState.canGoBack] == false）导航图标的视觉风格。
 *
 * 注意这只是**视觉换皮**，根页 click 一律走 SDK 传进来的 `onCloseRequest`（关页语义由 host 决定），
 * 与 `canGoBack == true` 分支的 ArrowBack 走 `state.goBack()` 不同——后者是 WebView 内部栈回退。
 *
 * 之所以提供选项：x 是"关掉容器"心智（适合"打开一个 H5 后关掉回 host"），箭头是"返回上一屏"心智
 * （适合 WebView 内部已有栈回退、或和 Android 系统返回键图标对齐）。业务按产品习惯选。
 */
enum class CloseIconStyle { Close, ArrowBack }

data class WebViewConfig(
    val titleOverride: String? = null,
    val userAgent: UserAgentStrategy = UserAgentStrategy.Default,
    // 加载主文档时附加的 HTTP 请求头（Android WebView.loadUrl(url, headers) / iOS NSMutableURLRequest）。
    // 仅作用于「主文档」那一次请求；页面内同域子资源（JS / CSS / 图片 / XHR）不会自动携带——这是
    // 平台 WebView 的固有限制，非本库可绕过。典型用途：测试环境泳道路由标识（如 route-tag）。
    // 为空时行为与完全不传 header 一致（各端按空 map 短路，保持既有调用）。
    val defaultHttpHeaders: Map<String, String> = emptyMap(),
    val showTopBar: Boolean = true,
    val showBottomBar: Boolean = true,
    val showProgressBar: Boolean = true,
    val allowFileChooser: Boolean = true,
    val allowCameraCapture: Boolean = true,
    val allowMediaCapture: Boolean = true,
    // 顶栏根页（canGoBack == false）导航图标的视觉风格；默认 [CloseIconStyle.Close] 保持既有行为。
    // 详见 [CloseIconStyle] KDoc。
    val closeIconStyle: CloseIconStyle = CloseIconStyle.Close,
    // Android setWebContentsDebuggingEnabled 是进程级开关 / iOS isInspectable 仅 16.4+；release 应留 false
    val enableRemoteDebugging: Boolean = false,
    // 启用设备内日志面板（悬浮 FAB + 抽屉），采集 console / JS 异常 / WebView 错误 / JSBridge 调用链。
    // 仅 debug/灰度环境开启；启用时不要在生产暴露给终端用户（detail 字段含 bridge payload，可能携带敏感信息）。
    val enableLogPanel: Boolean = false,
    val errorContent: (@Composable (LoadingState.Error, retry: () -> Unit) -> Unit)? = null,
)
