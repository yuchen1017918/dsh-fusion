# 第三方组件与许可证声明 · 安装器

本文件管的是 **`main` 分支（安装器）**。完整应用的组件清单在
[`full-bundled`](https://github.com/yuchen1017918/dsh-fusion/blob/full-bundled/THIRD-PARTY-NOTICES.md) 分支上。

> 本文件不是法律意见。它把实际情况如实列出来 —— 具体合规判断请咨询律师。

---

## 这个安装器里有什么

安装器**故意很小**（5.45 MB），因为它不带任何运行时。它的第三方依赖只有四个：

| 组件 | 许可证 |
|---|---|
| AndroidX（core-ktx / appcompat / lifecycle） | Apache-2.0 |
| Material Components | Apache-2.0 |
| Kotlin stdlib | Apache-2.0 |
| kotlinx-coroutines | Apache-2.0 |
| **本安装器自身代码** | **MIT** |

**没有 GPL 组件。** 安装器里没有一行原生代码、没有 PRoot、没有 Node。

对应的许可证全文在 `app/src/main/assets/licenses/`，会打进 APK。

---

## 但它会替你下载带 GPL 的东西 —— 所以这里也要说清

安装器负责把 `full-bundled` 分支的产物交到你手上，而**那个产物里含 PRoot（GPL-2.0-or-later）**。

用户有权知道他要装的东西受什么许可约束，所以 `assets/licenses/` 里也放了
`GPL-2.0.txt` 与 `LGPL-3.0.txt`，并在 `INDEX.txt` 里明确区分「安装器自身用的」和
「下载下来的产物会用的」两类。

### 那个产物要不要整体 GPL？

**不需要。** 简要理由（完整论证见 `full-bundled` 分支的声明文件）：

PRoot 是**独立可执行文件**，产物里用 `execve` 启动它，**没有任何链接**。
这是 GPL-2.0 意义上的**「聚合」（mere aggregation）** —— 把独立程序放在同一介质分发，
不使其他程序受 GPL 约束。

### 那个产物履行了什么义务

| 义务 | 状态 |
|---|---|
| 随包提供 GPL-2.0 / LGPL-3.0 全文 | ✅ |
| 提供对应源码 | ✅ 上游地址 + 唯一的修改脚本 |
| **标明对 PRoot 的修改**（GPL-2.0 §2(a)） | ✅ 用 `patchelf` 归一化 SONAME，改动已完整披露 |
| 不附加额外限制 | ✅ MIT 只覆盖自己的代码 |

---

## 许可

安装器的代码是 **MIT**（见 [LICENSE](LICENSE)）。MIT 只覆盖本项目的代码，
不覆盖上面列出的第三方组件。

---

*核对日期：2026-10-14。*
