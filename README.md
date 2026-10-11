# DSH Fusion · 融合版 Android

把社区里 15 个 DSH 安卓方案的长处收进一个 APK：**预置运行时、完整 Linux 环境、离线语音、全设备控制、自更新**，
一套代码两条运行时路线，装机即用。

| | |
|---|---|
| 包名 | `io.dsh.fusion`（独立包名 + 独立签名，可与任何其它 DSH 安卓版**并排安装**） |
| 版本 | 1.0.0 (versionCode 1) |
| 支持 | Android 8.0+（minSdk 26）/ targetSdk 34 / compileSdk 35 |
| ABI | `arm64-v8a`（真机）· `x86_64`（应用宝电脑版、x86 模拟器） |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`（约 87 MB，含两套内置运行时） |
| 签名 | `keystore/dsh-fusion.jks`，SHA-256 `55:F3:AF:65:08:81:90:35:CE:F0:DF:F6:0F:F0:97:10:D2:91:A6:13:A2:16:BB:BE:B7:9C:22:E0:BF:F5:83:AB` |

---

## 一、这个东西解决什么问题

桌面上的 DSH 要跑起来，得先有 Node、再装内核、再配环境。搬到手机上，这三步每一步都能卡死人。
融合版的做法是：**把最硬的那部分（运行时）焊进 APK，把最灵活的那部分（内核与插件）留给网络**。

- **装完即用**：bionic Node 24.18 + PRoot 5.1.107 直接躺在 APK 里，首次打开不需要下载任何运行时；
- **两条跑法**：同一个内核，既能在 bionic 上原生直跑（快、省内存），也能塞进 PRoot 的 Ubuntu rootfs 里跑（apt / gcc / python 全在）；
- **模型的手**：22 个 `android_*` 工具，走无障碍（免授权）、Shizuku（免 root 系统 shell）、Root 三条通道，谁在用谁；
- **看得见**：设备体检把十几个「会死人的地方」逐条摆出来，红的给按钮，黄的给指引。

---

## 二、架构

```
                        ┌──────────────────────────────────────────┐
   WebView ← 官方 DSH   │  app : UI 壳（启动向导 / 控制台 / 设置 / 体检）
      Web UI            ├──────────────────────────────────────────┤
                        │  service : EngineService（前台保活 + 看门狗）
                        └──────────────────────────────────────────┘
                                            │
        ┌───────────────────────────────────┼───────────────────────────────────┐
        │                                   │                                   │
┌───────▼────────────┐            ┌─────────▼──────────┐             ┌──────────▼─────────┐
│  :engine           │            │  :devicectl        │             │  :voice  :overlay  │
│  运行时内核         │◄───────────│  设备控制域         │             │  离线唤醒 / 桌宠    │
│                    │  能力总线   │                    │             │                    │
│  FusionPaths       │  ToolBus   │  Shizuku 通道       │             │  Vosk 离线唤醒      │
│  EngineSupervisor  │            │  无障碍通道         │             │  悬浮球桌宠         │
│  ProotLauncher     │            │  Root 通道          │             │  流体云回复卡       │
│  Provisioner       │            │  22 个 android_* 工具│             │                    │
│  BridgeServer      │            │  CompatDoctor 体检  │             │                    │
│  KernelUpdater     │            │                    │             │                    │
└────────┬───────────┘            └────────────────────┘             └────────────────────┘
         │ ① 启动引擎（环境变量注入桥地址与 token）
         │ ② 内核通过 HTTP 回环桥反向调用设备能力
┌────────▼──────────────────────────────────────────────────────────────────────────────┐
│  bionic Node 24.18 (libnode.so)  →  @deepseek-ai/dsh 0.2.0-rc.2  →  web profile        │
│      ├─ 后端 A：原生直跑（nativeLibraryDir 里直接 execve）                              │
│      └─ 后端 B：PRoot 5.1（libproot.so）→ Ubuntu 22.04 rootfs，同一份 Node 绑进 /opt/bionic│
└───────────────────────────────────────────────────────────────────────────────────────┘
```

### 关键接缝：能力桥

手机知道有哪些能力，模型需要在**同步的插件注册点**把它们变成工具。中间这条缝是这样焊的：

1. `ToolBus` 里注册着所有 `android_*` 能力；
2. 装配时把清单导出成 `tools.json`，和桥插件一起铺进 `$DSH_HOME/profiles/web/plugins/dsh-fusion-android-bridge/`；
3. 引擎启动时，`EngineSupervisor` 把桥的 `http://127.0.0.1:<port>` 与随机 token 通过**环境变量**传进去（端口是动态分配的，写死必翻车）；
4. 插件的 `apply()` 同步读 `tools.json`，为每条能力注册一个 `defineTool`；
5. 模型调用 → 插件 `POST /invoke` → 桥校验 token → `ToolBus` 派发 → 结果回传。

