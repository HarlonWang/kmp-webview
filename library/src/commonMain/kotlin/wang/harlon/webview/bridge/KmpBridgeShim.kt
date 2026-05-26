package wang.harlon.webview.bridge

/**
 * 注入到页面的 JS 垫片，按传入的 [namespace] / [channel] 模板化拼装：
 *   - 在页面上暴露 `window.{namespace}`：
 *     * `call(method, params)` → Promise
 *     * `on(event, cb)` / `once(event, cb)` / 返回 `off()`
 *     * 内部 `__resolve(id, ok, payload)` / `__emit(event, payload)` 由 Native 调用
 *   - 通过 `window.{channel}.postMessage(json)`（Android）或
 *     `window.webkit.messageHandlers.{channel}.postMessage(json)`（iOS）发出消息
 *   - 注入完成时派发 `{namespace}Ready` 事件，便于业务方在 inline script 早于 shim 时兜底
 *
 * 调用方需保证 [namespace] / [channel] 都是合法 JS 标识符（由 [JsBridge] / 各 binder 在构造期校验）。
 */
internal fun buildKmpBridgeShim(namespace: String, channel: String): String = """
(function () {
  if (window.$namespace) return;
  var nextId = 1;
  var pending = {};
  var listeners = {};
  function postRaw(json) {
    if (window.$channel && window.$channel.postMessage) {
      window.$channel.postMessage(json);
    } else if (window.webkit && window.webkit.messageHandlers &&
               window.webkit.messageHandlers.$channel) {
      window.webkit.messageHandlers.$channel.postMessage(json);
    } else {
      throw new Error('$namespace native channel not available');
    }
  }
  window.$namespace = {
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
      var off = window.$namespace.on(event, function (p) { off(); cb(p); });
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
  // 通知页面：bridge 已就绪。Android 上注入晚于同步 inline <script> 的场景，
  // 业务方可监听此事件兜底：if (window.$namespace) init(); else addEventListener('${namespace}Ready', init);
  try { window.dispatchEvent(new Event('${namespace}Ready')); } catch (e) {}
})();
"""
