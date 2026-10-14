# iOS 版方案

> **结论先说：iOS 上「本地跑 DSH」已经有人做出来了，而且真机验证过。
> 我们这条分支的价值不是再造一个 —— 而是把其中最硬的一环（guest rootfs）
> 从「必须 macOS」搬到 Linux，让没有 Mac 的人也能参与。**

## 一、iOS 的硬约束（决定了方案的形状）

iOS 比 Android 严得多，而且这几条**没有绕过去的办法**：

| 约束 | 后果 |
|---|---|
| **禁止 JIT**（`mprotect(PROT_EXEC)` 被拒） | V8 只能 `--jitless` 跑 ⇒ **没有 WebAssembly** ⇒ 依赖 wasm 的库（如 undici 的 HTTP 解析器）全部要打垫片 |
| **禁止 `fork` / 受限的 `posix_spawn`** | 启动子进程这条路基本封死 |
| **禁止下载并执行代码**（App Store 2.5.2） | 不能像 Android 那样运行期下载 Node |
| **没有 `ptrace` 给第三方应用** | ❌ **PRoot 这条线彻底不可用** —— 我们 Android 版的核心机制在 iOS 上不存在 |
| 只能用 Xcode 构建 | 没有 macOS 就**一行都编译不了** |
| 需要开发者签名 | 没有 Apple 开发者账号就**装不到自己手机上**（免费账号也要 Mac/AltStore） |

**所以 iOS 上唯一可行的「本地跑」路线是：用户态模拟器。**
不需要 JIT、不需要 ptrace、不需要子进程 —— 它**解释执行** aarch64 指令。

## 二、社区三条路线（都已存在，不必重造）

| 路线 | 代表项目 | 怎么工作 | 取舍 |
|---|---|---|---|
| **① 本地模拟器** | [`everettjf/dsh-ios`](https://github.com/everettjf/dsh-ios) | 内嵌 **iSH-ARM64** 用户态模拟器 → 跑 Alpine + Node 22 + dsh → WKWebView 加载官方 Web UI | ✅ **真机验证过**（iPad Air M3 / iPhone 17 Pro）<br>❌ 性能受限（模拟执行）<br>❌ **GPL-3.0**（静态链接 iSH）<br>❌ 作者明确说不上架 |
| **② 远程客户端** | [`jayantTang/DSH_Mobile`](https://github.com/jayantTang/DSH_Mobile) · `kriskwok/dsh-ios-app` | WSS 连一台常开的电脑，界面在 iPhone | ✅ 简单、快、能上架<br>❌ **必须有电脑常开**、需要公网中转 |
| **③ PWA** | `guoyihub/deepseek-harness-mobile` | 局域网扫码配对桌面 Host，Safari 添加到主屏幕 | ✅ **零安装、零签名、不需要 Mac**<br>❌ 仍需要一台跑着 dsh 的机器 |

**①是最接近「iOS 版 DSH」的**，而且它把最难的部分（模拟器、NEON 指令补全、`waitpid` 回归、fetch polyfill）都做完了。

## 三、我们这条分支做什么

**不做**：再造一个 iOS app。①已经真机验证过，我们没有 Mac 就是**零验证能力** —— 写一千行编译不了的 Swift 不如不写。

**做**：**把 guest rootfs 的构建从 macOS 搬到 Linux。**

### 为什么这件事有价值

看上游的构建流程：

```
scripts/build-rootfs.sh（在 macOS 上跑）
  fakefsify alpine.tar.gz → fakefs      ← 需要 iSH 工具
  在模拟器里 apk add nodejs npm …        ← 需要 iSH 模拟器
  组装 payload
  unfakefsify fakefs → root.tar.gz      ← 需要 iSH 工具
```

**三步都要 iSH，而 iSH 要 macOS 编译。** 于是「想给 iOS 版做点贡献」的门槛变成了「你得先有台 Mac」。

### 但交付格式是普通的 tar.gz

关键发现：`fakefsify` / `unfakefsify` **只是让模拟器能跑 `apk` 的中间手段，不是交付格式**。
最终产物 `root.tar.gz` 就是一个平凡的 tar.gz —— 而里面的东西**没有一样需要执行 aarch64 代码才能准备**：

| 步骤 | 能不能在 Linux 上做 |
|---|---|
| 下载 Alpine aarch64 minirootfs 并解包 | ✅ 就是个 tar.gz |
| 解析 `APKINDEX` 算依赖闭包 | ✅ 文本处理 |
| 抓 apk 包并解包 | ✅ **apk 本身也是 tar.gz** —— 不需要执行 |
| `npm ci --os=linux --cpu=arm64 --libc=musl` | ✅ npm 不需要目标架构能跑 |
| 放 overlay / `cordis.patch.yml` / `dsh-serve` | ✅ 纯文件 |
| 组装成 `root.tar.gz` | ✅ |

### 做不了的（如实列出）

| 步骤 | 为什么 |
|---|---|
| `node-pty` 为 musl 重建 | 需要跑 aarch64 的 `node-gyp` → 必须模拟<br>（它的 `install` 脚本是 `node scripts/prebuild.js \|\| node-gyp rebuild`，**网络好时能拿到预编译**） |
| 在 guest 里 `node --dump-config` 预建 profile | 需要能跑 aarch64 的 node |
| iSH 模拟器本身 | 要 macOS 编译 |
| Xcode 构建 / 签名 / 装机 | 要 macOS + Apple 账号 |

**所以产出是「一个 90% 完成的 rootfs」**，最后的原生重建与预建 profile 在有 Mac 时补上（各一两条命令）。

## 四、给你的建议

| 你的情况 | 建议 |
|---|---|
| **就想在 iPhone 上用 DSH** | 用 **③ PWA**（零门槛，今天就能用）；或 ②远程客户端（有常开电脑的话） |
| **有 Apple Silicon Mac** | 直接编译 [`everettjf/dsh-ios`](https://github.com/everettjf/dsh-ios) —— 它真机验证过，是最省事的路 |
| **没有 Mac 但想参与** | **用本分支的 rootfs 构建** —— 这是本项目存在的理由：把门槛从「有台 Mac」降到「有台 Linux 机器」 |
| **想上架 App Store** | ① 走不通（GPL-3.0 + 模拟执行性能）；只能走 ②/③ |

---

## 五、许可证的连带影响（这条必须先说）

`everettjf/dsh-ios` 是 **GPL-3.0**，因为**静态链接了 GPLv3 的 iSH/iSH-ARM64 模拟器**。

**这意味着**：如果你基于它做一个 app，你的 app 也必须是 GPL-3.0，
而且 iSH 还有附加的 App Store 条款（`ish-arm64/LICENSE.IOS`）。

对照：**我们这个仓库的 Android 版是 MIT**（PRoot 是独立进程，属聚合）。
**iOS 版走模拟器路线就是 GPL-3.0** —— 这是两条完全不同的许可路径，不能混为一谈。

> 我们**不复制**上游代码，只提供 rootfs 构建（数据，不是代码），
> 所以本分支可以保持 MIT。但**你用它构建出的 app 若链接了 iSH，就是 GPL-3.0**。
