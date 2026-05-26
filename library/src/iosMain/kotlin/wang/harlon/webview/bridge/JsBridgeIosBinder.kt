package wang.harlon.webview.bridge

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSOperationQueue
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
internal class JsBridgeIosBinder(
    private val webView: WKWebView,
    private val bridge: JsBridge,
    private val channel: String,
) : NSObject(), WKScriptMessageHandlerProtocol {

    init {
        // channel 由 WebViewState 从 bridgeNamespace 派生，合法性由 namespace 校验链保证，此处不再重复 require。
        val ucc = webView.configuration.userContentController
        ucc.addUserScript(WKUserScript(
            source = buildKmpBridgeShim(bridge.namespace, channel),
            injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
            forMainFrameOnly = true,
        ))
        ucc.addScriptMessageHandler(this, name = channel)
        bridge.attachEvaluator { js ->
            // evaluateJavaScript 必须在主线程；scriptMessage 回调本身就在主线程，
            // 但 Native → JS 的 emit 可能从任意线程来，统一派发到 main。
            NSOperationQueue.mainQueue.addOperationWithBlock {
                webView.evaluateJavaScript(js, completionHandler = null)
            }
        }
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val body = didReceiveScriptMessage.body as? String ?: return
        bridge.dispatchIncoming(body) { webView.URL?.absoluteString }
    }

    fun dispose() {
        webView.configuration.userContentController
            .removeScriptMessageHandlerForName(channel)
        bridge.detachEvaluator()
    }
}
