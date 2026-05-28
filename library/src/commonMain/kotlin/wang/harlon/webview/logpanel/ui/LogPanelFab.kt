package wang.harlon.webview.logpanel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 悬浮入口按钮：默认右下角，可拖动；未读 error > 0 时右上角显示数字 badge。
 *
 * offset 用 [rememberSaveable] 持久化跨 Activity 重建（spec §4 边界场景）。
 */
@Composable
internal fun BoxScope.LogPanelFab(
    unreadErrors: Int,
    onClick: () -> Unit,
) {
    var offset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offset = Offset(offset.x + drag.x, offset.y + drag.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
            .size(48.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BadgedBox(
            badge = {
                if (unreadErrors > 0) {
                    Badge(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White,
                    ) {
                        Text(if (unreadErrors > 99) "99+" else unreadErrors.toString())
                    }
                }
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = "WebView logs",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

private val OffsetSaver: Saver<Offset, Any> = Saver(
    save = { listOf(it.x, it.y) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        val l = it as List<Float>
        Offset(l[0], l[1])
    },
)
