# 纯血鸿蒙版（HAP）方案 · 分支 `experiment/harmonyos-hap`

> 这份文档回答一件事：**社区已经有 9 个鸿蒙 DSH 项目，「集合它们的优点做更好」在技术上意味着什么，
> 以及哪些部分在没有鸿蒙真机的前提下就做得成。**

## 一、先说结论

**「比 dshm 更好」在架构上是有意义的，因为各家的优点恰好是正交的 —— 但瓶颈不是代码，是签名与真机。**

| 门槛 | 状态 |
|---|---|
| 构建工具链 | ✅ **可获取**（OpenHarmony SDK 2.49 GB，华为云镜像 5.8 MB/s） |
| 代码与架构 | ✅ 可以做 |
| **签名（装到手机）** | ❌ **需要华为开发者证书** —— dshm 的 README 明说「签名材料不在库内」 |
| **真机验证** | ❌ **本机没有鸿蒙设备** —— 社区所有修复都来自真机 |

**一句话**：能在没有真机的情况下做出来的部分（架构、构建期组件、工程骨架）值得做；
需要真机的部分（UI 手感、冷启动、心跳、权限弹窗）**只能靠你在设备上跑，我来改**。

## 二、社区 9 个项目的优点拆解（这是「集合」的前提）

各家解决的**不是同一个问题**，而是五个正交的子问题：

