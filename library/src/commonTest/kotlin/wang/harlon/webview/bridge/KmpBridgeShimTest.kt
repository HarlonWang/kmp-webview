package wang.harlon.webview.bridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KmpBridgeShimTest {

    @Test
    fun defaultArgsProduceLegacyKmpBridgeShim() {
        val shim = buildKmpBridgeShim("KmpBridge", "__kmpBridgeNative")

        assertTrue("if (window.KmpBridge) return;" in shim)
        assertTrue("window.KmpBridge =" in shim)
        assertTrue("window.__kmpBridgeNative.postMessage" in shim)
        assertTrue("window.webkit.messageHandlers.__kmpBridgeNative" in shim)
        assertTrue("new Event('KmpBridgeReady')" in shim)
        assertTrue("'KmpBridge native channel not available'" in shim)
    }

    @Test
    fun customArgsReplaceNamespaceAndChannelEverywhere() {
        val shim = buildKmpBridgeShim("CustomBridge", "__customNativeChannel")

        // 业务接触面 / 内部引用都换名
        assertTrue("if (window.CustomBridge) return;" in shim)
        assertTrue("window.CustomBridge =" in shim)
        assertTrue("window.CustomBridge.on(event," in shim, "once 内部对 namespace 的引用要跟随")
        // Android 路径
        assertTrue("window.__customNativeChannel.postMessage" in shim)
        // iOS 路径
        assertTrue("window.webkit.messageHandlers.__customNativeChannel" in shim)
        // 就绪事件名
        assertTrue("new Event('CustomBridgeReady')" in shim)
        assertTrue("addEventListener('CustomBridgeReady'" in shim)
        // 错误提示带 namespace
        assertTrue("'CustomBridge native channel not available'" in shim)

        // 老名字不应残留
        assertFalse("KmpBridge" in shim, "残留旧 namespace: $shim")
        assertFalse("__kmpBridgeNative" in shim, "残留旧 channel: $shim")
    }
}
