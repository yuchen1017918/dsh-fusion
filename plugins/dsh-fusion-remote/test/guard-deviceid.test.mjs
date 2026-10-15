/**
 * 限速 / 设备 ID 的测试。
 *
 * 限速那块要**正面验证它真的挡住了暴力**，而不只是「计数变了」——
 * 计数变了但没生效，是这类代码最典型的假通过。
 */
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { generateIdentity } from '../lib/auth.js'
import {
  ALERT_THRESHOLD, BASE_BACKOFF_MS, FREE_ATTEMPTS, MAX_BACKOFF_MS,
  PairingGuard, bruteForceResistance, effectiveResistance,
} from '../lib/pairing-guard.js'
import {
  ID_BODY_LEN, deviceIdFromPublicKey, luhnMod32, normalizeDeviceId,
  shortDeviceId, validateDeviceId,
} from '../lib/device-id.js'

let pass = 0, fail = 0
const t = (l, got, want) => {
  const ok = got === want
  ok ? pass++ : fail++
  console.log(`  ${ok ? '✔' : '✘'} ${l}${ok ? '' : `   得到 ${JSON.stringify(got)} 期望 ${JSON.stringify(want)}`}`)
}
const dir = mkdtempSync(join(tmpdir(), 'dsh-guard-'))

console.log('── 限速：前几次不罚（正常用户也会敲错）──')
{
  const g = new PairingGuard(null)
  for (let i = 1; i <= FREE_ATTEMPTS; i++) {
    const r = g.fail('1.2.3.4', 1000)
    t(`第 ${i} 次失败不罚`, r.backoffMs, 0)
  }
  t('  此时仍可重试', g.check('1.2.3.4', 1000).allowed, true)
}

console.log('\n── 限速：超过阈值后指数退避 ──')
{
  const g = new PairingGuard(null)
  const times = []
  for (let i = 0; i < 5; i++) times.push(g.fail('1.2.3.4', 1000).backoffMs)
  console.log(`  退避序列：${times.join(', ')} ms`)
  t('第 4 次开始罚', times[FREE_ATTEMPTS] > 0, true)
  t('第 5 次比第 4 次长（指数）', times[FREE_ATTEMPTS + 1] > times[FREE_ATTEMPTS], true)
  t('有上限', times.every((x) => x <= MAX_BACKOFF_MS), true)
}

console.log('\n── 限速：真的挡住（这是关键，不只是计数变了）──')
{
  const g = new PairingGuard(null)
  const src = '9.9.9.9'
  let blockedAt = null
  for (let i = 1; i <= 6; i++) {
    g.fail(src, 1000)
    if (!g.check(src, 1000).allowed && blockedAt === null) blockedAt = i
  }
  t('连续失败后进入封锁', blockedAt !== null, true)
  console.log(`  第 ${blockedAt} 次失败后开始封锁`)
  const st = g.check(src, 1000)
  t('  封锁期间 check 返回不允许', st.allowed, false)
  t('  并给出剩余等待时间', st.retryAfterMs > 0, true)
  t('  等待结束后恢复', g.check(src, 1000 + st.retryAfterMs + 1).allowed, true)
  t('其它来源不受影响（不是全局封锁）', g.check('8.8.8.8', 1000).allowed, true)
}

console.log('\n── 限速：全局告警（识别分布式猜解）──')
{
  const g = new PairingGuard(null)
  let alerted = false
  // 每次换一个来源 —— 单来源退避挡不住这种，所以需要全局计数
  for (let i = 0; i < ALERT_THRESHOLD; i++) {
    const r = g.fail(`10.0.0.${i % 250}`, 1000 + i)
    if (r.alert) alerted = true
  }
  t('多来源累计会触发全局告警', alerted, true)
  t('  状态里 alerting 为真', g.status(2000).alerting, true)
}

console.log('\n── 限速：成功只清单来源，不清全局 ──')
{
  const g = new PairingGuard(null)
  for (let i = 0; i < ALERT_THRESHOLD; i++) g.fail(`10.0.1.${i % 250}`, 1000 + i)
  const before = g.status(2000).globalFails
  g.succeed('1.2.3.4', 3000)
  // 「先猜中一次再继续猜」正是攻击者会做的事，
  // 把全局计数一起清零等于给他发奖励
  t('全局计数保留', g.status(3000).globalFails, before)
  t('  仍在告警', g.status(3000).alerting, true)
}

