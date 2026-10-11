# DSH Fusion · 融合版 Android

把社区里 15 个 DSH 安卓方案的长处收进一个 APK：**预置运行时、完整 Linux 环境、离线语音、全设备控制、自更新**，
一套代码两条运行时路线，装机即用。

| | |
|---|---|
| 包名 | `io.dsh.fusion`（独立包名 + 独立签名，可与任何其它 DSH 安卓版**并排安装**） |
| 版本 | 1.0.0 (versionCode 1) |
| 支持 | Android 8.0+（minSdk 26）/ targetSdk 34 / compileSdk 35 |
| ABI | `arm64-v8a`（真机）· `x86_64`（应用宝电脑版、x86 模拟器） |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`（约 108 MB，含两套运行时 + 预装内核） |
| 签名 | `keystore/dsh-fusion.jks`，SHA-256 `55:F3:AF:65:08:81:90:35:CE:F0:DF:F6:0F:F0:97:10:D2:91:A6:13:A2:16:BB:BE:B7:9C:22:E0:BF:F5:83:AB` |

---

> **第一次用？直接跳到 [十七、上手指南](#十七上手指南第一次用看这一节)。**

## 一、这个东西解决什么问题

桌面上的 DSH 要跑起来，得先有 Node、再装内核、再配环境。搬到手机上，这三步每一步都能卡死人。
融合版的做法是：**把最硬的那部分（运行时）焊进 APK，把最灵活的那部分（内核与插件）留给网络**。

- **装完即用**：bionic Node 24.18 + PRoot 5.1.107 直接躺在 APK 里，首次打开不需要下载任何运行时；
- **两条跑法**：同一个内核，既能在 bionic 上原生直跑（快、省内存），也能塞进 PRoot 的 Ubuntu rootfs 里跑（apt / gcc / python 全在）；
- **模型的手**：27 个 `android_*` 工具，走无障碍（免授权）、Shizuku（免 root 系统 shell）、Root 三条通道，谁在用谁；
- **看得见**：设备体检把十几个「会死人的地方」逐条摆出来，红的给按钮，黄的给指引。

---

## 设备需求

### 一句话

| | **最低** | **推荐** |
|---|---|---|
| Android 版本 | **8.0（API 26）** | 12 及以上 |
| CPU 架构 | **arm64-v8a** | 同左 |
| 整机内存 | 3 GB | **6 GB 及以上** |
| 可用存储 | **1.2 GB** | 2 GB 及以上 |
| 系统 WebView | 100+ | 最新（应用商店可更新） |

### 存储到底花在哪（实测，不是估的）

| 阶段 | 占用 | 说明 |
|---|---|---|
| **安装** | **约 209 MB** | APK 自身 108 MB ＋ **安装时解出的 arm64 原生库 101 MB**（`extractNativeLibs=true`，两个都要占盘） |
| **首启装配** | **再 +131 MB** | 官方内核树解压（12814 个文件） |
| 基础合计 | **约 340 MB** | 到这步就能正常对话了 |
| 可选：Ubuntu 容器后端 | +300 MB | 下载只要 30 MB，展开约 300 MB |
| 可选：本地大模型 | +36 MB 运行时 | 下载 11 MB |
| 可选：本地模型（0.5B/1.5B/3B） | +0.4 / 1.1 / 1.9 GB | 见 [十六节](#十六在手机上跑本地大模型可选按需下载) |

> **为什么按磁盘算比文件大小更大**：内核树有 12814 个文件，其中大量是小文件，
> 文件系统按块分配 —— 文件字节合计 131 MB，落到磁盘上约 169 MB。
> 上面的「约 340 MB」是按文件字节算的**下限**，实际预留请按 400 MB 看。

**1.2 GB 这条线是怎么来的**：首启解压需要「解压后体积 × 1.5」的余量
（131 MiB × 1.5 ≈ **197 MB**），加上必须给系统留出缓冲 —— 应用里的「设备体检」在
可用空间低于 **400 MB 时直接拦截**、低于 **1.2 GB 时给黄色警告**，用的就是这条线。

> 这个余量在应用里是**动态算的**（读内核包的清单），不是写死的常量 ——
> 它随内核包体积变化，写死必然过期。

### 内存

| 组件 | 占用 |
|---|---|
| Node 引擎（V8 堆默认 512 MB，可在设置里调 128–3072） | 通常 300–500 MB |
| 官方内核 + 插件 | 视装了多少插件而定 |
| **建议整机内存** | **6 GB 及以上** |

低于 6 GB 也能跑，但要留意：安卓会优先杀内存占用大的后台应用，
而这个应用的常驻部分（引擎 + 内核）本身就接近 400 MB。
打开「设置 → 引擎」把 V8 堆调小能缓解，代价是长上下文容易失败。

**本地大模型是另一套账**，比上面高得多：

| 模型 | 建议整机内存 |
|---|---|
| Qwen2.5 0.5B | ≥3 GB |
| Qwen2.5 1.5B | ≥6 GB |
| Qwen2.5 3B | ≥8 GB |

模型内存不够的表现是**被系统直接杀掉**，而用户只会看到「闪退」——
所以应用里会按你的实际内存推荐档位，别硬上。

### 关于 x86_64

APK 里同时带了 `arm64-v8a` 与 `x86_64` 两套运行时，所以**在 x86_64 的模拟器或
应用宝电脑版上也能跑**。但要注意两件事：

1. **本地大模型只支持 arm64。** 我们用的 `llama-cpp` 只有 aarch64 的包，
   x86_64 上没有对应产物 —— 在 x86_64 上「下载运行时」这一步会失败。
   用云端模型不受影响。
2. x86_64 那一套主要是为了「电脑上预览」，真机体验以 arm64 为准。

### 不满足会怎样

应用会在能提前发现的地方把话说明白，而不是让你对着黑屏猜：

| 缺什么 | 应用的表现 |
|---|---|
| 架构不是 arm64 | 装配时直接报「运行时缺失：APK 里没有本机架构的 libnode.so」 |
| 存储不足 | 「设备体检」红色拦截；首启解压前会明确告诉你还差多少 MB |
| WebView 版本低 | 首启弹提示「系统 WebView 版本偏低，建议升级」 |
| 内存不足（本地模型） | 模型选择器按实际内存推荐；装大了会被系统杀掉 |

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
│  Provisioner       │            │  27 个 android_* 工具│             │                    │
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

### 引擎监听在哪个端口？

**不是固定端口，而且默认就不是固定值。**

| 场景 | 行为 |
|---|---|
| 首选端口 = 0（默认语义） | 传 `--port 0`，**由操作系统在 listen 那一刻原子分配** |
| 用户显式指定了端口 | 从它起扫 128 个，用第一个空闲的 |
| 那 128 个都被占用 | **退回 0**（而不是硬闯一个已知被占用的端口） |
| 指定端口启动时撞上 `EADDRINUSE` | 自动改用 0 重试一次，并在状态里显示「端口被占用，正在换端口重试…」 |

**为什么默认让操作系统分配**：先探测空闲端口、再把它交给引擎，这两步之间有一个窗口
（TOCTOU 竞态）—— 别的进程完全可能在这个窗口里抢走它。传 0 则没有窗口：
端口是内核 `listen` 成功之后才确定的。而内核**明确支持**这样做
（`dsh-host-webserver` 的注释原话：*"the OS-assigned value when config.port is 0"*），
它打印的 URL 读的是 `ctx.webServer.port` —— **真实端口而不是我们传进去的 0**，
所以从 stdout 解析地址这条路不受任何影响。

> 实测确认过一件事：**内核自己不会退让**。给它一个被占用的端口，它直接
> `EADDRINUSE` 崩掉，不会自己换一个。所以端口冲突的全部责任在我们的选择逻辑上。
> 实现见 [`PortPicker.kt`](engine/src/main/java/io/dsh/fusion/engine/runtime/PortPicker.kt)，
> 三条行为由 5 个单测钉住。设置入口在「设置 → 引擎 → 监听端口」。

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
| `:engine` | 运行时内核：路径、引擎监督者、双后端、装配器、HTTP 桥、内核更新、**安全模式** | `EngineSupervisor.kt` `ProotLauncher.kt` `BridgeServer.kt` `provision/*` `plugin/SafeMode.kt` |
| `:devicectl` | 设备控制：三通道 shell、无障碍、通知读取、24 个工具、设备体检（18 项） | `ShellChannel.kt` `AccessibilityBridge.kt` `DeviceTools.kt` `CompatDoctor.kt` |
| `:voice` | 离线唤醒：模型管理 + Vosk 引擎 + 前台服务（2 个工具） | `VoiceModels.kt` `WakeWordEngine.kt` `WakeWordService.kt` |
| `:overlay` | 悬浮球桌宠 + 流体云回复卡（全部 Canvas 绘制，零位图资源；1 个工具） | `PetView.kt` `CloudCapsuleView.kt` `FloatingBallService.kt` |
| `:app` | 界面壳：WebView 主界面、控制台、设置、体检、首启向导、前台服务 | `ui/*.kt` `service/EngineService.kt` `FusionApplication.kt` |
| `tools/` | 运行时流水线、adb 通道、交付自检 | `build-runtime.py` `adb-androws.sh` `verify.sh` |
| `assets/bridge-plugin/` | node 侧的能力桥插件（随 APK 发货） | `index.js` `cordis.patch.yml` |
| `assets/plugins/` | **随包安装的本地插件**（首启铺进 profile 并注册进 bundles） | `dsh-mobile-layout/*` |
| `plugins/` | 随包插件的**源码**（`assets/` 下那份是它的副本） | `dsh-mobile-layout/lib/client.js` |
| `assets/host/` | 宿主脚本与 carrier | `fusion-host.mjs` `desktop-carrier.js` |

> 三个工具模块合计 **27 个 `android_*` 工具**（设备 24 + 语音 2 + 悬浮球 1），
> 全部经能力桥注册给引擎。

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

`CompatDoctor` 的 18 项，每一项都对应一个真实会让 DSH 在手机上死掉的阻塞点：

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

## 九、装完就能用（预装内核包）

**这个版本不需要联网装配。** 官方内核已经随 APK 发货，首次启动只做一次本地解压。

### 内核包是怎么来的

`tools/build-kernel-bundle.py` 在构建期把官方内核预装好、裁干净、压成一个包：

| 阶段 | 体积 | 说明 |
|---|---|---|
| 双树安装（原来应用首启干的事） | 942 MB | 启动器一套 + profile 一套，**重复** |
| 单树安装（启动器变成 profile 依赖） | 501 MB | pnpm 去重，省 441 MB |
| 裁剪后 | **131 MB** | 砍掉 3 类用不上的东西 |
| **xz -6 压缩** | **21.2 MB** | 进 APK 的最终体积 |

裁剪明细（每一步都有理由，报告会打印出来）：

| 剔除项 | 体积 | 为什么 |
|---|---|---|
| LibreOffice wasm（`soffice.wasm` + `.data`） | 145 MB | 文档转换能力。手机跑 122 MB 的 wasm LibreOffice 意义有限，且它是超 GitHub 单文件硬限制的元凶。要保留加 `--keep-office` |
| sourcemap / `.d.ts` | 81 MB | 运行期完全不需要 |
| 外平台原生库（linux-x64 / win32 / darwin） | 70 MB | Android 是 arm64/x86_64 bionic，这些一个都加载不了 |
| 文档 / 示例 / Windows 二进制 | 12 MB | 用不上 |

包里含：`@deepseek-ai/dsh`、`dsh-base`、`dsh-web-app`，**以及 pnpm** —— 官方插件安装页底层就是 pnpm，不带的话用户解完包插件页依然点不动。

### 首次启动会发生什么

1. 检查 `files/fusion/profiles/web` 是否已就位（标记文件 + 关键路径抽查，两者都过才认）；
2. 不够就先查空间（需要约 190 MB 余量，不够会明确告知差多少）；
3. 解压到临时目录，**完成后原子改名**就位 —— 中途被杀不会留下半残的 profile；
4. 铺入我们自己的能力桥插件（官方内核里没有它，设备工具与流体云全靠它）；
5. 起引擎。

向导页会显示进度条与「正在初始化本地环境，预计需要 30 秒到 1 分钟，请勿关闭应用」。

**判断有没有卡死**：切到「控制台」页，日志在滚就是正常。装完之后**完全不需要联网**，飞行模式下也能启动。

### 两个版本

| 版本 | 构建命令 | 体积 | 首启 |
|---|---|---|---|
| **完整版**（推荐） | `gradle :app:assembleDebug` | ≈108 MB | 本地解压，零联网 |
| 精简版 | `gradle :app:assembleDebug -PwithKernelBundle=false` | ≈87 MB | 联网 npm 安装（数百 MB） |

精简版是给 **x86_64 预览版（应用宝电脑版）** 用的：内核包是按 arm64 Android 裁的，
塞给 x86_64 反而装不上。所以那种场合刻意不带。

### 仍然需要联网的（可选）

| 项目 | 体积 | 什么时候 |
|---|---|---|
| Ubuntu rootfs | 约 30 MB | 想用「Ubuntu 容器」后端时 |
| 离线语音唤醒模型 | 约 42 MB | 想用语音唤醒时 |

这两个都是可选的，不做也能正常对话。

## 十、APK 体积优化预案（完整版已落地）

当前约 108 MB 里有 **220 MB 未被压缩的 `jniLibs`**（两个 ABI 各 17 个 `.so`：
15 个我们的运行时——含 Node、ICU、PRoot 与两个 loader——加 2 个来自语音模块的
`libvosk.so` / `libjnidispatch.so`）。压缩后落在 APK 里约 77 MB，是主要体积来源。
三条可选的路线，按推荐顺序：

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

## 十一、形态定位与「桌面版架构」的真实障碍

### 先破一个混淆：「桌面版」不是「原生 UI 重写」

DSH 官方的**桌面版本质上也是套壳**——Electron 加载官方 Web UI，preload 注入宿主能力，
一行 UI 都没重写：

```
apps/desktop/src/web-document.ts   → 提供 index 文档（官方 Web dist）
apps/desktop/src/preload-app.ts    → 注入 dshDesktop carrier
apps/desktop/src/host-process.ts   → 起一个 dsh-desktop-host 子进程
```

所以「套壳 vs 桌面版」这个说法本身是错位的。真实的选择是**三个独立的轴**：

| 轴 | 选项 A | 选项 B | 本项目的判断 |
|---|---|---|---|
| **① UI 来源** | 官方 Web UI（WebView 渲染） | 原生 Kotlin 重写 | **永远 A** —— 重写等于放弃「跟随官方」，把项目从**适配层**变成**复刻层** |
| **② 宿主形态** | 直连引擎（纯套壳） | 冒充桌面宿主（carrier + 代理 + polyfill） | **A 为主**，B 做成可开关实验（`experiment/desktop-architecture`） |
| **③ 外围能力** | 全塞进 WebView | 原生实现（设置 / 体检 / 控制台 / 悬浮球 / 安全模式） | **B —— 最该投入**，因为这是「能不能用」而不是「好不好用」 |

拿最近三件事对号入座就很清楚：

- **移动端布局插件**（第十三节）属于**轴①的适配层**：让官方 UI 在手机上可用，**而不改官方源码**；
- **安全模式**（第十二节）属于**轴③**：桌面版有它是因为 Electron 主进程是宿主，**不是因为桌面架构**；
- **桌面架构实验分支**属于**轴②**：我在这上面花过 998 行，但它属于「好不好用」，
  优先级低于轴③的「能不能用」——**这是我一次真实的优先级误判**。

---

三种形态，差别不在「谁重写了 UI」，而在**宿主有没有满足官方客户端期待的宿主契约**。

| 形态 | 侧边栏浏览器 | 机制 |
|---|---|---|
| **网页版** | iframe 降级 | 无 `globalThis.dshDesktop.browser` → 走 `IframeImpl` |
| **桌面版**（Electron） | 真 Chromium 标签页 | preload 注入完整 carrier → 走 `ElectronWebviewPresentation` |
| **本项目的 Android 版（当前）** | iframe 降级 | WebView 加载官方 Web UI，**零注入** |

### ⚠️ 障碍：后端同源闸 vs WebView 的能力边界

桌面版能拿到桌面级能力，靠的是两项 **Electron 特权**：

1. 文档从自定义协议 `dsh-app://app` 加载（而非 `http://127.0.0.1`）；
2. 主进程可以**改写请求头**。

而官方后端的同源闸是这样的：

```ts
// packages/client/connection/src/api-request-trust.ts
if (header(request.headers, 'sec-fetch-site') === 'cross-site') return false
const origin = header(request.headers, 'origin')
if (origin === undefined) return true
try { return new URL(origin).host === hostUrl.host } catch { return false }
```

**两道闸**：`Sec-Fetch-Site: cross-site` 直接拒；带 Origin 时 `origin.host` 必须严格等于 `Host` 头。

**Android WebView 没有等价能力** —— `shouldInterceptRequest` 只能改**响应**，改不了**请求头**。
照搬桌面架构（把文档换个 origin）就会当场被这两道闸拒掉。

**并且加白名单解决不了**：`trustedHosts` 校验的是 **Host 头**，Origin 栅栏与之独立。

### 出路：官方留的口子

第二行 `if (origin === undefined) return true` —— 注释也明写着
*"Absent Origin is fine — the Host fence above already bound the request."*

所以只要让 API 请求**不带 Origin 发出**即可。方案是**在本机起一个反向代理**：

```
WebView  ──同源请求──▶  127.0.0.1:<代理端口>  ──无 Origin 转发──▶  127.0.0.1:<引擎端口>
         （文档也从这个代理加载，于是 API 是同源）
```

代理用普通 HTTP 客户端转发，**不会附加 Origin 与 Sec-Fetch-Site** → 两道闸都过。
文档与 API 同在代理端口下，对浏览器而言还是同源。

完整拆解与实施蓝图见 [docs/DESKTOP-ARCH.md](docs/DESKTOP-ARCH.md)。
实验性实现见分支 `experiment/desktop-architecture`。

## 十二、安全模式：插件把界面搞死之后的自救通道

### 为什么必须有它

插件跑在引擎进程里，一个坏插件能让整个 Web UI 起不来。
而**恢复入口恰恰不能画在那个 UI 里 —— 页面挂了它一起挂**。

| 形态 | 恢复入口在哪 | UI 挂了还能用吗 |
|---|---|---|
| 网页版 | 网页自己的插件管理页 | ✘ 页面挂了它一起挂 |
| 桌面版 | Electron 主进程的原生 `messageBox` | ✔ 独立于网页 |
| **本项目** | 原生设置页 / 控制台 / 通知 | ✔ **本来就是宿主，只是以前没做这个按钮** |

顺带一个实测背景：开发机的 profile 里有 **50+ 个 `cordis.patch.yml.bak-*`、
20+ 个 `package.json.bak-*`** —— 也就是说「备份 → 改 → 出问题回滚」这件事
一直是**手工**在做的，做了七十多次。

### 它到底改什么

对齐官方 `packages/boot/app-boot/src/profile-sanitize.ts` 的语义，就两件事：

1. 把 `cordis.patch.yml` 改名成带时间戳的备份 → 等价于「禁用全部插件补丁」
2. 把 `package.json` 里的 `dsh.profile.bundles` 重置为**官方列表**

**官方 web profile 的 bundles 只有两项**：`@deepseek-ai/dsh-base` 与
`@deepseek-ai/dsh-web-app`。其余一切都是第三方——这就是「禁用所有插件」的准确定义。

> **不删任何东西**，全部是改名或副本。恢复工具如果再毁一次数据，就没有下一步了。

实现见 [`engine/.../plugin/SafeMode.kt`](engine/src/main/java/io/dsh/fusion/engine/plugin/SafeMode.kt)，
行为由 [`SafeModeTest.kt`](engine/src/test/java/io/dsh/fusion/engine/plugin/SafeModeTest.kt)
的 13 个用例钉住：不删任何文件、禁用后 bundles 恰好等于官方列表、还原能回到禁用前的状态。

### 两个原生入口

- **设置页「安全模式」卡片**：现状诊断（几个 bundle、几个第三方、补丁多大、几份备份）
  + 禁用 + 备份列表 + 一键还原
- **引擎启动失败时**，主界面按钮直接变成「进入安全模式」（而不是「重试」——
  重试多半还是同样的失败，而用户不知道该改什么）

### 比桌面版多做的

| | 桌面版 | 本项目 |
|---|---|---|
| 入口时机 | 只在启动失败时弹一次 | 原生设置页**常驻** + 失败时引导 |
| 备份可视化 | 只返回一个路径 | 列出全部备份 + 一键还原 |
| 还原安全性 | — | 还原前先留 `bak-beforeRestore-*`，还原错了还能回头 |
| 写清单 | — | **原子写**（临时文件 + 改名）：直接覆盖时断电会留下无法解析的清单，比插件坏掉更严重 |

### 白屏探针：宿主才做得到的事

插件坏掉最严重的表现不是布局错乱，而是**整页白屏** —— 那时页面里的 JS 可能压根没跑起来，
所以**任何画在网页里的提示都不会出现**。

`MainActivity` 在 `onPageFinished` 后每 2 秒探测官方 shell 锚点 `[data-shell-overlay]`，
**24 秒仍无则弹原生对话框**引导进安全模式。措辞上刻意不判断原因（可能是 WebView 太老、
内核起了一半、或插件）——**给出可做的动作比给一个判断有用**。

---

## 十三、随包的移动端布局插件

### 为什么它必须随包

官方 Web UI 是给桌面浏览器写的：手机上按钮太小、侧栏挤占宽度。本项目用一个
**纯客户端 overlay 插件**把它改成移动布局——**不改官方源码一行**，所以官方升级不会覆盖它。

这个插件是自研的，但它原本以 `pnpm add file:<本机路径>` 安装，而**那个源目录已经不存在了**，
整个插件一度只剩开发机 profile 的 `node_modules` 里那一份。而 APK 的内核包是用官方包
现装的，所以**设备上根本不会有它**。现在它随 APK 发货：

```
app/src/main/assets/plugins/dsh-mobile-layout/   → 首启装配时铺进 profile
                                                  并注册进 dsh.profile.bundles
```

> **装了 ≠ 生效。** dsh 的插件要两处同时成立：文件在 `node_modules` 里，
> **并且**包名在 `dsh.profile.bundles` 里。只做前者的话现象是
> 「插件装了但一点效果都没有」——最难排查的一类。装配代码里 `registerBundle()`
> 就是干这个的，自检也有一条断言盯着它。

### 它做了什么

- 三列网格锁定为「聊天占满」；侧栏收起时隐藏，左下角 ☰ 悬浮钮（44px）
- 点 ☰ → 官方侧栏以 **fixed 280px 抽屉**滑入 + 半透明遮罩
- 输入卡圆角收紧、安全区适配（刘海屏 / 手势条）

**`always-mobile` 是刻意的选择**：任何视口都跑移动布局。Android 的视口宽度会随
横屏、平板、分屏、系统字体缩放而变，一旦越过断点就会退回桌面布局——而用户明明在手机上。

### v5 强化：只补纯增量

开发机的版本（v4）是**实测可用**的，而表现层插件不该为了更好看去冒回归风险。
所以对照社区插件后只补了两块纯增量：

**① canary —— 让插件自己发现官方改版**

这个插件 v1→v4 **每一次**都是被官方改版搞坏的，而每一次都是**人眼发现界面坏了**才修的：

| 版本 | 被什么搞坏 |
|---|---|
| v1/v2 | 合成点击丢失 → 官方保持展开而 CSS 已锁 0 宽（**既没侧栏也没 FAB**） |
| v3.2 | 第一个 grid item 消失导致串轨 → **Details 页整页泄漏** |
| v4 | 官方 0.1.2 改 locale；0.2.0 右侧栏改 `absolute` → 面板盖住对话 |

canary 的判据：**先健康过一轮**，之后连续 40 次 reconcile（约 10 秒）三列锚点全丢
→ 判定漂移 → **移除自己注入的 CSS 与 DOM，交还官方布局**。

这是 **fail-safe 而不是 fail-broken**：针对一个已不存在的 DOM 写的 CSS
不是「部分可用」，而是有害的。两个刻意设计：**冷启动不计数**（shell 挂载了但列还没
渲染完是正常的）与**短暂抖动不触发**（有一次布局闪断就自杀是灾难）。

**② composer 触控与字号下限**

- 输入框字号 **≥16px**：低于此值时移动端 WebView 聚焦会自动缩放，而且**不一定退回去**，
  结果整页永久被放大
- 触控目标 **≥44px**（WCAG 2.5.5）：只作用于 composer 行与抽屉，**不做全局规则**——
  那会重排我们并不拥有的官方行

### 与社区版的关系

社区至少有五个同类插件，各自强调的正好点出本插件缺什么：

| 社区插件 | 它强调的 |
|---|---|
| [`dsh-mobile-xc`](https://www.npmjs.com/package/dsh-mobile-xc) | **canary 防升级失效**、≤1023px 生效（v5 已借鉴前者） |
| [`dsh-webui-mobile`](https://github.com/bbtssama/dsh-webui-mobile) | **mobile composer fixes**、composer 内图片上传（v5 借鉴前者） |
| [`dsh-mobile-theme`](https://github.com/roojay/dsh-mobile-theme) | 右侧预览全屏、**touch-size controls**（v5 借鉴后者） |
| [`@ddtcorex/dsh-maestro-mobile`](https://github.com/ddtcorex/dsh-maestro-mobile) | sheet 弹窗、safe-area |
| [`NOirBRight/dsh-mobile`](https://github.com/NOirBRight/dsh-mobile) · [`lehhair/dsh-mobile`](https://github.com/lehhair/dsh-mobile) | 是 **Android 客户端 + Host 配对**，不是布局插件 |

**没做的**（不是忘了，是往一个实测可用的插件里塞未验证的改动风险大于收益）：
composer 内图片上传、sheet 弹窗、右侧预览全屏。

源码在 [`plugins/dsh-mobile-layout/`](plugins/dsh-mobile-layout)，
`history/` 里留着 v3/v4 时期的 `.bak`。

---

## 十四、测试与自检

| 层 | 数量 | 覆盖什么 |
|---|---|---|
| Kotlin 单元测试 | **38** | 版本比较、镜像地址拼接、sha256、能力清单 JSON 契约、唤醒词编辑距离、**安全模式（13 个）**、**端口选择（5 个）** |
| 插件行为测试 | **6** | canary：健康不触发、冷启动不误触发、漂移触发并自我卸载、幂等、抖动不触发 |
| `tools/verify.sh` | **50 项** | 见下 |

单测第一次运行就抓到一个真 bug：`KernelUpdater.compareVersions("0.2", "0.2.0")`
返回 `-2`（应为 0）——数字段比完后错误地退回了字符串比较，会让升级页的版本排序抖动。

自检里几条值得一提的断言，它们盯的都是**不报错、只是没效果**那一类故障：

- **事件总线接线完整性**：每种 `FusionEvent` 都必须有生产者。`Reply` 曾经只有消费者
  没有生产者，结果「流体云回复卡」永远不会触发且不报任何错
- **APK 体积健康度**：对齐填充超过 8 MiB 就报错。踩过一次——超大 STORED 资源让
  zip 写入器算错偏移，内容被物理写两遍，APK 从 107 涨到 129 MiB
- **桥名两侧一致**：Kotlin 侧的 `JS_INTERFACE` 必须与 carrier 脚本里的名字相同。
  不一致的表现是 carrier 找不到宿主后**静默 return**
- **两条装配路径都铺随包插件**：内置包路径与在线安装路径都要铺，否则两条路会得到
  两个不同的 App，而用户完全不知道差别从哪来
- **端口选择的三条行为**：0 交给操作系统、指定端口被占就顺延、整段被占则退回 0
  —— 第三条曾经写错，会把一个**已知被占用**的端口交给引擎，注定失败而报错只说「启动超时」

```bash
bash tools/verify.sh                                  # 交付自检
gradle :engine:testDebugUnitTest :voice:testDebugUnitTest
cd plugins/dsh-mobile-layout && node --test test/     # 插件行为测试
```

---

## 十五、诚实的边界

**已完成并经过静态验证**

- 五个模块全部编译通过；`assembleDebug` 产出约 108 MB APK
- 双 ABI 的 ELF 依赖闭包 100% 自洽；内置 Node 24.18.0、PRoot 5.1.107（**含 loader**——
  少了这两个文件容器后端基本是残废的）
- 预装内核包：942 MB → 501 → 131 → **21.2 MB**，实测数据，脚本（`tools/build-kernel-bundle.py`）可复现
- 随包移动端布局插件（462 行）+ canary 自我降级
- 安全模式（285 行）+ 13 个单测 + 两个原生入口 + 白屏探针
- `tools/verify.sh` **50 项**自检；38 个 Kotlin 单测 + 6 个插件行为测试
- 独立包名 + 独立 RSA-4096 签名

**尚未在真机验证**

> 这一条要说在前面：**至今没有任何一行运行期代码在真机上跑过。**
> 下面全部是「写好了，逻辑上自洽，有静态与单元测试覆盖，但没跑过」。

- 端到端启动（含内核包解压）：解压 131 MB / 约 3 万个文件，**预估** 30–90 秒，没测过
- 移动端布局在真机 WebView 里的实际效果（抽屉、☰ 钮位置、输入框缩放）
- 白屏探针会不会误报（24 秒的阈值是估的，真机冷启动可能更慢）
- 流体云胶囊、确认闸、官方插件安装页、Ubuntu rootfs 后端、离线唤醒
- 内核包在 arm64 上解压后能否真的把引擎拉起来

**已知缺陷**

- 流体云胶囊已接通（插件订阅 `session/event` → 桥 `/event` 端点），但未在真实会话中跑过
- `replaceLegacyPackaging` + 超大 STORED 资源会触发 zipflinger 对齐膨胀
  （已在构建配置中规避，并在自检里加了断言）
- **存储分区隔离做不到 Electron `partition` 的等价物**：Android WebView 的
  Cookie 与 DOM Storage 都是进程级的。实验分支现用「一租约一 WebView + 释放即销毁」
  做粗粒度隔离——若将来要跑互不信任的站点，这一条必须先解决

**实验分支 `experiment/desktop-architecture` 的已知问题**

它让 Android 版进入官方客户端的「桌面宿主」分支（拿到桌面级侧边栏浏览器）。
不合并进主干的原因不是「做不到」，而是**它的失败模式不对等**——
套壳版坏了坏一个面板，它坏了坏整个应用（代理在关键路径上）。

- 代理的 **WebSocket 处理有 3 个真 bug**：`Connection: close` 无条件追加与
  `Connection: Upgrade` 冲突；`firstOrNull` 只转发 1 个 `sec-websocket-*` 头，
  而 `Sec-WebSocket-Key` 与 `Sec-WebSocket-Version` 两个都是必需的
- 后果：`/api/remote.mux` 握手失败 → **整条实时通道挂掉 → 聊天界面不能用**
- 8 个代理测试一个都没抓到，因为它们**全是手写 HTTP 请求，没有一条走 WebSocket**

**要让它有价值，第一步是先修这 3 个 bug 并补上真正走 WebSocket 握手的测试。**

---

## 十六、在手机上跑本地大模型（可选，按需下载）

**不联网也能对话** —— 但模型要你自己下，**不打进 APK**。

### 它是怎么接进来的（没有改官方一行代码）

官方内核的 `dsh-llm` 是 **provider 无关的抽象层**，而 `dsh-llm-deepseek` 的配置里有一个
`baseURL`，协议是 `chat/completions`（OpenAI 兼容）。它的解析顺序是：

```js
baseURL: config.baseURL ?? environment?.get("DEEPSEEK_BASE_URL")?.value ?? "https://api.deepseek.com"
```

而 `DEEPSEEK_BASE_URL` 在「**只能由启动环境提供**」的变量名单里
（同列的是 `ALL_PROXY`、`CURL_CA_BUNDLE`、`GIT_*` —— 都是决定进程怎么上网的那一类）。

**所以我们不改你那个 100 KB 的 `cordis.patch.yml`**：本机起一个说同样协议的服务，
再把 `DEEPSEEK_BASE_URL` 注入引擎的继承环境即可。

```
DSH 引擎 ──OpenAI 兼容协议──▶ 127.0.0.1:<port>/v1 ──▶ llama-server ──▶ GGUF 模型
             （DEEPSEEK_BASE_URL 指过来）
```

### 两个都要下：运行时 + 模型

| | 内容 | 下载 | 装完占用 |
|---|---|---|---|
| **运行时** | llama.cpp 的 `llama-server` + 它的 8 个 `.so` 依赖 | **11.0 MB** | 36.1 MB |
| **模型** | GGUF，三档可选（见下） | 0.4 – 2 GB | 同左 |

**运行时清单只有 4.6 KB**（`assets/llm/runtime-manifest.json`），里面**只有 URL 与 sha256，
没有任何二进制** —— 自检里有一条断言专门盯这个。不用本地模型的人不该多背 11 MB。

依赖闭包不是手写的：`tools/build-llm-manifest.py` **真的去解包、真的读 `DT_NEEDED`**
算出来的（llama-server → libllama-server-impl → libllama/libggml\*/libmtmd/libcrypto.so.3/libssl.so.3）。
手写一定会漏，而漏了的表现是「启动时报 cannot link executable」—— 那时用户已经等了几分钟下载。

