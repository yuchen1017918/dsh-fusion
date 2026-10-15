# 社区调研：连接与配对（2026-10-15）

> 目的：看看别人怎么做远程连接与设备配对，把我们的实现照着改。
> **结论先说**：最该补的不是「换个更高级的算法」，是一个我完全漏掉的东西 —— **限速**。

---

## 一、最有价值的一条：短码的安全性 100% 来自限速

出处：[Magic Wormhole 的攻击模型文档](https://github.com/magic-wormhole/magic-wormhole/blob/master/docs/attacks.rst)
（`docs/attacks.rst`，作者亲自写的「已知漏洞」）。

原文的关键句：

> By default, wormhole codes contain 16 bits of entropy. If an attacker can
> intercept your network connection … they will have a one-in-65536 chance of
> successfully guessing your code.
>
> Basic probability tells us that peers will see **a large number of
> WrongPasswordErrors** before the attacker has a useful chance…

**它把「猜码」当成主要威胁，而不是边角情况。**

我们的设计漏了什么，算一下就知道：

| 码长 | 无限速（脚本 1000 次/秒） | 有指数退避 |
|---|---|---|
| 4 位 | **秒级** | 几分钟 |
| **6 位（我们的默认）** | **8 分钟** | **14 年** |
| 8 位 | 14 小时 | 更久 |

**6 位码在没有限速时，期望 8 分钟就被脚本猜中。** 那不是「弱密码」，是等于没有密码。

### 已补：`lib/pairing-guard.js`

三层：
1. **单来源退避** —— 前 3 次不罚（正常用户也会敲错），之后指数拉长，上限 15 分钟
2. **全局计数** —— 所有来源汇总，识别分布式猜解，超阈值主动告警
3. **持久化** —— 重启不清零（攻击者不能靠让你重启把计数抹掉）

**一个刻意的设计**：成功只清「单来源」，**不清全局** ——
「先猜中一次再继续猜」正是攻击者会做的事，清全局等于给他发奖励。

---

## 二、Wormhole 的其他几条（都是踩过的坑）

| 它写的 | 我们的对应 |
|---|---|
| 服务端重用同一个 channel id（nameplate）会放大攻击（issue #31） | ✅ **设计时就要禁止复用** —— rendezvous 还没做，先把约定写进协议 |
| **不要把口令本身当 channel id** —— 16 bit 熵，服务端/网络攻击者能反推 | ✅ 我们的码不进日志、不当标识符 |
| `--code-length=4` 可以加长码 | ✅ `pairingCode(pubA, pubB, digits)` 已支持 |
| 想要 ReCAPTCHA / hashcash / 按 IP 限速来防 DoS | ⏳ rendezvous 上线前需要定（阿里云 2核2G，DoS 是真实威胁） |

---

## 三、Syncthing：设备 ID 的三个设计

出处：[Understanding Device IDs](https://docs.syncthing.net/dev/device-ids.html)

1. **设备 ID 是公钥的直接属性**，不是另发一个编号 ——
   「认 ID」与「认公钥」是同一件事，不会出现两者不一致的窗口
2. **Luhn 校验位** —— 手动输错一位**立刻**能发现
3. **分组显示**（每 7 字符一个短横）—— 不分组的长串，人根本没法逐字核对

### 已补：`lib/device-id.js`

格式：`REOIB6H-E5WAMN3-7X4RJ2M-CSZD3XQ-VKBNCLY-Y5VKV2U-3SBMECM-DMAA`
（52 个 base32 字符 + 1 个 Luhn mod 32 校验位，每 7 个一组）

实测：**单字符错误 404/404 全部检出，相邻换位 50/50 检出**。

> 为什么这条重要：**没有校验位时，输错一位的表现是「连不上，原因不明」** ——
> 用户会去怀疑网络、防火墙、配对流程，唯独不会怀疑自己少打了一个字符。

**与 Syncthing 不同的一点**：我们明确声明**ID 是展示形式，不是身份** ——
校验永远用原始公钥，不用 ID。ID 碰撞现实中不会发生，但代码不依赖这一点。

---

## 四、评估过但**否决**的：SPAKE2（PAKE）

### 为什么它理论上更好

PAKE 能把**人从信任链里移出去**：

| | 我们现在的 SAS 比对 | PAKE |
|---|---|---|
| 用户操作 | 两边看码、**肉眼比对** | 输入/扫同一个码 |
| 安全性来源 | **人真的比对了** | 密码学：不知道码就推不出密钥 |
| 用户偷懒的后果 | **跳过比对 = 零安全** | 无影响（前提是码没泄露） |

### 为什么否决

看了 [npm 上的 `spake2`](https://www.npmjs.com/package/spake2)：

- **它自己的 README 写着**：
  > This have not go through a formal cryptographic audit and should be
  > awared in any use case. Also this do not protect against time attacks
  > as operations are probably not constant-time.
- API 无文档（`cipherSuite` 怎么构造要翻源码）
- 最后发布 2020 年

**把未经审计、自承非常数时间的密码学实现放进安全关键路径，
风险大于收益。** 尤其我们这个场景的威胁是「被接管设备」，
而不是「被偷看」—— 密码学实现出错的后果是不可逆的。

**这跟之前 sherpa-onnx 的判断是同一条标准**：
不因为「看起来更高级」就引入一个自己无法验证的依赖。

### 那条路还在

如果将来要用，正确做法是：
1. 用**经过审计**的实现（如 libsodium 的 `crypto_pwhash` + 自己写 SPAKE2 的组合，
   或等 Node 生态出现被审计的版本）
2. 先用测试向量对齐参考实现
3. **保留 SAS 作为回退** —— 它虽然依赖人，但它的失效模式是「用户看到了却没比」，
   而不是「密码学悄悄错了」

---

## 五、另外两个观察

**RustDesk / 远控类工具**：普遍是「ID + 一次性密码」两段式，
ID 长期不变、密码每次随机。**我们的做法（长期公钥 + 每次派生的短码）本质相同**，
但我们的短码是**双方派生**（不能由一方单独决定），这点比「服务端发密码」更抗篡改。

**Matter / HomeKit 的配对码**：11 位数字，但关键是它带 **PAKE（SPAKE2+）**，
所以 11 位不需要人比对。**它们用码长换掉了人眼比对** —— 我们用了相反的策略
（短码 + 人眼比对 + **限速**）。两者都能成立，但**都必须有防暴力那一层**，
否则再长的码也扛不住脚本。

---

## 六、本轮实际改动

| 文件 | 改动 | 来源 |
|---|---|---|
| `lib/pairing-guard.js` | **新增** —— 限速、失败计数、全局告警、持久化 | Wormhole `attacks.rst` |
| `lib/device-id.js` | **新增** —— 带 Luhn 校验位的设备 ID | Syncthing |
| `lib/auth.js` | `pairingCode(..., digits)` 支持可选码长 | Wormhole `--code-length` |
| `lib/index.js` | 接线 + 状态里暴露限速状况与**实际抗暴力时间** | —— |
| 测试 | 新增 35 项（合计 197） | —— |

**写的过程中发现两个真 bug**：

1. **持久化整条是死的** —— `load()` 只恢复了 `fails`/`until`，**漏了 `lastAt`**，
   于是紧接着的 `prune()` 用 `now - 0 > 24小时` 判定，把**刚加载进来的记录全部删掉**。
   从外面看不出任何异常：文件有、内容对、就是重载后为空。
2. **我自己的估算函数是错的** —— 第一版按「平坦速率 10 次/分」算出 6 位码 35 天，
   **35 天不叫安全**，差点因此得出「必须上 8 位码」的错误结论。
   按真实的指数退避曲线算，答案是 **14 年**。
   **用错误的模型估算，比不估算更危险 —— 它会让你去调错的参数。**
