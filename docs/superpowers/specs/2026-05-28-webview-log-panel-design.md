# WebView 日志面板设计

> SDK 内置一个可在设备上展示的日志面板，方便前端同学在没有远程调试条件时直接看到 console、JS 异常、WebView 加载错误、JSBridge 调用链，加速线上和真机问题排查。

- 状态：设计中，未实现
- 日期：2026-05-28
- 适用版本：>= 0.2.0（计划）

## 背景

当前 SDK 已具备 `enableRemoteDebugging`，但它仅解决"通过 Chrome/Safari 远程调试 WebView"的场景——前提是开发机能连上设备且 H5 在前端同学手边运行。线上、灰度、产测、客户现场等场景下，前端拿不到一台能远程调试的设备，问题排查只能靠"业务方截图 + 口述"。

需要一个不依赖远程调试器、前端在设备上即可直接查看日志的轻量面板。

## 范围

### 采集

- JS `console.log` / `info` / `warn` / `error`
- JS 未捕获异常 `window.onerror`、`unhandledrejection`
- WebView 加载/资源错误：navigation 失败、HTTP 错误、SSL 错误
- JSBridge 调用链：`call` / `emit` / 响应 / 异常（仅在面板启用时采集）

### 不包含

- 网络请求逐条记录（XHR/fetch 全量抓包不在本次范围；如需要后续单独立项）
- 日志导出/分享（前端在面板里直接读，不做文件输出）
- 单条日志详情页/折叠（长内容在列表里多行 wrap）
- 生产环境默认开启（必须由业务方通过 `WebViewConfig.enableLogPanel = true` 显式启用）

## 决策摘要

| 维度 | 决策 |
|---|---|
| 启用范围 | `WebViewConfig.enableLogPanel: Boolean = false` |
| 唤起方式 | 可拖动悬浮 FAB（带未读 error 角标）+ ModalBottomSheet 抽屉 |
| 数据持有 | `WebViewState.logStore: LogStore?`，跟随 WebView 生命周期 |
| 容量 | 环形 buffer，写死 500 条 |
| 交互能力 | 类型/级别过滤、关键字搜索、清空 |
| 长内容 | 列表内多行 wrap，不折叠、不跳详情 |
| JSBridge 采集 | 关闭面板时不采集（短路到 null） |
| 资源错误粒度 | 主框架错误正常记录；二级资源失败标记为 verbose，默认过滤 |

## 架构

```
┌──────────────────────────────────────────────┐
│                WebViewScreen                  │
│  ┌────────────────────────────────────────┐  │
│  │            PlatformWebView              │  │
│  │   WebChromeClient/Coordinator → store   │  │
│  │   WebViewClient/Navigation   → store    │  │
│  │   注入 __kmpLogShim.js       → store    │  │
│  └────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────┐  │
│  │              JsBridge                    │ │
│  │   call/emit/error → store (可选)        │  │
│  └────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────┐  │
│  │   LogPanelHost  ← Box 叠在最上层        │  │
│  │   订阅 store.entries (StateFlow)         │  │
│  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘
              ▲ 全部走同一个
       ┌──────┴──────┐
       │  LogStore   │  commonMain，单 WebView 1 个
       │  - ring 500 │
       │  - append() │
       │  - clear()  │
       │  - flow     │
       └─────────────┘
```

## 数据模型

```kotlin
// commonMain
data class WebViewLog(
    val id: Long,                  // 自增，LazyColumn key
    val timestamp: Long,            // epochMs
    val source: Source,
    val level: Level,
    val message: String,            // 列表主文本，已截断到 256 字符
    val detail: String? = null,     // stack / payload / URL 等，已截断到 4KB
) {
    enum class Source { Console, JsException, WebViewError, JsBridge }
    enum class Level { Verbose, Info, Warn, Error }
}
```

各来源的写入规则：

