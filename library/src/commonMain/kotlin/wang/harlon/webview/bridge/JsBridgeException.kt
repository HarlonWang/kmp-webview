package wang.harlon.webview.bridge

/**
 * Handler 抛出此异常时，错误码 [code] 会原样透传给 JS 端 Promise reject 的 error.code。
 * code 约定为 ASCII 大写字母 + 下划线，例如 `INVALID_PARAMS`、`AUTH_REQUIRED`。
 */
class JsBridgeException(val code: String, message: String) : RuntimeException(message)
