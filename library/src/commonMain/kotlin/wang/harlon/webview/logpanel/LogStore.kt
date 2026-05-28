package wang.harlon.webview.logpanel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 日志面板的中央数据持有者：环形 buffer + StateFlow。
 *
 * `enableLogPanel = false` 时由 [wang.harlon.webview.core.WebViewState] 持空引用，
 * 所有写入路径走 `logStore?.appendAsync(...)` 短路；存在性即零开销开关。
 *
 * Compose UI 订阅 [entries] —— 每次 append/clear 都发射新 List 引用，
 * LazyColumn 即可 recompose。
 *
 * 写入入口：
 *   - [append] / [clear] 是 suspend，串行化在 [mutex] 内，供单测和已经在协程里的调用方
 *   - [appendAsync] / [clearAsync] 用注入的 [scope] 把 suspend 调用 fire-and-forget，
 *     供 Android JavaScriptInterface 线程 / iOS WKScriptMessage 主线程 / WebViewClient 回调使用
 */
@OptIn(ExperimentalTime::class)
internal class LogStore(
    private val scope: CoroutineScope,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var nextId = 0L
    private val buffer = ArrayDeque<WebViewLog>(capacity)
    private val _entries = MutableStateFlow<List<WebViewLog>>(emptyList())
    val entries: StateFlow<List<WebViewLog>> = _entries

    suspend fun append(
        source: WebViewLog.Source,
        level: WebViewLog.Level,
        message: String,
        detail: String? = null,
    ) {
        mutex.withLock {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(
                WebViewLog(
                    id = nextId++,
                    timestamp = now(),
                    source = source,
                    level = level,
                    message = truncate(message, MESSAGE_MAX),
                    detail = detail?.let { truncate(it, DETAIL_MAX) },
                )
            )
            _entries.value = buffer.toList()
        }
    }

    suspend fun clear() {
        mutex.withLock {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    fun appendAsync(
        source: WebViewLog.Source,
        level: WebViewLog.Level,
        message: String,
        detail: String? = null,
    ) {
        scope.launch { append(source, level, message, detail) }
    }

    fun clearAsync() {
        scope.launch { clear() }
    }

    /** 把 shim 协议消息 fire-and-forget 写入，供平台 binder 在收到 native channel 消息时调用。 */
    fun ingestAsync(raw: String) {
        scope.launch { ingestShimMessage(raw) }
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
        const val MESSAGE_MAX = 256
        const val DETAIL_MAX = 4096
        private const val TRUNCATED = "... [truncated]"

        internal fun truncate(s: String, max: Int): String =
            if (s.length <= max) s else s.take(max - TRUNCATED.length) + TRUNCATED
    }
}
