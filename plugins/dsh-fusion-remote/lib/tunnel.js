/**
 * 隧道：本地端口 ⇄ 通道 ⇄ 对端 DSH 的 web 端口。
 *
 * ── 为什么要有这一层 ──────────────────────────────────────────────────
 * 「远程模式」要让壳把 WebView 指到远端。但**壳不应该懂协议** ——
 * 它只要能拿到一个本地地址就行。所以插件在本地开一个端口，
 * 把它接到远端的 DSH 上；壳只改一个 URL。
 *
 * 顺带「同时」模式就免费了：侧边栏只是同一个端口的第二个消费者。
 *
 * ── 这里必须支持的三样东西 ────────────────────────────────────────────
 * DSH 的 Web UI 不是普通网页，它有三条长连接：
 *   · **SSE**（`/api/event` 之类）—— 模型流式回复走这条
 *   · **WebSocket**（`/api/remote.mux`）—— 实时通道
 *   · 普通 HTTP
 *
 * 三者里最容易写错的是 WebSocket 的升级握手。**本项目踩过**：
 * 之前一个代理实现有三处 bug，全都只在 WebSocket 上暴露，
 * 而它的 8 个测试一个都没测到 —— 因为那些测试全是手写的 HTTP 请求。
 * 教训是：**被测试的路径和出问题的路径不是同一条时，覆盖率是安慰剂。**
 * 所以下面每一处都注明了「为什么」。
 *
 * ── 帧格式 ────────────────────────────────────────────────────────────
 * 通道上跑的是长度前缀的消息（4 字节大端长度 + JSON 头 + 可选二进制体）。
 * 选它而不是 HTTP 明文转发，是因为要能双向传输原始字节（WebSocket 升级之后
 * 数据就不再是 HTTP 了，任何「解析成 HTTP 再转发」的做法都会在那里断掉）。
 */

import { createConnection } from 'node:net'
// **本地隧道口必须用 http.createServer，不是 net.createServer。**
// 两者签名看起来一样，给的却是完全不同的东西：
//   · http.createServer((req, res) => …)  ← req 是 IncomingMessage
//   · net.createServer((socket) => …)     ← 参数是裸 Socket
// 用错时 `req.method` / `req.url` 会是 undefined，`req.on('end')` 永远不触发，
// 表现是「请求发出去就没了」—— 不报错、不断开、就是挂住。
// 纯 JS 里这种类型混淆只有跑起来才发现；有类型系统的话一秒就报出来了。
import { createServer as createHttpServer } from 'node:http'

/** 单帧上限。DSH 传输里最大的是附件与截图，给到 64 MB。 */
export const FRAME_HEADER = 8
export const MAX_FRAME = 64 * 1024 * 1024

/**
 * 分帧：`[4 字节总长][4 字节 JSON 长][JSON][二进制体]`。
 *
 * ── 为什么是两个长度，而不是「JSON 里写 bodyLen 然后拼起来」 ──────────
 * 因为接收端**没法知道 JSON 在哪里结束**。
 * 直接把 `JSON 文本 + 二进制` 拼一帧、然后 `JSON.parse(整帧)` 的话，
 * 尾部的二进制会让解析直接失败 —— 而失败发生在**每一个带 body 的请求**上，
 * 症状却是「页面能开，一提交就出错」，很难指向分帧。
 *
 * 两个长度把这件事变成确定的：JSON 长多少就是多少，剩下的都是体。
 * 第一版就是这么写错的（注释里写了这个坑，发送端却照踩）。
 */
export function encodeFrame(payload, body) {
  const json = Buffer.isBuffer(payload) ? payload : Buffer.from(JSON.stringify(payload), 'utf8')
  const bin = Buffer.isBuffer(body) ? body : Buffer.alloc(0)
  const head = Buffer.allocUnsafe(8)
  head.writeUInt32BE(8 + json.length + bin.length, 0)   // 总长（含头）
  head.writeUInt32BE(json.length, 4)
  return Buffer.concat([head, json, bin])
}

/**
 * 增量解帧。
 *
 * 用累积缓冲 + 循环取出完整帧，而不是「假设一次 data 事件就是一帧」——
 * TCP 是字节流，**一帧可能分多次到达，多帧也可能挤在一次到达里**。
 * 后者是长连接下的常态（服务端连续快发），前者在跨网络时是常态。
 */
