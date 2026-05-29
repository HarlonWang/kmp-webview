package wang.harlon.webview.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 全屏二维码扫码界面。
 *
 * 自动申请相机权限并打开相机；识别到**合法 http/https URL** 时回调 [onResult]，
 * 接入方据此打开 WebView（如 `rememberWebViewState(url)` + `WebViewScreen`）。
 * 非网页内容（纯文本 / `tel:` / `javascript:` 等）会提示并继续扫描，不回调。
 * 用户返回或在权限被拒后放弃时回调 [onCancel]。
 *
 * @param onResult 识别到合法 http/https URL 时回调，参数为该网址
 * @param onCancel 用户返回 / 放弃扫码时回调
 */
@Composable
fun QrScannerScreen(
    onResult: (url: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasPermission) {
            ScannerContent(onResult = onResult, onCancel = onCancel)
        } else {
            PermissionPlaceholder(
                denied = permissionDenied,
                onRetry = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun ScannerContent(
    onResult: (url: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    var torchOn by remember { mutableStateOf(false) }

    val analyzer = remember {
        ZxingAnalyzer { text ->
            val url = UrlValidator.validate(text)
            scope.launch(Dispatchers.Main) {
                if (url != null) {
                    currentOnResult(url)
                } else {
                    Toast.makeText(
                        context,
                        "非有效网址，请对准网页二维码",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { analyzer.stop() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            analyzer = analyzer,
            torchOn = torchOn,
            modifier = Modifier.fillMaxSize(),
        )
        ScannerOverlay(modifier = Modifier.fillMaxSize())
        TopControls(
            torchOn = torchOn,
            onToggleTorch = { torchOn = !torchOn },
            onCancel = onCancel,
        )
        Text(
            text = "将二维码放入框内，即可自动扫描",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 96.dp, start = 24.dp, end = 24.dp),
        )
    }
}

@Composable
private fun TopControls(
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(8.dp),
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        IconButton(
            onClick = onToggleTorch,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(
                imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ScannerOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanlineProgress",
    )

    val mask = Color.Black.copy(alpha = 0.5f)
    val frameColor = Color.White
    val lineColor = Color(0xFF4CAF50)

    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height) * 0.7f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f

        // 取景框外四块半透明遮罩
        drawRect(color = mask, size = Size(size.width, top))
        drawRect(
            color = mask,
            topLeft = Offset(0f, top + side),
            size = Size(size.width, size.height - top - side),
        )
        drawRect(color = mask, topLeft = Offset(0f, top), size = Size(left, side))
        drawRect(
            color = mask,
            topLeft = Offset(left + side, top),
            size = Size(left, side),
        )

        // 取景框边框
        drawRect(
            color = frameColor,
            topLeft = Offset(left, top),
            size = Size(side, side),
            style = Stroke(width = 3.dp.toPx()),
        )

        // 扫描线
        val lineY = top + side * progress
        drawLine(
            color = lineColor,
            start = Offset(left, lineY),
            end = Offset(left + side, lineY),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

@Composable
private fun PermissionPlaceholder(
    denied: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (denied) {
                "扫码需要相机权限。请授予权限后重试，或前往系统设置开启。"
            } else {
                "正在请求相机权限…"
            },
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (denied) {
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text("重试")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("去设置")
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("取消")
            }
        }
    }
}

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
