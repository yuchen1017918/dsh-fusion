# 桌面宿主的契约（上游调研）

> **来历**：这份文档原在 `experiment/desktop-architecture` 分支（该分支已删除）。
> 它是对**官方客户端内部契约**的调研 —— 同源闸的代码出处、桌面版的四个构件、
> 客户端要宿主提供什么。**这些结论与分支无关，仍然有效**，所以留下来。
>
> **现状**：契约已被消费，落地方式见 [README 第十一节](../README.md#十一侧边栏浏览器桌面宿主载体--金丝雀) ——
> 用官方注入 API（`WebViewCompat.addDocumentStartJavaScript`），
> **不是**这份文档里设想的本地代理。
>
> 文档里「五、实施蓝图」那一节讲的是代理方案，已被取代；其余部分（尤其是
> 一、二、五·五节的契约全文与代码出处）是查上游代码得到的，仍然准确。

---

# 能不能改成 DSH 桌面版的架构

结论先说：**技术上可行，但 Android 会撞到一个桌面版靠 Electron 特权绕开、而 WebView 没有等价能力的障碍。
出路存在，而且是官方留的口子。**

本文所有结论都有代码出处，不是推测。

---

## 一、桌面版到底特殊在哪（四个构件）

### 构件 1 · 文档来自自定义协议，不是 http

```ts
// apps/desktop/src/ipc.ts:90
export const SCHEME = 'dsh-app'
```

桌面窗口加载的是 `dsh-app://app/`，而**不是** `http://127.0.0.1:3080/`。数据请求另外走 http
（测试夹具里 `streamBaseUrl: 'http://127.0.0.1:3080'`）。

### 构件 2 · 宿主接管 index 文档，自己拼装

```ts
// apps/desktop/src/web-document.ts:10
const BOOT = '<script>globalThis.__DSH_BOOT_READY__ = Promise.withResolvers()</script>'
```

前端启动时会等这个承诺：

```ts
// packages/client/web/src/boot.ts:57
await globalThis.__DSH_BOOT_READY__?.promise
```

而 web server 会注入它的 settle 脚本：

```ts
// packages/host/webserver/src/injections.ts:86
const READY_MARKUP = '<script>(globalThis.__DSH_BOOT_READY__ ??= Promise.withResolvers()).resolve()</script>'
```

**桌面版自己托管 index 的目的**：把 carrier 注入**放在** resolve 之前，
这样前端启动那一刻宿主能力已经就绪 —— 消除激活竞态。
（直接通过 http 加载时，注入只能发生在页面加载之后，时序上就晚了。）

### 构件 3 · preload 注入 carrier，且带安全闸

```ts
// apps/desktop/src/preload-app.ts:100
contextBridge.exposeInMainWorld('dshDesktop',
  location.protocol === `${SCHEME}:` && location.hostname === 'app' && process.isMainFrame
    ? createProductApi()        // 完整 API（含 browser）
    : { protocolVersion: 1 })   // 空壳：没有 browser → 客户端降级成 iframe
```

注意**空壳也注入**：`{ protocolVersion: 1 }`。所以光看「有没有 dshDesktop」区分不出来，
判断在客户端那三行（`ui-sidebar-browser/src/client/index.ts:70-72`）：
只有 `protocolVersion === 1` **且** 带 `browser` 才走 Electron 分支。

### 构件 4 · 独立的宿主进程 + IPC

```ts
// apps/desktop/src/host-process.ts:196
...(this.packageManager === undefined ? [] : [this.packageManager.pnpm, this.packageManager.nodeBin]),
```

宿主是 `dsh-desktop-host`，通过 Node IPC（`stdio: [..., 'ipc']`）回传结构化 ready：

```ts
// apps/desktop-host/src/index.ts:104
process.send?.({ type: 'ready', url, injections: ctx.webServer.collectIndexInjections() })
```

---

## 二、Android 映射表

| 桌面构件 | Android 对应能力 | 可行性 |
|---|---|---|
| `dsh-app://app` 自定义协议 | `WebViewAssetLoader`（`https://appassets.androidplatform.net`）或 `shouldInterceptRequest` | ✅ 能做 |
| 自拼 index + injections | 先 `GET /` 拿 index，注入 carrier 后 `loadDataWithBaseURL` 或经 asset loader 提供 | ✅ 能做 |
| `contextBridge` preload | `addJavascriptInterface` + `evaluateJavascript` 包装成 Promise | ✅ 能做 |
| desktop-host + IPC | **已有** `fusion-host.mjs`（能拿 url；扩展它通过 stdout 输出 injections 即可） | ✅ 架构已就位 |
| Electron `WebContentsView` | 原生 WebView 叠加在面板区域之上 | ⚠️ 能做但繁琐 |
| **Electron 的特权协议 + 请求头改写** | **WebView 没有等价能力** | ❌ **障碍** |

---

## 三、真正的障碍：后端的同源闸

```ts
// packages/client/connection/src/api-request-trust.ts:104-118
if (header(request.headers, 'sec-fetch-site') === 'cross-site') return false
const origin = header(request.headers, 'origin')
if (origin === undefined) return true
try { return new URL(origin).host === hostUrl.host } catch { return false }
```

两道闸：

1. `Sec-Fetch-Site: cross-site` → 直接拒；
2. 带 Origin 时，`new URL(origin).host` 必须**正好等于** `Host` 头的 host。

`dsh-app://app` 的 `.host` 是 `"app"`，`127.0.0.1:3080` 的 host 是 `"127.0.0.1:3080"` —— 按这段代码，桌面版**也不该过**。
它能过，靠的是 Electron 层面的东西（`protocol.registerSchemesAsPrivileged` 把自定义协议做成特权 standard scheme、
主进程可改写请求头），**Android WebView 没有对应能力**：
`shouldInterceptRequest` 只能改**响应**，改不了**请求头**。

所以：**照搬桌面架构、把文档换个 origin，会当场被后端拒掉。**

---

## 四、出路（官方留的口子）

障碍的关键在第二行：

```ts
if (origin === undefined) return true      // ← 没有 Origin 就放行
```

**没有 Origin 的请求一律放行**（注释也写了：*"Absent Origin is fine — the Host fence above already bound the request."*）。

所以只要**让 API 请求不带 Origin 发出**即可。Android 上三种做法：

**方案 A · Kotlin 侧本地代理（推荐）**
`WebViewAssetLoader` 提供文档，并把 `https://appassets.androidplatform.net/api/*` 在
`shouldInterceptRequest` 里**转成 OkHttp 请求**发往 `http://127.0.0.1:<port>/api/*`。
OkHttp（非浏览器客户端）默认不附加 `Origin` 与 `Sec-Fetch-Site` → 两道闸都过。
代价：要自己搬运 SSE / WebSocket 之类的长连接流。

**方案 B · 用 Node 宿主做代理（复用现成能力）**
`fusion-host.mjs` 里已经跑着 Node。让它额外起一个反向代理端口，
文档从该端口提供，`/api` 前缀转发到 web server。
好处：SSE 只是管道转发，不涉及协议解析。代价：多一跳本地 TCP。

**方案 C · 让 web server 值得信任**（不可行）
`isTrustedApiRequest(request, trustedHosts)` 里的 `trustedHosts` 校验的是 **Host 头**，
而 Origin 栅栏与之独立。所以**加白名单解决不了这个问题** —— 必须走 A 或 B。

---

## 五、实施蓝图（如果要做）

按依赖顺序，四步，每步都能单独验证：

1. **先解决 Origin（最高风险，先做）**
   实现方案 A 或 B 中的一个，用 `curl` 与真实 WebView 各验证一次 `/api` 是否能通。
   **这一步过不了，后面三步都没有意义。**
2. **接管文档加载 + 注入 carrier**
   `dshDesktop = { protocolVersion: 1, browser: <bridge> }`，注入必须发生在
   `__DSH_BOOT_READY__` resolve 之前。
3. **实现 `DesktopBrowserBridge`（只有三个方法）**
   ```ts
   acquire(workspace: string): Promise<{ lease, partition }>
   release(lease): Promise<void>
   onOpenRequested(lease, listener: (url) => void): () => void
   ```
4. **原生 WebView 承载浏览器页**
   按客户端报告的 bounds 把子 WebView 叠加到面板区域；处理滚动/缩放同步。

估算：Kotlin + 注入层合计约 500–800 行，外加方案 A/B 的代理。

---

## 五点五、客户端到底要宿主给什么（契约全文）

这是实施的关键：**桥只有三个方法，视图创建走的是别处**。追到底在 `ElectronWebviewPresentation`：

```ts
const element = document.createElement('webview')      // Electron 的 <webview> 标签
element.setAttribute('name', reservation.lease)
element.setAttribute('partition', reservation.partition)
element.setAttribute('allowpopups', '')
element.setAttribute('src', 'about:blank#' + reservation.lease)
```

元素 API（`WebviewElement` 接口）：

```ts
loadURL(url): Promise<void>   getURL(): string        getTitle(): string
canGoBack(): boolean          canGoForward(): boolean clearHistory(): void
goBack(): void                goForward(): void       reload(): void
isLoading(): boolean
```

外加导航事件监听（`did-start-loading` / `did-stop-loading` / `did-navigate` / `page-title-updated` 一类）。

**结论：Android 的实现路线是「宿主侧 polyfill `document.createElement('webview')`」**，
把它桥接到原生 WebView。这样**不需要改一行官方客户端代码**，完全在宿主侧完成。



1. **第 1 步就是未知数。** 同源闸能不能过，只有真机 + 真实 WebView 才能确认。
   在没确认之前写后面 700 行，是在赌一个未验证的前提。
2. **本项目至今没有任何运行期验证。** 在这个前提下再加一个「宿主级重构」，
   会把变量从「功能有没有写对」变成「功能对不对 + origin 通不通 + 注入时序对不对」。
3. **它不是必需品。** 桌面架构带来的差异只体现在侧边栏浏览器那一个面板上；
   对话、工具、插件、会话管理全都不受影响。

**建议的次序**：先真机跑通现有功能 → 再单独做第 1 步的 origin 验证 →
验证通过后再决定要不要投入第 2–4 步。

---

## 七、一句话总结

桌面版架构的本质**不是 Electron**，而是「**宿主接管文档加载，从而能在正确的时机注入宿主能力**」。
这个思想在 Android 上完全成立，四个构件里有三个能直接映射。

真正的分水岭只有一个：**后端的同源闸要求 Origin 与 Host 一致，而 WebView 改不了请求头**。
它不是死路（无 Origin 即放行是官方留的口子），但它需要先写一个本地代理并**在真机上验证** ——
这一步没走过之前，其余都是纸上谈兵。
