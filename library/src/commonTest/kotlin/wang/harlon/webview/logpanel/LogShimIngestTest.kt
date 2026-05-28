package wang.harlon.webview.logpanel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogShimIngestTest {

    @Test
    fun console_warn_maps_to_warn_level() = runTest {
        val store = LogStore(scope = this, now = { 0L })
        store.ingestShimMessage("""{"kind":"console","level":"warn","args":["bad"]}""")
        val log = store.entries.value.single()
        assertEquals(WebViewLog.Source.Console, log.source)
        assertEquals(WebViewLog.Level.Warn, log.level)
        assertEquals("[warn] bad", log.message)
        assertNull(log.detail)
    }

    @Test
    fun console_log_short_no_detail_long_keeps_detail() = runTest {
        val store = LogStore(scope = this, now = { 0L })
        store.ingestShimMessage("""{"kind":"console","level":"log","args":["short"]}""")
        assertNull(store.entries.value.last().detail)

        val long = "x".repeat(300)
        store.ingestShimMessage("""{"kind":"console","level":"log","args":["$long"]}""")
        assertTrue(store.entries.value.last().detail != null)
    }

    @Test
    fun jserror_formats_position() = runTest {
        val store = LogStore(scope = this, now = { 0L })
        store.ingestShimMessage(
            """{"kind":"jserror","message":"TypeError: x","src":"app.js","line":42,"col":7,"stack":"at y"}"""
        )
        val log = store.entries.value.single()
        assertEquals(WebViewLog.Source.JsException, log.source)
        assertEquals(WebViewLog.Level.Error, log.level)
        assertEquals("Uncaught TypeError: x (app.js:42:7)", log.message)
        assertEquals("at y", log.detail)
    }

    @Test
    fun jserror_without_position_fields() = runTest {
        val store = LogStore(scope = this, now = { 0L })
        store.ingestShimMessage(
            """{"kind":"jserror","message":"oops","src":null,"line":null,"col":null,"stack":null}"""
        )
        val log = store.entries.value.single()
        assertEquals("Uncaught oops", log.message)
        assertNull(log.detail)
    }

    @Test
    fun rejection_maps_to_jsexception_error() = runTest {
        val store = LogStore(scope = this, now = { 0L })
        store.ingestShimMessage("""{"kind":"rejection","message":"Promise rejected","stack":"stack"}""")
        val log = store.entries.value.single()
        assertEquals(WebViewLog.Source.JsException, log.source)
        assertEquals(WebViewLog.Level.Error, log.level)
        assertEquals("Unhandled rejection: Promise rejected", log.message)
        assertEquals("stack", log.detail)
    }

    @Test
    fun unparsable_message_logged_verbose_with_raw_detail() = runTest {
        val store = LogStore(scope = this, now = { 0L })
        store.ingestShimMessage("not json at all")
        val log = store.entries.value.single()
        assertEquals(WebViewLog.Level.Verbose, log.level)
        assertEquals("shim: unparsable message", log.message)
        assertEquals("not json at all", log.detail)
    }

    @Test
    fun unknown_kind_logged_verbose() = runTest {
        val store = LogStore(scope = this, now = { 0L })
        store.ingestShimMessage("""{"kind":"surprise","msg":"hi"}""")
        val log = store.entries.value.single()
        assertEquals(WebViewLog.Level.Verbose, log.level)
        assertTrue(log.message.contains("'surprise'"))
    }
}