| 项目 | ⭐ | 它真正解决的问题 | 独特实现 |
|---|---|---|---|
| [sol5766/dshm](https://github.com/sol5766/dshm) | 45 | **运行时可执行性** | ① 自建 `jitless` Node 随包分发<br>② **ELF 构建期自签名**（端侧 execve 只放行带 `.codesign` 的 ELF）<br>③ **`gitcompat.c` + `gitcompat.so`**（源码在库，LD_PRELOAD 兼容垫片）<br>④ python3.12/git/ripgrep/busybox 随包 + 启动逐项 exec 探测<br>⑤ 核心版本切换/回滚<br>⑥ **已签名 release hap（306 MB）** |
| [hnmrxz/HDSH](https://github.com/hnmrxz/deepseek-harness-desktop-HarmonyOS) | 4 | **界面形态** | ① **双形态**：≥600vp 用 `WebShell` 直载官方 Web UI；<600vp 用 `RemoteShell` **ArkTS 原生复刻**<br>② `MobileTheme.ets` **逐值移植** DSH Mobile 的 `ui/theme/*.kt`<br>③ `Ds*` 原生组件基元族<br>④ `parity-matrix.md` 逐行登记对等性 |
| [QinpanWan/dsh-harmonyos-pc](https://github.com/QinpanWan/dsh-harmonyos-pc) | 25 | **对话质量与成本** | ① **8 套预设**，缓存命中率实测 **93.8%–98.0%**<br>② `dsh-prompt-antivirus` 全局防注入（金丝雀守卫）<br>③ 11 道基准 A/B 跑分数据<br>④ node_modules 补丁脚本 |
| [shd101wyy/…harmonyos](https://github.com/shd101wyy/deepseek-harness-harmonyos) | 3 | **文件系统坑** | ① 实测证明**「hmdfs 忽略 chmod，写 600 后 stat 仍返回 660」**<br>② koffi/sharp 原生模块构建 + 加载自检（失败自动清理重建） |
| [1na-ko/dsh-hdc-bridge](https://github.com/1na-ko/dsh-hdc-bridge) | 19 | **开发闭环** | ① hdc 设备闭环调试 + 设备面板（**官方 client 插件形态**）<br>② **Tier-1 官方知识层随包**（28 篇 1.7 MB，CC-BY-4.0） |

其余：`fellow99/dsh-desktop-hos`（Electron 包装）· `MakeBlackSheepGreat/dsh-OHDSH`（可复现实现）·
`QinpanWan/dsh-harmonyos-market`（鸿蒙插件市场）· `spike-faye-lei/dsh-harmonyos-arkts`（ArkTS 开发技能）·
`shizhonggang/homebrew-dsh-harmonyos` · `oheco/dsh-browser-control` · `mengde-tu/…harmonyos6`

### 各家强在哪、弱在哪

| | 运行时/工具链 | 界面形态 | 对话质量 | 文件系统 | 开发闭环 |
|---|---|---|---|---|---|
| dshm | ✅ **最强** | ⚠️ 断点判定，PC 出身 | — | ✅ | — |
| HDSH | ✅ | ✅ **最强**（双形态） | — | — | — |
| QinpanWan | ✅ | ⚠️ PC/平板 | ✅ **最强** | ✅ | — |
| shd101wyy | ⚠️ | — | — | ✅ **最早** | — |
| 1na-ko | — | — | — | — | ✅ **最强** |

**没有一家是全满的。** 这就是「集合」的空间 —— 而且它们的强项互不冲突。

## 三、鸿蒙端侧的硬约束（决定架构，不是选择）

| 约束 | 事实 | 后果 |
|---|---|---|
| **execve 受签名域管辖** | 端侧只能执行**自己签名域内**的原生二进制 | 所有原生二进制必须**构建期自签名**；这是与 Android（W^X）**完全不同**的一类约束 |
| **沙箱内没有 Node** | 系统不提供 | 运行时随包分发（约 300 MiB 包体换「装上就能跑」） |
| **jitless ⇒ 无 WASM** | 不申请 JIT 权限就必然如此（V8 的 `--jitless` 与 `--expose_wasm` 互斥） | `typeof WebAssembly === 'undefined'`；**凡是上游 `import('undici')` 的功能都要打垫片**（undici 的 HTTP 解析器是 WASM） |
| **hmdfs 忽略 chmod** | 写 `600` 后 `stat` 仍返回 `660` | 依赖文件权限的安全假设要重新检查 |
| **不支持硬链接** | 文件系统层面 | `node_modules` 的软链层、原子写、`link()` 回退都要垫片 |
| **无进程创建能力** | 上游硬约束 | `tool-bash` / `tool-pwsh` / `tool-fs-search` / `workflow-ptc` / `tool-workflow` 五行 preset 必须禁用 |

> 最后一条值得单独说：**「手机上没有进程创建能力」是上游的限制，不是鸿蒙独有的。**
> 我们的 Android 版遇到的是同一个问题（`node-pty` / `koffi` 装不上）。

## 三·补 · 签名域机制：这条线最硬、也最少被文档化的一块

**端侧 `execve` 对第三方 ELF 一律拒绝（签名域策略），与权限位、创建者无关。**

dshm 里有实测记录：构建期跑 `binary-sign-tool sign -selfSign 1` 之后，
设备上的 exec 探测由 `rg=denied` 变为 `rg=ok`。这是**唯一**能让原生二进制跑起来的办法。

### 签名是怎么调的

```bash
java -jar binary-sign-tool.jar sign \
     -mode localSign -selfSign 1 \
     -inFile <src> -outFile <dst> \
     -signAlg SHA256withECDSA
```

判据是**魔数 `\x7fELF`**，不按扩展名猜 —— 这个细节很重要：脚本、文本、目录、symlink
都不需要签，签了也没用。

### 难的不是签名，是「在归档里签名」

端侧工具链（python / git / ripgrep / busybox）是以**归档**形式随包的，解包发生在设备上。
所以构建期要先解归档 → 签名 → 重新打包。而这中间有两个 Windows 侧的陷阱：

| 陷阱 | 后果 |
|---|---|
| bsdtar 无符号链接特权 | 每条 symlink 报 `Invalid argument` 且**条目丢失** → 重打包把归档**永久改坏** |
| 7z / Node 展开会**物化** symlink | `git-core` 从 8 MB 变成 **1.3 GB** |

**解法**：用 Python 的 `tarfile` **逐条目读出再原样写回** —— symlink 保持 symlink 条目
（不落盘、不需要特权），只让普通文件落盘去签名。这是唯一既保结构又能改字节的做法。

### 还有一条文件系统约束：hmfs 禁 symlink

所以端侧解包时 symlink 会失败（仅告警跳过），需要启动后**复制补齐关键 symlink**
（dshm 的 `main.js finishToolchainExtraction` 就是干这个的）。

> **这三段合起来说明一件事**：鸿蒙端侧的约束不是「沙箱更严」这么笼统，
> 而是一组具体的、互相牵连的机制 —— 签名域管 execve、hmfs 管 symlink、
> jitless 管 WASM。**每一条都会在上游代码的某个具体位置炸出来。**

## 四、要做的架构（「集合」的落点）

```
┌─ 一个 HAP ────────────────────────────────────────────────────┐
│  形态路由（按窗口宽度断点，取 HDSH 的方案）                      │
│    · ≥600vp → WebShell：直载官方 Web UI（不维护第二份桌面界面）  │
│    · <600vp → RemoteShell：ArkTS 原生移动壳                     │
│         └ MobileTheme 令牌 + Ds* 基元（逐值移植 DSH Mobile）     │
│                       │ HTTP /api/* · WS /api/remote.mux        │
│                       ▼                                         │
│  端侧 Host（Node 运行在应用进程内，jitless + --expose-internals）│
│    · 运行时与全部原生库随包分发 + 构建期自签名（取 dshm）         │
│    · undici 垫片 / link() 回退 / chmod 规避（取 shd101wyy+dshm）│
│    · 预设：缓存命中优先的鸿蒙对话模式（取 QinpanWan）            │
│    · DSH_HOME = <沙箱>/dsh/home（跨版本共享）                    │
└─────────────────────────────────────────────────────────────────┘
```

**四条设计原则**（对齐社区共识 + 官方立场）：

1. **不 fork、不魔改 dsh** —— 端侧差异只通过官方的组合面（profile / `cordis.patch.yml` / bundle）+ 运行期垫片 + 打包期补丁表达
2. **不申请特殊权限** —— 需要 JIT、可写可执行内存的方案一律不进入选型（保证上架可行）
3. **界面不撒谎** —— 失败必须给出下一步；能力边界（端侧 pending 的行）如实呈现
4. **上游知识只出现在一处** —— 字段名、端点形状、事件类型集中在一个 compat 模块，升级上游时改一个地方

## 五、分阶段计划（按「能不能在没有真机的情况下做」切分）

### 阶段 0 · 不需要真机，现在就能做

- [x] 社区 9 项目优点拆解与本方案（本文档）
- [ ] **可复用构建期组件**（这是社区重复踩坑最多、却没人抽出来的部分）
  - ELF `.codesign` 签名工具（dshm 有 python 版，可做成通用的）
  - undici 垫片（模块名解析钩子）
  - 工具链归档自签名流程
  - 文件系统规避层（chmod / 硬链接 / link 回退）
- [ ] **工程骨架**：hvigor 工程结构 + 形态路由 + compat 模块边界

### 阶段 1 · 需要工具链（SDK 下载中）

- [ ] 用 OpenHarmony SDK 构建出 **unsigned HAP** 并跑通静态检查
- [ ] 端侧 Host 的启动链路（argv、`--expose-internals`、垫片注入）

### 阶段 2 · 需要你（我做不到的部分）

- [ ] **签名** —— 需要华为开发者证书（+ 调试证书需把设备 UDID 注册到开发者后台）
- [ ] **真机验证** —— 装上跑，把报错与现象给我，我来改
- [ ] 按真机反馈迭代 UI 手感 / 冷启动 / 心跳 / 权限弹窗

## 六、必须说清的一件事

**「比 dshm 更好」这句话，最后是在真机上验证的，不是在代码里。**

dshm 的 changelog 里那些修复 —— 62 秒冷启动阻塞、长连接心跳抖动（官方 2 s 间隔对有重计算的会话偏紧）、
`response(FormData)` 导致附件 RPC 响应体为空、托盘幂等回归 —— **没有一条是静态分析能发现的**。
它们全是真机踩出来的。

所以这个分支的现实形态是：**我把不需要真机的部分做到位（架构、构建期组件、骨架），
你负责在设备上跑，我负责改**。这也是我们在这台机器上跑通 Android 版的同一种分工。

---

*本文档整理自社区 9 个项目的公开 README（2026-10-12 读取），以及 OpenHarmony 6.0-Release SDK 的公开信息。*
