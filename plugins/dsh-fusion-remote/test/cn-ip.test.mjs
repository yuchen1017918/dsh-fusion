// 跨境判定的测试。**这是整个远程功能里最该被测的一块** ——
// 判错的两种后果都很糟：误放行 = 违反了「不能跨境」；
// 误拦截 = 用户正常的内网连接突然连不上，而且他不知道为什么。
import { ChinaIpTable, judgePeer, isPrivateAddress, ipv4ToInt } from '../lib/cn-ip.js'

let pass = 0, fail = 0
const t = (label, got, want) => {
  const ok = got === want
  ok ? pass++ : fail++
  console.log(`  ${ok ? '✔' : '✘'} ${label}${ok ? '' : `   得到 ${JSON.stringify(got)} 期望 ${JSON.stringify(want)}`}`)
}

console.log('── 加载 ──')
const t0 = Date.now()
const table = ChinaIpTable.load()
const loadMs = Date.now() - t0
console.log(`  ${table.ranges.length} 个合并区间 · 加载 ${loadMs}ms · 数据日期 ${table.generatedAt}`)
// 8794 条 CIDR 会把相邻的合并掉，所以**区间数少于 CIDR 数是对的**。
// 第一版这里按 CIDR 的数量写期望，结果测试自己错了 —— 断言要跟着语义走。
t('区间数合理（3000~6000 个合并区间）', table.ranges.length > 3000 && table.ranges.length < 6000, true)
t('数据带生成日期（过时检测的基准）', /^\d{4}-\d{2}-\d{2}$/.test(table.generatedAt ?? ''), true)

console.log('\n── 私有地址：必须跳过跨境检查 ──')
// 这一组是最容易误杀的：EasyTier/Tailscale 用 100.64/10，
// 若把它当公网地址去查归属，会判成境外 → 用户的内网直连被拒
const priv = ['10.0.0.1', '172.16.5.5', '192.168.1.5', '100.64.0.1', '100.127.255.254',
              '127.0.0.1', '169.254.1.1', 'localhost', '::1', 'fe80::1', 'fd00::1', 'fc00::1']
for (const ip of priv) t(`isPrivateAddress(${ip})`, isPrivateAddress(ip), true)
t('judgePeer(192.168.x) 放行', judgePeer(table, '192.168.1.5').allowed, true)
t('judgePeer(100.64.x) 放行（CGNAT）', judgePeer(table, '100.64.0.1').allowed, true)
t('私有地址的原因是 private-address', judgePeer(table, '192.168.1.5').reason, 'private-address')

console.log('\n── 境内公网：放行 ──')
for (const ip of ['223.5.5.5', '114.114.114.114', '39.156.66.10', '180.101.50.242']) {
  t(`judgePeer(${ip}) 放行`, judgePeer(table, ip).allowed, true)
}

console.log('\n── 境外公网：必须拒绝 ──')
for (const ip of ['8.8.8.8', '1.1.1.1', '142.250.72.14', '13.107.42.14']) {
  const j = judgePeer(table, ip)
  t(`judgePeer(${ip}) 拒绝`, j.allowed, false)
  t(`  原因 = not-in-china`, j.reason, 'not-in-china')
}

console.log('\n── 非法输入：拒绝而不是放行 ──')
// 失败方向很重要：看不懂的地址**必须拒绝**。
// 「看不懂就放行」是最危险的一种默认值。
for (const bad of ['', 'not-an-ip', '999.1.1.1', '1.2.3', null, undefined]) {
  t(`judgePeer(${String(bad)}) 拒绝`, judgePeer(table, bad).allowed, false)
}
t('ipv4ToInt(256.1.1.1) = null', ipv4ToInt('256.1.1.1'), null)

console.log('\n── 边界：区间端点 ──')
// 取表里第一个区间的首尾，确认二分查找的边界没写错（这是最容易差一位的地方）
const [a, b] = table.ranges[0]
const toIp = (n) => [n >>> 24, (n >>> 16) & 255, (n >>> 8) & 255, n & 255].join('.')
t(`区间首地址 ${toIp(a)} 命中`, table.contains(toIp(a)), true)
t(`区间末地址 ${toIp(b)} 命中`, table.contains(toIp(b)), true)
t(`末地址 +1 ${toIp(b + 1)} 不命中`, table.contains(toIp(b + 1)), false)

console.log('\n── 过时检测（只警告不阻止）──')
const old = new ChinaIpTable([], '2020-01-01')
t('180 天前的数据标记为过时', old.staleness(Date.parse('2026-01-01')).stale, true)
const fresh = new ChinaIpTable([], '2026-01-01')
t('新数据不过时', fresh.staleness(Date.parse('2026-01-10')).stale, false)

const N = 100000
const s = Date.now()
for (let i = 0; i < N; i++) table.contains('223.5.5.5')
console.log(`\n  查找性能 ${((Date.now() - s) * 1000 / N).toFixed(2)} µs/次`)
console.log(`\n  ${pass} 通过 / ${fail} 失败`)
process.exit(fail ? 1 : 0)
