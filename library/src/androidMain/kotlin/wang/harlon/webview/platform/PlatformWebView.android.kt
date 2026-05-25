package wang.harlon.webview.platform

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val webViewHolder = remember { WebViewHolder() }
    val launcherHolder = remember { LauncherHolder() }

    val singleDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        launcherHolder.fileChooser?.onPickerResult(uri)
    }
    val multiDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        launcherHolder.fileChooser?.onMultiPickerResult(uris)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        launcherHolder.fileChooser?.onCameraResult(success)
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        launcherHolder.fileChooser?.onCameraPermissionResult(granted)
    }
    val mediaPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        launcherHolder.mediaPermission?.onSystemPermissionResult(result)
    }

    DisposableEffect(config) {
        launcherHolder.fileChooser = FileChooserLauncher(
            context = context,
            config = config,
            singleDocLauncher = singleDocLauncher,
            multiDocLauncher = multiDocLauncher,
            cameraLauncher = cameraLauncher,
            cameraPermissionLauncher = cameraPermLauncher,
        )
        launcherHolder.mediaPermission = MediaPermissionLauncher(context, mediaPermLauncher)
        onDispose {
            launcherHolder.fileChooser?.dispose()
            launcherHolder.mediaPermission?.dispose()
            launcherHolder.fileChooser = null
            launcherHolder.mediaPermission = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // 进程级开关——只调一次即生效；多 WebView 共存时任一 config 为 true 即整体启用
            if (config.enableRemoteDebugging) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            WebView(ctx).also { wv ->
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    applyUserAgent(this, config.userAgent)
                }
                wv.webViewClient = SdkWebViewClient(state)
                wv.webChromeClient = SdkWebChromeClient(
                    state = state,
                    config = config,
                    fileChooserProvider = { launcherHolder.fileChooser },
                    mediaPermissionProvider = { launcherHolder.mediaPermission },
                )
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

private class LauncherHolder {
    var fileChooser: FileChooserLauncher? = null
    var mediaPermission: MediaPermissionLauncher? = null
}
