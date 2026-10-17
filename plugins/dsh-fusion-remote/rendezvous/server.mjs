/**
 * DSH Fusion · Rendezvous 服务器
 *
 * ── 它做什么、不做什么 ────────────────────────────────────────────────
 * 做：让两台**互相够不到**的设备（都在 NAT 后、没有公网 IP、不同局域网）
 *     在一个短命通道里会合，然后**中转不透明字节**。
 *
 * 不做：**不参与认证，也看不到明文。**
 *     端到端认证（Ed25519 双向挑战 + 配对码）在插件层做完了。
 *     到这里时，双方交换的已经是被会话密钥保护的字节 —— 服务器只是个管道。
 *
 * 这一条是刻意的：如果服务器的角色是「认证」，
 * 那服务器本身就成了必须被信任的环节，而它跑在别人的机器上（我们的阿里云）。
 * **让服务器无法作恶，比让它承诺不作恶更可靠。**
 *
 * ── 从社区教训里继承的四条 ────────────────────────────────────────────
 *
 * 1. **channel id 短命且一次性**（Magic Wormhole issue #31）
 *    允许复用同一个 nameplate 会放大攻击：攻击者猜中一个码之后，
 *    立刻用同一个码另开一条连接，让真正的对端连到他那里去。
 *    所以这里：id 一次性、用完即焚、超时即焚、**从不回收重用**。
 *
 * 2. **配对码绝不进服务端**
 *    服务端只看到 channel id（那是随机串，与配对码无关）。
 *    日志里也不记 channel id 的全文 —— 它虽然不直接是密钥，
 *    但「谁和谁在会合」属于元数据，能少记就少记。
 *
 * 3. **必须限速**
 *    2 核 1.7 G 的机器，一个脚本就能打满。Wormhole 的作者在自己的攻击模型
 *    文档里说 DoS 是他「没有好办法」的问题 —— 我们正面处理：
 *    按 IP 限制建通道速率、限制并发通道数、限制单通道与全局带宽。
 *
 * 4. **对爬虫和扫描器什么都不给**
 *    所有非 WebSocket 的 HTTP 请求一律 404，不列目录、不报版本、
 *    不带 Server 头。它从来就不是一个网站。
 */
import { createServer } from 'node:http'
import { WebSocketServer } from 'ws'
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'

// ─────────────────────────────────────────── 参数

export const DEFAULTS = {
  port: Number(process.env.PORT ?? 8443),
  host: process.env.HOST ?? '0.0.0.0',

  /** 通道存活时间。3 分钟 —— 与插件侧的配对窗口一致，不多给。 */
  channelTtlMs: 3 * 60 * 1000,
  /** 一个通道必须在这段时间内凑齐两方，否则作废。 */
  joinWindowMs: 90 * 1000,
  /** 通道 id 长度（字节）。16 字节 = 128 bit，猜不到。 */
  channelIdBytes: 16,

  /** 单 IP 每分钟最多建几个通道。 */
  createPerMinute: 6,
  /** 单 IP 最多同时占几个通道。 */
  concurrentPerIp: 3,
  /** 全站最多同时存在几个通道。2核2G 的机器撑不了太多。 */
  maxChannels: 256,
  /** 全站最多同时在线连接数。 */
  maxConnections: 512,

  /** 单通道中继速率上限（字节/秒）。用于防止有人拿它当免费用的大流量通道。 */
  relayBytesPerSecPerChannel: 2 * 1024 * 1024,
  /** 全站中继速率上限（字节/秒）。 */
  relayBytesPerSecTotal: 24 * 1024 * 1024,

  /** WebSocket 心跳间隔。闲置的通道要能被及时发现与回收。 */
  pingIntervalMs: 25 * 1000,
  /** 单帧上限。与插件侧一致。 */
  maxPayload: 64 * 1024 * 1024,

  /** 日志里是否记录完整 IP（默认不记完整值，只在内存里用于限速）。 */
  logFullIp: false,
}

const log = (...a) => console.log(new Date().toISOString(), ...a)

/** IP 脱敏：日志里默认只保留前缀，完整值只在内存里用于限速。 */
function maskIp(ip, full) {
  if (full) return ip
  if (!ip) return '?'
  if (ip.includes(':')) return ip.split(':').slice(0, 2).join(':') + '::***'
  const p = ip.split('.')
  return p.length === 4 ? `${p[0]}.${p[1]}.x.x` : ip
}

