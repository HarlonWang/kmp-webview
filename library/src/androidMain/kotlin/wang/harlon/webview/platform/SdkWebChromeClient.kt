package wang.harlon.webview.platform

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import wang.harlon.webview.core.WebViewConfig
import wang.harlon.webview.core.WebViewState

internal class SdkWebChromeClient(
    private val state: WebViewState,
    private val config: WebViewConfig,
    private val fileChooserProvider: () -> FileChooserLauncher?,
    private val mediaPermissionProvider: () -> MediaPermissionLauncher?,
) : WebChromeClient() {

    override fun onReceivedTitle(view: WebView, title: String?) {
        state.onTitleChanged(title)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        if (!config.allowFileChooser && !config.allowCameraCapture) {
            filePathCallback.onReceiveValue(null)
            return true
        }
        val launcher = fileChooserProvider() ?: run {
            filePathCallback.onReceiveValue(null)
            return true
        }
        return launcher.launch(filePathCallback, fileChooserParams)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        if (!config.allowMediaCapture) {
            request.deny()
            return
        }
        val launcher = mediaPermissionProvider() ?: run {
            request.deny()
            return
        }
        launcher.handle(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        // 系统取消时 PermissionRequest 已被释放，不需要额外操作
    }
}
