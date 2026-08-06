package wang.harlon.webview.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSError
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
// downloadTaskWithURL:completionHandler: 与 popoverPresentationController 都是 Obj-C 分类成员，
// K/N 暴露为扩展函数 / 扩展属性，须显式 import 才能解析（同 NSMutableURLRequest 的
// setAllHTTPHeaderFields，见 PlatformWebView.ios.kt）。
import platform.Foundation.downloadTaskWithURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.popoverPresentationController
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import wang.harlon.webview.core.DownloadScripts
import wang.harlon.webview.core.WebViewConfig

/**
 * 接管 iOS WKWebView 内的文件下载（由 [PlatformWebView] 在 `config.allowDownloads` 时装配）。
 *
 * **与 Android 的关键差异**：Android 靠 `WebView.setDownloadListener` 兜住所有下载请求；WKWebView
 * 没有等价回调——`<a download>` 的点击（尤其 `blob:` / `data:`）不产生任何原生通知，点了就是静默无
 * 事发生。故入口改由 [DownloadScripts.ANCHOR_CLICK_INTERCEPT_JS] 在 JS 层拦下点击，经
 * `WKScriptMessageHandler` 把 URL 交回原生，再按 scheme 分流——分流后的处理与 Android 对齐：
 *
 * - `blob:`：原生网络栈读不了，注入 [DownloadScripts.buildBlobReaderJs] 让页面把 blob 读成
 *   `data:` base64 回传，走下面的 data 分支
 * - `data:`：解码 → [saveThenOpen]
 * - `http(s)`：[NSURLSession] 下载到临时文件 → 落盘 → [saveThenOpen]
 *
 * 落盘位置：应用私有 `Caches/kmpwebview/`（免权限，随系统清理）。打开方式用
 * [UIActivityViewController] 分享面板——iOS 没有 Android `ACTION_VIEW` 那种"交给系统选应用打开"
 * 的等价物，分享面板是让用户存到「文件」App 或转交其他应用的惯常做法。
 *
 * 线程：JS 回调经 message handler 到主线程；下载与落盘在 NSURLSession 后台线程；present 面板与
 * [WebViewConfig.onFileDownloaded] 统一切回主线程。
 *
 * 安全：注入的 `window.__kmpDownloadNative` 对页面所有来源可见，恶意页面可借此把任意 base64 写入
 * 应用 cache 并弹出分享面板。风险面等同于 Android 侧既有取舍（见 AndroidWebDownloader KDoc）。
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosWebDownloader(
    private val config: WebViewConfig,
) : NSObject(), WKScriptMessageHandlerProtocol {

    private var webView: WKWebView? = null

    fun attach(webView: WKWebView) {
        this.webView = webView
    }

    fun dispose() {
        webView = null
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val body = didReceiveScriptMessage.body as? Map<*, *> ?: return
        val type = body["type"] as? String ?: return
        val name = (body["name"] as? String).orEmpty()
        when (type) {
            // 拦截到的锚点点击：按 scheme 分流
            DownloadBridge.TYPE_ANCHOR -> {
                val href = body["href"] as? String ?: return
                when {
                    href.startsWith("blob:") -> readBlob(href)
                    href.startsWith("data:") -> handleDataUrl(href, name.ifBlank { null })
                    href.startsWith("http://") || href.startsWith("https://") ->
                        downloadHttp(href, name.ifBlank { null })

                    else -> Unit
                }
            }
            // 页面读完 blob 回传的 data: URL
            DownloadBridge.TYPE_BLOB -> {
                val dataUrl = body["dataUrl"] as? String
                if (dataUrl.isNullOrEmpty()) return
                handleDataUrl(dataUrl, name.ifBlank { null })
            }
        }
    }

    /** blob: 读不了，让页面上下文读成 data: 再回传（复用 Android 同一段脚本）。 */
    private fun readBlob(blobUrl: String) {
        webView?.evaluateJavaScript(DownloadScripts.buildBlobReaderJs(blobUrl), null)
    }

    private fun handleDataUrl(dataUrl: String, preferredName: String?) {
        val comma = dataUrl.indexOf(',')
        if (comma < 0 || !dataUrl.startsWith("data:")) return
        val header = dataUrl.substring("data:".length, comma) // 形如 "application/pdf;base64"
        val mime = header.substringBefore(';').ifBlank { null }
        val payload = dataUrl.substring(comma + 1)
        // 只处理 base64：非 base64 的 data: URL 在下载场景极罕见，且 percent-decoding 到二进制
        // 在 K/N 侧没有直接对应物，不值得为此引入手写解码。
        if (!header.contains("base64", ignoreCase = true)) return
        val data = NSData.create(base64EncodedString = payload, options = 0u) ?: return
        saveThenOpen(data, resolveName(preferredName, mime), mime)
    }

    private fun downloadHttp(url: String, preferredName: String?) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        val task = NSURLSession.sharedSession.downloadTaskWithURL(nsUrl) { location, response, error ->
            handleHttpResult(location, response, error, preferredName)
        }
        task.resume()
    }

    private fun handleHttpResult(
        location: NSURL?,
        response: NSURLResponse?,
        error: NSError?,
        preferredName: String?,
    ) {
        if (error != null || location == null) return
        val http = response as? NSHTTPURLResponse
        if (http != null && http.statusCode !in 200..299) return
        // location 是 NSURLSession 的临时文件，回调返回后即被删除，必须当场读出来
        val data = NSData.create(contentsOfURL = location) ?: return
        val mime = response?.MIMEType
        val name = preferredName ?: response?.suggestedFilename
        saveThenOpen(data, resolveName(name, mime), mime)
    }

    private fun saveThenOpen(data: NSData, fileName: String, mimeType: String?) {
        val dir = cacheDir() ?: return
        val path = "$dir/$fileName"
        if (!data.writeToFile(path, atomically = true)) return
        dispatch_async(dispatch_get_main_queue()) {
            presentShareSheet(path)
            config.onFileDownloaded?.invoke(fileName, path, mimeType)
        }
    }

    private fun cacheDir(): String? {
        val caches = NSSearchPathForDirectoriesInDomains(
            NSCachesDirectory, NSUserDomainMask, true,
        ).firstOrNull() as? String ?: return null
        val dir = "$caches/${DownloadBridge.DIR}"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }

    /**
     * 弹系统分享面板，让用户存到「文件」App 或转交其他应用。
     *
     * iPad 上 UIActivityViewController 走 popover 呈现，不配 sourceView 会直接崩——这里挂到
     * 呈现控制器自身的 view 上并把锚点收敛到中心，避免依赖具体触发控件的位置。
     */
    private fun presentShareSheet(path: String) {
        val host = topViewController() ?: return
        val fileUrl = NSURL.fileURLWithPath(path)
        val controller = UIActivityViewController(
            activityItems = listOf(fileUrl),
            applicationActivities = null,
        )
        controller.popoverPresentationController?.let { popover ->
            popover.sourceView = host.view
            popover.sourceRect = host.view.bounds
        }
        host.presentViewController(controller, animated = true, completion = null)
    }

    /** 从 WebView 所在窗口取当前最顶层控制器；已在呈现别的界面时顺着 presentedViewController 往下找。 */
    private fun topViewController(): UIViewController? {
        val window: UIWindow = webView?.window ?: return null
        var vc = window.rootViewController ?: return null
        while (true) {
            vc = vc.presentedViewController ?: return vc
        }
    }

    /** 文件名兜底：优先用页面/响应给的名字，否则按 MIME 推扩展名 + 时间戳；最后净化非法字符。 */
    private fun resolveName(preferredName: String?, mimeType: String?): String {
        val base = preferredName?.takeIf { it.isNotBlank() }
            ?: run {
                val stamp = NSDate().timeIntervalSince1970.toLong()
                val ext = mimeType?.let { DownloadBridge.EXT_BY_MIME[it.substringBefore(';').trim().lowercase()] }
                "download_$stamp" + (ext?.let { ".$it" } ?: "")
            }
        return sanitize(base)
    }

    // 去掉路径分隔符与常见非法字符，避免写到 cache 目录外或被文件系统拒绝
    private fun sanitize(name: String): String =
        name.map { if (it in DownloadBridge.ILLEGAL_CHARS) '_' else it }
            .joinToString("")
            .ifBlank { "download_${NSDate().timeIntervalSince1970.toLong()}" }

}

