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

# ---------------------------------------------------------------- 汇总
echo
echo "═══════════════════════════════════════════════════════════════"
echo " 通过 ${PASS} · 警告 ${WARN} · 失败 ${FAIL}"
echo "═══════════════════════════════════════════════════════════════"
[ "$FAIL" -eq 0 ] && echo "${C_OK}交付自检通过。${C_R}" || echo "${C_ERR}有 $FAIL 项需要修。${C_R}"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
