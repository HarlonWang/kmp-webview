# KmpBridge 前端接入指南

在 [kmp-webview](../README.md) 容器里跑的 H5 页面，通过 `window.KmpBridge` 调用 App 原生能力、接收原生推送事件。本文面向写页面的前端工程师。

---

## 0. 在开始之前

**KmpBridge 是通信通道，不是能力库。**

- SDK 本身不内置 `getToken` / `scanQR` 这类业务 handler。
- 你能调用哪些方法，取决于具体 App 的 Native 团队注册了什么。**方法名、参数结构、返回值结构都需要和 Native 同事对齐契约**。
- 同一份 H5 跑在两个 App 里，可调用的 handler 列表可能完全不同。

---

## 1. 5 分钟上手

```html
<script>
function init() {
  KmpBridge.call('getToken', { scope: 'user' })
    .then(res => console.log('token:', res.token))
    .catch(err => console.error(err.code, err.message));
}
if (window.KmpBridge) init();
else window.addEventListener('KmpBridgeReady', init);
</script>
```

90% 场景就是这个套路：**等就绪 → call → try/catch**。下文是细节。

---

## 2. 入口与就绪

容器会在页面加载时往 `window` 注入 `KmpBridge` 对象。但 **Android 上注入不保证早于页面里的同步 `<script>`**，所以不能直接假设它存在。

推荐兜底：

```js
function init() { /* 用 KmpBridge 的初始化逻辑 */ }

if (window.KmpBridge) {
  init();
} else {
  window.addEventListener('KmpBridgeReady', init);
}
```

`KmpBridgeReady` 是 bridge 注入完成时派发到 `window` 的事件，专门给上面这种"页面 inline script 跑得比注入还早"的场景兜底。

**非容器环境**（普通浏览器里调试）`window.KmpBridge` 不存在。建议做能力检测 + 降级，避免开发期报错：

```js
if (!window.KmpBridge) {
  // 走 mock / 走 web 兜底逻辑
}
```

---

## 3. 调用 Native：`call`

```ts
KmpBridge.call(method: string, params?: any): Promise<any>
```

- `method`：和 Native 约定好的方法名。
- `params`：任意可 JSON 序列化的值，可省略或传 `null`。
- 返回 `Promise`，resolve 的值是 Native 返回的 JSON 解析结果。

三种典型写法：

```js
// 无参
const me = await KmpBridge.call('getUserInfo');

// 带参
const result = await KmpBridge.call('scanQR', { format: 'qr', timeout: 30000 });

// 链式
KmpBridge.call('uploadFile', { path: '/tmp/a.png' })
  .then(res => console.log('url:', res.url))
  .catch(err => console.error(err));
```

---

## 4. 监听事件：`on` / `once`

Native 也可以主动推消息给 H5，常见场景：登录态变化、消息推送、外部状态变更。

```js
const off = KmpBridge.on('auth.changed', payload => {
  console.log('logged in:', payload.loggedIn);
});

// 不再监听时调用
off();
```

- `on(event, cb)` 返回 `off` 句柄，调用即解绑。
- `once(event, cb)` 触发一次后自动解绑，也返回 `off` 句柄（可在触发前手动取消）。
- 同一个事件可以注册多个监听器。

事件名同样由 Native 约定（如 `auth.changed`、`push.received`），不是 SDK 预定义的。

---

## 5. 错误处理

`call` 失败时 Promise 会 reject，错误对象带 `code` 和 `message`：

```js
try {
  const data = await KmpBridge.call('getToken');
} catch (err) {
  console.error(err.code, err.message);
}
```

### SDK 内置错误码

| `code`              | 触发场景                              | 前端处理建议                          |
|---------------------|---------------------------------------|---------------------------------------|
| `HANDLER_NOT_FOUND` | Native 没注册这个 method              | 检查方法名拼写 / App 版本是否支持     |
| `HANDLER_ERROR`     | handler 抛了未被分类的异常            | 上报埋点 + 给用户兜底提示             |
| `ORIGIN_DENIED`     | 当前页面 origin 不在 Native 白名单内  | 联系 Native 同事把域名加进白名单      |

### 业务自定义错误码

Native 在 handler 里 `throw JsBridgeException(code, message)` 抛出的错误，会原样透传给 H5。约定使用 `SCREAMING_SNAKE_CASE`（如 `USER_CANCELLED`、`NETWORK_TIMEOUT`），具体清单跟 Native 同事对齐。

### 特殊情况：payload 解析失败

如果 Native handler 返回了不是合法 JSON 的字符串，`call` 的 Promise 会被 reject 为一个**没有 `code` 字段**的普通 `Error('Invalid payload JSON')`。判错时记得对 `err.code` 做存在性检查：

