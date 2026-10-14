#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen-catalog.py — 从一个真实 APK 生成/更新 catalog/artifacts.json 里的条目。

为什么要这个脚本而不是手改 JSON
------------------------------
清单里的 bytes 与 sha256 必须是**产物的真实值**。手填一定会漂 ——
改一行代码重新打包，字节数就变了，而 sha256 更是完全对不上。
对不上的后果是安装器下载完校验失败，用户看到「下载的文件损坏」，
而真正的原因是清单过期了。

所以数字只由这个脚本写，而且它从 APK 里**读**而不是猜：
  · bytes  → 文件大小
  · sha256 → 实际哈希
  · abi    → 解包看 lib/ 下有哪些目录（这比问用户选更可靠）
  · version→ 从 APK 里读（有 aapt 就用，没有就让调用方给）

用法：
    python3 tools/gen-catalog.py --apk D:/DSH-Fusion-v1.2-arm64.apk --id android-arm64-full
    python3 tools/gen-catalog.py --apk A.apk --id X --apk B.apk --id Y   # 多个一起
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "catalog", "artifacts.json")


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def abis_in(apk: str) -> list[str]:
    """从 APK 的 lib/ 目录读出实际打进去的 ABI。"""
    found = set()
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            m = re.match(r"^lib/([^/]+)/", n)
            if m:
                found.add(m.group(1))
    order = ["arm64-v8a", "x86_64", "armeabi-v7a", "x86"]
    return [a for a in order if a in found] or sorted(found)


def version_from_apk(apk: str) -> str | None:
    """没有 aapt 时的兜底：从 AndroidManifest 的二进制 XML 里捞版本号太脆，
    所以只在能找到 aapt 时才用，否则返回 None 让调用方提供。"""
    for tool in ("aapt2", "aapt"):
        if os.system(f"{tool} version >/dev/null 2>&1") == 0:
            out = os.popen(f'{tool} dump badging "{apk}" 2>/dev/null').read()
            m = re.search(r"versionName='([^']+)'", out)
            if m:
                return m.group(1)
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", action="append", required=True)
    ap.add_argument("--id", action="append", required=True)
    ap.add_argument("--version", default=None)
    args = ap.parse_args()

    if len(args.apk) != len(args.id):
        print("✘ --apk 与 --id 数量必须一致", file=sys.stderr)
        return 2

    with open(CATALOG, encoding="utf-8") as f:
        cat = json.load(f)

    by_id = {a["id"]: a for a in cat["artifacts"]}
    for apk, aid in zip(args.apk, args.id):
        if not os.path.isfile(apk):
            print(f"✘ 找不到 {apk}", file=sys.stderr)
            return 1
        entry = by_id.get(aid)
        if entry is None:
            print(f"✘ 清单里没有 id={aid}", file=sys.stderr)
            return 1
        size = os.path.getsize(apk)
        digest = sha256_of(apk)
        abis = abis_in(apk)
        ver = args.version or version_from_apk(apk) or entry.get("version") or "0.0.0"

        # ABI 必须与清单声明的对得上 —— 拿错产物的表现是安装后立即崩溃
        declared = entry.get("abi")
        if declared and declared not in abis:
            print(f"✘ {aid} 声明 ABI={declared}，但 APK 里实际是 {abis}", file=sys.stderr)
            return 1

        entry["bytes"] = size
        entry["sha256"] = digest
        entry["version"] = ver
        entry["fileName"] = entry.get("fileName") or os.path.basename(apk)
        print(f"  ✔ {aid}")
        print(f"      {size:,} 字节 · sha256 {digest[:16]}… · ABI {abis} · v{ver}")

    with open(CATALOG, "w", encoding="utf-8") as f:
        json.dump(cat, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"  → {CATALOG}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
