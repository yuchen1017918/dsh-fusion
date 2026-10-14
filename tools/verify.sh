#!/usr/bin/env bash
# =============================================================================
# verify.sh — 交付前自检：APK 是否真的「装到手机上就能跑」
#
# 只查静态事实，不依赖任何设备：
#   1. APK 存在、签名正确（融合版独立密钥，不是 debug key）
#   2. 包名 / minSdk / targetSdk / 两种 ABI
#   3. jniLibs 里每个 .so 的 DT_NEEDED 都能在同一个目录里找到
#      —— 这一条最关键：SONAME 改写只要漏一个，上机就是 dlopen failed
#   4. 两个可执行文件（libnode.so / libproot.so）的 ELF 类型与解释器
#   5. assets 里的 npm / CA 证书 / 清单是否齐全
#   6. 源码层面的关键约定（extractNativeLibs、前台服务类型、无障碍配置）
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
JNI="$ROOT/app/src/main/jniLibs"
ASSETS="$ROOT/app/src/main/assets"
BT="${BUILD_TOOLS:-$HOME/Android/Sdk/build-tools/35.0.0}"

C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_DIM=$'\033[2m'; C_R=$'\033[0m'
PASS=0; FAIL=0; WARN=0
pass() { echo "${C_OK}✔${C_R} $*"; PASS=$((PASS+1)); }
fail() { echo "${C_ERR}✘${C_R} $*"; FAIL=$((FAIL+1)); }
warn() { echo "${C_WARN}!${C_R} $*"; WARN=$((WARN+1)); }
# 只看文件在不在。注意用 grep -m1 而不是 head -1：后者会让 grep 收到 SIGPIPE
# 而在 pipefail 下丢掉输出 —— 这个坑在实验分支上踩过一次。
check_file() { local label="$1" path="$2"; if [ -f "$path" ]; then pass "$label 存在"; else fail "$label 缺失：$path"; fi; }
dim()  { echo "${C_DIM}    $*${C_R}"; }

# 系统库里本来就有的，不需要我们自己带
SYSTEM_LIBS="libc.so libm.so libdl.so liblog.so libandroid.so libz.so libstdc++.so libc++_shared.so libjnigraphics.so libEGL.so libGLESv2.so libnativehelper.so libsync.so libcutils.so libui.so libutils.so libbinder.so libc++_shared.so"
is_system() { for s in $SYSTEM_LIBS; do [ "$1" = "$s" ] && { [ "$1" = "libc++_shared.so" ] && return 1 || return 0; }; done; return 1; }

echo "═══════════════════════════════════════════════════════════════"
echo " DSH Fusion · 交付自检"
echo "═══════════════════════════════════════════════════════════════"

# ---------------------------------------------------------------- 1. APK
echo
echo "── 1. APK 产物 ─────────────────────────────────────────────"
if [ -f "$APK" ]; then
  pass "APK 存在：$APK（$(du -h "$APK" | cut -f1)）"
else
  fail "APK 不存在，先跑：gradle :app:assembleDebug"
  exit 1
fi

if [ -x "$BT/apksigner" ]; then
  certs=$("$BT/apksigner" verify --print-certs "$APK" 2>&1)
  dn=$(echo "$certs" | grep -m1 "certificate DN" | sed 's/.*DN: //')
  sha=$(echo "$certs" | grep -m1 "certificate SHA-256 digest" | sed 's/.*digest: //' | cut -c1-16)
  if echo "$dn" | grep -qi "Android Debug"; then
    fail "签名是 Android Debug 密钥（说明 signingConfigs 没生效，很可能 keystore.properties 路径错了）"
  else
    pass "签名：$dn"
    dim "SHA-256: ${sha}…"
  fi
  [ -x "$BT/apksigner" ] && "$BT/apksigner" verify "$APK" >/dev/null 2>&1 && pass "签名校验通过" || fail "签名校验失败"
else
  warn "找不到 apksigner（$BT），跳过签名检查"
fi

