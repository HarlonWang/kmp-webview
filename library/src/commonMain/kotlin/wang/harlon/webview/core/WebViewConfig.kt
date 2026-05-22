package wang.harlon.webview.core

import androidx.compose.runtime.Composable

data class WebViewConfig(
    val titleOverride: String? = null,
    val userAgent: UserAgentStrategy = UserAgentStrategy.Default,
    val showBottomBar: Boolean = true,
    val showProgressBar: Boolean = true,
    val allowFileChooser: Boolean = true,
    val allowCameraCapture: Boolean = true,
    val allowMediaCapture: Boolean = true,
    val errorContent: (@Composable (LoadingState.Error, retry: () -> Unit) -> Unit)? = null,
)
