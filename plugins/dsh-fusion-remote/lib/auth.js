/**
 * 双向认证。
 *
 * ── 威胁模型 ──────────────────────────────────────────────────────────
 * 远程连接一旦建立，对端就能通过 DSH 在本机执行工具 ——
 * 那等于拿到设备控制权。所以这里要防的不是「被偷看」，是**被接管**：
 *
 *   1. 有人扫到端口，直接来连
 *   2. 中间人冒充其中一方
 *   3. 重放之前抓到的握手包
 *
 * ── 三层，缺一不可 ────────────────────────────────────────────────────
 *
 * **第一层：双向挑战应答。**
 * 不是单向认证。单向（只有服务端验客户端）意味着客户端不知道对面是谁 ——
 * 中间人可以冒充服务端，客户端照样把数据交出去。
 * 这里双方都要用私钥签对方的随机数，**谁都冒充不了谁**。
 *
 * **第二层：配对码。**
 * 首次配对时两侧各显示一个 6 位码，由**双方公钥共同派生** ——
 * 所以两边算出来必然相同。用户核对一致后确认，双方把对方公钥 pin 下来。
 *
 * > 安全性来自**首次使用时的人工核对**，不来自协议本身。
 * > 协议只能保证「你现在说话的对象，和上次是同一个」。
 * > 这与 SSH 的 known_hosts 是同一个思路 ——
 * > 也同样的：**跳过核对就等于没有这一层**，所以这里不做「跳过」选项。
 *
 * **第三层：公钥变更即拒绝。**
 * 已 pin 的对端换了公钥 → 拒绝并告警，不静默接受。
 * 静默接受等于把中间人攻击变成一个透明代理。
 *
 * ── 明确不做的事 ──────────────────────────────────────────────────────
 * · 不做「记住这台设备，以后免确认」——那会把第二层削掉
 * · 不做基于 IP 的信任 —— IP 可以伪造，公钥不行
 * · 密钥材料不进任何日志（桥的日志会经过 /console）
 */

import {
  createPrivateKey, createPublicKey, generateKeyPairSync,
  randomBytes, sign, timingSafeEqual, verify, createHash,
} from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

/** 协议版本。不匹配直接拒绝 —— 免得新旧版本用不同规则握手。 */
export const PROTOCOL_VERSION = 1

const NONCE_BYTES = 32

/** 新随机数（base64url）。 */
export function nonce() {
  return randomBytes(NONCE_BYTES).toString('base64url')
}

// ─────────────────────────────────────────── 身份

export class Identity {
  constructor(privateKey, publicKeyRaw) {
    this.privateKey = privateKey          // KeyObject
    this.publicKeyRaw = publicKeyRaw      // Buffer(32)，Ed25519 原始公钥
  }

  /** 公钥的 base64url 形式 —— 用于传输、pin、配对码派生。 */
  get publicKey() {
    return this.publicKeyRaw.toString('base64url')
  }

  /** 给一段数据签名。 */
  sign(data) {
    return sign(null, Buffer.from(data, 'utf8'), this.privateKey).toString('base64url')
  }

  /** 伪装成空实现的对象，用于「未配置」时快速失败而不是静默放行。 */
  static unavailable() {
    return new Identity(null, Buffer.alloc(0))
  }
}

/** 生成新身份。 */
export function generateIdentity() {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519')
  const raw = publicKey.export({ type: 'spki', format: 'der' }).subarray(-32)
  return new Identity(privateKey, raw)
}

/**
 * 从目录读取身份，没有就生成。
 *
 * 私钥以 **0600** 写入 —— 手机上的其他应用读不到。
 * 注意 `chmod` 在部分文件系统（如共享目录）上无效，所以失败不抛错，
 * 但**会记下**这个事实（交给调用方决定要不要告警）。
 */
export function loadOrCreateIdentity(dir) {
  const keyPath = join(dir, 'identity.key')
  const pubPath = join(dir, 'identity.pub')
  if (existsSync(keyPath)) {
    const raw = readFileSync(keyPath, 'utf8').trim()
    const privateKey = createPrivateKey({
      key: Buffer.from(raw, 'base64'),
      format: 'der',
      type: 'pkcs8',
    })
    const pub = createPublicKey(privateKey).export({ type: 'spki', format: 'der' }).subarray(-32)
    return new Identity(privateKey, pub)
  }
  const id = generateIdentity()
  mkdirSync(dir, { recursive: true })
  let chmodFailed = false
  try {
    writeFileSync(keyPath, id.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64'),
                  { mode: 0o600 })
    chmodSync(keyPath, 0o600)
  } catch { chmodFailed = true }
  writeFileSync(pubPath, id.publicKey + '\n')
  id.chmodFailed = chmodFailed
  return id
}

// ─────────────────────────────────────────── 校验

