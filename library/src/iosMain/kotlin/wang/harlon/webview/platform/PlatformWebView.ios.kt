package wang.harlon.webview.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import wang.harlon.webview.core.UserAgentStrategy
import wang.harlon.webview.core.WebViewCommand
import wang.harlon.webview.core.WebViewConfig
import wang.harlon.webview.core.WebViewState

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformWebView(
    state: WebViewState,
    config: WebViewConfig,
    modifier: Modifier,
) {
    val coordinator = remember { WebViewCoordinator(state) }

    UIKitView(
        modifier = modifier,
        factory = {
            val webView = WKWebView(
                frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 0.0, 0.0),
                configuration = WKWebViewConfiguration(),
            )
            webView.navigationDelegate = coordinator.delegate
            coordinator.applyUserAgent(webView, config.userAgent)
            coordinator.bind(webView)
            webView
        },
        onRelease = {
            coordinator.dispose()
        },
    )

    LaunchedEffect(state.pendingCommand) {
        val command = state.pendingCommand ?: return@LaunchedEffect
        val wv = coordinator.webView ?: return@LaunchedEffect
        when (command) {
            WebViewCommand.GoBack -> if (wv.canGoBack) wv.goBack()
            WebViewCommand.GoForward -> if (wv.canGoForward) wv.goForward()
            WebViewCommand.Reload -> wv.reload()
            WebViewCommand.StopLoading -> wv.stopLoading()
            is WebViewCommand.LoadUrl -> {
                val nsUrl = NSURL.URLWithString(command.url) ?: run {
                    state.consumeCommand()
                    return@LaunchedEffect
                }
                wv.loadRequest(NSURLRequest.requestWithURL(nsUrl))
            }
        }
        state.consumeCommand()
    }

    DisposableEffect(coordinator) {
        onDispose { coordinator.dispose() }
    }
}
