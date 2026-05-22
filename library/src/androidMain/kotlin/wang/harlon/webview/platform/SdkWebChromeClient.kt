package wang.harlon.webview.platform

import android.webkit.WebChromeClient
import android.webkit.WebView
import wang.harlon.webview.core.WebViewState

internal class SdkWebChromeClient(private val state: WebViewState) : WebChromeClient() {
    override fun onReceivedTitle(view: WebView, title: String?) {
        state.onTitleChanged(title)
    }
}
