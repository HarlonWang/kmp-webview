package wang.harlon.webview.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import wang.harlon.webview.bridge.JsBridgeIosBinder
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
    val coordinator = remember(config) { WebViewCoordinator(state, config) }
    val binderHolder = remember { BinderHolder() }

    UIKitView(
        modifier = modifier,
        factory = {
            val wkConfig = WKWebViewConfiguration().apply {
                allowsInlineMediaPlayback = true
                mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
            }
            val webView = WKWebView(
                frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 0.0, 0.0),
                configuration = wkConfig,
            )
            webView.navigationDelegate = coordinator.delegate
            webView.UIDelegate = coordinator.uiDelegate
            coordinator.applyUserAgent(webView, config.userAgent)
            if (config.enableRemoteDebugging) {
                webView.applyInspectableIfAvailable(true)
            }
            binderHolder.binder = JsBridgeIosBinder(
                webView = webView,
                bridge = state.jsBridge,
                channel = state.bridgeChannel,
            )
            coordinator.bind(webView)
            webView
        },
        onRelease = {
            binderHolder.binder?.dispose()
            binderHolder.binder = null
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

private class BinderHolder {
    var binder: JsBridgeIosBinder? = null
}

/**
 * 设置 WKWebView 远程调试可见。iOS 16.4+ 新增 `setInspectable:` 方法，
 * 用 selector 探测守住运行时：低于 16.4 直接跳过。
 *
 * iOS 16.4 以下：debug 配置打包的 App 默认就能被 Safari Web Inspector inspect，无需此开关。
 */
@OptIn(ExperimentalForeignApi::class)
private fun WKWebView.applyInspectableIfAvailable(enabled: Boolean) {
    val obj: NSObject = this
    if (obj.respondsToSelector(NSSelectorFromString("setInspectable:"))) {
        setInspectable(enabled)
    }
}
