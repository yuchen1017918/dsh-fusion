# 签名材料

这个目录放**你自己的**签名密钥。本目录下的 `.jks` 已被 `.gitignore` 排除，不会被提交。

## 为什么必须用独立密钥

社区里多个 DSH 安卓版共用过同一个包名与签名，导致「无法并排安装」。
本项目的包名是 `io.dsh.fusion`，签名也必须独立——两个条件同时成立才能真正并存。

## 生成

```bash
keytool -genkeypair -v \
  -keystore keystore/dsh-fusion.jks \
  -alias dsh-fusion \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$DSH_FUSION_KEY_PASSWORD" \
  -keypass  "$DSH_FUSION_KEY_PASSWORD" \
  -dname "CN=DSH Fusion, OU=Community, O=DSH Fusion Project, C=CN"
```

然后在工程根目录建 `keystore.properties`（照抄 `keystore.properties.example`），
或者设好那四个环境变量。

## 没有配置会怎样

构建不会失败，但会**退回 Android Debug 签名**——而 `tools/verify.sh` 会当场报红：

```
✘ 签名是 Android Debug 密钥（说明 signingConfigs 没生效，很可能 keystore.properties 路径错了）
```

这条断言不是摆设：开发过程中真的抓到过一次——`keystore.properties` 里的路径多写了一级 `../`，
签名悄悄退回了 debug，而没人会去主动核对。

## 丢了会怎样

`keytool` 生成的密钥没有找回途径。**请离线备份 `.jks` 与口令**。
丢了之后只能换新密钥重新发布，老用户必须卸载重装（签名不一致无法覆盖安装）。
