/**
 * 局域网发现 + 配对的测试。
 *
 * 发现部分**真的起两个实例、真的走 UDP 广播** —— 不 mock。
 * 理由和隧道那次一样：被测试的路径和出问题的路径不是同一条时，覆盖率是安慰剂。
 * 广播这块的坑（网卡选择、自己发的包回环、防火墙）全在真跑时才出现。
 */
import { generateIdentity } from '../lib/auth.js'
import {
  DEFAULT_PORT, MAGIC, MAX_BEACON, Role, decodeBeacon, encodeBeacon,
  createDiscovery, listBroadcastTargets, suggestDeviceName,
} from '../lib/discovery.js'
import { sessionIdFrom } from '../lib/auth.js'
import {
  DEFAULT_TTL_MS, EXPIRY_WARN_MS, MAX_PAYLOAD_CHARS, PairState,
  createPairingSession, decodePairingText, encodePairingText, finalizePairing,
  makePairingPayload,
} from '../lib/pairing.js'

let pass = 0, fail = 0
const t = (label, got, want) => {
  const ok = got === want
  ok ? pass++ : fail++
  console.log(`  ${ok ? '✔' : '✘'} ${label}${ok ? '' : `   得到 ${JSON.stringify(got)} 期望 ${JSON.stringify(want)}`}`)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  const me = generateIdentity()
  const you = generateIdentity()

  console.log('── 广播包：正常往返 ──')
  const enc = encodeBeacon({ publicKey: me.publicKey, port: 48899, name: '书房电脑', role: Role.SERVER })
  const dec = decodeBeacon(enc, '192.168.1.5')
  t('能解回来', dec !== null, true)
  t('公钥对', dec.publicKey, me.publicKey)
  t('端口对', dec.port, 48899)
  t('名字对', dec.name, '书房电脑')
  t('角色对', dec.role, Role.SERVER)
  // **地址取自包的来源，不取自包里的字段** —— 包里说自己是谁都可以
  t('地址取自来源', dec.address, '192.168.1.5')

  console.log('\n── 广播包：对恶意输入要严 ──')
  // 这个端口任何人都能往里发包，所以校验不过必须丢弃，
  // 不能「尽力解析」—— 那会把攻击者构造的字段带进后面的逻辑
  const bad = [
    ['不是 JSON', 'garbage'],
    ['JSON 但不是对象', '"hi"'],
    ['魔数不对', JSON.stringify({ m: 'OTHER', v: 1, pub: me.publicKey, p: 1 })],
    ['版本不对', JSON.stringify({ m: MAGIC, v: 99, pub: me.publicKey, p: 1 })],
    ['没有公钥', JSON.stringify({ m: MAGIC, v: 1, p: 1 })],
    ['公钥太短', JSON.stringify({ m: MAGIC, v: 1, pub: 'short', p: 1 })],
    ['公钥含非法字符', JSON.stringify({ m: MAGIC, v: 1, pub: 'x'.repeat(43) + '!!!!', p: 1 })],
    ['端口越界', JSON.stringify({ m: MAGIC, v: 1, pub: me.publicKey, p: 99999 })],
    ['端口不是数字', JSON.stringify({ m: MAGIC, v: 1, pub: me.publicKey, p: 'abc' })],
  ]
  for (const [label, text] of bad) t(`拒绝：${label}`, decodeBeacon(text, '1.2.3.4'), null)
  t('拒绝：超大包', decodeBeacon('x'.repeat(MAX_BEACON + 1), '1.2.3.4'), null)
  t('拒绝：null', decodeBeacon(null, '1.2.3.4'), null)
  // 角色字段非法时不该整个丢掉，而是回退到安全默认
  const weirdRole = decodeBeacon(JSON.stringify({ m: MAGIC, v: 1, pub: me.publicKey, p: 1, r: 'admin' }), '1.2.3.4')
  t('非法角色回退到 both 而不是丢弃', weirdRole?.role, Role.BOTH)

  console.log('\n── 网卡枚举 ──')
  const targets = listBroadcastTargets()
  console.log(`  找到 ${targets.length} 个可广播网卡`)
  for (const x of targets.slice(0, 4)) console.log(`    ${x.ifname}  ${x.address} → ${x.broadcast}`)
  t('不把回环当广播目标', targets.every((x) => !x.address.startsWith('127.')), true)
  t('广播地址格式合法', targets.every((x) => /^\d+\.\d+\.\d+\.\d+$/.test(x.broadcast)), true)
  // 实测踩到过：EasyTier 的 CGNAT 网卡是 100.64.0.1/32，
  // 算出来的广播地址就是它自己 —— 往自己发包永远发现不了东西，而且不报错
  t('不含「广播地址 == 自己」的网卡', targets.every((x) => x.broadcast !== x.address), true)

  console.log('\n── 真跑：两个实例互相发现 ──')
  if (targets.length === 0) {
    console.log('  （本机没有可广播的网卡，跳过这一节）')
  } else {
    const found = []
    const port = DEFAULT_PORT + 7
    const a = createDiscovery({
      publicKey: me.publicKey, servicePort: 50001, name: 'A 机',
      role: Role.SERVER, port, onPeer: (p) => found.push(p),
    })
    const b = createDiscovery({
      publicKey: you.publicKey, servicePort: 50002, name: 'B 机',
      role: Role.CLIENT, port,
    })
    await a.start()
    await b.start()
    // 广播 + 收敛，给足时间（真实网络里可能要 2~3 个周期）
    for (let i = 0; i < 12 && found.length === 0; i++) await sleep(500)

    t('A 发现了 B', found.length > 0, true)
    if (found.length) {
      t('  发现的是 B 的公钥', found[0].publicKey, you.publicKey)
      t('  设备名传过来了', found[0].name, 'B 机')
      // **自己发的包会回环回来，必须被丢掉**，否则每台设备都会「发现」自己
      t('  没有把自己当成对端', a.peers().some((p) => p.publicKey === me.publicKey), false)
    }
    await a.close()
    await b.close()
  }

  console.log('\n── 配对载荷 ──')
  const payload = makePairingPayload({ publicKey: me.publicKey, address: '192.168.1.5', port: 48899, name: '书房电脑' })
  const text = encodePairingText(payload)
  console.log(`  二维码内容（${text.length} 字符）：${text.slice(0, 72)}…`)
  t('长度在二维码可编码范围内', text.length <= MAX_PAYLOAD_CHARS, true)
  t('用的是 dshfusion:// 前缀（扫码 App 能认出来）', text.startsWith('dshfusion://pair/'), true)
  const back = decodePairingText(text)
  t('能解回来', back?.publicKey, me.publicKey)
  t('地址对', back?.address, '192.168.1.5')
  t('端口对', back?.port, 48899)

  console.log('\n── 配对载荷：对扫码内容要严 ──')
  for (const bad of ['', 'hello', 'dshfusion://other/x', 'dshfusion://pair/!!!!',
                     'dshfusion://pair/' + Buffer.from('{"v":99}').toString('base64url'),
                     'dshfusion://pair/' + Buffer.from('{"v":1,"pub":"short","port":1}').toString('base64url'),
                     'dshfusion://pair/' + Buffer.from(JSON.stringify({ v: 1, pub: me.publicKey, port: 0 })).toString('base64url')]) {
    t(`拒绝：${bad.slice(0, 40) || '(空)'}`, decodePairingText(bad), null)
  }
  t('拒绝：null', decodePairingText(null), null)

  console.log('\n── 配对码：两边算出来必须一样 ──')
  const sid = sessionIdFrom('nonce-a-' + 'x'.repeat(20), 'nonce-b-' + 'y'.repeat(20))
  const f1 = finalizePairing(me.publicKey, you.publicKey, sid)
  const f2 = finalizePairing(you.publicKey, me.publicKey, sid)
  t('双方码一致（排序生效）', f1.code, f2.code)
  t('是 6 位数字', /^\d{6}$/.test(f1.code), true)

  console.log('\n── 配对码：必须绑定到本次会话 ──')
  // **这是这个模块最重要的性质。** 第一版只用两个公钥派生，
  // 于是码永远不变 —— 那是 WPS PIN 的形状，被拍到一次就永久有效。
  const sid2 = sessionIdFrom('nonce-a-' + 'z'.repeat(20), 'nonce-b-' + 'w'.repeat(20))
  t('换了会话，码就不同', finalizePairing(me.publicKey, you.publicKey, sid2).code !== f1.code, true)
  const sameSid = sessionIdFrom('nonce-a-' + 'x'.repeat(20), 'nonce-b-' + 'y'.repeat(20))
  t('同一会话内稳定', finalizePairing(me.publicKey, you.publicKey, sameSid).code, f1.code)
  let sidErr = ''
  try { finalizePairing(me.publicKey, you.publicKey) } catch (e) { sidErr = e.message }
  t('不传 sessionId → 抛错（不许退回固定派生）', sidErr.includes('sessionId'), true)
  try { finalizePairing(me.publicKey, you.publicKey, 'short') } catch (e) { sidErr = e.message }
  t('sessionId 太短 → 抛错', sidErr.includes('足够长'), true)

  console.log('\n── 方式 A：数字码 ──')
  const N1 = 'peer-nonce-' + '1'.repeat(20)
  const sa = createPairingSession({ myPublicKey: me.publicKey, method: 'code' })
  sa.start()
  t('初始状态 SHOWING', sa.state, PairState.SHOWING)
  const got = sa.acceptPeer(you.publicKey, { name: 'B 机', nonce: N1 })
  t('拿到码', /^\d{6}$/.test(got.code), true)
  t('  码绑定了会话', got.sessionId.length > 20, true)
  t('状态进入 PEER_SEEN', sa.state, PairState.PEER_SEEN)

  let threw = ''
  try { sa.confirm() } catch (e) { threw = e.message }
  // **数字码方式下不核对就确认，是这一层唯一的安全性来源被跳过**
  t('不核对码就确认 → 抛错', threw.includes('必须核对'), true)

  const sb = createPairingSession({ myPublicKey: me.publicKey, method: 'code' })
  sb.start(); sb.acceptPeer(you.publicKey, { nonce: N1 })
  threw = ''
  try { sb.confirm('000000') } catch (e) { threw = e.message }
  t('码不对 → 抛错', threw.includes('不一致'), true)
  t('  且状态置为 FAILED（不可继续）', sb.state, PairState.FAILED)

  const sc = createPairingSession({ myPublicKey: me.publicKey, method: 'code' })
  sc.start(); const r3 = sc.acceptPeer(you.publicKey, { nonce: N1 })
  t('码对 → 确认成功', sc.confirm(r3.code).peerPublicKey, you.publicKey)
  t('  状态 CONFIRMED', sc.state, PairState.CONFIRMED)

  console.log('\n── 方式 B：二维码（安全性不依赖人眼）──')
  const sq = createPairingSession({ myPublicKey: me.publicKey, method: 'qr' })
  sq.start()
  sq.acceptPeer(you.publicKey, { name: 'B 机', nonce: N1 })
  t('二维码方式可以不传比对码', sq.confirm().peerPublicKey, you.publicKey)

  console.log('\n── 状态机的边界 ──')
  const sx = createPairingSession({ myPublicKey: me.publicKey, method: 'qr' })
  threw = ''
  try { sx.acceptPeer(you.publicKey, { nonce: N1 }) } catch (e) { threw = e.message }
  t('没 start 就 acceptPeer → 抛错', threw.includes('状态不对'), true)

  const sy = createPairingSession({ myPublicKey: me.publicKey, method: 'qr' })
  sy.start()
  threw = ''
  try { sy.acceptPeer(me.publicKey, { nonce: N1 }) } catch (e) { threw = e.message }
  // 同机双开时会连到自己 —— 要明确拒掉，不能静默
  t('接受自己的公钥 → 抛错', threw.includes('与本机相同'), true)

  const sz = createPairingSession({ myPublicKey: me.publicKey, method: 'qr' })
  sz.start()
  sz.acceptPeer(you.publicKey, { nonce: N1 })
  threw = ''
  try { sz.acceptPeer(generateIdentity().publicKey, { nonce: N1 }) } catch (e) { threw = e.message }
  // 同一会话里换公钥 = 有人在中间插了一手
  t('中途换公钥 → 抛错', threw.includes('发生变化'), true)
  t('  且状态 FAILED', sz.state, PairState.FAILED)

  threw = ''
  try { createPairingSession({ myPublicKey: me.publicKey, method: 'telepathy' }) } catch (e) { threw = e.message }
  t('未知配对方式 → 抛错', threw.includes('未知'), true)

  console.log('\n── 时限：到期后码作废 ──')
  {
    let clock = 1_000_000
    let expiredFired = 0
    const sp = createPairingSession({
      myPublicKey: me.publicKey, method: 'code',
      ttlMs: 180_000, now: () => clock, onExpire: () => { expiredFired++ },
    })
    sp.start()
    t('刚开时剩余时间 = TTL', sp.remainingMs(), 180_000)
    t('  还不到提醒线', sp.expiringSoon(), false)

    clock += 180_000 - EXPIRY_WARN_MS + 1
    t('临近到期会提醒', sp.expiringSoon(), true)

    clock += EXPIRY_WARN_MS + 1
    t('到期后剩余 0', sp.remainingMs(), 0)
    let e2 = ''
    try { sp.acceptPeer(you.publicKey, { nonce: N1 }) } catch (e) { e2 = e.message }
    t('到期后不能再接受对端', e2.includes('超时'), true)
    t('  触发了 onExpire 回调', expiredFired, 1)
    t('  状态置为 EXPIRED', sp.state, PairState.EXPIRED)

    // 超时后再确认也要被挡住 —— 少了这条，限时就是装饰
    let e3 = ''
    try { sp.confirm('123456') } catch (e) { e3 = e.message }
    t('到期后连确认也被挡住', e3.includes('超时'), true)
  }

  console.log('\n── 时限：重置必须换码（不能只是拨回时钟）──')
  {
    let clock = 5_000_000
    const sp = createPairingSession({ myPublicKey: me.publicKey, method: 'qr',
                                      ttlMs: 60_000, now: () => clock })
    sp.start()
    const r1 = sp.acceptPeer(you.publicKey, { nonce: N1 })
    clock += 61_000
    sp.refresh()
    t('重置后回到 SHOWING', sp.state, PairState.SHOWING)
    t('  时钟重新计时', sp.remainingMs(), 60_000)
    const r2 = sp.acceptPeer(you.publicKey, { nonce: N1 })
    // **关键**：如果 refresh 只重置时钟而没换 nonce，
    // 这里会得到同一个码 —— 那等于没重置
    t('  重置后的码与之前不同', r2.code !== r1.code, true)
  }

  console.log('\n── 一次性：用过就作废 ──')
  {
    const sp = createPairingSession({ myPublicKey: me.publicKey, method: 'qr' })
    sp.start()
    const r = sp.acceptPeer(you.publicKey, { nonce: N1 })
    sp.confirm()
    t('第一次确认成功', sp.state, PairState.CONFIRMED)
    t('  标记为已消费', sp.consumed, true)
    // 码若可重复使用，攻击者拍到一次就永久有效
    const sp2 = createPairingSession({ myPublicKey: me.publicKey, method: 'qr' })
    sp2.start()
    sp2.acceptPeer(you.publicKey, { nonce: N1 })
    sp2.confirm()
    let e = ''
    try { sp2.confirm() } catch (x) { e = x.message }
    t('不能重复确认（状态已推进）', e.length > 0, true)
  }

  console.log('\n── 缺对端 nonce 不许降级 ──')
  {
    const sp = createPairingSession({ myPublicKey: me.publicKey, method: 'code' })
    sp.start()
    let e = ''
    try { sp.acceptPeer(you.publicKey) } catch (x) { e = x.message }
    // 没有 nonce 就只能退回「公钥固定派生」，那正是要摆脱的形状
    t('缺 nonce → 抛错而不是降级', e.includes('nonce'), true)
  }

  console.log('\n── 设备名建议值 ──')
  const dn = suggestDeviceName('linux', 'my-macbook-pro')
  // 默认用机型而不是主机名 —— 否则在咖啡厅里会广播出身份
  t('不直接用主机名', dn.includes('my-macbook-pro'), false)
  t('是个可读的名字', dn.length > 0, true)

  console.log(`\n  ${pass} 通过 / ${fail} 失败`)
  process.exit(fail ? 1 : 0)
}

main().catch((e) => { console.error('  测试自身出错：', e); process.exit(2) })