### 模型怎么选

按整机内存推荐（清单里三档）：

| 模型 | 大小 | 建议内存 | 适合 |
|---|---|---|---|
| Qwen2.5 **0.5B** | 0.4 GB | ≥3 GB | 先验证链路通不通 |
| Qwen2.5 **1.5B** | 1.1 GB | ≥6 GB | **手机上的主力档** |
| Qwen2.5 **3B** | 1.9 GB | ≥8 GB | 接近可用，但 8 GB 以下会被系统杀掉 |

下载源 ModelScope 主用、hf-mirror 兜底，两个都**支持断点续传**（实测 HTTP 206）——
手机下 GB 级文件一定会断，能续传是刚需。

### 用之前要知道的两件事

**① 速度和云端不是一个量级。** 1.5B 模型在手机上是每秒几个字到几十个字。
适合离线、隐私敏感、或纯粹想试试的场景；日常还是云端模型更好用。

**② 上下文窗口只有 4096。** 这不是随便定的：KV cache 随上下文线性增长，
手机上开太大**会直接触发系统 OOM 把整个应用杀掉**，而用户只会看到「闪退」。

### 怎么用

```
设置 → 本地模型（可选）
  → 下载运行时（11 MB）
  → 选择模型（会按你的内存推荐一档）
  → 启动服务（首次加载较慢，要等它把模型读进内存）
  → 重启引擎（DSH 在启动时读端点，不重启不生效）
```

