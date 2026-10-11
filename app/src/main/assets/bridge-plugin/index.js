/**
 * dsh-fusion-android-bridge —— 手机能力 ⇄ DSH 工具的翻译层。
 *
 * 设计要点（每一条都是被 Android 的现实逼出来的）：
 *  · 工具清单在装配时落成 tools.json，启动同步读取 —— apply() 是同步的，
 *    在这里做网络发现会把宿主启动拖慢，而且失败就是「工具全没了」。
 *  · 一切经由 127.0.0.1 的回环桥转发，带 token 鉴权；手机数据不出设备。
 *  · 返回值统一收敛成 { ok, summary, detail } 三元组：截图这类工具的 base64
 *    有几百 KB，直接塞进上下文能把一次对话撑爆，所以这里做了尺寸裁剪。
 *  · 反向还有一条通道：订阅 dsh 的 session 事件，把「模型刚说完一段话」
 *    推给手机端，屏幕顶部的流体云胶囊就是靠它浮出来的。
 */

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'fusion-android-bridge'

/** 依赖宿主提供的工具注册表服务。 */
export const inject = ['tools']

const HERE = dirname(fileURLToPath(import.meta.url))

/** 单次回传进上下文的字符上限：超过就截断，并提示结果已落盘。 */
const DETAIL_LIMIT = 6000

/**
 * 读取装配时写下的能力清单。
 * @returns {{tools: Array<object>, generatedAt?: string, bridge?: object} | null} 清单或 null。
 */
function loadManifest() {
  const candidates = [join(HERE, 'tools.json'), join(HERE, '..', 'tools.json')]
  for (const file of candidates) {
    try {
      const parsed = JSON.parse(readFileSync(file, 'utf8'))
      if (Array.isArray(parsed?.tools)) return parsed
    } catch {
      // 找不到就试下一个；全都没有时静默退化成「不注册任何工具」
    }
  }
  return null
}

/** 桥的地址与 token 由手机端通过环境变量注入。 */
function bridgeConfig() {
  const base = (process.env.DSH_FUSION_BRIDGE_URL || 'http://127.0.0.1:7310').replace(/\/+$/, '')
  const token = process.env.DSH_FUSION_BRIDGE_TOKEN || ''
  return { base, token }
}

/**
 * 把 DSH 的参数规格翻译成桥能接收的 JSON Schema 片段。
 * @param spec - `{ type:'object', properties, required }` 形式的规格。
 * @returns 桥端工具注册仍沿用的同一份结构（此处直接原样透传，仅做空值兜底）。
 */
function normalizeParameters(spec) {
  if (!spec || typeof spec !== 'object') return { type: 'object', properties: {} }
  return {
    type: 'object',
    properties: spec.properties || {},
    ...(Array.isArray(spec.required) && spec.required.length > 0 ? { required: spec.required } : {}),
  }
}

/**
 * 调用手机端执行一条能力。
 * @param id - 能力 id，例如 `android_tap`。
 * @param args - 模型给出的参数。
 * @returns 手机端返回的原始对象。
 * @throws 网络失败、HTTP 非 2xx、或桥端显式返回 `{ok:false}` 时抛出。
 */
async function invoke(id, args) {
  const { base, token } = bridgeConfig()
  const response = await fetch(`${base}/invoke`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-fusion-token': token },
    body: JSON.stringify({ id, args: args ?? {} }),
  })
  if (!response.ok) {
    throw new Error(`能力桥返回 HTTP ${response.status}（${id}）`)
  }
  const payload = await response.json()
  if (payload && payload.ok === false) {
    throw new Error(payload.error || `能力 ${id} 执行失败`)
  }
  return payload
}

/**
 * 把任意结果压成一段人类/模型可读的摘要。
 * 优先挑选信息密度高的字段，并丢弃截图 base64 这种超大负载。
 * @param result - 桥返回的对象。
 * @returns 摘要文本。
 */
function summarize(result) {
  if (result == null) return '（无返回）'
  if (typeof result === 'string') return result.slice(0, DETAIL_LIMIT)

  const preferred = ['path', 'text', 'stdout', 'summary', 'message', 'raw', 'detail']
  for (const key of preferred) {
    const value = result[key]
    if (typeof value === 'string' && value.trim().length > 0) {
      const suffix = result[key === 'text' ? 'truncated' : 'truncated'] ? '\n…（已截断）' : ''
      return (value.length > DETAIL_LIMIT ? value.slice(0, DETAIL_LIMIT) + '\n…（已截断）' : value) + suffix
    }
  }

  const clone = { ...result }
  delete clone.base64
  delete clone.image
  if (result.base64) clone.base64 = `<${String(result.base64).length} 字节，已省略；完整图片见 path 字段>`
  const json = JSON.stringify(clone)
  return json.length > DETAIL_LIMIT ? json.slice(0, DETAIL_LIMIT) + '…（已截断）' : json
}

/**
 * 从 assistant/message 事件里抽出正文。
 *
 * 真实载荷（对着本机一份 v4 会话文件核过，不是照文档猜的）：
 *   { type:'assistant/message', seq, data:{ turn, step, message:{ role, content:[...] } } }
 * 其中 content 是块数组，只有 type === 'text' 的块是给用户看的正文，
 * `reasoning` 块是思维链 —— 拿它当回复展示会很难看，所以必须过滤。
 *
 * @param message - data.message
 * @returns 纯正文；没有正文时返回空串。
 */
