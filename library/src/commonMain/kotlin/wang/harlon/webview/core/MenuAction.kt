package wang.harlon.webview.core

data class MenuAction(
    val id: String,
    val label: String,
    val onClick: (WebViewState) -> Unit,
)
