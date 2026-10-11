# 踩坑对照表：32 个已知坑 → 本项目的处理

每条注明「在代码里的哪一处」或「为什么本项目天然不踩」。
标 ⚠️ 的是**仍未在本机验证**的项（缺真机 / 缺应用宝内的 adb 助手）。

## 🛠️ 环境与工具链

| # | 坑 | 处理 |
|---|---|---|
| 1 | WSL2 下 NDK 链接器权限被拒 | **本项目不使用 NDK。** 运行时是把现成的 bionic 二进制搬进来再改 ELF 元数据（`patchelf`），一行 C 代码都不编。源文件全在 Linux 原生文件系统（`~/workspace`），不跨 `/mnt` |
| 2 | WSL2 下 ADB 与设备连接不稳定 | `tools/adb-androws.sh` 走**应用宝自带的 Windows 侧 adb.exe**（WSL 互操作调用），绕开 WSL 的 USB 与网络栈；真机走无线调试 `adb connect`。另注：`adb devices` 在 WSL 里直连会挂起，脚本用 `timeout` 兜底 |
| 3 | Gradle 依赖解析失败 / 版本不匹配 | 固定组合：**Gradle 8.9 + AGP 8.6.1 + Kotlin 2.0.21**；`settings.gradle.kts` 里 google/mavenCentral 在前、阿里云公共仓库兜底；Gradle 发行版走腾讯云镜像（`services.gradle.org` 在本机直连不通） |
| 4 | JDK 版本不匹配 | 本机 JDK 21 + AGP 8.6.1（AGP 8.x 支持 17–21），`sourceCompatibility/targetCompatibility/jvmTarget` 统一 17；`JAVA_HOME` 显式指定，不依赖系统默认 |
| 5 | 最小 API 与包名隔离冲突 | `minSdk 26`（Android 8.0+）；包名 `io.dsh.fusion` **独立**，签名独立（RSA-4096 自己的 keystore），与任何其它 DSH 安卓版**可并排安装**。`tools/verify.sh` 会检查签名不是 debug key |

## 📦 运行时与依赖

| # | 坑 | 处理 |
|---|---|---|
| 6 | node-gyp 的 `android_ndk_path` 未定义（`process.platform === "android"`） | 本项目把「需要编译原生模块」的场景整体推到 **Ubuntu/PRoot 后端**：那里 `process.platform` 是 `linux`，node-gyp 走正常路径。原生直跑后端只跑纯 JS 部分 |
| 7 | koffi 的 `statx()` 在 Android 30 以下不可用 | 同上，由 Ubuntu 后端承接；`CompatDoctor.apiLevelCheck` 会把系统版本摊给用户看 |
| 8 | sharp 无 android-arm64 预编译绑定 | 同上。README 的「演进方向」里记录了 `SHARP_FORCE_GLOBAL_LIBVIPS=1` 这条路，属于 Ubuntu 后端范围内的事 |
| 9 | HMR 需要 `--expose-internals` | `EngineSupervisor.buildCommand()` 在 node 与入口脚本之间插入 `--expose-internals`（**只能走命令行，塞 NODE_OPTIONS 无效**），`FusionSettings.exposeInternals` 可开关，默认开 |
| 10 | FUSE 禁止硬链接，session 落盘失败 | **`DSH_HOME` 落在应用私有目录（ext4/f2fs），绝不落 `/sdcard`。** `CompatDoctor.hardLinkCheck()` 会**实测一次 `Files.createLink()`**，失败就报红并指出原因 —— 这正是「进程活着但界面永远转圈」的元凶 |
| 11 | better-sqlite3 等无 Android 预编译 | 归 Ubuntu 后端；控制台会把 npm 的真实输出摊出来，失败点看得见 |
| 12 | `spawn.h` 占位符污染 `CPATH` | 本项目不安装任何全局头文件；Ubuntu rootfs 是干净解包的官方 `ubuntu-base`，不注入 `CPATH` |

## 📱 Android 系统限制

