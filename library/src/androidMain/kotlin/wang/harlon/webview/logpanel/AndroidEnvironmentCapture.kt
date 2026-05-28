package wang.harlon.webview.logpanel

import android.os.Build
import android.webkit.WebView
import wang.harlon.webview.core.WebViewState

/**
 * 在 PlatformWebView factory 内 WebView 创建后立即调用：
 * 同步读 UA + WebView Provider 包名与 versionName 写入 [WebViewState].environment。
 *
 * `WebView.getCurrentWebViewPackage()` 是 API 26+，minSdk 24 上 SDK_INT < 26 时
 * 退回 null，前端仍能看到 UA。
 */
internal fun captureAndroidEnvironment(webView: WebView, state: WebViewState) {
    val ua = webView.settings.userAgentString.orEmpty()
    val version: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WebView.getCurrentWebViewPackage()?.let { pkg ->
            "${prettyName(pkg.packageName)} ${pkg.versionName}"
        }
    } else {
        null
    }
    state.setEnvironment(WebViewEnvironment(userAgent = ua, webViewVersion = version))
}

private fun prettyName(packageName: String): String = when {
    packageName.contains("chrome", ignoreCase = true) -> "Chrome"
    packageName.contains("webview", ignoreCase = true) -> "Android System WebView"
    else -> packageName.substringAfterLast('.')
}