export class FrameDecoder {
  constructor(onFrame, onError) {
    this.buf = Buffer.alloc(0)
    this.onFrame = onFrame
    this.onError = onError ?? (() => {})
  }

  push(chunk) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk
    for (;;) {
      if (this.buf.length < 8) return
      const len = this.buf.readUInt32BE(0)
      if (len < 8) { this.onError(new Error(`帧长非法：${len}`)); return }
      if (len > MAX_FRAME) {
        // 长度离谱 = 流已经错位了。继续解只会产出垃圾，
        // 而垃圾会被当成合法消息处理 —— 所以这里必须断开，不能跳过
        this.onError(new Error(`帧长超限：${len} > ${MAX_FRAME}（流已错位）`))
        return
      }
      if (this.buf.length < len) return
      const frame = this.buf.subarray(0, len)
      this.buf = this.buf.subarray(len)
      const jsonLen = frame.readUInt32BE(4)
      if (jsonLen > len - 8) {
        this.onError(new Error(`JSON 段长度非法：${jsonLen} > ${len - 8}`))
        return
      }
      try {
        this.onFrame(frame.subarray(8, 8 + jsonLen), frame.subarray(8 + jsonLen))
      } catch (e) {
        this.onError(e)
        return
      }
    }
  }
}

/** 消息类型。 */
export const Msg = {
  HELLO: 'hello',           // 握手
  HELLO_OK: 'hello-ok',
  REQ: 'req',               // 一次 HTTP 请求
  RESP_HEAD: 'resp-head',
  RESP_BODY: 'resp-body',
  RESP_END: 'resp-end',
  UPGRADE: 'upgrade',       // WebSocket 升级：之后的字节流原样透传
  RAW: 'raw',               // 升级后的原始字节
  ERROR: 'error',
}

// ─────────────────────────────────────────── 头部处理

/**
 * 把 Node 的 headers 对象归一成「可重复的键值对数组」。
 *
 * **这一条是本文件存在的主要理由。**
 * `request.headers` 会把同名头合并：`set-cookie` 变成数组，
 * 而 `sec-websocket-key` / `sec-websocket-version` 这类只有一个的还好，
 * 但反过来 —— 用 `headers[k][0]` 或 `headers[k]` 只取一个 —— 就会在
 * 需要多个 `set-cookie` 的响应上丢数据。
 *
 * 之前那个实现的问题是：转发 WebSocket 握手时只转发了**一个**
 * `sec-websocket-*` 头，而握手**必须同时有 `Sec-WebSocket-Key` 和
 * `Sec-WebSocket-Version`**，缺一个服务端就拒。结果整个实时通道是死的，
 * 而日志上看不出任何错误 —— 因为 HTTP 部分全都好好的。
 */
export function normalizeHeaders(raw) {
  const out = []
  for (const [k, v] of Object.entries(raw ?? {})) {
    if (Array.isArray(v)) for (const one of v) out.push([k, String(one)])
    else if (v !== undefined) out.push([k, String(v)])
  }
  return out
}

/**
 * 把键值对数组还原成 Node 能用的 headers。
 * 同名头合并成数组 —— 反过来只留一个会丢 `set-cookie`。
 */
export function denormalizeHeaders(pairs) {
  const out = {}
  for (const [k, v] of pairs ?? []) {
    if (out[k] === undefined) out[k] = v
    else if (Array.isArray(out[k])) out[k].push(v)
    else out[k] = [out[k], v]
  }
  return out
}

/**
 * 逐跳头（hop-by-hop）：按 RFC 7230 它们**只对单条连接有意义**，
 * 不能被代理转发。转发 `Connection: close` 是最典型的错误 ——
 * 它会让下游在响应后关连接，而 WebSocket 升级恰恰需要连接**保持**。
 */
