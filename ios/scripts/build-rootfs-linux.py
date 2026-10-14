#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-rootfs-linux.py — 在 **Linux 上**构建 iOS 版的 guest rootfs（root.tar.gz）。

━━ 为什么需要它 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
iOS 上「本地跑 DSH」目前唯一可行的路线是**用户态模拟器**（不需要 JIT、
不需要 ptrace、不需要子进程）—— 社区项目 everettjf/dsh-ios 用 iSH-ARM64
把它做了出来，而且真机验证过。

但它的构建脚本要求 **macOS**：先 fakefsify alpine.tar.gz，再在模拟器里
apk add，最后 unfakefsify 导出。三步都要 iSH，而 iSH 要 macOS 编译。
于是「想给 iOS 版做点贡献」的门槛变成了「你得先有台 Mac」。

**这个脚本把门槛降到「有台 Linux 机器」。**

━━ 为什么能这么做 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
关键观察：fakefsify / unfakefsify 只是**让模拟器能跑 apk 的中间手段**，
不是交付格式。最终产物 root.tar.gz 就是一个平凡的 tar.gz ——
而里面的东西，**没有一样需要执行 aarch64 代码才能准备**：

  · Alpine minirootfs 是 tar.gz
  · **apk 包本身也是 tar.gz**（实测 `tar -xzf busybox.apk` 直接解出完整内容）
  · npm 装 --os=linux --cpu=arm64 --libc=musl 不需要目标架构能跑
  · overlay 是纯文件

所以整棵树可以在 Linux 上组装出来。**唯一的缺口是
node-pty 为 musl 的原生重建**（要跑 aarch64 的 node-gyp），
脚本会把这一点明确标注出来，而不是假装做完了。

用法：
    python3 ios/scripts/build-rootfs-linux.py                 # 全流程
    python3 ios/scripts/build-rootfs-linux.py --stage base    # 只做 Alpine 基础
    python3 ios/scripts/build-rootfs-linux.py --stage packages
    python3 ios/scripts/build-rootfs-linux.py --stage npm
    python3 ios/scripts/build-rootfs-linux.py --stage overlay
    python3 ios/scripts/build-rootfs-linux.py --stage pack
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))       # ios/
BUILD = os.path.join(ROOT, "build")
WORK = os.path.join(BUILD, "stage")            # 组装中的 rootfs 树
CACHE = os.path.join(BUILD, "cache")           # 下载缓存
OUT = os.path.join(BUILD, "root.tar.gz")

ALPINE_VER = "3.21"
ALPINE_BRANCH = f"v{ALPINE_VER}"
MIRRORS = [
    "https://mirrors.tuna.tsinghua.edu.cn/alpine",     # 国内快，放前面
    "https://dl-cdn.alpinelinux.org/alpine",
]
ARCH = "aarch64"

# guest 里要装的包。与上游 build-rootfs.sh 的 guest phase 1 对齐，
# 但**去掉了构建期依赖**（python3 / make / g++ / nodejs-dev）——
# 它们只为 node-pty 重建服务，而那一步在 Linux 上做不了（见文件头）。
# 在 Mac 上补那一步时，上游脚本会在 guest 里临时 apk add 它们。
GUEST_PACKAGES = [
    "busybox", "alpine-baselayout", "alpine-keys", "apk-tools",
    "libc-utils", "musl", "musl-utils", "zlib", "openssl", "ca-certificates",
    "nodejs", "npm",
    "bash", "curl", "git", "openssh-client",
    "libstdc++", "libgcc",
]

DSH_VERSION = os.environ.get("DSH_VERSION", "0.1.0-rc.7")


def log(*a):
    print(*a, flush=True)


