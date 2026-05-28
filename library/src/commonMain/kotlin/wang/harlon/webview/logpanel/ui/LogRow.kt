package wang.harlon.webview.logpanel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import wang.harlon.webview.logpanel.WebViewLog

@Composable
internal fun LogRow(log: WebViewLog) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formatTime(log.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LevelChip(log.level)
            Text(
                text = sourceLabel(log.source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = log.message,
            style = MaterialTheme.typography.bodySmall,
            color = levelColor(log.level),
        )
        log.detail?.let { d ->
            Text(
                text = d,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LevelChip(level: WebViewLog.Level) {
    val color = levelColor(level)
    Text(
        text = level.name.lowercase(),
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun levelColor(level: WebViewLog.Level): Color = when (level) {
    WebViewLog.Level.Verbose -> MaterialTheme.colorScheme.onSurfaceVariant
    WebViewLog.Level.Info -> MaterialTheme.colorScheme.onSurface
    WebViewLog.Level.Warn -> Color(0xFFE57C02)
    WebViewLog.Level.Error -> Color(0xFFD32F2F)
}

private fun sourceLabel(s: WebViewLog.Source): String = when (s) {
    WebViewLog.Source.Console -> "console"
    WebViewLog.Source.JsException -> "jserror"
    WebViewLog.Source.WebViewError -> "webview"
    WebViewLog.Source.JsBridge -> "bridge"
}

/**
 * `HH:mm:ss.fff` 格式，按 epochMs 直接算 —— 不依赖 platform 时区库；
 * 排查日志看的是相对顺序和秒/毫秒精度，UTC 偏移在面板里不重要。
 */
private fun formatTime(epochMs: Long): String {
    val totalSeconds = epochMs / 1000
    val ms = (epochMs % 1000).toInt()
    val s = (totalSeconds % 60).toInt()
    val m = ((totalSeconds / 60) % 60).toInt()
    val h = ((totalSeconds / 3600) % 24).toInt()
    return buildString {
        append(h.pad2()); append(':')
        append(m.pad2()); append(':')
        append(s.pad2()); append('.')
        append(ms.toString().padStart(3, '0'))
    }
}

private fun Int.pad2(): String = if (this < 10) "0$this" else toString()