想回到云端模型：**停止服务 + 重启引擎**即可。

### 为什么用 proot 跑它

Android 10+ 禁止 `execve` 应用数据目录里的文件。Node 的解法是塞进 jniLibs 伪装成
`lib*.so`，但**运行期下载的东西进不了只读的 `nativeLibraryDir`**。
proot 用 loader 映射绕过这条限制，正好解决 —— 代价是慢一点，对一个推理进程可以忽略。

这也是为什么 LLM 运行时**不需要** SONAME 归一化（不像 Node 那样要 patchelf）：
proot 下文件可以叫任何名字，`libcrypto.so.3` 照原样就能被找到。

---

## 十七、上手指南（第一次用看这一节）

### 1. 拿到安装包

`DSH-Fusion-v1.1-mobile.apk`（约 108 MB）。
体积大是因为它**自带**了：两套运行时（arm64 + x86_64）、官方内核（预装好的 21 MB 压缩包）、
自研移动端布局插件。代价是装的时候慢一点，换来的是**装上就能用、首启不需要联网**。

### 2. 装到手机上

华为 / 鸿蒙机型要先过两关：

| 关卡 | 怎么过 |
|---|---|
| 「未知来源」拦截 | 设置 → 安全 → 更多安全设置 → 外部来源应用下载 → 允许对应应用 |
| **纯净模式**（HarmonyOS 默认开） | 设置 → 系统和更新 → 纯净模式 → 退出。**不开这个，侧载的包会被直接拒绝** |

