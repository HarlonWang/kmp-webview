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
import wang.harlon.webview.core.CloseIconStyle
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
                // 根页：按 [WebViewConfig.closeIconStyle] 选视觉，click 一律走 onCloseRequest（关页）。
                // 即便选了 ArrowBack，行为仍是"关掉整个 WebView 容器"，与 canGoBack==true 分支
                // 走 state.goBack() 含义不同——SDK 不替 host 决定"返回上一屏"语义。
                //
                // 无障碍描述统一为"关闭"：无障碍语义应与实际行为一致（这里是关页），不应随
                // 图标样式漂移成"返回"——后者会误导 TalkBack/VoiceOver 用户以为能栈内回退。
                val icon = when (config.closeIconStyle) {
                    CloseIconStyle.ArrowBack -> Icons.AutoMirrored.Filled.ArrowBack
                    CloseIconStyle.Close -> Icons.Filled.Close
                }
                IconButton(onClick = onCloseRequest) {
                    Icon(icon, contentDescription = "关闭")
                }
            }
        },
    )
}
