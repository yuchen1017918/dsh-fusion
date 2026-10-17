# DSH Fusion · 会合服务器（rendezvous）

让两台**互相够不到**的设备（都在 NAT 后、没有公网 IP）会合并中转字节。

> **它不参与认证，也看不到明文。** 端到端认证（Ed25519 + 配对码）在插件层做完，
> 到这里已经是被会话密钥保护的字节 —— 服务器只是个管道。
>
> 这是刻意的：**让服务器无法作恶，比让它承诺不作恶更可靠。**

---

## 当前状态：已部署，**未启动**

| 项 | 状态 |
|---|---|
| 代码 | 已放到 `/opt/dsh-rendezvous/` |
| 容器 | **未构建、未运行** |
| 端口 | 8443 未监听 |
| Caddy | 未加任何配置（网站照旧） |

**等子域名配好之后再启动。**

---

## 为什么现在就能用（不必等备案）

备案拦的是 **80/443 上的域名访问**。我们的服务走 **8443 非标端口**，
备案期间就能用 —— 只要**阿里云安全组放行了 8443**。

检查安全组：阿里云控制台 → 该实例 → 安全组 → 入方向 → 放行 TCP 8443。

## 启动（子域名配好后）

```bash
# 1. 构建镜像
cd /opt/dsh-rendezvous && docker compose build

# 2. 启动
docker compose up -d

# 3. 验证（本机自测，只回 "ok"）
curl -s http://127.0.0.1:8443/healthz

# 4. 看日志
docker compose logs -f --tail 50
```

**停止 / 彻底不跑：**

```bash
docker compose down            # 停掉
docker compose down --rmi all  # 连镜像一起清掉
```

## 挂了子域名之后（推荐做法）

让它**只监听回环**，由 Caddy 在前面做 TLS —— 这样证书、续期、HTTP/2 都交给 Caddy：

```yaml
# docker-compose.yml 里把 ports 改成
    ports:
      - "127.0.0.1:8443:8443"
```

```caddy
# /etc/caddy/Caddyfile 追加（**不要动现有的 :80 站点块**）
rendezvous.yuchenhub.com {
    reverse_proxy 127.0.0.1:8443
    log {
        output file /var/log/caddy/rendezvous.log {
            roll_size 10mb
            roll_keep 3
        }
    }
}
```

然后 `systemctl reload caddy`。

---

## 安全设计（为什么这么写）

| 点 | 做法 | 理由 |
|---|---|---|
| **通道一次性** | 任一方断开即焚，**从不回收重用** | Wormhole issue #31：允许复用会放大攻击 —— 攻击者猜中一个 id 后立刻用它另开一条 |
| **通道短命** | 3 分钟，且 90 秒内凑不齐两方就作废 | 与插件侧的配对窗口一致，不多给 |
| **配对码不进服务端** | 服务端只看到随机 channel id，与配对码无关 | 减少元数据泄露 |
| **不记完整 IP** | 日志里脱敏，完整值只在内存里用于限速 | 「谁和谁在会合」属于元数据 |
| **三层限速** | 单 IP 建通道速率 / 单 IP 并发通道 / 全站通道与连接上限 | 1.7 G 的机器，一个脚本就能打满 |
| **带宽闸** | 单通道 2 MB/s、全站 24 MB/s，**超限直接断而不是排队** | 排队会让内存涨起来，在 1.7 G 上那是更糟的失败方式 |
| **对扫描器什么都不给** | 除 `/healthz`（只回 `ok`）与 `POST /channel`，一律 404，无 `Server` 头 | 它从来就不是一个网站 |
| **握手前校验** | 通道无效/已满时在 **HTTP 升级阶段**就拒（404/409） | 否则客户端先收到 `open` 再收到 `close`，**分不清「连上了」和「被拒了」** |
| **非 root 运行** | 容器内 `USER rv`、`read_only: true`、`no-new-privileges` | 万一有 RCE，拿到的权限极小 |

## 端点

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/healthz` | 存活探测，**只回 `ok`**（不报版本/负载/通道数） |
| `POST` | `/channel` | 建通道 → `{channel, ttlMs, joinWindowMs}` |
| `WS` | `/join/<channel>` | 加入通道，双向中转 |
| 其它 | * | **一律 404**，带 `X-Robots-Tag: noindex` |

## 部署时注意（爬虫与扫描器）

服务本身不提供任何文件，所以没有「被扫到隐私文件」的面。
但部署目录仍按最小暴露来做：

- 放在 `/opt/dsh-rendezvous/`，**不在 `/var/www` 下**（那是 Caddy 的文件根）
- 目录 `700`，只有 root 能进
- 不放 `.env`、不放私钥、不放 `.git`
- 日志写在 Docker 的 json-file 里（自动轮转），不写到 web 可访问的路径
