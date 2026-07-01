package wang.harlon.webview.core

/**
 * WebView 下载所需的注入 JS（跨端复用同一段字面量；当前仅 Android 消费，iOS 后续接入时复用）。
 *
 * 背景：Web 端下载普遍走 `API 取 Blob → URL.createObjectURL → <a download>.click()`，产出的是
 * `blob:` URL。原生的 DownloadListener 能收到该 URL，但 `blob:` 无法被原生网络栈直接读取——必须在
 * 页面上下文里用 XHR 把 blob 读回、经 FileReader 转成 `data:` base64，再交给原生解码落盘。
 */
internal object DownloadScripts {

    /** 供 JS 回调原生的接口名（对应 Android `addJavascriptInterface(..., NATIVE_INTERFACE)`）。 */
    const val NATIVE_INTERFACE: String = "__kmpDownloadNative"

    /**
     * 在页面上下文读取 [blobUrl] 对应的 blob，转成 `data:` base64 后回传原生
     * [NATIVE_INTERFACE].onBlobDownloaded(blobUrl, dataUrl, name)。
     *
     * 任何失败路径都回传空 dataUrl，让原生侧能结束等待、不静默挂起。文件名取自
     * [DOWNLOAD_NAME_HOOK_JS] 暂存的 `window.__kmpLastDownloadName`（拿不到则空串，由原生兜底）。
     */
    fun buildBlobReaderJs(blobUrl: String): String {
        val u = jsString(blobUrl)
        val n = NATIVE_INTERFACE
        return """
        (function(){
          function fail(){ try { window.$n.onBlobDownloaded($u, '', ''); } catch(e){} }
          try {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', $u, true);
            xhr.responseType = 'blob';
            xhr.onload = function(){
              if (xhr.status === 200 || xhr.status === 0) {
                var reader = new FileReader();
                reader.onloadend = function(){
                  try {
                    window.$n.onBlobDownloaded($u, reader.result, window.__kmpLastDownloadName || '');
                  } catch(e){ fail(); }
                };
                reader.onerror = fail;
                reader.readAsDataURL(xhr.response);
              } else { fail(); }
            };
            xhr.onerror = fail;
            xhr.send();
          } catch(e){ fail(); }
        })();
        """.trimIndent()
    }

    /**
     * 在 onPageStarted 注入：捕获阶段监听点击，把最近一次带 `download` 属性的 `<a>` 的文件名暂存到
     * `window.__kmpLastDownloadName`。`blob:` 触发 DownloadListener 时 contentDisposition 通常为空，
     * 靠这里补文件名。程序化 `a.click()` 也会派发真实 click 事件，故捕获监听同样能拿到。幂等安装。
     */
    val DOWNLOAD_NAME_HOOK_JS: String = """
        (function(){
          if (window.__kmpDownloadNameHookInstalled) return;
          window.__kmpDownloadNameHookInstalled = true;
          window.__kmpLastDownloadName = '';
          document.addEventListener('click', function(e){
            try {
              var el = e.target;
              while (el && el.tagName !== 'A') el = el.parentElement;
              if (el && el.hasAttribute('download')) {
                window.__kmpLastDownloadName = el.getAttribute('download') || '';
              }
            } catch(_){}
          }, true);
        })();
    """.trimIndent()

    /** 把任意字符串安全地包成 JS 单引号字面量（转义反斜杠 / 引号 / 换行）。 */
    private fun jsString(raw: String): String {
        val sb = StringBuilder("'")
        for (c in raw) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        return sb.append("'").toString()
    }
}
