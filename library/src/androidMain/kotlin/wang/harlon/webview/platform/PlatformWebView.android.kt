package wang.harlon.webview.platform

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import wang.harlon.webview.core.UserAgentStrategy
import wang.harlon.webview.core.WebViewCommand
import wang.harlon.webview.core.WebViewConfig
import wang.harlon.webview.core.WebViewState

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun PlatformWebView(
    state: WebViewState,
    config: WebViewConfig,
    modifier: Modifier,
) {
    val webViewHolder = remember { WebViewHolder() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).also { wv ->
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    applyUserAgent(this, config.userAgent)
                }
                wv.webViewClient = SdkWebViewClient(state)
                wv.webChromeClient = SdkWebChromeClient(state)
                webViewHolder.attach(wv)
            }
        },
        onRelease = { wv ->
            wv.stopLoading()
            wv.webChromeClient = null
            wv.destroy()
            webViewHolder.detach()
        },
    )

    LaunchedEffect(state.pendingCommand) {
        val command = state.pendingCommand ?: return@LaunchedEffect
        val wv = webViewHolder.webView ?: return@LaunchedEffect
        when (command) {
            WebViewCommand.GoBack -> if (wv.canGoBack()) wv.goBack()
            WebViewCommand.GoForward -> if (wv.canGoForward()) wv.goForward()
            WebViewCommand.Reload -> wv.reload()
            WebViewCommand.StopLoading -> wv.stopLoading()
            is WebViewCommand.LoadUrl -> wv.loadUrl(command.url)
        }
        state.consumeCommand()
    }
}

private fun applyUserAgent(settings: WebSettings, strategy: UserAgentStrategy) {
    when (strategy) {
        UserAgentStrategy.Default -> Unit
        is UserAgentStrategy.Append -> {
            settings.userAgentString = settings.userAgentString.orEmpty() + strategy.suffix
        }
        is UserAgentStrategy.Override -> {
            settings.userAgentString = strategy.value
        }
    }
}

private class WebViewHolder {
    var webView: WebView? = null
        private set

    fun attach(wv: WebView) { webView = wv }
    fun detach() { webView = null }
}
