package wang.harlon.webview.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import wang.harlon.webview.core.LoadingState

@Composable
internal fun WebViewProgressBar(loading: LoadingState) {
    val targetAlpha = if (loading is LoadingState.Loading) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 200),
        label = "webview-progress-alpha",
    )

    if (alpha > 0f) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().alpha(alpha),
        )
    }
}
