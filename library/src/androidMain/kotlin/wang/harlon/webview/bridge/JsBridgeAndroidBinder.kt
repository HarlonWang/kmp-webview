package wang.harlon.webview.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView

internal class JsBridgeAndroidBinder(
    private val webView: WebView,
    private val bridge: JsBridge,
) {

    init {
        webView.addJavascriptInterface(NativeChannel(), CHANNEL_NAME)
        bridge.attachEvaluator { js ->
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    /**
     * Android 上每次新文档生命周期都需要重新注入垫片。
     * 由 [wang.harlon.webview.platform.SdkWebViewClient.onPageStarted] 触发。
     */
    fun injectShim() {
        webView.post { webView.evaluateJavascript(KMP_BRIDGE_SHIM_JS, null) }
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
