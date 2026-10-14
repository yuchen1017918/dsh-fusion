/**
 * DSH Fusion · 桌面版架构 carrier（注入到官方 Web UI 的最前面）
 *
 * ── 这个文件在做什么 ────────────────────────────────────────────
 * 官方客户端判断「是不是桌面宿主」只看一个全局对象：
 *
 *     const carrier = globalThis.dshDesktop
 *     const desktop = carrier?.protocolVersion === 1 ? carrier.browser : undefined
 *     if (desktop === undefined) installFrames(ctx, () => createIframePage)   // 降级
 *     else                        ... createElectronPage(options, desktop)    // 桌面级
 *
 * 桌面级那一支会走 `ElectronWebviewPresentation`，它的全部要求就是：
 *
 *     const element = document.createElement('webview')
 *     element.setAttribute('name', lease)
 *     element.setAttribute('partition', partition)
 *     并且这个元素要提供 loadURL / getURL / getTitle / canGoBack /
 *     canGoForward / clearHistory / goBack / goForward / reload / isLoading
 *
 * **Android WebView 没有 <webview> 标签**。所以这里的做法是：
 * 把 `document.createElement('webview')` polyfill 成一个普通 div，
 * 通过 `window.__DSH_FUSION_HOST__`（Kotlin 侧 addJavascriptInterface 注入）
 * 把它的挂载位置与导航动作交给原生，由原生 WebView 真正承载。
 *
 * **官方客户端一行都不用改。**
 *
 * ── 为什么注入点必须在最前面 ────────────────────────────────────
 * 客户端的 boot 会 `await globalThis.__DSH_BOOT_READY__?.promise`。
 * carrier 必须在它 resolve 之前就位，否则前端启动那一刻宿主能力还没挂上 ——
 * 这正是桌面版要自己接管 index 文档的原因，我们靠代理注入达到同样效果。
 */
