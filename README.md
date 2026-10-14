# 纯血鸿蒙（HAP）方案 · 实验分支

> **你在 `experiment/harmonyos-hap` 分支。** 这是**只有方案、没有实现**的一个分支。
>
> | 分支 | 是什么 |
> |---|---|
> | [`main`](https://github.com/yuchen1017918/dsh-fusion/tree/main) | 安装器（5 MB，探测设备 + 按需拉取） |
> | [`full-bundled`](https://github.com/yuchen1017918/dsh-fusion/tree/full-bundled) | **Android 完整应用**（108 MB） |
> | **`experiment/harmonyos-hap`**（你在这） | **纯血鸿蒙方案与调研** |
> | ~~`experiment/desktop-architecture`~~ | 已删除 —— 有用的部分（浏览器面板）已带金丝雀融合进 `full-bundled` |

---

## 这个分支回答什么

**社区已经有 9 个纯血鸿蒙 DSH 项目，`main`/`full-bundled` 的 Android 产物在纯血鸿蒙上装不了。
那「集合社区优点做更好」在技术上意味着什么？**

结论写在 [`docs/HAP-PLAN.md`](docs/HAP-PLAN.md)，这里只摘要点。

---

## 一、为什么 Android 产物装不上（这条必须先说）

华为从 HarmonyOS 5 起分了**两条线**，应用模型完全不同：

| 线路 | 内核 | 能装 APK 吗 |
|---|---|---|
| HarmonyOS 4.x 及更早（AOSP 底） | Linux | ✅ 能 |
| **HarmonyOS 5 / 6（纯血鸿蒙，NEXT 线）** | **HongMeng Kernel** | ❌ **原生不能** |

**怎么判断自己在哪条线上**：看「设置 → 关于本机」——
**同时出现**「OpenHarmony 版本」和「内核版本 HongMeng Kernel」就是纯血线
（AOSP 底的鸿蒙显示的是 Linux 内核版本，也不会单列 OpenHarmony 版本）。

纯血鸿蒙上运行安卓应用只有一条路：**卓易通**（基于 iSulad 轻量容器的沙盒安卓子系统）。
但据社区反馈，已鸿蒙化的应用不再允许通过卓易通安装，而**自建未上架的 APK 能否侧载，
本分支不下结论** —— 需要实测。

**10 秒验证法**：把 APK 拷进手机用文件管理器点它。报「应用是非正式发布版本，当前设备不支持安装」
之类就是走不通。

---

## 二、社区 9 个项目的优点拆解（这是「集合」的前提）

各家解决的是**五个正交的子问题** —— 没有一家全满，而强项互不冲突，这就是空间所在：

| 项目 | ⭐ | 它真正解决的问题 | 独特实现 |
|---|---|---|---|
| [sol5766/dshm](https://github.com/sol5766/dshm) | 45 | **运行时可执行性** | 自建 `jitless` Node 随包 · **ELF 构建期自签名** · `gitcompat.c` LD_PRELOAD 垫片 · python/git/ripgrep 随包 · **已签名 release hap（306 MB）** |
| [hnmrxz/HDSH](https://github.com/hnmrxz/deepseek-harness-desktop-HarmonyOS) | 4 | **界面形态** | 600vp 分界：桌面态 `WebShell` 直载官方 UI，手机态 `RemoteShell` **ArkTS 原生复刻** · `MobileTheme` 逐值移植 |
| [QinpanWan/dsh-harmonyos-pc](https://github.com/QinpanWan/dsh-harmonyos-pc) | 25 | **对话质量与成本** | 8 套预设，缓存命中实测 **93.8%–98.0%** · `dsh-prompt-antivirus` 防注入 |
| [shd101wyy/…harmonyos](https://github.com/shd101wyy/deepseek-harness-harmonyos) | 3 | **文件系统坑** | 实测证明 **hmdfs 忽略 chmod**（写 600 后 stat 仍 660） |
| [1na-ko/dsh-hdc-bridge](https://github.com/1na-ko/dsh-hdc-bridge) | 19 | **开发闭环** | hdc 设备调试 · Tier-1 官方知识层随包 |

其余：`fellow99/dsh-desktop-hos`（Electron 包装）· `MakeBlackSheepGreat/dsh-OHDSH` ·
`QinpanWan/dsh-harmonyos-market`（鸿蒙插件市场）· `spike-faye-lei/dsh-harmonyos-arkts` ·
`shizhonggang/homebrew-dsh-harmonyos` · `oheco/dsh-browser-control` · `mengde-tu/…harmonyos6`

**许可证**：dshm / HDSH / QinpanWan 都是 **MIT**；
`shd101wyy` **没有 LICENSE 文件**（默认保留所有权利）—— 要参考它的实现得先问作者。

---

## 三、鸿蒙端侧的硬约束（决定架构，不是选择）

| 约束 | 事实 | 后果 |
|---|---|---|
| **execve 受签名域管辖** | 端侧只能执行**自己签名域内**的原生二进制 | 所有原生二进制必须**构建期自签名**。这是与 Android（W^X）**完全不同**的一类约束 |
| 沙箱内没有 Node | 系统不提供 | 运行时随包分发（约 300 MiB 换「装上就能跑」） |
| **jitless ⇒ 无 WASM** | V8 的 `--jitless` 与 `--expose_wasm` 互斥 | `typeof WebAssembly === 'undefined'`；**凡是上游 `import('undici')` 的功能都要打垫片** |
| **hmdfs 忽略 chmod** | 写 600 后 stat 仍返回 660 | 依赖文件权限的安全假设要重新检查 |
| 不支持硬链接 · hmfs 禁 symlink | 文件系统层面 | `node_modules` 软链层、原子写、`link()` 回退都要垫片 |
| 无进程创建能力 | 上游硬约束 | 五行 preset 必须禁用（`tool-bash`/`tool-pwsh`/`tool-fs-search`/`workflow-ptc`/`tool-workflow`） |

> 最后一条值得单独说：**「手机上没有进程创建能力」是上游的限制，不是鸿蒙独有的。**
> Android 版遇到的是同一个问题（`node-pty` / `koffi` 装不上）。

### 签名域机制（社区最少文档化、又人人都要的一块）

```bash
java -jar binary-sign-tool.jar sign \\
     -mode localSign -selfSign 1 \\
     -inFile <src> -outFile <dst> -signAlg SHA256withECDSA
```

判据是**魔数 `\\x7fELF`**，不按扩展名猜。

**但难的不是签名，是「在归档里签名」** —— 端侧工具链以 tar.gz / apk 随包，解包发生在设备上：

| 陷阱 | 后果 |
|---|---|
| bsdtar 无符号链接特权 | 每条 symlink 报错且**条目丢失** → 重打包把归档**永久改坏** |
| 7z / Node 展开会**物化** symlink | `git-core` 从 8 MB 变成 **1.3 GB** |
| **解法** | Python `tarfile` **逐条目读出再原样写回** —— symlink 保持条目（不落盘、不需特权） |

还有一条：**hmfs 禁 symlink**，所以端侧解包时 symlink 会失败（仅告警跳过），
需要启动后**复制补齐关键 symlink**。

---

## 四、分阶段计划

按「**能不能在没有真机的情况下做**」切分：

### 阶段 0 · 不需要真机

- [x] 社区 9 项目优点拆解与本方案（`docs/HAP-PLAN.md`）
- [ ] **可复用构建期组件** —— 社区重复踩坑最多、却没人抽出来的部分
  - ELF `.codesign` 签名工具
  - undici 垫片（无 WASM 时的模块名解析钩子）
  - 工具链归档自签名流程
  - 文件系统规避层（chmod / 硬链接 / link 回退）
- [ ] 工程骨架

### 阶段 1 · 需要工具链（OpenHarmony SDK 2.49 GB，华为云镜像可下）

- [ ] 构建出 **unsigned HAP** 并跑通静态检查
- [ ] 端侧 Host 启动链路（argv、`--expose-internals`、垫片注入）

### 阶段 2 · 需要你（我做不了的部分）

- [ ] **签名** —— 需要华为开发者证书。**dshm 的 README 明说「签名材料不在库内」** ——
      任何自己构建出来的 HAP 都是 unsigned 的，装不上手机
- [ ] **真机验证** —— 装上跑，把报错给我，我来改

---

## 五、必须说清的一件事

**「比 dshm 更好」这句话，最后是在真机上验证的，不是在代码里。**

dshm 的 changelog 里那些修复 —— **62 秒冷启动阻塞**、长连接心跳抖动、
`Response(FormData)` 导致附件 RPC 响应体为空、托盘幂等回归 ——
**没有一条是静态分析能发现的**。它们全是真机踩出来的。

所以这个分支的现实形态是：**不需要真机的部分做到位（架构、构建期组件、骨架），
真机部分靠你跑、我来改**。这也是 Android 版在这台机器上的同一种分工。

---

## 六、建议：先用社区已有的

**如果你的手机是纯血鸿蒙，现在最省事的是直接用 [dshm](https://github.com/sol5766/dshm/releases)
的已签名 HAP**（306 MB，支持手机/折叠屏/平板/2in1，核心版本比本项目用的还新）。

这个分支的价值在于**把「怎么做」沉淀下来**，而不是立刻做一个能用的替代品。

---

*方案详文：[docs/HAP-PLAN.md](docs/HAP-PLAN.md) —— 164 行，含完整论证与代码出处。*
