package wang.harlon.webview.logpanel

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIDevice
import platform.WebKit.WKWebView
import wang.harlon.webview.core.WebViewState

/**
 * 在 PlatformWebView factory 内 WebView 创建后调用：
 *   - 同步：iOS 系统版本（`UIDevice.currentDevice.systemVersion`）作为 WebView 内核版本展示
 *     —— WKWebView 没有公开 WebKit 版本 API，前端通常关心的就是宿主 iOS 版本
 *   - UA：优先读 `customUserAgent`，未 override 时 `evaluateJavaScript("navigator.userAgent")` 异步获取
 *
 * 异步路径：第一次写入可能仅含版本（`userAgent="(pending)"` 用 customUserAgent 即可避免），
 * 等 JS 拉到真实 UA 后整个对象被替换，抽屉 mutableStateOf 自动 recompose。
 */
@OptIn(ExperimentalForeignApi::class)
internal fun captureIosEnvironment(webView: WKWebView, state: WebViewState) {
    val iosVersion = "iOS ${UIDevice.currentDevice.systemVersion}"
    val custom = webView.customUserAgent
    if (!custom.isNullOrEmpty()) {
        state.setEnvironment(WebViewEnvironment(userAgent = custom, webViewVersion = iosVersion))
    } else {
        // 先占位写一条仅含版本的，避免 UA 异步到达前抽屉里 Environment 折叠区不显示。
        state.setEnvironment(WebViewEnvironment(userAgent = "(loading...)", webViewVersion = iosVersion))
        webView.evaluateJavaScript("navigator.userAgent") { result, _ ->
            val ua = (result as? String).orEmpty().ifEmpty { "(unavailable)" }
            state.setEnvironment(WebViewEnvironment(userAgent = ua, webViewVersion = iosVersion))
        }
    }
}
