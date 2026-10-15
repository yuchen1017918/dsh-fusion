// 握手测试。重点在**两边都验**这件事上 ——
// 只验一边的实现能连上、看起来是对的，而它让中间人可以冒充未被验的那一边。
import { generateIdentity, PinStore } from '../lib/auth.js'
import { Msg } from '../lib/tunnel.js'
import { MAX_HANDSHAKE_FRAMES, clientHandshake, serverHandshake } from '../lib/handshake.js'

let pass = 0, fail = 0
const t = (l, got, want) => {
  const ok = got === want
  ok ? pass++ : fail++
  console.log(`  ${ok ? '✔' : '✘'} ${l}${ok ? '' : `   得到 ${JSON.stringify(got)} 期望 ${JSON.stringify(want)}`}`)
}

/** 把两端用一对回调接起来，模拟一条双向通道 */
function pair() {
  const aToB = [], bToA = []
  return {
    a: { send: (m) => bToA.push(m), onMessage: (h) => { aToB.push(h) } },
    b: { send: (m) => aToB.push(m), onMessage: (h) => { bToA.push(h) } },
    // 把排队中的消息投递给对应的处理器
    pump() {
      for (const h of aToB.splice(0)) for (const m of bToA.splice(0)) { /* 见下 */ }
    },
  }
}

/** 更直白的连线：把两端的 send 直接接到对方的 handler */
function wire() {
  let hA = () => {}, hB = () => {}
  const qA = [], qB = []
  const flush = () => {
    while (qA.length) hA(qA.shift())
    while (qB.length) hB(qB.shift())
  }
  return {
    A: { send: (m) => { qB.push(m); setTimeout(flush, 0) }, onMessage: (h) => { hA = h } },
    B: { send: (m) => { qA.push(m); setTimeout(flush, 0) }, onMessage: (h) => { hB = h } },
    flush,
  }
}

async function main() {
  const alice = generateIdentity(), bob = generateIdentity()

  console.log('── 正常握手（已配对）──')
  {
    const pins = new PinStore('/tmp/nonexistent-pins.json')
    pins.peers.set(alice.publicKey, { name: 'Alice 的手机' })
    pins.peers.set(bob.publicKey, { name: 'Bob 的电脑' })
    const w = wire()
    const sp = serverHandshake({ ...w.B, identity: bob, pinStore: pins })
    const cp = clientHandshake({ ...w.A, identity: alice, pinStore: pins })
    const [sr, cr] = await Promise.all([sp, cp])
    t('服务端握手成功', sr.ok, true)
    t('  认出的对端是 Alice', sr.peerPublicKey, alice.publicKey)
    t('  带出设备名', sr.name, 'Alice 的手机')
    // 客户端也必须验 —— 只验一边的实现同样会得到 ok:true，看不出问题
    t('客户端握手成功', cr.ok, true)
    t('  认出的对端是 Bob', cr.peerPublicKey, bob.publicKey)
  }

  console.log('\n── 未配对：走配对流程而不是当攻击 ──')
  {
    const pins = new PinStore('/tmp/nonexistent-pins2.json')
    const w = wire()
    const sp = serverHandshake({ ...w.B, identity: bob, pinStore: pins })
    const cp = clientHandshake({ ...w.A, identity: alice, pinStore: pins })
    const [sr, cr] = await Promise.all([sp, cp])
    t('服务端报 not-paired', sr.reason, 'not-paired')
    t('  并标记 needsPairing', sr.needsPairing, true)
    t('  带出对端公钥（配对要用）', sr.peerPublicKey, alice.publicKey)
    t('客户端也报 not-paired', cr.reason, 'not-paired')
  }

  console.log('\n── 冒充：只验一边的实现会漏掉这些 ──')
  {
    const pins = new PinStore('/tmp/nonexistent-pins3.json')
    pins.peers.set(alice.publicKey, { name: 'Alice' })
    // 服务端被换成了一个不知道 Bob 私钥的冒充者
    const mallory = generateIdentity()
    const w = wire()
    const sp = serverHandshake({ ...w.B, identity: mallory, pinStore: pins })
    const cp = clientHandshake({ ...w.A, identity: alice, pinStore: pins })
    const [, cr] = await Promise.all([sp, cp])
    // 关键：**客户端必须拒绝**。跳过客户端验证的实现会在这里返回 ok
    t('客户端拒绝冒充的服务端', cr.ok, false)
  }

  console.log('\n── 帧数上限 ──')
  {
    const pins = new PinStore('/tmp/nonexistent-pins4.json')
    let handler = null
    const r = serverHandshake({
      send: () => {},
      onMessage: (h) => { handler = h },
      identity: bob, pinStore: pins, timeoutMs: 2000,
    })
    for (let i = 0; i < MAX_HANDSHAKE_FRAMES + 2; i++) {
      handler?.({ type: Msg.HELLO, v: 1, pub: alice.publicKey, nonce: 'x'.repeat(43) })
    }
    const res = await r
    // 对端狂发帧时不能一直陪它玩 —— 那是个廉价的资源耗尽方式
    t('超过帧数上限就中止', res.reason, 'too-many-frames')
  }

  console.log('\n── 协议版本 ──')
  {
    const pins = new PinStore('/tmp/nonexistent-pins5.json')
    let handler = null
    const r = serverHandshake({ send: () => {}, onMessage: (h) => { handler = h },
                                 identity: bob, pinStore: pins, timeoutMs: 2000 })
    handler?.({ type: Msg.HELLO, v: 99, pub: alice.publicKey, nonce: 'x'.repeat(43) })
    t('版本不符即拒', (await r).reason, 'protocol-mismatch')
  }

  console.log('\n── 超时 ──')
  {
    const pins = new PinStore('/tmp/nonexistent-pins6.json')
    const t0 = Date.now()
    const r = await serverHandshake({ send: () => {}, onMessage: () => {},
                                      identity: bob, pinStore: pins, timeoutMs: 150 })
    t('不说话的连接会被超时断掉', r.reason, 'handshake-timeout')
    t('  而且没有等太久', Date.now() - t0 < 1500, true)
  }

  console.log(`\n  ${pass} 通过 / ${fail} 失败`)
  process.exit(fail ? 1 : 0)
}
main().catch((e) => { console.error('  测试自身出错：', e); process.exit(2) })
