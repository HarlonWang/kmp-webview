package wang.harlon.webview.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView

internal class JsBridgeAndroidBinder(
    private val webView: WebView,
    private val bridge: JsBridge,
) {

    init {
        webView.addJavascriptInterface(NativeChannel(), CHANNEL_NAME)
        // evaluateJavascript 必须在主线程；emit 可能来自 binder.dispatchIncoming 协程或业务方任意线程，
        // 用 webView.post 派发到主线程。
        bridge.attachEvaluator { js ->
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    /**
     * Android 上每次新文档生命周期都需要重新注入垫片。
     * 由 [wang.harlon.webview.platform.SdkWebViewClient.onPageStarted] 在主线程触发，
     * 直接 evaluateJavascript 而不再 post —— post 会把执行延后到主线程消息队列末尾，
     * 可能晚于页面同步 inline <script>，导致业务方调 KmpBridge.* 时变量未定义。
     */
    fun injectShim() {
        webView.evaluateJavascript(KMP_BRIDGE_SHIM_JS, null)
    }

    fun dispose() {
        webView.removeJavascriptInterface(CHANNEL_NAME)
        bridge.detachEvaluator()
    }

    private inner class NativeChannel {
        @JavascriptInterface
        fun postMessage(json: String) {
            // 注意：该回调在 WebView 内部的 JsBridge 线程，不是 Android 主线程
            bridge.dispatchIncoming(json) { webView.url }
        }
    }

    private companion object {
        const val CHANNEL_NAME = "__kmpBridgeNative"
    }
}
