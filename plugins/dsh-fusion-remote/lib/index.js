/**
 * dsh-fusion-remote · 远程连接插件
 *
 * ⚠ **本功能仍在开发中。** 服务器（rendezvous）尚未就绪，
 *   当前可用的是**局域网直连**。跨网络连接会在服务器上线后启用。
 *
 * ── 一个插件，两个角色 ────────────────────────────────────────────────
 * 服务端与客户端是**同一个插件**（你要求的），靠配置里的 `role` 区分：
 *
 *   off      什么都不做（默认）
 *   serve    把本机 DSH 暴露给已配对的对端
 *   connect  把本机后端换成远端 DSH
 *   both     两者都做
 *
 * 两边都装了这个插件才能连 —— 协议在插件层，天然如此。
 *
 * ── 三种后端模式的落地方式 ────────────────────────────────────────────
 *   local   壳指向本机引擎端口            （插件不参与）
 *   remote  壳指向**隧道端口**            ← 本插件提供
 *   side    本机照常 + 侧边栏指向隧道端口   ← 同一个隧道端口，零额外代码
 *
 * 壳不需要懂协议，它只需要知道「指向哪个端口」。
 */
import { loadOrCreateIdentity, PinStore } from './lib/auth.js'
import { Role as DiscRole, createDiscovery, suggestDeviceName } from './lib/discovery.js'
import { FrameDecoder, Msg, createLocalTunnel, denormalizeHeaders, encodeFrame, serveTunnel } from './lib/tunnel.js'
import { clientHandshake, isHandshakeType, serverHandshake } from './lib/handshake.js'
import { createConnection, createServer } from 'node:net'
import { ChinaIpTable, isPrivateAddress, judgePeer } from './lib/cn-ip.js'
import { createPairingSession, encodePairingText, makePairingPayload } from './lib/pairing.js'
import { PairingGuard, effectiveResistance } from './lib/pairing-guard.js'
import { deviceIdFromPublicKey, shortDeviceId } from './lib/device-id.js'
import { join } from 'node:path'
import { homedir } from 'node:os'

export const name = 'dsh-fusion-remote'

/** 这个插件依赖的服务。少一个就不激活 —— 免得半个功能起来更难查。 */
export const inject = []

export const Config = {
  role: 'off',            // off | serve | connect | both
  mode: 'local',          // local | remote | side（壳读这个决定指向哪）
  deviceName: '',
  /** 远程模式下本机引擎怎么办：'idle' 继续跑但空闲，'stop' 停掉省电。 */
  localEngineInRemote: 'idle',
  /** 隧道端口。0 = 随机，由插件选好之后报给壳。 */
  tunnelPort: 0,
  /** 连接端口（服务端监听）。 */
  servicePort: 48899 + 1,
  discoveryPort: 48899,
  /** 局域网发现开关。关掉之后只接受手动填地址。 */
  discoveryEnabled: true,
  /** 出口监测间隔（毫秒）。只在「对端是公网地址」时才真的会去查。 */
  egressCheckMs: 30000,
  /** 配对码位数。6 位 + 限速已足够；加长会让用户更难核对，通常不划算。 */
  codeDigits: 6,
}

/** 允许的取值。配置写错时**尽早失败**，不要带着半截配置跑起来。 */
const ROLES = new Set(['off', 'serve', 'connect', 'both'])
const MODES = new Set(['local', 'remote', 'side'])

