package wang.harlon.webview.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject
import wang.harlon.webview.core.UserAgentStrategy
import wang.harlon.webview.core.WebViewState

@OptIn(ExperimentalForeignApi::class)
internal class WebViewCoordinator(
    private val state: WebViewState,
    private val config: wang.harlon.webview.core.WebViewConfig,
) {

    val delegate: WKNavigationDelegateProtocol = SdkNavigationDelegate(state)
    val uiDelegate: platform.WebKit.WKUIDelegateProtocol = SdkUIDelegate(config)

    var webView: WKWebView? = null
        private set

    fun bind(webView: WKWebView) { this.webView = webView }

    fun applyUserAgent(webView: WKWebView, strategy: UserAgentStrategy) {
        when (strategy) {
            UserAgentStrategy.Default -> Unit
            is UserAgentStrategy.Override -> {
                webView.customUserAgent = strategy.value
            }
            is UserAgentStrategy.Append -> {
                webView.evaluateJavaScript("navigator.userAgent") { result, _ ->
                    val current = (result as? String).orEmpty()
                    webView.customUserAgent = current + strategy.suffix
                }
            }
            is UserAgentStrategy.Prefix -> {
                webView.evaluateJavaScript("navigator.userAgent") { result, _ ->
                    val current = (result as? String).orEmpty()
                    webView.customUserAgent = strategy.prefix + current
                }
            }
        }
    }

    fun dispose() {
        webView?.navigationDelegate = null
        webView?.UIDelegate = null
        webView = null
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class SdkNavigationDelegate(
    private val state: WebViewState,
) : NSObject(), WKNavigationDelegateProtocol {

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        state.onLoadStarted(webView.URL?.absoluteString)
        state.onNavigationChanged(webView.canGoBack, webView.canGoForward)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        state.onLoadFinished(webView.URL?.absoluteString)
        state.onTitleChanged(webView.title)
        state.onNavigationChanged(webView.canGoBack, webView.canGoForward)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        state.onLoadFailed(
            code = withError.code.toInt(),
            description = withError.localizedDescription,
            failingUrl = webView.URL?.absoluteString,
        )
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        state.onLoadFailed(
            code = withError.code.toInt(),
            description = withError.localizedDescription,
            failingUrl = webView.URL?.absoluteString,
        )
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        state.onLoadFailed(
            code = -1,
            description = "process_terminated",
            failingUrl = webView.URL?.absoluteString,
        )
    }
}