其它安卓机型一般点「仍要安装」即可。

### 3. 第一次启动

会看到「准备工作」阶段，进度条显示解压进度。**这一步不需要联网。**

- 需要约 **190 MB** 空闲空间（不够会明确告诉你还差多少）
- 预计 **30 秒到 1 分钟**（131 MB / 约 3 万个文件）
- 手机可能发热 —— 大量小文件写入，正常
- **这期间别把应用划掉**：虽然解压到临时目录再原子改名，划掉不会留下半残状态，
  但下次要重新解压一遍

解压完成后引擎自动启动，然后进入对话界面。

### 4. 界面怎么看

主界面就是**官方的 DSH Web UI**（不是我们重写的），外面套了一层原生壳：

- **左下角 ☰ 悬浮钮** → 滑出侧栏抽屉（移动端布局插件的功劳）
- **左上角状态胶囊** → 点它进「控制台」，看引擎日志
- **底部胶囊导航** → 设置 / 体检 / 控制台 / 悬浮球
- 对话、工具、插件、会话管理**和桌面版完全一致** —— 因为就是同一套官方界面

### 5. 出问题怎么办

按「现象 → 先做什么」的顺序：

| 现象 | 先做什么 |
|---|---|
| **整页白屏超过 24 秒** | 会**自动弹出**原生对话框问你要不要进安全模式 —— 点它 |
| 引擎起不来 | 主界面按钮会变成「进入安全模式」；点它，或去「设置 → 安全模式」 |
| 界面布局错乱 | 可能是官方 UI 改版了。移动布局插件有 canary，**10 秒内会自我卸载并交还官方布局**；若仍不对，看「控制台」有没有 `canary tripped` |
| 怀疑某个插件搞的 | 「设置 → 安全模式 → 禁用全部第三方插件」→ 重启。**不删任何文件** |
| 想退回上一次的状态 | 「设置 → 安全模式 → 备份与还原」→ 选一份 → 还原 |
| 引擎反复重启 | 「控制台」看最后一次失败原因；端口冲突会自动换端口重试 |