export const HOP_BY_HOP = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'upgrade',
  // **`transfer-encoding` 不在这里，这是刻意的。**
  //
  // 按 RFC 7230 它是逐跳头，代理转发时应当删掉 —— 但那条规则的前提是
  // **代理会重新分帧**。我们这个隧道是**字节透明**的：响应体原样转过去，
  // 分块标记（`2c\r\n…\r\n0\r\n\r\n`）也一起转。
  //
  // 删掉头的后果是确定的：客户端不知道要解分块，于是把
  // `2c\r\n这是一段请求体\r\n0\r\n\r\n` **原样当成正文**。
  // 表现是「POST 返回值里混了十六进制和 \r\n」—— 看起来像编码问题，
  // 其实是头删错了。这条是端到端测试抓出来的，纯逻辑测试不会碰到。
  //
  // 两个都对的做法：要么保留这个头（我们在做的），
  // 要么在这里解分块、转发解码后的正文。前者更简单，且对 SSE 天然正确。
])

/**
 * 过滤逐跳头。
 *
 * 但有个例外要处理对：`Upgrade` / `Connection: Upgrade` 在**升级请求**里
 * 是必须转发的 —— 那正是它的用途。所以这里不是无脑删，
 * 而是「默认删，升级时把 Upgrade 相关的挑出来」。
 */
export function stripHopByHop(pairs, { isUpgrade = false } = {}) {
  const out = []
  for (const [k, v] of pairs) {
    const low = k.toLowerCase()
    if (!HOP_BY_HOP.has(low)) { out.push([k, v]); continue }
    if (isUpgrade && (low === 'upgrade' || low === 'connection')) out.push([k, v])
  }
  return out
}

/** 判断一个请求是不是 WebSocket 升级。 */
export function isUpgradeRequest(headersPairs) {
  for (const [k, v] of headersPairs) {
    if (k.toLowerCase() === 'upgrade' && String(v).toLowerCase() === 'websocket') return true
  }
  return false
}

// ─────────────────────────────────────────── 客户端侧

/**
 * 本地隧道口：壳把 WebView 指到这里。
 *
 * @param {object} opts
 * @param {number} opts.port            本地监听端口（0 = 随机）
 * @param {() => object|null} opts.channel 取当前通道；null 表示未连接
 * @param {(msg: string) => void} opts.log
 */
export function createLocalTunnel({ port = 0, channel, log = () => {} }) {
  const server = createHttpServer((req, res) => {
    const ch = channel()
    if (!ch) {
      // 未连接时给一个**能看懂**的错误，而不是挂起或 502。
      // 「远程后端不可用」比一个空响应有用得多 —— 用户在 WebView 里
      // 看到的是这行字，而不是一片白。
      res.writeHead(503, { 'content-type': 'text/plain; charset=utf-8' })
      res.end('远程 DSH 未连接。请在设置 → 通用 → DSH 后端 里检查连接状态。')
      return
    }
    forwardHttp(ch, req, res, log)
  })

  // WebSocket 升级：**必须单独接**。
  // 走普通 request 路径的话，Node 会在 'upgrade' 事件里把 socket 交给你，
  // 而 request 处理器根本不会被调用 —— 漏接这个事件的表现是
  // 「页面能开，但实时通道永远连不上」。
  server.on('upgrade', (req, socket, head) => {
    const ch = channel()
    if (!ch) { socket.destroy(); return }
    forwardUpgrade(ch, req, socket, head, log)
  })

  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, '127.0.0.1', () => {
      const actual = server.address().port
      log(`[tunnel] 本地隧道口就绪 127.0.0.1:${actual}`)
      resolve({
        port: actual,
        close: () => new Promise((r) => server.close(() => r())),
        server,
      })
    })
  })
}

function forwardHttp(ch, req, res, log) {
  const id = ch.nextId()
  log(`[tunnel] 本地收到 ${req.method} ${req.url} → id=${id}`)
  const pairs = stripHopByHop(normalizeHeaders(req.headers))
  const chunks = []
  req.on('data', (c) => chunks.push(c))
  req.on('end', () => {
    const body = Buffer.concat(chunks)
    log(`[tunnel] 转发 id=${id}（体 ${body.length} 字节）`)
    ch.send({
      type: Msg.REQ, id,
      method: req.method,
      url: req.url,
      headers: pairs,
      bodyLen: body.length,
    }, body)
  })
  req.on('error', () => ch.send({ type: Msg.ERROR, id, message: '客户端请求中断' }))

  ch.awaitResponse(id, res, log)
}

