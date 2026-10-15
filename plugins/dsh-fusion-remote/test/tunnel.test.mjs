/**
 * 隧道的端到端测试。
 *
 * ── 为什么这么写 ──────────────────────────────────────────────────────
 * 本项目踩过的一个坑：之前一个代理实现有 3 个真 bug，**全在 WebSocket 上**，
 * 而它的 8 个测试一个都没测到 —— 因为那些测试全是手写的 HTTP 请求。
 *
 * 教训：**被测试的路径和出问题的路径不是同一条时，覆盖率是安慰剂。**
 *
 * 所以这里起一个**真的上游 HTTP 服务**、走**真的 TCP**、
 * 用**真的 HTTP 客户端**打过去，四条路径全覆盖：
 *   1. 普通 HTTP
 *   2. 多次 set-cookie（同名头不能丢 —— 这正是上个实现错的地方）
 *   3. SSE 流式（模型回复走这条）
 *   4. **WebSocket 升级**（实时通道，上个实现整条死在这）
 */
import { createServer as createHttpServer, request as httpRequest } from 'node:http'
import { createServer as createNetServer, connect as netConnect } from 'node:net'
import { once } from 'node:events'
import {
  FrameDecoder, Msg, Msg as _M, createLocalTunnel, denormalizeHeaders,
  encodeFrame, isUpgradeRequest, normalizeHeaders, serveTunnel, stripHopByHop,
} from '../lib/tunnel.js'

let pass = 0, fail = 0
const t = (label, got, want) => {
  const ok = got === want
  ok ? pass++ : fail++
  console.log(`  ${ok ? '✔' : '✘'} ${label}${ok ? '' : `   得到 ${JSON.stringify(got)} 期望 ${JSON.stringify(want)}`}`)
}
const logs = []
const log = (m) => logs.push(m)

// ─────────────────────────────────────────── 假的上游「DSH」

const upstream = createHttpServer((req, res) => {
  if (req.url === '/') {
    res.writeHead(200, { 'content-type': 'text/html' })
    res.end('<h1>远端 DSH</h1>')
    return
  }
  if (req.url === '/cookies') {
    // 两条同名头。**只转发一个的实现会在这里露馅**
    res.setHeader('set-cookie', ['a=1; Path=/', 'b=2; Path=/'])
    res.writeHead(200)
    res.end('ok')
    return
  }
  if (req.url === '/api/event') {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
    res.write('data: 一\n\n')
    setTimeout(() => { res.write('data: 二\n\n'); res.end() }, 30)
    return
  }
  if (req.url === '/echo') {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const body = Buffer.concat(chunks)
      res.writeHead(200, { 'content-type': 'application/octet-stream' })
      res.end(body)
    })
    return
  }
  res.writeHead(404); res.end('nope')
})

