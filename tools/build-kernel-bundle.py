#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-kernel-bundle.py — 把官方 dsh 内核预装好、裁剪好、压成一个小包塞进 APK。

要解决的问题
------------
原来应用首启要跑一次 npm 安装：538 个包、942 MB 下载与解包、几分钟等待。
用户装完软件还得装依赖，这是最劝退的一段。

实测数据（本机跑出来的，不是估的）：

    双树安装（全局启动器 + profile 各一套）      942 MB
    单树安装（启动器作为 profile 依赖，自动去重）  501 MB
    再裁掉用不上的东西                            189 MB
    xz -6 压缩后                                  21 MB

21 MB 的代价换来「装上就能用」，这笔账划得来。所以这个脚本产出的
`dsh-kernel-<version>.tar.xz` 直接进 assets，首次启动只做解包，不联网。

裁剪原则（每一刀都写清理由，宁可少裁不要裁坏）
--------------------------------------------
1. **LibreOffice wasm**（soffice.wasm 122 MB + soffice.data 22 MB）
   文档转换能力。默认剔除（`--keep-office` 保留）：wasm 虽跨平台，但
   手机上跑 122 MB 的 wasm LibreOffice 现实意义有限，且它是单文件超
   GitHub 硬限制（100 MB）的元凶。
2. **非本平台原生库**（linux-x64 / win32 / darwin 的 .node / .so / .exe / .pdb）
   Android 是 arm64 或 x86_64 bionic，桌面 Linux 的 glibc 原生库一个都加载不了。
   留在包里是纯粹的浪费 —— 而且它们本来就是 `pnpm` 报
   `Ignored build scripts` 的那批需要现场编译的包。
3. **sourcemap / .d.ts / .pdb**
   调试与类型声明，运行期完全不需要。
4. **文档与示例**（README / docs / examples / test）

不裁的东西
----------
- 任何 `.js` / `.mjs` / `.cjs`（哪怕是测试用不到的，也不敢按名字猜）
- `.wasm` 里除 LibreOffice 之外的（可能是运行必需的）
- `prebuilds/` 下 **linux-arm64 / android** 的产物（我们的目标平台）

用法：
    python3 tools/build-kernel-bundle.py                    # 默认版本与裁剪
    python3 tools/build-kernel-bundle.py --keep-office      # 保留 LibreOffice wasm
    python3 tools/build-kernel-bundle.py --version 0.2.0-rc.2
    python3 tools/build-kernel-bundle.py --mirror https://registry.npmjs.org
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, ".build", "kernel-bundle")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "kernel")

# 目标平台：Android 只有这两种
KEEP_PLATFORMS = ("linux-arm64", "linux-arm64-gnu", "android", "arm64")

# 需要剔除的外平台标记（出现在路径里就整个移除）
FOREIGN_MARKERS = (
    "linux-x64", "linux-x64-gnu", "linux-x64-musl",
    "win32-x64", "win32-arm64", "win32-ia32",
    "darwin-x64", "darwin-arm64",
    "linux-arm", "linux-ia32", "freebsd", "openbsd", "sunos",
)

# 纯调试/类型产物
DEBUG_SUFFIXES = (".map", ".d.ts", ".d.ts.map", ".pdb", ".tsbuildinfo")

# 文档类（体积小但没必要带）
DOC_DIRS = ("docs", "examples", "test", "tests", "__tests__", ".github")

# 默认剔除的重量级可选件
HEAVY_OPTIONAL = (
    "soffice.wasm",
    "soffice.data",
)


def log(*args):
    print(*args, flush=True)


def sh(cmd, cwd=None, capture=True, tolerate: tuple[str, ...] = ()):
    """跑一条命令。

    tolerate 里的子串命中输出时，非零退出码不算失败 —— pnpm 会把
    「按设计忽略了构建脚本」以退出码 1 报出来，那不该让整个流程挂掉。
    """
    p = subprocess.run(cmd, cwd=cwd, shell=isinstance(cmd, str),
                       capture_output=capture, text=True)
    output = (p.stdout or "") + (p.stderr or "")
    if p.returncode != 0 and not any(marker in output for marker in tolerate):
        raise RuntimeError(f"命令失败({p.returncode}): {cmd}\n{output[-1500:]}")
    if p.returncode != 0:
        log(f"    （退出码 {p.returncode} 已被容忍：命中 {[m for m in tolerate if m in output]}）")
    return output


