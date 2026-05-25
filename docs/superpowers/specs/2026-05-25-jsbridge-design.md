# JSBridge 设计方案

- **日期**：2026-05-25
- **作者**：HarlonWang
- **状态**：设计已定，待实现

## 1. 目标

为 `kmp-webview` SDK 增加 JS ↔ Native 双向通信通道（JSBridge），让接入业务方能够：

1. 在 Native 侧注册自定义 handler，供页面 JS 通过 Promise 调用
2. 从 Native 主动向页面 JS 推送事件
3. 通过 origin 白名单约束桥的可访问范围

设计上保持 SDK 轻量调性：不引入 `kotlinx.serialization`、`androidx.webkit`，双端基于官方 WebView 原生通道实现。

## 2. 决策汇总

| 维度 | 选择 | 理由 |
|---|---|---|
| API 模式 | 业务方注册自定义 handler；SDK 仅提供内核 | 最常见的 JSBridge 形态；内置能力放到后续独立迭代 |
| JS → Native | `await bridge.call(method, params)` → Promise | 现代 JSBridge 默认形态 |
| Native → JS | 事件推送 `bridge.on(event, cb)` | 满足登录态、生命周期等推送场景 |
| handler 作用域 | 绑定到 `WebViewState` 实例 | 与现有 SDK API 风格一致；多 WebView 自然隔离 |
| 安全模型 | origin 白名单 API，默认不限制 | 遵循「默认能用，需要时加锁」 |
| handler 签名 | `suspend (paramsJson: String?) -> String?` | Kotlin 协程项目自然形态；返回值自动 resolve、异常自动 reject |
| 序列化 | 原生 JSON String | SDK 零额外依赖；业务方自选 JSON 库 |
| 底层传输 | Android `addJavascriptInterface` + iOS `WKScriptMessageHandler` | 官方标准通道，双端对称，无新增依赖 |