桥只绑 `127.0.0.1`，token 每次安装随机生成。**数据不出设备**不是口号，是网络拓扑。

---

## 三、目录与模块

| 模块 | 职责 | 关键文件 |
|---|---|---|
| `:engine` | 运行时内核：路径、引擎监督者、双后端、装配器、HTTP 桥、内核更新 | `EngineSupervisor.kt` `ProotLauncher.kt` `BridgeServer.kt` `provision/*` |
| `:devicectl` | 设备控制：三通道 shell、无障碍、通知读取、22 个工具、设备体检 | `ShellChannel.kt` `AccessibilityBridge.kt` `DeviceTools.kt` `CompatDoctor.kt` |
| `:voice` | 离线唤醒：模型管理 + Vosk 引擎 + 前台服务 | `VoiceModels.kt` `WakeWordEngine.kt` `WakeWordService.kt` |
| `:overlay` | 悬浮球桌宠 + 流体云回复卡（全部 Canvas 绘制，零位图资源） | `PetView.kt` `CloudCapsuleView.kt` `FloatingBallService.kt` |
| `:app` | 界面壳：WebView 主界面、控制台、设置、体检、首启向导、前台服务 | `ui/*.kt` `service/EngineService.kt` `FusionApplication.kt` |
| `tools/` | 运行时流水线、adb 通道、交付自检 | `build-runtime.py` `adb-androws.sh` `verify.sh` |
| `assets/bridge-plugin/` | node 侧的能力桥插件（随 APK 发货） | `index.js` `cordis.patch.yml` |

---

## 四、从零构建

### 1. 环境

```bash
sudo apt install -y openjdk-21-jdk-headless unzip python3-pip
pip install patchelf                       # 改写 SONAME 用
# Android SDK（只装这四个就够，全程不需要 NDK）
#   cmdline-tools / platform-tools / platforms;android-35 / build-tools;35.0.0
# Gradle 8.9（AGP 8.6.1 要求 8.7+）
```

> **不需要 NDK。** 运行时是把现成的 bionic 二进制搬进来 + 改 ELF 元数据，一行 C 代码都不用编。
> 顺带绕开了 WSL2 下 NDK 链接器权限被拒的经典坑。

### 2. 装配运行时（双 ABI）

```bash
python3 tools/build-runtime.py                 # 两个 ABI 都建
python3 tools/build-runtime.py --abis arm64-v8a   # 只建真机用的
```

它做四件事，每件都有理由：

1. 从 Termux 镜像取 `nodejs-lts` / `proot` 及全部传递依赖（清华镜像，约 25 MB/ABI）；
2. 把带版本号的 SONAME（`libicuuc.so.78` / `libcrypto.so.3` …）统一改成 `lib*.so`
   —— **Android 的包管理器只解压形如 `lib*.so` 的文件**，不改名它们根本进不了 `nativeLibraryDir`；
3. 改写所有 ELF 的 `DT_NEEDED` 指向改名后的库，并抹掉 Termux 的 `$PREFIX` rpath；
4. 把两个**可执行文件**伪装成 `.so`（`libnode.so` / `libproot.so`）
   —— Android 10 起禁止从应用私有目录 `execve`，`nativeLibraryDir` 是唯一的免 root 落地点。

产出 `app/src/main/jniLibs/{arm64-v8a,x86_64}/`（13 个 `.so`/ABI）+ `assets/runtime/`（npm tgz、121 个 CA 根证书）。

### 3. 签名与编译