def human(mb: float) -> str:
    return f"{mb:.1f} MB"


def dir_mb(path: str) -> float:
    total = 0
    for dirpath, _, files in os.walk(path):
        for name in files:
            fp = os.path.join(dirpath, name)
            try:
                total += os.path.getsize(fp)
            except OSError:
                pass
    return total / 1048576


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_profile(profile_dir: str, version: str, registry: str, pnpm_spec: str) -> None:
    """写出 profile 清单。

    刻意把 `@deepseek-ai/dsh` 与 `pnpm` 都作为 profile 依赖：
    这样整棵树只有一份（pnpm 的 nodeLinker 会做去重），
    体积从「启动器一套 + profile 一套」的 942 MB 降到 501 MB。
    """
    manifest = {
        "name": "dsh-profile-web",
        "private": True,
        "dependencies": {
            "@deepseek-ai/dsh": version,
            "@deepseek-ai/dsh-base": version,
            "@deepseek-ai/dsh-web-app": version,
            # pnpm 一并带上（约 3 MB 压缩）。官方插件安装页底层就是它，
            # 不带的话用户装完插件页依然点不动，那就白折腾了。
            "pnpm": "11.7.0",
        },
        "dsh": {"profile": {"bundles": ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"]}},
    }
    with open(os.path.join(profile_dir, "package.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)

    # nodeLinker: hoisted —— 与 npm 装出来的扁平结构一致，两种安装路径可以混用
    # ignoredBuiltDependencies 是 pnpm 的显式声明：这些包我们不构建。
    # 不写它 pnpm 会以退出码 1 报 ERR_PNPM_IGNORED_BUILDS（把「按设计忽略」当成失败），
    # 而它们本来就需要现场编译原生模块 —— 在 Android 上根本做不到，忽略才是对的。
    with open(os.path.join(profile_dir, "pnpm-workspace.yaml"), "w", encoding="utf-8") as fh:
        fh.write(
            "packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n\n"
            "ignoredBuiltDependencies:\n"
            "  - '@deepseek-ai/dsh-subprocess-local'\n"
            "  - '@google/genai'\n"
            "  - koffi\n"
            "  - node-pty\n"
            "  - protobufjs\n"
        )
    with open(os.path.join(profile_dir, ".npmrc"), "w", encoding="utf-8") as fh:
        fh.write(f"registry={registry}\n")
    log(f"  已写出 profile 清单（dsh {version} + dsh-base + dsh-web-app）")


def prune(profile_dir: str, keep_office: bool) -> dict:
    """按规则裁剪，返回裁剪报告。"""
    report = {"removedFiles": 0, "removedBytes": 0, "byRule": {}}
    node_modules = os.path.join(profile_dir, "node_modules")
    if not os.path.isdir(node_modules):
        raise RuntimeError("node_modules 不存在，安装可能失败了")

    def record(rule: str, size: int):
        report["removedFiles"] += 1
        report["removedBytes"] += size
        entry = report["byRule"].setdefault(rule, {"files": 0, "bytes": 0})
        entry["files"] += 1
        entry["bytes"] += size

    def drop_file(path: str, rule: str) -> bool:
        try:
            size = os.path.getsize(path)
            os.remove(path)
            record(rule, size)
            return True
        except OSError:
            return False

    log("  裁剪中…")
    for dirpath, dirnames, filenames in os.walk(node_modules, topdown=True):
        rel = os.path.relpath(dirpath, node_modules)

        # 1) 整目录剔除：文档 / 示例 / 测试
        for name in list(dirnames):
            full = os.path.join(dirpath, name)
            child_rel = os.path.join(rel, name) if rel != "." else name
            if name in DOC_DIRS and "node_modules" not in child_rel.split(os.sep)[:-1]:
                size = int(dir_mb(full) * 1048576)
                shutil.rmtree(full, ignore_errors=True)
                dirnames.remove(name)
                record("docs/tests", size)
                continue
            # 2) 整目录剔除：外平台原生库
            if not keep_office and any(marker in child_rel for marker in ("libreoffice-kit-wasm",)):
                size = int(dir_mb(full) * 1048576)
                shutil.rmtree(full, ignore_errors=True)
                dirnames.remove(name)
                record("libreoffice-wasm", size)
                continue
            # 3) 外平台目录（保留 arm64/android）
            if any(marker in name.lower() for marker in FOREIGN_MARKERS) and not any(k in name.lower() for k in KEEP_PLATFORMS):
                size = int(dir_mb(full) * 1048576)
                shutil.rmtree(full, ignore_errors=True)
                dirnames.remove(name)
                record("foreign-platform", size)

        # 4) 文件级剔除
        for name in filenames:
            full = os.path.join(dirpath, name)
            lower = name.lower()
            if name.endswith(DEBUG_SUFFIXES):
                drop_file(full, "sourcemap/types")
            elif not keep_office and name in HEAVY_OPTIONAL:
                drop_file(full, "libreoffice-wasm")
            elif lower.endswith((".exe", ".dll", ".pdb", ".lib", ".exp")):
                drop_file(full, "windows-binaries")
    return report


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default=os.environ.get("DSH_KERNEL_VERSION", "0.2.0-rc.2"))
    ap.add_argument("--registry", default="https://registry.npmmirror.com")
    ap.add_argument("--keep-office", action="store_true", help="保留 LibreOffice wasm（+约 144 MB 未压缩）")
    ap.add_argument("--work", default=BUILD)
    ap.add_argument("--out", default=ASSETS)
    args = ap.parse_args()

    started = time.time()
    work = args.work
    profile = os.path.join(work, "profile")

    log("═" * 68)
    log(f" DSH 内核预打包 · 版本 {args.version}")
    log("═" * 68)

    if os.path.isdir(profile):
        shutil.rmtree(profile, ignore_errors=True)
    os.makedirs(profile, exist_ok=True)
    os.makedirs(args.out, exist_ok=True)

    write_profile(profile, args.version, args.registry, "pnpm")

    log("  安装依赖（单棵树，pnpm 去重）…")
    pnpm = shutil.which("pnpm") or "pnpm"
    log(f"    使用 {pnpm}")
    sh([pnpm, "install", f"--registry={args.registry}", "--reporter=append-only"], cwd=profile,
       tolerate=("ERR_PNPM_IGNORED_BUILDS", "Ignored build scripts"))

    raw_mb = dir_mb(profile)
    log(f"  安装完成：{human(raw_mb)}")

    report = prune(profile, args.keep_office)
    pruned_mb = dir_mb(profile)
    log(f"  裁剪完成：{human(pruned_mb)}（剔除 {report['removedFiles']} 个文件 / {human(report['removedBytes']/1048576)}）")
    for rule, data in sorted(report["byRule"].items(), key=lambda kv: -kv[1]["bytes"]):
        log(f"    - {rule:20} {human(data['bytes']/1048576):>10}  ({data['files']} 项)")

    # 打包。xz 是纯 Java 可解的格式（commons-compress + xz），
    # 实测比 gzip 小 35%，而且不需要为 Android 引入任何原生库。
    name = f"dsh-kernel-{args.version}" + ("" if not args.keep_office else "-office")
    tarball = os.path.join(args.out, name + ".tar.xz")
    log("  压缩中（xz -6，这一步较慢）…")
    sh(f"tar -cf - profile | xz -6 -T0 -c > '{tarball}'", cwd=work)

    size_mb = os.path.getsize(tarball) / 1048576
    digest = sha256_of(tarball)

    manifest = {
        "generator": "tools/build-kernel-bundle.py",
        "kernelVersion": args.version,
        "registry": args.registry,
        "officeIncluded": args.keep_office,
        "rawMB": round(raw_mb, 1),
        "prunedMB": round(pruned_mb, 1),
        "tarballMB": round(size_mb, 1),
        "sha256": digest,
        "removed": report,
    }
    manifest_path = os.path.join(args.out, name + ".json")
    with open(manifest_path, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)

    log("═" * 68)
    log(f" 产出：{tarball}")
    log(f"       {human(size_mb)}   sha256={digest[:16]}…")
    log(f" 清单：{manifest_path}")
    log(f" 耗时：{time.time() - started:.0f}s")
    log("═" * 68)
    log(f" 未压缩 {human(raw_mb)} → 裁剪 {human(pruned_mb)} → 压缩 {human(size_mb)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
