package wang.harlon.webview.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
            WebViewScreen(state = state, onCloseRequest = onClose)
        }
    }
}
