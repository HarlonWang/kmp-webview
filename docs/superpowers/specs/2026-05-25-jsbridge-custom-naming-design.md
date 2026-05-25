# JSBridge 自定义命名（namespace / channel）设计方案

- **日期**：2026-05-25
- **作者**：HarlonWang
- **状态**：已实现（首版）
- **关联**：[2026-05-25-jsbridge-design.md](./2026-05-25-jsbridge-design.md)（基础 JSBridge 设计）

## 更新记录

- **2026-05-25 决策变更**：本期仅暴露 `bridgeNamespace`，**不开放 `bridgeChannel` 单独自定义**。native channel 名由 namespace 派生：`"__" + bridgeNamespace.replaceFirstChar { it.lowercase() } + "Native"`（默认 `KmpBridge` → `__kmpBridgeNative` 向前兼容；自定义 `CustomBridge` → `__customBridgeNative`）。理由：99% 场景只需要换 JS 端名字，channel 单独自定义增加了接入面与心智成本却无明确需求；遵循 YAGNI，未来真有需要再加。
  - 文档以下章节中标 ⛔️ 的条目均**已在实现里跳过**，保留作为未来扩展参考。

## 1. 目标

让 `kmp-webview` SDK 的 JSBridge 支持**业务方自定义命名**——既能自定义 JS 端访问入口名（`window.KmpBridge` → 任意业务名），也能自定义 native channel 名（`__kmpBridgeNative` → 任意名）。

满足以下接入场景：

- 多 App 共享 SDK 时，各 App 用自己的品牌名（`FooBridge` / `BarBridge`）
- 同一 App 多构建（debug build 用 `KmpBridgeDev`，prod 用 `KmpBridge`），避免互串
- H5 已被某个第三方 SDK 占了默认名时回避命名冲突
- 接入既有 H5 协议时，按 H5 期望的命名注入

**非目标**：

- 不支持运行时改名（实例创建后名字不可变）
- 不支持单一 WebView 同时挂多个 bridge 实例（YAGNI；未来真需要再扩展）
- 不解决"自定义协议"问题（如 DSBridge 同步 `call`）；本方案只改名字，**不改协议契约**

## 2. 决策汇总

| 维度 | 选择 | 理由 |
|---|---|---|
| 命名字段数 | **单字段** `bridgeNamespace`（channel 自动派生）| 见"更新记录"——本期 YAGNI；保留 `bridgeChannel` 单独自定义为未来扩展点 |
| 字段挂载位置 | `rememberWebViewState` 参数，**不放** `WebViewConfig` | bridge 配置是实例期、`WebViewConfig` 是渲染期；`jsBridge` 创建时就要确定 namespace |
| 默认值 | `bridgeNamespace = "KmpBridge"` → 派生 channel = `__kmpBridgeNative` | 派生规则首字母小写保证默认值不变，老调用方零改动 |
| Channel 派生规则 | `"__" + namespace.replaceFirstChar { it.lowercase() } + "Native"` | 默认行为不变；自定义 namespace 时 channel 自动跟随 |
| 就绪事件名 | 跟随 namespace：`{namespace}Ready`（默认仍 `KmpBridgeReady`）| 前端 adapter 能按 state 配置监听对应事件 |
| Shim 实现形态 | const string → 函数 `buildKmpBridgeShim(namespace, channel)` | 模板化注入，运行时按参数拼装；channel 参数留作内部使用 |
| 字段校验 | 构造期对 `bridgeNamespace` `require` JS 标识符 regex；channel 派生天然合法不再单独校验 | 校验链一处即可保证两个值都合法 |
| Binder 接 namespace 方式 | 从 `JsBridge.namespace` 拿（`internal val`），channel 从 `WebViewState.bridgeChannel` 派生后传入 | 减少接口面 + 强耦合本来就存在 |

## 3. 对外 API

### 3.1 接入示例

```kotlin
val state = rememberWebViewState(
    initialUrl = "https://example.com",
    bridgeNamespace = "CustomBridge",
)
// 派生：state.bridgeChannel == "__customBridgeNative"（internal，仅 binder 用）

state.jsBridge.registerHandler("getUserInfo") { _ -> userInfoJson }

WebViewScreen(state = state, config = WebViewConfig(), onCloseRequest = { ... })
```

