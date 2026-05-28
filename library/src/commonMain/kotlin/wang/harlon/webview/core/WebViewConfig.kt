package wang.harlon.webview.core

import androidx.compose.runtime.Composable

data class WebViewConfig(
    val titleOverride: String? = null,
    val userAgent: UserAgentStrategy = UserAgentStrategy.Default,
    val showTopBar: Boolean = true,
    val showBottomBar: Boolean = true,
    val showProgressBar: Boolean = true,
    val allowFileChooser: Boolean = true,
    val allowCameraCapture: Boolean = true,
    val allowMediaCapture: Boolean = true,
    // Android setWebContentsDebuggingEnabled 是进程级开关 / iOS isInspectable 仅 16.4+；release 应留 false
    val enableRemoteDebugging: Boolean = false,
    // 启用设备内日志面板（悬浮 FAB + 抽屉），采集 console / JS 异常 / WebView 错误 / JSBridge 调用链。
    // 仅 debug/灰度环境开启；启用时不要在生产暴露给终端用户（detail 字段含 bridge payload，可能携带敏感信息）。
    val enableLogPanel: Boolean = false,
    val errorContent: (@Composable (LoadingState.Error, retry: () -> Unit) -> Unit)? = null,
)
