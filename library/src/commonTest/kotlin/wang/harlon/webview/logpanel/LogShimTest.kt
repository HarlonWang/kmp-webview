package wang.harlon.webview.logpanel

import kotlin.test.Test
import kotlin.test.assertTrue

class LogShimTest {

    private val shim = buildLogShim(LOG_CHANNEL)

    @Test
    fun has_install_guard_to_avoid_double_injection() {
        assertTrue(shim.contains("__kmpLogShimInstalled"))
    }

    @Test
    fun wraps_all_four_console_levels() {
        // 用数组定义 + 循环包装，4 个 level 都在数组里
        assertTrue(
            Regex("""\['log',\s*'info',\s*'warn',\s*'error']""").containsMatchIn(shim),
            "console levels array missing one of log/info/warn/error: \n$shim"
        )
        assertTrue(shim.contains("console[level]"), "console[level] override missing")
    }

    @Test
    fun listens_window_error() {
        assertTrue(shim.contains("addEventListener('error'"))
        assertTrue(shim.contains("'jserror'"))
    }

    @Test
    fun listens_unhandledrejection() {
        assertTrue(shim.contains("addEventListener('unhandledrejection'"))
        assertTrue(shim.contains("'rejection'"))
    }

    @Test
    fun uses_runtime_platform_branch_for_channel() {
        // Android 分支：window.$channel.postMessage
        assertTrue(shim.contains("window.$LOG_CHANNEL && window.$LOG_CHANNEL.postMessage"))
        // iOS 分支：webkit.messageHandlers.$channel.postMessage
        assertTrue(shim.contains("window.webkit.messageHandlers.$LOG_CHANNEL"))
    }

    @Test
    fun all_post_calls_wrapped_in_try_catch() {
        // 所有 post() 调用点都不应该让异常逃出 shim 影响业务 console
        val safeStringifyHasFallback = shim.contains("try { return JSON.stringify(v)")
        assertTrue(safeStringifyHasFallback, "safeStringify must fallback on JSON.stringify failure")
        // 各 listener / wrapper 都有 try/catch (_) 包裹（粗略统计）
        val tryCount = Regex("""\btry\s*\{""").findAll(shim).count()
        assertTrue(tryCount >= 4, "expected ≥4 try-blocks (post, safeStringify, error, rejection); found $tryCount")
    }
}
