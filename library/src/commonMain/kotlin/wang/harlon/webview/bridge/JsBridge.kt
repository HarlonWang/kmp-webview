package wang.harlon.webview.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import wang.harlon.webview.logpanel.LogStore
import wang.harlon.webview.logpanel.WebViewLog

class JsBridge internal constructor(
    private val scope: CoroutineScope,
    internal val namespace: String,
) {

    init {
        require(VALID_JS_IDENT.matches(namespace)) {
            "bridgeNamespace must be a valid JS identifier: '$namespace'"
        }
    }

    private val handlers = mutableMapOf<String, suspend (String?) -> String?>()
    private var allowedOrigins: Set<String>? = null
    private var evaluator: ((String) -> Unit)? = null
    private var logStore: LogStore? = null

    fun registerHandler(method: String, handler: suspend (String?) -> String?) {
        if (handlers.containsKey(method)) {
            println("JsBridge: handler '$method' replaced")
        }
        handlers[method] = handler
    }

    fun unregisterHandler(method: String) {
        handlers.remove(method)
    }

    fun emit(event: String, payloadJson: String? = null) {
        val payloadLit = if (payloadJson == null) "null" else jsonQuote(payloadJson)
        evaluate("window.$namespace && window.$namespace.__emit(${jsonQuote(event)}, $payloadLit)")
        logStore?.let { store ->
            scope.launch {
                store.append(
                    source = WebViewLog.Source.JsBridge,
                    level = WebViewLog.Level.Info,
                    message = "emit $event",
                    detail = payloadJson?.let { "payload: $it" },
                )
            }
        }
    }

    fun setAllowedOrigins(origins: Set<String>?) {
        allowedOrigins = origins
    }

    // ───────── internal ─────────

    internal fun attachEvaluator(eval: (String) -> Unit) {
        evaluator = eval
    }

    internal fun detachEvaluator() {
        evaluator = null
    }

    /** 由 [wang.harlon.webview.core.WebViewState.enableLogPanel] 注入；构造后调用一次即可。 */
    internal fun attachLogStore(store: LogStore) {
        logStore = store
    }

    @OptIn(ExperimentalTime::class)
    internal fun dispatchIncoming(envelopeJson: String, originProvider: () -> String?) {
        val env = parseEnvelope(envelopeJson) ?: return

        val whitelist = allowedOrigins
        if (whitelist != null) {
            val origin = originProvider()?.let(::extractOrigin)
            if (origin == null || !originMatches(origin, whitelist)) {
                resolve(env.id, ok = false, errorJson("ORIGIN_DENIED", "origin not allowed"))
                logBridge(env.method, env.params, "ORIGIN_DENIED", null)
                return
            }
        }

        val handler = handlers[env.method]
        if (handler == null) {
            resolve(env.id, ok = false, errorJson("HANDLER_NOT_FOUND", "no handler for ${env.method}"))
            logBridge(env.method, env.params, "HANDLER_NOT_FOUND", null)
            return
        }

        val started = Clock.System.now().toEpochMilliseconds()
        scope.launch {
            try {
                val result = handler(env.params)
                resolve(env.id, ok = true, result)
                logBridge(env.method, env.params, null, result, started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JsBridgeException) {
                resolve(env.id, ok = false, errorJson(e.code, e.message))
                logBridge(env.method, env.params, e.code, null, started)
            } catch (e: Throwable) {
                resolve(env.id, ok = false, errorJson("HANDLER_ERROR", e.message))
                logBridge(env.method, env.params, "HANDLER_ERROR", e.message, started)
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun logBridge(
        method: String,
        params: String?,
        errorCode: String?,
        result: String?,
        startedAt: Long? = null,
    ) {
        val store = logStore ?: return
        val elapsed = startedAt?.let { Clock.System.now().toEpochMilliseconds() - it }
        val tag = if (elapsed != null) " (${elapsed}ms)" else ""
        val msg: String
        val level: WebViewLog.Level
        if (errorCode == null) {
            msg = "call $method$tag"
            level = WebViewLog.Level.Info
        } else {
            msg = "call $method failed [$errorCode]$tag"
            level = WebViewLog.Level.Error
        }
        val detail = buildString {
            append("params: ").append(params ?: "null")
            if (result != null) append("\nresult: ").append(result)
        }
        scope.launch { store.append(WebViewLog.Source.JsBridge, level, msg, detail) }
    }

    /** WebViewState 离开组合时调，取消 scope 内所有 handler 协程。 */
    internal fun cancelScope() { scope.cancel() }

    // ───────── helpers ─────────

    private fun resolve(id: Long, ok: Boolean, payloadJson: String?) {
        val payloadLit = if (payloadJson == null) "null" else jsonQuote(payloadJson)
        evaluate("window.$namespace && window.$namespace.__resolve($id, $ok, $payloadLit)")
    }

    private fun evaluate(js: String) {
        evaluator?.invoke(js)
    }

    private fun errorJson(code: String, message: String?): String {
        val msgLit = if (message == null) "null" else jsonQuote(message)
        return """{"code":${jsonQuote(code)},"message":$msgLit}"""
    }

    internal companion object {
        // JS 标识符规则；namespace 拼到 JS 源码字面里，非法值会导致 SyntaxError，构造期 fail-fast。
        private val VALID_JS_IDENT = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

        internal fun create(scope: CoroutineScope, namespace: String): JsBridge =
            JsBridge(scope, namespace)

        internal fun createForTest(namespace: String = "KmpBridge"): JsBridge =
            JsBridge(CoroutineScope(SupervisorJob() + Dispatchers.Main), namespace)
    }
}

// ───────── envelope 解析（minimal JSON, 仅支持本 SDK 的 envelope 形状）─────────

private data class Envelope(val id: Long, val method: String, val params: String?)

private fun parseEnvelope(json: String): Envelope? = try {
    val p = JsonReader(json)
    p.skipWs(); p.expect('{')
    var id: Long? = null
    var method: String? = null
    var params: String? = null
    var first = true
    p.skipWs()
    while (!p.atEnd() && p.peek() != '}') {
        if (!first) { p.skipWs(); p.expect(','); p.skipWs() }
        first = false
        val key = p.readString()
        p.skipWs(); p.expect(':'); p.skipWs()
        when (key) {
            "id" -> id = p.readNumber()
            "method" -> method = p.readString()
            "params" -> params = p.readStringOrNull()
            else -> p.skipValue()
        }
        p.skipWs()
    }
    p.expect('}')
    if (id != null && method != null) Envelope(id, method, params) else null
} catch (_: Throwable) {
    null
}

private class JsonReader(val s: String) {
    var i = 0
    fun atEnd() = i >= s.length
    fun peek(): Char = s[i]
    fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun expect(c: Char) {
        if (i >= s.length || s[i] != c) error("expected '$c' at $i")
        i++
    }
    fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) error("unterminated escape")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 > s.length) error("bad unicode escape")
                            val code = s.substring(i, i + 4).toInt(16)
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> error("bad escape \\$e")
                    }
                }
                else -> sb.append(c)
            }
        }
        error("unterminated string")
    }
    fun readNumber(): Long {
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        while (i < s.length && s[i].isDigit()) i++
        if (start == i) error("expected number at $start")
        return s.substring(start, i).toLong()
    }
    fun readStringOrNull(): String? {
        if (i + 4 <= s.length && s.substring(i, i + 4) == "null") {
            i += 4
            return null
        }
        return readString()
    }
    fun skipValue() {
        skipWs()
        when {
            atEnd() -> error("unexpected end")
            peek() == '"' -> { readString() }
            peek() == 'n' -> { if (s.substring(i, minOf(i + 4, s.length)) == "null") i += 4 else error("bad null") }
            peek() == 't' -> { if (s.substring(i, minOf(i + 4, s.length)) == "true") i += 4 else error("bad true") }
            peek() == 'f' -> { if (s.substring(i, minOf(i + 5, s.length)) == "false") i += 5 else error("bad false") }
            peek() == '-' || peek().isDigit() -> {
                if (s[i] == '-') i++
                while (i < s.length && s[i].isDigit()) i++
            }
            else -> error("unsupported value at $i")
        }
    }
}

