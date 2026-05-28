package wang.harlon.webview.logpanel

/**
 * 注入到 H5 页面的 JS 垫片：
 *   - 包装 `console.log/info/warn/error`，原版仍调用，复制一份发 native
 *   - 监听 `window.onerror` 抓未捕获异常（含 stack）
 *   - 监听 `unhandledrejection` 抓未处理的 Promise rejection
 *
 * 与 Native 的通道协议（JSON，运行时探测平台分支）：
 *   - Android: `window.{channel}.postMessage(json)`
 *   - iOS:     `window.webkit.messageHandlers.{channel}.postMessage(json)`
 *
 * 消息形状：
 *   - `{ kind: "console", level: "log"|"info"|"warn"|"error", args: [str, ...] }`
 *   - `{ kind: "jserror", message, src, line, col, stack }`
 *   - `{ kind: "rejection", message, stack }`
 *
 * 整段 IIFE 用 `__kmpLogShimInstalled` guard 防止重复注入（Android 上每个新文档
 * 生命周期都会 evaluateJavascript 一次，iOS 上 WKUserScript 跨 frame 也可能多次执行）。
 *
 * channel 名由调用方传入，Android 与 iOS 端独立注册（不复用 JSBridge 的 channel，
 * 避免日志面板未启用时仍需保留 handler）。固定值 `__kmpLog`，详见 [LOG_CHANNEL]。
 */
internal fun buildLogShim(channel: String): String = """
(function () {
  if (window.__kmpLogShimInstalled) return;
  window.__kmpLogShimInstalled = true;

  function post(json) {
    try {
      if (window.$channel && window.$channel.postMessage) {
        window.$channel.postMessage(json);
      } else if (window.webkit && window.webkit.messageHandlers &&
                 window.webkit.messageHandlers.$channel) {
        window.webkit.messageHandlers.$channel.postMessage(json);
      }
    } catch (_) {}
  }

  function safeStringify(v) {
    if (typeof v === 'string') return v;
    try { return JSON.stringify(v); } catch (_) { return String(v); }
  }

  var levels = ['log', 'info', 'warn', 'error'];
  for (var i = 0; i < levels.length; i++) {
    (function (level) {
      var orig = console[level];
      console[level] = function () {
        try {
          var args = [];
          for (var j = 0; j < arguments.length; j++) args.push(safeStringify(arguments[j]));
          post(JSON.stringify({ kind: 'console', level: level, args: args }));
        } catch (_) {}
        if (orig) orig.apply(console, arguments);
      };
    })(levels[i]);
  }

  window.addEventListener('error', function (e) {
    try {
      post(JSON.stringify({
        kind: 'jserror',
        message: e && e.message,
        src: e && e.filename,
        line: e && e.lineno,
        col: e && e.colno,
        stack: e && e.error && e.error.stack
      }));
    } catch (_) {}
  });

  window.addEventListener('unhandledrejection', function (e) {
    try {
      var r = e && e.reason;
      post(JSON.stringify({
        kind: 'rejection',
        message: r && r.message ? r.message : String(r),
        stack: r && r.stack
      }));
    } catch (_) {}
  });
})();
"""

/** 日志面板 native 通道名，双端独立注册。 */
internal const val LOG_CHANNEL = "__kmpLog"