/**
 * [IosWebDownloader] 的常量与注入脚本。
 *
 * 独立成 object 而非 companion：Kotlin/Native 不允许 Obj-C 类（[NSObject] 子类）的 companion
 * 持有字段——"Fields are not supported for Companion of subclass of ObjC type"。
 */
internal object DownloadBridge {
    /** message handler 名，与注入的 shim 中 `messageHandlers.<name>` 对应。 */
    const val HANDLER_NAME: String = DownloadScripts.NATIVE_INTERFACE
    const val TYPE_ANCHOR: String = "anchor"
    const val TYPE_BLOB: String = "blob"

    const val DIR = "kmpwebview"
    val ILLEGAL_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    // iOS 没有 Android MimeTypeMap 那样的系统级 MIME→扩展名表；下载场景常见类型手列即可，
    // 查不到就不带扩展名（分享面板仍能按内容处理）。
    val EXT_BY_MIME = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "text/csv" to "csv",
        "text/plain" to "txt",
        "image/png" to "png",
        "image/jpeg" to "jpg",
    )

    /**
     * 建立 [DownloadScripts.NATIVE_INTERFACE] 全局对象，把 JS 调用桥到 WKScriptMessageHandler。
     *
     * Android 侧这层由 `addJavascriptInterface` 直接提供同名对象；iOS 只有
     * `window.webkit.messageHandlers.<name>.postMessage(...)`，故用这段 shim 抹平，让
     * [DownloadScripts] 里的跨端脚本在两端调用形态完全一致。
     */
    val BRIDGE_SHIM_JS: String = """
        (function(){
          if (window.${DownloadScripts.NATIVE_INTERFACE}) return;
          function post(payload) {
            try {
              window.webkit.messageHandlers.$HANDLER_NAME.postMessage(payload);
            } catch(_){}
          }
          window.${DownloadScripts.NATIVE_INTERFACE} = {
            onAnchorDownload: function(href, name) {
              post({ type: '$TYPE_ANCHOR', href: href, name: name || '' });
            },
            onBlobDownloaded: function(blobUrl, dataUrl, name) {
              post({ type: '$TYPE_BLOB', href: blobUrl, dataUrl: dataUrl, name: name || '' });
            }
          };
        })();
    """.trimIndent()

    /**
     * 下载所需的全部注入脚本，供 [PlatformWebView] 装配进 userContentController。
     *
     * documentStart 注入：`window.open` 的改写必须早于页面任何脚本执行，否则页面可能先缓存了
     * 原始引用；点击监听挂在 document 上，此刻 document 已存在，无需等 body。
     * `forMainFrameOnly = false` 让 iframe 内的下载同样被接管。
     */
    fun userScripts(): List<WKUserScript> = listOf(
        // shim 必须在最前：后面几段脚本都调用它建立的 NATIVE_INTERFACE
        BRIDGE_SHIM_JS,
        DownloadScripts.DOWNLOAD_NAME_HOOK_JS,
        DownloadScripts.WINDOW_OPEN_DOWNLOAD_HOOK_JS,
        DownloadScripts.ANCHOR_CLICK_INTERCEPT_JS,
    ).map { source ->
        WKUserScript(
            source = source,
            injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
            forMainFrameOnly = false,
        )
    }
}
