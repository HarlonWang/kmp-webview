package wang.harlon.webview.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import wang.harlon.webview.core.WebViewConfig
import wang.harlon.webview.core.WebViewState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebViewTopBar(
    state: WebViewState,
    config: WebViewConfig,
    onCloseRequest: () -> Unit,
) {
    val resolvedTitle = config.titleOverride ?: state.title

    TopAppBar(
        title = {
            Text(text = resolvedTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            if (state.canGoBack) {
                IconButton(onClick = { state.goBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            } else {
                IconButton(onClick = onCloseRequest) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
        },
    )
}
