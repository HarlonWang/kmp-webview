package wang.harlon.webview.platform

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import wang.harlon.webview.bridge.JsBridgeAndroidBinder
import wang.harlon.webview.core.DownloadScripts
import wang.harlon.webview.core.UserAgentStrategy
import wang.harlon.webview.core.WebViewCommand
import wang.harlon.webview.core.WebViewConfig
import wang.harlon.webview.core.WebViewState
import wang.harlon.webview.logpanel.LogJsBridgeAndroidBinder
import wang.harlon.webview.logpanel.captureAndroidEnvironment

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
        // imePadding 收缩 AndroidView 可用尺寸 = IME 高度，让出空间给软键盘。
        // Activity 走 enableEdgeToEdge() 后 decorFitsSystemWindows=false，旧的
        // windowSoftInputMode=adjustResize 已失效，必须由内容主动消费 IME inset。
        // 与 [SdkWebViewClient] 注入的 KeyboardScrollPolyfill 配对：imePadding 让
        // visualViewport.height 真正收缩（M139 以下内核不会自己 resize vv），
        // polyfill 收到 vv.resize 后才能算出焦点 input 被遮挡 → scrollIntoView。
        modifier = modifier.imePadding(),
        factory = { ctx ->
            // 进程级开关——只调一次即生效；多 WebView 共存时任一 config 为 true 即整体启用
            if (config.enableRemoteDebugging) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            // 在创建 WebView 前确保 logStore 已就绪：state.enableLogPanel 调用一次幂等。
            if (config.enableLogPanel) state.enableLogPanel()
            WebView(ctx).also { wv ->
                // 显式 MATCH_PARENT：WebView 默认 LayoutParams 是 WRAP_CONTENT，与 SPA 用
                // `height: 100vh / 100%` 的 CSS 互相依赖会算出 viewport h=0，整页空白。
                wv.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    applyUserAgent(this, config.userAgent)
                }
                val binder = JsBridgeAndroidBinder(
                    webView = wv,
                    bridge = state.jsBridge,
                    channel = state.bridgeChannel,
                )
                webViewHolder.binder = binder
                val logBinder = state.logStore?.let { LogJsBridgeAndroidBinder(wv, it) }
                webViewHolder.logBinder = logBinder
                // 仅在面板启用时采集 environment；关闭路径下不读 settings/PackageManager。
                if (state.logStore != null) captureAndroidEnvironment(wv, state)
                // 下载接管：注册 DownloadListener + JS 回传接口（须在页面加载前 addJavascriptInterface）。
                val downloader = if (config.allowDownloads) {
                    AndroidWebDownloader(ctx, config).also { dl ->
                        wv.addJavascriptInterface(dl.jsInterface(), DownloadScripts.NATIVE_INTERFACE)
                        wv.setDownloadListener { url, ua, disposition, mime, _ ->
                            dl.onDownloadStart(wv, url, ua, disposition, mime)
                        }
                    }
                } else {
                    null
                }
                webViewHolder.downloader = downloader
                wv.webViewClient = SdkWebViewClient(
                    state = state,
                    onPageStartedExtra = { view, _ ->
                        binder.injectShim()
                        logBinder?.injectShim()
                        // blob 下载拿不到 <a download> 文件名，靠该 hook 在点击时暂存。
                        if (downloader != null) view.evaluateJavascript(DownloadScripts.DOWNLOAD_NAME_HOOK_JS, null)
                    },
                )
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
            webViewHolder.logBinder?.dispose()
            webViewHolder.logBinder = null
            webViewHolder.binder?.dispose()
            webViewHolder.binder = null
            webViewHolder.downloader?.dispose()
            webViewHolder.downloader = null
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
            is WebViewCommand.LoadUrl ->
                if (config.defaultHttpHeaders.isEmpty()) wv.loadUrl(command.url)
                else wv.loadUrl(command.url, config.defaultHttpHeaders)
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
        is UserAgentStrategy.Prefix -> {
            settings.userAgentString = strategy.prefix + settings.userAgentString.orEmpty()
        }
        is UserAgentStrategy.Override -> {
            settings.userAgentString = strategy.value
        }
    }
}

private class WebViewHolder {
    var webView: WebView? = null
        private set
    var binder: JsBridgeAndroidBinder? = null
    var logBinder: LogJsBridgeAndroidBinder? = null
    var downloader: AndroidWebDownloader? = null

    fun attach(wv: WebView) { webView = wv }
    fun detach() { webView = null }
}

private class LauncherHolder {
    var fileChooser: FileChooserLauncher? = null
    var mediaPermission: MediaPermissionLauncher? = null
}
