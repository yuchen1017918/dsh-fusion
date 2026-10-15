#!/usr/bin/env python3
"""重新生成中国 IPv4 段表（plugins/dsh-fusion-remote/data/cn-ipv4.txt）。

APNIC 的委派数据每周更新。表里**必须带生成日期** ——
否则 cn-ip.js 的过时检测拿不到基准，那个功能就等于不存在
（这是测试跑起来才发现的：第一版忘了写日期）。
"""
import ipaddress
import datetime
import os
import sys
import urllib.request

URL = "https://ftp.apnic.net/apnic/stats/apnic/delegated-apnic-latest"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "data", "cn-ipv4.txt")


def main() -> int:
    print(f"下载 {URL}")
    req = urllib.request.Request(URL, headers={"User-Agent": "dsh-fusion-remote/gen"})
    with urllib.request.urlopen(req, timeout=120) as r:
        text = r.read().decode("utf-8", "replace")

    rows = []
    for line in text.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        p = line.rstrip("\n").split("|")
        if len(p) < 7 or p[1] != "CN" or p[2] != "ipv4":
            continue
        start, count = p[3], int(p[4])
        net = ipaddress.ip_network(f"{start}/{32 - (count.bit_length() - 1)}", strict=False)
        if net.num_addresses == count:
            rows.append(str(net))
        else:
            # 数量不是 2 的幂 —— 拆成若干 CIDR
            for n in ipaddress.summarize_address_range(
                    ipaddress.IPv4Address(start),
                    ipaddress.IPv4Address(int(ipaddress.IPv4Address(start)) + count - 1)):
                rows.append(str(n))
    rows = sorted(set(rows), key=lambda s: int(ipaddress.ip_network(s).network_address))

    today = datetime.date.today().isoformat()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# 中国 IPv4 段（APNIC delegated-apnic-latest 生成）\n")
        f.write(f"# generated: {today}\n")     # ← cn-ip.js 的过时检测读这一行
        f.write("# 用途：远程连接判定对端与本机出口是否在境内。内置离线表 ——\n")
        f.write("#       用在线 API 查询本身就是一次出境请求，与「不跨境」自相矛盾。\n")
        f.write(f"# 数据来源：{URL}\n")
        f.write("# 重新生成：python3 plugins/dsh-fusion-remote/tools/gen-cn-ip.py\n")
        for r in rows:
            f.write(r + "\n")

    total = sum(ipaddress.ip_network(r).num_addresses for r in rows)
    print(f"  {len(rows)} 条 CIDR · 覆盖 {total / 1e6:.1f}M 地址 "
          f"({total / 2**32 * 100:.1f}% IPv4) · {os.path.getsize(OUT) / 1024:.0f} KB")
    print(f"  generated: {today}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
