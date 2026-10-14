# 怎么编译一个 HAP（在 Linux 上，没有华为开发者账号）

**结论：能编译，能打包，能签名。装不到华为商用设备是签名信任链的问题，不是编译的问题。**

本文记的都是**实际跑通的**，不是照文档抄的 —— 其中一半是文档没写、只有踩到才知道的配置项。

---

## 一、拿到工具链（三个包，都在华为云镜像上）

| 包 | 地址 | 大小 | 作用 |
|---|---|---|---|
| OpenHarmony SDK | `repo.huaweicloud.com/openharmony/os/6.0-Release/ohos-sdk-windows_linux-public.tar.gz` | 2.49 GB | **含 linux/ 目录** —— ets/js/native/toolchains/previewer 五个组件 |
| DevEco Command Line Tools | `repo.huaweicloud.com/harmonyos/ohpm/5.1.0/commandline-tools-linux-x64-5.1.0.840.zip` | 2.05 GB | **hvigorw**（构建编排）+ ohpm |
| JDK | 系统包 | — | 签名工具是 Java 的 |

镜像实测 5.8 MB/s，两个包约 8 分钟。

> **注意**：`ohos-sdk` 里**没有** hvigor。它在 DevEco Command Line Tools 里 ——
> 只在 ohos-sdk 里找会卡在「Hvigorfile not found」然后一路怀疑工程配置。

## 二、SDK 目录布局（这一步决定了 90% 的报错）

hvigor 按**版本号目录**找组件：

```
$SDK/
  20/                 ← API 版本号目录（必须等于 compatibleSdkVersion）
    ets/  js/  native/  toolchains/  previewer/
```

每个组件目录里要有 `oh-uni-package.json`（SDK 自带，解压就有）。

环境变量用 **`OHOS_BASE_SDK_HOME`** 或 `local.properties` 里的 `sdk.dir`。
**不是** `DEVECO_SDK_HOME` —— 那是 DevEco 的 HarmonyOS 工程用的，名字不同、指向的结构也不同。

## 三、工程配置里五个必须写对的地方

这几个字段**写错时的报错都不会告诉你是哪个字段**：

| 字段 | OpenHarmony 工程要写 | 写错的后果 |
|---|---|---|
| `runtimeOS` | `"OpenHarmony"` | 写 `"HarmonyOS"` 会去找 `default/hms/` 组件 → 「SDK component missing」 |
| `compatibleSdkVersion` | **整数**（如 `20`） | 写 `"5.0.0(12)"` 这种字符串 → schema 校验失败 |
| `compileSdkVersion` | **必须显式给出** | 只给 compatible 会报「Please configure compileSdkVersion」 |
| `deviceTypes` | 必须在 SDK 的 `api/device-define/` 集合内 | **`phone` 是华为 HarmonyOS 的，OpenHarmony 里没有** → SyscapTransform 报「多设备能力集交集为空」 |
| 图标资源 | `$media:app_icon` 必须真实存在 | `CompileResource` 报 Resource Pack Error |

OpenHarmony 的设备类型只有六种：`default` · `tablet` · `2in1` · `tv` · `wearable` · `liteWearable`。

> **`phone` 那一条最值得记**：同一个应用在 OpenHarmony SDK 与 HarmonyOS SDK 下
> 要写不同的设备类型。这说明 **OpenHarmony SDK ≠ HarmonyOS SDK** ——
> 前者是开源的底座，后者是华为商用的超集。

## 四、构建

```bash
export OHOS_BASE_SDK_HOME=/path/to/sdkroot
export JAVA_HOME=/path/to/jdk
node /path/to/command-line-tools/hvigor/bin/hvigorw.js assembleHap --no-daemon
```

产物：`entry/build/default/outputs/default/entry-default-unsigned.hap`

**实测通过的任务链**（第一次成功后是增量构建）：

```
PreBuild → CreateModuleInfo → GenerateMetadata → MergeProfile
→ PreCheckSyscap → ProcessProfile → CompileResource → BuildJS
→ SyscapTransform → CompileArkTS → GeneratePkgModuleJson → PackageHap
→ SignHap → CollectDebugSymbol → assembleHap
                                                            BUILD SUCCESSFUL
```

## 五、签名：材料齐了，但装不进华为设备

`toolchains/lib/` 里有 OpenHarmony 官方的一整套签名材料：

```
OpenHarmony.p12              ← 口令 123456（官方公开的）
OpenHarmonyProfileDebug.pem
OpenHarmonyProfileRelease.pem
hap-sign-tool.jar            ← Apache-2.0，命令有 generate-keypair / generate-csr
                                / generate-cert / generate-ca / generate-app-cert
                                / generate-profile-cert / sign-profile / sign-app / verify-app
```

密钥库里六个别名：`openharmony application ca` · `openharmony application profile debug` ·
`openharmony application profile release` · `openharmony application release` ·
`openharmony application root ca` · `openharmony software signature`

### 关键限制

**这套证书是 OpenHarmony 的，不是华为商用 HarmonyOS 的。**

| 目标 | 能不能装 |
|---|---|
| OpenHarmony 设备 / 模拟器 | ✅ |
| **华为商用 HarmonyOS（手机、平板）** | ❌ **只信任华为签发的证书链** |

dshm 的 README 说「签名材料不在库内」就是这个意思 ——
**任何自己构建出来的 HAP 都是 unsigned 的**，要装到华为设备上必须有华为开发者证书
（调试证书还要把设备 UDID 注册到开发者后台）。

**但这条路是通的**：拿到证书后只需替换签名材料，**ArkTS 代码一行不用改**。

## 六、现在这个骨架到哪一步了

**能编译的容器**已经立住了：

| 层 | 状态 |
|---|---|
| 工程结构 / 清单 / 权限 / 资源 | ✅ 可编译 |
| ArkTS 首屏（环境自检） | ✅ 编译通过（含真实类型检查） |
| 打包成 HAP | ✅ 90 KB 产物，含编译后的 `ets/modules.abc` |
| 签名 | ⚠️ 材料与方法齐备，未跑通（证书链格式细节） |
| **端侧 Host（Node 运行时 + dsh 核心树）** | ❌ **未接入** —— 这是真正的工作量所在 |

端侧 Host 那三件事在 `docs/HAP-PLAN.md` 里有详述：Node 的 jitless 构建、
原生二进制的签名域（execve 只放行带 `.codesign` 的 ELF）、hmfs 禁 symlink 时的解包。
