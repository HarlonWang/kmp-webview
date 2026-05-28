package wang.harlon.webview.logpanel

import kotlinx.cinterop.ExperimentalForeignApi
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * iOS 日志面板 native 通道：通过 [WKScriptMessageHandlerProtocol] 接收 [buildLogShim]
 * 发回的 JSON，再 ingest 到 [LogStore]。
 *
 * 风格与 [wang.harlon.webview.bridge.JsBridgeIosBinder] 对齐：
 *   - 用 [WKUserScript] 在 `atDocumentStart` 注入 shim；`forMainFrameOnly = false`
 *     让 iframe 内的 console / onerror 也被采集
 *   - 用独立 channel 名 [LOG_CHANNEL]，与 JSBridge 解耦；关闭路径下不注册
 *
 * 仅在 [wang.harlon.webview.core.WebViewState.logStore] 非空时构造；
 * 关闭路径下 binder 完全不出现，不向 `userContentController` 添加任何脚本或 handler。
 */
@OptIn(ExperimentalForeignApi::class)
internal class LogIosBinder(
    private val webView: WKWebView,
    private val store: LogStore,
) : NSObject(), WKScriptMessageHandlerProtocol {

    init {
        val ucc = webView.configuration.userContentController
        ucc.addUserScript(
            WKUserScript(
                source = buildLogShim(LOG_CHANNEL),
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = false,
            )
        )
        ucc.addScriptMessageHandler(this, name = LOG_CHANNEL)
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val body = didReceiveScriptMessage.body as? String ?: return
        store.ingestAsync(body)
    }

    fun dispose() {
        webView.configuration.userContentController
            .removeScriptMessageHandlerForName(LOG_CHANNEL)
    }
}
