#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-llm-manifest.py — 算出「手机本地跑大模型」需要哪些运行时包，并产出清单。

产出 `app/src/main/assets/llm/runtime-manifest.json`。**它不含任何二进制**，
只是 URL + sha256 + 大小 —— 应用在设备上按需下载（用户明确要求：不进 APK）。

为什么需要这个脚本
------------------
llama-server 不是一个自包含的二进制，它的 DT_NEEDED 闭包跨了好几个 Termux 包：
    llama-cpp            → llama-server + libggml* + libllama* + libmtmd
    openssl              → libcrypto.so.3 + libssl.so.3
    libcurl              → libcurl.so（libllama-common 链了它）
    libnghttp2/3, libngtcp2, libssh2, zlib → libcurl 的依赖
    libandroid-spawn     → llama-server 的直接依赖

靠手写这份清单一定会漏，漏了的表现是「启动时报 cannot link executable」——
而那时用户已经在手机上等了几分钟下载。所以这里**真的去解包、真的读
DT_NEEDED、真的做闭包**，算不出来的就报错退出。

为什么不需要 patchelf
--------------------
我们的 Node 运行时必须把 `libicuuc.so.78` 这类名字归一化，因为 Android 只解压
`lib*.so`（W^X 方案要求它们伪装成 so 放进 jniLibs）。
**但 LLM 运行时是用 proot 跑的**：在 proot 下文件可以叫任何名字，动态链接器
照原样找得到。所以这里保留 `libcrypto.so.3` 这种带版本号的真名，
构建期一把 patchelf 都不需要 —— 整个装配可以在设备上完成。

用法：
    python3 tools/build-llm-manifest.py
    python3 tools/build-llm-manifest.py --mirror https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
