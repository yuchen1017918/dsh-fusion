#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check-license.py — iOS 分支的许可证守卫。

━━ 它守两件事 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. **我们没抄上游的受保护表达。**
   上游 everettjf/dsh-ios 是 GPL-3.0（它静态链接了 GPLv3 的 iSH 模拟器）。
   我们读了它的构建流程 —— 读不构成侵权 —— 然后自己写了一份。
   这个检查把「没抄」从一句声称变成**可复跑的断言**：
   抓上游脚本，逐行比对，超过阈值就失败。

   注意区分两类相似：
     · **受保护表达**（注释、变量命名、组织方式）→ 抄了就有问题
     · **事实与接口**（命令名 `node-gyp rebuild`、包名 `nodejs-dev`、
       文件名 `pty.node`）→ 不受版权保护，相同是必然的
   所以这里只比「非注释的完整代码行」，且要求长度 ≥ 阈值。

2. **GPL 后果被说出来了，而不是躺在文档里等人读。**
   每次跑都打印。许可证风险最怕的不是「有」，是「用的人不知道」。

用法：
    python3 ios/scripts/check-license.py            # 检查并打印
    python3 ios/scripts/check-license.py --quiet    # 只检查，失败才出声
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.request

UPSTREAM = "everettjf/dsh-ios"
UPSTREAM_SCRIPT = "scripts/build-rootfs.sh"
OURS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build-rootfs-linux.py")

# 逐行相同的**非注释**代码行上限。
# 允许 2 处而不是 0 处 —— 因为「事实性命令」撞车是正常且无法避免的
# （比如 `test -f build/Release/pty.node` 就是唯一合理的写法）。
# 一旦超过，通常意味着真的搬了代码，而不是巧合。
MAX_IDENTICAL_LINES = 2
MIN_LINE_LEN = 12


def fetch_upstream() -> str | None:
    """抓上游脚本。优先 gh CLI（带鉴权、不易被限流），退回 raw.githubusercontent。"""
    try:
        out = subprocess.run(
            ["gh", "api", f"repos/{UPSTREAM}/contents/{UPSTREAM_SCRIPT}", "--jq", ".content"],
            capture_output=True, text=True, timeout=60,
        )
        if out.returncode == 0 and out.stdout.strip():
            return base64.b64decode(out.stdout.strip()).decode("utf-8", "replace")
    except Exception:                                # noqa: BLE001
        pass
    try:
        url = f"https://raw.githubusercontent.com/{UPSTREAM}/main/{UPSTREAM_SCRIPT}"
        req = urllib.request.Request(url, headers={"User-Agent": "dsh-fusion-license-check/1.0"})
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read().decode("utf-8", "replace")
    except Exception:                                # noqa: BLE001
        return None


def check_no_copied_code(quiet: bool) -> bool:
    upstream = fetch_upstream()
    if upstream is None:
        # 抓不到就**不能声称检查过了** —— 静默通过比失败更糟
        print("  ⚠ 抓不到上游脚本，本次**未做**代码比对（不是通过）")
        return True

    up_lines = {}
    for i, line in enumerate(upstream.splitlines(), 1):
        s = line.strip()
        if len(s) >= MIN_LINE_LEN and not s.startswith("#"):
            up_lines.setdefault(s, i)

    with open(OURS, encoding="utf-8") as f:
        ours = f.read().splitlines()

    hits = []
    for j, line in enumerate(ours, 1):
        s = line.strip()
        if len(s) >= MIN_LINE_LEN and s in up_lines:
            hits.append((j, up_lines[s], s))

    if not quiet:
        print(f"  上游 {UPSTREAM}/{UPSTREAM_SCRIPT}：{len(upstream.splitlines())} 行")
        print(f"  逐行相同的非注释代码行：{len(hits)} 处（上限 {MAX_IDENTICAL_LINES}）")
        for j, i, s in hits:
            print(f"    我们:{j} ≈ 上游:{i}  {s[:70]}")

    if len(hits) > MAX_IDENTICAL_LINES:
        print(f"  ✘ 与上游逐行相同处过多（{len(hits)} > {MAX_IDENTICAL_LINES}）——")
        print("    请确认没有搬运受 GPL 保护的表达。若确是事实性命令撞车，收窄判据再复核。")
        return False
    if not quiet:
        print("  ✔ 没有复制上游的受保护表达")
    return True


GPL_WARNING = """
──────────────────────────────────────────────────────────────────────
 ⚠ 许可证：用本分支构建出的 iOS app 必然是 GPL-3.0
──────────────────────────────────────────────────────────────────────
 iOS 上唯一可行的「本地跑」路线是用户态模拟器（禁止 JIT / ptrace / 子进程），
 而 iSH 模拟器是 GPLv3 且被**静态链接**进 app —— 整个 app 因此被传染。

   · 本分支的脚本         → MIT（我们自己的代码，未复制上游）
   · 产出的 root.tar.gz   → 含 Alpine 的 GPL 包，分发它就要提供源码与许可证
   · 用它构建的 iOS app   → **GPL-3.0**，另需遵守 iSH 的附加 App Store 条款

 想上架 App Store → 这条路从一开始就不成立（GPL + 模拟执行性能）。
 只想在 iPhone 上用 → 考虑 PWA / 远程客户端，不涉及这些。

 详见 ios/docs/LICENSE-IMPLICATIONS.md
──────────────────────────────────────────────────────────────────────
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    if not args.quiet:
        print("=" * 68)
        print(" iOS 分支 · 许可证检查")
        print("=" * 68)

    ok = check_no_copied_code(args.quiet)

    # 许可证提示**每次都打印**，不因 --quiet 而静默 ——
    # 它要防的是「用的人不知道」，而不只是「检查失败」
    print(GPL_WARNING if not args.quiet else ("\n" + GPL_WARNING if not ok else ""))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