### 6. 安全模式是什么

插件跑在引擎进程里，一个坏插件能让整个界面起不来 —— 而**恢复按钮恰恰不能画在那个
界面里**（页面挂了它一起挂）。所以它在**原生设置页**。

它做两件事：把插件补丁文件改名成带时间戳的备份、把依赖清单重置为官方两项。
**全部是改名或副本，不删任何东西**，随时可以在「备份与还原」里退回去。

### 7. 可选的功能（需要联网，都是可选的）

| 功能 | 下载量 | 什么时候装 |
|---|---|---|
| Ubuntu 容器后端 | 约 30 MB | 想用「Ubuntu 容器」跑 Linux 命令时 |
| 离线语音唤醒 | 约 42 MB | 想喊一声就唤醒时 |

不做这两件事，对话与设备控制全部正常。

### 8. 常见问题

**Q：需要配 API key 吗？**
A：**需要** —— 但可能不用你自己去申请 key，因为有两条路。

推理发生在云端，**设备本地没有模型**（内核包 16007 个条目里没有任何模型权重，这一条我扫过）。
官方内核提供两种认证：

| 方式 | 包 | 说明 |
|---|---|---|
| **DeepSeek 账号** | `dsh-llm-deepseek-account` | 登录账号即可，不用手动搞 key |
| **API key** | `dsh-llm-deepseek-api-key` | 填一个 key |