"""
from __future__ import annotations

import argparse
import hashlib
import json
import lzma
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, ".build", "llm-debs")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "llm")

DEFAULT_MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"

# 备用镜像。设备上用的是 HttpDownloader，它按顺序试 —— 清华挂了不会让整个
# 下载失败。实测：清华 4.5 MB/s，官方源 15.7 KB/s，所以清华在前。
FALLBACK_MIRRORS = [
    "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
    "https://packages.termux.dev/apt/termux-main",
]

# 入口包。闭包从它们出发算。
ROOT_PACKAGES = ["llama-cpp"]

# bionic 系统自带的库：设备上本来就有，不用下载。
# 判据是它们由 Android 的 libc 提供，而不是 Termux 的某个包。
ANDROID_SYSTEM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libstdc++.so", "libc++_shared.so", "libc++.so", "libm.so.6", "libdl.so.2",
    "libunwind.so", "libatomic.so",
}

# 我们已经在 APK 的 jniLibs 里带了这些（Node 的依赖），设备上能直接复用，
# 不必再下载一份。名字按去版本号后的基名比对。
ALREADY_SHIPPED = {"libc++_shared.so"}


def log(*a):
    print(*a, flush=True)


def fetch(url: str, dest: str) -> None:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    log(f"    下载 {os.path.basename(dest)} …")
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=180) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 16)
    os.replace(tmp, dest)


def load_index(mirror: str) -> dict:
    """下载并解析 Termux 的 Packages 索引（只取 aarch64）。"""
    url = f"{mirror}/dists/stable/main/binary-aarch64/Packages"
    cached = os.path.join(CACHE, "Packages")
    fetch(url, cached)
    pkgs: dict[str, dict] = {}
    cur: dict[str, str] = {}
    with open(cached, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                if cur.get("Package"):
                    pkgs[cur["Package"]] = cur
                cur = {}
                continue
            if ":" in line:
                k, v = line.split(":", 1)
                cur[k.strip()] = v.strip()
    if cur.get("Package"):
        pkgs[cur["Package"]] = cur
    log(f"  索引里 {len(pkgs)} 个包")
    return pkgs


def deb_members(path: str) -> dict[str, str]:
    """读 ar 归档的成员名 → 偏移。deb 是 ar，不是 tar。"""
    out = subprocess.run(["ar", "t", path], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"ar t 失败：{out.stderr.strip()}")
    return {name: name for name in out.stdout.split()}


def extract_data_tar(deb: str, workdir: str) -> str:
    """把 deb 的 data.tar.* 解出来，返回解包目录。"""
    out = subprocess.run(["ar", "x", os.path.abspath(deb)], cwd=workdir, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"ar x 失败：{out.stderr.strip()}")
    for name in os.listdir(workdir):
        if name.startswith("data.tar"):
            target = os.path.join(workdir, "data")
            os.makedirs(target, exist_ok=True)
            with tarfile.open(os.path.join(workdir, name)) as t:
                # 只取 usr/ 下的东西，丢掉 termux 的家目录结构
                members = [m for m in t.getmembers() if "/files/usr/" in m.name]
                for m in members:
                    rel = m.name.split("/files/usr/", 1)[1]
                    m.name = rel
                    t.extract(m, target, filter="data")
            return target
    raise RuntimeError("deb 里找不到 data.tar.*")


def elf_needed(path: str) -> list[str]:
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
    return re.findall(r"NEEDED\).*?\[([^\]]+)\]", out)


def is_elf(path: str) -> bool:
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mirror", default=DEFAULT_MIRROR)
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()

    os.makedirs(CACHE, exist_ok=True)
    log("═" * 68)
    log(" 本地大模型运行时清单")
    log("═" * 68)

    index = load_index(args.mirror)

    # ── 1. 先按包依赖把包集算出来 ──
    chosen: dict[str, dict] = {}
    queue = list(ROOT_PACKAGES)
    while queue:
        name = queue.pop()
        if name in chosen:
            continue
        meta = index.get(name)
        if meta is None:
            log(f"  ✘ 索引里没有包 {name}（依赖闭包断了）")
            return 1
        chosen[name] = meta
        for dep in meta.get("Depends", "").split(","):
            dep = dep.strip()
            if not dep:
                continue
            # 去掉版本约束与替代项： "openssl (>= 1:3.2.1-1)" → "openssl"
            dep = re.split(r"[\s(]", dep)[0]
            if dep and dep != "ca-certificates":   # CA 证书我们已带
                queue.append(dep)

    log(f"  包集 {len(chosen)} 个：{', '.join(sorted(chosen))}")

    # ── 2. 逐个下载并解包，建一个「库名 → 实际文件」的索引 ──
    work = os.path.join(CACHE, "unpacked")
    os.makedirs(work, exist_ok=True)
    files: dict[str, str] = {}      # basename → 绝对路径
    packages_json = []
    for name, meta in sorted(chosen.items()):
        url = f"{args.mirror}/{meta['Filename']}"
        deb = os.path.join(CACHE, os.path.basename(meta["Filename"]))
        fetch(url, deb)
        digest = hashlib.sha256(open(deb, "rb").read()).hexdigest()
        if meta.get("SHA256") and digest != meta["SHA256"]:
            log(f"  ✘ {name} sha256 不匹配：期望 {meta['SHA256'][:16]}… 实际 {digest[:16]}…")
            return 1
        dest = os.path.join(work, name)
        if not os.path.isdir(dest):
            tmp = tempfile.mkdtemp(dir=work)
            shutil.move(extract_data_tar(deb, tmp), dest)
            shutil.rmtree(tmp, ignore_errors=True)
        for dirpath, _, names in os.walk(dest):
            for n in names:
                p = os.path.join(dirpath, n)
                if os.path.islink(p):
                    continue
                files.setdefault(n, p)
        packages_json.append({
            "name": name, "version": meta.get("Version", ""),
            # path 是相对镜像基址的路径 —— 设备上走 HttpDownloader(mirrors).to(dest, path, sha)，
            # 它自己拼镜像，所以这里必须给相对路径而不是绝对 URL
            "path": meta["Filename"],
            "url": url, "sha256": digest, "bytes": os.path.getsize(deb),
            "installedKB": int(meta.get("Installed-Size", "0") or 0),
        })
        log(f"    ✔ {name} {meta.get('Version','')} sha256 校验通过")

    # ── 3. 从 llama-server 出发做 DT_NEEDED 闭包 ──
    server = files.get("llama-server")
    if server is None:
        log("  ✘ 解包里没有 llama-server")
        return 1
    if not is_elf(server):
        log(f"  ✘ llama-server 不是 ELF（可能是符号链接或占位）：{server}")
        return 1

    needed: list[str] = []
    seen: set[str] = set()
    queue = [server]
    while queue:
        cur = queue.pop()
        for lib in elf_needed(cur):
            if lib in seen:
                continue
            seen.add(lib)
            if lib in ANDROID_SYSTEM:
                continue
            if lib in ALREADY_SHIPPED:
                continue
            path = files.get(lib)
            if path is None:
                # 带版本号的变体：libcrypto.so.3 ← 包里可能只有 libcrypto.so.x
                base = lib.split(".so")[0] + ".so"
                path = files.get(base)
                if path is None:
                    log(f"  ✘ 找不到依赖 {lib}（被 {os.path.basename(cur)} 需要）")
                    log("     → 说明还有包没进闭包，或者它由 Android 系统提供但没列进 ANDROID_SYSTEM")
                    return 1
            needed.append(lib)
            if path not in queue:
                queue.append(path)

    total_deb = sum(p["bytes"] for p in packages_json)
    total_inst = sum(p["installedKB"] for p in packages_json)
    log("")
    log(f"  llama-server 的库依赖 {len(needed)} 个：")
    for n in sorted(set(needed)):
        log(f"    {n}")
    log("")
    log(f"  下载量合计 {total_deb/1048576:.1f} MB · 安装后 {total_inst/1024:.1f} MB")

    # ── 4. 产出清单 ──
    os.makedirs(args.out, exist_ok=True)
    manifest = {
        "generator": "tools/build-llm-manifest.py",
        "note": "只含 URL 与校验值，不含任何二进制 —— 应用在设备上按需下载",
        "mirror": args.mirror,
        "fallbackMirrors": FALLBACK_MIRRORS,
        "entry": "llama-server",
        "serverDebPackage": "llama-cpp",
        "packages": packages_json,
        "libraries": sorted(set(needed)),
        "downloadBytes": total_deb,
        "installedKB": total_inst,
    }
    path = os.path.join(args.out, "runtime-manifest.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    log(f"  清单 -> {path}")
    log("═" * 68)
    return 0


if __name__ == "__main__":
    sys.exit(main())
