package wang.harlon.webview.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject
import wang.harlon.webview.core.UserAgentStrategy
import wang.harlon.webview.core.WebViewState
import wang.harlon.webview.logpanel.WebViewLog

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

/**
 * 强制页面 viewport 声明缩放限制，禁掉 iOS 「聚焦 font-size<16px 输入框时自动放大」。
 * 由 [wang.harlon.webview.core.WebViewConfig.lockZoom] 控制，默认注入；确需缩放的页面显式关掉。
 *
 * 放大本身不可怕，可怕的是 iOS 放大后不会自动缩回——页面就此横向溢出，用户只能左右滑动才能看全
 * （B2B2-8437）。`maximum-scale=1 + user-scalable=no` 是挡这个行为的标准声明，WKWebView 尊重它
 * （与 Safari 不同，Safari 自 iOS 10 起会无视 user-scalable=no）。
 *
 * 为什么不在 native 侧锁 `scrollView.min/maxZoomScale`：那两个值会被 WebKit 在页面生命周期里反复
 * 重算覆盖，实测仅靠 navigationDelegate 补设盖不住 SPA；而 Swift 那套 KeyPath KVO 守护在
 * Kotlin/Native 无法实现——`observeValueForKeyPath` 属于 NSObject 的 category，K/N 暴露为扩展函数，
 * 不可 override。故改由页面侧声明 + MutationObserver 守护，承担等价的「被改就改回来」职责。
 *
 * `forMainFrameOnly = false` 配合注入子 frame，跨域 iframe 内的表单同样覆盖。
 */
private val VIEWPORT_LOCK_JS = """
(function () {
  var LOCK = 'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=1.0, user-scalable=no';
  function apply() {
    var head = document.head || document.documentElement;
    if (!head) return;
    var m = document.querySelector('meta[name=viewport]');
    if (!m) {
      m = document.createElement('meta');
      m.setAttribute('name', 'viewport');
      head.appendChild(m);
    }
    // 判等再写：避免自身的写入触发 MutationObserver 造成无限回环
    if (m.getAttribute('content') !== LOCK) m.setAttribute('content', LOCK);
  }
  apply();
  if (window.__kmpViewportLockInstalled) return;
  window.__kmpViewportLockInstalled = true;
  // 守护 SPA / 适配库在运行时改写 viewport——与 native KVO 守护同职责
  if (window.MutationObserver && document.head) {
    new MutationObserver(apply).observe(document.head, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['content'],
    });
  }
})();
""".trimIndent()

/** 供 [PlatformWebView] 装配进 WKWebViewConfiguration 的 userContentController。 */
internal fun viewportLockUserScript(): WKUserScript = WKUserScript(
    source = VIEWPORT_LOCK_JS,
    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentEnd,
    forMainFrameOnly = false,
)

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
        val url = webView.URL?.absoluteString
        state.onLoadFailed(
            code = withError.code.toInt(),
            description = withError.localizedDescription,
            failingUrl = url,
        )
        appendNavError(withError, url, "didFail")
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        val url = webView.URL?.absoluteString
        state.onLoadFailed(
            code = withError.code.toInt(),
            description = withError.localizedDescription,
            failingUrl = url,
        )
        appendNavError(withError, url, "didFailProvisional")
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        val url = webView.URL?.absoluteString
        state.onLoadFailed(
            code = -1,
            description = "process_terminated",
            failingUrl = url,
        )
        state.logStore?.appendAsync(
            source = WebViewLog.Source.WebViewError,
            level = WebViewLog.Level.Error,
            message = "process terminated ${url.orEmpty()}".trim(),
        )
    }

    private fun appendNavError(error: NSError, url: String?, tag: String) {
        state.logStore?.appendAsync(
            source = WebViewLog.Source.WebViewError,
            level = WebViewLog.Level.Error,
            message = "[$tag ${error.code}] ${error.localizedDescription} ${url.orEmpty()}".trim(),
            detail = error.userInfo.takeIf { it.isNotEmpty() }?.toString(),
        )
    }
}
