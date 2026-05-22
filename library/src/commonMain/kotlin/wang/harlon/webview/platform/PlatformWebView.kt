package wang.harlon.webview.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import wang.harlon.webview.core.WebViewConfig
import wang.harlon.webview.core.WebViewState

@Composable
internal expect fun PlatformWebView(
    state: WebViewState,
    config: WebViewConfig,
    modifier: Modifier = Modifier,
)
