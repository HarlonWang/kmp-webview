package wang.harlon.webview.androidapp

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import wang.harlon.webview.WebViewScreen
import wang.harlon.webview.core.WebViewState
import wang.harlon.webview.core.rememberWebViewState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Demo 用浅色 MaterialTheme，强制 status bar / nav bar 深色图标，避免在浅色背景上看不见。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            DemoApp(onClose = { finish() })
        }
    }
}

private const val INITIAL_URL = "file:///android_asset/device_capabilities_test.html"

@Composable
private fun DemoApp(onClose: () -> Unit) {
    MaterialTheme {
        Surface {
            val state = rememberWebViewState(INITIAL_URL)

            LaunchedEffect(state) {
                state.jsBridge.registerHandler("echo") { params ->
                    // 直接回传 params 作为 result（params 已是 JSON 字符串）
                    params
                }
                state.jsBridge.registerHandler("getDeviceInfo") { _ ->
                    """{"manufacturer":"${Build.MANUFACTURER}","model":"${Build.MODEL}","sdk":${Build.VERSION.SDK_INT}}"""
                }

                // 每 3 秒往页面推一个 tick 事件
                var n = 0
                while (true) {
                    delay(3000)
                    state.jsBridge.emit("demo.tick", """{"n":${++n}}""")
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    // Android 15+ 默认 edge-to-edge：在外层消费 status bar inset，避免 URL bar 顶到状态栏。
                    // WebViewScreen 内部 Scaffold 已处理其余 system bars，子树看到 status bar 已消费不会重复 padding。
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                UrlBar(state = state)
                WebViewScreen(
                    state = state,
                    onCloseRequest = onClose,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun UrlBar(state: WebViewState) {
    var input by rememberSaveable { mutableStateOf(state.currentUrl) }
    // WebView 自身导航（点链接、redirect、reload）后回写 TextField；
    // 输入中用户被覆盖是已知小竞态，demo 场景可忽略。
    LaunchedEffect(state.currentUrl) { input = state.currentUrl }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { state.loadUrl(normalizeUrl(input)) }),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { state.loadUrl(normalizeUrl(input)) }) {
            Text("打开")
        }
    }
}

private fun normalizeUrl(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return s
    if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://")) return s
    return "https://$s"
}