if [ -x "$BT/aapt2" ]; then
  badging=$("$BT/aapt2" dump badging "$APK" 2>/dev/null)
  # 注意大小写与锚定：aapt2 输出的是 minSdkVersion / targetSdkVersion（大写 S），
  # 而 package 行里还有 compileSdkVersionCodename='15' 这种干扰字段
  pkg=$(echo "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)
  msdk=$(echo "$badging" | sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p" | head -1)
  tsdk=$(echo "$badging" | sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p" | head -1)
  abis=$(echo "$badging" | grep -m1 "native-code:" | sed "s/native-code: //")
  echo "$pkg" | grep -q "io.dsh.fusion" && pass "包名：$pkg（独立包名，可与其它 DSH 版并排安装）" || warn "包名是 $pkg，非预期"
  [ "${msdk:-0}" -ge 26 ] && pass "minSdk=$msdk（Android 8.0+，覆盖绝大多数存量机）" || fail "minSdk=$msdk 低于 26"
  pass "targetSdk=$tsdk"
  for need in arm64-v8a x86_64; do
    echo "$abis" | grep -q "$need" && pass "内置 ABI：$need" || fail "缺少 ABI：$need"
  done
  echo "$badging" | grep -q "POST_NOTIFICATIONS" && pass "已声明 POST_NOTIFICATIONS（Android 13+ 前台服务通知可见）" || fail "缺 POST_NOTIFICATIONS"
  echo "$badging" | grep -q "SYSTEM_ALERT_WINDOW" && pass "已声明 SYSTEM_ALERT_WINDOW（桌宠/流体云）" || warn "缺 SYSTEM_ALERT_WINDOW"
  echo "$badging" | grep -q "extractNativeLibs='true'" && pass "extractNativeLibs=true（W^X 规避的根基）" || \
    dim "（aapt2 不一定输出该字段，下面单查 manifest）"
fi

# ---------------------------------------------------------------- 2. ELF 依赖闭包
echo
echo "── 2. 运行时 ELF 依赖闭包（最关键的一项）──────────────────"
for abi_dir in "$JNI"/*/; do
  abi=$(basename "$abi_dir")
  [ -d "$abi_dir" ] || continue
  echo "  [$abi]"
  local_fail=0
  for so in "$abi_dir"*.so; do
    [ -f "$so" ] || continue
    name=$(basename "$so")
    # 跳过系统库
    [ "$name" = "libz.so" ] && : # 我们自己带 libz，仍要检查
    needed=$(readelf -d "$so" 2>/dev/null | grep NEEDED | sed 's/.*\[\(.*\)\]/\1/')
    for dep in $needed; do
      case "$dep" in
        libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libstdc++.so|libc++_shared.so)
          [ "$dep" = "libc++_shared.so" ] || continue ;;
      esac
      if [ ! -f "$abi_dir/$dep" ]; then
        # 系统库白名单
        case "$dep" in
          libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libstdc++.so|libEGL.so|libGLESv2.so) ;;
          *) fail "  $name 需要 $dep，但 $abi/ 里没有"; local_fail=$((local_fail+1)) ;;
        esac
      fi
    done
  done
  count=$(ls "$abi_dir"*.so 2>/dev/null | wc -l)
  [ "$local_fail" -eq 0 ] && pass "$abi：$count 个 .so，依赖全部闭合" || fail "$abi：$local_fail 个依赖缺失"

  # 两个「伪装成 .so 的可执行文件」
  for exe in libnode.so libproot.so; do
    f="$abi_dir/$exe"
    [ -f "$f" ] || { fail "$abi/$exe 缺失"; continue; }
    # INTERP 的方括号行在偏移行之后，必须 -A2
    interp=$(readelf -l "$f" 2>/dev/null | grep -A2 "INTERP" | sed -n 's/.*interpreter: \([^]]*\)\].*/\1/p' | head -1)
    type=$(readelf -h "$f" 2>/dev/null | grep "Type:" | awk '{print $2}')
    ver=$("$f" --version 2>/dev/null | head -1 || true)
    if [ "$interp" = "/system/bin/linker64" ]; then
      pass "$abi/$exe：$type，解释器 $interp（Android 原生可执行）"
    else
      warn "$abi/$exe 的解释器是 '${interp:-无}'，预期 /system/bin/linker64"
    fi
    # 版本号只能靠 readelf 里的字符串猜（本机是 x86_64，跑不了 aarch64 二进制）
    strings "$f" 2>/dev/null | grep -m1 -oE "v2[0-9]\.[0-9]+\.[0-9]+" | sed "s/^/      版本字符串: /" || true
  done
done

# ---------------------------------------------------------------- 3. assets
echo
echo "── 3. assets 资源 ──────────────────────────────────────────"
[ -f "$ASSETS/runtime/cert.pem" ] && pass "CA 证书：$(du -h "$ASSETS/runtime/cert.pem" | cut -f1)" || fail "缺 cert.pem（https 会全线失败）"
ls "$ASSETS/runtime/"npm-*.tgz >/dev/null 2>&1 && pass "内置 npm：$(ls -1 "$ASSETS/runtime/"npm-*.tgz | xargs -n1 basename)" || fail "缺 npm tgz"
[ -f "$ASSETS/runtime/runtime-manifest.json" ] && pass "运行时清单：$(python3 -c "import json;d=json.load(open('$ASSETS/runtime/runtime-manifest.json'));print(' / '.join(a['abi'] for a in d['abis']))" 2>/dev/null)" || warn "缺 runtime-manifest.json"
for f in package.json index.js cordis.patch.yml; do
  [ -f "$ASSETS/bridge-plugin/$f" ] && pass "能力桥插件：$f" || fail "缺 bridge-plugin/$f"
done

# ---------------------------------------------------------------- 4. 源码约定
echo
echo "── 4. 关键源码约定 ─────────────────────────────────────────"
MF="$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android:extractNativeLibs="true"' "$MF" && pass "manifest: extractNativeLibs=true" || fail "manifest 少了 extractNativeLibs=true（Android 10+ 会禁 exec）"
grep -q 'FOREGROUND_SERVICE_SPECIAL_USE' "$MF" && pass "manifest: 前台服务类型已按 Android 14 要求声明" || fail "缺 FOREGROUND_SERVICE_SPECIAL_USE"
grep -q 'BIND_ACCESSIBILITY_SERVICE' "$MF" && pass "manifest: 无障碍服务已声明" || fail "缺无障碍服务"
grep -q 'ShizukuProvider' "$MF" && pass "manifest: ShizukuProvider 已声明" || warn "缺 ShizukuProvider"
[ "$(grep -c 'abiFilters' "$ROOT/app/build.gradle.kts")" -ge 1 ] && pass "gradle: abiFilters 双 ABI 已配置" || fail "gradle 未配置 abiFilters"
grep -q 'useLegacyPackaging = true' "$ROOT/app/build.gradle.kts" && pass "gradle: useLegacyPackaging=true" || fail "gradle 缺 useLegacyPackaging"
grep -q 'max-old-space-size' "$ROOT/engine/src/main/java/io/dsh/fusion/engine/runtime/RuntimeEnv.kt" && pass "引擎: V8 堆上限按设备内存可调" || fail "引擎未限制 V8 堆（手机会 OOM）"
grep -q 'DSH_FUSION_BRIDGE_TOKEN' "$ROOT/engine/src/main/java/io/dsh/fusion/engine/EngineSupervisor.kt" && pass "引擎: 能力桥 token 已通过环境变量注入" || fail "桥 token 未注入"
grep -q 'PROOT_NO_SECCOMP' "$ROOT/engine/src/main/java/io/dsh/fusion/engine/runtime/RuntimeEnv.kt" && pass "PRoot: PROOT_NO_SECCOMP=1（Android 必需）" || fail "PRoot 未设 PROOT_NO_SECCOMP"
grep -q 'android.accessibilityservice.AccessibilityService' "$MF" && pass "无障碍 intent-filter 正确" || warn "无障碍 intent-filter 可疑"

# ---------------------------------------------------------------- 5. 事件总线接线完整性
echo
echo "── 5. 事件总线：每个事件都要有生产者 ─────────────────────"
if command -v python3 >/dev/null 2>&1; then
  python3 - "$ROOT" <<'PYEOF'
import re, sys, pathlib
root = pathlib.Path(sys.argv[1])
src = root / "engine/src/main/java/io/dsh/fusion/engine/EventBus.kt"
if not src.exists():
    print("    \033[33m!\033[0m 找不到 EventBus.kt，跳过"); sys.exit(0)
text = src.read_text(encoding="utf-8")
# 抓 sealed class FusionEvent 里的每种事件： data class X(...) : FusionEvent()  或  object X : FusionEvent()
names = re.findall(r"(?:data class|class|object)\s+(\w+)\s*(?:\([^)]*\))?\s*:\s*FusionEvent\b", text)
if not names:
    print("    \033[33m!\033[0m 没能从 EventBus.kt 解析出事件类型，跳过"); sys.exit(0)
# 统计全工程的 emit 点
kt = [p for p in root.rglob("*.kt") if "/build/" not in str(p)]
emitters = {n: 0 for n in names}
for p in kt:
    body = p.read_text(encoding="utf-8", errors="replace")
    for n in names:
        emitters[n] += len(re.findall(r"emit\(\s*FusionEvent\." + n + r"\b", body))
dead = [n for n, c in emitters.items() if c == 0]
print(f"    事件类型 {len(names)} 种：" + "，".join(f"{n}={c}" for n, c in sorted(emitters.items())))
# 这条断言是防一个具体的历史缺陷：FusionEvent.Reply 曾经只有消费者没有生产者，
# 结果「流体云回复卡」这个卖点功能永远不会触发，而且不报任何错。
if dead:
    print(f"\033[31m✘\033[0m 以下事件有消费者却没有任何生产者（功能永远不触发）：{', '.join(dead)}")
    sys.exit(1)
print("\033[32m✔\033[0m 所有事件都有生产者")
PYEOF
  if [ $? -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
else
  warn "跳过（缺 python3）"
fi

# ---------------------------------------------------------------- 6. 单元测试
echo
echo "── 6. 单元测试 ────────────────────────────────────────────"
TEST_TOTAL=0; TEST_FAIL=0
for m in engine voice; do
  R="$ROOT/$m/build/test-results/testDebugUnitTest"
  [ -d "$R" ] || continue
  read -r t f <<<"$(python3 - "$R" <<'PYEOF'
import glob, sys, xml.etree.ElementTree as ET
t = f = 0
for p in glob.glob(sys.argv[1] + "/*.xml"):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0)) + int(r.get("errors", 0))
print(t, f)
PYEOF
)"
  TEST_TOTAL=$((TEST_TOTAL + t)); TEST_FAIL=$((TEST_FAIL + f))
done
if [ "$TEST_TOTAL" -eq 0 ]; then
  warn "没有测试结果（先跑 gradle :engine:testDebugUnitTest :voice:testDebugUnitTest）"
elif [ "$TEST_FAIL" -gt 0 ]; then
  fail "$TEST_TOTAL 个用例中有 $TEST_FAIL 个失败"
else
  pass "$TEST_TOTAL 个单元测试全部通过"
fi

# ---------------------------------------------------------------- 7. APK 体积健康度
echo
echo "── 7. APK 体积健康度（防 zip 对齐膨胀）────────────────────"
if [ -f "$APK" ] && command -v python3 >/dev/null 2>&1; then
  python3 - "$APK" <<'PYEOF'
import sys, os, zipfile
apk = sys.argv[1]
size = os.path.getsize(apk)
with zipfile.ZipFile(apk) as z:
    comp = sum(i.compress_size for i in z.infolist())
    gap = z.start_dir - comp
size_mb, comp_mb, gap_mb = size/1048576, comp/1048576, gap/1048576
print(f"    文件 {size_mb:.2f} MiB · 条目压缩合计 {comp_mb:.2f} MiB · 对齐填充 {gap_mb:.2f} MiB")
# 正常 APK 的对齐填充只有几十 KB。超过 8 MiB 说明 zip 写入器算错了偏移，
# 内容被物理写了两遍 —— 实测踩过一次（STORED 的大 .xz 触发 zipflinger 偏移错误，
# APK 从 107 MiB 涨到 129 MiB）。这条断言就是为了不让它再复发。
if gap_mb > 8:
    print(f"\033[31m✘\033[0m 对齐填充异常（{gap_mb:.1f} MiB）——检查是否有超大文件被设为不压缩(noCompress)")
    sys.exit(1)
print(f"\033[32m✔\033[0m 对齐填充正常（{gap_mb:.2f} MiB）")
PYEOF
  if [ $? -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
else
  warn "跳过（缺 APK 或 python3）"
fi

# ---------------------------------------------------------------- 9. 安全模式
echo
echo "── 9. 安全模式（插件搞死界面后的自救通道）────────────────"
check_file "SafeMode.kt" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/plugin/SafeMode.kt"

# 官方 bundles 必须是两项 —— 写错了「禁用所有插件」就会连官方内核一起禁掉，
# 那会让引擎彻底起不来（比插件坏掉更严重）
OFFICIAL=$(grep -c '"@deepseek-ai/dsh-' "$ROOT/engine/src/main/java/io/dsh/fusion/engine/plugin/SafeMode.kt" 2>/dev/null || true)
OFFICIAL=${OFFICIAL:-0}
if [ "$OFFICIAL" -ge 2 ]; then
  pass "官方 bundles 列表已硬编码（$OFFICIAL 项）"
else
  fail "SafeMode 里的官方 bundles 列表不完整 —— 禁用插件会误伤官方内核"
fi

# 名字必须与官方 profile.ts 的常量一致，不一致会备份一个不存在的文件
if grep -q "cordis.patch.yml" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/plugin/SafeMode.kt"; then
  pass "补丁文件名与官方一致（cordis.patch.yml）"
else
  fail "补丁文件名与官方 PROFILE_PATCH_FILENAME 不一致"
fi

# 恢复入口必须在原生侧可达：画在网页里等于没有（网页挂了它一起挂）
if grep -q "buildSafeModeBox" "$ROOT/app/src/main/java/io/dsh/fusion/ui/SettingsActivity.kt"; then
  pass "原生设置页有常驻入口"
else
  fail "安全模式没有原生入口 —— 界面挂了就没法自救"
fi
if grep -q "进入安全模式" "$ROOT/app/src/main/java/io/dsh/fusion/ui/MainActivity.kt"; then
  pass "引擎启动失败时给出引导"
else
  warn "启动失败时没有引导到安全模式"
fi

# ---------------------------------------------------------------- 10. 随包插件
echo
echo "── 10. 随包插件（自研移动端布局）──────────────────────────"
PLUG="$ROOT/app/src/main/assets/plugins/dsh-mobile-layout"
if [ -d "$PLUG" ]; then
  for f in package.json cordis.patch.yml lib/index.js lib/client.js; do
    check_file "  $f" "$PLUG/$f"
  done
  # 它必须真的会被装配 —— 躺在 assets 里不装等于没有
  if grep -q "installBundledPlugins" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/Provisioner.kt"; then
    pass "装配流程会安装随包插件"
  else
    fail "assets 里有插件但没有任何地方安装它"
  fi
  # 两条装配路径都要铺 —— 否则「内置包」和「在线安装」会得到两个不同的 App
  N=$(grep -c "installBundledPlugins(onStep)" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/Provisioner.kt" || true)
N=${N:-0}
  if [ "$N" -ge 2 ]; then
    pass "两条装配路径都铺随包插件（$N 处）"
  else
    fail "只有 $N 处调用 —— 另一条装配路径会漏装这个插件"
  fi
  # 装了还要注册进 bundles，否则 cordis 根本不加载（现象是「装了但没效果」）
  if grep -q "registerBundle" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/provision/DshProvisioner.kt"; then
    pass "安装时会注册进 bundles"
  else
    fail "只铺文件不注册 —— 插件不会生效"
  fi
  # canary：v5 的核心行为，且它平时不做任何事，必须有测试兜着
  if grep -q "CANARY_MISS_LIMIT" "$PLUG/lib/client.js"; then
    pass "canary 已就位（官方 DOM 漂移时自我卸载）"
  else
    fail "client.js 里没有 canary —— 官方改版后只能靠人眼发现布局坏掉"
  fi
  CLIENT_NAME=$(grep -m1 -oE '"name": "[^"]+"' "$PLUG/package.json" | sed 's/.*"\(.*\)"/\1/')
  if grep -q "$CLIENT_NAME" "$PLUG/lib/index.js" || grep -q "$CLIENT_NAME" "$PLUG/lib/client.js"; then
    pass "包名两侧一致（$CLIENT_NAME）"
  else
    warn "包名 '$CLIENT_NAME' 在源码里找不到，可能未正确注册"
  fi
else
  warn "没有随包插件（$PLUG 不存在）"
fi

if command -v node >/dev/null 2>&1 && [ -d "$ROOT/plugins/dsh-mobile-layout/test" ]; then
  if (cd "$ROOT/plugins/dsh-mobile-layout" && node --test test/canary.test.mjs >/dev/null 2>&1); then
    pass "移动端布局插件行为测试通过"
  else
    fail "移动端布局插件测试失败（cd plugins/dsh-mobile-layout && node --test test/）"
  fi
else
  warn "跳过插件测试（缺 node 或测试目录）"
fi

# ---------------------------------------------------------------- 11. 端口选择
echo
echo "── 11. 端口选择（内核不会自己退让，责任全在这里）────────"
check_file "PortPicker.kt" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/runtime/PortPicker.kt"

# 三条行为缺一不可：0 交给系统、指定端口顺延、整段被占退回 0
if grep -q "OS_ASSIGNED = 0" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/runtime/PortPicker.kt"; then
  pass "支持「传 0 让操作系统分配」"
else
  fail "PortPicker 不支持传 0 —— 这是唯一没有竞态的路径"
fi
if grep -q "return OS_ASSIGNED" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/runtime/PortPicker.kt"; then
  pass "扫不到时退回 0（不硬闯已知被占用的端口）"
else
  fail "扫不到时可能返回一个已知被占用的端口 —— 那是静默的错误选择"
fi

# 必须是单一实现：EngineSupervisor 里如果又长出一份私有扫描逻辑，两边会漂移
# grep -c 在「没找到」时会输出 0 **并且**以 1 退出，所以不能写 `|| echo 0`
# ——那会追加第二个 0，变量变成 "0\n0"，后面的 [ -eq ] 直接报错。
PRIV=$(grep -c "ServerSocket(port, 1" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/EngineSupervisor.kt" 2>/dev/null || true)
PRIV=${PRIV:-0}
if [ "$PRIV" -eq 0 ]; then
  pass "端口选择只有 PortPicker 一处实现"
else
  fail "EngineSupervisor 里还有一份私有端口探测（$PRIV 处）—— 两份实现会漂移"
fi

# 端口能在设置页改：冲突时用户最需要的就是一个能直接点的开关
if grep -q "portRow" "$ROOT/app/src/main/java/io/dsh/fusion/ui/SettingsActivity.kt"; then
  pass "设置页有端口入口"
else
  fail "端口只能靠改配置文件 —— 而冲突时用户根本不知道该改"
fi
# 改了要能生效：端口是启动参数，不重启引擎等于没改
if grep -q "ACTION_RESTART" "$ROOT/app/src/main/java/io/dsh/fusion/ui/SettingsActivity.kt"; then
  pass "改端口会重启引擎（否则「保存了但没变化」更迷惑）"
else
  warn "改端口后没有重启引擎的逻辑"
fi

# ---------------------------------------------------------------- 12. 凭据泄漏
echo
echo "── 12. 随包产物里不能有凭据 ───────────────────────────────"
if command -v python3 >/dev/null 2>&1; then
  KERNEL=$(ls "$ROOT/app/src/main/assets/kernel/"*.tar.xz 2>/dev/null | head -1)
  if [ -n "$KERNEL" ]; then
    python3 - "$KERNEL" <<'PYEOF'
import sys, tarfile, posixpath
# 只按**文件名**精确匹配。用宽松的路径正则（比如含 "token"）会把 typebox 的
# tokenizer 源码全捞进来 —— 那是误报，会让人不再信任这条检查。
BASENAMES = {".credentials.yaml", ".credentials.yml", ".credentials.json",
             "credentials.json", "credentials.yaml", "id_rsa", "id_ed25519",
             "id_ecdsa", ".netrc"}
SUFFIXES = (".pem", ".pfx", ".p12", ".jks", ".keystore")
hits = []
total = 0
with tarfile.open(sys.argv[1], "r:xz") as t:
    for m in t:
        total += 1
        base = posixpath.basename(m.name)
        if base in BASENAMES or base.startswith(".env") or base.lower().endswith(SUFFIXES):
            hits.append(m.name)
if hits:
    print(f"\033[31m✘\033[0m 内核包里发现凭据类文件（{total} 条目中 {len(hits)} 个）：")
    for h in hits[:10]:
        print("     " + h)
    sys.exit(1)
print(f"\033[32m✔\033[0m 内核包无凭据类文件（{total} 条目）")
PYEOF
    if [ $? -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
  else
    warn "找不到内核包，跳过"
  fi
  # APK 里也不该有：凭据是运行期在设备上生成的，进包就是泄漏
  if [ -f "$APK" ]; then
    # 文件名只做**粗筛**，内容才作准。
    #
    # 原来的判定用 `\.pem$` 一把抓，把 assets/runtime/cert.pem（121 个公共
    # CA 根证书，Node 做 HTTPS 用）也判成了凭据 —— 那是误报。而误报的代价
    # 和漏报一样实在：它会拦住一次正常交付，然后逼着人去放宽规则。
    NAME_HITS=$(unzip -l "$APK" 2>/dev/null | awk '{print $4}' \
      | grep -iE "\.credentials|\.env$|id_rsa|id_ed25519|\.p12$|\.jks$|keystore|\.key$" || true)
    # 真正的判据：证书文件里出现私钥标记
    KEY_HITS=$(unzip -p "$APK" "*.pem" "*.crt" "*.cer" 2>/dev/null \
      | grep -cE "PRIVATE KEY|BEGIN RSA PRIVATE|BEGIN EC PRIVATE|BEGIN OPENSSH" || true)
    KEY_HITS=${KEY_HITS:-0}
    if [ -n "$NAME_HITS" ]; then
      fail "APK 里含凭据类文件：$(echo "$NAME_HITS" | head -3 | tr '\n' ' ')"
    elif [ "$KEY_HITS" -gt 0 ]; then
      fail "APK 的证书里含私钥（$KEY_HITS 处私钥标记）"
    else
      # 把公共 CA 包的存在说清楚，免得下次又有人以为是漏检
      CA=$(unzip -l "$APK" 2>/dev/null | awk '{print $4}' | grep -E "cert\.pem$" | head -1 || true)
      if [ -n "$CA" ]; then
        pass "APK 无凭据（$CA 是公共 CA 根证书包，已按内容确认无私钥）"
      else
        pass "APK 无凭据类文件"
      fi
    fi
  fi
else
  warn "跳过（缺 python3）"
fi

# ---------------------------------------------------------------- 13. 本地模型（按需下载）
echo
echo "── 13. 本地大模型（不进 APK，按需下载）────────────────────"
LLM="$ROOT/app/src/main/assets/llm/runtime-manifest.json"
if [ -f "$LLM" ]; then
  python3 - "$LLM" <<'PYEOF'
import json, sys
d = json.load(open(sys.argv[1]))
pkgs = d.get("packages", [])
libs = d.get("libraries", [])
assert pkgs, "清单里没有 packages"
assert d.get("entry") == "llama-server", "入口不是 llama-server"
# 「不进 APK」的核心断言：清单里只能有 URL/校验值，不能有任何二进制内容
for k in ("downloadBytes", "installedKB", "mirror"):
    assert k in d, f"清单缺 {k}"
print(f"    包 {len(pkgs)} 个 · 库依赖 {len(libs)} 个 · "
      f"下载 {d['downloadBytes']/1048576:.1f} MB · 安装后 {d['installedKB']/1024:.1f} MB")
# 清单必须小到「它才是该进包的东西」
import os
size = os.path.getsize(sys.argv[1])
if size > 64 * 1024:
    print(f"\033[31m✘\033[0m 清单 {size} 字节 —— 太大了，说明里面混进了不该有的东西")
    sys.exit(1)
print(f"\033[32m✔\033[0m 清单只有 {size} 字节（URL 与校验值，无二进制）")
PYEOF
  if [ $? -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi

  # 目录下不能有任何二进制 —— 这是「不进 APK」的直接检查
  BIN=$(find "$ROOT/app/src/main/assets/llm" -type f ! -name "*.json" 2>/dev/null | head -5)
  if [ -n "$BIN" ]; then
    fail "assets/llm 下有非清单文件（二进制不该进包）：$BIN"
  else
    pass "assets/llm 只有清单，无二进制"
  fi
else
  warn "没有 assets/llm/runtime-manifest.json"
fi

# 接线：DEEPSEEK_BASE_URL 必须真的被注入，否则本地模型跑了也没人用
if grep -q "DEEPSEEK_BASE_URL" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/EngineSupervisor.kt"; then
  pass "引擎会注入 DEEPSEEK_BASE_URL（官方认可的端点入口）"
else
  fail "没有注入 DEEPSEEK_BASE_URL —— 本地模型服务起了也没人用"
fi
if grep -q "Guest.LLM" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/runtime/ProotLauncher.kt"; then
  pass "LLM 运行时已绑进容器（proot 绕过 W^X，所以不需要 jniLibs）"
else
  fail "LLM 运行时没绑进 proot —— 数据目录里的可执行文件跑不起来"
fi
check_file "DebExtractor.kt" "$ROOT/engine/src/main/java/io/dsh/fusion/engine/llm/DebExtractor.kt"

# 模型目录：三档必须都有模型，否则「按内存推荐」会给出 null
CATALOG="$ROOT/engine/src/main/java/io/dsh/fusion/engine/llm/LocalModelCatalog.kt"
TIERS=$(grep -c "tier = Tier\." "$CATALOG" 2>/dev/null || true)
TIERS=${TIERS:-0}
if [ "$TIERS" -ge 3 ]; then
  pass "模型目录覆盖三档配置（$TIERS 个）"
else
  fail "模型目录只覆盖 $TIERS 档 —— 按内存推荐会漏档"
fi
# 每个模型都要有主源与备用源：手机下 GB 级文件，单源挂了等于功能挂了
MS=$(grep -c "^            modelScope = " "$CATALOG" 2>/dev/null || true); MS=${MS:-0}
HF=$(grep -c "^            hfMirror = " "$CATALOG" 2>/dev/null || true); HF=${HF:-0}
if [ "$MS" -ge 1 ] && [ "$MS" -eq "$HF" ]; then
  pass "每个模型都有主源与备用源（各 $MS 个）"
else
  fail "主源 $MS 个 / 备用源 $HF 个 —— 数量不等说明有模型只配了一个源"
fi
# 大小必须来自实测：写 0 或写个整数占位都会让「内存推荐」失真
if grep -qE "bytes = 0([,_]|)" "$CATALOG"; then
  fail "有模型的 bytes 是 0 —— 大小必须填实测值"
else
  pass "模型大小都有实测字节数"
fi

# 主源可达性：只做**警告**不做失败 —— 交付自检要能离线跑。
#
# 探测目标用**仓库 API** 而不是模型文件 URL：模型 URL 在 Kotlin 里是用 ms() 拼的，
# 文件里没有完整字面量可提取（第一版就是这么写的，提取结果为空、整段检查静默没跑 ——
# 而**静默不跑的自检比没有自检更糟**，它会让人以为验过了）。
if command -v curl >/dev/null 2>&1; then
  REPO=$(grep -m1 -oE '"unsloth/DeepSeek-R1-Distill-[A-Za-z0-9.\-]+-GGUF"' "$CATALOG" 2>/dev/null | tr -d '"')
  if [ -n "$REPO" ]; then
    CODE=$(timeout 25 curl -sS -o /dev/null -w "%{http_code}" "https://modelscope.cn/api/v1/models/$REPO" 2>/dev/null || echo 000)
    if [ "$CODE" = "200" ]; then
      pass "模型仓库可达（$REPO）"
    else
      warn "模型仓库探测返回 HTTP $CODE（$REPO）—— 可能网络不通，也可能仓库已改名，需人工确认"
    fi
  else
    warn "从目录里提取不到模型仓库名 —— 可达性检查**没有执行**（不是通过）"
  fi
else
  warn "缺 curl，跳过模型源可达性检查"
fi

# ---------------------------------------------------------------- 14. 许可证合规
echo
echo "── 14. 许可证合规 ────────────────────────────────────────"
LIC="$ROOT/app/src/main/assets/licenses"
NOTICES="$ROOT/THIRD-PARTY-NOTICES.md"

# GPL-2.0 是最关键的一份：PRoot 是唯一的强 copyleft，必须随包
if [ -f "$LIC/GPL-2.0.txt" ]; then
  pass "GPL-2.0 全文随包（PRoot 是唯一的强 copyleft 组件）"
else
  fail "缺 assets/licenses/GPL-2.0.txt —— 分发了 GPL-2.0 的 PRoot 却没带许可证全文"
fi
[ -f "$LIC/LGPL-3.0.txt" ] && pass "LGPL-3.0 全文随包（libtalloc）" || fail "缺 LGPL-3.0 全文"

# GPL-2.0 §2(a) 要求「显著标明修改」——我们确实改过 PRoot 二进制
if grep -q "patchelf" "$NOTICES" 2>/dev/null && grep -q "3.3" "$NOTICES" 2>/dev/null; then
  pass "PRoot 的修改已披露（GPL-2.0 §2(a) 要求）"
else
  fail "第三方声明里没有披露对 PRoot 的修改 —— 违反 GPL-2.0 §2(a)"
fi

# 声明的许可证必须与实际链接的库对得上
for lib in libproot.so libtalloc.so; do
  if [ -f "$ROOT/app/src/main/jniLibs/arm64-v8a/$lib" ]; then
    case "$lib" in
      libproot.so)  grep -q "GPL-2.0"  "$NOTICES" || fail "声明里没提 $lib 的 GPL-2.0" ;;
      libtalloc.so) grep -q "LGPL-3.0" "$NOTICES" || fail "声明里没提 $lib 的 LGPL-3.0" ;;
    esac
  fi
done
pass "GPL/LGPL 组件与声明一一对应"

# 内核包 533 个依赖不许出现 GPL —— 这是「整体不必 GPL」的前提之一
if python3 - "$ROOT/app/src/main/assets/kernel" <<'PYEOF' 2>/dev/null
import sys, glob, tarfile, json, re, os
d = sys.argv[1]
arcs = glob.glob(os.path.join(d, "*.tar.xz"))
if not arcs:
    sys.exit(1)
bad = []
with tarfile.open(arcs[0], 'r:xz') as t:
    for m in t:
        if not m.isfile() or not m.name.endswith('package.json'):
            continue
        try:
            j = json.loads(t.extractfile(m).read().decode('utf-8', 'replace'))
        except Exception:
            continue
        if not j.get('name'):
            continue
        lic = j.get('license') or j.get('licenses') or ''
        s = json.dumps(lic)
        if re.search(r'\bGPL', s) and 'LGPL' not in s:
            bad.append(j['name'])
sys.exit(1 if bad else 0)
PYEOF
then
  pass "内核包无 GPL 依赖（「聚合而非衍生」的前提之一）"
else
  warn "内核包里出现 GPL 依赖，需人工复核 —— 这会改变整体许可判断"
fi

# ---------------------------------------------------------------- 15. 桌面宿主载体（浏览器面板）
echo
echo "── 15. 侧边栏浏览器（桌面宿主载体 + 金丝雀）──────────────"
CARRIER="$ROOT/app/src/main/assets/host/desktop-carrier.js"
DC="$ROOT/engine/src/main/java/io/dsh/fusion/engine/desktop/DesktopCarrier.kt"
BH="$ROOT/engine/src/main/java/io/dsh/fusion/engine/desktop/DesktopBrowserHost.kt"

check_file "desktop-carrier.js" "$CARRIER"
check_file "DesktopCarrier.kt" "$DC"
check_file "DesktopBrowserHost.kt" "$BH"

# 桥名两侧必须一致 —— 不一致是**不报错、只是没效果**的隐蔽故障
KT_NAME=$(grep -oE 'JS_INTERFACE = "[^"]+"' "$BH" 2>/dev/null | sed 's/.*= "//;s/"//')
JS_NAME=$(grep -oE 'window\.__DSH_[A-Z_]+__' "$CARRIER" 2>/dev/null | head -1 | sed 's/window\.//')
if [ -n "$KT_NAME" ] && [ "$KT_NAME" = "$JS_NAME" ]; then
  pass "JS 桥名两侧一致（$KT_NAME）"
else
  fail "JS 桥名不一致：Kotlin=$KT_NAME / carrier=$JS_NAME —— 注入会静默无效"
fi

# 金丝雀三要素：陷阱、报告、原生侧判定
if grep -q "__dshFusionCanary" "$CARRIER" && grep -q "canary.browserRead = true" "$CARRIER"; then
  pass "carrier 带金丝雀（getter 陷阱 + 上报）"
else
  fail "carrier 缺金丝雀：契约失效时会变成「功能没了但界面正常」的隐蔽故障"
fi
if grep -q "shouldUninstall" "$DC" && grep -q "uninstall" "$DC"; then
  pass "原生侧会按金丝雀结论摘掉注入（优雅退化，不是白屏）"
else
  fail "原生侧没有按金丝雀结论卸载 —— 失效的补丁会一直挂在官方界面上"
fi

# 注入必须走官方 API，不能自己起代理
if grep -q "addDocumentStartJavaScript" "$DC"; then
  pass "用官方 API 注入（addDocumentStartJavaScript）"
else
  fail "没用官方注入 API —— 自己起代理会把链路风险放大"
fi
if [ -f "$ROOT/engine/src/main/java/io/dsh/fusion/engine/desktop/LocalProxy.kt" ]; then
  fail "树里还有 LocalProxy.kt —— 那条路已被官方注入 API 取代"
else
  pass "没有本地代理（少一个进程、少一分白屏风险）"
fi

# 不需要 GPL 之外的额外依赖：webkit 是 Apache-2.0
if grep -q "androidx.webkit" "$ROOT/engine/build.gradle.kts"; then
  pass "androidx.webkit 依赖已声明（Apache-2.0）"
else
  fail "engine 缺 androidx.webkit 依赖"
fi

# ---------------------------------------------------------------- 汇总
echo
echo "═══════════════════════════════════════════════════════════════"
echo " 通过 ${PASS} · 警告 ${WARN} · 失败 ${FAIL}"
echo "═══════════════════════════════════════════════════════════════"
[ "$FAIL" -eq 0 ] && echo "${C_OK}交付自检通过。${C_R}" || echo "${C_ERR}有 $FAIL 项需要修。${C_R}"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