(function () {
  'use strict'

  // ══════════════════════════════════════════════════════════════
  // 金丝雀 —— 判断「官方客户端还认不认我们挂的东西」
  // ══════════════════════════════════════════════════════════════
  //
  // 【为什么需要它】
  // carrier 依赖的是官方客户端的**内部契约**：读 `globalThis.dshDesktop`，
  // 且只在 `protocolVersion === 1 && browser` 成立时才走桌面分支。
  // 这个契约不在任何公开文档里 —— 官方一次重构就可能让它失效。
  //
  // 失效有两种表现，**都不会报错**：
  //   · 官方不再读这个全局        → 我们挂的东西是个没人看的装饰
  //   · 读了但字段名/版本变了      → 官方悄悄回落到 iframe 降级
  // 两种都是「功能没了但界面正常」，正是最难发现的那类故障。
  //
  // 【怎么测】
  // 用 **getter 陷阱**记录官方到底读了哪几层。这比轮询 DOM 精确得多 ——
  // 它直接回答「契约有没有被消费」，而不是猜。
  //
  //   read        被读过 dshDesktop     → 契约还认这个全局
  //   browserRead 被读过 .browser        → **确实进了桌面分支**（关键信号）
  //   acquired    调过 acquire()        → 用户真的用上了浏览器面板
  //
  // 【怎么退】
  // 原生侧读这份报告后决定：没进桌面分支就 removeDocumentStartJavaScript
  // 并重载 —— 页面回到**今天这个已知可用的形态**（iframe 降级），而不是白屏。
  var canary = {
    injected: true,
    read: false,
    browserRead: false,
    acquired: false,
    errors: [],
    startedAt: Date.now(),
  }

  function canaryFail(where, e) {
    var msg = where + ': ' + (e && e.message ? e.message : String(e))
    canary.errors.push(msg)
    try { console.warn('[fusion-carrier] ' + msg) } catch (_) {}
  }

  // 暴露给原生侧读。用 JSON 字符串而不是对象 —— addJavascriptInterface 的
  // 返回值必须是基本类型，回传对象会被序列化成 "[object Object]"。
  globalThis.__dshFusionCanary = function () {
    return JSON.stringify({
      injected: canary.injected,
      read: canary.read,
      browserRead: canary.browserRead,
      acquired: canary.acquired,
      errors: canary.errors,
      elapsedMs: Date.now() - canary.startedAt,
    })
  }


  try {
  var host = window.__DSH_FUSION_HOST__
  if (!host) {
    console.warn('[fusion-carrier] 找不到原生宿主桥，桌面能力不启用（回落到 iframe 降级）')
    return
  }

  // ══════════════════════════════════════════════════════════════
  // 一、dshDesktop：官方客户端唯一认的那个全局对象
  // ══════════════════════════════════════════════════════════════

  var openListeners = new Map()   // lease -> Set<listener>

  var browserApi = {
    /** 申请一个存储分区租约。原生侧负责分配与回收。 */
    acquire: function (workspace) {
      return Promise.resolve().then(function () {
        return JSON.parse(host.acquire(String(workspace)))
      })
    },
    /** 释放租约。 */
    release: function (lease) {
      return Promise.resolve().then(function () {
        host.release(String(lease))
      })
    },
    /** 订阅「用户要求在新页打开」。返回退订函数。 */
    onOpenRequested: function (lease, listener) {
      var key = String(lease)
      if (!openListeners.has(key)) {
        openListeners.set(key, new Set())
        try { host.watch(key) } catch (e) { /* 原生侧不在也不致命 */ }
      }
      openListeners.get(key).add(listener)
      return function () {
        var set = openListeners.get(key)
        if (!set) return
        set.delete(listener)
        if (set.size === 0) {
          openListeners.delete(key)
          try { host.unwatch(key) } catch (e) { /* 同上 */ }
        }
      }
    },
  }

  var carrier = {
    protocolVersion: 1,
    browser: browserApi,
    // 桌面专有的 deviceInfo / shortcuts / keyboard / update 等一律不提供：
    // 官方客户端对它们是特性检测，缺了就只少那部分能力，不会崩。
  }
  // ── 陷阱：记录官方有没有读、读到了哪一层 ──
  // 用 getter 而不是 value —— 这是整个金丝雀的观测点。
  // configurable: true 让原生侧卸载 carrier 时能干净地删掉它。
  try {
    Object.defineProperty(globalThis, 'dshDesktop', {
      configurable: true,
      get: function () {
        canary.read = true
        return carrier
      },
    })
  } catch (e) {
    canaryFail('defineProperty(dshDesktop)', e)
    globalThis.dshDesktop = carrier
  }

  // browser 那一层单独再包一次：官方只读 dshDesktop 但**不读 browser**，
  // 说明它没走桌面分支（版本或字段变了），这是最关键的区分。
  var browserApiTrapped = {
    acquire: function (w) {
      canary.browserRead = true
      canary.acquired = true
      try {
        return browserApi.acquire(w)
      } catch (e) {
        canaryFail('acquire', e)
        throw e
      }
    },
    release: function (l) {
      canary.browserRead = true
      try {
        return browserApi.release(l)
      } catch (e) {
        canaryFail('release', e)
        throw e
      }
    },
    onOpenRequested: function (l, fn) {
      canary.browserRead = true
      try {
        return browserApi.onOpenRequested(l, fn)
      } catch (e) {
        canaryFail('onOpenRequested', e)
        throw e
      }
    },
  }
  Object.defineProperty(carrier, 'browser', {
    configurable: true,
    get: function () {
      canary.browserRead = true
      return browserApiTrapped
    },
  })

  /** 原生侧收到「打开新页」请求时调它。 */
  globalThis.__dshFusionOpenRequested = function (lease, url) {
    var set = openListeners.get(String(lease))
    if (!set) return
    set.forEach(function (fn) {
      try { fn(String(url)) } catch (e) { console.warn('[fusion-carrier] open listener 抛错', e) }
    })
  }

  // ══════════════════════════════════════════════════════════════
  // 二、<webview> polyfill
  // ══════════════════════════════════════════════════════════════

  var nativeCreate = document.createElement.bind(document)
  var counter = 0
  var views = new Map()           // viewId -> 记录
  var rafTimer = null

  function nowMs() { return (window.performance && performance.now()) ? performance.now() : Date.now() }

  function createFakeWebview() {
    var id = 'wv' + (++counter)
    var el = nativeCreate('div')

    var state = {
      url: 'about:blank', title: '', loading: false,
      canBack: false, canForward: false,
      partition: '', attached: false, lease: '',
      lastBounds: null,
    }

    // ── Electron webview 元素要有的那套方法 ──
    el.loadURL = function (url) {
      state.url = String(url)
      try { host.loadUrl(id, state.url) } catch (e) { console.warn('[fusion-carrier] loadUrl 失败', e) }
      return Promise.resolve()
    }
    el.getURL = function () { return state.url }
    el.getTitle = function () { return state.title }
    el.canGoBack = function () { return state.canBack }
    el.canGoForward = function () { return state.canForward }
    el.clearHistory = function () { try { host.clearHistory(id) } catch (e) {} }
    el.goBack = function () { try { host.goBack(id) } catch (e) {} }
    el.goForward = function () { try { host.goForward(id) } catch (e) {} }
    el.reload = function () { try { host.reload(id) } catch (e) {} }
    el.isLoading = function () { return state.loading }

    el.__fusionWebviewId = id
    views.set(id, { element: el, state: state })
    return el
  }

  // 拦截 createElement：只有 'webview' 被换掉，其余原样
  document.createElement = function (tag, options) {
    if (typeof tag === 'string' && tag.toLowerCase() === 'webview') return createFakeWebview()
    return nativeCreate(tag, options)
  }

  // ── 属性变化要跟着走（客户端是先设属性再挂载）──
  var ATTR_WATCH = ['name', 'partition', 'src']
  var attrObserver = new MutationObserver(function (records) {
    records.forEach(function (rec) {
      var el = rec.target
      var entry = views.get(el.__fusionWebviewId)
      if (!entry) return
      var attr = rec.attributeName
      if (attr === 'name') entry.state.lease = el.getAttribute('name') || ''
      if (attr === 'partition') entry.state.partition = el.getAttribute('partition') || ''
    })
  })

  // ── 挂载 / 卸载：靠 MutationObserver 侦测连通性 ──
  var domObserver = new MutationObserver(function () { syncAttachments() })
  domObserver.observe(document.documentElement, { childList: true, subtree: true })

  function syncAttachments() {
    views.forEach(function (entry, id) {
      var el = entry.element
      var state = entry.state
      var connected = el.isConnected
      if (connected && !state.attached) {
        state.attached = true
        try {
          host.attach(id, state.lease, state.partition)
        } catch (e) { console.warn('[fusion-carrier] attach 失败', e) }
        startBoundsLoop()
      } else if (!connected && state.attached) {
        state.attached = false
        try { host.detach(id) } catch (e) { /* 卸载失败不致命 */ }
      }
    })
  }

  /**
   * 把占位元素在页面里的位置同步给原生。
   *
   * 原生 WebView 不会跟着网页滚动 —— 这是这条路线最烦的部分。
   * 用固定频率的轮询而不是 scroll/resize 监听：侧边栏会有抽屉动画、
   * 软键盘弹起、分屏尺寸变化，逐个监听远不如轮询稳。
   */
  function startBoundsLoop() {
    if (rafTimer !== null) return
    rafTimer = window.setInterval(function () {
      var anyAttached = false
      views.forEach(function (entry, id) {
        var el = entry.element
        var state = entry.state
        if (!state.attached) return
        anyAttached = true
        var r = el.getBoundingClientRect()
        var bounds = [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)]
        var prev = state.lastBounds
        // 1px 内的抖动不值得跨语言同步一次
        if (prev && Math.abs(prev[0] - bounds[0]) < 2 && Math.abs(prev[1] - bounds[1]) < 2
          && Math.abs(prev[2] - bounds[2]) < 2 && Math.abs(prev[3] - bounds[3]) < 2) return
        state.lastBounds = bounds
        var visible = r.width > 1 && r.height > 1 && r.bottom > 0 && r.top < window.innerHeight
        try {
          host.setBounds(id, bounds[0], bounds[1], bounds[2], bounds[3], visible ? '1' : '0')
        } catch (e) { /* 同步失败下一轮会重试 */ }
      })
      if (!anyAttached) {
        window.clearInterval(rafTimer)
        rafTimer = null
      }
    }, 200)
  }

  // ══════════════════════════════════════════════════════════════
  // 三、原生 → 页面 的回调入口
  // ══════════════════════════════════════════════════════════════

  function fire(el, name, detail) {
    try {
      el.dispatchEvent(new CustomEvent(name, { detail: detail }))
    } catch (e) {
      // CustomEvent 在极老的 WebView 上不存在时退化成普通事件
      try {
        var ev = document.createEvent('Event')
        ev.initEvent(name, false, false)
        el.dispatchEvent(ev)
      } catch (e2) { /* 放弃 */ }
    }
  }

  /**
   * 原生 WebView 的状态回传。
   *
   * 用单个入口 + 字符串参数：每次 evaluateJavascript 都要拼 JS 源码，
   * 入口越少、参数越简单，注入串越不容易出岔子。
   */
  globalThis.__dshFusionWv = function (id, event, payload) {
    var entry = views.get(id)
    if (!entry) return
    var el = entry.element
    var state = entry.state
    var data = null
    if (typeof payload === 'string' && payload.length > 0) {
      try { data = JSON.parse(payload) } catch (e) { data = null }
    }
    switch (event) {
      case 'start':
        state.loading = true
        fire(el, 'did-start-loading', {})
        break
      case 'stop':
        state.loading = false
        if (data && data.url) state.url = data.url
        state.canBack = !!(data && data.canBack)
        state.canForward = !!(data && data.canForward)
        fire(el, 'did-stop-loading', {})
        break
      case 'navigate':
        if (data && data.url) state.url = data.url
        state.canBack = !!(data && data.canBack)
        state.canForward = !!(data && data.canForward)
        fire(el, 'did-navigate', { url: state.url })
        fire(el, 'did-navigate-in-page', { url: state.url, isMainFrame: true })
        break
      case 'title':
        state.title = (data && data.title) || ''
        fire(el, 'page-title-updated', { title: state.title, explicitSet: false })
        break
      case 'fail':
        state.loading = false
        fire(el, 'did-fail-load', {
          errorCode: (data && data.code) || -1,
          errorDescription: (data && data.description) || 'load failed',
          validatedURL: (data && data.url) || state.url,
          isMainFrame: true,
        })
        break
      case 'newwindow':
        // 页面自己要求开新窗口：交给客户端的 onOpenRequested 订阅者
        if (data && data.url) {
          globalThis.__dshFusionOpenRequested(state.lease, data.url)
        }
        break
      default:
        break
    }
  }

  console.log('[fusion-carrier] 桌面能力已挂载（protocolVersion=1，含 browser 桥与 <webview> polyfill）')
  } catch (e) {
    // carrier 自己炸了也要留下痕迹 —— 否则表现只是「浏览器面板没了」
    globalThis.__dshFusionCanaryError = String(e && e.message ? e.message : e)
    throw e
  }
})()
