/**
 * rendezvous 服务端的测试。
 *
 * 重点不在「能连通」，在两件**看代码看不出来**的事：
 *   · 限速到底挡不挡得住脚本
 *   · 通道是不是真的**一次性**（复用会让「猜中 id」的价值变大）
 * 外加一条容易被忘的：**对扫描器要什么都不给**。
 */
import { WebSocket } from 'ws'
import { createRendezvous } from '../rendezvous/server.mjs'

let pass = 0, fail = 0
const t = (l, got, want) => {
  const ok = got === want
  ok ? pass++ : fail++
  console.log(`  ${ok ? '✔' : '✘'} ${l}${ok ? '' : `   得到 ${JSON.stringify(got)} 期望 ${JSON.stringify(want)}`}`)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const post = (port, path) => fetch(`http://127.0.0.1:${port}${path}`, { method: 'POST' })
const get = (port, path) => fetch(`http://127.0.0.1:${port}${path}`)

function wsOpen(port, id) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/join/${id}`)
    ws.on('open', () => resolve(ws))
    ws.on('error', reject)
    ws.on('close', (code) => reject(new Error(`closed ${code}`)))
  })
}

// 功能测试用宽松限额。
// 第一版把 concurrentPerIp 设成 2，结果前两个通道（建模测试里建的、从没关闭）
// 一直占着这个 IP，第三个通道就被挡了 —— 测试自己把自己绊倒。
// **这个行为本身是对的**：建了不用的通道会占到 90 秒清扫为止，
// 否则「狂建通道」就是免费的。所以是测试要迁就它，不是反过来。
const rv = createRendezvous({ port: 0, createPerMinute: 50, concurrentPerIp: 50, maxChannels: 64 })
const addr = await rv.listen()
const P = addr.port

console.log('── 健康检查：只回一个词 ──')
{
  const r = await get(P, '/healthz')
  const t0 = await r.text()
  t('200', r.status, 200)
  t('正文就是 ok（不报版本/负载/通道数）', t0, 'ok')
  t('没有 Server 头指纹', r.headers.get('server'), null)
}

console.log('\n── 对扫描器什么都不给 ──')
{
  // 扫描器最爱探这些。**统一 404，不区分「不存在」与「无权限」** ——
  // 区分了就等于告诉扫描者「这个路径有东西」
  for (const p of ['/', '/robots.txt', '/.git/config', '/.env', '/admin', '/api', '/channel']) {
    const r = await get(P, p)
    t(`${p} → 404`, r.status, 404)
  }
  const r = await get(P, '/')
  t('带 noindex 头（给守规矩的爬虫）', r.headers.get('x-robots-tag'), 'noindex, nofollow')
  t('不列目录、不返回版本', (await r.text()).includes('rendezvous'), false)
}

console.log('\n── 建通道 ──')
const ch1 = await (await post(P, '/channel')).json()
t('拿到 channel id', typeof ch1.channel === 'string' && ch1.channel.length >= 20, true)
t('带 TTL', ch1.ttlMs > 0, true)
{
  const r2 = await post(P, '/channel')
  t('第二次还能建', r2.status, 201)
}

console.log('\n── 通过通道中转字节 ──')
{
  const { channel } = await (await post(P, '/channel')).json()
  const a = await wsOpen(P, channel)
  await sleep(60)
  const b = await wsOpen(P, channel)
  await sleep(80)

  const got = new Promise((res) => b.once('message', (d) => res(Buffer.from(d))))
  a.send(Buffer.from([1, 2, 3, 4, 5]))
  const data = await got
  t('对端收到了', data.length, 5)
  t('内容原样（不解析、不改动）', data.toString('hex'), '0102030405')

  // 反方向也要通
  const back = new Promise((res) => a.once('message', (d) => res(Buffer.from(d))))
  b.send(Buffer.from('反方向'))
  t('双向都通', (await back).toString(), '反方向')

  a.close(); b.close()
  await sleep(120)
}

console.log('\n── 一次性：通道用完即焚（Wormhole issue #31 的教训）──')
{
  const { channel } = await (await post(P, '/channel')).json()
  const a = await wsOpen(P, channel)
  await sleep(50)
  const b = await wsOpen(P, channel)
  await sleep(80)
  a.close(); b.close()
  await sleep(200)

  // 复用同一 id 必须失败。允许复用会让「猜中一个 id」的价值变大
  let reused = false
  try { const w = await wsOpen(P, channel); reused = true; w.close() } catch { reused = false }
  t('已用完的 id 不能再连', reused, false)
}

console.log('\n── 第三个人进不来 ──')
{
  const { channel } = await (await post(P, '/channel')).json()
  const a = await wsOpen(P, channel)
  await sleep(50)
  const b = await wsOpen(P, channel)
  await sleep(80)
  let third = false
  try { const c = await wsOpen(P, channel); third = true; c.close() } catch { third = false }
  t('通道满了就拒第三个人', third, false)
  a.close(); b.close(); await sleep(120)
}

console.log('\n── 一方断开，另一方立刻知道 ──')
{
  const { channel } = await (await post(P, '/channel')).json()
  const a = await wsOpen(P, channel)
  await sleep(50)
  const b = await wsOpen(P, channel)
  await sleep(80)
  const closed = new Promise((res) => b.once('close', (code) => res(code)))
  a.close()
  // 不能干等 —— 干等会让用户以为「对面还在」
  const code = await Promise.race([closed, sleep(1500).then(() => 'timeout')])
  t('剩下那方收到关闭（不是干等）', code !== 'timeout', true)
  b.close(); await sleep(100)
}

console.log('\n── 限速：脚本狂建通道要被挡住 ──')
{
  // 单独起一个紧限额的服务 —— 限速参数一改，测的东西就该变，
  // 不要指望靠「在主服务上多建几个」间接测出来
  const rv2 = createRendezvous({ port: 0, createPerMinute: 3, concurrentPerIp: 3, maxChannels: 8 })
  const a2 = await rv2.listen()
  const P2 = a2.port
  let created = 0, blocked = 0, retryAfterSeen = false
  for (let i = 0; i < 8; i++) {
    const r = await post(P2, '/channel')
    if (r.status === 201) created++
    else { blocked++; if (r.headers.get('retry-after')) retryAfterSeen = true }
  }
  console.log(`    建成功 ${created} 次 / 被挡 ${blocked} 次`)
  t('创建数被限住了（不超过每分钟上限）', created <= 3, true)
  t('后续请求被挡', blocked > 0, true)
  t('被挡时给出 retry-after（让客户端知道等多久）', retryAfterSeen, true)
  await rv2.close()
}

console.log('\n── 建了不用的通道会占住这个 IP（有意为之）──')
{
  const rv3 = createRendezvous({ port: 0, createPerMinute: 10, concurrentPerIp: 1, maxChannels: 8 })
  const a3 = await rv3.listen()
  const first = await post(a3.port, '/channel')
  t('第一个建成功', first.status, 201)
  // 不 join、不关闭。第二个应该被并发上限挡下 ——
  // 否则「建通道」就是免费的，扫描者可以把表占满
  const second = await post(a3.port, '/channel')
  t('同一个 IP 再建被挡（并发上限 1）', second.status !== 201, true)
  const body = await second.json()
  t('  原因说明是 too-many-channels', body.error, 'too-many-channels')
  await rv3.close()
}

console.log('\n── 并发通道上限 ──')
{
  const before = rv.channels.size
  t('当前通道数不超过上限', before <= rv.cfg.maxChannels, true)
}

console.log(`\n  ${pass} 通过 / ${fail} 失败`)
await rv.close()
process.exit(fail ? 1 : 0)