function forwardUpgrade(ch, req, socket, head, log) {
  const id = ch.nextId()
  const pairs = normalizeHeaders(req.headers)
  // 升级请求里 `Upgrade` 与 `Connection` 是要转发的（见 stripHopByHop 的说明）。
  // 但**同时**要确认 `Sec-WebSocket-Key` 与 `Sec-WebSocket-Version` 都在 ——
  // 上一个实现只转了一个，服务端直接拒，而日志里什么异常都没有。
  const forwards = stripHopByHop(pairs, { isUpgrade: true })
  const hasKey = forwards.some(([k]) => k.toLowerCase() === 'sec-websocket-key')
  const hasVer = forwards.some(([k]) => k.toLowerCase() === 'sec-websocket-version')
  if (!hasKey || !hasVer) {
    log(`[tunnel] 拒绝升级：握手头不全（key=${hasKey} version=${hasVer}）`)
    socket.destroy()
    return
  }
  log(`[tunnel] 本地升级请求 ${req.url} → id=${id}（key/ver 齐全）`)
  ch.send({ type: Msg.UPGRADE, id, url: req.url, headers: forwards })
  if (head?.length) ch.send({ type: Msg.RAW, id }, head)

  socket.on('data', (c) => ch.send({ type: Msg.RAW, id }, c))
  socket.on('close', () => ch.send({ type: Msg.RESP_END, id }))
  socket.on('error', () => ch.send({ type: Msg.RESP_END, id }))

  ch.awaitUpgrade(id, socket, log)
}

// ─────────────────────────────────────────── 服务端侧

/**
 * 把一条已认证的通道接到本机的 DSH web 端口上。
 *
 * @param {object} opts
 * @param {import('node:net').Socket} opts.socket 通道
 * @param {number} opts.targetPort 本机 DSH 的 web 端口
 * @param {(peer) => boolean} opts.isAllowed 每次请求前再确认一次（跨境闸的运行时检查）
 */
