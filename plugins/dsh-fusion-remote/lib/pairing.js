/**
 * 配对：两种方式，走同一份载荷。
 *
 * ── 两种方式的安全强度**不同**，这一点必须说清楚 ──────────────────────
 *
 * **方式 A · 6 位数字码（短认证串 / SAS）**
 *   两边各自显示一个由**双方公钥共同派生**的 6 位数，用户肉眼看是否一致。
 *   一致 → 说明中间没有人替换公钥。
 *   强度取决于**用户真的去比对**。跳过比对就等于没有这一层
 *   —— 所以这里不提供「跳过」选项。
 *
 * **方式 B · 二维码**
 *   一方显示二维码（内容含它的公钥与地址），另一方用相机扫。
 *   二维码本身就是一条**带外信道**：它不经过可能被中间人的网络，
 *   所以扫到的那一刻，公钥就已经被可信地传递了。
 *   **因此二维码比数字码强** —— 数字码的安全性依赖人眼比对，
 *   二维码不依赖人（前提是屏幕没被替换）。
 *
 * 两者都在这里实现，但 UI 上要如实标注差别，而不是并排摆着让人以为一样。
 *
 * ── 二维码适合哪个方向 ────────────────────────────────────────────────
 * 屏幕大的一侧显示、有相机的一侧扫。
 * 「手机连电脑」时电脑显示（屏幕大、好扫），手机扫 —— 这是最顺的。
 * 反过来（电脑连手机）电脑没相机，所以那一侧走数字码。
 */

import { pairingCode } from './auth.js'

export const PAIR_SCHEME = 'dshfusion'
export const PAIR_VERSION = 1

/** 二维码里塞得下的上限。超过就说明载荷有问题，不要硬编。 */
export const MAX_PAYLOAD_CHARS = 512

/**
 * 造配对载荷。
 *
 * `code` 由 `finalizePairing` 填 —— 它需要**双方**公钥才能算，
 * 而显示二维码时还不知道对方是谁（正等着对方来扫）。
 */
export function makePairingPayload({ publicKey, address = '', port, name = '' }) {
  return {
    v: PAIR_VERSION,
    pub: publicKey,
    addr: String(address ?? '').slice(0, 64),
    port: Number(port),
    name: String(name ?? '').slice(0, 64),
  }
}

/**
 * 编码成二维码内容。
 *
 * 用 `dshfusion://pair/<base64url>` 而不是裸 JSON：
 *   · 任何扫码 App 都能看出这是「一个 DSH 配对码」而不是乱码
 *   · base64url 没有 `+` `/` `=`，不会在二维码里被误读
 *   · 用户可以复制粘贴（跨设备手动传也走得通）
 */
export function encodePairingText(payload) {
  const json = JSON.stringify(payload)
  const b64 = Buffer.from(json, 'utf8').toString('base64url')
  const text = `${PAIR_SCHEME}://pair/${b64}`
  if (text.length > MAX_PAYLOAD_CHARS) {
    throw new Error(`配对码太长（${text.length} > ${MAX_PAYLOAD_CHARS}），设备名或地址字段需要收短`)
  }
  return text
}

/**
 * 解析二维码内容。
 *
 * 对输入要严：这是**相机扫进来的**，内容完全由对方控制。
 * 校验不过返回 null，不做「尽力解析」。
 */
export function decodePairingText(text) {
  if (typeof text !== 'string') return null
  const s = text.trim()
  if (s.length > MAX_PAYLOAD_CHARS) return null
  const m = /^dshfusion:\/\/pair\/([A-Za-z0-9_-]+)$/.exec(s)
  if (!m) return null
  let o
  try { o = JSON.parse(Buffer.from(m[1], 'base64url').toString('utf8')) } catch { return null }
  if (!o || o.v !== PAIR_VERSION) return null
  if (typeof o.pub !== 'string' || !/^[A-Za-z0-9_-]{40,64}$/.test(o.pub)) return null
  const port = Number(o.port)
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null
  return {
    v: PAIR_VERSION,
    publicKey: o.pub,
    address: typeof o.addr === 'string' ? o.addr.slice(0, 64) : '',
    port,
    name: typeof o.name === 'string' ? o.name.slice(0, 64) : '',
  }
}

