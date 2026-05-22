package wang.harlon.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import wang.harlon.webview.core.LoadingState
import wang.harlon.webview.core.WebViewConfig
import wang.harlon.webview.core.WebViewState
import wang.harlon.webview.platform.PlatformWebView
import wang.harlon.webview.ui.DefaultErrorView
import wang.harlon.webview.ui.WebViewBottomBar
import wang.harlon.webview.ui.WebViewProgressBar
import wang.harlon.webview.ui.WebViewTopBar

@Composable
fun WebViewScreen(
    state: WebViewState,
    config: WebViewConfig = WebViewConfig(),
    modifier: Modifier = Modifier,
    onCloseRequest: () -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                WebViewTopBar(state, config, onCloseRequest)
                if (config.showProgressBar) {
                    WebViewProgressBar(state.loading)
                }
            }
        },
        bottomBar = {
            if (config.showBottomBar) {
                WebViewBottomBar(state)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            PlatformWebView(state, config, Modifier.fillMaxSize())
            val current = state.loading
            if (current is LoadingState.Error) {
                val renderer = config.errorContent ?: { e, retry -> DefaultErrorView(e, retry) }
                renderer(current) { state.reload() }
            }
        }
    }
}