| Source | message 示例 | detail 示例 | Level 映射 |
|---|---|---|---|
| Console | `[log] foo bar 42` | 无（短）/ 多行参数全量（长） | `console.log/debug` → Info；`console.info` → Info；`console.warn` → Warn；`console.error` → Error |
| JsException | `Uncaught TypeError: x is undefined` | stack | Error |
| WebViewError | `404 https://x.com/a.js` | 无 / SSL chain | 主框架失败 → Error；HTTP 4xx/5xx → Warn；二级资源（`isForMainFrame == false`）→ Verbose |
| JsBridge | `call getToken (12ms)` | `params: {...}\nresult: {...}` | 成功 → Info；抛 `JsBridgeException` → Error |

## 模块

```
library/src/commonMain/kotlin/wang/harlon/webview/logpanel/
├── LogStore.kt              # ring buffer + StateFlow
├── LogShim.kt               # JS 注入脚本常量（双端共享）
├── WebViewEnvironment.kt    # UA + WebView 内核版本数据类
└── ui/
    ├── LogPanelHost.kt      # 入口：FAB + 抽屉容器
    ├── LogPanelFab.kt       # 可拖动 FAB + 未读 error 角标
    ├── LogPanelDrawer.kt    # ModalBottomSheet 内含 Environment 折叠区 + toolbar + list
    └── LogRow.kt            # 单条 row

library/src/androidMain/kotlin/wang/harlon/webview/logpanel/
├── LogJsBridgeAndroidBinder.kt
└── AndroidEnvironmentCapture.kt   # 同步读 UA + WebView Provider 包

library/src/iosMain/kotlin/wang/harlon/webview/logpanel/
├── LogIosBinder.kt
└── IosEnvironmentCapture.kt       # 读 customUserAgent / evaluateJavaScript + UIDevice.systemVersion
```

### LogStore

```kotlin
class LogStore(private val capacity: Int = 500) {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow(persistentListOf<WebViewLog>())
    val entries: StateFlow<ImmutableList<WebViewLog>> = _entries

    suspend fun append(
        source: Source,
        level: Level,
        message: String,
        detail: String? = null,
    ) { /* mutex 内自增 id、截断、push、超容丢首条 */ }

    suspend fun clear() { /* mutex 内置空 */ }
}
```

- 截断：`message` 256 字符、`detail` 4KB，尾部追加 `... [truncated]`
- 实例由 `WebViewState` 持有；`enableLogPanel = false` 时 `WebViewState.logStore = null`

### JS 注入脚本

`LogShim.kt` 返回字符串常量；占位符 `__KMP_LOG_POST__` 在各平台替换为不同的"送回 Native"调用：

- Android：`__kmpLogNative.post(json)`（JavaScriptInterface）
- iOS：`webkit.messageHandlers.__kmpLog.postMessage(json)`

脚本内全部 `try/catch` 包裹，shim 失败不影响业务 console；含 `__kmpLogShimInstalled` guard 避免重复注入。

## 采集挂钩

### Android

| 挂钩点 | 文件 | 写入 |
|---|---|---|
| Console + JsError | 新增 `LogJsBridge`（JavaScriptInterface `__kmpLogNative`） | shim 回灌；`SdkWebChromeClient.onConsoleMessage` 作为兜底（少数 framework 重写过 console） |
| WebView 错误 | `SdkWebViewClient.onReceivedError` / `onReceivedHttpError` / `onReceivedSslError` | 主框架错误→Warn/Error；二级资源→Verbose |
| 注入入口 | `SdkWebViewClient.onPageStarted` 中 `evaluateJavascript`，与现有 `KmpBridgeShim` 合并一次注入 |

### iOS

| 挂钩点 | 文件 | 写入 |
|---|---|---|
| Console + JsError | 新增 `WKScriptMessageHandler` 名 `__kmpLog` | shim 回灌 |
| WebView 错误 | `WebViewCoordinator.didFail*` | 主框架错误→Warn/Error |
| 注入入口 | `WKUserScript(injectionTime=.atDocumentStart, forMainFrameOnly=false)`，与现有 JSBridge UserScript 一起注册 |

### JSBridge

`JsBridge` 构造时注入可空 `LogStore?`；call/emit/respond/异常路径加 `logStore?.append(...)`。`enableLogPanel = false` 时 LogStore 为 null，单次 null 检查零开销。

