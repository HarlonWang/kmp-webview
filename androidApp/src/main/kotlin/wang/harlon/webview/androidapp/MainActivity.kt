package wang.harlon.webview.androidapp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import wang.harlon.webview.WebViewScreen
import wang.harlon.webview.core.rememberWebViewState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DemoApp(onClose = { finish() })
        }
    }
}

@Composable
private fun DemoApp(onClose: () -> Unit) {
    MaterialTheme {
        Surface {
            val state = rememberWebViewState("file:///android_asset/device_capabilities_test.html")

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

            WebViewScreen(state = state, onCloseRequest = onClose)
        }
    }
}
