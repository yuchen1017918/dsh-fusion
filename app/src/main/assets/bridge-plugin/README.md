# dsh-fusion-android-bridge

DSH Fusion（Android 版）自带的能力桥。它把手机上的能力翻译成模型可以直接调用的
`android_*` 工具，全部经由回环地址 `127.0.0.1` 转发，**不出设备**。

## 它是怎么工作的

1. 手机端应用启动时在回环地址上开一个 HTTP 桥，并生成随机 token；
2. 启动 DSH 引擎时，把桥的地址与 token 通过环境变量
   `DSH_FUSION_BRIDGE_URL` / `DSH_FUSION_BRIDGE_TOKEN` 传进来；
3. 本插件在宿主启动时同步读取同目录下的 `tools.json`（装配时由手机端写出），
   为每一条能力注册一个 DSH 工具；
4. 模型调用工具 → 本插件 `POST /invoke` → 手机端执行 → 结果回传。

## 为什么用 tools.json 而不是运行时查询

`apply()` 是同步注册点，而手写一个阻塞式 HTTP 查询既脆弱又慢。装配时落一份清单，
启动即可同步读入，工具列表 100% 确定；新增能力只需重新装配一次。

## 通道

每个工具在 `tools.json` 里标了自己走哪条通道：

| channel | 含义 | 需要什么 |
| --- | --- | --- |
| `ACCESSIBILITY` | 无障碍服务 | 系统设置里打开一次 |
| `SHIZUKU` | Shizuku 提供的系统 shell | 装 Shizuku 并授权 |
| `ROOT` | Root（su） | 设备已 Root |
| `APP` | 应用自身权限 | 无需额外授权 |

`dangerous: true` 的工具会执行写操作或系统级变更，应用端可能要求用户确认。
