package wang.harlon.webview.logpanel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogStoreTest {

    @Test
    fun append_emits_in_order_with_increasing_ids() = runTest {
        var fakeNow = 1000L
        val store = LogStore(scope = this, capacity = 10, now = { fakeNow++ })

        store.append(WebViewLog.Source.Console, WebViewLog.Level.Info, "a")
        store.append(WebViewLog.Source.JsBridge, WebViewLog.Level.Error, "b")

        val entries = store.entries.value
        assertEquals(2, entries.size)
        assertEquals(0L, entries[0].id)
        assertEquals(1L, entries[1].id)
        assertEquals(1000L, entries[0].timestamp)
        assertEquals(1001L, entries[1].timestamp)
        assertEquals("a", entries[0].message)
        assertEquals("b", entries[1].message)
    }

    @Test
    fun capacity_overflow_drops_oldest_keeps_newest() = runTest {
        val store = LogStore(scope = this, capacity = 3, now = { 0L })
        repeat(5) { i ->
            store.append(WebViewLog.Source.Console, WebViewLog.Level.Info, "msg$i")
        }
        val entries = store.entries.value
        assertEquals(3, entries.size)
        assertEquals(listOf("msg2", "msg3", "msg4"), entries.map { it.message })
        // ids 仍单调递增，未因 drop 重置
        assertEquals(listOf(2L, 3L, 4L), entries.map { it.id })
    }

    @Test
    fun clear_empties_buffer_but_keeps_id_monotonic() = runTest {
        val store = LogStore(scope = this, capacity = 10, now = { 0L })
        store.append(WebViewLog.Source.Console, WebViewLog.Level.Info, "a")
        store.append(WebViewLog.Source.Console, WebViewLog.Level.Info, "b")
        store.clear()
        assertEquals(emptyList(), store.entries.value)

        store.append(WebViewLog.Source.Console, WebViewLog.Level.Info, "c")
        val entries = store.entries.value
        assertEquals(1, entries.size)
        assertEquals(2L, entries[0].id)  // 0,1 已用，不复用
    }

    @Test
    fun message_truncates_when_over_max() = runTest {
        val store = LogStore(scope = this, capacity = 10, now = { 0L })
        val long = "x".repeat(LogStore.MESSAGE_MAX + 100)
        store.append(WebViewLog.Source.Console, WebViewLog.Level.Info, long)
        val msg = store.entries.value[0].message
        assertEquals(LogStore.MESSAGE_MAX, msg.length)
        assertTrue(msg.endsWith("... [truncated]"))
    }

    @Test
    fun detail_truncates_when_over_max() = runTest {
        val store = LogStore(scope = this, capacity = 10, now = { 0L })
        val long = "y".repeat(LogStore.DETAIL_MAX + 100)
        store.append(WebViewLog.Source.JsException, WebViewLog.Level.Error, "boom", long)
        val detail = store.entries.value[0].detail!!
        assertEquals(LogStore.DETAIL_MAX, detail.length)
        assertTrue(detail.endsWith("... [truncated]"))
    }

    @Test
    fun short_content_not_truncated() = runTest {
        val store = LogStore(scope = this, capacity = 10, now = { 0L })
        store.append(WebViewLog.Source.Console, WebViewLog.Level.Info, "short", "also short")
        val entry = store.entries.value[0]
        assertEquals("short", entry.message)
        assertEquals("also short", entry.detail)
    }

    @Test
    fun null_detail_stays_null() = runTest {
        val store = LogStore(scope = this, capacity = 10, now = { 0L })
        store.append(WebViewLog.Source.Console, WebViewLog.Level.Info, "no detail")
        assertEquals(null, store.entries.value[0].detail)
    }
}
