@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package wang.harlon.webview.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * 收集 evaluator 调用，用于断言 SDK 发给 WebView 的 JS。
 */
private class EvaluatorRecorder {
    val calls = mutableListOf<String>()
    val asLambda: (String) -> Unit = { calls += it }
}

class JsBridgeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun newBridge(recorder: EvaluatorRecorder = EvaluatorRecorder()): Pair<JsBridge, EvaluatorRecorder> {
        val bridge = JsBridge.createForTest()
        bridge.attachEvaluator(recorder.asLambda)
        return bridge to recorder
    }

    // ───────── handler 路由 ─────────

    @Test
    fun handlerNotFoundReturnsHandlerNotFound() = runTest {
        val (bridge, recorder) = newBridge()

        bridge.dispatchIncoming("""{"id":1,"method":"missing","params":null}""") { "https://x.com" }
        advanceUntilIdle()

        val js = recorder.calls.single()
        assertTrue(js.contains("__resolve(1, false"), js)
        assertTrue(js.contains("HANDLER_NOT_FOUND"), js)
    }

    @Test
    fun handlerReturnsJsonStringResolvesTrue() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("echo") { params -> params }

        bridge.dispatchIncoming("""{"id":7,"method":"echo","params":"{\"a\":1}"}""") { "https://x.com" }
        advanceUntilIdle()

        val js = recorder.calls.single()
        assertTrue(js.contains("__resolve(7, true"), js)
        assertTrue(js.contains("""{\"a\":1}"""), js)
    }

    @Test
    fun handlerReturnsNullResolvesTrueWithNullPayload() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("noop") { null }

        bridge.dispatchIncoming("""{"id":2,"method":"noop","params":null}""") { "https://x.com" }
        advanceUntilIdle()

        assertEquals("window.KmpBridge && window.KmpBridge.__resolve(2, true, null)", recorder.calls.single())
    }

    @Test
    fun handlerThrowingJsBridgeExceptionPropagatesCode() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("share") { throw JsBridgeException("INVALID_PARAMS", "missing url") }

        bridge.dispatchIncoming("""{"id":3,"method":"share","params":null}""") { "https://x.com" }
        advanceUntilIdle()

        val js = recorder.calls.single()
        assertTrue(js.contains("__resolve(3, false"), js)
        assertTrue(js.contains("INVALID_PARAMS"), js)
        assertTrue(js.contains("missing url"), js)
    }

    @Test
    fun handlerThrowingGenericExceptionMapsToHandlerError() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("boom") { throw IllegalStateException("kaboom") }

        bridge.dispatchIncoming("""{"id":4,"method":"boom","params":null}""") { "https://x.com" }
        advanceUntilIdle()

        val js = recorder.calls.single()
        assertTrue(js.contains("__resolve(4, false"), js)
        assertTrue(js.contains("HANDLER_ERROR"), js)
        assertTrue(js.contains("kaboom"), js)
    }

    @Test
    fun handlerReceivesParamsAsString() = runTest {
        val (bridge, _) = newBridge()
        val received = CompletableDeferred<String?>()
        bridge.registerHandler("capture") { params ->
            received.complete(params)
            null
        }

        bridge.dispatchIncoming("""{"id":1,"method":"capture","params":"{\"k\":\"v\"}"}""") { "https://x.com" }
        advanceUntilIdle()

        assertEquals("""{"k":"v"}""", received.await())
    }

    @Test
    fun handlerWithNullParamsReceivesNull() = runTest {
        val (bridge, _) = newBridge()
        val received = CompletableDeferred<String?>()
        bridge.registerHandler("capture") { params ->
            received.complete(params)
            null
        }

        bridge.dispatchIncoming("""{"id":1,"method":"capture","params":null}""") { "https://x.com" }
        advanceUntilIdle()

        assertNull(received.await())
    }

    // ───────── 重复注册 ─────────

    @Test
    fun reRegisteringReplacesPreviousHandler() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { "\"old\"" }
        bridge.registerHandler("m") { "\"new\"" }

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "https://x.com" }
        advanceUntilIdle()

        val js = recorder.calls.single()
        assertTrue(js.contains("\\\"new\\\""), "expected new handler payload, got: $js")
    }

    @Test
    fun unregisterRemovesHandler() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { "\"v\"" }
        bridge.unregisterHandler("m")

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "https://x.com" }
        advanceUntilIdle()

        assertTrue(recorder.calls.single().contains("HANDLER_NOT_FOUND"))
    }

    // ───────── emit ─────────

    @Test
    fun emitProducesEmitCallWithEscapedPayload() {
        val (bridge, recorder) = newBridge()

        bridge.emit("auth.changed", """{"loggedIn":true}""")

        assertEquals(
            """window.KmpBridge && window.KmpBridge.__emit("auth.changed", "{\"loggedIn\":true}")""",
            recorder.calls.single(),
        )
    }

    @Test
    fun emitWithNullPayload() {
        val (bridge, recorder) = newBridge()

        bridge.emit("ping")

        assertEquals(
            """window.KmpBridge && window.KmpBridge.__emit("ping", null)""",
            recorder.calls.single(),
        )
    }

    // ───────── origin 白名单 ─────────

    @Test
    fun nullOriginWhitelistAllowsAny() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { "\"ok\"" }

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "https://anywhere.com/p" }
        advanceUntilIdle()

        assertTrue(recorder.calls.single().contains("__resolve(1, true"))
    }

    @Test
    fun exactOriginMatch() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { "\"ok\"" }
        bridge.setAllowedOrigins(setOf("https://example.com"))

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "https://example.com/foo" }
        advanceUntilIdle()

        assertTrue(recorder.calls.single().contains("__resolve(1, true"))
    }

    @Test
    fun originMismatchRejected() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { "\"ok\"" }
        bridge.setAllowedOrigins(setOf("https://example.com"))

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "https://evil.com/p" }
        advanceUntilIdle()

        val js = recorder.calls.single()
        assertTrue(js.contains("ORIGIN_DENIED"), js)
    }

    @Test
    fun wildcardSubdomainMatch() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { "\"ok\"" }
        bridge.setAllowedOrigins(setOf("https://*.example.com"))

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "https://a.example.com/x" }
        advanceUntilIdle()
        bridge.dispatchIncoming("""{"id":2,"method":"m","params":null}""") { "https://b.c.example.com/y" }
        advanceUntilIdle()

        assertEquals(2, recorder.calls.size)
        assertTrue(recorder.calls[0].contains("__resolve(1, true"))
        assertTrue(recorder.calls[1].contains("__resolve(2, true"))
    }

    @Test
    fun wildcardSubdomainDoesNotMatchApex() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { "\"ok\"" }
        bridge.setAllowedOrigins(setOf("https://*.example.com"))

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "https://example.com/x" }
        advanceUntilIdle()

        assertTrue(recorder.calls.single().contains("ORIGIN_DENIED"))
    }

    @Test
    fun originSchemeMustMatch() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { "\"ok\"" }
        bridge.setAllowedOrigins(setOf("https://example.com"))

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "http://example.com/p" }
        advanceUntilIdle()

        assertTrue(recorder.calls.single().contains("ORIGIN_DENIED"))
    }

    @Test
    fun missingCurrentUrlIsDeniedWhenWhitelistSet() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { "\"ok\"" }
        bridge.setAllowedOrigins(setOf("https://example.com"))

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { null }
        advanceUntilIdle()

        assertTrue(recorder.calls.single().contains("ORIGIN_DENIED"))
    }

    // ───────── evaluator 解绑 ─────────

    @Test
    fun detachedEvaluatorSuppressesResolveAndEmit() = runTest {
        val recorder = EvaluatorRecorder()
        val (bridge, _) = newBridge(recorder)
        bridge.registerHandler("m") { "\"v\"" }
        bridge.detachEvaluator()

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "https://x.com" }
        advanceUntilIdle()
        bridge.emit("e")

        assertEquals(0, recorder.calls.size)
    }

    // ───────── 不规范输入 ─────────

    @Test
    fun malformedJsonIsIgnored() = runTest {
        val (bridge, recorder) = newBridge()

        bridge.dispatchIncoming("""{not json""") { "https://x.com" }
        advanceUntilIdle()

        assertEquals(0, recorder.calls.size)
    }

    @Test
    fun missingFieldsIgnored() = runTest {
        val (bridge, recorder) = newBridge()

        bridge.dispatchIncoming("""{"foo":"bar"}""") { "https://x.com" }
        advanceUntilIdle()

        assertEquals(0, recorder.calls.size)
    }

    // ───────── handler 异常包含特殊字符 ─────────

    @Test
    fun errorMessageWithQuotesEscapedProperly() = runTest {
        val (bridge, recorder) = newBridge()
        bridge.registerHandler("m") { throw RuntimeException("""bad "quoted" value""") }

        bridge.dispatchIncoming("""{"id":1,"method":"m","params":null}""") { "https://x.com" }
        advanceUntilIdle()

        // 三层编码：message 字符串 → 进 JSON 对象（引号转 \"）→ 整个 JSON 对象再进 JS 字符串
        // 字面量（\ 翻倍、" 转义）。验证完整产出 + JS 结构完整性。
        val expected = "window.KmpBridge && window.KmpBridge.__resolve(1, false, " +
            "\"{\\\"code\\\":\\\"HANDLER_ERROR\\\",\\\"message\\\":" +
            "\\\"bad \\\\\\\"quoted\\\\\\\" value\\\"}\")"
        assertEquals(expected, recorder.calls.single())
    }
}
