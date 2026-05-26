# 前端 Bridge 使用文档（`docs/frontend-bridge.md`）— 内容设计

## 背景

当前根 `README.md` 的 JSBridge 章节是从 SDK 接入者（Kotlin/原生侧）视角写的，对 H5/前端工程师只暴露了两行 JS 示例（`call` + `on`），缺一份独立的、面向前端的完整使用说明。

本设计文档约定**新建** `docs/frontend-bridge.md` 的内容结构和取舍。根 `README.md` 的 JSBridge 章节保持现状（或仅追加一行链接指过去），不重复内容。

## 读者画像与边界

- **读者**：在 KmpWebView 容器里写页面的 H5/JS 工程师。可能用 vanilla JS、Vue、React，不假设懂 Kotlin。
- **深度**：快速接入 + 调试排错，**不暴露 envelope 协议**（避免暗示前端可绕过 `window.KmpBridge` 直接 `postMessage`，增加耦合面）。
- **非目标**：
  - 不列具体业务 handler（如 `getToken`）——这些由各 App 的 Native 团队定义，不属于 SDK 文档。
  - 不提供 TypeScript `.d.ts` 声明（维护成本高，SDK 改动需同步；前端项目可自行定义）。
  - 不写底层 envelope/`__resolve`/`__emit` 协议（SDK 内部实现）。

## 章节结构

### 0. 在开始之前（核心概念）

**目的**：纠正常见误解——前端工程师可能以为 `KmpBridge` 内置了一堆 API。实际上它只是通道。

要点：

- `KmpBridge` 是通信通道，不是能力库。
- 具体 handler（方法名、参数、返回值）由各 App 的 Native 团队注册并约定契约，**SDK 不内置任何业务 handler**。
- 同一份 H5 跑在两个 App 里，可调用的 handler 列表可能完全不同——以 Native 同事提供的契约为准。

### 1. 5 分钟上手

最小可运行片段（≤ 10 行），覆盖：Ready 兜底 + `call` + try/catch 错误处理。这一节让 90% 读者拷贝即用。

### 2. 入口与就绪

- `window.KmpBridge` 的注入时机**不保证早于 inline `<script>`**——Android 上尤其需要兜底。
- 推荐模板：
  ```js
  if (window.KmpBridge) init();
  else window.addEventListener('KmpBridgeReady', init);
  ```
- 非容器环境（普通浏览器调试时）`window.KmpBridge` 不存在，应做能力检测 + 降级（点出来即可，不展开降级实现）。

### 3. 调用 Native：`call(method, params)`

- 签名：`KmpBridge.call(method: string, params?: any) => Promise<any>`
- `params` 任意可 JSON 序列化的值，可省略或传 `null`
- 返回值由 handler 决定，前端拿到的就是 JSON 解析后的对象
- 示例：无参 / 带参 / `async-await` 三种写法

### 4. 监听事件：`on` / `once` / `off`

- `on(event, cb)` 返回 `off` 句柄，调用即解绑
- `once` 同上，触发一次后自动解绑
- 典型场景：登录态变化、推送通知、Native 主动通知 H5

### 5. 错误处理

- 模板：
  ```js
  try { await KmpBridge.call(...); }
  catch (err) { /* err.code, err.message */ }
  ```
- **SDK 内置错误码表**：

  | code                | 触发场景                          | 前端处理建议                   |
  |---------------------|-----------------------------------|--------------------------------|
  | `HANDLER_NOT_FOUND` | Native 没注册该 handler           | 检查方法名 / App 版本是否支持 |
  | `HANDLER_ERROR`     | handler 抛了未分类异常            | 上报 + 给用户友好提示          |
  | `ORIGIN_DENIED`     | 页面 origin 不在 Native 白名单内  | 找 Native 同事把域名加白名单   |

- **业务自定义错误码**：由 Native 在 handler 内 `throw JsBridgeException(code, message)` 决定；约定 `SCREAMING_SNAKE_CASE`，含义跟 Native 同事对齐。
- **特殊情况**：handler 返回值不是合法 JSON 时，Promise 会被 reject 为一个普通 `Error('Invalid payload JSON')`（无 `code` 字段）。

### 6. 自定义命名（按需）

- 何时用：App 改了 namespace（默认 `KmpBridge` 被占用 / 多 App 共享 SDK / 对接历史协议）
- 用法：前端把代码里所有 `KmpBridge` 替换成 App 指定的名字，`KmpBridgeReady` 同步替换成 `<Name>Ready`
- 由 App 端决定，前端拿到 namespace 后照搬即可

### 7. 调试与排错

- **验证 bridge 已注入**：DevTools console 输入 `window.KmpBridge`，应该是个 object。
- **观察 Ready 事件**：`window.addEventListener('KmpBridgeReady', () => console.log('ready'))`
- **远程调试入口**：Android `chrome://inspect`、iOS Safari → 开发 → 设备名。（一句话指引，不展开。）
- **现象速查表**：

  | 现象                          | 可能原因                                       |
  |-------------------------------|------------------------------------------------|
  | `window.KmpBridge` 是 undefined | 非容器环境 / 注入未完成 / namespace 名字写错  |
  | `call` 永远 pending           | method 名拼错 + Native 没做超时（SDK 不自动超时） |
  | `HANDLER_NOT_FOUND`           | App 版本不支持 / 方法名跟 Native 没对齐        |
  | `ORIGIN_DENIED`               | 页面 origin 不在 Native 白名单内              |

- **明确点出**：SDK 不给 `call` 加自动超时；业务方需要超时就自己 `Promise.race`。

### 8. 附录：完整 demo

一份独立可运行的 HTML，包含：

- Ready 兜底初始化
- 一次成功 `call` + 一次失败 `call`（含 try/catch）
- `on` 监听 + `beforeunload` 时 `off`
- 顶部注释提示"如 App 用了自定义 namespace，替换以下两处即可"

## 关键取舍记录

1. **D（handler 契约）放最前**作为概念前提，避免读者带着错误预期往下读
2. **5 分钟上手在 §1**（demo 缩小版），完整 demo 留附录——常用路径前置，参考代码后置
3. **自定义命名放 §6 而非 §2**——属于少数 App 才用到的配置，不污染主线
4. **不暴露 envelope 协议**（A 选 C），但调试章节点出"call pending = Native 没回"，让读者知道排查方向
5. **不承诺超时机制**——SDK 当前确实没做，明确写出"业务自己 race"避免误用
6. **跳过 TypeScript 类型声明**——SDK 改动同步成本高，前端项目可按需自行定义

## 实现产物

- 新建 `docs/frontend-bridge.md`，按上述结构落地
- 根 `README.md` 的 JSBridge 章节追加一行：「前端使用详见 [docs/frontend-bridge.md](docs/frontend-bridge.md)」（位置：现有"JSBridge"章节末尾或"自定义命名"小节之后）