```bash
# 独立 keystore（首次）
keytool -genkeypair -v -keystore keystore/dsh-fusion.jks -alias dsh-fusion \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$DSH_FUSION_STORE_PASSWORD" -keypass "$DSH_FUSION_KEY_PASSWORD" \
  -dname "CN=DSH Fusion, O=DSH Fusion Project, C=CN"

gradle :app:assembleDebug
bash tools/verify.sh          # 交付前自检（含 ELF 依赖闭包）
```

口令优先读环境变量（`DSH_FUSION_STORE_FILE` / `DSH_FUSION_STORE_PASSWORD` / `DSH_FUSION_KEY_ALIAS` / `DSH_FUSION_KEY_PASSWORD`），
其次读本地 `keystore.properties`；两者都没有时退回 debug 签名并**在自检里报红**，不会悄悄发出去。

> **内存**：在 7.8 GB 的机器上编译需要收敛内存 —— `org.gradle.parallel=false`、`--max-workers=2`、
> `kotlin.compiler.execution.strategy=in-process`（少起一个 JVM）、`-Xmx2g`。本机实测峰值后仍余 2.8 GB。
> 打包 200 MB 的 `jniLibs` 是 I/O 密集而非堆密集，真正的风险在 Kotlin 守护进程共存。

---

## 五、测试路径

### A. Windows + 应用宝电脑版

先纠正一个流传很广的说法：**应用宝电脑版不是 Intel Bridge 的 ARM 翻译层，而是 VirtualBox 里的 x86_64 安卓虚拟机。**
实测依据（本机）：

```
D:\program files\Tencent\Androws\Image\Logs\VBox.log
  → VirtualBox VM 6.1.36 r1345 win.amd64
D:\program files\Tencent\Androws\Image\
  → system.vhd / data.vhd / swap.vhd / androws.abox
宿主 CPU：AMD Ryzen 5 5500U（Intel Bridge 本来就只存在于 Intel 平台的 WSA）
```

所以：**只出 arm64 的 APK 在应用宝里装不上。** 这也是本项目出双 ABI 的直接原因。

```bash
tools/adb-androws.sh detect    # 找应用宝自带的 adb、看 VM 与设备状态
tools/adb-androws.sh all       # 起 VM → 等设备 → 安装 → 启动 → 抓日志
```

脚本会自动找到应用宝自带的 `adb.exe`
（本机实测：`D:\program files\Tencent\Androws\Application\5.10.7500.6534\adb.exe`，v33.0.2），
通过 WSL 互操作调起 Windows 侧的 adb server。

> ⚠️ **当前环境的真实状态**：应用宝已安装且能拉起虚拟机（`AndrowsVm.exe` 已运行），
> 但 adb 看不到设备 —— 需要在**应用宝内部**搜索并安装它自带的「ADB 调试助手」，
> 虚拟机才会把 adbd 暴露给宿主 adb。这一步必须人工点，脚本无法代劳。
> 打开调试助手后重新执行 `tools/adb-androws.sh all` 即可。

### B. 真机（华为 / 其它安卓）

```bash
# WSL2 看不到 USB，两条路：
#  ① Windows 侧 usbipd attach 后 adb connect
#  ② 手机开「无线调试」，直接  tools/adb-androws.sh connect <IP>:<端口>
tools/adb-androws.sh install
```

### C. 模拟器

内置 x86_64 运行时，任何 x86_64 的 AVD 直接装即可。

---

## 六、华为 / 鸿蒙

安装前会遇到的三个拦路虎，`CompatDoctor` 里都有对应检测项：

**1. 纯净模式**（EMUI / HarmonyOS 4 及以下）
华为默认开启，会拒绝应用市场之外来源的 APK，提示语还特别含糊（「应用来源未知」）。
体检页会直接给出路径：设置 → 系统和更新 → 纯净模式 → 退出；
再到 设置 → 安全 → 更多安全设置 → 安装外部来源应用 → 允许文件管理器安装。
若应用宝这类分发器仍然拒绝，改用系统文件管理器直装即可绕过。

**2. 鸿蒙的分叉口**
- HarmonyOS 4 及以下：底子是 AOSP，APK 直接可用，体检判绿；
- HarmonyOS NEXT（5.0+）：**移除了 AOSP 兼容层，APK 根本装不上**，只能跑在「卓易通」之类的兼容层里。
  体检通过 `getprop ro.build.version.harmonyos` / `hw_sc.build.platform.version` / `Build.DISPLAY` 综合判断，
  并识别是否处于兼容层（那种情况下 PROOT 后端可能因 ptrace 被 SELinux 拦而失效，
  建议切「原生直跑」）。

