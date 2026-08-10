package wang.harlon.webview.logpanel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeConsoleTest {

    @Test
    fun uncaught_message_formats_with_source_and_line() {
        assertEquals(
            "Uncaught ReferenceError: process is not defined (https://cdn.example.com/main.js:1)",
            nativeUncaughtConsoleMessage(
                message = "Uncaught ReferenceError: process is not defined",
                sourceId = "https://cdn.example.com/main.js",
                lineNumber = 1,
            ),
        )
    }

    @Test
    fun missing_source_omits_position_and_invalid_line_omits_line() {
        assertEquals(
            "Uncaught TypeError: x",
            nativeUncaughtConsoleMessage("Uncaught TypeError: x", sourceId = null, lineNumber = 3),
        )
        assertEquals(
            "Uncaught TypeError: x (app.js)",
            nativeUncaughtConsoleMessage("Uncaught TypeError: x", sourceId = "app.js", lineNumber = 0),
        )
    }

    @Test
    fun non_uncaught_console_error_is_filtered() {
        // 页面 console.error 由 shim 的 console patch 采集，native 通道放行会重复入面板
        assertNull(nativeUncaughtConsoleMessage("boom happened", "app.js", 1))
        assertNull(nativeUncaughtConsoleMessage(null, "app.js", 1))
        // "Uncaught" 后必须带空格分隔，避免误吞形如 "UncaughtFoo..." 的普通文本
        assertNull(nativeUncaughtConsoleMessage("UncaughtFoo", "app.js", 1))
    }
}