底层传输方案的替代选项 + 排除理由见 [§7 备选方案对比](#7-备选方案对比)。

## 3. 对外 API

### 3.1 JS 端

JS 端通过注入的全局对象 `window.KmpBridge` 访问桥：

```js
// JS → Native
const token = await KmpBridge.call('getToken', { scope: 'user' });
KmpBridge.call('share', payload).catch(e => {
  // e.code: 'HANDLER_NOT_FOUND' | 'INVALID_PARAMS' | 'HANDLER_ERROR'
  //       | 'ORIGIN_DENIED' | 'BRIDGE_DISPOSED' | 业务自定义码
  // e.message: string
});

// Native → JS
const off = KmpBridge.on('auth.changed', payload => { /* ... */ });
off();                          // 取消订阅
KmpBridge.once('app.resume', cb);
```

`call(method, params)`：
- `params` 可为任意可 JSON 序列化的值，或 `undefined`/`null`
- 返回 `Promise<any | null>`：`any` 为 handler 返回 JSON 反序列化的结果，`null` 表示 handler 无返回

`on(event, cb)`：返回 `off()` 函数。同一事件可多次订阅，按订阅顺序触发。

### 3.2 Native（Kotlin）端

```kotlin
val state = rememberWebViewState("https://example.com")
val bridge = state.jsBridge

bridge.registerHandler("getToken") { paramsJson: String? ->
    val token = authRepo.fetchToken()
    """{"token":"$token"}"""
}

bridge.registerHandler("share") { params ->
    if (params == null) throw JsBridgeException("INVALID_PARAMS", "params required")
    shareService.share(params)
    null  // 表示 void
}

bridge.unregisterHandler("getToken")
bridge.emit("auth.changed", """{"loggedIn":true}""")
bridge.setAllowedOrigins(setOf("https://example.com", "https://*.example.com"))
```

类型签名：

```kotlin
class JsBridge internal constructor(/* ... */) {
    fun registerHandler(method: String, handler: suspend (String?) -> String?)
    fun unregisterHandler(method: String)
    fun emit(event: String, payloadJson: String? = null)
    fun setAllowedOrigins(origins: Set<String>?)  // null = 不限
}

class JsBridgeException(val code: String, message: String) : RuntimeException(message)
```

## 4. 架构

```
┌──────────────────────── commonMain ────────────────────────┐
│  WebViewState                                              │
│    └─ jsBridge: JsBridge ──────────────┐                   │
│                                        │                   │
│  JsBridge                              │                   │
│    ├─ handlers: Map<String, suspend>   │                   │
│    ├─ allowedOrigins: Set<String>?     │                   │
│    ├─ scope: CoroutineScope            │                   │
│    └─ evaluator: (String) -> Unit  ◀───┘ 由平台 binder 注入 │
└────────────────────────────┬───────────────────────────────┘
                             │ expect/actual
              ┌──────────────┴──────────────┐
              ▼                             ▼
   ┌─────── androidMain ─────┐    ┌──────── iosMain ────────┐
   │ JsBridgeAndroidBinder   │    │ JsBridgeIosBinder       │
   │  ├─ addJavascriptInter- │    │  ├─ WKUserContent-      │
   │  │  face(NativeChannel) │    │  │  Controller.add(     │
   │  └─ WebViewClient       │    │  │   scriptMessage-     │
   │     .onPageStarted →    │    │  │   Handler, name)     │
   │     evaluateJs(SHIM)    │    │  └─ WKUserScript(SHIM,  │
   │                         │    │     atDocumentStart)    │
   └─────────────────────────┘    └─────────────────────────┘
                             │
                             ▼
                       WebView / WKWebView
                             │
                             ▼
                     window.KmpBridge（垫片）
```

`JsBridge` 内核位于 `commonMain`，平台 binder 仅负责：
- 注入 `__kmpBridgeNative` 原生通道
- 注入 `KMP_BRIDGE_SHIM_JS` 垫片
- 把 `evaluator: (String) -> Unit` 回填到 `JsBridge`，用于触发 `evaluateJavascript`
- 把 JS 发来的消息透传到 `JsBridge.dispatchIncoming(json, originProvider)`

## 5. JS 垫片

垫片作为 `commonMain` 常量字符串：

```js
(function () {
  if (window.KmpBridge) return;
  var nextId = 1;
  var pending = {};
  var listeners = {};

  function postRaw(json) {
    if (window.__kmpBridgeNative && window.__kmpBridgeNative.postMessage) {
      window.__kmpBridgeNative.postMessage(json);                              // Android
    } else if (window.webkit && window.webkit.messageHandlers &&
               window.webkit.messageHandlers.__kmpBridgeNative) {
      window.webkit.messageHandlers.__kmpBridgeNative.postMessage(json);       // iOS
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
          params: params == null ? null : JSON.stringify(params),
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
      else p.reject(Object.assign(new Error(v && v.message || ''), { code: v && v.code }));
    },
    __emit: function (event, payload) {
      var arr = listeners[event]; if (!arr || !arr.length) return;
      var v = null;
      if (payload != null) { try { v = JSON.parse(payload); } catch (e) { v = payload; } }
      arr.slice().forEach(function (cb) { try { cb(v); } catch (e) {} });
    },
  };
})();
```

## 6. 双端实现

### 6.1 Android

```kotlin
internal class JsBridgeAndroidBinder(
    private val webView: WebView,
    private val bridge: JsBridge,
) {
    init {
        webView.addJavascriptInterface(NativeChannel(), "__kmpBridgeNative")
        bridge.attachEvaluator { js ->
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    fun injectShim() {
        webView.evaluateJavascript(KMP_BRIDGE_SHIM_JS, null)
    }

    fun dispose() {
        webView.removeJavascriptInterface("__kmpBridgeNative")
        bridge.detachEvaluator()
    }

    private inner class NativeChannel {
        @JavascriptInterface
        fun postMessage(json: String) {
            // 在 WebView 自己的 JsBridge 线程被调用
            bridge.dispatchIncoming(json) { webView.url }
        }
    }
}
```

**注入时机**：`SdkWebViewClient.onPageStarted` 调 `binder.injectShim()`。

**已知限制（接受）**：`onPageStarted` 严格意义上不等于 `document_start`，对于在 `<head>` 内同步执行 inline `<script>` 立即调用 `KmpBridge.*` 的页面有微小概率失败。业务方可在自己的 JS 中做兜底检查：

```js
if (window.KmpBridge) { /* ... */ }
else { /* 推迟到 DOMContentLoaded 或 polling */ }
```

后续若有强需求，可升级到 `WebMessageListener`（方案 C，需引入 `androidx.webkit`）。

**iframe 防御**：`addJavascriptInterface` 没有 frame 粒度，所有 frame 都能拿到 `__kmpBridgeNative`。这是为什么 origin 白名单功能存在 —— Android 上必须通过白名单防御第三方 iframe 滥用桥。

### 6.2 iOS

```kotlin
@OptIn(ExperimentalForeignApi::class)
internal class JsBridgeIosBinder(
    private val webView: WKWebView,
    private val bridge: JsBridge,
) : NSObject(), WKScriptMessageHandlerProtocol {

    init {
        val ucc = webView.configuration.userContentController
        ucc.addUserScript(WKUserScript(
            source = KMP_BRIDGE_SHIM_JS,
            injectionTime = WKUserScriptInjectionTimeAtDocumentStart,
            forMainFrameOnly = true,
        ))
        ucc.addScriptMessageHandler(this, name = "__kmpBridgeNative")
        bridge.attachEvaluator { js ->
            dispatch_async(dispatch_get_main_queue()) {
                webView.evaluateJavaScript(js, completionHandler = null)
            }
        }
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val body = didReceiveScriptMessage.body as? String ?: return
        bridge.dispatchIncoming(body) { webView.URL?.absoluteString }
    }

    fun dispose() {
        webView.configuration.userContentController
            .removeScriptMessageHandlerForName("__kmpBridgeNative")
        bridge.detachEvaluator()
    }
}
```

**与 Android 的不对称（在 KDoc 标注）**：
- iOS 用 `AtDocumentStart` 真正在 `<head>` 之前就绪
- iOS `forMainFrameOnly = true` 屏蔽 iframe；Android 没有等价能力

## 7. 备选方案对比

| 方案 | Android | iOS | 排除理由 |
|---|---|---|---|
| **A（采用）** 原生注入对象 + JS 垫片 | `addJavascriptInterface` | `WKScriptMessageHandler` | — |
| B URL Scheme 拦截 | `WebViewClient.shouldOverrideUrlLoading` | `WKNavigationDelegate.decidePolicyFor` | URL 长度限制（Android ≈ 8K）、需 URL 编码、隐式 navigation 副作用、性能差 |
| C `WebMessageListener`（Android 升级版） | `WebViewCompat.addWebMessageListener` | 同 A | Android 端更安全 + 文档级注入精确；但要新增 `androidx.webkit` 依赖。**作为未来升级路径，本期不引入**。 |

`addJavascriptInterface` 历史上的 RCE 漏洞（CVE-2012-6636）已在 Android 4.2+ 被修复（只有 `@JavascriptInterface` 注解方法对外）。本 SDK `minSdk 24`，且 `NativeChannel` 仅暴露单个 `postMessage(String)`，无安全风险。

## 8. 错误模型

| code | 触发场景 | 抛出方 |
|---|---|---|
| `HANDLER_NOT_FOUND` | method 未注册 | SDK |
| `INVALID_PARAMS` | 业务 handler 抛 `JsBridgeException("INVALID_PARAMS", ...)` | 业务 |
| `HANDLER_ERROR` | handler 内部抛任意非 `JsBridgeException` 异常 | SDK 兜底 |
| `ORIGIN_DENIED` | 当前 WebView URL origin 不在白名单 | SDK |
| `BRIDGE_DISPOSED` | `WebViewState` 已释放后仍收到消息（极少见竞态） | SDK |
| 业务自定义码 | 业务 handler 抛 `JsBridgeException("CUSTOM_X", ...)` | 业务 |

**约束**：`code` 必须为 ASCII 大写字母 + 下划线；SDK 不把内置码做成 enum，避免业务码受版本升级限制。

**`dispatchIncoming` 异常分类规则**：

```kotlin
try {
    val result = handler(params)
    resolve(id, ok = true, result)
} catch (e: CancellationException) {
    throw e  // 合作取消，向上传播
} catch (e: JsBridgeException) {
    resolve(id, ok = false, """{"code":"${e.code}","message":${quote(e.message)}}""")
} catch (e: Throwable) {
    resolve(id, ok = false, """{"code":"HANDLER_ERROR","message":${quote(e.message)}}""")
}
```

## 9. 生命周期

```
WebViewState 创建
  └─ JsBridge 懒初始化（首次访问 state.jsBridge）
       ├─ scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
       ├─ handlers / allowedOrigins 空状态
       └─ evaluator = no-op（暂未 attach）

WebViewScreen 进入组合，PlatformWebView 创建 WebView
  └─ binder 创建
       ├─ 注入 __kmpBridgeNative + 垫片
       └─ bridge.attachEvaluator { evaluateJavascript(...) }

PlatformWebView onRelease
  ├─ Android: removeJavascriptInterface + bridge.detachEvaluator()
  ├─ iOS:    removeScriptMessageHandlerForName + bridge.detachEvaluator()
  └─ in-flight handler 继续跑完，但 resolve 时 evaluator 已是 no-op，结果丢弃

WebViewState 离开 Compose 组合
  └─ JsBridge 实现 RememberObserver，在 onForgotten/onAbandoned 时 scope.cancel()
     取消所有挂起的 handler（CancellationException）
```

**关键决策**：
- `JsBridge` 生命周期 ≡ `WebViewState`（长于 WebView 实例）。WebView 重建时 handler/白名单配置保留
- WebView 重建时 binder 重新创建并重新注入垫片
- scope 清理通过 `RememberObserver` 触发，不依赖 GC 时机
- **不主动取消 in-flight handler 的副作用** —— scope cancel 后 handler 会收到 CancellationException，由业务方决定是否 cooperative cancel；SDK 不强行打断
- handler 在 `Dispatchers.Main.immediate` scope 上 `launch`；业务方需要切线程请自己 `withContext(Dispatchers.IO)`

## 10. handler 重复注册策略

- 后注册覆盖前注册，旧 handler 不再被调用
- 通过 `println` 输出警告：`"JsBridge: handler 'getToken' replaced"`（本期不引入 logger 抽象，避免范围蠕变；如未来 SDK 整体接入日志门面再迁移）
- 不抛异常，避免热加载/HMR 场景误伤

## 11. emit 投递语义

- `emit()` 是 fire-and-forget，无投递确认
- 调用时 WebView 未 ready（垫片未注入 / 页面未加载）→ `evaluateJavascript` 静默 no-op（WebView 原生行为）
- **不做事件缓冲**——避免无界内存增长 + 语义复杂。业务方若需要「页面加载后获取最新状态」，请用 JS 主动 `call('getXxx')` 拉取

## 12. origin 白名单

- 配置：`bridge.setAllowedOrigins(Set<String>?)`，`null` = 不限（默认）
- 匹配规则：
  - 精确匹配：`"https://example.com"` ↔ origin `"https://example.com"`
  - 通配子域：`"https://*.example.com"` ↔ `"https://a.example.com"`、`"https://a.b.example.com"`（任意层级）
  - 协议必须一致；端口必须一致（缺省即缺省）
- 校验时机：每次 `dispatchIncoming` 进入时，从 WebView 当前 URL 解析 origin
  - Android：`webView.url`
  - iOS：`webView.URL?.absoluteString`
- 不匹配 → 直接 resolve `{ok:false, code:"ORIGIN_DENIED"}`，不触发 handler

## 13. 测试策略

| 层 | 方式 | 覆盖 |
|---|---|---|
| `JsBridge` 内核（commonTest） | unit test + mock evaluator | 解析、路由、异常映射、origin 校验、emit 拼装、并发 launch |
| JS 垫片 | 演示页面手测 | Promise resolve/reject、on/off/once、错误码透传 |
| Android binder | androidUnitTest（可选 Robolectric）或省略转手测 | `addJavascriptInterface` 接线、onPageStarted 注入 |
| iOS binder | 不写自动化测试 | iOS KMP 测试 WKWebView 设施薄弱，ROI 低 |
| 集成手测 | `androidApp`/`iosApp` 各加 JsBridgeDemo 屏 | 双端行为一致性 + 生命周期 |

**新增依赖**：仅 `kotlinx-coroutines-test`（commonTest），通过 `gradle/libs.versions.toml` 声明。

## 14. 不在本期范围（YAGNI）

- Native → JS 带返回值的双向 call（emit 已能覆盖大多数场景）
- handler 命名空间 / scoped bridge（业务方用 `auth.getToken` 点号约定即可）
- 内置「常用 handler」（closeWebView、setTitle、getDeviceInfo 等）——后续单独迭代
- JS 端 TypeScript `.d.ts` 类型定义
- Web Workers / iframe 内的桥支持
- 事件缓冲 / replay

## 15. 文件改动清单

新增：
- `library/src/commonMain/kotlin/wang/harlon/webview/bridge/JsBridge.kt`
- `library/src/commonMain/kotlin/wang/harlon/webview/bridge/JsBridgeException.kt`
- `library/src/commonMain/kotlin/wang/harlon/webview/bridge/KmpBridgeShim.kt`（垫片 JS 字符串常量）
- `library/src/commonMain/kotlin/wang/harlon/webview/bridge/JsBridgeBinder.kt`（expect）
- `library/src/androidMain/kotlin/wang/harlon/webview/bridge/JsBridgeBinder.android.kt`
- `library/src/iosMain/kotlin/wang/harlon/webview/bridge/JsBridgeBinder.ios.kt`
- `library/src/commonTest/kotlin/wang/harlon/webview/bridge/JsBridgeTest.kt`

修改：
- `library/src/commonMain/kotlin/wang/harlon/webview/core/WebViewState.kt` — 新增 `jsBridge` 字段，scope 集成
- `library/src/androidMain/kotlin/wang/harlon/webview/platform/PlatformWebView.android.kt` — 集成 binder
- `library/src/androidMain/kotlin/wang/harlon/webview/platform/SdkWebViewClient.kt` — `onPageStarted` 触发垫片注入
- `library/src/iosMain/kotlin/wang/harlon/webview/platform/PlatformWebView.ios.kt` — 集成 binder
- `gradle/libs.versions.toml` — 新增 `kotlinx-coroutines-test` alias
- `library/build.gradle.kts` — commonTest 依赖
- `androidApp` / `iosApp` — 新增 JsBridge 演示屏与本地 HTML
- `README.md` — 新增「JSBridge」章节
