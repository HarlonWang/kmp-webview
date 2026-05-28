package wang.harlon.webview.logpanel

/**
 * 单条日志条目，承载从各采集点写入面板的全部信息。
 * 字段全部拍平成字符串：列表 UI 不按 source 走 4 套分支，前端读起来一目了然。
 */
data class WebViewLog(
    val id: Long,
    val timestamp: Long,
    val source: Source,
    val level: Level,
    val message: String,
    val detail: String? = null,
) {
    enum class Source { Console, JsException, WebViewError, JsBridge }
    enum class Level { Verbose, Info, Warn, Error }
}
