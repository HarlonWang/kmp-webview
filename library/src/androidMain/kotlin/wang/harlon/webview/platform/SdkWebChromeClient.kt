package wang.harlon.webview.platform

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import wang.harlon.webview.core.WebViewConfig
import wang.harlon.webview.core.WebViewState
import wang.harlon.webview.logpanel.NATIVE_CONSOLE_DETAIL
import wang.harlon.webview.logpanel.WebViewLog
import wang.harlon.webview.logpanel.nativeUncaughtConsoleMessage

internal class SdkWebChromeClient(
    private val state: WebViewState,
    private val config: WebViewConfig,
    private val fileChooserProvider: () -> FileChooserLauncher?,
    private val mediaPermissionProvider: () -> MediaPermissionLauncher?,
) : WebChromeClient() {

    override fun onReceivedTitle(view: WebView, title: String?) {
        state.onTitleChanged(title)
    }

    // 未捕获 JS 异常的 native 兜底：跨域脚本的异常在 shim 侧被脱敏成 "Script error."，
    // 该通道不受脱敏约束，能补回真实 message 与文件行号（筛选逻辑见 nativeUncaughtConsoleMessage）。
    // 恒返回 false，保留系统默认行为（logcat [INFO:CONSOLE] 照常输出）。
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val store = state.logStore ?: return false
        if (consoleMessage.messageLevel() != ConsoleMessage.MessageLevel.ERROR) return false
        val formatted = nativeUncaughtConsoleMessage(
            message = consoleMessage.message(),
            sourceId = consoleMessage.sourceId(),
            lineNumber = consoleMessage.lineNumber(),
        ) ?: return false
        store.appendAsync(
            source = WebViewLog.Source.JsException,
            level = WebViewLog.Level.Error,
            message = formatted,
            detail = NATIVE_CONSOLE_DETAIL,
        )
        return false
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