| # | 坑 | 处理 |
|---|---|---|
| 13 | proot 的 ptrace 被 SELinux 阻止 | `ProotLauncher` 默认带 `-0`（伪 root）；`RuntimeEnv` 设 `PROOT_NO_SECCOMP=1`（Android 必需）；`CompatDoctor.selinuxCheck` 读 `getenforce` 并在拦截时建议切「原生直跑」 |
| 14 | Android 10+ 数据目录执行限制（W^X） | **架构级处理**：node 与 proot 不走数据目录，而是伪装成 `libnode.so` / `libproot.so` 放进 `jniLibs`，由包管理器在安装时解压进 `nativeLibraryDir` 并带上执行位。配套三件套：manifest `extractNativeLibs="true"` + AGP `useLegacyPackaging = true` + `CompatDoctor.wxcCheck` 复核 |
| 15 | Scoped Storage 无法访问外部存储 | 已声明 `MANAGE_EXTERNAL_STORAGE`（+ API≤32 的 READ/WRITE）。**不用 SAF 的理由**：SAF 给的是 `Uri`，而模型的 `android_file_*` 工具要的是真实路径。旁加载应用，用户可单独授权 |
| 16 | `NetworkInterface.GetAllNetworkInterfaces()` 非 root 失效 | 本项目的引擎只绑回环，**不需要枚举网卡**；网络状态由 `HttpDownloader` 直接按 URL 试错，不做接口探测 |
| 17 | Android 12+ 禁止后台启动前台服务 | `EngineService.start()` / `FusionApplication.uiVisible`：先判断界面是否在前台，不满足就**直接放弃启动并写日志**，而不是去撞 `ForegroundServiceStartNotAllowedException`；主界面在 `onCreate` 里起服务（此时必然在前台） |
| 18 | Android 15 前台服务 6 小时上限 | 三种服务分别用 `specialUse`（引擎、桌宠）与 `microphone`（语音），**刻意避开** `dataSync` / `mediaProcessing` 这两个受限类型；manifest 里带 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途。`CompatDoctor.foregroundServiceCheck` 会把 API≥35 标成警告 |
| 19 | 缺 `POST_NOTIFICATIONS` 导致前台服务通知不显示 | manifest 已声明；`CompatDoctor.notificationCheck` 在 Android 13+ 检查运行时授权并提供跳转 |

## ⚡ 性能与资源

| # | 坑 | 处理 |
|---|---|---|
| 20 | proot 冷启动极慢（20s+） | 这是本项目做**双后端**的直接动机：日常用「原生直跑」（不经过 ptrace 翻译），重活才切 Ubuntu。另外 `ProotLauncher` 只挂必要的 bind（`/dev /proc /sys /system /apex /vendor` + 三个自有目录），减少路径翻译开销 |
| 21 | 幻影进程杀手（Android 11–14） | `PhantomGuard` 读 `/proc/<pid>/task/*/children` 统计子进程数、查 `settings_enable_monitor_phantom_procs` 状态；`CompatDoctor.phantomCheck` 在超阈值或监控开启时报警，并给**一键关闭按钮**（`settings put global ... false`，走 Shizuku/Root） |
| 22 | V8 堆内存限制 | `RuntimeEnv.base(paths, registry, heapMb)` 把 `--max-old-space-size` 做成设置项，默认 **512 MB**（不是桌面的 2G），范围 128–3072；`CompatDoctor.heapCheck` 与设备总内存对比，超过一半就报警 |
| 23 | proot 下文件读写缓慢 | 运行时与 `$DSH_HOME`、rootfs **全在同一文件系统**（`filesDir/fusion/`），不跨 `/sdcard`；npm 缓存也落在同一处 |
| 24 | Android 16 下 proot 性能骤降 | 系统级兼容问题，本项目的应对是**保留原生直跑后端**：即使 PRoot 在某设备上退化，日常仍可用 |

## 🏗️ 工程化与融合