/** 通道 id：随机、不可枚举。 */
function newChannelId(bytes) {
  return randomBytes(bytes).toString('base64url')
}

/** 常数时间比较，避免通过响应时间判断 id 是否接近。 */
function sameId(a, b) {
  const x = Buffer.from(String(a))
  const y = Buffer.from(String(b))
  if (x.length !== y.length) return false
  try { return timingSafeEqual(x, y) } catch { return false }
}

// ─────────────────────────────────────────── 限速

/**
 * 单 IP 的建通道限速。
 *
 * 用「滑动窗口计数」而不是令牌桶 —— 这里要防的是「脚本狂建通道」，
 * 令牌桶的平滑放行对它太宽松，而滑窗能明确说出「这一分钟你已经建了 6 个」。
 */
class IpLimiter {
  constructor({ createPerMinute, concurrentPerIp }) {
    this.createPerMinute = createPerMinute
    this.concurrentPerIp = concurrentPerIp
    this.history = new Map()      // ip → 时间戳数组
    this.concurrent = new Map()   // ip → 当前占用数
  }

  canCreate(ip, now = Date.now()) {
    const cutoff = now - 60_000
    const arr = (this.history.get(ip) ?? []).filter((t) => t > cutoff)
    this.history.set(ip, arr)
    if (arr.length >= this.createPerMinute) {
      return { ok: false, reason: 'rate-limited', retryAfterMs: arr[0] + 60_000 - now }
    }
    if ((this.concurrent.get(ip) ?? 0) >= this.concurrentPerIp) {
      return { ok: false, reason: 'too-many-channels' }
    }
    return { ok: true }
  }

  record(ip, now = Date.now()) {
    const arr = this.history.get(ip) ?? []
    arr.push(now)
    this.history.set(ip, arr)
  }

  occupy(ip) { this.concurrent.set(ip, (this.concurrent.get(ip) ?? 0) + 1) }
  release(ip) {
    const n = (this.concurrent.get(ip) ?? 1) - 1
    if (n <= 0) this.concurrent.delete(ip)
    else this.concurrent.set(ip, n)
  }

  /** 定期清掉过期的历史，避免 Map 无限增长。 */
  sweep(now = Date.now()) {
    const cutoff = now - 120_000
    for (const [ip, arr] of this.history) {
      const kept = arr.filter((t) => t > cutoff)
      if (kept.length) this.history.set(ip, kept)
      else this.history.delete(ip)
    }
  }
}

// ─────────────────────────────────────────── 带宽闸

/**
 * 一个很朴素的全局速率闸。
 *
 * 目的不是「精确控制带宽」，而是「别让一个通道把机器的出口吃光」。
 * 所以它在超限时的行为是**丢帧并断开**，而不是排队等待 ——
 * 排队会让内存涨起来，那在 1.7 G 的机器上是更糟的失败方式。
 */
class RateGate {
  constructor(bytesPerSec) {
    this.bytesPerSec = bytesPerSec
    this.used = 0
    this.windowStart = Date.now()
  }
  allow(n, now = Date.now()) {
    if (now - this.windowStart >= 1000) {
      this.windowStart = now
      this.used = 0
    }
    if (this.used + n > this.bytesPerSec) return false
    this.used += n
    return true
  }
}

// ─────────────────────────────────────────── 主体

