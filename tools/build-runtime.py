#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-runtime.py — DSH Fusion 的内置运行时流水线（多 ABI）。

一次跑完「取包 → 解包 → 挑选 → 改写 ELF → 落地 jniLibs → 打包 npm/CA」，
产出 app/ 能直接消费的运行时种子。

为什么是**多 ABI**而不是只有 arm64：
  手机是 arm64，但「在 Windows 上用应用宝跑一遍」这条测试路径不是 ——
  应用宝电脑版是 VirtualBox 里的 **x86_64** Android 虚拟机（实测：
  Image/Logs/VBox.log 里写着 `VirtualBox 6.1.36 win.amd64`，
  镜像为 system.vhd / data.vhd），而 Intel Bridge 只存在于 Intel 平台的 WSA，
  在 AMD 机器上更无从谈起。所以只出 arm64 的包，在应用宝里连装都装不上。
  出 arm64-v8a + x86_64 双份，包体积上去了，但「手机能跑 + 电脑能测」才成立。

用法：
  python3 tools/build-runtime.py                 # 两个 ABI 都建
  python3 tools/build-runtime.py --abis arm64-v8a
  python3 tools/build-runtime.py --mirror https://mirrors.bfsu.edu.cn/termux/apt/termux-main

输出：
  app/src/main/jniLibs/<androidAbi>/*.so
  app/src/main/assets/runtime/{npm-<ver>.tgz, cert.pem, runtime-manifest.json}
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, ".build")
JNI_ROOT = os.path.join(ROOT, "app", "src", "main", "jniLibs")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "runtime")

TERMUX_USR = "data/data/com.termux/files/usr"
DEFAULT_MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
NPM_VERSION = "10.9.2"
NPM_URL = f"https://registry.npmmirror.com/npm/-/npm-{NPM_VERSION}.tgz"

PATCHELF = shutil.which("patchelf") or os.path.expanduser("~/.local/bin/patchelf")

# Android ABI -> Termux 架构目录名
ABI_TO_TERMUX = {
    "arm64-v8a": "aarch64",
    "x86_64": "x86_64",
}

# 每个 ABI 都要拿的包（含传递依赖）
SEED_PACKAGES = ["nodejs-lts", "proot", "ca-certificates"]
SKIP_PACKAGES = {"libandroid-support", "bash", "coreutils", "python", "python-pip"}

# (目标文件名, 源包前缀, 包内相对路径, 旧 SONAME, 新 SONAME)
# 新 SONAME 为 None 表示「可执行文件伪装成 .so」，不设 SONAME。
LIBRARIES = [
    ("libnode.so",          "nodejs-lts",       "bin/node",                  None,               None),
    ("libproot.so",         "proot",            "bin/proot",                 None,               None),
    ("libc++_shared.so",    "libc++",           "lib/libc++_shared.so",      "libc++_shared.so", "libc++_shared.so"),
    ("libicuuc.so",         "libicu",           "lib/libicuuc.so.78.3",      "libicuuc.so.78",   "libicuuc.so"),
    ("libicui18n.so",       "libicu",           "lib/libicui18n.so.78.3",    "libicui18n.so.78", "libicui18n.so"),
    ("libicudata.so",       "libicu",           "lib/libicudata.so.78.3",    "libicudata.so.78", "libicudata.so"),
    ("libcrypto.so",        "openssl",          "lib/libcrypto.so.3",        "libcrypto.so.3",   "libcrypto.so"),
    ("libssl.so",           "openssl",          "lib/libssl.so.3",           "libssl.so.3",      "libssl.so"),
    ("libsqlite3.so",       "libsqlite",        "lib/libsqlite3.so.3.54.0",  None,               "libsqlite3.so"),
    ("libcares.so",         "c-ares",           "lib/libcares.so",           "libcares.so",      "libcares.so"),
    ("libz.so",             "zlib",             "lib/libz.so.1.3.2",         "libz.so.1",        "libz.so"),
    ("libtalloc.so",        "libtalloc",        "lib/libtalloc.so.2.5.0",    "libtalloc.so.2",   "libtalloc.so"),
    ("libandroid-shmem.so", "libandroid-shmem", "lib/libandroid-shmem.so",   None,               "libandroid-shmem.so"),
]

# 所有 ELF 的 DT_NEEDED 都要归一到 jniLibs 里的真名
NEEDED_MAP = {
    "libicuuc.so.78":   "libicuuc.so",
    "libicui18n.so.78": "libicui18n.so",
    "libicudata.so.78": "libicudata.so",
    "libcrypto.so.3":   "libcrypto.so",
    "libssl.so.3":      "libssl.so",
    "libz.so.1":        "libz.so",
    "libtalloc.so.2":   "libtalloc.so",
    "libsqlite3.so.0":  "libsqlite3.so",
}


def log(*args):
    print(*args, flush=True)


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(*cmd: str):
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr).strip()


def fetch(url: str, dest: str) -> str:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    log(f"    GET {url}")
    urllib.request.urlretrieve(url, dest + ".part")
    os.replace(dest + ".part", dest)
    return dest


# ---------------------------------------------------------------- 索引与依赖

def load_index(mirror: str, termux_arch: str, cache_dir: str) -> dict:
    Path = os.path.join(cache_dir, f"Packages-{termux_arch}")
    fetch(f"{mirror}/dists/stable/main/binary-{termux_arch}/Packages", Path)
    import re
    text = open(Path, encoding="utf-8", errors="replace").read()
    pkgs = {}
    for block in text.split("\n\n"):
        m = re.search(r"^Package: (\S+)$", block, re.M)
        if not m:
            continue
        entry = {}
        for line in block.splitlines():
            if ": " in line:
                k, v = line.split(": ", 1)
                entry[k] = v
        pkgs[m.group(1)] = entry
    return pkgs


def resolve_dependencies(pkgs: dict) -> list:
    need, queue = set(), list(SEED_PACKAGES)
    while queue:
        name = queue.pop()
        if name in need or name in SKIP_PACKAGES:
            continue
        if name not in pkgs:
            log(f"    [warn] 索引里没有 {name}，跳过")
            continue
        need.add(name)
        for part in pkgs[name].get("Depends", "").split(","):
            dep = part.split("(")[0].strip().split(" ")[0].strip()
            if dep and not dep.startswith("$"):
                queue.append(dep)
    return sorted(need)


# ---------------------------------------------------------------- ELF 改写

def patch_elf(path: str, set_soname: str | None) -> list:
    changes = []
    rc, out = run(PATCHELF, "--print-needed", path)
    if rc == 0 and out:
        for old in out.splitlines():
            old = old.strip()
            if old in NEEDED_MAP:
                rc2, err = run(PATCHELF, "--replace-needed", old, NEEDED_MAP[old], path)
                changes.append(f"needed {old} -> {NEEDED_MAP[old]}" if rc2 == 0 else f"ERR {err}")
    if set_soname:
        rc, _ = run(PATCHELF, "--set-soname", set_soname, path)
        if rc == 0:
            changes.append(f"soname={set_soname}")
    # 抹掉 Termux 的 $PREFIX runpath：找不到就退回 LD_LIBRARY_PATH
    rc, out = run(PATCHELF, "--print-rpath", path)
    if rc == 0 and out.strip():
        run(PATCHELF, "--remove-rpath", path)
        changes.append("drop rpath")
    return changes


# ---------------------------------------------------------------- 单个 ABI

def build_abi(android_abi: str, mirror: str) -> dict:
    termux_arch = ABI_TO_TERMUX[android_abi]
    work = os.path.join(BUILD, android_abi)
    debs = os.path.join(work, "debs")
    stage = os.path.join(work, "stage")
    os.makedirs(debs, exist_ok=True)
    os.makedirs(stage, exist_ok=True)

    log(f"\n{'='*70}\n== ABI {android_abi}  (termux: {termux_arch})\n{'='*70}")

    pkgs = load_index(mirror, termux_arch, work)
    log(f"  [索引] {len(pkgs)} 个包")
    wanted = resolve_dependencies(pkgs)
    log(f"  [依赖] {len(wanted)} 个包：{', '.join(wanted)}")

    for name in wanted:
        filename = pkgs[name]["Filename"]
        dest = os.path.join(debs, os.path.basename(filename))
        try:
            fetch(f"{mirror}/{filename}", dest)
        except Exception as exc:  # noqa: BLE001
            log(f"    FAIL {name}: {exc}")

    for fname in sorted(os.listdir(debs)):
        if not fname.endswith(".deb"):
            continue
        out = os.path.join(stage, fname[:-4])
        if os.path.isdir(out):
            continue
        os.makedirs(out, exist_ok=True)
        subprocess.run(["dpkg-deb", "-x", os.path.join(debs, fname), out], check=True)

    def source_of(prefix: str, rel: str):
        for d in sorted(os.listdir(stage)):
            if d.startswith(prefix + "_"):
                candidate = os.path.join(stage, d, TERMUX_USR, rel)
                if os.path.isfile(candidate):
                    return candidate
        return None

    target_dir = os.path.join(JNI_ROOT, android_abi)
    os.makedirs(target_dir, exist_ok=True)

    manifest = {
        "abi": android_abi,
        "termuxArch": termux_arch,
        "libraries": [],
        "runner": {
            "node": "libnode.so",
            "proot": "libproot.so",
            "note": "Termux bionic builds; SONAMEs rewritten to lib*.so so the "
                    "package manager extracts them into nativeLibraryDir with the exec bit.",
        },
    }

    log(f"  [装配] -> {target_dir}")
    for target, prefix, rel, _old, new_soname in LIBRARIES:
        src = source_of(prefix, rel)
        if not src:
            log(f"    !! 缺失 {prefix}:{rel}")
            continue
        dst = os.path.join(target_dir, target)
        shutil.copy2(src, dst)
        os.chmod(dst, 0o755)
        changes = patch_elf(dst, new_soname)
        manifest["libraries"].append(
            {
                "name": target,
                "bytes": os.path.getsize(dst),
                "sha256": sha256(dst),
                "origin": f"{prefix}:{rel}",
                "soname": new_soname,
                "patched": changes,
            }
        )
        log(f"    {target:22} {os.path.getsize(dst)//1024:>7} KB  {len(changes)} 处改写")

    total = sum(x["bytes"] for x in manifest["libraries"])
    log(f"  [合计] {total/1048576:.1f} MB / {len(manifest['libraries'])} 个 .so")
    manifest["totalBytes"] = total
    return manifest


# ---------------------------------------------------------------- 主流程

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--abis", default="arm64-v8a,x86_64", help="逗号分隔，默认两个都建")
    parser.add_argument("--mirror", default=DEFAULT_MIRROR)
    parser.add_argument("--skip-shared-assets", action="store_true", help="跳过 npm / CA 证书（已存在时）")
    args = parser.parse_args()

    abis = [a.strip() for a in args.abis.split(",") if a.strip()]
    unknown = [a for a in abis if a not in ABI_TO_TERMUX]
    if unknown:
        log(f"!! 不支持的 ABI: {unknown}（可选：{list(ABI_TO_TERMUX)}）")
        return 2

    os.makedirs(ASSETS, exist_ok=True)
    manifests = []
    for abi in abis:
        manifests.append(build_abi(abi, args.mirror))

    # ---- 共享资源：npm 与 CA 证书（与 ABI 无关，只放一份）----
    log(f"\n== 共享资源（与 ABI 无关）==")
    npm_tgz = os.path.join(ASSETS, f"npm-{NPM_VERSION}.tgz")
    if not os.path.exists(npm_tgz) or not args.skip_shared_assets:
        log(f"  npm {NPM_VERSION}")
        fetch(NPM_URL, npm_tgz)
    log(f"  npm tgz: {os.path.getsize(npm_tgz)//1024} KB")

    cert_src = None
    for abi in abis:
        stage = os.path.join(BUILD, abi, "stage")
        if not os.path.isdir(stage):
            continue
        for d in sorted(os.listdir(stage)):
            if d.startswith("ca-certificates_"):
                candidate = os.path.join(stage, d, TERMUX_USR, "etc/tls/cert.pem")
                if os.path.isfile(candidate):
                    cert_src = candidate
                    break
        if cert_src:
            break
    cert_dst = os.path.join(ASSETS, "cert.pem")
    if cert_src:
        shutil.copy2(cert_src, cert_dst)
        with open(cert_dst, "rb") as fh:
            ca_count = fh.read().count(b"BEGIN CERTIFICATE")
        log(f"  CA 证书: {os.path.getsize(cert_dst)//1024} KB / {ca_count} 个根证书")

    # ---- 总清单 ----
    combined = {
        "generator": "tools/build-runtime.py",
        "npm": {"file": f"npm-{NPM_VERSION}.tgz", "version": NPM_VERSION},
        "caBundle": "cert.pem",
        "abis": manifests,
    }
    out = os.path.join(ASSETS, "runtime-manifest.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(combined, fh, indent=2, ensure_ascii=False)

    log(f"\n{'='*70}")
    grand = sum(m["totalBytes"] for m in manifests)
    log(f"== 完成：{len(manifests)} 个 ABI，jniLibs 合计 {grand/1048576:.1f} MB")
    for m in manifests:
        log(f"   {m['abi']:12} {m['totalBytes']/1048576:>6.1f} MB  ({len(m['libraries'])} 个 .so)")
    log(f"   清单 -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
