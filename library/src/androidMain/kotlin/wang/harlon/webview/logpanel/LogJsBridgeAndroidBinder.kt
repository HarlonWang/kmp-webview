package wang.harlon.webview.logpanel

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Android 日志面板 native 通道：把 H5 内 [buildLogShim] 注入脚本收回的 JSON 消息
 * ingest 到 [LogStore]。
 *
 * 风格与 [wang.harlon.webview.bridge.JsBridgeAndroidBinder] 对齐：每个文档生命周期
 * 由 [wang.harlon.webview.platform.SdkWebViewClient.onPageStarted] 主线程触发
 * [injectShim]，直接 evaluateJavascript 而非 post —— post 会延后到主线程消息队列末尾，
 * 可能晚于页面同步 inline `<script>`，导致早期 console / error 丢失。
 *
 * 仅在业务方启用 `WebViewConfig.enableLogPanel`（即 [wang.harlon.webview.core.WebViewState.logStore]
 * 非空）时构造；关闭路径下 binder 完全不出现，不向 WebView 注册 JS interface。
 */
internal class LogJsBridgeAndroidBinder(
    private val webView: WebView,
    private val store: LogStore,
) {

    init {
        webView.addJavascriptInterface(NativeChannel(), LOG_CHANNEL)
    }

    fun injectShim() {
        webView.evaluateJavascript(buildLogShim(LOG_CHANNEL), null)
    }

    fun dispose() {
        webView.removeJavascriptInterface(LOG_CHANNEL)
    }

    private inner class NativeChannel {
        @JavascriptInterface
        fun postMessage(json: String) {
            // 在 WebView 内部 JS bridge 线程；LogStore 内自带的 scope 会切回 Main 串行写入。
            store.ingestAsync(json)
        }
    }
}