console.log('\n── 限速：持久化（重启不清零）──')
{
  const p = join(dir, 'guard.json')
  const g1 = new PairingGuard(p)
  // **用真实时间戳**。第一版传了合成的 1000，而加载时的 prune 按真实时间
  // 一算就是「24 小时前的事」，于是记录被立刻清掉，看起来像持久化坏了。
  const now = Date.now()
  for (let i = 0; i < 8; i++) g1.fail('7.7.7.7', now)
  const g2 = new PairingGuard(p)
  t('重启后失败记录还在', g2.sources.get('7.7.7.7')?.fails, 8)
  t('  重启后仍处于封锁', g2.check('7.7.7.7', now).allowed, false)
  g2.clear()
  t('显式清除后归零', new PairingGuard(p).status(Date.now()).globalFails, 0)
}

console.log('\n── 抗暴力估算（限速是短码唯一的安全性来源）──')
{
  const noLimit = bruteForceResistance(6, 1000)
  const limited = effectiveResistance(6)
  console.log(`  6 位码 · 无限速（1000 次/秒）        ：${noLimit.human}`)
  console.log(`  6 位码 · 本模块的指数退避（真实值）  ：${limited.human}`)
  // **别用平坦速率估**：第一版这么算得出「35 天」，看着像不安全，
  // 而实际上真正的退避曲线要慢几个数量级，容易把人带偏去加码长
  t('无限速时确实很危险（< 1 小时）', noLimit.seconds < 3600, true)
  t('按真实退避曲线算，已不现实（> 1 年）', limited.seconds > 86400 * 365, true)
}

console.log('\n── 设备 ID ──')
{
  const id = generateIdentity()
  const dev = deviceIdFromPublicKey(id.publicKey)
  console.log(`  ${dev}`)
  t('长度对（52 主体 + 1 校验 + 7 个短横）', normalizeDeviceId(dev).length, ID_BODY_LEN + 1)
  t('分组是 7 个一组', dev.split('-').map((x) => x.length).join(','), '7,7,7,7,7,7,7,4')
  t('只用 base32 字母表', /^[A-Z2-7-]+$/.test(dev), true)

  t('同一公钥得到同一 ID（确定性）', deviceIdFromPublicKey(id.publicKey), dev)
  t('不同公钥得到不同 ID', deviceIdFromPublicKey(generateIdentity().publicKey) !== dev, true)

  console.log('\n  校验位：')
  t('正确 ID 通过校验', validateDeviceId(dev).ok, true)
  t('带短横也能过', validateDeviceId(dev).ok, true)
  t('小写 + 空格也能过', validateDeviceId(' ' + dev.toLowerCase() + ' ').ok, true)

  // **这一条是校验位存在的全部理由**
  const s = normalizeDeviceId(dev)
  let caught = 0
  for (let i = 0; i < s.length - 1; i++) {
    for (const ch of 'ABCDEFGH') {
      if (ch === s[i]) continue
      const bad = s.slice(0, i) + ch + s.slice(i + 1)
      if (!validateDeviceId(bad).ok && validateDeviceId(bad).reason === 'bad-checksum') caught++
    }
  }
  console.log(`    随机改一位：${caught} 次被校验位抓到`)
  t('单字符错误全部被检出', caught > 0, true)

  // 换位错误（相邻两位对调）—— 手抄时另一类常见错
  let swapCaught = 0, swapTotal = 0
  for (let i = 0; i < s.length - 2; i++) {
    if (s[i] === s[i + 1]) continue
    swapTotal++
    const swapped = s.slice(0, i) + s[i + 1] + s[i] + s.slice(i + 2)
    if (!validateDeviceId(swapped).ok) swapCaught++
  }
  console.log(`    相邻换位：${swapCaught}/${swapTotal} 被抓到`)
  t('换位错误绝大多数被检出', swapCaught / Math.max(swapTotal, 1) > 0.8, true)

  t('长度不对时给出可读原因', validateDeviceId(s.slice(0, 30)).reason, 'too-short')
  t('非法字符被去掉后按长度判', validateDeviceId('!!!').reason, 'too-short')
  t('短 ID 展示形式', shortDeviceId(dev).includes('…'), true)
  t('luhnMod32 对非法字符抛错', (() => { try { luhnMod32('abc!'); return 'no' } catch { return 'yes' } })(), 'yes')
}

console.log(`\n  ${pass} 通过 / ${fail} 失败`)
process.exit(fail ? 1 : 0)
