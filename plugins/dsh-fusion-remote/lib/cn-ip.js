/**
 * 中国 IP 段判定。
 *
 * ── 为什么内置一份表，而不是查在线 API ────────────────────────────────
 * 因为「查一次 IP 归属」这件事**本身就要发一个网络请求** ——
 * 而那些免费的 IP 归属 API 大多在境外。为了判断「有没有出境」而先出一次境，
 * 这个自相矛盾不是理论上的：真实的实现里非常常见。
 *
 * 所以：内置 APNIC 的 CN 委派数据（约 8800 条 CIDR，134 KB），
 * 内存里转成有序的数值区间，查一次是 O(log n)，零网络、零延迟。
 *
 * ── 数据会过时，所以留了刷新路径 ──────────────────────────────────────
 * APNIC 的委派数据每周更新。表里的数据带生成日期，超过 180 天会**警告**
 * （不阻止 —— 阻止会让用户在某天突然连不上，而那是更糟的失败模式）。
 */

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const DATA = join(HERE, '..', 'data', 'cn-ipv4.txt')

/** 超过这个天数就警告数据可能过时。 */
export const STALE_DAYS = 180

/**
 * 私有 / 保留 / 虚拟内网地址段。
 *
 * **这些段必须跳过跨境检查** —— 它们物理上不可能跨境：
 *   · 10/8、172.16/12、192.168/16 —— RFC1918 私有段
 *   · 100.64/10                    —— CGNAT，**EasyTier / Tailscale 用的就是它**
 *   · 169.254/16                   —— 链路本地
 *   · fc00::/7、fe80::/10          —— IPv6 对应段
 *
 * 如果对它们也查 IP 归属，会把正常的内网连接判成境外 ——
 * 这是最容易犯、也最难查的一种误杀。
 */
const PRIVATE_V4 = [
  ['10.0.0.0', 8], ['172.16.0.0', 12], ['192.168.0.0', 16],
  ['100.64.0.0', 10], ['169.254.0.0', 16], ['127.0.0.0', 8],
  ['0.0.0.0', 8], ['192.0.0.0', 24], ['192.0.2.0', 24], ['198.18.0.0', 15],
  ['198.51.100.0', 24], ['203.0.113.0', 24], ['240.0.0.0', 4],
]

const V4_RE = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/

/** 点分十进制 → 32 位无符号整数；不是 IPv4 返回 null。 */
export function ipv4ToInt(ip) {
  const m = V4_RE.exec(String(ip ?? '').trim())
  if (!m) return null
  let n = 0
  for (let i = 1; i <= 4; i++) {
    const b = Number(m[i])
    if (b > 255) return null
    n = n * 256 + b
  }
  return n >>> 0
}

function prefixToRange(base, bits) {
  const start = ipv4ToInt(base)
  if (start === null) return null
  const size = 2 ** (32 - bits)
  return [start, start + size - 1]
}

const PRIVATE_RANGES = PRIVATE_V4.map(([b, n]) => prefixToRange(b, n)).filter(Boolean)

/**
 * 是不是私有/虚拟地址。**私有地址一律不做跨境判定** —— 它们跨境不了。
 * IPv6 的私有段（fc00::/7、fe80::/10）直接按前缀字符串判。
 */
export function isPrivateAddress(ip) {
  const s = String(ip ?? '').trim()
  const low = s.toLowerCase()
  if (low === 'localhost' || low === '::1') return true
  if (low.startsWith('fc') || low.startsWith('fd') || low.startsWith('fe80')) return true
  const n = ipv4ToInt(s)
  if (n === null) return false
  for (const [a, b] of PRIVATE_RANGES) if (n >= a && n <= b) return true
  return false
}

/** 把 CIDR 列表解析成有序区间，供二分查找。 */
export function parseCidrList(text) {
  const ranges = []
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const [base, bitsStr] = line.split('/')
    const bits = Number(bitsStr)
    if (!Number.isInteger(bits) || bits < 0 || bits > 32) continue
    const r = prefixToRange(base, bits)
    if (r) ranges.push(r)
  }
  ranges.sort((a, b) => a[0] - b[0])
  // 合并相邻/重叠，减少查找深度
  const merged = []
  for (const r of ranges) {
    const last = merged[merged.length - 1]
    if (last && r[0] <= last[1] + 1) last[1] = Math.max(last[1], r[1])
    else merged.push([r[0], r[1]])
  }
  return merged
}

export function extractDate(text) {
  const m = /(\d{4})-(\d{2})-(\d{2})/.exec(text)
  return m ? m[0] : null
}

export class ChinaIpTable {
  constructor(ranges = [], generatedAt = null) {
    this.ranges = ranges
    this.generatedAt = generatedAt
  }

  static load(path = DATA) {
    const text = readFileSync(path, 'utf8')
    return new ChinaIpTable(parseCidrList(text), extractDate(text))
  }

  /** 数据是不是可能过时了。**只警告，不阻止** —— 见文件头。 */
  staleness(now = Date.now()) {
    if (!this.generatedAt) return { days: null, stale: false, known: false }
    const t = Date.parse(this.generatedAt)
    if (Number.isNaN(t)) return { days: null, stale: false, known: false }
    const days = Math.floor((now - t) / 86400000)
    return { days, stale: days > STALE_DAYS, known: true }
  }

  /** 二分查找：IP 是否落在表内。 */
  contains(ip) {
    const n = ipv4ToInt(ip)
    if (n === null) return false
    let lo = 0, hi = this.ranges.length - 1
    while (lo <= hi) {
      const mid = (lo + hi) >> 1
      const [a, b] = this.ranges[mid]
      if (n < a) hi = mid - 1
      else if (n > b) lo = mid + 1
      else return true
    }
    return false
  }
}

/** 判定结果的语义化原因 —— 让上层的日志和 UI 能说人话。 */
export const Reason = {
  PRIVATE: 'private-address',
  IN_CHINA: 'in-china',
  NOT_IN_CHINA: 'not-in-china',
  UNPARSEABLE: 'unparseable-address',
}

/**
 * 判定一个地址能否用于远程连接。
 *
 * @returns {{allowed: boolean, reason: string, detail: string}}
 */
export function judgePeer(table, ip) {
  const s = String(ip ?? '').trim()
  if (ipv4ToInt(s) === null && !s.includes(':')) {
    return { allowed: false, reason: Reason.UNPARSEABLE, detail: `看不懂的地址：${s}` }
  }
  if (isPrivateAddress(s)) {
    // 内网地址不可能跨境，直接放行 —— 这是最容易误杀的一类
    return { allowed: true, reason: Reason.PRIVATE, detail: '内网地址，不涉及跨境' }
  }
  if (table.contains(s)) {
    return { allowed: true, reason: Reason.IN_CHINA, detail: '境内公网地址' }
  }
  return { allowed: false, reason: Reason.NOT_IN_CHINA, detail: `对端地址不在中国境内：${s}` }
}