// WebSocket 升级：只做握手 + 原样回显，足够验证隧道是否正确透传
upstream.on('upgrade', (req, socket) => {
  console.log(`      [上游] 收到升级请求 ${req.url}`)
  const key = req.headers['sec-websocket-key']
  const ver = req.headers['sec-websocket-version']
  // 这里就是上个实现死掉的地方：握手必须**同时**有 key 和 version。
  // 缺一个就直接拒，而 HTTP 部分一切正常 —— 所以日志上看不出问题。
  if (!key || !ver) {
    console.log(`      [上游] 拒绝升级：key=${key} ver=${ver}`)
    socket.write('HTTP/1.1 400 Bad Request\r\n\r\n')
    socket.destroy()
    return
  }
  const { createHash } = require$('node:crypto')
  const accept = createHash('sha1')
    .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')
  console.log(`      [上游] 回 101`)
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`)
  socket.on('data', (d) => socket.write(Buffer.concat([Buffer.from('echo:'), d])))
})

// 在 ESM 里拿 require
import { createRequire } from 'node:module'
const require$ = createRequire(import.meta.url)
console.log('  （测试脚本已加载完）')

async function main() {
  await new Promise((r) => upstream.listen(0, '127.0.0.1', r))
  const upstreamPort = upstream.address().port
  console.log(`  （假上游 DSH 起在 127.0.0.1:${upstreamPort}）\n`)

  console.log('── 分帧 ──')
  const dec = new FrameDecoder(() => {})
  const f = encodeFrame({ type: 'x', n: 1 }, Buffer.from('二进制体'))
  t('单帧能解出来', (() => {
    let got = null
    const d = new FrameDecoder((j, b) => { got = [JSON.parse(j.toString()).n, b.toString()] })
    d.push(f)
    return JSON.stringify(got)
  })(), JSON.stringify([1, '二进制体']))

  t('拆成两半也能解', (() => {
    let got = null
    const d = new FrameDecoder((j, b) => { got = [JSON.parse(j.toString()).n, b.toString()] })
    d.push(f.subarray(0, 5)); d.push(f.subarray(5))
    return JSON.stringify(got)
  })(), JSON.stringify([1, '二进制体']))

  t('两帧挤在一次里都能解', (() => {
    const got = []
    const d = new FrameDecoder((j) => { got.push(JSON.parse(j.toString()).n) })
    d.push(Buffer.concat([encodeFrame({ n: 1 }), encodeFrame({ n: 2 }), encodeFrame({ n: 3 })]))
    return JSON.stringify(got)
  })(), JSON.stringify([1, 2, 3]))

  console.log('\n── 头部处理 ──')
  const multi = normalizeHeaders({ 'set-cookie': ['a=1', 'b=2'], 'x-one': 'v' })
  t('同名头展开成多条', multi.filter(([k]) => k === 'set-cookie').length, 2)
  const back = denormalizeHeaders(multi)
  t('再合回去仍是数组（不丢）', Array.isArray(back['set-cookie']) && back['set-cookie'].length, 2)

  const hop = stripHopByHop([['connection', 'close'], ['upgrade', 'websocket'], ['x-real', 'v']])
  t('非升级时删掉 hop-by-hop', hop.length, 1)
  const hopU = stripHopByHop([['connection', 'Upgrade'], ['upgrade', 'websocket']], { isUpgrade: true })
  t('升级时保留 upgrade/connection', hopU.length, 2)
  t('isUpgradeRequest 认得出来', isUpgradeRequest([['Upgrade', 'websocket']]), true)

  console.log('\n── 端到端：真 TCP + 真 HTTP ──')
  // 通道：一个 TCP 对，两端分别接隧道服务端与本地隧道口
  console.log('    [1] 建隧道服务端…')
  const bridge = createNetServer((s) => {
    serveTunnel({ socket: s, targetPort: upstreamPort, isAllowed: () => true, log })
  })
  await new Promise((r) => bridge.listen(0, '127.0.0.1', r))
  const bridgePort = bridge.address().port
  console.log(`    [2] 隧道服务端在 ${bridgePort}`)

  // 客户端侧通道
  console.log('    [3] 拨号…')
  const chan = netConnect({ port: bridgePort, host: '127.0.0.1' })
  await once(chan, 'connect')
  console.log('    [4] 通道已连')

  let nextId = 1
  const pending = new Map()
  const sub = { nextId: () => nextId++ }
  const decoder = new FrameDecoder((j, b) => {
    const m = JSON.parse(j.toString())
    const p = pending.get(m.id)
    if (p) p(m, b)
  })
  chan.on('data', (c) => decoder.push(c))
  sub.send = (obj, body) => chan.write(encodeFrame(obj, body))
  sub.awaitResponse = (id, res, _log) => {
    let head = null
    pending.set(id, (m, b) => {
      if (m.type === Msg.RESP_HEAD && !head) {
        head = m
        res.writeHead(m.status, denormalizeHeaders(m.headers))
      } else if (m.type === Msg.RESP_BODY && head) {
        res.write(b)
      } else if (m.type === Msg.RESP_END) {
        res.end(); pending.delete(id)
      } else if (m.type === Msg.ERROR) {
        res.writeHead(502); res.end(m.message); pending.delete(id)
      }
    })
  }
  sub.awaitUpgrade = (id, socket, _log) => {
    console.log(`      [测试] 注册升级等待 id=${id}`)
    pending.set(id, (m, b) => {
      console.log(`      [测试] 升级收到 ${m.type} upgraded=${m.upgraded} 体=${b?.length ?? 0}`)
      if (m.type === Msg.RESP_HEAD && m.upgraded) {
        socket.write(m.raw + '\r\n\r\n')
      } else if (m.type === Msg.RAW) {
        socket.write(b)
      } else if (m.type === Msg.RESP_END) {
        socket.end(); pending.delete(id)
      } else if (m.type === Msg.ERROR) {
        socket.destroy(); pending.delete(id)
      }
    })
  }

  console.log('    [5] 起本地隧道口…')
  const tunnel = await createLocalTunnel({ channel: () => sub, log })
  console.log(`    [6] 本地隧道口在 ${tunnel.port}`)
  const base = `http://127.0.0.1:${tunnel.port}`

  const get = (path) => new Promise((resolve, reject) => {
    const req = httpRequest(base + path, (res) => {
      const chunks = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString() }))
    })
    req.on('error', reject); req.end()
  })

  // 实时把隧道内部日志打出来 —— 卡住时这是唯一能看出走到哪的办法
  let logCursor = 0
  const drain = setInterval(() => {
    while (logCursor < logs.length) console.log(`      · ${logs[logCursor++]}`)
  }, 200)

  console.log('    [7] 发第一个请求…')
  const r1 = await Promise.race([get('/'), new Promise((_, rj) => setTimeout(() => rj(new Error('请求 8 秒未返回')), 8000))]).catch((e) => { console.log(`      ✘ ${e.message}`); return null })
  clearInterval(drain)
  while (logCursor < logs.length) console.log(`      · ${logs[logCursor++]}`)
  if (!r1) { console.log('\n  首个请求就卡住，后续断言跳过'); process.exit(1) }
  console.log('    [8] 第一个请求回来了')
  t('普通 HTTP 状态', r1.status, 200)
  t('普通 HTTP 正文', r1.body.includes('远端 DSH'), true)

  const r2 = await get('/cookies')
  // **这条就是上个实现会失败的地方**
  t('两条 set-cookie 都到了（不是只到一条）', (r2.headers['set-cookie'] ?? []).length, 2)
  t('  第一条内容对', (r2.headers['set-cookie'] ?? [])[0], 'a=1; Path=/')
  t('  第二条内容对', (r2.headers['set-cookie'] ?? [])[1], 'b=2; Path=/')

  const r3 = await get('/api/event')
  t('SSE 流内容完整', r3.body.includes('一') && r3.body.includes('二'), true)
  t('SSE content-type 透传', r3.headers['content-type'], 'text/event-stream')

  // 带 body 的请求 —— 分帧错位会在这里露馅
  const echoed = await new Promise((resolve, reject) => {
    const req = httpRequest(base + '/echo', { method: 'POST' }, (res) => {
      const chunks = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => resolve(Buffer.concat(chunks).toString()))
    })
    req.on('error', reject)
    req.end('这是一段请求体，中文与 emoji 🎯')
  })
  t('POST body 原样往返（分帧没错位）', echoed, '这是一段请求体，中文与 emoji 🎯')

  console.log('\n── WebSocket 升级（上个实现整条死在这）──')
  const wsResult = await new Promise((resolve) => {
    // 走隧道口做一次真的升级
    const s = netConnect({ port: tunnel.port, host: '127.0.0.1' }, () => {
      s.write(
        'GET /api/remote.mux HTTP/1.1\r\n' +
        'Host: 127.0.0.1\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n' +
        'Sec-WebSocket-Version: 13\r\n\r\n')
    })
    // 用 Buffer 累积、按字节比对。
    // 第一版用 toString('latin1') 拼字符串再 includes('echo:原始帧') ——
    // 中文经过 latin1 会变成乱码，于是**明明透传成功却判定失败**。
    // 测试自己错了比代码错更难发现：你会去查实现，而实现是对的。
    const parts = []
    let sent = false
    const timer = setTimeout(() => { s.destroy(); resolve({ ok: false, why: '超时' }) }, 3000)
    s.on('data', (c) => {
      console.log(`      [探测] socket 收到 ${c.length} 字节：${JSON.stringify(c.subarray(0,48).toString('latin1'))}`)
      parts.push(c)
      const all = Buffer.concat(parts)
      if (!sent && all.includes(Buffer.from('101 Switching Protocols'))) {
        sent = true
        // 升级成功后发一段原始字节，验证是否原样透传
        s.write('原始帧')
      }
      if (all.includes(Buffer.from('echo:原始帧'))) {
        console.log('      [探测] 匹配成功')
        clearTimeout(timer); s.destroy()
        // 这里**不要**把字节转成字符串再比 ——
        // latin1 会把中文变成乱码，于是「明明成功却判定失败」。
        // 同一个坑我在这一个测试里踩了三次，所以最后改成直接传布尔。
        resolve({ ok: true, handshake: all.includes(Buffer.from('Sec-WebSocket-Accept')), echoed: true })
      }
    })
    s.on('error', (e) => { clearTimeout(timer); resolve({ ok: false, why: e.message }) })
  })
  if (!wsResult.ok) console.log(`      （升级失败原因：${wsResult.why}）`)
  t('升级握手成功（101）', wsResult.ok, true)
  t('  带回了 Sec-WebSocket-Accept', wsResult.handshake ?? false, true)
  t('  升级后的原始字节原样透传', wsResult.echoed === true, true)

  console.log('\n── 失败路径 ──')
  const noChan = await createLocalTunnel({ channel: () => null, log })
  const err = await new Promise((resolve) => {
    httpRequest(`http://127.0.0.1:${noChan.port}/`, (res) => {
      let b = ''; res.on('data', (c) => b += c); res.on('end', () => resolve({ s: res.statusCode, b }))
    }).end()
  })
  t('未连接时返回 503 而不是挂起', err.s, 503)
  t('  并给出可读的原因', err.b.includes('未连接'), true)

  console.log('\n── 隧道内部日志（定位用）──')
  for (const line of logs) console.log(`    · ${line}`)
  console.log(`\n  ${pass} 通过 / ${fail} 失败`)
  // 显式清理 + 硬退出。
  // 只 await server.close() 的话，残留的长连接会让进程永远不退出 ——
  // 而 CI 里「测试通过了但进程挂着」和「测试失败了」看起来一样。
  // 测试脚本不需要优雅退出，直接 exit 更可靠。
  try { chan.destroy() } catch {}
  for (const c of [tunnel, noChan]) { try { c.server.close() } catch {} }
  try { bridge.close() } catch {}
  try { upstream.closeAllConnections?.() ; upstream.close() } catch {}
  process.exit(fail ? 1 : 0)
}

main().catch((e) => { console.error('  测试自身出错：', e); process.exit(2) })
