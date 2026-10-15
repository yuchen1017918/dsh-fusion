/**
 * 设备 ID：公钥的可读、可核对、**能检出打字错误**的形式。
 *
 * ── 抄的是 Syncthing 的思路 ────────────────────────────────────────────
 * Syncthing 那套设计的三个点都值得学：
 *
 *   1. **设备 ID 是公钥的直接属性**，不是另发一个编号 ——
 *      这样「认 ID」和「认公钥」是同一件事，不会出现两者不一致的窗口。
 *   2. **Luhn 校验位** —— 手动输错一位会被立刻发现，而不是等到连接失败。
 *      没有校验位时，输错一位的表现是「连不上，原因不明」，
 *      用户会去怀疑网络、防火墙、配对流程，唯独不会怀疑自己少打了一个字符。
 *   3. **分组显示**（每 7 个字符一个短横）—— 长串不分组时人根本没法逐字核对。
 *
 * ── 与 Syncthing 不同的地方 ────────────────────────────────────────────
 * 它用 SHA-256 再 base32 出 52 字符。我们用同样的长度与同样的分组，
 * 但**明确声明这是展示形式，不是身份** ——
 * 真正参与密码学的是原始公钥。ID 碰撞（前 260 bit 相同）在现实中不会发生，
 * 但代码里不依赖这一点：**校验永远用原始公钥，不用 ID。**
 */

import { createHash } from 'node:crypto'

/** base32 字母表。去掉 0/1/8/9 之类容易混的字符是常见的做法，
 *  但 Syncthing 用的是完整 RFC 4648 表，这里跟随它 —— 与校验位配合已经够用。 */
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

/** ID 主体长度（base32 字符数）。52 × 5 = 260 bit。 */
export const ID_BODY_LEN = 52

/** 分组大小。 */
export const GROUP = 7

/** 把字节按 RFC 4648 base32 编码（无填充）。 */
export function base32Encode(buf) {
  let bits = 0, value = 0, out = ''
  for (const byte of buf) {
    value = (value << 8) | byte
    bits += 8
    while (bits >= 5) {
      out += ALPHABET[(value >>> (bits - 5)) & 31]
      bits -= 5
    }
  }
  if (bits > 0) out += ALPHABET[(value << (5 - bits)) & 31]
  return out
}

/**
 * Luhn mod 32 校验位。
 *
 * 这是 Luhn 算法在「字母表大小 32」上的推广（原始的 Luhn 用于十进制）。
 * 它能检出**所有单字符错误**和**绝大多数相邻字符换位错误** ——
 * 这正是手抄长串时最常犯的两类错。
 */
export function luhnMod32(input) {
  let factor = 2
  let sum = 0
  const n = ALPHABET.length
  // 从右往左
  for (let i = input.length - 1; i >= 0; i--) {
    const codePoint = ALPHABET.indexOf(input[i])
    if (codePoint < 0) throw new Error(`非法字符：${input[i]}`)
    let addend = factor * codePoint
    factor = factor === 2 ? 1 : 2
    addend = Math.floor(addend / n) + (addend % n)
    sum += addend
  }
  return ALPHABET[(n - (sum % n)) % n]
}

/** 公钥（base64url）→ 设备 ID 字符串。 */
export function deviceIdFromPublicKey(publicKeyB64) {
  const raw = Buffer.from(String(publicKeyB64 ?? ''), 'base64url')
  if (raw.length === 0) throw new Error('公钥为空')
  const digest = createHash('sha256').update(raw).digest()
  const body = base32Encode(digest).slice(0, ID_BODY_LEN)
  const check = luhnMod32(body)
  return group(body + check)
}

/** 每 7 个字符插一个短横。 */
export function group(s) {
  const parts = []
  for (let i = 0; i < s.length; i += GROUP) parts.push(s.slice(i, i + GROUP))
  return parts.join('-')
}

/** 去掉短横、空格、转小写为大写。用户从各种地方粘贴过来的都能收。 */
export function normalizeDeviceId(text) {
  return String(text ?? '').toUpperCase().replace(/[^A-Z2-7]/g, '')
}

/**
 * 校验一个用户输入的设备 ID。
 *
 * @returns {{ok: boolean, reason?: string, detail?: string}}
 */
export function validateDeviceId(text) {
  const s = normalizeDeviceId(text)
  const expected = ID_BODY_LEN + 1
  if (s.length !== expected) {
    return {
      ok: false,
      reason: s.length < expected ? 'too-short' : 'too-long',
      detail: `长度应为 ${expected} 个字符（当前 ${s.length}）`,
    }
  }
  const body = s.slice(0, ID_BODY_LEN)
  const check = s[ID_BODY_LEN]
  let want
  try { want = luhnMod32(body) } catch (e) { return { ok: false, reason: 'bad-charset', detail: e.message } }
  if (check !== want) {
    // 这一条的价值在于：**打错字会在输入时就被指出**，
    // 而不是等到连接失败时让用户去怀疑网络
    return {
      ok: false,
      reason: 'bad-checksum',
      detail: `校验位不对（应为 ${want}，输入的是 ${check}）—— 可能有一位打错了`,
    }
  }
  return { ok: true }
}

/** 把设备 ID 缩短用于界面展示（前面 7 + 后面 4）。 */
export function shortDeviceId(id) {
  const s = normalizeDeviceId(id)
  if (s.length <= 12) return s
  return `${s.slice(0, 7)}…${s.slice(-4)}`
}
