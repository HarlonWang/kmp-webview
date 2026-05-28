package wang.harlon.webview.logpanel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import wang.harlon.webview.logpanel.WebViewEnvironment
import wang.harlon.webview.logpanel.WebViewLog

/**
 * 抽屉本体：ModalBottomSheet（最高 90%）+ 顶部 toolbar（标题/Clear/关闭）+
 * 4 个 chip（All/Console/Bridge/Error）+ 搜索框 + 倒序 LazyColumn。
 *
 * 过滤组合规则见 spec UI 节：
 *   - 多选并集；空集即 All；勾 All 时清空其他
 *   - Console → source == Console；Bridge → source == JsBridge；Error → level == Error
 *   - Verbose 默认隐藏（resource 二级失败、shim unparsable 兜底等都标 Verbose）
 *
 * 跟随新日志规则：用户在列表顶 24px 内时自动滚到顶部，否则保持位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogPanelDrawer(
    entries: List<WebViewLog>,
    environment: WebViewEnvironment?,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedFilters by rememberSaveable { mutableStateOf(emptySet<Filter>()) }
    var search by rememberSaveable { mutableStateOf("") }
    var showVerbose by rememberSaveable { mutableStateOf(false) }

    val filtered = remember(entries, selectedFilters, search, showVerbose) {
        filterEntries(entries, selectedFilters, search, showVerbose).asReversed()
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            // 顶部 toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Logs (${entries.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear logs")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            // Environment 折叠区——元信息，跟日志数据分离；Clear 不影响。
            EnvironmentSection(environment)

            // 过滤 chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = selectedFilters.isEmpty(),
                    onClick = { selectedFilters = emptySet() },
                    label = { Text("All") },
                )
                Filter.entries.forEach { f ->
                    FilterChip(
                        selected = f in selectedFilters,
                        onClick = {
                            selectedFilters = if (f in selectedFilters) {
                                selectedFilters - f
                            } else {
                                selectedFilters + f
                            }
                        },
                        label = { Text(f.label) },
                    )
                }
                FilterChip(
                    selected = showVerbose,
                    onClick = { showVerbose = !showVerbose },
                    label = { Text("Verbose") },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }

            // 搜索
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                placeholder = { Text("Search message / detail") },
                singleLine = true,
                trailingIcon = if (search.isNotEmpty()) {
                    {
                        IconButton(onClick = { search = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                } else null,
            )

            HorizontalDivider()

            val listState = rememberLazyListState()
            // 跟随新日志：用户已在列表顶（首条可见且滚动偏移 < 24dp）时自动 scrollToItem(0)。
            LaunchedEffect(filtered.firstOrNull()?.id) {
                val first = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                if (first == 0 && offset < 64 && filtered.isNotEmpty()) {
                    listState.scrollToItem(0)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(420.dp),
            ) {
                if (filtered.isEmpty()) {
                    item("empty") {
                        Text(
                            text = if (entries.isEmpty()) "No logs yet." else "No matches.",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    items(filtered, key = { it.id }) { log ->
                        LogRow(log)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentSection(env: WebViewEnvironment?) {
    if (env == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse environment" else "Expand environment",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Environment",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)) {
                EnvLine("UA", env.userAgent)
                env.webViewVersion?.let { EnvLine("WebView", it) }
            }
        }
    }
}

@Composable
private fun EnvLine(label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

internal enum class Filter(val label: String) {
    Console("Console"),
    Bridge("Bridge"),
    Error("Error"),
}

internal fun filterEntries(
    entries: List<WebViewLog>,
    selected: Set<Filter>,
    search: String,
    showVerbose: Boolean,
): List<WebViewLog> {
    val q = search.trim()
    return entries.filter { log ->
        if (!showVerbose && log.level == WebViewLog.Level.Verbose) return@filter false
        if (selected.isNotEmpty()) {
            val matches = selected.any { f -> f.matches(log) }
            if (!matches) return@filter false
        }
        if (q.isNotEmpty()) {
            val hay = log.message + " " + (log.detail.orEmpty())
            if (!hay.contains(q, ignoreCase = true)) return@filter false
        }
        true
    }
}

private fun Filter.matches(log: WebViewLog): Boolean = when (this) {
    Filter.Console -> log.source == WebViewLog.Source.Console
    Filter.Bridge -> log.source == WebViewLog.Source.JsBridge
    // Error 跨 source：console.error / JsException / 主框架 WebViewError / Bridge 异常
    Filter.Error -> log.level == WebViewLog.Level.Error
}