export function apply(ctx, config = {}) {
  const cfg = { ...Config, ...config }
  if (!ROLES.has(cfg.role)) throw new Error(`未知的 role：${cfg.role}`)
  if (!MODES.has(cfg.mode)) throw new Error(`未知的 mode：${cfg.mode}`)

  const log = (m) => ctx.logger?.info?.(`[remote] ${m}`) ?? console.log(`[remote] ${m}`)
  const warn = (m) => ctx.logger?.warn?.(`[remote] ${m}`) ?? console.warn(`[remote] ${m}`)

  // ── 身份与已配对设备 ──
  const stateDir = join(homedir(), '.dsh', 'fusion-remote')
  const identity = loadOrCreateIdentity(stateDir)
  const pins = new PinStore(join(stateDir, 'peers.json'))
  // **限速是短码唯一的安全性来源**，不是可选的加固：
  // 6 位码在无限速下期望 8 分钟被脚本猜中；有指数退避后是 14 年。
  const guard = new PairingGuard(join(stateDir, 'pairing-guard.json'))
  if (identity.chmodFailed) {
    // 不阻止运行，但要让人知道 —— 私钥权限没设上在某些共享目录上是真实存在的
    warn(`私钥文件权限未能设为 0600（${stateDir} 的文件系统可能不支持）`)
  }

  // ── 跨境闸 ──
  const cnTable = ChinaIpTable.load()
  const stale = cnTable.staleness()
  if (stale.known && stale.stale) {
    // 只警告不阻止：数据过时就拒绝连接会让用户某天突然用不了，
    // 那是比过时更糟的失败模式
    warn(`中国 IP 段数据已 ${stale.days} 天未更新（生成于 ${cnTable.generatedAt}），建议重新生成`)
  }

  const runtime = {
    mode: cfg.mode,
    role: cfg.role,
    tunnelPort: null,
    connected: null,          // 当前连接的远端
    peers: [],                // 局域网发现到的
    pendingPairing: null,
    /** 开发中标记。壳与 UI 读它来显示状态。 */
    development: {
      inDevelopment: true,
      reason: 'rendezvous 服务器尚未就绪',
      available: '局域网直连',
      unavailable: '跨网络连接',
    },
  }

  /** 把状态推给壳。壳据此决定 WebView 指向、显示连接状态。 */
  const pushState = () => {
    ctx.emit?.('fusion/remote-state', { ...runtime, peers: runtime.peers.map((p) => ({
      name: p.name, address: p.address, port: p.port, publicKey: p.publicKey, paired: pins.has(p.publicKey),
    })) })
  }

  // ─────────────────────────────────────── 服务端

  let serviceServer = null
  let discovery = null

  async function startServe() {
    serviceServer = createServer((socket) => {
      const from = socket.remoteAddress ?? ''
      // **用 TCP 的实际来源地址，不信对端自称的**
      const judge = judgePeer(cnTable, from)
      if (!judge.allowed) {
        log(`拒绝来自 ${from} 的连接：${judge.detail}`)
        socket.destroy()
        return
      }
      serveTunnel({
        socket,
        targetPort: cfg.localWebPort ?? Number(process.env.DSH_WEB_PORT ?? 3080),
        // 运行期的第二次判定：代理跑起来之后出口变了也要断
        isAllowed: () => true,
        log,
      })
      log(`已把隧道接到本机 DSH（来源 ${from}，${judge.reason}）`)
    })
    await new Promise((res, rej) => {
      serviceServer.once('error', rej)
      serviceServer.listen(cfg.servicePort, '0.0.0.0', () => {
        log(`服务端监听 0.0.0.0:${cfg.servicePort}`)
        res()
      })
    })
  }

  async function startDiscovery() {
    if (!cfg.discoveryEnabled) return
    discovery = createDiscovery({
      publicKey: identity.publicKey,
      servicePort: cfg.servicePort,
      name: cfg.deviceName || suggestDeviceName(process.platform),
      role: cfg.role === 'both' ? DiscRole.BOTH : (cfg.role === 'serve' ? DiscRole.SERVER : DiscRole.CLIENT),
      port: cfg.discoveryPort,
      onPeer: (p) => { runtime.peers = discovery.peers(); pushState() },
      onLost: (p) => { runtime.peers = discovery.peers(); pushState() },
      log,
    })
    await discovery.start()
  }

  // ─────────────────────────────────────── 客户端

  let tunnel = null

  /**
   * 连到一个对端：先做双向认证，再起本地隧道口。
   *
   * **认证必须在那之前完成。** 隧道一起就绪，壳就可能把 WebView 指过去 ——
   * 那时候如果还没认过对方，等于把本机的 DSH 交给一个陌生人。
   */
  async function connectTo({ address, port }) {
    const judge = judgePeer(cnTable, address)
    if (!judge.allowed) throw new Error(`跨境闸：${judge.detail}`)

    const socket = createConnection({ port, host: address })
    await new Promise((res, rej) => {
      socket.once('connect', res)
      socket.once('error', rej)
    })

    const ch = attachChannel(socket)
    // 先认人，再谈别的
    const hs = await clientHandshake({
      send: (m) => ch.send(m),
      onMessage: (h) => ch.onMessage(h),
      identity, pinStore: pins,
    })
    if (!hs.ok) {
      socket.destroy()
      if (hs.needsPairing) {
        // 未配对是**正常路径**，不是错误 —— 要让上层去走配对，而不是报「连接失败」
        throw Object.assign(new Error('这台设备还没配对过，请先配对'), {
          needsPairing: true, peerPublicKey: hs.peerPublicKey,
        })
      }
      throw new Error(`认证失败：${hs.reason}`)
    }

    currentChannel = ch
    tunnel = await createLocalTunnel({ port: cfg.tunnelPort, channel: () => currentChannel, log })
    runtime.tunnelPort = tunnel.port
    runtime.connected = { address, port, name: hs.name }
    log(`隧道口就绪：127.0.0.1:${tunnel.port} → ${address}:${port}（对端：${hs.name ?? '未命名'}）`)
    pushState()
    return tunnel.port
  }

  let currentChannel = null

  /**
   * 把一条裸 socket 包成隧道通道。
   *
   * 认证帧与业务帧走同一条通道 —— 这样「未认证的连接」在协议上
   * 就没有别的能力：它在通过握手之前发的业务帧会被 `authed` 挡掉。
   */
  function attachChannel(socket) {
    let authed = false
    let msgHandler = () => {}
    let pendingResolve = null
    let nextId = 1
    const waiters = new Map()
    const decoder = new FrameDecoder((jsonBuf, body) => {
      let msg
      try { msg = JSON.parse(jsonBuf.toString('utf8')) } catch { return }
      if (isHandshakeType(msg.type)) { msgHandler(msg); return }
      if (!authed) return          // 没通过握手就不处理业务帧
      const w = waiters.get(msg.id)
      if (w) w(msg, body)
    }, (e) => { warn(`通道解帧失败：${e.message}`); socket.destroy() })
    socket.on('data', (c) => decoder.push(c))

    return {
      socket,
      markAuthed() { authed = true },
      send: (obj, body) => { if (!socket.destroyed) socket.write(encodeFrame(obj, body)) },
      onMessage: (h) => { msgHandler = h },
      nextId: () => nextId++,
      awaitResponse: (id, res, _log) => { waiters.set(id, (m, b) => {
        if (m.type === Msg.RESP_HEAD) res.writeHead(m.status, denormalizeHeaders(m.headers))
        else if (m.type === Msg.RESP_BODY) res.write(b)
        else if (m.type === Msg.RESP_END) { res.end(); waiters.delete(id) }
        else if (m.type === Msg.ERROR) { res.writeHead(502); res.end(m.message); waiters.delete(id) }
      }) },
      awaitUpgrade: (id, sock, _log) => { waiters.set(id, (m, b) => {
        if (m.type === Msg.RESP_HEAD && m.upgraded) sock.write(m.raw + '\r\n\r\n')
        else if (m.type === Msg.RAW) sock.write(b)
        else if (m.type === Msg.RESP_END) { sock.end(); waiters.delete(id) }
        else if (m.type === Msg.ERROR) { sock.destroy(); waiters.delete(id) }
      }) },
    }
  }

  // ─────────────────────────────────────── 生命周期

  ctx.effect(() => {
    if (cfg.role === 'serve' || cfg.role === 'both') {
      startServe().catch((e) => warn(`服务端启动失败：${e.message}`))
      startDiscovery().catch((e) => warn(`发现服务启动失败：${e.message}`))
    }
    if (cfg.role === 'connect' || cfg.role === 'both') {
      startDiscovery().catch((e) => warn(`发现服务启动失败：${e.message}`))
    }
    pushState()
    log(`远程插件已加载（role=${cfg.role} mode=${cfg.mode}）`
      + (cfg.role === 'off' ? ' —— 未启用任何远程能力' : ''))

    return async () => {
      // 关闭顺序：先停发现（别再被扫到），再断隧道，最后关服务端
      await discovery?.close().catch(() => {})
      await tunnel?.close().catch(() => {})
      await new Promise((r) => serviceServer?.close(() => r()) ?? r())
    }
  })

  // ─────────────────────────────────────── 对外的工具

  ctx.tools?.define?.({
    name: 'fusion_remote_status',
    description: '查看远程连接插件的状态：模式、角色、发现的设备、已配对设备、跨境闸状况。',
    output: { schema: { type: 'object' } },
    async execute() {
      return {
        inDevelopment: true,
        note: '跨网络连接需要 rendezvous 服务器（准备中）；当前可用局域网直连',
        mode: runtime.mode,
        role: runtime.role,
        deviceName: cfg.deviceName || suggestDeviceName(process.platform),
        publicKey: identity.publicKey,
        tunnelPort: runtime.tunnelPort,
        connected: runtime.connected,
        discovered: runtime.peers.map((p) => ({ name: p.name, address: p.address, port: p.port })),
        paired: pins.list().map((p) => ({ name: p.name, publicKey: p.publicKey.slice(0, 12) + '…' })),
        // 设备 ID 是公钥的可读形式（带 Luhn 校验位，打错一位能查出来）
        deviceId: deviceIdFromPublicKey(identity.publicKey),
        deviceIdShort: shortDeviceId(deviceIdFromPublicKey(identity.publicKey)),
        pairing: {
          ...guard.status(),
          codeDigits: cfg.codeDigits,
          // 把「限速到底有没有用」直接算给用户看，而不是让他信我们
          resistance: effectiveResistance(cfg.codeDigits),
        },
        crossBorderGuard: {
          tableRanges: cnTable.ranges.length,
          generatedAt: cnTable.generatedAt,
          staleDays: stale.days,
        },
      }
    },
  })

  ctx.tools?.define?.({
    name: 'fusion_remote_pair',
    description: '生成一个配对码（二维码内容）。把它给对方扫，或让对方手动核对 6 位数字。',
    output: { schema: { type: 'object' } },
    async execute(args = {}) {
      const method = args.method === 'qr' ? 'qr' : 'code'
      const session = createPairingSession({ myPublicKey: identity.publicKey, method })
      session.start()
      runtime.pendingPairing = session
      pushState()
      const payload = makePairingPayload({
        publicKey: identity.publicKey,
        address: '',                      // 局域网里由发现填，公网由用户填
        port: cfg.servicePort,
        name: cfg.deviceName || suggestDeviceName(process.platform),
      })
      return {
        method,
        qrText: encodePairingText(payload),
        // 数字码要等**对方公钥**到了才算得出来，这里如实说明
        codeNote: method === 'code' ? '等对方连上来后会显示 6 位码，两边核对一致再确认' : undefined,
        publicKey: identity.publicKey,
      }
    },
  })

  // 暴露给测试与壳
  return { runtime, identity, pins, connectTo, startServe, startDiscovery, pushState }
}

export default { name, inject, Config, apply }
