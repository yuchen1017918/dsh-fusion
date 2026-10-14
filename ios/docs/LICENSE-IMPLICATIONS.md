# 许可证影响分析 · iOS 分支

> **一句话**：本分支的**代码**是 MIT，但**它产出的 rootfs 含 GPL 软件**，
> 而**用它构建出的 iOS app 必然是 GPL-3.0**。
> 这不是可以选择的事，是链接 iSH 的必然结果。

---

## 一、事实（都核实过，不是推测）

| 组件 | 许可证 | 怎么被用 |
|---|---|---|
| [`everettjf/dsh-ios`](https://github.com/everettjf/dsh-ios) | **GPL-3.0** | 它是 iOS 上唯一真机验证过的本地方案 |
| [iSH](https://github.com/ish-app/ish) / [iSH-ARM64](https://github.com/OpenMinis/ish-arm64) | **GPLv3 + 附加 App Store 条款** | 上游**静态链接**进 app → 整个 app 被传染 |
| Alpine 包（busybox / bash / git / musl…） | 各种（GPL / LGPL / MIT / BSD） | 打进 guest 镜像 `root.tar.gz` |
| `@deepseek-ai/dsh` 及其 npm 依赖 | MIT 及其他 | 同上 |
| **本分支的脚本** | **MIT** | 我们自己的代码 |

上游自己的 `LICENSE.md` 写得很清楚：

> DSH is licensed under the **GNU General Public License v3.0**, because it
> compiles and statically links the [iSH] / [iSH-ARM64] emulator, which is
> GPLv3 with the additional App Store terms in `ish-arm64/LICENSE.IOS`.

**为什么必须链接 iSH**：iOS 禁止 JIT、禁止 ptrace、限制子进程。
唯一能跑起 Node 的路线是**用户态模拟器**（解释执行 aarch64），而 iSH 是 GPLv3。

**所以「iOS 上本地跑 DSH」这条路，许可证上是绑死的：想走就得 GPL-3.0。**

---

## 二、对本仓库的影响：分三层，结论不同

### (a) 我们写的代码 → 保持 MIT ✅

`ios/scripts/build-rootfs-linux.py` 是**从零写的**，没有复制上游表达。

**审计过，不是声称**：把上游 `scripts/build-rootfs.sh`（129 行）与我们
（458 行）逐行比对，**完全相同的非注释行只有 1 处**：

```
test -f build/Release/pty.node
```

那是一条**事实性的验证命令**（确认 node-gyp 编译产物存在），
属于「不受版权保护的事实/方法」，不是创作表达。

其余相似全是**命令名、包名、文件名**：
`node-gyp rebuild` · `unfakefsify` · `sharp-linux-arm64` · `dump-config` …
这些是接口与事实，同样不受版权保护。

> 换句话说：我们**读了**上游的流程（读不构成侵权），**理解了它要什么**，
> 然后**自己写了一份**。这是干净的。

### (b) 我们产出的 `root.tar.gz` → 分发它就承担里面那些包的义务 ⚠️

rootfs 里是 Alpine 的二进制包，其中 busybox / bash / git 等是 **GPL**。
**分发二进制 = 成为这些 GPL 软件的分发者**，义务是：

1. 随包提供对应许可证全文
2. 提供源码获取方式
3. 不加额外限制

这与「分发任何一个 Linux 发行版」是同一种义务，不特殊。
但**必须做**，不能装作没有。

### (c) 一个链接了 iSH 的 iOS app → **必须 GPL-3.0** ❌

这是用户指出的那条，也是最重要的一条：

> **如果你用本分支的 rootfs + iSH 构建出一个 app，那个 app 是 GPL-3.0。**

而且要连带遵守 iSH 的**附加 App Store 条款**（`ish-arm64/LICENSE.IOS`）——
上游作者明确写了「本项目不会提交到 App Store」，这也是原因之一。

---

## 三、所以该怎么选

| 你想要的 | 该走哪条 | 许可证 |
|---|---|---|
| **iOS 上本地跑 DSH** | iSH 路线（本分支提供 rootfs） | **GPL-3.0**（没得选） |
| **iOS 上远程连电脑** | 远程客户端 / PWA | 你自己的选择，可以 MIT |
| **上架 App Store** | ❌ iSH 路线走不通 | —— （GPL-3.0 + 模拟执行性能） |
| **只是想用起来** | PWA：Safari 添加主屏幕，**零安装零签名** | 不涉及 |

**如果你的目标是「做一个能上架的 iOS DSH」**，那 iSH 路线从一开始就不成立 ——
不是技术问题，是许可证与审核政策的问题。

---

## 四、我们做了什么来保证不踩线

| 措施 | 说明 |
|---|---|
| **不复制上游代码** | 已逐行审计（见上）；`ios/scripts/check-license.py` 可复跑 |
| **不把上游代码放进本仓库** | 本分支只有我们自己的脚本 + 文档 |
| **把 GPL 后果写进产物的 guest 里** | rootfs 的 `/etc/dsh-rootfs-build-notes` 明确写 |
| **构建时打印许可证提示** | 每次跑构建器都会输出，不依赖人记得 |
| **本分支 README 与方案里都标注** | 三处提及，避免「不知道就用了」 |

---

## 五、给下游的提醒

如果你基于本分支做了 iOS app：

1. **你的 app 必须 GPL-3.0** —— 要发布就附完整源码
2. **遵守 iSH 的附加 App Store 条款** —— 去读 `ish-arm64/LICENSE.IOS`
3. **rootfs 里的 Alpine 包各有各的许可证** —— 随包给许可证全文与源码地址
4. **别把本仓库的 MIT 当成你的 app 的许可证** —— 那只管我们的脚本

---

*核对日期：2026-10-14。许可证判断基于各仓库的 LICENSE / LICENSE.md 与实际使用方式（静态链接 vs 独立进程）。本文不是法律意见。*