JS 端：

```js
// 业务接触面跟随 bridgeNamespace
const user = await window.CustomBridge.call('getUserInfo', null);

// 就绪事件名跟随 bridgeNamespace
window.addEventListener('CustomBridgeReady', () => {
    // bridge 已可用
});
```

### 3.2 完整签名

```kotlin
@Composable
fun rememberWebViewState(
    initialUrl: String,
    bridgeNamespace: String = "KmpBridge",
): WebViewState
```

## 4. 实现细节

涉及 6 个文件改动：

### 4.1 `bridge/KmpBridgeShim.kt`：const 改函数

```kotlin
// 改前
internal const val KMP_BRIDGE_SHIM_JS: String = """
(function () {
  if (window.KmpBridge) return;
  // ... 所有 "KmpBridge" / "__kmpBridgeNative" / "KmpBridgeReady" 字面量
})();
"""

// 改后
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
    call: function (method, params) { /* 不变，内部用 postRaw */ },
    on:   function (event, cb)      { /* 不变 */ },
    once: function (event, cb) {
      var off = window.$namespace.on(event, function (p) { off(); cb(p); });
      return off;                                          // ← once 内部引用要跟随 namespace
    },
    __resolve: function (id, ok, payload) { /* 不变 */ },
    __emit:    function (event, payload)  { /* 不变 */ }
  };
  try { window.dispatchEvent(new Event('${namespace}Ready')); } catch (e) {}
})();
"""
```

**注意**：

- Kotlin raw string 里 `$namespace` / `${namespace}Ready` 是 Kotlin 插值，构造期被替换为字面字符串；不需要 `${'$'}` 转义
- `once` 函数原本硬编码 `window.KmpBridge.on(...)`，改造时要跟随 namespace

### 4.2 `bridge/JsBridge.kt`：加 namespace 字段 + emit/resolve 改写

```kotlin
class JsBridge internal constructor(
    private val scope: CoroutineScope,
    internal val namespace: String,        // ← 新增，internal 让 binder 能读
) {
    init {
        require(VALID_JS_IDENT.matches(namespace)) {
            "bridgeNamespace must be a valid JS identifier: $namespace"
        }
    }

    fun emit(event: String, payloadJson: String? = null) {
        val payloadLit = if (payloadJson == null) "null" else jsonQuote(payloadJson)
        evaluate("window.$namespace && window.$namespace.__emit(${jsonQuote(event)}, $payloadLit)")
    }

    private fun resolve(id: Long, ok: Boolean, payloadJson: String?) {
        val payloadLit = if (payloadJson == null) "null" else jsonQuote(payloadJson)
        evaluate("window.$namespace && window.$namespace.__resolve($id, $ok, $payloadLit)")
    }

    internal companion object {
        private val VALID_JS_IDENT = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

        internal fun create(scope: CoroutineScope, namespace: String): JsBridge =
            JsBridge(scope, namespace)

        internal fun createForTest(namespace: String = "KmpBridge"): JsBridge =
            JsBridge(CoroutineScope(SupervisorJob() + Dispatchers.Main), namespace)
    }
}
```

### 4.3 `bridge/JsBridgeAndroidBinder.kt`：加 channel 字段

```kotlin
internal class JsBridgeAndroidBinder(
    private val webView: WebView,
    private val bridge: JsBridge,
    private val channel: String,          // ← 新增（namespace 从 bridge.namespace 拿）
) {
    init {
        require(VALID_IDENT.matches(channel)) {
            "bridgeChannel must be a valid JS/Java identifier: $channel"
        }
        webView.addJavascriptInterface(NativeChannel(), channel)
        bridge.attachEvaluator { js -> webView.post { webView.evaluateJavascript(js, null) } }
    }

    fun injectShim() {
        webView.evaluateJavascript(buildKmpBridgeShim(bridge.namespace, channel), null)
    }

    fun dispose() {
        webView.removeJavascriptInterface(channel)
        bridge.detachEvaluator()
    }

    private inner class NativeChannel { /* 不变 */ }

    private companion object {
        private val VALID_IDENT = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
        // 删掉 const val CHANNEL_NAME
    }
}
```

