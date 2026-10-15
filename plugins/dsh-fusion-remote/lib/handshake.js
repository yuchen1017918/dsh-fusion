/**
 * 在一条裸连接上完成双向认证握手。
 *
 * ── 为什么单独一个文件 ────────────────────────────────────────────────
 * 握手是**唯一一段既在服务端跑、也在客户端跑**的代码，而两边要做的事不同。
 * 把它拆出来是为了让「哪一边验什么」一眼可见 ——
 * 混在各自的连接逻辑里时，很容易写成「服务端验了、客户端没验」，
 * 而那种错误在测试里通常表现为「能连上」，于是看起来是好的。
 *
 * ── 顺序 ──────────────────────────────────────────────────────────────
 *   客户端 → hello {pub, nonce}
 *   服务端 → hello-ok {pub, nonce, proof}      proof = sign(resp|客户端nonce|客户端pub)
 *   客户端   验证 proof → 得到对端身份
 *   客户端 → proof {sig}                        sig  = sign(chal|服务端pub|服务端nonce)
 *   服务端   验证 sig → 得到对端身份
 *              两边都验完，才算互相认得
 *
 * **两步缺一不可。** 只验一边（常见的是只让服务端验客户端）意味着另一边
 * 不知道自己在跟谁说话 —— 中间人可以冒充服务端，客户端照样把数据交出去。
 */
import { Msg } from './tunnel.js'
import {
  PROTOCOL_VERSION, makeClientHello, makeServerHello, verifyClientProof,
  verifyServerHello,
} from './auth.js'

/** 握手超时。卡住不动的连接要主动断，不能挂着占资源。 */
export const HANDSHAKE_TIMEOUT_MS = 10_000

/** 一次握手交换里最多允许的帧数。防止对端狂发帧拖住我们。 */
export const MAX_HANDSHAKE_FRAMES = 8

/**
 * 握手消息走隧道同一条通道，用 `type: 'hello'` / `'hello-ok'` / `'proof'`。
 * 复用通道而不是另开一条，是为了让「未认证的连接」在协议上就没有别的能力 ——
 * 它在通过握手之前发的任何业务帧都会被丢弃。
 */
export function isHandshakeType(type) {
  return type === Msg.HELLO || type === Msg.HELLO_OK || type === 'proof'
}

/**
 * 服务端侧握手。
 *
 * @returns {Promise<{ok: boolean, reason?: string, peerPublicKey?: string}>}
 */
export function serverHandshake({ send, onMessage, identity, pinStore, timeoutMs = HANDSHAKE_TIMEOUT_MS }) {
  return new Promise((resolve) => {
    let serverHello = null
    // **客户端的公钥必须从 HELLO 里记下来。**
    // 第一版写成了 `serverHello.clientPub` —— 那个字段不存在，
    // 于是验证时拿到 null，而 null 会让验签失败但**原因指向签名而不是字段缺失**，
    // 排查时会往密码学方向查，其实是忘了存。
    let clientPub = null
    let frames = 0
    let settled = false

    const finish = (r) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve(r)
    }
    const timer = setTimeout(() => finish({ ok: false, reason: 'handshake-timeout' }), timeoutMs)

    onMessage((msg) => {
      if (settled) return
      if (++frames > MAX_HANDSHAKE_FRAMES) {
        return finish({ ok: false, reason: 'too-many-frames' })
      }
      if (msg.type === Msg.HELLO) {
        if (msg.v !== PROTOCOL_VERSION) {
          return finish({ ok: false, reason: 'protocol-mismatch' })
        }
        clientPub = msg.pub
        serverHello = makeServerHello(identity, msg)
        // 先回挑战，**此时还没有验证客户端的任何东西** ——
        // 这一帧只是让客户端能验证我们。验证它在下一帧。
        send({ type: Msg.HELLO_OK, ...serverHello })
        return
      }
      if (msg.type === 'proof' && serverHello) {
        // 两次出现的公钥必须一致。中途换公钥 = 有人插了一手
        if (msg.pub && msg.pub !== clientPub) {
          return finish({ ok: false, reason: 'peer-key-changed' })
        }
        // 复用 auth.js 的校验，避免这里再写一遍（写两遍就会两边不一致）
        const v = verifyClientProof(
          clientPub,
          serverHello.nonce,
          identity.publicKey,
          msg.sig,
        )
        if (!v.ok) return finish({ ok: false, reason: v.reason })
        if (!pinStore.has(msg.pub)) {
          // 签名是对的但没配对过 —— 这是**正常的首次配对路径**，不是攻击。
          // 所以要区分开：needsPairing 让上层去走配对流程，而不是直接拒绝。
          return finish({ ok: false, reason: 'not-paired', needsPairing: true, peerPublicKey: msg.pub })
        }
        pinStore.touch(msg.pub)
        return finish({ ok: true, peerPublicKey: msg.pub, name: pinStore.get(msg.pub)?.name })
      }
    })
  })
}

/**
 * 客户端侧握手。
 *
 * @returns {Promise<{ok: boolean, reason?: string, peerPublicKey?: string}>}
 */
export function clientHandshake({ send, onMessage, identity, pinStore, timeoutMs = HANDSHAKE_TIMEOUT_MS }) {
  return new Promise((resolve) => {
    const hello = makeClientHello(identity)
    let frames = 0
    let settled = false

    const finish = (r) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve(r)
    }
    const timer = setTimeout(() => finish({ ok: false, reason: 'handshake-timeout' }), timeoutMs)

    send({ type: Msg.HELLO, ...hello })

    onMessage((msg) => {
      if (settled) return
      if (++frames > MAX_HANDSHAKE_FRAMES) {
        return finish({ ok: false, reason: 'too-many-frames' })
      }
      if (msg.type === Msg.HELLO_OK) {
        // **客户端也必须验证服务端。** 跳过这一步的实现到处都是，
        // 而它意味着「客户端不知道自己在跟谁说话」——
        // 中间人冒充服务端时，客户端会照样把会话交出去。
        const v = verifyServerHello(msg, hello)
        if (!v.ok) return finish({ ok: false, reason: v.reason })

        const sig = identity.sign(`chal|${msg.pub}|${msg.nonce}`)
        send({ type: 'proof', pub: identity.publicKey, sig })

        if (!pinStore.has(msg.pub)) {
          return finish({ ok: false, reason: 'not-paired', needsPairing: true, peerPublicKey: msg.pub })
        }
        pinStore.touch(msg.pub)
        return finish({ ok: true, peerPublicKey: msg.pub, name: pinStore.get(msg.pub)?.name })
      }
    })
  })
}
