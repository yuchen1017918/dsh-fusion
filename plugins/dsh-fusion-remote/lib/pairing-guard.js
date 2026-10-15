/**
 * 配对限速与失败计数。
 *
 * ── 为什么这块是**必需**的，不是「加固」 ──────────────────────────────
 * 短码的熵很低。6 位十进制只有约 20 bit，也就是 100 万种可能 ——
 * 而**在没有限速的情况下，这个空间根本不算空间**：
 *
 *     每秒 1000 次（一个脚本）      → 期望 **8 分钟**猜中
 *     每分钟 10 次（人工慢慢试）    → 期望 35 天
 *
 * 也就是说：**短码的安全性 100% 来自限速。**
 * 没有限速的 6 位码不是「弱密码」，是**等于没有密码**。
 *
 * 这条不是我推出来的 —— Magic Wormhole 把它写在自己的攻击模型文档里
 * （docs/attacks.rst），并且诚实地说：他们希望用户看到大量失败
 * 才会意识到有人在猜。我们这里把「大量失败」做成**主动告警**，
 * 而不是指望用户去翻日志。
 *
 * ── 三层 ──────────────────────────────────────────────────────────────
 *   1. 单来源退避：某个地址连续失败 → 指数拉长等待
 *   2. 全局计数：所有来源的失败汇总 —— 用来识别分布式猜解
 *   3. 持久化：重启不清零。攻击者不能靠「让你重启」把计数抹掉
 *
 * ── 一个刻意的设计：失败计数**不会**因为一次成功就清零 ──────────────
 * 只清「单来源」那一层。全局计数保留 —— 因为「先猜中一次再继续猜」
 * 正是攻击者会做的事，而把全局计数一起清零等于给他发奖励。
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

/** 窗口内的失败次数上限（单来源）。超过就开始退避。 */
export const FREE_ATTEMPTS = 3

/** 退避基线。第 3 次失败后等 2 秒，第 4 次 4 秒…… 指数增长。 */
export const BASE_BACKOFF_MS = 2000

/** 单次退避上限。不设上限会让一次误操作把设备锁死半小时。 */
export const MAX_BACKOFF_MS = 15 * 60 * 1000

/** 全局失败到这个数，就认为「有人在猜」，开始主动告警。 */
export const ALERT_THRESHOLD = 20

/** 全局统计的滑动窗口。超窗口的失败不再计入告警。 */
export const WINDOW_MS = 24 * 3600 * 1000

/** 连续失败记录的上限，防止有人用海量来源把内存打满。 */
export const MAX_TRACKED_SOURCES = 512

export class PairingGuard {
  /**
   * @param {string} path 持久化路径（null = 只在内存里，测试用）
   */
  constructor(path = null) {
    this.path = path
    this.sources = new Map()      // 来源 → { fails, until }
    this.global = []              // { at } 时间戳列表（滑动窗口）
    this.load()
  }

  load() {
    if (!this.path || !existsSync(this.path)) return
    try {
      const d = JSON.parse(readFileSync(this.path, 'utf8'))
      for (const [k, v] of Object.entries(d.sources ?? {})) {
        if (typeof v?.fails !== 'number') continue
        // **`lastAt` 必须一起恢复。**
        //
        // 漏掉它的后果不是「少一个字段」，而是：紧接着的 prune() 会算出
        // `now - 0 > 24小时`，把**刚加载进来的每一条记录立刻删掉** ——
        // 于是持久化整条是死的，而从外面看不出任何异常
        // （文件有、内容对、就是重载后为空）。
        this.sources.set(k, {
          fails: v.fails,
          until: v.until ?? 0,
          lastAt: typeof v.lastAt === 'number' ? v.lastAt : Date.now(),
        })
      }
      this.global = Array.isArray(d.global) ? d.global.filter((x) => typeof x?.at === 'number') : []
    } catch {
      // 读坏了就重置，但**要留下痕迹** —— 静默重置等于给攻击者一条捷径
      this.corrupt = true
    }
    this.prune()
  }

  save() {
    if (!this.path) return
    try {
      mkdirSync(dirname(this.path), { recursive: true })
      writeFileSync(this.path, JSON.stringify({
        version: 1,
        sources: Object.fromEntries(this.sources),
        global: this.global.slice(-1000),
      }))
    } catch { /* 写不进去不影响限速本身，只是丢了持久化 */ }
  }

  prune(now = Date.now()) {
    this.global = this.global.filter((x) => now - x.at < WINDOW_MS)
    for (const [k, v] of this.sources) {
      // 很久没动的来源直接忘掉，避免 Map 无限增长。
      //
      // 注意这里的兜底：**拿不准就不删**。
      // 原来的写法是 `v.lastAt ?? 0` —— 缺字段时按「1970 年」算，
      // 于是任何一次字段改名/漏存都会静默清空整张表。
      // 保留一条多余的记录代价很小；误删一条限速记录代价是安全上的。
      if (typeof v.lastAt !== 'number') { v.lastAt = now; continue }
      if (now - v.lastAt > WINDOW_MS) this.sources.delete(k)
    }
    // 还是太多就按最近活动排序保留最新的那批
    if (this.sources.size > MAX_TRACKED_SOURCES) {
      const sorted = [...this.sources.entries()].sort((a, b) => (b[1].lastAt ?? 0) - (a[1].lastAt ?? 0))
      this.sources = new Map(sorted.slice(0, MAX_TRACKED_SOURCES))
    }
  }