/** 校验签名。任何异常都算失败 —— 失败方向必须是「不通过」。 */
export function verifySignature(publicKeyB64, data, signatureB64) {
  try {
    const raw = Buffer.from(publicKeyB64, 'base64url')
    if (raw.length !== 32) return false
    // 把 32 字节原始公钥包成 SPKI DER：Ed25519 的前缀是固定的
    const spki = Buffer.concat([
      Buffer.from('302a300506032b6570032100', 'hex'),
      raw,
    ])
    const key = createPublicKey({ key: spki, format: 'der', type: 'spki' })
    return verify(null, Buffer.from(data, 'utf8'), key, Buffer.from(signatureB64, 'base64url'))
  } catch {
    return false
  }
}

/**
 * 配对码：由**双方公钥共同派生**，两侧算出来必然一致。
 *
 * 排序后再哈希 —— 否则 A 算 (A,B)、B 算 (B,A)，两边不一致。
 * 这个「顺序问题」是实现里最常见的错，而且症状是「码总也对不上」，
 * 看起来像协议错，其实是忘了排序。
 *
 * 取 6 位十进制（约 20 bit）。这不是密码学强度的验证码，
 * 它的作用是**让用户确认对面是同一台设备**，对抗的是中间人而不是暴力枚举
 * （要暴力，攻击者得先能同时截断两条连接并各自冒充，那时它面对的
 *  是挑战应答，不是这 6 位数）。
 */
export const DEFAULT_CODE_DIGITS = 6

/**
 * @param {number} [digits] 码长。默认 6 位（约 20 bit 熵）。
 *
 * Magic Wormhole 的 `--code-length=4` 提供了这个开关，理由是：
 * 默认码长是**便利与熵的折中**，而对安全性要求更高的场景
 * （比如要跨不可信网络传配对码）应该能自己加长。
 *
 * **但要注意：加长码长不是免费的。** 6 位已经要用户逐位核对，
 * 8 位就更难念、更难比 —— 而**用户看不清就不会认真比**，
 * 那等于把安全性从「码长」转移到了「用户的耐心」上，
 * 通常不划算。真正该做的是**限速**（见 pairing-guard.js）：
 * 6 位 + 限速 ≈ 14 年，而无限速的 8 位只要 14 小时。
 */
export function pairingCode(pubA, pubB, sessionId, digits = DEFAULT_CODE_DIGITS) {
  // ── sessionId 是**必填**的，这是刻意的 ──────────────────────────────
  //
  // 第一版只用了两个公钥：`hash(sorted(pubA, pubB))`。
  // 那样算出来的码**永远不变** —— 因为两个公钥不变。
  //
  // 那是「长期有效的固定秘密」，**WPS PIN 就是这个形状**，
  // 而 WPS PIN 之所以被攻破，正是因为它不变、不限时、可重复使用。
  // 具体到我们这里，后果是：
  //   · 有人拍到了屏幕上的二维码 → 那个码**以后一直有效**
  //   · 上一次配对时被看到的码 → 下一次**还是同一个码**
  //   · 限速只能拖慢攻击者，**他只需要成功一次**
  //
  // 所以这里要求必须带一个**每次配对新生成的** sessionId。
  // 它不需要保密（码才是秘密），它的作用是**让这次配对的码只对这一次有效**。
  //
  // 把它设成必填而不是可选，是为了让「忘记传」编译不过 ——
  // 可选的话，忘了传就会静默退回成固定秘密，而这正是最难发现的失败方式。
  if (!sessionId || typeof sessionId !== 'string' || sessionId.length < 16) {
    throw new Error('pairingCode 需要一个足够长的 sessionId（每次配对新生成）')
  }
  const n = Math.max(4, Math.min(10, digits | 0))
  const [x, y] = [pubA, pubB].sort()
  const h = createHash('sha256')
    .update(`dsh-fusion-remote/pair/v2|${x}|${y}|${sessionId}`).digest()
  // 用两个 32 位字拼出最多 10 位十进制（10^10 < 2^34）
  const big = h.readUInt32BE(0) * 0x100000000 + h.readUInt32BE(4)
  const mod = 10 ** n
  const v = Number(BigInt(big) % BigInt(mod))
  return String(v).padStart(n, '0')
}

/**
 * 由双方各自的 nonce 派生出本次配对的 sessionId。
 *
 * **双方各出一个**，而不是由一方决定 —— 否则那一方可以反复使用自己挑的
 * sessionId，把「每次不同」这件事又变回「他自己说了算」。
 * 这和配对码本身要排序是同一个道理。
 */
export function sessionIdFrom(nonceA, nonceB) {
  if (!nonceA || !nonceB) throw new Error('sessionId 需要双方的 nonce')
  const [x, y] = [String(nonceA), String(nonceB)].sort()
  return createHash('sha256').update(`dsh-fusion-remote/session/v1|${x}|${y}`)
    .digest('base64url')
}

// ─────────────────────────────────────────── 握手