export function createRendezvous(config = {}) {
  const cfg = { ...DEFAULTS, ...config }
  const limiter = new IpLimiter(cfg)
  const globalGate = new RateGate(cfg.relayBytesPerSecTotal)
  /** channelId → { id, createdAt, peers: [], bytes, gate } */
  const channels = new Map()
  let connections = 0

  const stats = { created: 0, joined: 0, relayedBytes: 0, rejected: 0, closed: 0 }

  function sweepChannels(now = Date.now()) {
    for (const [id, ch] of channels) {
      const expired = now - ch.createdAt > cfg.channelTtlMs
      const tooOldWaiting = ch.peers.length < 2 && now - ch.createdAt > cfg.joinWindowMs
      if (expired || tooOldWaiting) {
        for (const p of ch.peers) {
          try { p.close(1000, 'channel expired') } catch { /* 已断 */ }
        }
        channels.delete(id)
        limiter.release(ch.ownerIp)
        stats.closed++
        // **不记 id 全文** —— 「谁和谁在会合」也是元数据
        log(`channel 关闭（${ch.peers.length} 方，存活 ${Math.round((now - ch.createdAt) / 1000)}s）`)
      }
    }
  }

  function handleNewChannel(req, res, ip) {
    const now = Date.now()
    const ok = limiter.canCreate(ip, now)
    if (!ok.ok) {
      stats.rejected++
      res.writeHead(429, { 'content-type': 'application/json', 'retry-after': '60' })
      res.end(JSON.stringify({ error: ok.reason }))
      return
    }
    if (channels.size >= cfg.maxChannels) {
      stats.rejected++
      res.writeHead(503, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ error: 'server-busy' }))
      return
    }
    const id = newChannelId(cfg.channelIdBytes)
    channels.set(id, {
      id, createdAt: now, peers: [], ownerIp: ip,
      gate: new RateGate(cfg.relayBytesPerSecPerChannel),
      bytes: 0,
    })
    limiter.record(ip, now)
    limiter.occupy(ip)
    stats.created++
    res.writeHead(201, { 'content-type': 'application/json', 'cache-control': 'no-store' })
    res.end(JSON.stringify({ channel: id, ttlMs: cfg.channelTtlMs, joinWindowMs: cfg.joinWindowMs }))
    log(`channel 新建（来自 ${maskIp(ip, cfg.logFullIp)}，当前 ${channels.size} 个）`)
  }

  function handleJoin(ws, ch, ip) {
    // 校验已在 upgrade 阶段做过了（见那里的说明）。
    // 这里再查一次是为了**防住「两方几乎同时升级」的竞态** ——
    // 两个请求都在 `peers.length < 2` 时通过校验，然后都进来，
    // 通道里就出现三个人。这个窗口很小但真实存在。
    if (ch.peers.length >= 2) {
      ws.close(4009, 'channel full')
      stats.rejected++
      return
    }
    ch.peers.push(ws)
    stats.joined++
    log(`channel 有 ${ch.peers.length}/2 方加入`)

    ws.on('message', (data, isBinary) => {
      // **中转不解析。** 我们不该知道里面是什么。
      const buf = isBinary ? data : Buffer.from(data)
      if (!ch.gate.allow(buf.length) || !globalGate.allow(buf.length)) {
        // 超带宽就断开而不是排队 —— 排队会让内存涨起来，
        // 在 1.7 G 的机器上那是更糟的失败方式
        try { ws.close(1013, 'bandwidth limit') } catch { /* 已断 */ }
        return
      }
      stats.relayedBytes += buf.length
      ch.bytes += buf.length
      const other = ch.peers.find((p) => p !== ws)
      if (other && other.readyState === 1) {
        try { other.send(buf, { binary: true }) } catch { /* 对端已断 */ }
      }
    })

    ws.on('close', () => {
      const before = ch.peers.length
      ch.peers = ch.peers.filter((p) => p !== ws)
      // 已经被别的 close 处理过（两方几乎同时断）就不要再算一次
      if (before === ch.peers.length) return
      // **一次性**：任一方断开就整个通道作废，绝不回收重用。
      // 复用会让「猜中一个 id」的价值变大，也是 Wormhole issue #31 的教训。
      //
      // 剩一方时也立刻关 —— 让剩下那方知道对端走了，而不是干等。
      // 「干等」是这里最容易犯的错：用户会以为对面还在，一直对着一个死连接操作。
      if (ch.peers.length > 0) {
        const last = ch.peers[0]
        try { last.close(1001, 'peer left') } catch { /* 已断 */ }
      }
      if (channels.delete(ch.id)) {
        limiter.release(ch.ownerIp)
        stats.closed++
      }
    })

    ws.on('error', () => {
      try { ws.close() } catch { /* 已断 */ }
    })
  }

  // ── HTTP：只认三个端点，其余一律 404 ──
  const server = createServer((req, res) => {
    const ip = (req.socket.remoteAddress ?? '').replace(/^::ffff:/, '')
    const url = new URL(req.url ?? '/', 'http://x')

    // 健康检查：**只回一个词，不泄露版本/负载/通道数。**
    // 运维需要知道「活着没」，攻击者不需要知道「有多忙」。
    if (req.method === 'GET' && url.pathname === '/healthz') {
      res.writeHead(200, { 'content-type': 'text/plain', 'cache-control': 'no-store' })
      res.end('ok')
      return
    }

    if (req.method === 'POST' && url.pathname === '/channel') {
      handleNewChannel(req, res, ip)
      return
    }

    // 其它一切 —— 包括 /、/robots.txt、/.git、/.env、扫描器爱探的那些 ——
    // 统一 404，不带 Server 头，不给任何提示。
    // `X-Robots-Tag` 是给守规矩的爬虫看的；不守规矩的我们也不打算靠它挡。
    stats.rejected++
    res.writeHead(404, {
      'content-type': 'text/plain',
      'x-robots-tag': 'noindex, nofollow',
      'cache-control': 'no-store',
    })
    res.end('not found')
  })

  // 显式去掉 Express 之类会加的指纹；原生 http 本身不带，这里只确保不误加
  server.headersTimeout = 20_000
  server.requestTimeout = 30_000

  const wss = new WebSocketServer({ noServer: true, maxPayload: cfg.maxPayload })

  server.on('upgrade', (req, socket, head) => {
    const ip = (req.socket.remoteAddress ?? '').replace(/^::ffff:/, '')
    if (connections >= cfg.maxConnections) {
      socket.write('HTTP/1.1 503 Service Unavailable\r\n\r\n')
      socket.destroy()
      stats.rejected++
      return
    }
    const url = new URL(req.url ?? '/', 'http://x')
    // 只接受 `/join/<id>`。别的 upgrade 一律拒 —— 也在防「探测有哪些路径」
    const m = /^\/join\/([A-Za-z0-9_-]{16,64})$/.exec(url.pathname)
    if (process.env.RV_DEBUG) log(`upgrade pathname=${JSON.stringify(url.pathname)} 匹配=${!!m}`)
    if (!m) {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n')
      socket.destroy()
      stats.rejected++
      return
    }
    const id = m[1]
    // **通道校验必须在握手之前做。**
    //
    // 第一版是握手成功之后再 `ws.close(4004)` —— 客户端先收到 open、
    // 再收到 close，于是它**分不清「连上了」和「被拒了」**。
    // 测试里就表现为「用完的 id 还能连」。
    //
    // 放在升级前拒还有一个好处：无效请求根本不会完成 WS 握手，
    // 扫描者连一个成功握手都拿不到，也省下握手开销。
    const ch = channels.get(id)
    if (!ch || !sameId(ch.id, id)) {
      socket.write('HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n')
      socket.destroy()
      stats.rejected++
      return
    }
    if (ch.peers.length >= 2) {
      socket.write('HTTP/1.1 409 Conflict\r\nContent-Length: 0\r\n\r\n')
      socket.destroy()
      stats.rejected++
      return
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      connections++
      ws.on('close', () => { connections-- })
      handleJoin(ws, ch, ip)
    })
  })

  const timers = [
    setInterval(() => sweepChannels(), 15_000),
    setInterval(() => limiter.sweep(), 60_000),
    // 心跳：把僵死的连接揪出来，否则通道会一直占着
    setInterval(() => {
      for (const ch of channels.values()) {
        for (const p of ch.peers) {
          if (p.__alive === false) { try { p.terminate() } catch { /* */ } }
          p.__alive = false
          try { p.ping() } catch { /* */ }
        }
      }
    }, cfg.pingIntervalMs),
  ]

  function attachPong() {
    wss.on('connection', (ws) => { ws.__alive = true; ws.on('pong', () => { ws.__alive = true }) })
  }
  attachPong()

  return {
    server, wss, channels, stats, cfg,
    listen() {
      return new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(cfg.port, cfg.host, () => {
          log(`rendezvous 在听 ${cfg.host}:${cfg.port}`)
          log(`  channel TTL ${cfg.channelTtlMs / 1000}s · 单 IP ${cfg.createPerMinute}/分 · 上限 ${cfg.maxChannels} 通道`)
          resolve(server.address())
        })
      })
    },
    close() {
      for (const t of timers) clearInterval(t)
      for (const ch of channels.values()) {
        for (const p of ch.peers) { try { p.terminate() } catch { /* */ } }
      }
      channels.clear()
      return new Promise((r) => {
        wss.close(() => server.close(() => r()))
      })
    },
  }
}

// ─────────────────────────────────────────── 直接运行

const isMain = process.argv[1] && import.meta.url === `file://${process.argv[1]}`
if (isMain) {
  const rv = createRendezvous()
  await rv.listen()
  const shutdown = async (sig) => {
    log(`收到 ${sig}，正在关闭…`)
    await rv.close()
    process.exit(0)
  }
  process.on('SIGTERM', () => shutdown('SIGTERM'))
  process.on('SIGINT', () => shutdown('SIGINT'))
}