  /**
   * 现在能不能试？不能的话还要等多久。
   * @returns {{allowed: boolean, retryAfterMs: number, reason?: string}}
   */
  check(source, now = Date.now()) {
    this.prune(now)
    const s = this.sources.get(source)
    if (s && s.until > now) {
      return {
        allowed: false,
        retryAfterMs: s.until - now,
        reason: `来自 ${source} 的配对尝试过于频繁`,
      }
    }
    return { allowed: true, retryAfterMs: 0 }
  }

  /** 记一次失败。返回是否需要告警。 */
  fail(source, now = Date.now()) {
    const s = this.sources.get(source) ?? { fails: 0, until: 0 }
    s.fails += 1
    s.lastAt = now
    // 前 FREE_ATTEMPTS 次不罚 —— 正常用户也会敲错码
    const over = s.fails - FREE_ATTEMPTS
    if (over > 0) {
      const backoff = Math.min(BASE_BACKOFF_MS * 2 ** (over - 1), MAX_BACKOFF_MS)
      s.until = now + backoff
    }
    this.sources.set(source, s)
    this.global.push({ at: now })
    this.save()
    return {
      sourceFails: s.fails,
      backoffMs: Math.max(0, s.until - now),
      globalFails: this.global.length,
      alert: this.global.length >= ALERT_THRESHOLD,
    }
  }

  /**
   * 记一次成功。
   *
   * **只清单来源，不清全局。** 「先猜中一次再继续猜」正是攻击者会做的事，
   * 把全局计数一起清零等于给他发奖励。
   */
  succeed(source, now = Date.now()) {
    if (this.sources.delete(source)) this.save()
    return { globalFails: this.global.length, stillAlerting: this.global.length >= ALERT_THRESHOLD }
  }

  /** 当前状态，给 UI 显示。 */
  status(now = Date.now()) {
    this.prune(now)
    return {
      globalFails: this.global.length,
      alerting: this.global.length >= ALERT_THRESHOLD,
      blockedSources: [...this.sources.entries()].filter(([, v]) => v.until > now).length,
      trackedSources: this.sources.size,
      corruptState: this.corrupt === true,
    }
  }

  /** 用户手动清除告警。**要显式动作** —— 不能自动过期掉。 */
  clear() {
    this.sources.clear()
    this.global = []
    this.corrupt = false
    this.save()
  }
}

/**
 * 按**实际的退避曲线**估算抗暴力时间。
 *
 * ── 为什么需要这个，而不是「用平坦速率估一下」──────────────────────────
 * 第一版我用「10 次/分钟」这种平坦速率去估，算出 6 位码 35 天 ——
 * **35 天不叫安全**，于是看起来像配置不够。
 *
 * 但那个估算本身是错的：真正的限速不是「每分钟 10 次」，
 * 而是**指数退避**（第 4 次等 2 秒、第 5 次 4 秒……很快撞到 15 分钟的上限）。
 * 按真实曲线算，攻破 6 位码需要 **十几年**。
 *
 * 所以：**用平坦速率估算会得出明显偏保守（甚至吓人）的结论**，
 * 而拿那个结论去调参数会把人带偏（比如误以为必须上 8 位码）。
 * 这个函数按真实曲线算，参数改了也能立刻看出实际效果。
 *
 * @param {number} digits 码长（十进制）
 * @param {object} [opt] 覆盖默认退避参数
 */
export function effectiveResistance(digits, opt = {}) {
  const free = opt.freeAttempts ?? FREE_ATTEMPTS
  const base = opt.baseBackoffMs ?? BASE_BACKOFF_MS
  const cap = opt.maxBackoffMs ?? MAX_BACKOFF_MS
  const target = 10 ** digits / 2          // 期望次数
  let elapsed = 0                          // 秒
  let n = 0
  // 逐次累加太慢（50 万次），所以分两段：
  // 前半段逐次算（还没撞到上限），后半段用上限乘剩余次数
  for (;;) {
    n++
    if (n > target) break
    if (n <= free) continue
    const over = n - free
    const wait = Math.min((base * 2 ** (over - 1)) / 1000, cap / 1000)
    if (wait >= cap / 1000) {
      // 从这一次起都是上限，剩下的直接乘
      elapsed += (target - n + 1) * (cap / 1000)
      break
    }
    elapsed += wait
  }
  return {
    space: 10 ** digits,
    bits: Math.log2(10 ** digits),
    seconds: elapsed,
    human: humanDuration(elapsed),
    capReachedAfter: (() => {
      // 第几次之后开始撞上限
      for (let k = 1; k < 200; k++) {
        if ((base * 2 ** k) / 1000 >= cap / 1000) return free + k + 1
      }
      return null
    })(),
  }
}

function humanDuration(seconds) {
  return seconds < 60 ? `${seconds.toFixed(0)} 秒`
    : seconds < 3600 ? `${(seconds / 60).toFixed(0)} 分钟`
    : seconds < 86400 ? `${(seconds / 3600).toFixed(1)} 小时`
    : seconds < 86400 * 365 ? `${(seconds / 86400).toFixed(0)} 天`
    : `${(seconds / 86400 / 365).toFixed(0)} 年`
}

/** 使用方式见上方说明。 */
export function bruteForceResistance(digits, attemptsPerSecond) {
  const space = 10 ** digits
  const seconds = space / 2 / Math.max(attemptsPerSecond, 1e-9)
  return {
    space,
    bits: Math.log2(space),
    seconds,
    human: seconds < 60 ? `${seconds.toFixed(0)} 秒`
      : seconds < 3600 ? `${(seconds / 60).toFixed(0)} 分钟`
      : seconds < 86400 ? `${(seconds / 3600).toFixed(1)} 小时`
      : seconds < 86400 * 365 ? `${(seconds / 86400).toFixed(0)} 天`
      : `${(seconds / 86400 / 365).toFixed(0)} 年`,
  }
}