export function serveTunnel({ socket, targetPort, isAllowed, log = () => {} }) {
  /** id → 已经建好的上游连接 */
  const live = new Map()

  const send = (obj, body) => {
    if (socket.destroyed) return
    socket.write(encodeFrame(obj, body))
  }

  const decoder = new FrameDecoder((jsonBuf, bodyBuf) => {
    // JSON 与二进制已经由分帧层切开了，这里不用再猜边界
    let msg
    try { msg = JSON.parse(jsonBuf.toString('utf8')) } catch { return }
    handle(msg, bodyBuf)
  }, (e) => {
    log(`[tunnel] 解帧失败，断开：${e.message}`)
    socket.destroy()
  })

  function handle(msg, body) {
    log(`[tunnel] 服务端收到 ${msg.type} id=${msg.id}`)
    if (!isAllowed?.()) {
      // 运行期的跨境闸：不是只在握手时检查一次。
      // 代理已经开始工作之后出口变了（用户开了全局代理），这时必须断开，
      // 而不是把已经建立的连接当成「历史遗留」继续用。
      send({ type: Msg.ERROR, id: msg.id, message: '跨境闸：当前网络出口不允许远程连接' })
      socket.destroy()
      return
    }
    switch (msg.type) {
      case Msg.REQ: return doRequest(msg, body)
      case Msg.UPGRADE: return doUpgrade(msg)
      case Msg.RAW: return doRaw(msg, body)
      case Msg.RESP_END: return closeLive(msg.id)
      default: return
    }
  }

  function doRequest(msg, body) {
    log(`[tunnel] 连上游 127.0.0.1:${targetPort}`)
    const upstream = createConnection({ port: targetPort, host: '127.0.0.1' }, () => {
      log(`[tunnel] 上游已连，发请求`)
      const headers = denormalizeHeaders(stripHopByHop(msg.headers))
      // 用原始路径转发；不加 Host 改写 —— DSH 的同源闸要求 Origin 与 Host 一致，
      // 改了 Host 会让它以为跨站，于是拒绝。这是「代理之后页面能开但 API 全 403」
      // 的典型原因。
      const lines = [`${msg.method} ${msg.url} HTTP/1.1`]
      for (const [k, v] of normalizeHeaders(headers)) lines.push(`${k}: ${v}`)
      upstream.write(lines.join('\r\n') + '\r\n\r\n')
      if (body.length) upstream.write(body)
    })
    live.set(msg.id, upstream)

    let decoded
    try {
      decoded = new HttpStreamDecoder()
    } catch {
      upstream.destroy(); return
    }
    upstream.on('data', (chunk) => {
      log(`[tunnel] 上游回来 ${chunk.length} 字节`)
      for (const part of decoded.push(chunk)) {
        if (part.head) {
          // 分块已经在 HttpStreamDecoder 里解掉了，所以这个头不能往下传：
          // 传下去的话本地 Node 会以为正文是分块的，而它拿到的是解码后的裸字节。
          // 去掉之后 Node 会自己重新分帧 —— 流式语义因此保住。
          const headers = part.head.headers.filter(
            ([k]) => k.toLowerCase() !== 'transfer-encoding')
          send({ type: Msg.RESP_HEAD, id: msg.id, ...part.head, headers })
        } else if (part.body) send({ type: Msg.RESP_BODY, id: msg.id }, part.body)
      }
    })
    upstream.on('end', () => { send({ type: Msg.RESP_END, id: msg.id }); live.delete(msg.id) })
    upstream.on('error', (e) => {
      send({ type: Msg.ERROR, id: msg.id, message: `上游连接失败：${e.message}` })
      live.delete(msg.id)
    })
  }

  function doUpgrade(msg) {
    const headers = denormalizeHeaders(msg.headers)
    const key = headers['sec-websocket-key']
    const ver = headers['sec-websocket-version']
    const upstream = createConnection({ port: targetPort, host: '127.0.0.1' }, () => {
      const lines = [`GET ${msg.url} HTTP/1.1`]
      for (const [k, v] of normalizeHeaders(headers)) lines.push(`${k}: ${v}`)
      upstream.write(lines.join('\r\n') + '\r\n\r\n')
    })
    live.set(msg.id, upstream)
    log(`[tunnel] 升级到上游（key=${String(key).slice(0, 8)}… version=${ver}）`)

    let upgraded = false
    upstream.on('data', (chunk) => {
      log(`[tunnel] 升级通道回来 ${chunk.length} 字节：${JSON.stringify(chunk.subarray(0, 40).toString('latin1'))}`)
      if (!upgraded) {
        const idx = chunk.indexOf('\r\n\r\n')
        if (idx < 0) { log('[tunnel] 还没收到完整头部'); return }
        const headText = chunk.subarray(0, idx).toString('latin1')
        const rest = chunk.subarray(idx + 4)
        send({ type: Msg.RESP_HEAD, id: msg.id, upgraded: true, raw: headText })
        // 升级**成功**之后，剩下的字节原样透传 —— 不能再当 HTTP 解析。
        // 把这之后的流继续按 HTTP 解，就会把 WebSocket 帧当成响应体，
        // 表现为「连上了但收到乱码 / 立刻断开」。
        upgraded = /^HTTP\/1\.1 101/.test(headText)
        if (rest.length) send({ type: Msg.RAW, id: msg.id }, rest)
      } else if (chunk.length) {
        send({ type: Msg.RAW, id: msg.id }, chunk)
      }
    })
    upstream.on('end', () => { send({ type: Msg.RESP_END, id: msg.id }); live.delete(msg.id) })
    upstream.on('error', (e) => {
      send({ type: Msg.ERROR, id: msg.id, message: `升级失败：${e.message}` })
      live.delete(msg.id)
    })
  }

  function doRaw(msg, body) {
    const c = live.get(msg.id)
    if (c && !c.destroyed) c.write(body)
  }

  function closeLive(id) {
    const c = live.get(id)
    if (c) { c.end(); live.delete(id) }
  }

  // **必须把 socket 的入站字节接进解码器。**
  // 第一版漏了这一行：解码器建好了、处理器写全了，但没有任何东西喂它 ——
  // 表现是「服务端装死」：不报错、不断开、就是什么都不发生。
  // 端到端测试一跑就露馅（纯逻辑测试全过，因为它们直接调了处理器）。
  socket.on('data', (chunk) => decoder.push(chunk))

  socket.on('close', () => { for (const c of live.values()) c.destroy(); live.clear() })
  socket.on('error', () => { for (const c of live.values()) c.destroy(); live.clear() })
}

