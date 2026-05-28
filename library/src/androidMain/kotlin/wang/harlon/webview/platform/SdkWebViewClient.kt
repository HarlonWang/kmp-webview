package wang.harlon.webview.platform

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import wang.harlon.webview.core.WebViewState
import wang.harlon.webview.logpanel.WebViewLog

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
        val url = request.url?.toString()
        val desc = error.description?.toString() ?: "load failed"
        if (request.isForMainFrame) {
            state.onLoadFailed(code = error.errorCode, description = desc, failingUrl = url)
            state.logStore?.appendAsync(
                source = WebViewLog.Source.WebViewError,
                level = WebViewLog.Level.Error,
                message = "[mainFrame] $desc ${url.orEmpty()}".trim(),
            )
        } else {
            // 二级资源失败按 Verbose 入库；UI 默认过滤掉，避免淹没列表。
            state.logStore?.appendAsync(
                source = WebViewLog.Source.WebViewError,
                level = WebViewLog.Level.Verbose,
                message = "[resource ${error.errorCode}] ${desc} ${url.orEmpty()}".trim(),
            )
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        val url = request.url?.toString()
        val status = errorResponse.statusCode
        val reason = errorResponse.reasonPhrase.orEmpty()
        val level = if (request.isForMainFrame) WebViewLog.Level.Error else WebViewLog.Level.Warn
        state.logStore?.appendAsync(
            source = WebViewLog.Source.WebViewError,
            level = level,
            message = "HTTP $status ${url.orEmpty()} $reason".trim(),
        )
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        state.onLoadFailed(
            code = error.primaryError,
            description = "SSL error",
            failingUrl = error.url,
        )
        state.logStore?.appendAsync(
            source = WebViewLog.Source.WebViewError,
            level = WebViewLog.Level.Error,
            message = "SSL error (${error.primaryError}) ${error.url.orEmpty()}",
            detail = error.certificate?.toString(),
        )
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        state.onNavigationChanged(view.canGoBack(), view.canGoForward())
    }
}
