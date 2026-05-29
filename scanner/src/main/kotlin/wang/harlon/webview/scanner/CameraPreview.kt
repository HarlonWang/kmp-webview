package wang.harlon.webview.scanner

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors

/**
 * CameraX 相机预览 + 帧分析的 Compose 封装。
 *
 * 用 [LifecycleCameraController] 绑定到宿主 Activity 的生命周期，只启用 IMAGE_ANALYSIS 用例，
 * 把每帧交给 [analyzer]。[torchOn] 控制手电筒。
 */
@Composable
internal fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    torchOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = remember(context) { context.findComponentActivity() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisAnalyzer(analysisExecutor, analyzer)
        }
    }

    LaunchedEffect(controller, lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
    }
    LaunchedEffect(torchOn) {
        controller.enableTorch(torchOn)
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.unbind()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.controller = controller
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
    )
}

private tailrec fun Context.findComponentActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> error("QrScannerScreen 必须承载在 ComponentActivity 内")
}
