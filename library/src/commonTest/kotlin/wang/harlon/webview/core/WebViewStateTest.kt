package wang.harlon.webview.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WebViewStateTest {

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
