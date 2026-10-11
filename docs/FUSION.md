# 融合对照表：15 个社区方案 → 本项目的哪一部分

原则：**能共存的不做取舍，不能共存的选一条并说明理由。**
社区里「完整移植派」与「设备控制派」其实不冲突 —— 冲突的只有两种跑法（原生 vs 容器），所以那一条做成可切换的双后端。

## 完整移植派

| # | 项目 | 核心优势 | 融合到哪 | 状态 |
|---|---|---|---|---|
| 1 | **DSH Mobile (Soodok)** | 内置 bionic Node、免 root 免 Termux、装完即用、低内存 | **底座**：`tools/build-runtime.py` 把 Termux 的 bionic Node 24.18 焊进 APK；`FusionPaths` 用 `nativeLibraryDir` 规避 W^X；`RuntimeEnv` 把 V8 堆限制成可调（默认 512 MB） | ✅ 已实现 |
| 2 | **DSHA** | 完整 Ubuntu rootfs、proroot 零 ptrace 开销、Low 兼容版 | **后端 B**：`ProotLauncher` + `UbuntuProvisioner`。关键手法与它同源但更进一步 —— 不把 Node 装进 rootfs，而是把 `nativeLibraryDir` 绑进客人视角的 `/opt/bionic`，于是**两个后端共享同一份 Node 与同一份内核**，切换不重装 | ✅ 已实现 |
| 3 | **DSHA 二开版 (xliaoy)** | UI 美化、悬浮胶囊底栏、内核在线更新、源一键切换 | 底栏做成自定义胶囊（`MainActivity.buildBottomBar`）；内核更新走 `KernelUpdater`（**改用 npm registry 而非 GitHub Releases** —— dsh 本来就只从 npm 发布，且国内可直连）；npm 源可一键切 npmmirror/官方 | ✅ 已实现 |
| 4 | **DSHM (RochelimitDawn)** | 高颜值原生 UI、运行时在线下载、断点续传 + sha256 + GHProxy 三网优选、插件装配验证 | `HttpDownloader`：多镜像轮询 + Range 断点续传 + sha256 三级兜底（单文件 `.sha256` → `SHA256SUMS` → 内置已知值）；`BridgeRouter /health` 与 `/capabilities` 就是装配后的验证端点 | ✅ 已实现 |
| 5 | **dsh-mobile (Thanksgiver233)** | Kotlin 原生、Foreground Service 保活、弱网重连 | 全 Kotlin；`EngineService` 前台保活（specialUse）；`EngineSupervisor` 看门狗：进程掉线自动拉回，连续 5 次失败就停下并把原因摊开 | ✅ 已实现 |
| 6 | **HDSH (HarmonyOS)** | 双形态界面、多核心版本并存切换/回滚、jitless 上架友好 | 手机形态用原生壳 + WebView（等价于它的 RemoteShell 路线）；`KernelUpdater.available()` 列出全部版本可任意切换，`$DSH_HOME` 不动所以会话与插件都在。**HAP 形态未实现，见 README「演进方向」** | 🟡 部分 |
| 7 | **DSHBox (WSK-build)** | DSH + PRoot + Debian + 终端 + 文件管理器，一个 APK 一个工作台 | 运行时 + PRoot + 终端（控制台页）+ 文件工具（`android_file_*` 四件套）都有了；控制台支持复制/仅错误过滤，方便当终端用 | ✅ 已实现 |
| 8 | **paopaocat** | 内置 321 专家 + 12 技能、完全离线、仅 arm64 | 融合版**保留了双 ABI**（离线可用是运行时的事，不必牺牲兼容性）；专家/技能属于内核侧内容，安装后从插件市场按需拉 | ➖ 不做 |
| 9 | **Tongyu3 NO-ROOT** | 一键安装、首启自动下载解压约 150MB Ubuntu | `Provisioner.provisionRootfs()` 一键装配，官方 `ubuntu-base-22.04.5` + sha256 校验 + 自动写 DNS 与 apt 源 | ✅ 已实现 |