/**
 * 配对收尾：两边都拿到对方公钥之后调用。
 *
 * @returns {{code: string, digest: string}}
 *   `code` 是给用户比对的 6 位数；`digest` 是完整摘要（用于日志与去重，不进 UI）。
 */
export function finalizePairing(myPublicKey, peerPublicKey) {
  const code = pairingCode(myPublicKey, peerPublicKey)
  const digest = Buffer.from(`${myPublicKey}|${peerPublicKey}`)
    .toString('base64url').slice(0, 16)
  return { code, digest }
}

/**
 * 配对请求的状态机。
 *
 * 单独抽出来是因为它有**明确的阶段**，而每个阶段的准入条件不同：
 * 把「已扫到二维码」当成「已配对」是这里最容易犯的错 ——
 * 扫到只说明拿到了公钥，还没确认对方真的持有对应的私钥。
 */
export const PairState = {
  IDLE: 'idle',                 // 没在配对
  SHOWING: 'showing',           // 显示二维码/数字码，等对方
  PEER_SEEN: 'peer-seen',       // 拿到对方公钥，等用户确认
  CONFIRMED: 'confirmed',       // 用户已确认，正在建立通道
  DONE: 'done',
  FAILED: 'failed',
}

export function createPairingSession({ myPublicKey, method }) {
  if (!['code', 'qr'].includes(method)) throw new Error(`未知的配对方式：${method}`)
  let state = PairState.IDLE
  let peer = null
  let code = null

  const guard = (want) => {
    if (state !== want) throw new Error(`配对状态不对：现在 ${state}，需要 ${want}`)
  }

  return {
    get state() { return state },
    get method() { return method },
    get peer() { return peer },
    get code() { return code },

    start() { guard(PairState.IDLE); state = PairState.SHOWING; return this },

    /** 收到对方的公钥（扫码得到，或对端握手时带来）。 */
    acceptPeer(peerPublicKey, info = {}) {
      // **公钥变更的检查必须排在状态守卫之前。**
      //
      // 这两类错误的性质完全不同：
      //   · 「状态不对」= 调用方的编程错误，重试或改代码就能过
      //   · 「公钥变了」= **可能有人在中间插了一手**，是需要用户知道的安全事件
      // 守卫排在前面时，安全事件会被报成编程错误 —— 既有误导，
      // 又可能被实现者当成「顺序问题」随手放宽守卫而丢掉检查。
      if (peer && peer.publicKey !== peerPublicKey) {
        state = PairState.FAILED
        throw new Error('配对过程中对端公钥发生变化，已中止')
      }
      guard(PairState.SHOWING)
      if (!peerPublicKey || peerPublicKey === myPublicKey) {
        // 连自己是最常见的误操作（同机双开），要明确拒掉而不是静默
        throw new Error('对端公钥无效或与本机相同')
      }
      peer = { publicKey: peerPublicKey, ...info }
      const f = finalizePairing(myPublicKey, peerPublicKey)
      code = f.code
      state = PairState.PEER_SEEN
      return { code, digest: f.digest }
    },

    /**
     * 用户确认。
     *
     * `comparedCode` 是数字码方式下用户在对面屏幕上看到的值；
     * 二维码方式下可以不传（二维码本身已经完成校验）。
     * **数字码方式下必须传，且必须相等** —— 这是那一层唯一的安全性来源。
     */
    confirm(comparedCode = null) {
      guard(PairState.PEER_SEEN)
      if (method === 'code') {
        if (comparedCode === null || comparedCode === undefined) {
          throw new Error('数字码配对必须核对屏幕上的码')
        }
        if (String(comparedCode) !== code) {
          state = PairState.FAILED
          throw new Error('配对码不一致 —— 可能存在中间人，已中止')
        }
      }
      state = PairState.CONFIRMED
      return { peerPublicKey: peer.publicKey, code }
    },

    done() { guard(PairState.CONFIRMED); state = PairState.DONE },
    fail(reason) { state = PairState.FAILED; this.reason = reason },
    cancel() { if (state !== PairState.DONE) state = PairState.IDLE; peer = null; code = null },
  }
}
