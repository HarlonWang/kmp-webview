package wang.harlon.webview.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class JsBridge internal constructor(
    private val scope: CoroutineScope,
) {

    private val handlers = mutableMapOf<String, suspend (String?) -> String?>()
    private var allowedOrigins: Set<String>? = null
    private var evaluator: ((String) -> Unit)? = null

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
        evaluate("window.KmpBridge && window.KmpBridge.__emit(${jsonQuote(event)}, $payloadLit)")
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

    internal fun dispatchIncoming(envelopeJson: String, originProvider: () -> String?) {
        val env = parseEnvelope(envelopeJson) ?: return

        val whitelist = allowedOrigins
        if (whitelist != null) {
            val origin = originProvider()?.let(::extractOrigin)
            if (origin == null || !originMatches(origin, whitelist)) {
                resolve(env.id, ok = false, errorJson("ORIGIN_DENIED", "origin not allowed"))
                return
            }
        }

        val handler = handlers[env.method]
        if (handler == null) {
            resolve(env.id, ok = false, errorJson("HANDLER_NOT_FOUND", "no handler for ${env.method}"))
            return
        }

        scope.launch {
            try {
                val result = handler(env.params)
                resolve(env.id, ok = true, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JsBridgeException) {
                resolve(env.id, ok = false, errorJson(e.code, e.message))
            } catch (e: Throwable) {
                resolve(env.id, ok = false, errorJson("HANDLER_ERROR", e.message))
            }
        }
    }

    /** WebViewState 离开组合时调，取消 scope 内所有 handler 协程。 */
    internal fun cancelScope() { scope.cancel() }

    // ───────── helpers ─────────

    private fun resolve(id: Long, ok: Boolean, payloadJson: String?) {
        val payloadLit = if (payloadJson == null) "null" else jsonQuote(payloadJson)
        evaluate("window.KmpBridge && window.KmpBridge.__resolve($id, $ok, $payloadLit)")
    }

    private fun evaluate(js: String) {
        evaluator?.invoke(js)
    }

    private fun errorJson(code: String, message: String?): String {
        val msgLit = if (message == null) "null" else jsonQuote(message)
        return """{"code":${jsonQuote(code)},"message":$msgLit}"""
    }

    internal companion object {
        internal fun create(scope: CoroutineScope): JsBridge = JsBridge(scope)

        internal fun createForTest(): JsBridge =
            JsBridge(CoroutineScope(SupervisorJob() + Dispatchers.Main))
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
