package wang.harlon.webview.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import wang.harlon.webview.bridge.JsBridge
import wang.harlon.webview.logpanel.LogStore
import wang.harlon.webview.logpanel.WebViewEnvironment

/**
 * WebView 首屏内容的来源。
 *
 * 区分二者的**唯一理由是页面 origin**：`file://` 页的 origin 是字符串 `"null"`，任何依赖
 * `postMessage(msg, targetOrigin)` 与宿主页握手的第三方 widget（LiveChat 等）在其上都会直接抛
 * `Invalid target origin 'null'` 而初始化失败。[Html] 让内置页带着一个**由调用方指定的
 * https origin** 渲染，从而绕开这一规范级限制。
 */
sealed interface WebViewSource {
    /** 常规远程/本地 URL（`http` / `https` / `file`）。 */
    data class Url(val url: String) : WebViewSource

    /**
     * 直接渲染一段 HTML，并把文档 URL 伪装成 [baseUrl]——页面 origin、相对路径解析、
     * cookie 归属都按 [baseUrl] 走。
     *
     * [baseUrl] 必须是**完整的 http(s) URL**（例如 `https://livechat.pingpongx.com/`）。
     * 该域**不需要真实存在**：两端都只用它定 origin，不会对它发主文档请求。
     *
     * 平台实现：Android `WebView.loadDataWithBaseURL`；iOS `WKWebView.loadSimulatedRequest`
     * （iOS 15+）。
     *
     * ⚠️ 此模式下 [WebViewState.reload] 在 iOS 上不会重放这段 HTML（`loadSimulatedRequest`
     * 没有可重放的真实请求）；需要"重来一次"请重新 [WebViewState.loadHtml]。
     */
    data class Html(val html: String, val baseUrl: String) : WebViewSource
}

class WebViewState internal constructor(
    source: WebViewSource,
    bridgeNamespace: String,
) : RememberObserver {

    private val initialUrl: String = when (source) {
        is WebViewSource.Url -> source.url
        is WebViewSource.Html -> source.baseUrl
    }

    /**
     * Native 消息通道名，由 [bridgeNamespace] 经 [deriveBridgeChannel] 派生。
     */
    internal val bridgeChannel: String = deriveBridgeChannel(bridgeNamespace)

    var currentUrl: String by mutableStateOf(initialUrl)
        private set

    var title: String by mutableStateOf("")
        private set

    var loading: LoadingState by mutableStateOf(LoadingState.Idle)
        private set

    var canGoBack: Boolean by mutableStateOf(false)
        private set

    var canGoForward: Boolean by mutableStateOf(false)
        private set

    private val bridgeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 关联到此 WebViewState 的 JSBridge 实例。在 WebView 实际创建前即可访问、注册 handler。
     * 生命周期与 WebViewState 一致；WebView 重建时配置不丢失。
     */
    val jsBridge: JsBridge = JsBridge.create(bridgeScope, bridgeNamespace)

    /**
     * 日志面板的中央 store；`WebViewConfig.enableLogPanel = true` 时由 [WebViewScreen] 在
     * 首次组合时通过 [enableLogPanel] 创建并 attach 到 [jsBridge]。
     *
     * 关闭路径下保持 null，所有采集点 `state.logStore?.append(...)` 单次 null 检查短路。
     * 开关运行期只读取一次，不支持运行时切换。
     */
    internal var logStore: LogStore? = null
        private set

    /**
     * 日志面板 Environment 区域展示用；由各端 PlatformWebView 在 WebView 创建后
     * 通过 `capture...Environment` 函数写入。iOS UA 异步获取，第一次写入时
     * `webViewVersion` 可能仍为 null，后续到达时整个对象被替换。
     */
    internal var environment: WebViewEnvironment? by mutableStateOf(null)
        private set

    internal fun enableLogPanel() {
        if (logStore != null) return
        val store = LogStore(scope = bridgeScope)
        logStore = store
        jsBridge.attachLogStore(store)
    }

    internal fun setEnvironment(env: WebViewEnvironment) {
        environment = env
    }

    internal var pendingCommand: WebViewCommand? by mutableStateOf(
        when (source) {
            is WebViewSource.Url -> WebViewCommand.LoadUrl(source.url)
            is WebViewSource.Html -> WebViewCommand.LoadHtml(source.html, source.baseUrl)
        },
    )
        private set

    fun goBack() { dispatch(WebViewCommand.GoBack) }
    fun goForward() { dispatch(WebViewCommand.GoForward) }
    fun reload() { dispatch(WebViewCommand.Reload) }
    fun stopLoading() { dispatch(WebViewCommand.StopLoading) }

    fun loadUrl(url: String) {
        if (url.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) return
        dispatch(WebViewCommand.LoadUrl(url))
    }

    /**
     * 以 [baseUrl] 的 origin 渲染 [html]，语义与构造期的 [WebViewSource.Html] 一致（含其注意事项）。
     * [baseUrl] 必须是完整 http(s) URL，否则调用被忽略。
     */
    fun loadHtml(html: String, baseUrl: String) {
        if (html.isEmpty()) return
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return
        dispatch(WebViewCommand.LoadHtml(html, baseUrl))
    }

    private fun dispatch(command: WebViewCommand) {
        pendingCommand = command
    }

    internal fun consumeCommand() {
        pendingCommand = null
    }

    internal fun onLoadStarted(url: String?) {
        if (url != null) currentUrl = url
        loading = LoadingState.Loading
    }

    internal fun onLoadFinished(url: String?) {
        if (url != null) currentUrl = url
        loading = LoadingState.Finished
    }

    internal fun onLoadFailed(code: Int, description: String, failingUrl: String?) {
        loading = LoadingState.Error(code, description, failingUrl)
    }

    internal fun onTitleChanged(value: String?) {
        title = value.orEmpty()
    }

    internal fun onNavigationChanged(canGoBack: Boolean, canGoForward: Boolean) {
        this.canGoBack = canGoBack
        this.canGoForward = canGoForward
    }

    // RememberObserver: 离开 Compose 组合时取消 bridge scope，断开所有挂起的 handler。
    override fun onRemembered() = Unit
    override fun onForgotten() { jsBridge.cancelScope() }
    override fun onAbandoned() { jsBridge.cancelScope() }
}

