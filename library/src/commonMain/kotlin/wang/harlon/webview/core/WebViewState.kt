package wang.harlon.webview.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class WebViewState internal constructor(initialUrl: String) {

    var currentUrl: String by mutableStateOf(initialUrl)
        private set

    var title: String by mutableStateOf("")
        private set

    var loading: LoadingState by mutableStateOf(LoadingState.Idle)
        private set

    var canGoBack: Boolean by mutableStateOf(false)
        private set

    var canGoForward: Boolean by mutableStateOf(false)
        private set

    internal var pendingCommand: WebViewCommand? by mutableStateOf(WebViewCommand.LoadUrl(initialUrl))
        private set

    fun goBack() { dispatch(WebViewCommand.GoBack) }
    fun goForward() { dispatch(WebViewCommand.GoForward) }
    fun reload() { dispatch(WebViewCommand.Reload) }
    fun stopLoading() { dispatch(WebViewCommand.StopLoading) }

    fun loadUrl(url: String) {
        if (url.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) return
        dispatch(WebViewCommand.LoadUrl(url))
    }

    private fun dispatch(command: WebViewCommand) {
        pendingCommand = command
    }

    internal fun consumeCommand() {
        pendingCommand = null
    }

    internal fun onLoadStarted(url: String?) {
        if (url != null) currentUrl = url
        loading = LoadingState.Loading
    }

    internal fun onLoadFinished(url: String?) {
        if (url != null) currentUrl = url
        loading = LoadingState.Finished
    }

    internal fun onLoadFailed(code: Int, description: String, failingUrl: String?) {
        loading = LoadingState.Error(code, description, failingUrl)
    }

    internal fun onTitleChanged(value: String?) {
        title = value.orEmpty()
    }

    internal fun onNavigationChanged(canGoBack: Boolean, canGoForward: Boolean) {
        this.canGoBack = canGoBack
        this.canGoForward = canGoForward
    }
}

internal sealed interface WebViewCommand {
    object GoBack : WebViewCommand
    object GoForward : WebViewCommand
    object Reload : WebViewCommand
    object StopLoading : WebViewCommand
    data class LoadUrl(val url: String) : WebViewCommand
}

@Composable
fun rememberWebViewState(initialUrl: String): WebViewState =
    remember(initialUrl) { WebViewState(initialUrl) }
