package wang.harlon.webview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import wang.harlon.webview.core.LoadingState
import wang.harlon.webview.core.WebViewState

@Composable
internal fun WebViewBottomBar(state: WebViewState) {
    val isLoading = state.loading is LoadingState.Loading
    BottomAppBar {
        Row(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            IconButton(onClick = { state.goBack() }, enabled = state.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
            }
            IconButton(onClick = { state.goForward() }, enabled = state.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
            }
            IconButton(onClick = { if (isLoading) state.stopLoading() else state.reload() }) {
                if (isLoading) {
                    Icon(Icons.Filled.Close, contentDescription = "停止")
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }
    }
}
