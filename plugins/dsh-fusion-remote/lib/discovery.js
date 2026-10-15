/**
 * 局域网发现。
 *
 * ── 为什么局域网这层最要紧 ────────────────────────────────────────────
 * 它**不需要任何服务器**：同一网段里两台设备互相广播就能找到对方。
 * 也就是说，即使 rendezvous 还没上线、或者你根本不想让流量出门，
 * 「书房电脑 ↔ 手机」这种最常见的场景已经能用了。
 *
 * ── 广播里放什么、不放什么 ────────────────────────────────────────────
 * 放：协议版本、**公钥**、服务端口、设备名、角色
 * 不放：任何密钥、任何会话、任何「谁已经配对过谁」的信息
 *
 * 公钥是**公开**信息（它出现在每次握手里），广播它没有额外暴露。
 * 但正因为广播对同网段**所有人可见**，设备名要由用户决定 ——
 * 默认用机型而不是用户名，免得在咖啡厅里广播「张三的 MacBook」。
 *
 * ── 一个容易被忽略的点：发现 ≠ 信任 ──────────────────────────────────
 * 收到广播只代表「这个网段里有这么一台设备」。
 * **能不能连是另一回事** —— 那一步走 `auth.js` 的挑战应答与 pin 校验。
 * 把这两件事混在一起（比如「发现到的就信任」）是最常见的错误，
 * 它会让局域网里的任何人都能连上来。
 */

import { createSocket } from 'node:dgram'
import { networkInterfaces } from 'node:os'
import { randomBytes } from 'node:crypto'

/** 协议魔数。用来一眼滤掉同端口上的其它协议的包。 */
export const MAGIC = 'DSHFUSION-RD1'

/** 默认端口。选一个不常用的高位端口，减少撞车。 */
export const DEFAULT_PORT = 48899

/** 广播间隔。2 秒 —— 够快（打开页面就能看到），又不至于刷屏。 */
export const BEACON_INTERVAL_MS = 2000

/** 多久没收到就认为对端不在了。给 3 个广播周期，容忍丢包。 */
export const PEER_TTL_MS = BEACON_INTERVAL_MS * 3

/** 单个广播包的上限。防止有人发巨型包打内存。 */
export const MAX_BEACON = 2048

export const Role = { SERVER: 'serve', CLIENT: 'connect', BOTH: 'both' }

/**
 * 造一个广播包。
 *
 * 字段名都很短 —— 这不是为了省字节，是因为广播包在部分网络设备上
 * 会被当作「可疑流量」限速，小包通过率更高。
 */
export function encodeBeacon({ publicKey, port, name, role, pairingOpen = false }) {
  return JSON.stringify({
    m: MAGIC,
    v: 1,
    pub: publicKey,
    p: port,
    n: String(name ?? '').slice(0, 64),
    r: role,
    // 只有在「等待配对」时才置位 —— 平时不广播这个状态，
    // 免得让人看出「这台设备现在可以被接入」
    o: pairingOpen ? 1 : 0,
    t: Date.now(),
  })
}

/**
 * 解析广播包。
 *
 * **对不可信输入要严**：这是任何人都能往这个端口上发的。
 * 校验不过就返回 null（丢弃），不要「尽力解析」——
 * 「尽力解析」的失败方式是把攻击者构造的字段带进后面的逻辑。
 */
export function decodeBeacon(text, fromIp) {
  if (typeof text !== 'string' || text.length > MAX_BEACON) return null
  let o
  try { o = JSON.parse(text) } catch { return null }
  if (!o || o.m !== MAGIC) return null
  if (o.v !== 1) return null
  if (typeof o.pub !== 'string' || o.pub.length < 40 || o.pub.length > 64) return null
  if (!/^[A-Za-z0-9_-]+$/.test(o.pub)) return null
  const port = Number(o.p)
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null
  const role = [Role.SERVER, Role.CLIENT, Role.BOTH].includes(o.r) ? o.r : Role.BOTH
  return {
    publicKey: o.pub,
    port,
    name: typeof o.n === 'string' ? o.n.slice(0, 64) : '',
    role,
    pairingOpen: o.o === 1,
    address: fromIp,
    seenAt: Date.now(),
  }
}

/** 列出所有可用于广播的 IPv4 地址（含各自的广播地址）。 */
export function listBroadcastTargets() {
  const out = []
  const ifaces = networkInterfaces()
  for (const [ifname, addrs] of Object.entries(ifaces)) {
    for (const a of addrs ?? []) {
      if (a.family !== 'IPv4' || a.internal) continue
      if (!a.netmask) continue
      try {
        const ip = a.address.split('.').map(Number)
        const mask = a.netmask.split('.').map(Number)
        const bcast = ip.map((b, i) => (b & mask[i]) | (~mask[i] & 255)).join('.')
        // **广播地址等于自己时要跳过。**
        // /32 或点对点链路上会算出这个结果（实测：EasyTier 的 CGNAT 网卡
        // 100.64.0.1 就是 100.64.0.1/32），往自己地址发包等于空转 ——
        // 不会报错，只是永远发现不了任何东西，很难查。
        if (bcast === a.address) continue
        out.push({ ifname, address: a.address, broadcast: bcast })
      } catch { /* 单条网卡算不出来就跳过，不影响其它网卡 */ }
    }
  }
  return out
}

