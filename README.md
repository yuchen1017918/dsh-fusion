# DSH Fusion · 安装器

> **这个仓库的 `main` 分支不是应用本身，而是一个安装器。**
> 真正的应用在 [`full-bundled`](https://github.com/yuchen1017918/dsh-fusion/tree/full-bundled) 分支，
> 按需下载。

一个把 [DeepSeek Harness](https://github.com/deepseek-ai/dsh) 装到手机上的引导程序：
探测这台设备 → 选出适合它的构建 → 多渠道路由 + **断点续传**拉下来 → 交给系统安装。

**5.43 MB。** 因为它什么都不带 —— 不带 Node 运行时、不带内核树、不带语音库。

---

## 为什么要拆

原来 `main` 是一个 **107.7 MB** 的一体化 APK，同时塞进了 `arm64-v8a` 和 `x86_64`
两套运行时。问题很直接：

> 一台 arm64 手机，永远用不到那 100 MB 的 x86_64 运行时。

而且那还只是「Android」一条线。纯血鸿蒙的产物形态完全不同（HAP），塞不进同一个包。

```
拆之前：107.7 MB 的一体化 APK（两套 ABI 都带上）
拆之后：5.43 MB 安装器 + 按需拉取你真正需要的那一份
```

| | 大小 | 说明 |
|---|---|---|
| **安装器**（本分支） | **5.43 MB** | 探测 + 匹配 + 下载 + 安装引导 |
| 一体化应用（`full-bundled`） | 107.7 MB | 自带运行时与内核树，装上即用 |

---

## 它怎么决定给你装什么

**不让你选 ABI。** 绝大多数人不知道自己的手机是 arm64 还是 x86_64，
而选错的后果是装上就崩（`UnsatisfiedLinkError`）—— 错误信息还不会说明原因。

所以能测的一律测：架构、系统版本、内存、可用存储、系统 WebView 版本。

**它还会探测你是否在模拟器 / 兼容层里。** 这不是多此一举：本项目的核心引擎依赖
**PRoot**（Linux `ptrace`），而应用宝电脑版那类 VirtualBox 环境未必放行 `ptrace` ——
引擎会起不来。这种事必须在**下载之前**说，而不是让你下完 100 MB 再崩。

### 断点续传 + 多渠道路由

手机上下 100 MB 的包，**中断是常态不是异常**：切后台、锁屏、地铁进隧道、换 Wi-Fi。

- **断点续传**：写到 `.part` 文件，下次带 `Range` 头接着下。断在第 90% 不用从头再来
- **多渠道路由**：国内直连 GitHub 经常只有几十 KB/s。一个渠道失败自动换下一个

两者必须一起做：单一渠道 + 续传 = 渠道挂了永远下不完；多渠道 + 不续传 = 每次换渠道都从 0 开始。

> 有个坑值得单独说：**服务器不一定支持续传**。如果它忽略 `Range` 直接返回 200 全文，
> 而我们继续往 `.part` 后面追加，就会得到「头部是旧数据、尾部是新数据」的损坏文件 ——
> **而且大小看起来是对的**，只有 sha256 能发现。所以这里严格检查 `206 Partial Content`，
> 见到 200 就从头重写。

---

## 有一个死结，和它的解法

纯血鸿蒙（HarmonyOS 5/6 及以后，NEXT 线）**原生不运行 Android APK**。所以：

> **安装器本身也装不到纯血鸿蒙上。**

这就是为什么仓库里除了 APK 还有 **[网页版](web/index.html)**：

| 你的环境 | 谁来做判定 | 结果 |
|---|---|---|
| Android | APK 安装器 | 探测硬件 → 拉对应产物 |
| **纯血鸿蒙** | **网页版**（APK 装不上，但浏览器能开） | 引导到社区的原生实现 |
| iOS | 网页版 | 明确说装不了 |
| 桌面浏览器 | 网页版 | 提示「你检测的是电脑，不是手机」 |

网页能拿到的东西比 APK 少得多（没有 ABI、没有内存），但它**恰好能判定最要命的那个问题**。

---

## 分支

| 分支 | 是什么 |
|---|---|
| **`main`**（本分支） | **安装器** |
| [`full-bundled`](https://github.com/yuchen1017918/dsh-fusion/tree/full-bundled) | Android 一体化应用（原 main）—— 5 个模块、双运行时后端、SafeMode、本地大模型 |
| `experiment/harmonyos-hap` | 纯血鸿蒙方案 + 社区 9 个项目优点拆解 |

详见 **[docs/BRANCHES.md](docs/BRANCHES.md)**。

---

## 清单：整个架构的契约

安装器**不认识「平台」是什么**，它只认识 [`catalog/artifacts.json`](catalog/artifacts.json)：

```json
{
  "id": "android-arm64-full",
  "branch": "full-bundled",
  "platform": "android",
  "abi": "arm64-v8a",
  "minApi": 26,
  "minRamGb": 3.0,
  "minStorageMb": 1500,
  "bytes": 112950000,
  "sha256": "…"
}
```

所以**加一个新平台不需要重新发安装器** —— 往清单里加一条就行
（清单是联网拉的，随包那份只是没网时的兜底）。

`bytes` 与 `sha256` **必须**用 `tools/gen-catalog.py` 从真实产物生成：

```bash
python3 tools/gen-catalog.py --apk DSH-Fusion-v1.2-arm64.apk --id android-arm64-full
```

手填必漂 —— 改一行代码重新打包，字节数就变了。漂了的后果是用户下载完校验失败，
而错误信息只会说「文件损坏」。

---

## 构建安装器

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=/path/to/gradle-8.9/bin:$PATH
gradle :app:assembleDebug
```

要求 JDK 17+、Android SDK（compileSdk 35）。**不需要 NDK** —— 安装器里没有一行原生代码。

---

## 许可证

安装器自身是 **MIT**。它**不含** PRoot 等 GPL 组件（那些在 `full-bundled` 分支的产物里）。
`assets/licenses/` 里的许可证文本是为**下载下来的产物**准备的说明。

---

## 诚实的边界

- **从未在真机上运行过。** 所有验证都是静态的（编译、单元测试、自检脚本）
- **下载渠道的可用性没有长期验证**。国内的 GitHub 加速服务生命周期普遍不长，
  渠道列表在 `catalog/artifacts.json` 里，挂了改一条就行 —— 这也是把它做成数据而不是代码的原因
- **`full-bundled` 的产物尚未上传到 Releases**，所以清单里的 `bytes`/`sha256` 还是空的，
  安装器会明确显示「该产物尚未发布」而不是假装能下