配置位置在**官方 Web UI 的「设置 → 模型」**（`dsh-client-ui-settings-models`），
里面有 `provider` / `baseUrl` / `model` 三个字段 —— 也就是说**可以指向任意兼容端点**
（第三方中转、自建服务都行）。

**Q：key 存在哪？安全吗？**
A：存在 `$DSH_HOME/.credentials.yaml`，**权限 600**。

在 Android 上 `DSH_HOME` 是**应用私有目录**（`/data/data/io.dsh.fusion/files/fusion/home`），
使用 Ubuntu 容器后端时映射为容器内的 `/root/.dsh`。也就是说：

- 其它应用**读不到**（无需 root 的情况下）
- **不进 APK、不进内核包** —— 自检里有一条断言专门扫这两处不该出现凭据类文件
- **卸载即清**：所有数据都在 `filesDir` 下，不碰用户存储

**Q：会连外网吗？**
A：**会** —— 因为模型在云端，你的对话内容必然要发给你配置的模型服务。这一点要说清楚，
不能含糊成「数据不出设备」。

准确的说法是：
- **设备数据不出设备**，除非模型通过工具去读（读通知、读屏、读文件都要经过程序化的工具调用，
  而且可审计 —— 每次调用都记在控制台里）
- 引擎只监听 `127.0.0.1`（回环），**局域网内其它设备访问不到它**
- 首启解压内核**全程离线**；不配模型的话，应用能启动但没法对话

