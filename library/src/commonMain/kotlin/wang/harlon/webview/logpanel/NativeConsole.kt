package wang.harlon.webview.logpanel

/**
 * native console 通道（Android `WebChromeClient.onConsoleMessage`）里未捕获异常的格式化。
 *
 * 背景：跨域脚本（如 CDN 上的 bundle，`<script>` 未加 `crossorigin`）抛出的未捕获异常，
 * 浏览器投递给页面侧 `window.onerror` / ErrorEvent 时会按规范脱敏成 `"Script error."`
 * （无 src/line/stack）——shim 采集到的就是这条空壳。而引擎发到 native console 通道的
 * 消息**不受该脱敏约束**，携带真实的 `Uncaught <Error 类型>: <message>` 与来源文件行号，
 * 与 logcat `[INFO:CONSOLE]` / DevTools 控制台一致（该通道无堆栈帧，完整堆栈仍需
 * DevTools 或前端加 `crossorigin` 后由 shim 取 `error.stack`）。
 *
 * 只放行 `Uncaught ` 前缀的 ERROR 级消息：引擎对未捕获异常固定用该前缀，而页面代码
 * `console.error(...)` 已由 shim 的 console patch 采集，不筛掉会重复入面板。
 *
 * iOS WKWebView 没有对应的公开回调，此兜底为 Android 独有；逻辑放 commonMain 是为了
 * 让 commonTest 直接覆盖，平台侧只做参数搬运。
 *
 * @return 面板展示用的消息文本；非未捕获异常消息返回 null（调用方跳过）
 */
internal fun nativeUncaughtConsoleMessage(
    message: String?,
    sourceId: String?,
    lineNumber: Int?,
): String? {
    val text = message?.takeIf { it.startsWith("Uncaught ") } ?: return null
    val pos = when {
        sourceId.isNullOrEmpty() -> ""
        lineNumber == null || lineNumber <= 0 -> " ($sourceId)"
        else -> " ($sourceId:$lineNumber)"
    }
    return "$text$pos"
}

/** native console 通道条目的 detail 说明，解释信息来源与堆栈缺失原因。 */
internal const val NATIVE_CONSOLE_DETAIL =
    "Captured from the native console (WebChromeClient.onConsoleMessage), which is not " +
        "subject to cross-origin masking. Stack frames are unavailable on this channel; " +
        "use chrome://inspect for the full stack."