```kotlin
class JsBridge internal constructor(
    private val logStore: LogStore? = null,
    /* ... */
) {
    suspend fun call(method: String, params: String?): String? {
        val start = currentMs()
        return try {
            val r = handler.invoke(params)
            logStore?.append(Source.JsBridge, Level.Info,
                "call $method (${currentMs() - start}ms)",
                "params: $params\nresult: $r")
            r
        } catch (e: JsBridgeException) {
            logStore?.append(Source.JsBridge, Level.Error,
                "call $method failed (${e.code})",
                "params: $params\n${e.stackTraceToString()}")
            throw e
        }
    }
}
```

## `enableLogPanel` 开关行为

设计目标：关闭即零侵入——业务方不开开关时，SDK 不向 WebView 注入任何调试相关脚本、不在 `userContentController` 上注册任何 handler、不重写 H5 侧的任何全局对象。开关只在 `WebViewScreen` 初始化时读取一次，运行时不切换（避免 shim 注入/卸载的状态机复杂度；如需切换业务方重建 WebViewState）。

| 模块 | `enableLogPanel = false`（默认） | `enableLogPanel = true` |
|---|---|---|
| `WebViewState.logStore` | `null` | `LogStore()` 实例 |
| `WebViewState.environment` | `null`，不读 `webView.settings` / `PackageManager` / `UIDevice` | `WebViewEnvironment` 实例，由各端 `capture...Environment` 写入 |
| LogShim JS 脚本 | 不注入；`window.__kmpLogShimInstalled` 不存在 | 与 `KmpBridgeShim` 合并注入（Android `onPageStarted` evaluateJavascript / iOS `WKUserScript`） |
| Android `LogJsBridge`（`__kmpLogNative` JavaScriptInterface） | 不注册 | 注册到 WebView |
| iOS `__kmpLog` `WKScriptMessageHandler` | 不注册 | 注册到 `userContentController` |
| H5 侧 `console.*` | 原生未被包装，零损耗 | 被 shim wrap，调用时多一次 try/catch + post |
| H5 侧 `window.onerror` / `unhandledrejection` | 未被 SDK 监听 | 被 shim 注册监听 |
| WebView 加载错误回调 | 仍触发，但走 `logStore?.append(...)` 短路 | 写入 LogStore |
| JSBridge `call/emit` 路径 | 仍执行，但 `logStore?.append(...)` 短路 | 每次调用写入 LogStore |
| LogPanelHost Composable | 不参与组合（`state.logStore?.let { ... }` 短路） | 渲染 FAB；点击展开 ModalBottomSheet（含 Environment 折叠区） |

#### 关闭状态下的可观测影响

- H5 页面里 `typeof window.__kmpLogNative === 'undefined'`（Android）/ `typeof window.webkit?.messageHandlers?.__kmpLog === 'undefined'`（iOS）
- `console.log.toString()` 是原生实现（未被包装的 `function log() { [native code] }`）
- 业务方页面里的 `window.onerror` / `unhandledrejection` 监听完全由业务自行决定，SDK 不抢注

#### 产物体积

`LogShim.kt` 字符串常量 + LogStore + UI Composable 在关闭路径下均为未引用代码。开启 R8 / dead-code elimination 的业务方 APK 几乎无额外占用；未开启时常量字符串仍在，但 < 10KB。

## UI

### 形态

- 悬浮 FAB：默认右下角，~48dp 半透明，`Modifier.draggable` 可移动，`rememberSaveable` 持久化 offset；右上角小角标显示当前未读 error 计数。计数语义：从上一次"抽屉关闭时"起，新增的 `Level == Error` 条目数；抽屉打开期间不累计、不显示；抽屉关闭瞬间将计数重置为 0，之后开始累计新增。
- 抽屉：`ModalBottomSheet`，最大高度 90%；slide-in 动画；打开期间消费 pointer 事件防止穿透到 WebView。
- 关闭路径：只能从抽屉顶部 X 按钮关闭（不在 FAB 上做长按等隐藏交互）。

### 抽屉内布局