**3. 演进方向：HAP**
要在华为应用市场上架需要鸿蒙原生包（HAP），而 HAP 不能复用 APK 的 Android 组件。
**但运行时这一层是可以搬的** —— `tools/build-runtime.py` 产出的是标准 ELF，
鸿蒙的 Native 侧同样能加载；真正要重写的是 UI 壳与无障碍/Shizuku 这些 Android 专有通道。
这是明确的后续工作，不是「以后再想」。

---

## 七、设备体检都查什么

`CompatDoctor` 的 17 项，每一项都对应一个真实会让 DSH 在手机上死掉的阻塞点：

| 项 | 为什么查 |
|---|---|
| CPU 架构 | 内置运行时只有 arm64-v8a / x86_64 |
| 运行时可执行位（W^X） | Android 10+ 禁止从数据目录 exec，必须落在 `nativeLibraryDir` |
| 硬链接支持（FUSE 实测） | `/sdcard` 是 FUSE，不支持 `link()`，会让 dsh 的会话原子写直接失败 |
| DSH_HOME 位置 | 同上；必须在应用私有目录 |
| 可用存储 | 内核 + rootfs 需要约 1.2 GB 余量 |
| WebView 版本 | 老内核会白屏（社区排第一位的问题） |
| 幻影进程杀手 | Android 12–14 在子进程超约 32 个时静默杀进程组，是 Node 运行时的头号暗杀者 |
| SELinux | PRoot 依赖 ptrace，部分加固 ROM 会拦 |
| 悬浮窗权限 | 桌宠 / 流体云的前提 |
| 无障碍服务 | 免 root 手势 / 读屏 / 输入的前提 |
| 通知权限 | Android 13+ 不授权，前台服务通知不显示，用户会以为程序在偷跑 |
| 前台服务策略 | Android 12+ 禁止后台启动前台服务；Android 15 对部分类型有 6 小时上限 |
| V8 堆上限 | 手机内存吃紧，堆开太大直接被系统 OOM 杀 |
| 系统命令通道 | Shizuku / Root / 应用自身，实际生效的是哪条 |
| **华为安装权限 / 纯净模式** | 见上 |
| **鸿蒙兼容层** | 见上 |
| **Android 版本兼容性** | minSdk 26 / targetSdk 34 的实际含义 |

幻影进程杀手可以一键关掉（需要 Shizuku 或 Root）：

```bash
settings put global settings_enable_monitor_phantom_procs false
```

---

## 八、安全与隐私

- 引擎只监听 `127.0.0.1`；能力桥同样只绑回环，且要求 `X-Fusion-Token`（每次安装随机生成）；
- 全部数据落在 `filesDir/fusion/`，卸载即净，不碰用户存储；
- 设备能力按通道分级：无障碍（用户开一次）、Shizuku（用户授权一次）、Root（设备已 root）、应用自身；
- 危险工具在清单里标 `dangerous: true`，描述里会带提示，设置里可要求二次确认；
- 语音唤醒完全离线，模型落盘后不再联网；
- 签名口令不硬编码，走环境变量或未入库的 `keystore.properties`。

---

## 九、首次启动要下载什么（别以为它卡死了）

**内置运行时（Node 24.18 + PRoot 5.1.107）已随 APK 提供，一个字节都不用下载。**
但官方内核与两个可选组件要联网取，而且体积不小 —— 这是首次启动「看起来没反应」的唯一原因。

| 项目 | 体积 | 触发时机 | 必选？ | 在哪看进度 |
|---|---|---|---|---|
| `@deepseek-ai/dsh` 内核 + web profile | **数百 MB**（取决于插件数量） | 首启向导点「开始装配」 | ✅ 必须 | 首启向导 / 控制台页 |
| Ubuntu rootfs（`ubuntu-base-22.04.5`） | **约 30 MB**（展开约 300 MB） | 设置 → 下载与内核 → 「装配」 | ⭕ 可选 | 设置页 / 控制台页 |
| 离线唤醒模型（Vosk 中文小型） | **约 42 MB** | 设置 → 语音 → 「下载」 | ⭕ 可选 | 设置页 / 控制台页 |
| 语音识别 | 0（本地推理） | 唤醒开启后 | — | 控制台页 |

