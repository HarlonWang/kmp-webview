package wang.harlon.webview.logpanel

/**
 * 日志面板顶部 Environment 区域展示的元信息——跟日志数据职责分离，
 * 不进 [LogStore]，Clear 不影响。
 *
 * 仅在 `WebViewConfig.enableLogPanel = true` 时由 PlatformWebView 在创建期
 * 通过各端 `capture...Environment` 函数写入 [wang.harlon.webview.core.WebViewState]。
 *
 * iOS 上 UA 获取是异步的（`evaluateJavaScript("navigator.userAgent")`），
 * 抽屉 UI 用 `mutableStateOf` 订阅，第一次组合时可能为 null，UA 到达后自动 recompose。
 */
internal data class WebViewEnvironment(
    val userAgent: String,
    val webViewVersion: String?,
)