```
┌──────────────────────────────────────────┐
│ Logs (123)                       [×]      │
│ ─────────────────────────────────────────│
│ ▸ Environment                             │  ← 默认收起，点击展开
│ ─────────────────────────────────────────│
│ [All] [Console] [Bridge] [Error]          │  ← 4 个 chip，多选
│ [search: _______________]  [Clear]        │
│ ─────────────────────────────────────────│
│ 10:23:45  warn  console                   │
│   Deprecated API foo() used in page X     │
│ ─────────────────────────────────────────│
│ 10:23:46  error  jserror                  │
│   Uncaught TypeError: x is undefined      │
│   at app.js:42:7                          │
│   at HTMLButton.onClick (app.js:99:3)     │
│ ─────────────────────────────────────────│
│ 10:23:48  info  bridge                    │
│   call getToken (12ms)                    │
│   params: {"scope":"user"}                │
│   result: {"token":"eyJ..."}              │
└──────────────────────────────────────────┘
```

- 过滤 chip：All / Console / Bridge / Error，多选并集；底层组合查询规则：
  - `Console` → `source == Console`
  - `Bridge` → `source == JsBridge`
  - `Error` → `level == Error`（跨 source 取并集：含 console.error、JsException、主框架 WebViewError、JSBridge 异常）
  - `All` 与其他互斥，选中即清空其他 chip
- 二级过滤：右上角下拉切是否显示 verbose（默认隐藏）
- 搜索：case-insensitive substring，匹配 `message + detail`
- 列表：`LazyColumn`，`key = log.id`，倒序（最新在顶部）
- 单条排版：第一行 `时间 · level · source`（level 用颜色 chip），第二行起 message + detail 多行 wrap，detail 用等宽字体
- 跟随新日志：当用户处于列表顶 24px 内时自动滚到顶部；否则保持位置（避免读旧日志被打断）
- 清空：直接调 `store.clear()`，不二次确认

### 配色

- verbose：灰
- info：默认前景色
- warn：琥珀
- error：红

### 与现有 UI 的关系

LogPanelHost 与 `WebViewTopBar` / `WebViewBottomBar` / `WebViewProgressBar` / `WebViewErrorView` 同级，在 `WebViewScreen` 内通过 `Box` 叠加在 WebView 之上，不参与 Scaffold 槽位。

### Environment 折叠区

抽屉 toolbar 下、过滤 chips 上方的元信息区域，跟日志数据职责分离（Clear 不影响）。

字段：

| 字段 | Android | iOS |
|---|---|---|
| `UA` | `webView.settings.userAgentString`，包含业务方通过 `UserAgentStrategy` Append/Prefix/Override 后的最终值 | `customUserAgent` 优先；若为 nil 则 `evaluateJavaScript("navigator.userAgent")` 异步获取 |
| `WebView` 内核版本 | `WebView.getCurrentWebViewPackage()` 拿 `packageName + versionName`（如 `Chrome 130.0.6723.105`）；API < 26 上为 null | `iOS X.Y.Z`（`UIDevice.currentDevice.systemVersion`）。WKWebView 没有公开 WebKit 版本 API，宿主 iOS 版本即决定了内核能力 |

交互：

- 默认折叠收起，点击标题切换；`rememberSaveable` 持久化展开态
- 不支持复制（与整体面板"无导出"决策一致；前端直接读屏）
- 字段缺失时该行不渲染（如 Android API 24/25 无内核版本）

数据持有与获取：

- `WebViewState.environment: WebViewEnvironment?`，`mutableStateOf` 暴露给 Compose 订阅
- 仅在 `enableLogPanel = true` 时由各端 PlatformWebView factory 调 `captureAndroidEnvironment` / `captureIosEnvironment` 写入
- 关闭路径下 environment 始终为 null，`EnvironmentSection` 第一行 `if (env == null) return` 短路，不读 settings、不调 PackageManager、不 evaluateJavaScript

iOS UA 异步路径：第一次写入 `userAgent = "(loading...)"` + 版本占位，JS 拉到后整个对象被替换，抽屉 mutableStateOf 自动 recompose。

