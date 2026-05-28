package wang.harlon.webview.logpanel

/**
 * 由 [buildLogShim] 注入脚本通过 native channel 送回的消息。
 * 字段对应 shim 的 JSON 协议；不同 kind 用各自字段，未用到的留 null。
 *
 *  - kind == "console"  → [level] + [args]
 *  - kind == "jserror"  → [message] / [src] / [line] / [col] / [stack]
 *  - kind == "rejection" → [message] / [stack]
 */
internal data class LogShimMessage(
    val kind: String,
    val level: String? = null,
    val args: List<String> = emptyList(),
    val message: String? = null,
    val src: String? = null,
    val line: Long? = null,
    val col: Long? = null,
    val stack: String? = null,
)

/**
 * 把 shim 发回的 JSON 解析为 [LogShimMessage]；只接受扁平 object，
 * args 是字符串数组（shim 端用 safeStringify 已统一成 string）。
 * 解析失败返回 null（不抛），由调用方决定是否回灌成 Verbose 兜底条。
 */
internal fun parseLogShimMessage(json: String): LogShimMessage? = try {
    val r = MiniJsonReader(json)
    r.skipWs(); r.expect('{'); r.skipWs()
    var kind: String? = null
    var level: String? = null
    var args: List<String> = emptyList()
    var message: String? = null
    var src: String? = null
    var line: Long? = null
    var col: Long? = null
    var stack: String? = null
    var first = true
    while (!r.atEnd() && r.peek() != '}') {
        if (!first) { r.expect(','); r.skipWs() }
        first = false
        val key = r.readString()
        r.skipWs(); r.expect(':'); r.skipWs()
        when (key) {
            "kind" -> kind = r.readString()
            "level" -> level = r.readStringOrNull()
            "args" -> args = r.readStringArray()
            "message" -> message = r.readStringOrNull()
            "src" -> src = r.readStringOrNull()
            "line" -> line = r.readNumberOrNull()
            "col" -> col = r.readNumberOrNull()
            "stack" -> stack = r.readStringOrNull()
            else -> r.skipValue()
        }
        r.skipWs()
    }
    r.expect('}')
    if (kind == null) null
    else LogShimMessage(kind, level, args, message, src, line, col, stack)
} catch (_: Throwable) {
    null
}

/**
 * 极简 JSON 读取器，仅覆盖 shim 协议需要的形状：
 * 扁平 object、字符串、整数、null、字符串数组。不支持嵌套对象/混合数组。
 */
private class MiniJsonReader(val s: String) {
    var i = 0
    fun atEnd() = i >= s.length
    fun peek(): Char = s[i]
    fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun expect(c: Char) {
        if (i >= s.length || s[i] != c) error("expected '$c' at $i")
        i++
    }

    fun readString(): String {
        skipWs(); expect('"')
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
                        'f' -> sb.append('')
                        'u' -> {
                            if (i + 4 > s.length) error("bad unicode escape")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
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

    fun readStringOrNull(): String? {
        skipWs()
        if (peekLiteral("null")) { i += 4; return null }
        return readString()
    }

    fun readNumberOrNull(): Long? {
        skipWs()
        if (peekLiteral("null")) { i += 4; return null }
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && s[i].isDigit()) i++
        if (start == i) error("expected number at $start")
        return s.substring(start, i).toLong()
    }

    fun readStringArray(): List<String> {
        skipWs()
        if (peekLiteral("null")) { i += 4; return emptyList() }
        expect('['); skipWs()
        if (peek() == ']') { i++; return emptyList() }
        val out = mutableListOf<String>()
        while (true) {
            out += readString()
            skipWs()
            if (peek() == ',') { i++; skipWs(); continue }
            if (peek() == ']') { i++; return out }
            error("expected ',' or ']' at $i")
        }
    }

    fun skipValue() {
        skipWs()
        when {
            atEnd() -> error("unexpected end")
            peek() == '"' -> { readString() }
            peek() == 'n' -> { if (peekLiteral("null")) i += 4 else error("bad null") }
            peek() == 't' -> { if (peekLiteral("true")) i += 4 else error("bad true") }
            peek() == 'f' -> { if (peekLiteral("false")) i += 5 else error("bad false") }
            peek() == '[' -> {
                i++; skipWs()
                if (peek() == ']') { i++; return }
                while (true) {
                    skipValue(); skipWs()
                    if (peek() == ',') { i++; skipWs(); continue }
                    if (peek() == ']') { i++; return }
                    error("bad array at $i")
                }
            }
            peek() == '{' -> {
                i++; skipWs()
                if (peek() == '}') { i++; return }
                while (true) {
                    readString(); skipWs(); expect(':'); skipValue(); skipWs()
                    if (peek() == ',') { i++; skipWs(); continue }
                    if (peek() == '}') { i++; return }
                    error("bad object at $i")
                }
            }
            peek() == '-' || peek().isDigit() -> {
                if (s[i] == '-') i++
                while (i < s.length && s[i].isDigit()) i++
            }
            else -> error("unsupported value at $i")
        }
    }

    private fun peekLiteral(lit: String): Boolean =
        i + lit.length <= s.length && s.substring(i, i + lit.length) == lit
}
