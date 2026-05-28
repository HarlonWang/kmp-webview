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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 悬浮入口按钮：默认右下角，可拖动；未读 error > 0 时右上角显示数字 badge。
 *
 * offset 用 [rememberSaveable] 持久化跨 Activity 重建（spec §4 边界场景）。
 *
 * 拖动边界：以默认 BottomEnd 位置为原点，offset.x ∈ [-(hostW - fabW - 2*padding), 0]，
 * offset.y 同理。Host 尺寸由 [LogPanelHost] 用 `Modifier.onSizeChanged` 实测后传入；
 * 横竖屏切换 / IME 弹出导致 host 收缩时通过 LaunchedEffect 重新 clamp，避免按钮被裁到屏幕外。
 */
@Composable
internal fun BoxScope.LogPanelFab(
    unreadErrors: Int,
    hostSize: IntSize,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val paddingPx = with(density) { FAB_PADDING.toPx() }
    val fabSizePx = with(density) { FAB_SIZE.toPx() }

    var offset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

    val minX = remember(hostSize.width, fabSizePx, paddingPx) {
        -((hostSize.width - fabSizePx - paddingPx * 2).coerceAtLeast(0f))
    }
    val minY = remember(hostSize.height, fabSizePx, paddingPx) {
        -((hostSize.height - fabSizePx - paddingPx * 2).coerceAtLeast(0f))
    }

    // host 尺寸变化（旋转、IME 弹出）时把保存的 offset clamp 回有效范围。
    LaunchedEffect(minX, minY) {
        offset = Offset(offset.x.coerceIn(minX, 0f), offset.y.coerceIn(minY, 0f))
    }

    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(FAB_PADDING)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .pointerInput(minX, minY) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offset = Offset(
                        (offset.x + drag.x).coerceIn(minX, 0f),
                        (offset.y + drag.y).coerceIn(minY, 0f),
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
            .size(FAB_SIZE)
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

private val FAB_SIZE = 48.dp
private val FAB_PADDING = 16.dp

private val OffsetSaver: Saver<Offset, Any> = Saver(
    save = { listOf(it.x, it.y) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        val l = it as List<Float>
        Offset(l[0], l[1])
    },
)