## 错误处理 / 边界场景

| 场景 | 处理 |
|---|---|
| `detail` 超大（如 5MB base64） | append 时截断 4KB + 后缀提示 |
| `message` 超长 | 截断 256 字符 |
| shim JS 自身抛异常 | 注入脚本内全 `try/catch`，不影响业务 console |
| 不可序列化对象 / 循环引用 | `safeStringify` fallback `String(v)` |
| iOS handler 收到非 JSON | `runCatching` 解析，失败原样作为 Verbose 入库（不丢） |
| 多 WebView 实例 handler 冲突 | 每实例独立 `userContentController`，天然隔离 |
| 并发 append（IO 线程回调） | `Mutex` 内串行，StateFlow 自身线程安全 |
| Activity 重建 | FAB offset 用 `rememberSaveable` 持久化；store 不持久化（重建即清空） |
| iframe 内异常 | iOS `forMainFrameOnly=false`；Android JavaScriptInterface 全 frame 可见 |
| Bridge payload 含 token 等敏感字段 | **不脱敏**（debug 场景）；README 注明"启用面板时不要在生产暴露给终端用户" |

## 测试

### commonTest 单测

- `LogStoreTest`：append、capacity 满后丢首条、clear、entries flow 发射顺序
- `LogTruncationTest`：detail 超长截断；safeStringify 循环引用 fallback
- `LogShimTest`：注入字符串包含必要 hook（regex 验证 `console.{log,info,warn,error}` 全覆盖、`onerror` / `unhandledrejection` 都存在）

### Android instrumented（可选）

- Compose UI：FAB 拖动 offset 保存、抽屉打开/关闭、过滤 chip 联动、Clear 按钮触发 `store.clear`
- WebView 集成：加载页面后 `console.log` 触发能到达 store

### iOS

随现有 JSBridge 测试覆盖思路：commonTest 主导，平台层手测

### 手测 checklist

- 启用 `enableLogPanel = true`，加载新增的 `sample/console_test.html`，触发 console.log/error/throw/promise reject，验证面板齐全
- 拖动 FAB 到 4 个角，重启 Activity 后位置保留
- 复用 `keyboard_test.html` 验证不影响正常浏览
- `enableLogPanel = false` 时 shim 不注入，业务 console 行为不变

### 验收口径

- `enableLogPanel = false`（默认）时 APK / IPA 体积变化 < 10KB
- 启用时 `console.log` 1000 次回灌延迟在主流机型 < 200ms

## 公共 API 变更

```kotlin
// WebViewConfig.kt
data class WebViewConfig(
    /* ... existing fields ... */
    val enableLogPanel: Boolean = false,
)

// WebViewState.kt
class WebViewState(/* ... */) {
    /* ... */
    internal val logStore: LogStore?  // 由 WebViewScreen 在初始化时根据 config 设置
}
```

`LogStore` 类本身保持 `internal`，不对外暴露——未来若有业务方需要自定义 UI 再考虑公开（YAGNI）。

## 文档变更

- `README.md` 新增 `## 日志面板（调试用）` 一节，说明：
  - 仅供调试，启用时不要在生产暴露给终端用户
  - 开关用法、采集范围、操作要点
- `sample/` 新增 `console_test.html`（~30 行），含触发 4 类日志的按钮，配合手测和 demo

## 风险与权衡

- **采集 console 重写后业务方自己再 wrap 一层**：shim 用 `__kmpLogShimInstalled` guard，后注入会被忽略。如果业务方先注入，shim 会调用业务方包装后的 console（透传）。综合可接受。
- **Bridge 调用链可能含敏感信息**：依靠 README 警示 + 默认关闭机制兜底，不在 SDK 内强行脱敏（无法可靠识别哪些字段敏感）。
- **WKWebView didFail 在某些 SPA 场景不触发**：依赖现有 SDK 行为，本设计不额外补偿。
- **环形 buffer 写死 500 条**：极长会话可能丢早期日志。如果后续接到反馈再开放 `logPanelCapacity` 配置（API 兼容追加字段成本低）。
