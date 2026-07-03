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

    /**
     * 在 onPageStarted 注入：改写 `window.open`，把 `data:` / `blob:` URL 的调用转成
     * `<a download>` 点击，其余 URL 透传改写前的 window.open。
     *
     * 背景：Chromium 60+ 禁止渲染进程发起的「顶层 frame 导航到 `data:` URL」；WebView 默认不支持
     * 多窗口，`window.open(dataUrl)` 退化为当前 frame 导航后被内核拦截（console 报
     * "Not allowed to navigate top frame to data URL"），且拦截发生在渲染层——DownloadListener /
     * shouldOverrideUrlLoading 都收不到回调，页面点击表现为静默无反应。Web 端组件库确有此写法
     * （如 charmander Uploader 对非 blob: 的 src 一律 `window.open(src, '_blank')`）。
     * 转成带 `download` 属性的锚点点击后，Chromium 按下载处理，正常触发 DownloadListener，
     * 进入原生既有的 data:/blob: 落盘管道。
     *
     * 细节：
     * - `download` 属性置空串：window.open 拿不到页面语义上的文件名，置空让原生按 MIME 兜底命名；
     *   不复用 `window.__kmpLastDownloadName`，避免沿用上一次下载的过期名字。
     * - 返回 null（等价于弹窗被拦截的浏览器行为），页面对返回值的判空逻辑可正常走通。
     * - 幂等安装：onPageStarted 可能因重定向/刷新多次注入，重复改写会把上一层 patch 误当
     *   "原始 open" 层层嵌套，靠安装标记短路。
     */
    val WINDOW_OPEN_DOWNLOAD_HOOK_JS: String = """
        (function(){
          if (window.__kmpWindowOpenHookInstalled) return;
          window.__kmpWindowOpenHookInstalled = true;
          var __kmpOrigWindowOpen = window.open ? window.open.bind(window) : null;
          window.open = function(url, target, features) {
            try {
              if (typeof url === 'string' &&
                  (url.indexOf('data:') === 0 || url.indexOf('blob:') === 0)) {
                var a = document.createElement('a');
                a.href = url;
                a.setAttribute('download', '');
                if (document.body) { document.body.appendChild(a); }
                a.click();
                if (a.parentNode) { a.parentNode.removeChild(a); }
                return null;
              }
            } catch(_){}
            return __kmpOrigWindowOpen ? __kmpOrigWindowOpen(url, target, features) : null;
          };
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
