/**
 * DSH Fusion · Android 原生语音识别后端
 *
 * ── 为什么需要它 ────────────────────────────────────────────────
 * 官方的语音栈是这一串：
 *
 *   dsh-experimental-voice-input-bundle
 *     ├── dsh-experimental-speech-to-text            抽象层（提供 ctx.speechToText）
 *     ├── dsh-experimental-client-ui-voice-input     UI（零依赖，能用）
 *     ├── dsh-experimental-api-speech-to-text        云端后端（能用）
 *     └── dsh-experimental-speech-to-text-sensevoice 本地后端 ← ❌ 在 Android 上跑不了
 *
 * SenseVoice 那个后端依赖 `sherpa-onnx-node`，而 sherpa 的
 * optionalDependencies 只有六个平台：
 *
 *     sherpa-onnx-darwin-arm64 / darwin-x64 / linux-x64 / linux-arm64 / win-x64 / win-ia32
 *
 * **没有 android / bionic 的任何构建。** 所以它不是「配置错了」，
 * 是在这个平台上根本没有可用的原生库 —— 换多少种包法都装不上。
 *
 * ── 怎么办：不删官方插件，只补一个后端 ──────────────────────────
 * 官方抽象层留了正规入口：`ctx.speechToText.register(provider)`。
 * 所以这里注册一个 Android 后端，把识别交给**设备本地的 Vosk** ——
 * Vosk 有 Android 构建（`vosk-android` 的 jni/arm64-v8a/libvosk.so），
 * 而这个应用本来就带着它（`:voice` 模块的离线唤醒用的就是它）。
 *
 * **官方插件一行不改，官方 UI 照常工作，用户看到的是同一个麦克风按钮。**
 *
 * ── provider 契约（照官方抽象层实现）──────────────────────────
 *   info.id          唯一标识
 *   info.languages   必须包含被选中的语言，否则 selectedProvider 会拒
 *   preparation?     可选的准备状态（我们要下载模型，所以实现了）
 *   transcribe({audio, language}, signal) → 转写结果
 *
 * `audio` 是 **WAV 字节**（官方 sensevoice 后端就是 `Buffer.from(input.audio)`
 * 配上 `content-type: audio/wav` 发的）。
 */
export const name = 'dsh-fusion-speech-android'
export const inject = ['speechToText']

/** 桥的地址与 token 由宿主注入（与 bridge-plugin 同一套）。 */
const BRIDGE_URL = (process.env.DSH_FUSION_BRIDGE_URL || 'http://127.0.0.1:7310').replace(/\/+$/, '')
const BRIDGE_TOKEN = process.env.DSH_FUSION_BRIDGE_TOKEN || ''

const PROVIDER_ID = 'fusion-android-vosk'

/**
 * 调原生。走的是本项目既有的能力桥 —— 音频不出设备，
 * 连回环之外的网络都不用。
 */
async function invoke(id, args) {
  const response = await fetch(`${BRIDGE_URL}/invoke`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(BRIDGE_TOKEN ? { 'x-fusion-token': BRIDGE_TOKEN } : {}),
    },
    // 字段名必须与原生侧一致：BridgeRouter 读的是 body.id。
    // 写成 name 的话原生会报「缺少 id」，而错误信息不会告诉你是哪个字段写错了。
    body: JSON.stringify({ id, args }),
  })
  if (!response.ok) throw new Error(`桥返回 ${response.status}`)
  const payload = await response.json()
  if (payload && payload.ok === false) throw new Error(payload.error || '原生调用失败')
  return payload?.result ?? payload
}

export function apply(ctx, config) {
  // ── 准备状态：模型要下载（约 42 MB），UI 要能显示进度 ──
  const listeners = new Set()
  let phase = { phase: 'idle' }
  const setPhase = (next) => {
    phase = next
    for (const l of listeners) {
      try { l() } catch (e) { ctx.logger?.warn?.(`[fusion-speech] 监听器抛错：${e?.message ?? e}`) }
    }
  }

  const preparation = {
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    snapshot: () => phase,
    prepare() {
      // 不在这里阻塞下载 —— 交给 transcribe 首次调用时按需拉，
      // 免得用户只是打开设置页就把 42 MB 流量吃掉
      setPhase({ phase: 'ready' })
    },
    async cancel() { setPhase({ phase: 'idle' }) },
  }

  const provider = {
    info: {
      id: PROVIDER_ID,
      // Vosk 的中文小模型 + 多语种模型都支持；这里如实列出我们实际能跑的
      languages: ['zh', 'zh-CN', 'auto'],
      label: '设备本地（Vosk）',
      // 让 UI 能说明「这是离线识别」
      local: true,
      requiresDownload: true,
    },
    preparation,
    async transcribe({ audio, language }, signal) {
      signal?.throwIfAborted?.()
      setPhase({ phase: 'transcribing' })
      try {
        // audio 是 WAV 字节 → base64 过桥。
        // 几秒的 16 kHz 单声道 WAV 大约几十到几百 KB，桥和 base64 都吃得下。
        const wav = Buffer.from(audio).toString('base64')
        const result = await invoke('android_voice_transcribe', { wav, language: language || 'auto' })
        signal?.throwIfAborted?.()
        setPhase({ phase: 'ready' })
        // 返回形状照官方抽象层的约定：一个带 text 的结果
        return { text: String(result?.text ?? ''), providerId: PROVIDER_ID }
      } catch (e) {
        setPhase({ phase: 'ready' })
        // 失败要说清是哪一层失败 —— 桥不通和模型没下是两回事，
        // 而 UI 只会显示错误信息本身
        throw new Error(`设备本地识别失败：${e?.message ?? e}`)
      }
    },
  }

  ctx.effect(() => ctx.speechToText.register(provider))
  ctx.logger?.info?.(`[fusion-speech] 已注册设备本地识别后端（${PROVIDER_ID}）`)
}

export default { name, inject, apply }