/**
 * 极简的 HTTP 响应流解析：从上游字节流里切出「头」与「体」。
 *
 * 不数 Content-Length 也能工作：把收到的一切按顺序发出去，由客户端侧的
 * Node HTTP 解析器去完成剩下的。这里只需要**正确找到头的结束位置**。
 */
export class HttpStreamDecoder {
  constructor() {
    this.headDone = false
    this.buf = Buffer.alloc(0)
    this.chunked = false
    this.chunkState = 'size'      // size → data → crlf → done
    this.need = 0
  }

  /**
   * 解分块传输。
   *
   * ── 为什么必须解 ───────────────────────────────────────────────────
   * 上游（Node 的 http 服务）在没有 Content-Length 时会用 chunked。
   * 我们把响应头与响应体**原样**转发给本地 WebView 那边的 Node response ——
   * 而 `ServerResponse` 发现没有 Content-Length，**自己又分块一次**。
   *
   * 结果是：上游的分块字节成了「正文」，被 Node 再包一层。
   * 客户端解开外层，看到的是内层的 `2c\r\n…\r\n0\r\n\r\n`。
   * 症状是「POST 返回值里混进了十六进制和 \r\n」——
   * 看着像编码问题，其实是**分块套了两层**。
   *
   * 解掉之后交给 Node 自己重新分帧：流式语义（SSE）照样成立，
   * 因为 Node 在没有 Content-Length 时也是边写边分块。
   */
  decodeChunks(chunk, out) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk
    for (;;) {
      if (this.chunkState === 'size') {
        const i = this.buf.indexOf('\r\n')
        if (i < 0) return
        const line = this.buf.subarray(0, i).toString('latin1').split(';')[0].trim()
        const n = parseInt(line, 16)
        this.buf = this.buf.subarray(i + 2)
        if (!Number.isFinite(n) || n < 0) { this.chunkState = 'done'; return }
        if (n === 0) { this.chunkState = 'done'; this.buf = Buffer.alloc(0); return }
        this.need = n
        this.chunkState = 'data'
      } else if (this.chunkState === 'data') {
        if (this.buf.length < this.need) return
        out.push(this.buf.subarray(0, this.need))
        this.buf = this.buf.subarray(this.need)
        this.need = 0
        this.chunkState = 'crlf'
      } else if (this.chunkState === 'crlf') {
        if (this.buf.length < 2) return
        this.buf = this.buf.subarray(2)
        this.chunkState = 'size'
      } else return
    }
  }

  push(chunk) {
    const out = []
    if (!this.headDone) {
      this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk
      const idx = this.buf.indexOf('\r\n\r\n')
      if (idx < 0) return out
      const headText = this.buf.subarray(0, idx).toString('latin1')
      const body = this.buf.subarray(idx + 4)
      this.buf = Buffer.alloc(0)
      this.headDone = true
      const [statusLine, ...headerLines] = headText.split('\r\n')
      const m = /^HTTP\/1\.[01] (\d{3})\s*(.*)$/.exec(statusLine)
      const headers = []
      for (const line of headerLines) {
        const i = line.indexOf(':')
        if (i > 0) headers.push([line.slice(0, i).trim(), line.slice(i + 1).trim()])
      }
      out.push({ head: { status: m ? Number(m[1]) : 502, statusText: m ? m[2] : '', headers } })
      if (process.env.DSH_TUNNEL_DEBUG) {
        console.error(`[tunnel:debug] 响应头 ${m?.[1]} ${JSON.stringify(headers)}`)
      }
      this.chunked = headers.some(([k, v]) =>
        k.toLowerCase() === 'transfer-encoding' && /chunked/i.test(String(v)))
      if (body.length) this.emitBody(body, out)
      return out
    }
    if (chunk.length) this.emitBody(chunk, out)
    return out
  }

  emitBody(chunk, out) {
    if (!this.chunked) { out.push({ body: chunk }); return }
    const parts = []
    this.decodeChunks(chunk, parts)
    for (const b of parts) if (b.length) out.push({ body: b })
  }
}
