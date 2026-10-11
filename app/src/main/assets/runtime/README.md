# runtime/ —— 运行时种子（生成物，不入库）

这个目录里的东西全部由 `tools/build-runtime.py` 产出，`.gitignore` 已排除：

| 文件 | 说明 |
|---|---|
| `npm-10.9.2.tgz` | 内置 npm 安装包（约 2.6 MB），首启解包到 `filesDir` |
| `cert.pem` | 121 个根证书（约 188 KB），装到 `NODE_EXTRA_CA_CERTS` |
| `runtime-manifest.json` | 两个 ABI 的 .so 清单与 sha256，供构建期校验 |

真正的运行时二进制在 `app/src/main/jniLibs/{arm64-v8a,x86_64}/`，同样是生成物。

**克隆后第一次构建前，必须先跑：**

```bash
python3 tools/build-runtime.py
```

它需要 `python3`、`dpkg-deb`（`dpkg` 自带）、`curl` 与 `patchelf`（`pip install patchelf`），
会从 Termux 镜像拉约 25 MB/ABI 并装配出 13 个 `.so`。
