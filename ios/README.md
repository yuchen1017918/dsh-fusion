# iOS 分支 · experiment/ios-build

> **⚠ 先说许可证**：用本分支的产出构建出的 iOS app **必然是 GPL-3.0**
> （iSH 模拟器是 GPLv3 且被静态链接）。详见 [docs/LICENSE-IMPLICATIONS.md](docs/LICENSE-IMPLICATIONS.md)。

---

## 这个分支在做什么

iOS 上「本地跑 DSH」只有一条可行的路：**用户态模拟器**。
因为 iOS 禁止 JIT、禁止 `ptrace`、限制子进程 —— 我们 Android 版那套
「bionic Node + PRoot」在 iOS 上**一行都用不了**。

社区项目 [everettjf/dsh-ios](https://github.com/everettjf/dsh-ios) 已经把这条路走通了
（内嵌 iSH-ARM64 模拟器跑 Alpine + Node 22 + dsh，WKWebView 加载官方 Web UI，
**真机验证过**，iPad Air M3 / iPhone 17 Pro）。

**所以本分支不重造那个 app** —— 没有 Mac 就是零验证能力，写一千行编译不了的 Swift 不如不写。

**本分支做的是：把 guest rootfs 的构建从 macOS 搬到 Linux。**

### 为什么这件事有价值

上游的构建流程三步都要 iSH，而 iSH 要 macOS 编译：

```
fakefsify alpine.tar.gz → 在模拟器里 apk add → 组装 → unfakefsify
     ↑ macOS              ↑ macOS            ↑ macOS
```

于是「想给 iOS 版做点贡献」的门槛变成了「你得先有台 Mac」。

**但关键观察是**：`fakefsify` / `unfakefsify` 只是让模拟器能跑 `apk` 的
**中间手段，不是交付格式** —— 最终产物 `root.tar.gz` 就是一个平凡的 tar.gz。
而里面的东西，**没有一样需要执行 aarch64 代码才能准备**：

| 步骤 | 为什么能在 Linux 上做 |
|---|---|
| Alpine minirootfs | 就是个 tar.gz |
| **apk 包** | **它本身也是 tar.gz** —— 实测 `tar -xzf busybox.apk` 直接解出完整内容 |
| npm 树 | `npm install --os=linux --cpu=arm64 --libc=musl` 不需要目标架构能跑 |
| overlay | 纯文件 |

**唯一的缺口**是 `node-pty` 为 musl 的原生重建（要跑 aarch64 的 node-gyp），
脚本会把待补步骤写进 guest 的 `/etc/dsh-rootfs-build-notes`，而不是假装做完了。

---

## 用法

```bash
# 全流程（约 5 分钟，取决于网络）
python3 ios/scripts/build-rootfs-linux.py

# 或分阶段
python3 ios/scripts/build-rootfs-linux.py --stage base       # Alpine 基础（3.9 MB）
python3 ios/scripts/build-rootfs-linux.py --stage packages   # apk 依赖闭包
python3 ios/scripts/build-rootfs-linux.py --stage npm        # dsh 的 npm 树
python3 ios/scripts/build-rootfs-linux.py --stage overlay
python3 ios/scripts/build-rootfs-linux.py --stage pack       # → build/root.tar.gz

# 许可证守卫（会逐行比对上游，确认我们没抄）
python3 ios/scripts/check-license.py
```

产出：`ios/build/root.tar.gz` + `.sha256`（供 app 比对，与上游同一约定）。

---

## 实测状态

| 阶段 | 状态 |
|---|---|
| Alpine 基础系统 | ✅ **实测通过**（8.5 MB，绝对符号链接保住） |
| apk 依赖闭包 | ✅ **实测通过**（22 个包，27.9 MB 压缩缓存） |
| npm 树 | ⏳ 待跑 |
| overlay + 打包 | ⏳ 待跑 |
| node-pty musl 重建 | ❌ **需要 aarch64 执行环境**（Mac 上补，各一两条命令） |
| iSH 编译 / Xcode / 签名 / 装机 | ❌ **需要 macOS + Apple 账号** |

---

## 你在 iOS 上到底该走哪条

| 你想要的 | 走哪条 | 许可证 |
|---|---|---|
| **就想在 iPhone 上用 DSH** | **PWA**（Safari 添加主屏幕，零安装零签名） | 不涉及 |
| 有台电脑常开 | 远程客户端（WSS 连过去） | 你自己选 |
| 有 Apple Silicon Mac | 直接编译上游 `everettjf/dsh-ios` | **GPL-3.0** |
| 没有 Mac 但想参与 | **本分支**（把门槛从「有台 Mac」降到「有台 Linux」） | 脚本 MIT |
| 想上架 App Store | ❌ iSH 路线走不通 | GPL-3.0 + 模拟执行性能 |

---

## 文件

```
ios/docs/IOS-PLAN.md              三条路线的取舍 + 我们为什么这样做
ios/docs/LICENSE-IMPLICATIONS.md  GPL 影响的分层分析（必读）
ios/scripts/build-rootfs-linux.py 构建器（实测可用）
ios/scripts/check-license.py      许可证守卫（可复跑）
ios/rootfs/overlay/               打进 guest 的覆盖层
```
