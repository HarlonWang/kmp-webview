package wang.harlon.webview.logpanel

/**
 * 把 shim 协议消息映射成 WebViewLog 并 append 到 [LogStore]。
 * 双端 binder 收到 native channel 消息后统一走这个路径，避免 Android/iOS 重复实现。
 *
 * 解析失败（[parseLogShimMessage] 返回 null）时仍写入一条 Verbose 兜底条目，不丢消息。
 */
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
            append(
                source = WebViewLog.Source.JsException,
                level = WebViewLog.Level.Error,
                message = "Uncaught ${msg.message ?: "(no message)"}$pos",
                detail = msg.stack,
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
