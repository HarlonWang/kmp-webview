package wang.harlon.webview.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSURLRequest
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject
import wang.harlon.webview.core.WebViewConfig

@OptIn(ExperimentalForeignApi::class)
internal class SdkUIDelegate(
    private val config: WebViewConfig,
) : NSObject(), WKUIDelegateProtocol {

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        val decision = if (config.allowMediaCapture) {
            WKPermissionDecision.WKPermissionDecisionGrant
        } else {
            WKPermissionDecision.WKPermissionDecisionDeny
        }
        decisionHandler(decision)
    }

    /**
     * `window.open` / `target="_blank"` 的新窗口请求。
     *
     * 不实现本回调时 WKWebView 会**直接丢弃**该请求，页面表现为点了没反应。这里不真开新 WebView
     * （库是单 WebView 容器），而是把请求在当前 WebView 里加载——行为对齐 Android
     * `setSupportMultipleWindows(false)` 的默认降级。
     *
     * `data:` / `blob:` 的 window.open 不会走到这里：`WINDOW_OPEN_DOWNLOAD_HOOK_JS` 已在 JS 层把
     * 它们改写成 `<a download>` 点击进下载管道，到这里的都是常规 http(s) 跳转。
     *
     * 仅处理 `targetFrame == null`（真正的新窗口请求）；返回 null 告知 WebKit 未创建新视图。
     */
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        if (forNavigationAction.targetFrame == null) {
            forNavigationAction.request.URL?.let {
                webView.loadRequest(NSURLRequest.requestWithURL(it))
            }
        }
        return null
    }
}
