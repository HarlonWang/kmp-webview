package wang.harlon.webview.scanner

import java.net.URI

/**
 * 校验扫码结果是否为可加载的网页地址。
 *
 * 仅放行带 host 的 http / https URL —— 默认即挡掉 `javascript:` / `file:` / `tel:` 等
 * 危险或非网页 scheme，保证回调出去的一定是合法网址。纯函数，无 Android 依赖，可单测。
 */
internal object UrlValidator {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * @param raw 扫码识别出的原始文本（可能为 null / 任意内容）
     * @return 去除首尾空白后的合法 http/https URL；不合法返回 null
     */
    fun validate(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in ALLOWED_SCHEMES) return null
        if (uri.host.isNullOrEmpty()) return null

        return trimmed
    }
}