**Q：和官方 DSH 是什么关系？**
A：这个应用**不修改官方一行代码**。它做的事是：把官方运行时（Node + PRoot）与官方内核
装进 APK、在手机上跑起来、再用 WebView 加载**官方 Web UI**。官方升级，跟着升级即可。

**Q：端口被占用怎么办？**
A：默认就让操作系统分配，**不需要你操心**。只有当你手动指定了一个端口而它恰好被
占用时，才会自动换端口重试；重试仍失败时，状态里会明确写「端口被占用」而不是
含糊的「启动超时」。

想改就去「**设置 → 引擎 → 监听端口**」：可以选「自动选择（推荐）」、几个常用端口，
或填一个自定义值。改完会**自动重启引擎**让它生效（端口是启动参数，不重启等于没改）。
设置页那行还会显示**当前实际在听的端口** —— 自动分配时你只能从这里看到它是多少。

---

## 附：产物

| 文件 | 体积 | 说明 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | ~108 MB | 当前构建产物 |
| `DSH-Fusion-v1.1-mobile.apk` | 107.7 MB | 含内核包 + 移动端布局 + 安全模式 |
| `DSH-Fusion-v1.1-desktop-experiment.apk` | 107.7 MB | 桌面架构实验分支 |

精简版（`-PwithKernelBundle=false`，约 87 MB）首启走在线安装，给 x86_64 预览版用。

仓库：<https://github.com/yuchen1017918/dsh-fusion>

---
