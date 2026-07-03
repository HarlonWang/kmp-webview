package wang.harlon.webview.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [DownloadScripts] 注入脚本的字符串契约锁。
 *
 * commonTest 没有 JS 引擎，无法执行脚本，这里锁的是「脚本必须包含的关键结构」——防止后续改动
 * 无意间丢掉某个分支（如 window.open 透传兜底）。真实行为以 Android 真机验证为准。
 */
class DownloadScriptsTest {

    // ---- WINDOW_OPEN_DOWNLOAD_HOOK_JS ----
    // 背景：Chromium 禁止顶层 frame 导航到 data: URL，H5 里 window.open(dataUrl) 会被内核拦截且
    // 不触发 DownloadListener（点击静默无反应）。该 hook 把 data:/blob: 的 window.open 改写成
    // <a download> 点击，使其进入 DownloadListener 管道。

    @Test
    fun windowOpenHook_patchesWindowOpen() {
        val js = DownloadScripts.WINDOW_OPEN_DOWNLOAD_HOOK_JS
        assertTrue(js.contains("window.open = function"), "必须改写 window.open")
    }

    @Test
    fun windowOpenHook_routesDataAndBlobToAnchorDownload() {
        val js = DownloadScripts.WINDOW_OPEN_DOWNLOAD_HOOK_JS
        assertTrue(js.contains("'data:'"), "必须分流 data: URL")
        assertTrue(js.contains("'blob:'"), "必须分流 blob: URL")
        assertTrue(js.contains("createElement('a')"), "必须经 <a> 触发下载")
        assertTrue(js.contains("'download'"), "锚点必须带 download 属性（否则 data: 仍走导航被拦）")
    }

    @Test
    fun windowOpenHook_fallsBackToOriginalOpenForOtherUrls() {
        val js = DownloadScripts.WINDOW_OPEN_DOWNLOAD_HOOK_JS
        // 非 data:/blob: 必须透传给改写前的 window.open，不能吞掉页面的正常弹窗行为。
        assertTrue(js.contains("__kmpOrigWindowOpen"), "必须暂存并回落原 window.open")
    }

    @Test
    fun windowOpenHook_installsIdempotently() {
        val js = DownloadScripts.WINDOW_OPEN_DOWNLOAD_HOOK_JS
        // onPageStarted 可能多次注入（重定向/刷新），重复安装会把上一次的 patch 当成"原始 open"
        // 层层套娃，必须有安装标记短路。
        assertTrue(js.contains("__kmpWindowOpenHookInstalled"), "必须幂等安装")
    }
}