def rootfs_filter(member: tarfile.TarInfo, dest_path: str):
    """解包 rootfs 用的过滤器。

    **为什么不能用 `filter="data"`**：它按「解一个归档到任意目录」的威胁模型设计，
    因此拒绝**绝对符号链接**。但我们要建的恰恰是一个 rootfs ——
    里面的绝对符号链接是**必需且正确**的：

        /usr/bin/yes  →  /bin/busybox

    这个链接在 guest 内部的根是 `/`，完全正确；只有把它当成「往宿主目录解包」
    才显得危险。用 `data` 过滤器会在第一万个条目上抛 AbsoluteLinkError，
    而失败信息不会告诉你「这是设计使然，不是恶意归档」。

    **但也不能直接 `fully_trusted`** —— 那连 `..` 穿越都不挡。
    所以这里自己写：允许绝对链接（rootfs 需要），拦住路径穿越（宿主需要）。
    """
    name = member.name
    # 路径穿越：归档条目里出现 .. 就拒绝
    parts = name.replace("\\", "/").split("/")
    if ".." in parts:
        raise ValueError(f"归档条目含路径穿越，拒绝：{name}")
    # 符号链接的**目标**也要挡穿越（相对链接里的 ../../ 会跑出 rootfs）
    if member.issym() and not member.linkname.startswith("/"):
        depth = len([p for p in parts[:-1] if p and p != "."])
        ups = member.linkname.count("../")
        if ups > depth:
            raise ValueError(f"符号链接目标穿越 rootfs，拒绝：{name} -> {member.linkname}")
    # 绝对路径条目：归一成相对（tar 里 './usr/...' 与 '/usr/...' 都要能解）
    if name.startswith("/"):
        member.name = name.lstrip("/")
    return member


def fetch(url: str, dest: str, quiet: bool = False) -> str:
    """带缓存与重试的下载。"""
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    last = None
    for _ in range(2):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "dsh-fusion-ios-rootfs/1.0"})
            with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 16)
            os.replace(tmp, dest)
            if not quiet:
                log(f"    ↓ {os.path.basename(dest)}  {os.path.getsize(dest) / 1048576:.1f} MB")
            return dest
        except Exception as e:                      # noqa: BLE001
            last = e
    raise RuntimeError(f"下载失败 {url}：{last}")


def mirror_fetch(path: str, dest: str) -> str:
    """按镜像依次试。path 是相对镜像根部的路径。"""
    errs = []
    for m in MIRRORS:
        try:
            return fetch(f"{m}/{path}", dest)
        except Exception as e:                      # noqa: BLE001
            errs.append(f"{m}: {e}")
    raise RuntimeError("所有镜像都失败：\n  " + "\n  ".join(errs))


# ─────────────────────────────────────────── 阶段 1：Alpine 基础

def stage_base() -> None:
    log("== 阶段 1：Alpine 基础系统 ==")
    name = f"alpine-minirootfs-{ALPINE_VER}.0-{ARCH}.tar.gz"
    path = f"{ALPINE_BRANCH}/releases/{ARCH}/{name}"
    tar = mirror_fetch(path, os.path.join(CACHE, name))

    if os.path.isdir(WORK):
        shutil.rmtree(WORK)
    os.makedirs(WORK, exist_ok=True)
    log(f"    解包到 {WORK}")
    with tarfile.open(tar) as t:
        t.extractall(WORK, filter=rootfs_filter)

    # Alpine 的 minirootfs 自带 /etc/apk/repositories。换成我们实际用的镜像 ——
    # 这只影响**将来在 guest 里**跑 apk，我们的构建不执行 apk
    #（那正是本方案能脱离模拟器的原因）。
    repo = os.path.join(WORK, "etc/apk/repositories")
    if os.path.exists(repo):
        with open(repo, "w", encoding="utf-8") as f:
            f.write(f"{MIRRORS[0]}/{ALPINE_BRANCH}/main\n")
            f.write(f"{MIRRORS[0]}/{ALPINE_BRANCH}/community\n")
    log("    ✔ 基础系统就位")


# ─────────────────────────────────────────── 阶段 2：apk 依赖闭包

