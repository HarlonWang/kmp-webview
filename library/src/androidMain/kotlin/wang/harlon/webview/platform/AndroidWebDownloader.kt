package wang.harlon.webview.platform

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import wang.harlon.webview.core.DownloadScripts
import wang.harlon.webview.core.WebViewConfig

/**
 * 接管 Android WebView 内的文件下载（由 [PlatformWebView] 在 `config.allowDownloads` 时装配）。
 *
 * 数据流：`WebView.setDownloadListener` 收到下载请求 → [onDownloadStart] 按 scheme 分流：
 * - `blob:`：无法被原生直接读取，注入 [DownloadScripts.buildBlobReaderJs] 让页面上下文把 blob 读成
 *   `data:` base64，经 [jsInterface] 的 `onBlobDownloaded` 回传 → 解码 → [saveThenOpen]。
 * - `data:`：直接解码 → [saveThenOpen]。
 * - `http(s)`：交系统 [DownloadManager]（带 WebView 的 Cookie / UA），落到公共 Downloads 目录。
 *
 * 落盘位置：应用私有 `cacheDir/kmpwebview/`（免存储权限），经库自带 FileProvider
 * `${packageName}.kmpwebview.fileprovider`（与 [FileChooserLauncher] 复用同一 provider）授权分享，
 * `ACTION_VIEW` 交系统应用打开。
 *
 * 线程：`@JavascriptInterface` 回调在 JS 线程；下载/解码/落盘统一切到单线程 [io]，打开/toast/回调
 * post 回 [main]。
 *
 * 安全：`addJavascriptInterface` 暴露的 `onBlobDownloaded` 对页面所有来源可见，恶意页面可借此把任意
 * base64 写入应用 cache 并触发系统打开选择。风险面等同于业务 WebView 既有的信任模型（本就加载后端下发
 * URL + 挂载 PPBridge），本期接受。
 */
internal class AndroidWebDownloader(
    private val context: Context,
    private val config: WebViewConfig,
) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** DownloadListener 回调（UI 线程）。按 scheme 分流处理。 */
    fun onDownloadStart(
        webView: WebView,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        when {
            url.startsWith("blob:") ->
                // evaluateJavascript 须在 UI 线程；DownloadListener 本就在 UI 线程回调。
                webView.evaluateJavascript(DownloadScripts.buildBlobReaderJs(url), null)

            url.startsWith("data:") ->
                io.execute { handleDataUrl(url, nameFromDisposition(contentDisposition)) }

            URLUtil.isNetworkUrl(url) ->
                enqueueHttpDownload(url, userAgent, contentDisposition, mimeType)

            else -> Log.w(TAG, "unsupported download scheme: ${url.take(16)}")
        }
    }

    /** 注册给 WebView 的 JS 接口对象；页面读完 blob 后回调这里。 */
    fun jsInterface(): Any = object {
        // dataUrl 形如 "data:<mime>;base64,<payload>"；name 来自 DOWNLOAD_NAME_HOOK_JS 暂存值，可能为空。
        @JavascriptInterface
        fun onBlobDownloaded(blobUrl: String, dataUrl: String?, name: String?) {
            if (dataUrl.isNullOrEmpty()) {
                Log.w(TAG, "blob read returned empty payload")
                postToast(MSG_ERROR)
                return
            }
            io.execute { handleDataUrl(dataUrl, name?.takeIf { it.isNotBlank() }) }
        }
    }

    private fun handleDataUrl(dataUrl: String, preferredName: String?) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0 || !dataUrl.startsWith("data:")) {
                Log.w(TAG, "malformed data url"); postToast(MSG_ERROR); return
            }
            val header = dataUrl.substring("data:".length, comma) // e.g. "application/pdf;base64"
            val mime = header.substringBefore(';').ifBlank { null }
            val payload = dataUrl.substring(comma + 1)
            val bytes = if (header.contains("base64", ignoreCase = true)) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray()
            }
            saveThenOpen(bytes, resolveName(preferredName, mime), mime)
        } catch (e: Throwable) {
            Log.w(TAG, "handle data url failed", e); postToast(MSG_ERROR)
        }
    }

    private fun saveThenOpen(bytes: ByteArray, fileName: String, mimeType: String?) {
        val file = try {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            File(dir, fileName).also { it.writeBytes(bytes) }
        } catch (e: Throwable) {
            Log.w(TAG, "save failed", e); postToast(MSG_ERROR); return
        }
        main.post {
            openFile(file, mimeType)
            config.onFileDownloaded?.invoke(file.name, file.absolutePath, mimeType)
        }
    }

    private fun openFile(file: File, mimeType: String?) {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.kmpwebview.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "fileprovider uri failed", e); postToast(MSG_ERROR); return
        }
        val mime = mimeType ?: context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app to open $mime", e); postToast(MSG_NO_APP)
        }
    }

    private fun enqueueHttpDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            postToast(MSG_START)
        } catch (e: Throwable) {
            Log.w(TAG, "http download failed", e); postToast(MSG_ERROR)
        }
    }

    /** 文件名兜底：优先用页面提供的名字，否则按 MIME 推扩展名 + 时间戳，最后统一净化非法字符。 */
    private fun resolveName(preferredName: String?, mimeType: String?): String {
        val base = preferredName?.takeIf { it.isNotBlank() }
            ?: run {
                val ext = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                "download_${System.currentTimeMillis()}" + (ext?.let { ".$it" } ?: "")
            }
        return sanitize(base)
    }

    private fun nameFromDisposition(contentDisposition: String?): String? =
        contentDisposition
            ?.let { Regex("filename\\*?=([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1) }
            ?.trim()
            ?.removePrefix("UTF-8''")
            ?.trim('"', ' ')
            ?.takeIf { it.isNotBlank() }

    // 去掉路径分隔符与常见非法字符，避免写到 cache 目录外或被系统拒绝。
    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "download_${System.currentTimeMillis()}" }

    private fun postToast(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    fun dispose() {
        io.shutdown()
    }

    private companion object {
        const val TAG = "KmpWebViewDownloader"
        const val DIR = "kmpwebview"
        // 简短英文提示，避免库内引入 i18n；宿主如需本地化可用 onFileDownloaded 自行接管 UI。
        const val MSG_START = "Downloading…"
        const val MSG_ERROR = "Download failed"
        const val MSG_NO_APP = "No app to open this file"
    }
}