```js
catch (err) {
  if (err.code) {
    // 走错误码分支
  } else {
    // 走通用兜底
  }
}
```

---

## 6. 自定义命名（按需）

默认 namespace 是 `KmpBridge`、就绪事件是 `KmpBridgeReady`。少数情况下 App 会自定义命名，比如：

- App 希望用自己的品牌名作为入口（如 `WeixinJSBridge`、`AlipayJSBridge`），跟产品品牌保持一致
- 多 App 共用同一份 H5，但默认名跟某 App 的旧协议冲突
- 页面原本就用了 `KmpBridge` 当变量名

App 改名后，前端把代码里所有 `KmpBridge` 替换成 App 指定的名字（比如 `CustomBridge`），就绪事件相应改成 `CustomBridgeReady`，其余 API 用法不变：

```js
if (window.CustomBridge) init();
else window.addEventListener('CustomBridgeReady', init);

await window.CustomBridge.call('getUserInfo');
```

如何拿到正确的 namespace：直接问 Native 同事。

---

## 7. 调试与排错

### 怎么验证 bridge 已经注入

DevTools console 输入：

```js
window.KmpBridge        // 应该返回一个 object
typeof window.KmpBridge.call   // 'function'
```

### 怎么观察 Ready 事件

在 inline script 顶部加：

```js
window.addEventListener('KmpBridgeReady', () => console.log('[bridge] ready'));
```

如果一直没打印，说明容器没注入（不在 KmpWebView 里 / 容器版本不对 / namespace 用了自定义名）。

### 远程调试入口

- **Android**：Chrome 输入 `chrome://inspect` → 选中页面 → Inspect。
- **iOS**：设备 Safari 设置里开"Web 检查器"，Mac Safari → 开发 → 设备名 → 页面。

### 现象速查表

| 现象                                | 可能原因                                                       |
|-------------------------------------|----------------------------------------------------------------|
| `window.KmpBridge` 是 `undefined`   | 非容器环境 / 注入还没完成（没用 Ready 兜底） / namespace 写错  |
| `call` 永远 pending、不 resolve     | method 名拼错 + **SDK 不会自动超时**；或 handler 内部卡死      |
| `HANDLER_NOT_FOUND`                 | App 版本不支持 / 方法名和 Native 没对齐                        |
| `ORIGIN_DENIED`                     | 当前页面 origin 不在 Native 配的白名单内                       |
| reject 的 error 没有 `code`         | Native handler 返回的不是合法 JSON                             |

### 关于超时

**SDK 不给 `call` 加自动超时**。如果业务有超时需求，自己加 `Promise.race`：

```js
function withTimeout(p, ms) {
  return Promise.race([
    p,
    new Promise((_, reject) => setTimeout(() => reject(new Error('TIMEOUT')), ms)),
  ]);
}

await withTimeout(KmpBridge.call('slowOp'), 5000);
```

---

## 8. 附录：完整 demo

可直接拷贝到容器里跑的最小 HTML（如 App 用了自定义 namespace，把所有 `KmpBridge` 改成对应名字，`KmpBridgeReady` 改成 `<Name>Ready`）：

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <title>KmpBridge demo</title>
</head>
<body>
  <button id="btn-token">getToken</button>
  <button id="btn-fail">trigger error</button>
  <pre id="log"></pre>

<script>
  var log = document.getElementById('log');
  function print(line) { log.textContent += line + '\n'; }

  var offAuth = null;

  function init() {
    print('[bridge] ready');

    // 成功路径
    document.getElementById('btn-token').onclick = async function () {
      try {
        var res = await KmpBridge.call('getToken', { scope: 'user' });
        print('token: ' + JSON.stringify(res));
      } catch (err) {
        print('call failed: ' + (err.code || '(no code)') + ' / ' + err.message);
      }
    };

    // 失败路径（method 故意写错，预期 HANDLER_NOT_FOUND）
    document.getElementById('btn-fail').onclick = async function () {
      try {
        await KmpBridge.call('definitelyNotRegistered');
      } catch (err) {
        print('expected error: ' + err.code + ' / ' + err.message);
      }
    };

    // 事件监听
    offAuth = KmpBridge.on('auth.changed', function (payload) {
      print('auth.changed: ' + JSON.stringify(payload));
    });
  }

  if (window.KmpBridge) init();
  else window.addEventListener('KmpBridgeReady', init);

  // 页面卸载时解绑
  window.addEventListener('beforeunload', function () {
    if (offAuth) offAuth();
  });
</script>
</body>
</html>
```