class ApkIndex:
    """APKINDEX 的解析。格式是一堆以空行分隔的 `键:值` 记录。"""

    def __init__(self, text: str):
        self.pkgs: dict[str, dict] = {}
        cur: dict[str, str] = {}
        for line in text.splitlines() + [""]:
            if not line:
                if cur.get("P"):
                    self.pkgs[cur["P"]] = cur
                cur = {}
                continue
            if len(line) > 2 and line[1] == ":":
                cur[line[0]] = line[2:]

    def deps_of(self, name: str, seen: set) -> list:
        """算依赖闭包。

        apk 的 D 字段形如 `so:libc.musl-aarch64.so.1 cmd:sh !pkg pkgname<1.2`。
        我们只跟 `pkgname` 形式的依赖走：`so:` / `cmd:` / `pc:` 是**虚拟依赖**，
        由提供它们的实体包满足，而 minirootfs 里已经带了 musl 等基础件；
        跟进去只会重复。`!` 是冲突声明，也不是依赖。
        """
        meta = self.pkgs.get(name)
        if meta is None:
            raise KeyError(f"APKINDEX 里没有包 {name}")
        out = [name]
        seen.add(name)
        for raw in meta.get("D", "").split():
            if raw.startswith(("so:", "cmd:", "pc:", "!")) or raw.startswith("/"):
                continue
            dep = re.split(r"[<>=~]", raw)[0].strip()
            if not dep or dep in seen or dep not in self.pkgs:
                continue
            out += self.deps_of(dep, seen)
        return out


def load_index() -> ApkIndex:
    """main 与 community 都要 —— community 里有 bash / git 等。"""
    merged: dict = {}
    for repo in ("main", "community"):
        rel = f"{ALPINE_BRANCH}/{repo}/{ARCH}/APKINDEX.tar.gz"
        dest = os.path.join(CACHE, f"APKINDEX-{repo}.tar.gz")
        mirror_fetch(rel, dest)
        with tarfile.open(dest, "r:gz") as t:
            member = next((m for m in t.getmembers() if m.name.endswith("APKINDEX")), None)
            if member is None:
                raise RuntimeError(f"{repo} 的 APKINDEX 里没有索引文件")
            text = t.extractfile(member).read().decode("utf-8", "replace")
        idx = ApkIndex(text)
        log(f"    {repo}: {len(idx.pkgs)} 个包")
        merged.update(idx.pkgs)
    out = ApkIndex("")
    out.pkgs = merged
    return out


def stage_packages() -> None:
    log("== 阶段 2：apk 依赖闭包 ==")
    idx = load_index()

    want: list = []
    seen: set = set()
    for p in GUEST_PACKAGES:
        if p not in idx.pkgs:
            log(f"    ! APKINDEX 里没有 {p}，跳过")
            continue
        for name in idx.deps_of(p, seen):
            if name not in want:
                want.append(name)
    log(f"    依赖闭包：{len(want)} 个包（{len(GUEST_PACKAGES)} 个直接 + 传递依赖）")

    total = 0
    ok_n = 0
    for i, name in enumerate(want, 1):
        meta = idx.pkgs[name]
        ver = meta.get("V", "")
        total += int(meta.get("S", "0") or 0)
        dest = os.path.join(CACHE, f"{name}-{ver}.apk")
        if not os.path.exists(dest):
            got = False
            for r in ("main", "community"):
                try:
                    mirror_fetch(f"{ALPINE_BRANCH}/{r}/{ARCH}/{name}-{ver}.apk", dest)
                    got = True
                    break
                except Exception:                   # noqa: BLE001
                    continue
            if not got:
                log(f"    ! 抓不到 {name}-{ver}，跳过")
                continue
        # **直接解 tar** —— apk 就是 tar.gz，不需要执行任何 aarch64 代码。
        # 这正是整个 Linux 方案成立的原因。
        try:
            with tarfile.open(dest, "r:gz") as t:
                members = [m for m in t.getmembers()
                           if not m.name.startswith((".SIGN", ".PKGINFO"))]
                t.extractall(WORK, members=members, filter=rootfs_filter)
            ok_n += 1
        except Exception as e:                      # noqa: BLE001
            log(f"    ! 解包 {name} 失败：{e}")
        if i % 25 == 0 or i == len(want):
            log(f"    [{i}/{len(want)}] 已解 {ok_n} 个")
    log(f"    ✔ 包阶段完成（{ok_n} 个已解，缓存 {total / 1048576:.1f} MB 压缩）")

    missing_builder = [p for p in ("nodejs-dev", "python3", "make", "g++") if p not in idx.pkgs]
    if missing_builder:
        log(f"    （构建期依赖不在索引里：{missing_builder} —— 那一步本来就要在 Mac 上补）")


