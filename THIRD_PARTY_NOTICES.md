# 第三方组件与许可

**本文档管的是「随 APK 一起分发的东西」，不是本项目自己的代码。**
本项目的代码按 [MIT](LICENSE) 授权；下面这些组件各有各的许可证，必须分别遵守。

生成这份清单时逐条查过来源，不是凭记忆写的：ELf 元数据、`share/doc/*/copyright`、
以及 GitHub API 的 `license.spdx_id`（查询命令见文末）。

---

## 一、随 APK 分发的二进制

### ⚠️ 1. PRoot 5.1.107.96 — **GPL-2.0**

- 来源：Termux `proot` 包（上游 `proot-me/PRoot`，GitHub API 返回 `GPL-2.0`）
- 体积：247 KB（`libproot.so`，伪装成 `.so` 以取得可执行位）
- 用途：为「Ubuntu 容器」后端提供用户态 `chroot`/bind-mount

**分发义务（必须履行）：**

1. 附上 GPL-2.0 全文 —— 见 [`licenses/GPL-2.0.txt`](licenses/GPL-2.0.txt)；
2. 提供对应源码。上游源码：<https://github.com/proot-me/PRoot>；
   Termux 的构建配方与补丁：<https://github.com/termux/termux-packages/tree/master/packages/proot>；
3. 若你二次分发本 APK，同样要保留以上两条。

**对本项目代码的影响：无。**
PRoot 与 Android 应用之间是 `fork + execve` 的**独立进程**关系，
没有链接、没有共享地址空间，属于典型的「单纯聚合」（mere aggregation）。
GPL 的传染性不会波及调用它的 MIT 代码。这一点是刻意的架构选择：
如果 PRoot 是以动态库形式链接进进程，整个应用就得跟着 GPL 了。

### ⚠️ 2. talloc 2.5.0 — **LGPL-3.0-or-later**

- 来源：Termux `libtalloc` 包（Samba 项目的 talloc，Samba 惯用 LGPL-3.0-or-later）
- 体积：35 KB（`libtalloc.so`）
- 用途：PRoot 的内存池依赖

**分发义务：** 附上 LGPL-3.0 全文（见 [`licenses/LGPL-3.0.txt`](licenses/LGPL-3.0.txt)）、
声明使用了该库、并保证用户可以用修改后的版本替换它。
**动态链接本身即满足「可替换」要求** —— 本项目正是动态链接，且不修改该库。

> 📌 **待办**：talloc 的许可证未能从上游仓库直接取到（`samba-team/talloc` 已不可访问）。
> 上述结论依据 Samba 项目的通行声明，**发布前请以实际随包的 `COPYING` 再核对一次**。
> 若你的使用场景对合规要求严格，建议直接查阅 Termux 的 `libtalloc` 构建配方。

### 3. Node.js 24.18.0 — MIT（含多项第三方许可）

- 来源：Termux `nodejs-lts` 包（bionic 构建，arm64-v8a + x86_64）
- 体积：53 MB（`libnode.so`，已 strip）
- 许可证全文与全部内嵌依赖声明见 [`licenses/Node.js.txt`](licenses/Node.js.txt)
  （取自包内 `share/doc/nodejs-lts/copyright`）

Node.js 自身是 MIT，但它静态链接了一批组件，各自许可证不同：

| 组件 | 许可证 |
|---|---|
| V8 | BSD-3-Clause |
| ICU | Unicode-3.0 |
| OpenSSL | Apache-2.0 |
| c-ares | MIT |
| zlib | Zlib |
| SQLite | Public Domain |
| libuv | MIT |
| nghttp2 / ngtcp2 / nghttp3 | MIT |
| llhttp | MIT |
| OpenSSL 的嵌入副本 | Apache-2.0 |

### 4. 其余运行时库

