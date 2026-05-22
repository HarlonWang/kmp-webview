package wang.harlon.webview.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
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
}
