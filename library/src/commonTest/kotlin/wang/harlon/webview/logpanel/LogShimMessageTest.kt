package wang.harlon.webview.logpanel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogShimMessageTest {

    @Test
    fun parses_console_message_with_args() {
        val json = """{"kind":"console","level":"warn","args":["hello","world"]}"""
        val m = parseLogShimMessage(json)!!
        assertEquals("console", m.kind)
        assertEquals("warn", m.level)
        assertEquals(listOf("hello", "world"), m.args)
        assertNull(m.message)
    }

    @Test
    fun parses_jserror_with_full_fields() {
        val json = """{"kind":"jserror","message":"oops","src":"app.js","line":42,"col":7,"stack":"x\n at y"}"""
        val m = parseLogShimMessage(json)!!
        assertEquals("jserror", m.kind)
        assertEquals("oops", m.message)
        assertEquals("app.js", m.src)
        assertEquals(42L, m.line)
        assertEquals(7L, m.col)
        assertEquals("x\n at y", m.stack)
    }

    @Test
    fun parses_rejection_with_nullable_fields() {
        val json = """{"kind":"rejection","message":null,"stack":null}"""
        val m = parseLogShimMessage(json)!!
        assertEquals("rejection", m.kind)
        assertNull(m.message)
        assertNull(m.stack)
    }

    @Test
    fun empty_args_array() {
        val json = """{"kind":"console","level":"log","args":[]}"""
        val m = parseLogShimMessage(json)!!
        assertEquals(emptyList(), m.args)
    }

    @Test
    fun handles_escapes_in_strings() {
        val json = """{"kind":"console","level":"log","args":["line1\nline2","quote\""]}"""
        val m = parseLogShimMessage(json)!!
        assertEquals(listOf("line1\nline2", "quote\""), m.args)
    }

    @Test
    fun skips_unknown_fields() {
        val json = """{"kind":"console","level":"log","args":["a"],"extra":{"nested":true},"junk":[1,2,3]}"""
        val m = parseLogShimMessage(json)!!
        assertEquals("console", m.kind)
        assertEquals(listOf("a"), m.args)
    }

    @Test
    fun returns_null_for_invalid_json() {
        assertNull(parseLogShimMessage(""))
        assertNull(parseLogShimMessage("not json"))
        assertNull(parseLogShimMessage("""{"kind":"unterminated"""))
    }

    @Test
    fun returns_null_when_kind_missing() {
        assertNull(parseLogShimMessage("""{"level":"log"}"""))
    }
}
