# dsh-fusion-speech-android

Android 原生语音识别后端 —— **官方插件一行不改**，只走官方留的注册点。

## 它解决什么

官方语音栈里的本地后端是 `dsh-experimental-speech-to-text-sensevoice`，
它依赖 `sherpa-onnx-node`。而 sherpa 的 `optionalDependencies` 只有六个平台：

```
sherpa-onnx-darwin-arm64  sherpa-onnx-darwin-x64
sherpa-onnx-linux-x64     sherpa-onnx-linux-arm64
sherpa-onnx-win-x64       sherpa-onnx-win-ia32
```

**没有 android / bionic 的任何构建。** 所以它在 Android 上不是「配置不对」，
是**根本没有可用的原生库** —— 换多少种打包方式都装不上。

对照：**Vosk 提供 Android 构建**（`vosk-android` 的 `jni/arm64-v8a/libvosk.so`），
而这个应用本来就带着它（离线唤醒用的就是 Vosk）。

## 怎么做的

```
官方栈（保留，不改）                          我们加的
─────────────────────────────────           ────────────────────
voice-input-bundle
  ├── speech-to-text            ← 抽象层 ──▶ ctx.speechToText.register(provider)
  ├── client-ui-voice-input     ← UI（零依赖，照常工作）
  ├── api-speech-to-text        ← 云端后端（可选）
  └── speech-to-text-sensevoice ← ❌ Android 上加载不了 sherpa
                                             dsh-fusion-speech-android
                                               transcribe() → 能力桥 → 原生 Vosk
```

**用户看到的是同一个麦克风按钮**，只是识别改在设备本地跑。

## provider 契约（照官方抽象层实现）

```js
{
  info: { id, languages, ... },
  preparation?: { subscribe, snapshot, prepare, cancel },
  async transcribe({ audio, language }, signal) → { text }
}
```

`audio` 是 **WAV 字节** —— 官方 sensevoice 后端就是
`Buffer.from(input.audio)` + `content-type: audio/wav` 发的，照它的形状接。

## 音频去哪了

**不出设备。** 通过 `127.0.0.1` 的回环能力桥发给原生 Vosk，
桥本身带 token 鉴权 —— 与本项目其它原生能力同一条通道。

## 许可证

MIT。参考的社区实现：`dsh-voice-scribe`（MIT）· `dsh-ears`（MIT）·
`dsh-voice`（MIT）· `dsh-talk`（MIT）。