| 文件 | 组件 | 版本 | 许可证 | 依据 |
|---|---|---|---|---|
| `libc++_shared.so` | LLVM libc++ | 30 | Apache-2.0 WITH LLVM-exception | LLVM 项目惯例 |
| `libicuuc.so` `libicui18n.so` `libicudata.so` | ICU | 78.3 | Unicode-3.0 | 包内 `share/icu/78.3/LICENSE` |
| `libcrypto.so` `libssl.so` | OpenSSL | 3.6.5 | Apache-2.0 | GitHub API |
| `libsqlite3.so` | SQLite | 3.54.0 | Public Domain | 上游声明 |
| `libcares.so` | c-ares | 1.34.8 | MIT | GitHub API + 包内 copyright |
| `libz.so` | zlib | 1.3.2 | Zlib | 包内 copyright |
| `libandroid-shmem.so` | libandroid-shmem | 0.7 | BSD-2-Clause | 包内 copyright 正文 |

### 5. `assets/runtime/npm-10.9.2.tgz`

npm CLI 10.9.2 —— **Artistic-2.0**。取自 `registry.npmmirror.com` 的官方分发包。
许可证全文见 [`licenses/npm.txt`](licenses/npm.txt)。

### 6. `assets/runtime/cert.pem`

Mozilla CA 根证书库（121 个根证书），打包格式取自 Termux `ca-certificates` 包。
**证书库整体按 MPL-2.0 分发，各根证书另有其自身的分发条款。**
详见 <https://wiki.mozilla.org/CA/Included_Certificates>。

### 7. `assets/bridge-plugin/`

本项目自己写的 DSH 插件，**MIT**（同 [LICENSE](LICENSE)）。

---

## 二、运行时按需下载、不随 APK 分发

这些是用户首次使用时联网获取的，不构成本仓库的分发行为，
但项目会去下载它们，使用者也应知晓：

| 组件 | 许可证 | 说明 |
|---|---|---|
| `@deepseek-ai/dsh` 及官方插件 | 见 npm 包内声明 | 从 npmmirror 安装 |
| Ubuntu 22.04 base rootfs | 各包各异（大多 GPL/LGPL） | 官方 `ubuntu-base-22.04.5-base-arm64.tar.gz` |
| Vosk 中文小型模型 | Apache-2.0（模型随 Vosk 项目分发） | 从 alphacephei.com 下载 |

---

## 三、构建期依赖（不随 APK 分发）

| 组件 | 许可证 |
|---|---|
| AndroidX（core-ktx / appcompat / lifecycle / webkit / recyclerview） | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| ConstraintLayout | Apache-2.0 |
| Kotlin / kotlinx.coroutines | Apache-2.0 |
| Shizuku（`api` / `provider` / `aidl`） | Apache-2.0 |
| Vosk Android（`com.alphacephei:vosk-android`） | Apache-2.0 |
| Apache Commons Compress | Apache-2.0 |
| JNA（`net.java.dev.jna:jna@aar`） | LGPL-2.1 / Apache-2.0 双许可 |
| Android Gradle Plugin / Gradle / Kotlin Gradle Plugin | Apache-2.0 |

---

## 四、为什么不直接 LICENSE = MIT 就完事

一句话：**MIT 只管得了我自己写的那些 `.kt` 文件，管不了我塞进 APK 的别人家的二进制。**

一个只放 `LICENSE` 的开源仓库，最容易出的问题就是把 GPL 组件一起分发却没有附许可证、没有指向源码。
本项目没有回避这一点：PRoot（GPL-2.0）与 talloc（LGPL-3.0）的许可证全文都放在
[`licenses/`](licenses/)，来源与构建配方也都写在上面。

---

## 五、复核方式

```bash
# 1) 每个 .so 的 SONAME 与 DT_NEEDED（确认打包的确实是这些版本）
readelf -d app/src/main/jniLibs/arm64-v8a/libproot.so | grep -E 'SONAME|NEEDED'

# 2) 包内自带的许可证文件
find .build/arm64-v8a/stage -path '*/share/doc/*' -name 'copyright' -o -path '*/share*/LICENSE'
find .build/arm64-v8a/stage -path '*share/icu/*/LICENSE'

# 3) 上游仓库的许可证声明（需要已登录的 gh）
gh api repos/proot-me/PRoot --jq '.license.spdx_id'    # → GPL-2.0
gh api repos/openssl/openssl --jq '.license.spdx_id'   # → Apache-2.0
gh api repos/alphacep/vosk-api --jq '.license.spdx_id' # → Apache-2.0
```
