# 分支体系

`main` **不是应用本身** —— 它是一个**安装器**。真正的实现按平台分到各分支，
安装器读 [catalog/artifacts.json](../catalog/artifacts.json) 决定该给这台设备装什么。

## 为什么这么分

原来 `main` 是一个 107.7 MB 的一体化 APK：自带 `arm64-v8a` + `x86_64` 两套 Node/PRoot
运行时、官方内核树、语音库。它有个明显的浪费 ——

> **一台 arm64 手机永远用不到那 100 MB 的 x86_64 运行时，反之亦然。**

而且它还只是「Android」这一条线。纯血鸿蒙那条线的产物形态完全不同（HAP），
塞进同一个包是不可能的。

所以拆成：

```
main（安装器，5.43 MB）
  │  探测设备 → 匹配产物 → 多渠道路由 + 断点续传 → 交给系统安装
  │  它不认识「平台」是什么，只认识清单
  │
  ├── full-bundled          Android 一体化应用（原 main 的内容）
  ├── experiment/harmonyos-hap      纯血鸿蒙方案（社区已有成熟实现）
```

**关键设计**：安装器**不含任何平台专属代码**。加一个新平台只需要往
`catalog/artifacts.json` 里加一条 —— 不用重新发安装器（清单是联网拉的，
随包那份只是没网时的兜底）。

## 各分支

| 分支 | 是什么 | 产物 |
|---|---|---|
| `main` | **安装器**：设备探测 + 产物匹配 + 断点续传 | 5.43 MB APK |
| `full-bundled` | Android 一体化应用（原来的 main） | 107.7 MB APK（arm64 / x86_64 / universal） |
| `experiment/harmonyos-hap` | 纯血鸿蒙方案文档 + 社区项目拆解 | 暂无（见该分支文档） |

## 安装器的判定逻辑

它**不是**让用户选 ABI —— 绝大多数人不知道自己的手机是 arm64 还是 x86_64，
而选错的后果是装上就崩（`UnsatisfiedLinkError`），错误信息还不会说明原因。

所以能测的一律测：

| 维度 | 来源 | 用途 |
|---|---|---|
| ABI | `Build.SUPPORTED_ABIS` | 选 arm64 还是 x86_64 产物 |
| 系统版本 | `Build.VERSION.SDK_INT` | 卡 `minApi` |
| 内存 | `ActivityManager.MemoryInfo` | 卡 `minRamGb`（小内存机型跑不动本地模型） |
| 可用存储 | `StatFs` | 卡 `minStorageMb` |
| WebView | `WebView.getCurrentWebViewPackage()` | 低于 100 给警告 |
| **模拟器/兼容层** | 指纹、product、hardware、qemu 痕迹文件 | ⚠️ 见下 |

### 为什么专门探测「模拟器 / 兼容层」

本项目的核心引擎依赖 **PRoot**（Linux `ptrace`）。在 VirtualBox 里的安卓
（应用宝电脑版）、或某些厂商兼容层中，`ptrace` 未必被放行 —— **引擎会起不来**。

这种事必须**在下载之前**告诉用户，而不是让他下完 100 MB 再崩。

### 一个死结，和它的解法

纯血鸿蒙**原生不运行 APK**，所以——

> **安装器本身也装不到纯血鸿蒙上。**

这就是为什么除了 APK **还有一个 [网页版](../web/index.html)**：

| 环境 | 谁来做判定 |
|---|---|
| Android | APK 安装器（能拿到完整硬件信息） |
| **纯血鸿蒙** | **网页版**（浏览器能开，APK 开不了）→ 引导到社区实现 |
| iOS | 网页版 → 明确说装不了 |
| 桌面 | 网页版 → 提示「你检测的是电脑」 |

网页能拿到的东西比 APK 少得多（没有 ABI、没有内存），但它**恰好能判定最要命的那个问题**。

## 想加一个平台？

1. 建分支实现它
2. 构建产物、上传到渠道
3. 往 `catalog/artifacts.json` 加一条（`bytes` 与 `sha256` 用
   `tools/gen-catalog.py` 从真实产物生成，别手填 —— 手填必漂，
   漂了的后果是用户下载完校验失败，而错误只会说「文件损坏」）
4. 不用动安装器代码
