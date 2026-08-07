package wang.harlon.webview.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WebViewStateTest {

    @Test
    fun urlSourceSeedsLoadUrlCommandAndCurrentUrl() {
        val state = WebViewState(WebViewSource.Url("https://example.com/a"), "KmpBridge")
        assertEquals("https://example.com/a", state.currentUrl)
        assertEquals(WebViewCommand.LoadUrl("https://example.com/a"), state.pendingCommand)
    }

    @Test
    fun htmlSourceSeedsLoadHtmlCommandAndReportsBaseUrlAsCurrentUrl() {
        // currentUrl 是"页面在哪"的展示语义；Html 模式下页面就活在 baseUrl 的 origin 上。
        val state = WebViewState(WebViewSource.Html("<p>hi</p>", "https://origin.test/"), "KmpBridge")
        assertEquals("https://origin.test/", state.currentUrl)
        assertEquals(WebViewCommand.LoadHtml("<p>hi</p>", "https://origin.test/"), state.pendingCommand)
    }

    @Test
    fun loadHtmlRequiresHttpBaseUrlAndNonEmptyHtml() {
        val state = WebViewState(WebViewSource.Url("https://example.com"), "KmpBridge")
        state.consumeCommand()

        // baseUrl 唯一的作用是给页面一个合法 origin；file:// / 空串起不到这个作用（file:// 的
        // origin 恰恰就是被绕开的那个 "null"），故直接忽略而非静默降级。
        state.loadHtml("<p>hi</p>", "file:///tmp/a.html")
        assertNull(state.pendingCommand)
        state.loadHtml("<p>hi</p>", "")
        assertNull(state.pendingCommand)
        state.loadHtml("", "https://origin.test/")
        assertNull(state.pendingCommand)

        state.loadHtml("<p>hi</p>", "https://origin.test/")
        assertIs<WebViewCommand.LoadHtml>(state.pendingCommand)
    }

    @Test
    fun defaultNamespaceDerivesLegacyChannel() {
        assertEquals("__kmpBridgeNative", deriveBridgeChannel("KmpBridge"))
    }

    @Test
    fun customNamespaceDerivesMatchingChannel() {
        assertEquals("__customBridgeNative", deriveBridgeChannel("CustomBridge"))
        assertEquals("__myAppBridgeNative", deriveBridgeChannel("MyAppBridge"))
    }

    @Test
    fun derivationLowercasesOnlyFirstChar() {
        // 内部其他大写字母保留，避免破坏 camelCase 可读性
        assertEquals("__aBCNative", deriveBridgeChannel("ABC"))
    }

    @Test
    fun derivationPreservesAlreadyLowercaseAndSpecialPrefix() {
        assertEquals("__bridgeNative", deriveBridgeChannel("bridge"))
        // `$` / `_` 已经"无大小写"，保留原样
        assertEquals("__\$bridgeNative", deriveBridgeChannel("\$bridge"))
        assertEquals("___internalNative", deriveBridgeChannel("_internal"))
    }
}