| # | 坑 | 处理 |
|---|---|---|
| 25 | WebView 版本过低导致白屏 | `MainActivity.warnIfOldWebView()` 用 `WebView.getCurrentWebViewPackage()` 读内核版本，< 100 就弹提示；`CompatDoctor.webViewCheck` 同时在体检页列出并提供跳转 |
| 26 | 插件版本升级导致布局根服务不激活 / 插件全 pending | `SettingsActivity` 的「内核更新」里给了**第二条动作：「重新装配 profile」**；`DshProvisioner.writeProfile` 每次安装都重写 `package.json`（三个包**锁死同一版本** —— 因为 `dsh-base` / `dsh-web-app` 的 npm `latest` 标签指向 0.0.1-rc.1 占位版，各装各的必然错位）；`BridgeRouter /health` 与 `/capabilities` 可在装配后立即验证 |
| 27 | WebView 访问 localhost 的非安全上下文 | 现代 WebView 已把 `127.0.0.1` 视为 potentially trustworthy origin，**Secure Context 天然成立**；`MainActivity` 直接用引擎打印的真实 URL（含 token），并设 `MIXED_CONTENT_COMPATIBILITY_MODE`；回环与本地留在应用内，外链交系统浏览器 |
| 28 | `DSH_HOME` 未设置导致 web 模式假死 | `RuntimeEnv.base()` **无条件**设置 `DSH_HOME` 与 `HOME` 指向 `filesDir/fusion/home`；`CompatDoctor.dshHomeCheck` 复核它不在 `/sdcard`/`/emulated` 下 |
| 29 | `--resume` 参数位置敏感 | 本项目启动引擎用的是 `node <bin.js> web --port N --host 127.0.0.1`，**参数顺序在 `EngineSupervisor.buildCommand` 里被写死并注释**；不使用 `--resume`（会话续接由 Web UI 内部完成） |
| 30 | headless 模式不支持连续对话 | 本项目只用 `web` profile（连续对话是 Web UI 的强项）；不提供 headless 入口，避免给用户一个不能连续对话的坑 |
| 31 | 签名与包名冲突导致无法并排安装 | 包名 `io.dsh.fusion` + 独立 RSA-4096 keystore；`tools/verify.sh` 断言签名 DN 不是 `Android Debug`（这条断言真的抓到过一次 —— `keystore.properties` 里路径多写了一级 `../`，签名悄悄退回 debug） |
| 32 | 手机端自出包的签名配置 | `signingConfigs.fusion` 的口令**优先读环境变量**（`DSH_FUSION_STORE_*`），其次读未入库的 `keystore.properties`（已在 `.gitignore`）；两者都缺时退回 debug 签名但**自检报红**，不会悄悄发出去 |

## 本机新增的三条（不在原列表里，但真实存在）

| 坑 | 现象 | 处理 |
|---|---|---|
| **SONAME 阻断** | Termux 的库叫 `libicuuc.so.78`；Android 包管理器只解压 `lib*.so`，这些库根本进不了 `nativeLibraryDir`，上机就是 `dlopen failed` | `tools/build-runtime.py` 把全部带版本号的 SONAME 改成 `lib*.so`，并用 `patchelf --replace-needed` 改写所有引用方；`tools/verify.sh` 逐库复核 `DT_NEEDED` 闭包 |
| **应用宝是 x86 虚拟机** | 流传的「应用宝用 Intel Bridge 翻译 ARM，无需重新打包」不成立：实测 `VBox.log` 写着 `win.amd64`，镜像是 `.vhd`，宿主是 AMD Ryzen（Intel Bridge 只存在于 Intel 平台的 WSA）。arm64-only 的 APK 装不上 | 运行时出**双 ABI**（`arm64-v8a` + `x86_64`），APK 从 44 MB 涨到 87 MB，换来「手机能跑 + 电脑能测」 |
| **编译期 OOM** | 7.8 GB 内存的机器上，Gradle 守护进程 + Kotlin 守护进程 + 200 MB native 打包会互抢内存 | `gradle.properties` 收敛：`-Xmx2g`、`MaxMetaspaceSize=512m`、`UseSerialGC`、`parallel=false`、`workers.max=2`、`kotlin.compiler.execution.strategy=in-process`（少起一个 JVM）。实测峰值后仍余 2.8 GB |
