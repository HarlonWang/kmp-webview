package wang.harlon.webview.core

import androidx.compose.runtime.Composable

data class WebViewConfig(
    val titleOverride: String? = null,
    val userAgent: UserAgentStrategy = UserAgentStrategy.Default,
    val showBottomBar: Boolean = true,
    val showProgressBar: Boolean = true,
    val overflowMenu: List<MenuAction> = defaultMenuActions(),
    val errorContent: (@Composable (LoadingState.Error, retry: () -> Unit) -> Unit)? = null,
)

fun defaultMenuActions(): List<MenuAction> = listOf(
    MenuAction(id = "refresh", label = "刷新", onClick = { it.reload() }),
)
