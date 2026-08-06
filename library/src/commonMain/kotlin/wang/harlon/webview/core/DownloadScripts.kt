package wang.harlon.webview.core

/**
 * WebView 下载所需的注入 JS（跨端复用同一段字面量）。
 *
 * 背景：Web 端下载普遍走 `API 取 Blob → URL.createObjectURL → <a download>.click()`，产出的是
 * `blob:` URL。原生的 DownloadListener 能收到该 URL，但 `blob:` 无法被原生网络栈直接读取——必须在
 * 页面上下文里用 XHR 把 blob 读回、经 FileReader 转成 `data:` base64，再交给原生解码落盘。
 *
 * **两端消费方式不同**：Android 有 `WebView.setDownloadListener` 兜住所有下载请求，JS 只需负责
 * blob 读取与补文件名；iOS 的 WKWebView **没有等价回调**——`<a download>`（尤其 `blob:` / `data:`）
 * 的点击不产生任何原生通知，点了就是静默无事发生。故 iOS 额外消费 [ANCHOR_CLICK_INTERCEPT_JS]，
 * 在 JS 层拦下点击、把 URL 交回原生按 scheme 分流。
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

    /**
     * 捕获阶段拦下下载型 `<a>` 的点击，把绝对 URL 与 `download` 文件名交回原生
     * [NATIVE_INTERFACE].onAnchorDownload(href, name)，由原生按 scheme 分流。
     *
     * **iOS 专用**：Android 有 `DownloadListener` 兜底，无需在 JS 层拦截；WKWebView 则对
     * `<a download>` 的点击（尤其 `blob:` / `data:`）不产生任何原生回调，不拦就是点了没反应
     * （B2B2-8439 的 KYC「下载模板」正是此形态）。
     *
     * 拦截范围：带 `download` 属性的锚点，以及 href 为 `blob:` / `data:` 的锚点——后者即便没有
     * `download` 属性，在 WKWebView 里导航过去同样是死路，一并接管。
     *
     * 只调 `preventDefault` 不调 `stopPropagation`：页面自身挂在同一次点击上的逻辑（埋点、状态
     * 更新等）仍需照常执行，这里只取消"默认导航"这一件事。若页面 handler 自己走 `window.open`，
     * 由 [WINDOW_OPEN_DOWNLOAD_HOOK_JS] 那条路接住。
     *
     * 用 `el.href` 而非 `getAttribute('href')`：前者已由浏览器解析成绝对 URL，原生侧无需再拼
     * base；`blob:` / `data:` 两种取值行为一致。幂等安装。
     */
    val ANCHOR_CLICK_INTERCEPT_JS: String = """
        (function(){
          if (window.__kmpAnchorHookInstalled) return;
          window.__kmpAnchorHookInstalled = true;
          document.addEventListener('click', function(e){
            try {
              var el = e.target;
              while (el && el.tagName !== 'A') el = el.parentElement;
              if (!el) return;
              var href = el.href || '';
              if (!href) return;
              var isInline = href.indexOf('blob:') === 0 || href.indexOf('data:') === 0;
              if (!el.hasAttribute('download') && !isInline) return;
              e.preventDefault();
              window.$NATIVE_INTERFACE.onAnchorDownload(href, el.getAttribute('download') || '');
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
