package wang.harlon.webview.logpanel

/**
 * 把 shim 协议消息映射成 WebViewLog 并 append 到 [LogStore]。
 * 双端 binder 收到 native channel 消息后统一走这个路径，避免 Android/iOS 重复实现。
 *
 * 解析失败（[parseLogShimMessage] 返回 null）时仍写入一条 Verbose 兜底条目，不丢消息。
 */
/** 跨域脱敏空壳条目（"Script error."）的 detail 提示：解释成因并给出前端修复指引。 */
internal const val CROSS_ORIGIN_MASK_HINT =
    "Details masked by the browser: the failing script is cross-origin and its <script> tag " +
        "lacks crossorigin=\"anonymous\" (CDN must also send CORS headers). Add the attribute " +
        "on the web side to see real messages and stacks here. On Android, look for an " +
        "accompanying \"Uncaught ...\" entry captured from the native console."

internal suspend fun LogStore.ingestShimMessage(raw: String) {
    val msg = parseLogShimMessage(raw)
    if (msg == null) {
        append(
            source = WebViewLog.Source.Console,
            level = WebViewLog.Level.Verbose,
            message = "shim: unparsable message",
            detail = raw,
        )
        return
    }
    when (msg.kind) {
        "console" -> {
            val level = when (msg.level) {
                "warn" -> WebViewLog.Level.Warn
                "error" -> WebViewLog.Level.Error
                else -> WebViewLog.Level.Info
            }
            val joined = msg.args.joinToString(" ")
            val display = "[${msg.level ?: "log"}] $joined"
            append(
                source = WebViewLog.Source.Console,
                level = level,
                message = display,
                detail = if (joined.length > LogStore.MESSAGE_MAX - 16) joined else null,
            )
        }
        "jserror" -> {
            val src = msg.src
            val line = msg.line
            val col = msg.col
            val pos = when {
                src.isNullOrEmpty() -> ""
                line == null -> " ($src)"
                col == null -> " ($src:$line)"
                else -> " ($src:$line:$col)"
            }
            // "Script error." 是浏览器对跨域脚本未捕获异常的规范脱敏（无 src/stack），
            // 不是页面真实报错文本——detail 换成成因与修复指引，别让空壳条目误导排查。
            val masked = msg.stack.isNullOrEmpty() &&
                msg.message.orEmpty().startsWith("Script error")
            // Chromium 的 onerror message 自带 "Uncaught " 前缀、Safari 不带——条件补齐，避免双前缀
            val text = msg.message ?: "(no message)"
            val prefixed = if (text.startsWith("Uncaught")) text else "Uncaught $text"
            append(
                source = WebViewLog.Source.JsException,
                level = WebViewLog.Level.Error,
                message = "$prefixed$pos",
                detail = if (masked) CROSS_ORIGIN_MASK_HINT else msg.stack,
            )
        }
        "rejection" -> {
            append(
                source = WebViewLog.Source.JsException,
                level = WebViewLog.Level.Error,
                message = "Unhandled rejection: ${msg.message ?: "(no reason)"}",
                detail = msg.stack,
            )
        }
        else -> {
            append(
                source = WebViewLog.Source.Console,
                level = WebViewLog.Level.Verbose,
                message = "shim: unknown kind '${msg.kind}'",
                detail = raw,
            )
        }
    }
}
