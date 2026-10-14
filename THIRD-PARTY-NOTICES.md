# 第三方组件与许可证声明

本文件说明 `DSH Fusion`（下称"本应用"）分发或调用的第三方组件、
它们的许可证，以及**我们为履行这些许可证做了什么事**。

> 本文件不是法律意见。它是把实际情况如实列出来 ——
> 具体合规判断请咨询律师。

---

## 一、直接回答：本应用要改用 GPL 吗？

**不需要。**

**理由**：本应用唯一的强 copyleft 组件是 **PRoot（GPL-2.0-or-later）**，
而它是**独立可执行文件**，我们用 `execve` 启动它，**没有任何链接**：

```
我们的 Kotlin 代码  ──execve()──▶  libproot.so（独立进程）──ptrace──▶ 目标进程
                   命令行参数 + 文件系统 + bind mount
```

这不是衍生作品，而是 **GPL-2.0 意义上的「聚合」（mere aggregation）** ——
把独立程序放在同一介质（这里是同一个 APK）分发，不使其他程序受 GPL 约束。
这也正是 Linux 内核（GPL-2.0）能与大量闭源用户态程序共存的法律基础。

**判断依据**（不是推测）：
- `libproot.so` 二进制内明写 `licensed under GPL v2 or later`
- 上游 [proot-me/PRoot](https://github.com/proot-me/proot) 的 SPDX 为 `GPL-2.0`
- 我们调用它的方式见 `engine/.../runtime/ProotLauncher.kt`：
  `args += paths.prootBin.absolutePath` 然后交给 `ProcessBuilder` —— 纯 exec，无 JNI、无 dlopen

**但分发 GPL 软件本身触发义务，而这些义务我们之前一项都没做。** 见第三节。

---

## 二、完整清单

### 2.1 我们的代码

| 组件 | 许可证 |
|---|---|
| 本应用全部 Kotlin / 脚本 / 插件源码 | **MIT**（见 [LICENSE](LICENSE)） |
| `plugins/dsh-mobile-layout`（自研移动端布局插件） | **MIT** |

### 2.2 随 APK 分发的原生库（`jniLibs/`，每个 ABI 15 个）

| 库 | 上游 | 许可证 | copyleft |
|---|---|---|---|
| **`libproot.so`** | [proot-me/PRoot](https://github.com/proot-me/proot) | **GPL-2.0-or-later** | ⚠️ **强** |
| **`libproot-loader.so`** | 同上（PRoot 的 loader 组件） | **GPL-2.0-or-later** | ⚠️ **强** |
| **`libproot-loader32.so`** | 同上 | **GPL-2.0-or-later** | ⚠️ **强** |
| `libtalloc.so` | [talloc](https://talloc.samba.org/) (Samba) | **LGPL-3.0-or-later** | ⚠️ 弱 |
| `libnode.so` | Node.js | MIT | — |
| `libcrypto.so` / `libssl.so` | OpenSSL 3.x | Apache-2.0 | — |
| `libcares.so` | c-ares | MIT | — |
| `libsqlite3.so` | SQLite | Public Domain | — |
| `libz.so` | zlib | Zlib | — |
| `libicuuc.so` / `libicui18n.so` / `libicudata.so` | ICU | Unicode-3.0 | — |
| `libc++_shared.so` | LLVM libc++ | Apache-2.0 WITH LLVM-exception | — |
| `libandroid-shmem.so` | Termux | ISC | — |

### 2.3 随 APK 分发的 JS 依赖（内核包，`assets/kernel/`）

官方 `@deepseek-ai/dsh` **v0.2.0-rc.2 是 MIT**，其 **533 个 npm 依赖**的许可证分布：

| 许可证 | 数量 |
|---|---|
| MIT | 426 |
| Apache-2.0 | 55 |
| ISC | 16 |
| BSD-3-Clause | 14 |
| BlueOak-1.0.0 | 5 |
| BSD-2-Clause | 2 |
| MPL-2.0 | 1 |
| Python-2.0 | 1 |
| Unlicense / 0BSD / (MIT OR CC0-1.0) | 各 1 |

**零 GPL。** 每个包的许可证文本**已经随内核包分发**（包内 149 个 `LICENSE*` 文件）。

### 2.4 运行期下载（**我们不分发**，因此不承担分发义务）

| 组件 | 许可证 | 下载源 |
|---|---|---|
| Ubuntu 22.04 rootfs | 大量 GPL/LGPL（bash、coreutils、glibc…） | 清华 ubuntu-ports 镜像 |
| llama.cpp（`llama-server`） | MIT | Termux 源 |
| GGUF 模型权重 | 各自模型许可（如 Qwen 系为 Apache-2.0） | ModelScope / hf-mirror |

> **这一条对合规很关键**：Ubuntu rootfs 里有成百上千个 GPL 程序，
> 但因为**是用户自己下载、我们不随包分发**，我们不承担 GPL 的分发义务。
> 如果哪天改成随包预置，这条要重新评估。

### 2.5 Java 依赖

| 组件 | 许可证 | 说明 |
|---|---|---|
| AndroidX / Material Components | Apache-2.0 | 标准 |
| Kotlin stdlib / coroutines | Apache-2.0 | 标准 |
| `com.alphacephei:vosk-android` | Apache-2.0 | 离线语音 |
| `net.java.dev.jna:jna` | **LGPL-2.1-or-later 或 Apache-2.0（双许可）** | **我们按 Apache-2.0 使用**，规避 LGPL 义务 |

### 2.6 社区插件（**未使用其代码**）

我们对照过 6 个社区移动端插件，**只借鉴思路、未复制代码**：

| 项目 | 许可证 |
|---|---|
| `dsh-mobile-xc` | MIT |
| `bbtssama/dsh-webui-mobile` | MIT |
| `roojay/dsh-mobile-theme` | MIT |
| `@ddtcorex/dsh-maestro-mobile` | MIT |
| `NOirBRight/dsh-mobile` | MIT |
| `lehhair/dsh-mobile` | BSD-3-Clause |

**全部宽松，无 GPL**。即便将来复制了代码，也只需保留其版权声明。

---

## 三、我们为履行 GPL / LGPL 义务做了什么

### 3.1 ✅ 随包提供许可证全文

`app/src/main/assets/licenses/` 会被打进 APK：

```
GPL-2.0.txt      ← PRoot（关键）
LGPL-3.0.txt     ← libtalloc
Apache-2.0.txt   ← OpenSSL / Vosk / libc++
MIT.txt · BSD-3-Clause.txt · ISC.txt · Zlib.txt · MPL-2.0.txt
```

### 3.2 ✅ 提供对应源码

**PRoot**（GPL-2.0-or-later）与 **talloc**（LGPL-3.0-or-later）的完整源码：

- PRoot 上游：<https://github.com/proot-me/PRoot>
- talloc 上游：<https://talloc.samba.org/>
- 我们使用的构建产物来自 Termux 的 `proot` 与 `libtalloc` 包，
  其构建配方（含补丁）在 <https://github.com/termux/termux-packages>
- **本应用对二进制所做的全部修改脚本**：`tools/build-runtime.py`（见下节）

> 本项目不提供镜像源码。若你是本项目的分发对象且需要源码副本，
> 请提 Issue，我们会以可复制的方式提供。

### 3.3 ✅ 标明我们对 PRoot 的修改（GPL-2.0 §2(a) 的明确要求）

**GPL-2.0 第 2(a) 条**要求：分发修改过的版本时，必须在文件中**显著地**标明改动与日期。

**我们确实修改了 PRoot 的三个二进制**：

| 被改的文件 | 改动 | 为什么 |
|---|---|---|
| `libproot.so`<br>`libproot-loader.so`<br>`libproot-loader32.so` | `patchelf --set-soname <归一化名>`<br>`patchelf --replace-needed <原名> <归一化名>` | **Android 只解压 `lib*.so`**（W^X 要求可执行文件伪装成 so 放进 jniLibs），所以带版本号的 SONAME（如 `libtalloc.so.2`）必须归一化成 `libtalloc.so`，否则运行期找不到库 |

**实测证据**（`readelf -d` 可见改动后的状态）：

```
libproot.so 的 DT_NEEDED：libtalloc.so · libandroid-shmem.so · libc.so
                           ↑ 上游原本是 libtalloc.so.2
```

- **修改工具**：`patchelf`
- **执行修改的脚本**：`tools/build-runtime.py`（第 167–172 行附近的 `--replace-needed` / `--set-soname`）
- **修改的日期**：随每次构建发生，构建脚本本身在 git 历史里可追溯
- **修改的性质**：**仅改动态链接元数据**，不触碰任何被 GPL 覆盖的源码逻辑

### 3.4 ✅ 未对 GPL 组件附加额外限制

我们没有对 PRoot / talloc 施加任何超出 GPL 的额外条款。
本项目的 MIT 许可证（`LICENSE`）**只覆盖我们自己的代码**，不覆盖第三方组件。

---

## 四、给下游分发者的提醒

如果你要分发本应用的构建产物（APK），你同样需要：

1. 保留 `assets/licenses/` 里的许可证全文
2. 保留本文件
3. 保留 `tools/build-runtime.py`（它是 PRoot 修改的完整披露）
4. 若你**改动**了 PRoot/talloc 二进制，按 GPL-2.0 §2(a) 标明你的改动与日期
5. 若你把 **Ubuntu rootfs 预置进包里**（当前是运行期下载），
   你就变成了 GPL 软件的分发者，需自行评估并履行义务

---

*清单核对日期：2026-10-14。许可证判断基于各组件二进制内的声明、
其上游仓库的 SPDX 标识，以及随包分发的 `package.json` 字段。*
