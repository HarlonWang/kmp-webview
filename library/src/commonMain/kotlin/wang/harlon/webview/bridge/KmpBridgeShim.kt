package wang.harlon.webview.bridge

/**
 * 注入到页面的 JS 垫片，暴露 `window.KmpBridge`：
 *   - `call(method, params)` → Promise
 *   - `on(event, cb)` / `once(event, cb)` / 返回 `off()`
 *   - 内部 `__resolve(id, ok, payload)` / `__emit(event, payload)` 由 Native 调用
 *
 * 通过 `window.__kmpBridgeNative.postMessage(json)`（Android）或
 * `window.webkit.messageHandlers.__kmpBridgeNative.postMessage(json)`（iOS）发出消息。
 */
internal const val KMP_BRIDGE_SHIM_JS: String = """
(function () {
  if (window.KmpBridge) return;
  var nextId = 1;
  var pending = {};
  var listeners = {};
  function postRaw(json) {
    if (window.__kmpBridgeNative && window.__kmpBridgeNative.postMessage) {
      window.__kmpBridgeNative.postMessage(json);
    } else if (window.webkit && window.webkit.messageHandlers &&
               window.webkit.messageHandlers.__kmpBridgeNative) {
      window.webkit.messageHandlers.__kmpBridgeNative.postMessage(json);
    } else {
      throw new Error('KmpBridge native channel not available');
    }
  }
  window.KmpBridge = {
    call: function (method, params) {
      return new Promise(function (resolve, reject) {
        var id = nextId++;
        pending[id] = { resolve: resolve, reject: reject };
        postRaw(JSON.stringify({
          id: id, method: method,
          params: params == null ? null : JSON.stringify(params)
        }));
      });
    },
    on: function (event, cb) {
      (listeners[event] = listeners[event] || []).push(cb);
      return function off() {
        listeners[event] = (listeners[event] || []).filter(function (x) { return x !== cb; });
      };
    },
    once: function (event, cb) {
      var off = window.KmpBridge.on(event, function (p) { off(); cb(p); });
      return off;
    },
    __resolve: function (id, ok, payload) {
      var p = pending[id]; if (!p) return; delete pending[id];
      var v = null;
      try { v = payload == null ? null : JSON.parse(payload); }
      catch (e) { p.reject(new Error('Invalid payload JSON')); return; }
      if (ok) p.resolve(v);
      else {
        var err = new Error(v && v.message || '');
        err.code = v && v.code;
        p.reject(err);
      }
    },
    __emit: function (event, payload) {
      var arr = listeners[event]; if (!arr || !arr.length) return;
      var v = null;
      if (payload != null) { try { v = JSON.parse(payload); } catch (e) { v = payload; } }
      arr.slice().forEach(function (cb) { try { cb(v); } catch (e) {} });
    }
  };
  // 通知页面：KmpBridge 已就绪。Android 上注入晚于同步 inline <script> 的场景，
  // 业务方可监听此事件兜底：if (window.KmpBridge) init(); else addEventListener('KmpBridgeReady', init);
  try { window.dispatchEvent(new Event('KmpBridgeReady')); } catch (e) {}
})();
"""