/**
 * 由 namespace 派生 native channel 名：首字母小写 + `__` 前缀 + `Native` 后缀。
 *
 * 默认 `KmpBridge` → `__kmpBridgeNative`（保持向前兼容）；
 * 自定义 `CustomBridge` → `__customBridgeNative`。
 *
 * 派生自合法 JS 标识符，结果天然也是合法标识符，binder 无需再校验。
 */
internal fun deriveBridgeChannel(namespace: String): String =
    "__" + namespace.replaceFirstChar { it.lowercase() } + "Native"

internal sealed interface WebViewCommand {
    object GoBack : WebViewCommand
    object GoForward : WebViewCommand
    object Reload : WebViewCommand
    object StopLoading : WebViewCommand
    data class LoadUrl(val url: String) : WebViewCommand
    data class LoadHtml(val html: String, val baseUrl: String) : WebViewCommand
}

/**
 * 创建并 remember 一个 [WebViewState]。
 *
 * @param initialUrl 首次进入时加载的 URL。
 * @param bridgeNamespace JS 端访问 bridge 的全局名，决定 `window.{bridgeNamespace}` 与就绪事件
 *   `{bridgeNamespace}Ready` 的字面量。默认 `"KmpBridge"`。需是合法 JS 标识符
 *   （`[A-Za-z_$][A-Za-z0-9_$]*`），非法值会在构造期抛 [IllegalArgumentException]。
 *   Native 消息通道名由此值派生为 `__{首字母小写}Native`（例如 `KmpBridge` → `__kmpBridgeNative`、
 *   `CustomBridge` → `__customBridgeNative`），暂不开放单独自定义。
 *   场景：多 App 共享 SDK 用自家品牌名、debug/prod 构建隔离、回避 H5 上已被占用的默认名等。
 */
@Composable
fun rememberWebViewState(
    initialUrl: String,
    bridgeNamespace: String = "KmpBridge",
): WebViewState =
    remember(initialUrl, bridgeNamespace) {
        WebViewState(WebViewSource.Url(initialUrl), bridgeNamespace)
    }

/**
 * [rememberWebViewState] 的 source 版重载：除 URL 外还能用 [WebViewSource.Html] 让内置页
 * 带着指定 https origin 渲染（动机与限制见 [WebViewSource]）。
 *
 * @param source 首屏内容来源；变更时重建 state（与 URL 版一致的 remember key 语义）。
 * @param bridgeNamespace 同 [rememberWebViewState] 的同名参数。
 */
@Composable
fun rememberWebViewState(
    source: WebViewSource,
    bridgeNamespace: String = "KmpBridge",
): WebViewState =
    remember(source, bridgeNamespace) {
        WebViewState(source, bridgeNamespace)
    }
