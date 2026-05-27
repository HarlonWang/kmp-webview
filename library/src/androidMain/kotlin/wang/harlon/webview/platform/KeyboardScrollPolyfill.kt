package wang.harlon.webview.platform

import android.webkit.WebView

/**
 * 键盘弹起时，把焦点输入框滚入可视区。
 *
 * 系统 WebView 的"键盘弹起 → 自动 resize visual viewport + scroll focused input"
 * 是 Chromium M139+ 才完整实现的特性；线上相当多设备的 WebView 版本低于 M139，
 * imePadding 收缩 WebView 后 vv 跟着变小，但焦点输入框不会主动滚出，会被键盘遮。
 * 这段 polyfill 监听 visualViewport.resize / focusin，当焦点输入框落在键盘覆盖区时
 * 调 scrollIntoView 兜底，恢复"input 不被键盘遮"的预期体验。
 *
 * IIFE + 全局标志保证同一 document 内只装一次；每次新文档由
 * SdkWebViewClient.onPageStarted 重新注入。
 */
internal fun WebView.injectKeyboardScrollPolyfill() {
    evaluateJavascript(KEYBOARD_SCROLL_POLYFILL_JS, null)
}

private val KEYBOARD_SCROLL_POLYFILL_JS = """
(function () {
  if (window.__kmpKbdScrollInstalled) return;
  window.__kmpKbdScrollInstalled = true;
  var vv = window.visualViewport;
  if (!vv) return;
  function isField(el) {
    if (!el || el === document.body || el === document.documentElement) return false;
    var t = el.tagName;
    return t === 'INPUT' || t === 'TEXTAREA' || el.isContentEditable;
  }
  var rafId = 0;
  function ensureVisible() {
    if (rafId) return;
    rafId = requestAnimationFrame(function () {
      rafId = 0;
      var ae = document.activeElement;
      if (!isField(ae)) return;
      var r = ae.getBoundingClientRect();
      var top = vv.offsetTop;
      var bot = vv.offsetTop + vv.height;
      // 留 8px 边距防 caret 紧贴键盘
      if (r.bottom > bot - 8 || r.top < top + 8) {
        ae.scrollIntoView({ block: 'center', behavior: 'instant' });
      }
    });
  }
  vv.addEventListener('resize', ensureVisible);
  // 兜底：focus 早于键盘弹起时，focusin 触发后等 vv 落定再判一次
  document.addEventListener('focusin', function () {
    setTimeout(ensureVisible, 150);
  });
})();
""".trimIndent()