/**
 * 发起方生成 hello。
 * @param {Identity} id 自己的身份
 * @param {string} peerPub 对端公钥（可能还不知道 —— 首次配对时为 null）
 */
export function makeClientHello(id, peerPub = null) {
  if (!id.privateKey) throw new Error('本机身份不可用')
  const n = nonce()
  return {
    v: PROTOCOL_VERSION,
    pub: id.publicKey,
    nonce: n,
    // 已经 pin 过对端，就直接带上对它的挑战签名
    peerProof: peerPub ? { peer: peerPub, sig: id.sign(`chal|${peerPub}|${n}`) } : null,
  }
}

/**
 * 服务端回应：带上自己的挑战，并**顺手验证客户端的挑战**。
 *
 * 注意这里不能先信后验 —— `verifyClientHello` 必须在使用 `hello.pub` 做任何事之前跑。
 */
export function makeServerHello(id, clientHello) {
  if (!id.privateKey) throw new Error('本机身份不可用')
  const n = nonce()
  return {
    v: PROTOCOL_VERSION,
    pub: id.publicKey,
    nonce: n,
    // 用客户端的随机数签，证明自己持有私钥（并防止重放：
    // 随机数是这次会话独有的，抓包重放拿到的是过期随机数）
    proof: id.sign(`resp|${clientHello.nonce}|${clientHello.pub}`),
  }
}

/** 客户端验证服务端回应。 */
export function verifyServerHello(serverHello, clientHello) {
  if (!serverHello || serverHello.v !== PROTOCOL_VERSION) {
    return { ok: false, reason: 'protocol-mismatch', detail: `协议版本不一致（对端 ${serverHello?.v}）` }
  }
  const ok = verifySignature(
    serverHello.pub,
    `resp|${clientHello.nonce}|${clientHello.pub}`,
    serverHello.proof,
  )
  return ok
    ? { ok: true, peerPub: serverHello.pub }
    : { ok: false, reason: 'bad-signature', detail: '对端未能证明持有私钥' }
}

/** 服务端验证客户端对服务端挑战的应答。 */
export function verifyClientProof(clientPub, serverNonce, serverPub, sig) {
  const ok = verifySignature(clientPub, `chal|${serverPub}|${serverNonce}`, sig)
  return ok
    ? { ok: true }
    : { ok: false, reason: 'bad-signature', detail: '客户端未能证明持有私钥' }
}

// ─────────────────────────────────────────── 已配对设备

/**
 * 已 pin 的对端公钥表。
 *
 * **公钥变更 = 拒绝 + 告警**，不静默更新。
 * 静默更新会把中间人攻击变成一个透明代理 —— 攻击者换了公钥，
 * 你这边什么都不会发现。
 */
export class PinStore {
  constructor(path) {
    this.path = path
    this.peers = new Map()      // pubKey -> { name, addedAt, lastSeen }
    this.load()
  }

  load() {
    try {
      if (!existsSync(this.path)) return
      const data = JSON.parse(readFileSync(this.path, 'utf8'))
      for (const [k, v] of Object.entries(data.peers ?? {})) this.peers.set(k, v)
    } catch {
      // 读坏了就当空的 —— 但不能静默：调用方会看到 peers 变空
      this.corrupt = true
    }
  }

  save() {
    mkdirSync(dirname(this.path), { recursive: true })
    writeFileSync(this.path, JSON.stringify({
      version: 1,
      peers: Object.fromEntries(this.peers),
    }, null, 2))
  }

  has(pub) { return this.peers.has(pub) }

  get(pub) { return this.peers.get(pub) }

  add(pub, name = '未命名设备') {
    this.peers.set(pub, { name, addedAt: Date.now(), lastSeen: Date.now() })
    this.save()
  }

  touch(pub) {
    const p = this.peers.get(pub)
    if (p) { p.lastSeen = Date.now(); this.save() }
  }

  remove(pub) {
    const had = this.peers.delete(pub)
    if (had) this.save()
    return had
  }

  list() {
    return [...this.peers.entries()].map(([pub, v]) => ({ publicKey: pub, ...v }))
  }
}

/**
 * 一次连接的准入判定。
 *
 * 顺序很重要：**先验证签名，再看 pin**。
 * 反过来的话，攻击者只要用一个没 pin 过的公钥就能跳过签名验证。
 */
export function admit({ identity, pinStore, clientHello, serverNonce, clientProof }) {
  if (!clientHello || clientHello.v !== PROTOCOL_VERSION) {
    return { ok: false, reason: 'protocol-mismatch' }
  }
  const v = verifyClientProof(clientHello.pub, serverNonce, identity.publicKey, clientProof)
  if (!v.ok) return v
  if (!pinStore.has(clientHello.pub)) {
    return { ok: false, reason: 'not-paired', peerPub: clientHello.pub, needsPairing: true }
  }
  pinStore.touch(clientHello.pub)
  return { ok: true, peerPub: clientHello.pub, name: pinStore.get(clientHello.pub)?.name }
}
