package wang.harlon.webview.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebViewConfigTest {

    @Test
    fun defaultsPreserveLegacyCloseIcon() {
        // 新增 closeIconStyle 字段必须保持默认 Close 行为——这是公共 SDK 兼容性硬要求：
        // 既有调用方不传新字段时，根页导航图标与之前完全一致（Icons.Filled.Close）。
        assertEquals(CloseIconStyle.Close, WebViewConfig().closeIconStyle)
    }

    @Test
    fun defaultsRemainSafeForProduction() {
        val config = WebViewConfig()
        // release 包绝不应默认打开调试相关开关，避免新接入方误用。
        assertFalse(config.enableRemoteDebugging)
        assertFalse(config.enableLogPanel)
        // defaultHttpHeaders 默认空 map，避免悄悄给主文档加 header。
        assertTrue(config.defaultHttpHeaders.isEmpty())
        // titleOverride 默认 null，title 走 state.title。
        assertNull(config.titleOverride)
    }
}