/**
 * 起一个发现服务。
 *
 * @param {object} opts
 * @param {string} opts.publicKey 自己的公钥（会被广播出去）
 * @param {number} opts.servicePort 对端连过来时用哪个端口
 * @param {string} opts.name 设备名
 * @param {string} opts.role
 * @param {number} [opts.port] 广播端口
 * @param {(peer) => void} [opts.onPeer] 发现（或刷新）一台设备时回调
 * @param {(peer) => void} [opts.onLost] 一台设备消失时回调
 * @param {(msg: string) => void} [opts.log]
 */
export function createDiscovery({
  publicKey, servicePort, name, role = Role.BOTH, port = DEFAULT_PORT,
  onPeer = () => {}, onLost = () => {}, log = () => {},
}) {
  const peers = new Map()          // "ip:port" → peer
  const socket = createSocket({ type: 'udp4', reuseAddr: true })

  /** 自己发出去的包会回环收到，必须认出来并丢掉。 */
  const isSelf = (p) => p.publicKey === publicKey

  function remember(peer) {
    const key = `${peer.address}:${peer.port}`
    const prev = peers.get(key)
    peers.set(key, peer)
    if (!prev) {
      log(`[discovery] 发现 ${peer.name || '(无名)'} @ ${peer.address}:${peer.port}`)
      onPeer(peer)
    }
  }

  /** 清理过期设备。局域网里拔网线/休眠时**不会**发「我走了」的包。 */
  function sweep(now = Date.now()) {
    for (const [key, p] of peers) {
      if (now - p.seenAt > PEER_TTL_MS) {
        peers.delete(key)
        log(`[discovery] ${p.name || p.address} 已离开`)
        onLost(p)
      }
    }
  }

  let beaconTimer = null
  let sweepTimer = null
  let started = false

  socket.on('message', (buf, rinfo) => {
    const peer = decodeBeacon(buf.toString('utf8'), rinfo.address)
    if (!peer) return
    if (isSelf(peer)) return
    // 用**包来源地址**而不是包里的字段 —— 包里说自己是谁都可以
    peer.address = rinfo.address
    remember(peer)
  })

  // 错误不能吞。UDP 在部分 Android 机型上会因为权限或省电策略失败，
  // 而那种失败是静默的：socket 建起来了，就是发不出去也收不到。
  socket.on('error', (e) => {
    log(`[discovery] socket 错误：${e.message}`)
  })

  return {
    peers: () => [...peers.values()],
    /** 手动触发一次广播（比如用户点了「刷新」）。 */
    announce(pairingOpen = false) {
      const payload = Buffer.from(encodeBeacon({ publicKey, port: servicePort, name, role, pairingOpen }))
      const targets = listBroadcastTargets()
      if (targets.length === 0) {
        log('[discovery] 没有可用的网卡（可能在蜂窝网络下）')
        return 0
      }
      let sent = 0
      for (const t of targets) {
        // 发到子网广播地址，而不是 255.255.255.255 ——
        // 后者在部分路由/手机上会被丢弃或被当成跨网段流量
        try {
          socket.send(payload, 0, payload.length, port, t.broadcast, () => {})
          sent++
        } catch (e) {
          log(`[discovery] 往 ${t.broadcast} 广播失败：${e.message}`)
        }
      }
      return sent
    },
    start() {
      if (started) return Promise.resolve()
      started = true
      return new Promise((resolve, reject) => {
        socket.once('error', reject)
        socket.bind(port, () => {
          socket.setBroadcast(true)
          socket.removeListener('error', reject)
          // 建好就立刻广播一次，别让用户等一整个周期
          this.announce()
          beaconTimer = setInterval(() => this.announce(), BEACON_INTERVAL_MS)
          sweepTimer = setInterval(() => sweep(), BEACON_INTERVAL_MS)
          log(`[discovery] 已启动，监听 udp/${port}`)
          resolve()
        })
      })
    },
    close() {
      if (beaconTimer) clearInterval(beaconTimer)
      if (sweepTimer) clearInterval(sweepTimer)
      beaconTimer = sweepTimer = null
      started = false
      return new Promise((resolve) => {
        try { socket.close(() => resolve()) } catch { resolve() }
      })
    },
    socket,
  }
}

/** 生成一份「设备名」建议值。默认用机型，不要用用户名（会在公共网络上暴露身份）。 */
export function suggestDeviceName(platform = process.platform, hostname = '') {
  const base = { android: 'Android 设备', darwin: 'Apple 设备', win32: 'Windows 设备', linux: 'Linux 设备' }[platform]
  if (base) return `${base} ${randomBytes(1).toString('hex')}`
  return hostname ? `设备 ${hostname.slice(0, 8)}` : '未命名设备'
}
