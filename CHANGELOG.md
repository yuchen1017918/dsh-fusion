# 变更日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] — 2026-10-11

首个版本。把社区里 15 个 DSH 安卓方案的长处收进一个 APK。
逐条对照见 [docs/FUSION.md](docs/FUSION.md)。

### 新增

**运行时内核**
- 内置 bionic **Node.js 24.18.0**（arm64-v8a + x86_64 双 ABI），满足 dsh 要求的 `^22.19 || >=24`
- 内置 **PRoot 5.1.107.96**，可跑完整 Ubuntu 22.04 rootfs（apt / gcc / python 可用）
- 两个可执行文件伪装成 `lib*.so` 落地 `nativeLibraryDir`，绕过 Android 10+ 的 W^X 执行禁令
- `tools/build-runtime.py`：多 ABI 运行时流水线，含 SONAME 归一与 `DT_NEEDED` 改写

**双后端引擎**
- 「原生直跑」与「Ubuntu 容器」两种跑法共用同一份 Node 与同一份内核，切换不重装
- `EngineSupervisor`：从 stdout 嗅探真实监听地址、看门狗自动拉回、后端热切换
- 可调 V8 堆上限（默认 512 MB）、可选 `--expose-internals`

**设备能力**
- 22 个 `android_*` 工具，走无障碍 / Shizuku / Root / 应用自身四条通道，运行时自动择优
- 能力经回环 HTTP 桥（随机 token、仅 `127.0.0.1`）交给 node 侧插件
- 装配时导出 `tools.json` 清单，插件同步注册，不依赖任何构建期耦合

**交互**
- 离线语音唤醒（Vosk，中文小型模型按需下载，识别全在本地）
- 悬浮球桌宠（全 Canvas 绘制，颜色跟随引擎状态）与流体云回复卡
- 前台服务保活 + 控制台日志页（可复制、可仅看错误）

**装配与更新**
- 首启向导、`Provisioner`、多镜像 + 断点续传 + sha256 三级兜底的下载器
- 内核在线更新与一键重装 profile

**设备体检**
- 18 项环境检查：W^X、FUSE 硬链接实测、DSH_HOME、WebView 版本、幻影进程杀手、
  SELinux、电池优化白名单、华为纯净模式、鸿蒙兼容层判断、API 级别……
- 能修的给按钮，不能修的给路径

### 工程

- 包名 `io.dsh.fusion` + 独立 RSA-4096 签名，可与任何其它 DSH 安卓版并排安装
- `tools/verify.sh`：32 项交付自检，含双 ABI 的 ELF 依赖闭包逐库核对
- `tools/adb-androws.sh`：应用宝电脑版的 adb 通道（detect / start / wait / install / launch）
- 7.8 GB 内存机器上可编译：`parallel=false`、`workers.max=2`、Kotlin 编译器内联

### 已知限制

- 端到端启动流程尚未在真机跑通（需要一台 arm64 手机，或在应用宝内装好「ADB 调试助手」）
- HarmonyOS NEXT 需借助兼容层运行；HAP 原生形态未实现
- Ubuntu rootfs / 语音模型 / 内核均为首次使用时联网获取，非开箱离线