### 4.4 `bridge/JsBridgeIosBinder.kt`：加 channel 字段

```kotlin
// 删掉顶部 private const val CHANNEL_NAME = "__kmpBridgeNative"

@OptIn(ExperimentalForeignApi::class)
internal class JsBridgeIosBinder(
    private val webView: WKWebView,
    private val bridge: JsBridge,
    private val channel: String,          // ← 新增
) : NSObject(), WKScriptMessageHandlerProtocol {

    init {
        require(VALID_IDENT.matches(channel)) {
            "bridgeChannel must be a valid JS identifier: $channel"
        }
        val ucc = webView.configuration.userContentController
        ucc.addUserScript(WKUserScript(
            source = buildKmpBridgeShim(bridge.namespace, channel),
            injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
            forMainFrameOnly = true,
        ))
        ucc.addScriptMessageHandler(this, name = channel)
        bridge.attachEvaluator { /* 不变 */ }
    }

    fun dispose() {
        webView.configuration.userContentController
            .removeScriptMessageHandlerForName(channel)
        bridge.detachEvaluator()
    }

    private companion object {
        private val VALID_IDENT = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    }
}
```

### 4.5 `core/WebViewState.kt` + `rememberWebViewState`：API 入口

```kotlin
class WebViewState internal constructor(
    initialUrl: String,
    bridgeNamespace: String,                  // ← 新增构造参数
    internal val bridgeChannel: String,       // ← 新增（internal 暴露给 PlatformWebView）
) : RememberObserver {

    // ... 其它 var/val 字段不变（currentUrl / title / loading / canGoBack ...）

    private val bridgeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val jsBridge: JsBridge = JsBridge.create(bridgeScope, bridgeNamespace)
    //                                                    ^^^^^^^^^^^^^^^ ← 注入 namespace

    // ... 其它方法不变
}

@Composable
fun rememberWebViewState(
    initialUrl: String,
    bridgeNamespace: String = "KmpBridge",
    bridgeChannel:   String = "__kmpBridgeNative",
): WebViewState =
    remember(initialUrl, bridgeNamespace, bridgeChannel) {
        WebViewState(initialUrl, bridgeNamespace, bridgeChannel)
    }
```

### 4.6 `platform/PlatformWebView.{android,ios}.kt`：binder 构造传参

**Android** (`PlatformWebView.android.kt:80`)：

```kotlin
// 改前
val binder = JsBridgeAndroidBinder(wv, state.jsBridge)

// 改后
val binder = JsBridgeAndroidBinder(
    webView = wv,
    bridge = state.jsBridge,
    channel = state.bridgeChannel,
)
```

**iOS** (`PlatformWebView.ios.kt:50`)：

```kotlin
// 改前
binderHolder.binder = JsBridgeIosBinder(webView, state.jsBridge)

// 改后
binderHolder.binder = JsBridgeIosBinder(
    webView = webView,
    bridge = state.jsBridge,
    channel = state.bridgeChannel,
)
```

## 5. 兼容性

| 维度 | 影响 |
|---|---|
| `rememberWebViewState` API | ✅ 向前兼容，新参数有默认值 |
| 默认行为 | ✅ 不传参时 `KmpBridge` 派生 `__kmpBridgeNative`，行为不变 |
| JS 端默认调用 | ✅ 未自定义时 `window.KmpBridge.call(...)` 仍可用 |
| 就绪事件名 | ⚠️ 自定义后是 `{namespace}Ready`；默认仍 `KmpBridgeReady` |
| `KMP_BRIDGE_SHIM_JS` 常量 | ⚠️ 删除，调用方都换 `buildKmpBridgeShim(...)`；外部无引用，仅 binder 内部用 |
| 单测 `createForTest` | ✅ 默认 `"KmpBridge"`，老测试不需要改 |
| 已发布版本用户 | ✅ 升级后不传新参数 = 行为完全一致 |
| `bridgeChannel` 单独自定义 | 🚧 本期未开放——若未来需要，再加 `bridgeChannel` 默认参数 + binder 校验即可，派生规则与外部传入互不冲突 |