# ─────────────────────────────────────────── 阶段 3：npm 树

def stage_npm() -> None:
    log("== 阶段 3：npm 树（dsh）==")
    staging = os.path.join(BUILD, "npm-staging")
    os.makedirs(staging, exist_ok=True)

    pkg_json = os.path.join(staging, "package.json")
    with open(pkg_json, "w", encoding="utf-8") as f:
        json.dump({
            "name": "dsh-guest-staging",
            "private": True,
            "dependencies": {"@deepseek-ai/dsh": DSH_VERSION},
        }, f, indent=2)

    # 关键：指定目标平台三件套。npm 会据此选可选依赖
    # （sharp 的 libvips、node-pty 的 prebuild…），**不需要目标架构能跑**。
    cmd = [
        "npm", "install",
        "--os=linux", "--cpu=arm64", "--libc=musl",
        "--ignore-scripts", "--no-audit", "--no-fund",
        "--registry", os.environ.get("NPM_REGISTRY", "https://registry.npmmirror.com"),
    ]
    log(f"    $ {' '.join(cmd)}")
    rc = subprocess.run(cmd, cwd=staging).returncode
    if rc != 0:
        raise RuntimeError("npm 安装失败")

    src = os.path.join(staging, "node_modules")
    dst = os.path.join(WORK, "usr/local/lib/node_modules")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst, symlinks=True)

    # 上游会删掉 sharp 的 glibc / wasm 变体：glibc 的在 Alpine 上加载不了，
    # 而 wasm 的在 jitless Node 下跑不起来（iOS 没有 JIT ⇒ 没有 WebAssembly）。
    for junk in ("@img/sharp-linux-arm64", "@img/sharp-libvips-linux-arm64", "@img/sharp-wasm32"):
        p = os.path.join(dst, junk)
        if os.path.isdir(p):
            shutil.rmtree(p)
            log(f"    剔除 {junk}（glibc / wasm 变体在 guest 里都跑不了）")

    bin_dir = os.path.join(WORK, "usr/local/bin")
    os.makedirs(bin_dir, exist_ok=True)
    entry = os.path.join(dst, "@deepseek-ai/dsh/lib/bin.js")
    link = os.path.join(bin_dir, "dsh")
    if os.path.exists(entry) and not os.path.lexists(link):
        os.symlink("../lib/node_modules/@deepseek-ai/dsh/lib/bin.js", link)
        log("    ✔ 已链 /usr/local/bin/dsh")
    log("    ✔ npm 树就位")


# ─────────────────────────────────────────── 阶段 4：overlay

BUILD_NOTES = """本 rootfs 由 ios/scripts/build-rootfs-linux.py 在 Linux 上构建。

以下步骤需要能执行 aarch64 代码，因此**未完成**，请在 macOS 上补：

1) node-pty 为 musl 重建
     apk add nodejs-dev python3 make g++
     cd /usr/local/lib/node_modules/node-pty && rm -rf build prebuilds
     npx --yes node-gyp rebuild --nodedir=/usr
     test -f build/Release/pty.node
     apk del nodejs-dev python3 make g++

2) 预建 web profile（让设备首次启动不做脚手架）
     node --expose-internals /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \\
          --profile web --dump-config >/dev/null
     install -m 0644 /usr/local/share/dsh/cordis.patch.yml \\
          /root/.dsh/profiles/web/cordis.patch.yml

上游参考：everettjf/dsh-ios 的 scripts/build-rootfs.sh（guest phase 3）。
"""


