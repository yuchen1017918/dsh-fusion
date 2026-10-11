/**
 * dsh-fusion-android-bridge —— 手机能力 ⇄ DSH 工具的翻译层。
 *
 * 设计要点（每一条都是被 Android 的现实逼出来的）：
 *  · 工具清单在装配时落成 tools.json，启动同步读取 —— apply() 是同步的，
 *    在这里做网络发现会把宿主启动拖慢，而且失败就是「工具全没了」。
 *  · 一切经由 127.0.0.1 的回环桥转发，带 token 鉴权；手机数据不出设备。
 *  · 返回值统一收敛成 { ok, summary, detail } 三元组：截图这类工具的 base64
 *    有几百 KB，直接塞进上下文能把一次对话撑爆，所以这里做了尺寸裁剪。
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