要点：

- 三项全部是**一次下载、之后永久离线**，装完断网也能用；
- 全程进度实时打在「控制台」页 —— **只要还在滚字就是正常的**，界面没有假死；
- 下载中断可重来，npm 的缓存目录会复用，不会从零开始；
- 内核与 rootfs 都走国内镜像（npm 走 npmmirror，rootfs 走清华/中科大/cdimage 三源轮询），
  断源会自动换下一个，并带断点续传与 sha256 校验。

粗算一台手机从点击安装到能对话：**下载时间取决于网速，本地处理约 1–3 分钟**
（解包 rootfs / 解包 npm / 首次启动 V8 与插件树）。

---

## 十、APK 体积优化预案（尚未实施）

当前 87 MB 里有 202 MB 未被压缩的 `jniLibs`（两个 ABI 各 13 个 `.so`）。
压缩后落在 APK 里约 80 MB，是主要体积来源。三条可选的路线，按推荐顺序：

**路线 A：ABI 拆包（推荐，改动最小）**
AGP 自带 `splits { abi { ... } }`，一次构建出两个 APK：
- `app-arm64-v8a-debug.apk` ≈ 45 MB —— 覆盖所有真机（含华为全系）；
- `app-x86_64-debug.apk` ≈ 45 MB —— 只给应用宝电脑版与模拟器。

代价：分发时要选对包（装错了会报 `INSTALL_FAILED_NO_MATCHING_ABIS`）。
收益：真机用户少下 42 MB。

**路线 B：纯 arm64 单包**
按你的判断先出一版纯 arm64：把 `abiFilters` 收成 `arm64-v8a`，同时可以删掉
`app/src/main/jniLibs/x86_64/`。体积直接砍半到约 45 MB。
代价：**应用宝电脑版装不上**，Windows 测试路径要单独出一个 x86_64 包。

**路线 C：瘦身 ICU（收益最大，风险最高）**
`libicudata.so` 单个就 31.6 MB（两个 ABI 合计 63 MB）。
如果换成 Node 的 `small-icu` 构建，这一个文件能省掉，两 ABI 合计省约 63 MB。
代价：需要自己交叉编译 Node（要 NDK），且会丢掉非拉丁语系的完整排序与本地化能力。
**本项目刻意没走这条路** —— 用自编译换取体积，会把「免 NDK、可复现」这个最大优点丢掉。

**另外可以顺手做掉的**：`libicudata.so` 之外，`libnode.so`（53 MB）已是 strip 过的；
`npm-10.9.2.tgz`（2.6 MB）在首次装配后其实可以从 APK 里省掉（改成首启下载），
但那会把「装完即用」的承诺打折，不建议。

---

## 十一、诚实的边界

已经做到的，和还没做到的，分开写：

**已完成并经过静态验证**
- 四个核心模块 + app 全部编译通过，`assembleDebug` 产出 87 MB APK；
- 双 ABI 的 ELF 依赖闭包 100% 自洽（`tools/verify.sh` 逐库检查 `DT_NEEDED`），内置 Node 确认 v24.18.0；
- 独立包名 + 独立 RSA-4096 签名，自检确认不是 debug 证书；
- 应用宝的 adb 通道已定位并打通到「等设备」这一步。

**尚未在真机/虚拟机上跑通**
- 端到端启动流程（需要一台 arm64 手机，或在应用宝内装好「ADB 调试助手」）；
- Ubuntu rootfs 后端（首次使用需联网下载约 30 MB）、离线唤醒（需下载约 42 MB 模型）；
- 内核 npm 安装（首次需下载数百 MB）。

**已知风险**
- `arm64` 设备上首次启动到引擎就绪预计 10–30 秒（PRoot 后端更慢，因为 syscall 翻译）；
  内核安装那几个百 MB 是主要等待来源，进度会实时打到控制台；
- 部分加固 ROM 会拦截 PRoot 的 ptrace，此时请用「原生直跑」后端。