## 6. 运行时校验

namespace 拼到 JS 源码字面里，不合法会让整段 shim 解析失败、运行时报莫名 SyntaxError。SDK 层 fail-fast：

| 字段 | 合法字符 | 校验 regex |
|---|---|---|
| `bridgeNamespace` | JS 标识符（`A-Z a-z 0-9 _ $`，不能数字开头）| `^[A-Za-z_$][A-Za-z0-9_$]*$` |
| `bridgeChannel`（派生）| 由 namespace 派生，天然合法 | 不再单独校验 |

在 `JsBridge.init` 中 `require(...)`，构造期直接抛 `IllegalArgumentException`。两个 binder 不再重复校验 channel。

**未校验项**（KDoc 说明、不做强校验）：

- JS 关键字（`delete / new / class / for / if ...`）—— 合法标识符但语义诡异；SDK 不穷举语境，使用者自负
- `__` 前缀 / 末尾 —— `__kmpBridgeNative` 这种约定俗成命名，规则上没问题，不做强制

## 7. 单测要点

新增测试用例：

| 测试 | 验证 |
|---|---|
| `JsBridge` 默认 namespace `KmpBridge` 的 emit / resolve JS 生成 | 行为兼容 |
| 自定义 namespace `CustomBridge` 的 emit / resolve JS 生成 | 拼接正确，无 escape 问题 |
| 非法 namespace（`"foo-bar"` / `"1foo"` / 空串 / `"foo bar"` / `"foo.bar"`）| 构造期抛 IAE |
| 合法 namespace 变体（字母 / `_` / `$` / 数字）| 构造期不抛 |
| 默认参数派生 channel = `__kmpBridgeNative`、自定义 namespace 派生对应 channel | `buildKmpBridgeShim` 输出 Android `window.{channel}` / iOS `window.webkit.messageHandlers.{channel}` 路径正确 |
| 多 WebView 实例不同 namespace 互不串扰 | handler 表独立、emit 拼不同 `window.X.__emit` |
| 就绪事件名跟随 namespace | `{namespace}Ready` 字符串正确 |

## 8. 落地节奏

1. **改 SDK 源码**：6 个文件
2. **加单测**：参考 §7
3. **更新 README 接入示例**：把 `rememberWebViewState` 调用示例补上 `bridgeNamespace` 说明
4. **CHANGELOG**：记一条"新增 `bridgeNamespace` 参数，向前兼容；native channel 由 namespace 派生，本期未单独开放"

该改造与具体业务方使用方式解耦，可独立发版（如 0.2.0）。

## 9. 备选方案对比（速查）

| 备选 | 评价 | 排除理由 |
|---|---|---|
| 合并为单字段 `bridgeName`（自动派生 `__{name}Native`）| 简洁 | ✅ **本期采纳**——派生规则 `"__" + namespace.replaceFirstChar { it.lowercase() } + "Native"`，确保默认 `KmpBridge` → `__kmpBridgeNative` 与改造前一致；自定义 `CustomBridge` → `__customBridgeNative` 视觉一致 |
| 命名 `jsGlobalName` + `nativeChannelName` | 显式但裸 `name` | "name" 易和"业务 API 名"混淆 |
| 命名 `namespace` + `channel` | 极简 | 与 DSBridge 协议的 method namespace 概念易混；`channel` 单用太裸 |
| 配置放 `WebViewConfig` | 跟其它 WebView 配置一处 | `jsBridge` 初始化时拿不到；config 是渲染期、bridge 是实例期，性质不同 |
| 支持运行时改名 | 灵活 | YAGNI；handler 表迁移、shim 重注入复杂度高，无明确场景 |
| 支持同一 WebView 多 bridge 实例 | 插件化 | YAGNI；shim 命名冲突、调试链路复杂；未来真有需求再扩 |
