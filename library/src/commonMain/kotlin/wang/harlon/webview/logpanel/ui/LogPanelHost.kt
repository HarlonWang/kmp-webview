package wang.harlon.webview.logpanel.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import wang.harlon.webview.logpanel.LogStore
import wang.harlon.webview.logpanel.WebViewEnvironment
import wang.harlon.webview.logpanel.WebViewLog

/**
 * 日志面板入口：管 FAB / 抽屉的开关，订阅 [LogStore.entries] 用于 FAB 未读计数。
 *
 * 未读计数语义（spec：[docs/superpowers/specs/2026-05-28-webview-log-panel-design.md]）：
 *   - 从上一次抽屉关闭时起，新增的 `Level == Error` 条目数
 *   - 抽屉打开期间不显示、不累计
 *   - 抽屉关闭瞬间把 `lastSeenId` 置为当前末条 id，归零开始累计新增
 */
@Composable
internal fun LogPanelHost(
    store: LogStore,
    environment: WebViewEnvironment?,
    modifier: Modifier = Modifier,
) {
    val entries by store.entries.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    var lastSeenId by rememberSaveable { mutableStateOf(-1L) }
    // host Box 的实测尺寸（px），给 FAB 用来 clamp 拖动边界；旋转/IME 时会变。
    var hostSize by remember { mutableStateOf(IntSize.Zero) }

    val unreadErrors = remember(entries, lastSeenId, open) {
        if (open) 0
        else entries.count { it.id > lastSeenId && it.level == WebViewLog.Level.Error }
    }

    Box(modifier.onSizeChanged { hostSize = it }) {
        LogPanelFab(
            unreadErrors = unreadErrors,
            hostSize = hostSize,
            onClick = { open = true },
        )
        if (open) {
            LogPanelDrawer(
                entries = entries,
                environment = environment,
                onClose = {
                    lastSeenId = entries.lastOrNull()?.id ?: -1L
                    open = false
                },
                onClear = { store.clearAsync() },
            )
        }
    }
}