def stage_overlay() -> None:
    log("== 阶段 4：overlay ==")
    overlay = os.path.join(ROOT, "rootfs/overlay")
    n = 0
    if os.path.isdir(overlay):
        for dirpath, _, files in os.walk(overlay):
            for f in files:
                src = os.path.join(dirpath, f)
                rel = os.path.relpath(src, overlay)
                dst = os.path.join(WORK, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)
                n += 1
        log(f"    ✔ 放进 {n} 个文件")
    else:
        log("    （没有 overlay 目录）")

    # 把待补步骤写进 guest，而不是让它在运行时神秘失败
    warn = os.path.join(WORK, "etc/dsh-rootfs-build-notes")
    os.makedirs(os.path.dirname(warn), exist_ok=True)
    with open(warn, "w", encoding="utf-8") as f:
        f.write(BUILD_NOTES)
    log("    ✔ 已写入待补步骤说明（/etc/dsh-rootfs-build-notes）")


# ─────────────────────────────────────────── 阶段 5：打包

def stage_pack() -> None:
    log("== 阶段 5：打包 ==")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    # 用 python 的 tarfile 而不是 GNU tar：要保证
    # （a）uid/gid 归 0、（b）不带构建机的用户名、（c）符号链接保持符号链接。
    n = 0
    with tarfile.open(OUT, "w:gz", compresslevel=6) as t:
        for dirpath, dirnames, filenames in os.walk(WORK):
            dirnames.sort()
            for name in sorted(dirnames):
                full = os.path.join(dirpath, name)
                t.add(full, arcname=os.path.relpath(full, WORK), recursive=False,
                      uid=0, gid=0, uname="root", gname="root")
            for name in sorted(filenames):
                full = os.path.join(dirpath, name)
                t.add(full, arcname=os.path.relpath(full, WORK), recursive=False,
                      uid=0, gid=0, uname="root", gname="root")
                n += 1
    size = os.path.getsize(OUT)
    log(f"    ✔ {OUT}")
    log(f"      {n} 个文件 · {size / 1048576:.1f} MB")
    h = hashlib.sha256()
    with open(OUT, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    with open(OUT + ".sha256", "w", encoding="utf-8") as f:
        f.write(f"{h.hexdigest()}  {os.path.basename(OUT)}\n")
    log(f"      sha256 {h.hexdigest()[:16]}…")


STAGES = {
    "base": stage_base,
    "packages": stage_packages,
    "npm": stage_npm,
    "overlay": stage_overlay,
    "pack": stage_pack,
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", choices=list(STAGES), help="只跑某一个阶段")
    args = ap.parse_args()

    os.makedirs(CACHE, exist_ok=True)
    t0 = time.time()
    log("=" * 66)
    log(" iOS guest rootfs · 在 Linux 上构建")
    log("=" * 66)
    # 许可证提示**每次都打印**。它要防的是「用的人不知道」——
    # 而许可证风险最怕的不是「有」，是「不知道就用」。
    log(" ⚠ 用这份 rootfs 构建出的 iOS app 必然是 GPL-3.0")
    log("   （iSH 模拟器是 GPLv3 且被静态链接进 app；")
    log("     详见 ios/docs/LICENSE-IMPLICATIONS.md）")
    log("=" * 66)

    if args.stage:
        STAGES[args.stage]()
    else:
        for name in ("base", "packages", "npm", "overlay", "pack"):
            STAGES[name]()

    log(f"\n完成，用时 {time.time() - t0:.0f} 秒")
    log("注意：node-pty 的 musl 重建与 profile 预建需要 aarch64 执行环境，")
    log("      本脚本已把待补步骤写进 guest 的 /etc/dsh-rootfs-build-notes。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