// ───────── JSON 字符串字面量（带引号 + 转义）─────────

internal fun jsonQuote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            // </script> 防御
            c == '<' -> sb.append("\\u003C")
            c == '>' -> sb.append("\\u003E")
            c == '&' -> sb.append("\\u0026")
            // U+2028/U+2029 在 JS 字符串字面量里会被解析为行终止符
            c.code == 0x2028 -> sb.append("\\u2028")
            c.code == 0x2029 -> sb.append("\\u2029")
            c.code < 0x20 -> sb.append("\\u" + c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

// ───────── origin 解析 + 匹配 ─────────

private fun extractOrigin(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val rest = url.substring(schemeEnd + 3)
    val hostEnd = rest.indexOfAny(charArrayOf('/', '?', '#'))
    val authority = if (hostEnd < 0) rest else rest.substring(0, hostEnd)
    if (authority.isEmpty()) return null
    return url.substring(0, schemeEnd) + "://" + authority
}

private fun originMatches(origin: String, patterns: Set<String>): Boolean {
    for (pat in patterns) {
        if (pat == origin) return true
        val starIdx = pat.indexOf("://*.")
        if (starIdx >= 0) {
            val scheme = pat.substring(0, starIdx)
            val tail = pat.substring(starIdx + 5)
            if (!origin.startsWith("$scheme://")) continue
            val originAuthority = origin.substring(scheme.length + 3)
            if (originAuthority.length <= tail.length + 1) continue
            if (!originAuthority.endsWith(".$tail")) continue
            return true
        }
    }
    return false
}