function extractAssistantText(message) {
  const blocks = Array.isArray(message?.content) ? message.content : []
  return blocks
    .filter(block => block && block.type === 'text' && typeof block.text === 'string')
    .map(block => block.text)
    .join('\n')
    .trim()
}

/** 已经推过的最大 seq，用来去重（同一条消息可能被重放）。 */
let lastPushedSeq = -1

/**
 * 把一条事件推给手机端。
 *
 * 刻意吞掉一切异常：胶囊是锦上添花，绝不能因为它把会话拖慢或拖挂。
 * 超时也给得很短 —— 推不动就不推了。
 *
 * @param kind - 'reply' | 'failure'。
 * @param payload - 事件附加数据。
 */
async function pushEvent(kind, payload) {
  const { base, token } = bridgeConfig()
  try {
    await fetch(`${base}/event`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-fusion-token': token },
      body: JSON.stringify({ kind, ...payload }),
      signal: AbortSignal.timeout(4000),
    })
  } catch {
    // 手机端没起来 / 桥没开 / 超时，一律静默
  }
}

/**
 * 订阅会话事件。
 *
 * 订阅 API 取自 dsh 自身的用法（packages/session/session-persistence-jsonl）：
 *   ctx.on('session/event', (session, event) => { ... })
 *
 * 特意放在 apply() 的最前面：流体云不该依赖 tools.json 是否存在 ——
 * 工具清单缺失时，胶囊仍然应该工作。
 *
 * @param ctx - 宿主上下文。
 */
function subscribeSessionEvents(ctx) {
  if (typeof ctx?.on !== 'function') {
    ctx?.logger?.warn?.('[fusion-android-bridge] 宿主没有 ctx.on，流体云事件通道未接通')
    return
  }
  ctx.on('session/event', (session, event) => {
    try {
      if (!event || typeof event !== 'object') return

      // 子代理会产出大量消息，全推到胶囊会变成刷屏；只关心顶层会话
      if (session && session.parentSession) return

      if (event.type === 'assistant/message') {
        const seq = typeof event.seq === 'number' ? event.seq : -1
        if (seq >= 0 && seq <= lastPushedSeq) return
        const text = extractAssistantText(event.data?.message)
        if (!text) return
        if (seq >= 0) lastPushedSeq = seq
        const interrupted = event.data?.interrupted === true
        void pushEvent('reply', {
          text: text.length > 1500 ? text.slice(0, 1500) + '\n…（已截断）' : text,
          done: true,
          interrupted,
          session: session?.id ?? null,
        })
        return
      }

      if (event.type === 'agent/error') {
        const message = event.data?.message ?? event.data?.error ?? 'agent error'
        void pushEvent('failure', { text: String(message).slice(0, 400) })
      }
    } catch {
      // 事件处理绝不允许把宿主拖下水
    }
  })
  ctx.logger?.info?.('[fusion-android-bridge] 已订阅 session/event，流体云通道就绪')
}

/**
 * 注册一条能力为 DSH 工具。
 * @param ctx - 宿主上下文。
 * @param spec - tools.json 里的一条能力描述。
 */
function registerOne(ctx, spec) {
  const parameters = normalizeParameters(spec.parameters)
  const dangerousNote = spec.dangerous ? '（危险操作：会改变设备状态，调用前请确认用户意图）' : ''

  ctx.tools.register(defineTool({
    name: spec.name,
    description: `${spec.description || spec.title || spec.name}${dangerousNote}`,
    parameters: parameters.properties && Object.keys(parameters.properties).length > 0
      ? Object.fromEntries(
        Object.entries(parameters.properties).map(([key, value]) => [
          key,
          {
            ...value,
            required: Array.isArray(parameters.required) && parameters.required.includes(key),
          },
        ]),
      )
      : {},

    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        required: true,
        properties: {
          ok: { type: 'boolean', required: true, description: '这条能力是否执行成功。' },
          summary: { type: 'string', required: true, description: '面向模型的结果摘要。' },
          channel: { type: 'string', required: true, description: '实际生效的通道：accessibility/shizuku/root/app。' },
        },
      },
      render: (_args, value) => [{ type: 'text', text: value.summary }],
    },

    async execute(args) {
      const result = await invoke(spec.name, args)
      return {
        ok: true,
        summary: summarize(result),
        channel: String(result?.channel ?? spec.channel ?? 'app').toLowerCase(),
      }
    },

    presentCall: args => ({
      card: 'generic',
      title: spec.title || spec.name,
      kind: 'other',
      rawInput: args,
    }),
  }))
}

/**
 * 插件入口。
 * @param ctx - 宿主上下文，`ctx.tools` 用于注册工具。
 */
export function apply(ctx) {
  // 先接事件通道：胶囊的可用性不该取决于工具清单在不在
  subscribeSessionEvents(ctx)

  const manifest = loadManifest()
  if (!manifest) {
    ctx.logger?.warn?.(
      '[fusion-android-bridge] 找不到 tools.json —— 设备工具本次不会注册。'
      + '在 DSH Fusion 应用里重新装配一次即可生成。',
    )
    return
  }

  let registered = 0
  for (const spec of manifest.tools) {
    try {
      registerOne(ctx, spec)
      registered += 1
    } catch (error) {
      ctx.logger?.warn?.(`[fusion-android-bridge] 注册 ${spec?.name} 失败：${error?.message ?? error}`)
    }
  }
  ctx.logger?.info?.(
    `[fusion-android-bridge] 已挂载 ${registered}/${manifest.tools.length} 个 Android 能力`
    + `（生成于 ${manifest.generatedAt ?? '未知时间'}）`,
  )
}
