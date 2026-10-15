/**
 * 双向认证的测试。
 *
 * 这块代码的失败模式是**静默放行** —— 认证写错时通常不报错，
 * 只是「谁都能连上」。所以测试要正面覆盖**拒绝路径**，而不只是happy path。
 */
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  Identity, PinStore, admit, generateIdentity, loadOrCreateIdentity,
  makeClientHello, makeServerHello, pairingCode, verifyClientProof,
  verifyServerHello, verifySignature,
} from '../lib/auth.js'

let pass = 0, fail = 0
const t = (label, got, want) => {
  const ok = got === want
  ok ? pass++ : fail++
  console.log(`  ${ok ? '✔' : '✘'} ${label}${ok ? '' : `   得到 ${JSON.stringify(got)} 期望 ${JSON.stringify(want)}`}`)
}

const dir = mkdtempSync(join(tmpdir(), 'dsh-remote-auth-'))

console.log('── 身份 ──')
const alice = loadOrCreateIdentity(join(dir, 'alice'))
const bob = loadOrCreateIdentity(join(dir, 'bob'))
t('公钥是 32 字节 base64url', Buffer.from(alice.publicKey, 'base64url').length, 32)
t('两个身份公钥不同', alice.publicKey !== bob.publicKey, true)

const again = loadOrCreateIdentity(join(dir, 'alice'))
t('重新加载得到同一身份（持久化生效）', again.publicKey, alice.publicKey)

console.log('\n── 签名 ──')
const sig = alice.sign('hello')
t('自己的签名能验过', verifySignature(alice.publicKey, 'hello', sig), true)
t('换数据验不过', verifySignature(alice.publicKey, 'hello!', sig), false)
t('换公钥验不过', verifySignature(bob.publicKey, 'hello', sig), false)
t('垃圾签名验不过', verifySignature(alice.publicKey, 'hello', 'AAAA'), false)
t('公钥长度不对时失败', verifySignature('AAAA', 'hello', sig), false)
// 失败方向：任何异常都必须返回 false，而不是抛出后让调用方当成「通过」
t('null 公钥失败', verifySignature(null, 'hello', sig), false)
t('null 签名失败', verifySignature(alice.publicKey, 'hello', null), false)

console.log('\n── 配对码 ──')
const codeAB = pairingCode(alice.publicKey, bob.publicKey)
const codeBA = pairingCode(bob.publicKey, alice.publicKey)
// 这条是最容易错的：不排序的话 A 算 (A,B)、B 算 (B,A)，两边永远对不上，
// 而且症状看起来像协议错，其实是忘了排序
t('(A,B) 与 (B,A) 相同（排序生效）', codeAB, codeBA)
t('是 6 位十进制', /^\d{6}$/.test(codeAB), true)
t('不同设备对得不同码', pairingCode(alice.publicKey, generateIdentity().publicKey) !== codeAB, true)

console.log('\n── 完整握手（首次配对）──')
const hello = makeClientHello(alice)
const serverHello = makeServerHello(bob, hello)
const vs = verifyServerHello(serverHello, hello)
t('客户端验证服务端通过', vs.ok, true)
t('客户端拿到的是服务端公钥', vs.peerPub, bob.publicKey)

const badServer = { ...serverHello, proof: alice.sign('随便什么') }
t('服务端签名不对时拒绝', verifyServerHello(badServer, hello).ok, false)
t('协议版本不一致时拒绝', verifyServerHello({ ...serverHello, v: 99 }, hello).ok, false)

const proof = alice.sign(`chal|${bob.publicKey}|${serverHello.nonce}`)
t('服务端验证客户端通过', verifyClientProof(alice.publicKey, serverHello.nonce, bob.publicKey, proof).ok, true)
t('用错随机数时拒绝（防重放）',
  verifyClientProof(alice.publicKey, 'other-nonce', bob.publicKey, proof).ok, false)
t('用错服务端公钥时拒绝',
  verifyClientProof(alice.publicKey, serverHello.nonce, alice.publicKey, proof).ok, false)

console.log('\n── 准入判定 ──')
const pins = new PinStore(join(dir, 'pins.json'))
let a = admit({ identity: bob, pinStore: pins, clientHello: hello, serverNonce: serverHello.nonce, clientProof: proof })
t('未配对 → 拒绝', a.ok, false)
t('未配对 → 标记 needsPairing', a.needsPairing, true)

pins.add(alice.publicKey, 'Alice 的手机')
a = admit({ identity: bob, pinStore: pins, clientHello: hello, serverNonce: serverHello.nonce, clientProof: proof })
t('已配对 + 签名对 → 放行', a.ok, true)
t('放行时带上设备名', a.name, 'Alice 的手机')

// **顺序**：签名必须先于 pin 检查。
// 反过来的话，攻击者拿一个没 pin 过的公钥 + 垃圾签名就能跳过签名验证
const mallory = generateIdentity()
const mHello = makeClientHello(mallory)
a = admit({ identity: bob, pinStore: pins, clientHello: mHello, serverNonce: serverHello.nonce,
            clientProof: 'AAAA' })
t('未配对的公钥 + 垃圾签名 → 拒绝', a.ok, false)
t('  原因必须是 bad-signature 而不是 not-paired',
  a.reason, 'bad-signature')

console.log('\n── 公钥变更 ──')
const pins2 = new PinStore(join(dir, 'pins2.json'))
pins2.add(alice.publicKey, 'Alice')
pins2.save()
const reloaded = new PinStore(join(dir, 'pins2.json'))
t('pin 表持久化', reloaded.has(alice.publicKey), true)
t('pin 表设备名保住了', reloaded.get(alice.publicKey).name, 'Alice')
const key2 = generateIdentity().publicKey
t('换了公钥就是未配对（不静默接受）', reloaded.has(key2), false)

console.log('\n── 不可用身份 ──')
const dead = Identity.unavailable()
let threw = false
try { makeClientHello(dead) } catch { threw = true }
t('身份不可用时**抛错**而不是发空握手', threw, true)

console.log(`\n  ${pass} 通过 / ${fail} 失败`)
process.exit(fail ? 1 : 0)
