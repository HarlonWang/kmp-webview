package wang.harlon.webview.platform

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import wang.harlon.webview.core.WebViewState

internal class SdkWebViewClient(
    private val state: WebViewState,
    private val onPageStartedExtra: ((WebView, String?) -> Unit)? = null,
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        state.onLoadStarted(url)
        state.onNavigationChanged(view.canGoBack(), view.canGoForward())
        view.injectKeyboardScrollPolyfill()
        onPageStartedExtra?.invoke(view, url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        state.onLoadFinished(url)
        state.onNavigationChanged(view.canGoBack(), view.canGoForward())
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        state.onLoadFailed(
            code = error.errorCode,
            description = error.description?.toString() ?: "load failed",
            failingUrl = request.url?.toString(),
        )
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        state.onLoadFailed(
            code = error.primaryError,
            description = "SSL error",
            failingUrl = error.url,
        )
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        state.onNavigationChanged(view.canGoBack(), view.canGoForward())
    }
}