## 设备控制派

| # | 项目 | 核心优势 | 融合到哪 | 状态 |
|---|---|---|---|---|
| 10 | **Luwi** | 离线语音唤醒、锁屏可唤醒、全权限设备工具 | `:voice` 模块：Vosk 离线识别 + `VoiceModels` 模型按需下载（约 42 MB，落盘后永不联网）；唤醒判定走**部分结果**（不等整句断完）；中文叠词做宽松匹配 + 编辑距离兜底 | ✅ 已实现 |
| 11 | **dphmoblie** | Ubuntu + Shizuku + 无障碍自动化、双通道 | `ShellRouter` 三通道（Shizuku / Root / 应用自身）自动择优；`AccessibilityBridge` 独立成第二条路。**能用无障碍完成的事就不惊动 shell** | ✅ 已实现 |
| 12 | **guzhou079-arch** | 悬浮球桌宠、流体云回复卡、11 款技能、手机自动化 | `:overlay` 模块：`PetView`（径向渐变 + 呼吸光晕 + 状态环，全 Canvas 零位图）、`CloudCapsuleView`（顶部胶囊，点开成卡片）、快捷菜单；手机自出包能力由 `tools/` 三个脚本承担 | ✅ 已实现 |
| 13 | **Chiyang001/DSHAN** | 应用进程内嵌 Node、Shizuku 提供 `android_*` 工具、Root 双模式、内核可切换 | **命名沿用它的 `android_*` 前缀**，已有提示词与技能可直接复用；`ShizukuBackend` 用 `IShizukuService.newProcess` 拿系统 shell；`KernelUpdater` 换内核不动会话 | ✅ 已实现 |
| 14 | **woaiys3** | AI 浏览器、免 root 操作手机、文件编辑 | `android_open_url` + WebView 内导航策略（回环与本地留应用内，外链交系统）；文件四件套（list/read/write/search） | ✅ 已实现 |
| 15 | **YOYOFeelings** | Termux 快照、SAF 目录桥、加固的引擎看门狗、应用内更新 | 看门狗（`MAX_WATCHDOG_RESTARTS=5`，状态机含 DEGRADED 中间态）；应用内更新由 `KernelUpdater` + `SettingsActivity` 承接。**SAF 目录桥未采用** —— 模型要的是路径而不是 Uri，改用 `MANAGE_EXTERNAL_STORAGE`（旁加载应用，用户可单独授权） | 🟡 替代方案 |

## 融合的取舍（三条，都写清楚理由）

1. **不做「谁替代谁」，做「谁在就谁上」**
   Shizuku / Root / 无障碍 / 应用自身——四条路同时存在，`ShellRouter` 与 `AccessibilityBridge` 运行时择优。
   这与 Luwi（要 Shizuku 或 Root）和 woaiys3（可选）的分歧不冲突：能力全有，用户按自己设备条件享受。

2. **HTTP 回环桥而不是进程内 AIDL**
   node 侧插件与 Kotlin 侧跨语言，进程内桥要么写 JNI（要 NDK），要么嵌 Node（要改内核）。
   回环 HTTP 零依赖、可单独探活（`/health`）、可单独调试（curl 就能调），代价是一次本地 TCP 往返 —— 在工具调用这个量级上完全可忽略。

3. **不做 Compose，原生壳全程序化绘制**
   这个应用真正的界面是 WebView 里的官方 DSH Web UI，原生侧只有几个壳页面。
   上 Compose 会引入一整套编译器插件与依赖，而收益只是几个壳页面 —— 不划算。

## 没有融合的东西（诚实列出）

- **HAP（鸿蒙原生包）**：运行时那层可搬，UI 壳与无障碍/Shizuku 通道要重写。
- **内置专家库 / 技能包**：属于内核侧内容，走插件生态而不是焊进 APK，否则每次更新都要发版。
- **SAF 目录桥**：见上表 #15。
