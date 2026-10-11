#!/usr/bin/env bash
# =============================================================================
# adb-androws.sh — 在 WSL2 里打通「应用宝电脑版」的 adb 调试通道
#
# 背景（实测结论，不是猜的）：
#   应用宝电脑版把安卓跑在 VirtualBox 里 —— D:\program files\Tencent\Androws\
#   Image\Logs\VBox.log 明确写着 `VirtualBox 6.1.36 win.amd64`，镜像为
#   system.vhd / data.vhd / swap.vhd。也就是说那是一个 **x86_64 的安卓虚拟机**，
#   不是 Intel Bridge 的 ARM 翻译层（Intel Bridge 只存在于 Intel 平台的 WSA，
#   本机是 AMD Ryzen 5 5500U，更谈不上）。
#   结论：只出 arm64-v8a 的 APK 在应用宝里**装不上**。所以本项目的运行时
#   出两份（arm64-v8a + x86_64），见 tools/build-runtime.py。
#
# 这个脚本负责：找到应用宝自带的 adb.exe → 起 Windows 侧 adb server →
#   等设备 → 安装 APK → 启动 → 抓日志。每一步都能单独跑。
#
# 用法：
#   tools/adb-androws.sh detect     # 只看环境（默认）
#   tools/adb-androws.sh start      # 拉起应用宝的安卓虚拟机
#   tools/adb-androws.sh wait       # 等 adb 认出设备
#   tools/adb-androws.sh install    # 安装 APK
#   tools/adb-androws.sh launch     # 启动应用并抓启动日志
#   tools/adb-androws.sh all        # 全流程
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
PKG="io.dsh.fusion"
ACTIVITY="$PKG/.ui.MainActivity"
LOG_DIR="$ROOT/.build/adb-logs"
mkdir -p "$LOG_DIR"

C_RESET=$'\033[0m'; C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_DIM=$'\033[2m'
ok()   { echo "${C_OK}✔${C_RESET} $*"; }
warn() { echo "${C_WARN}!${C_RESET} $*"; }
err()  { echo "${C_ERR}✘${C_RESET} $*"; }
dim()  { echo "${C_DIM}  $*${C_RESET}"; }

# ---------------------------------------------------------------- 定位 adb

find_androws_adb() {
  local base candidates
  for base in "/mnt/d/program files/Tencent/Androws/Application" \
              "/mnt/c/Program Files/Tencent/Androws/Application" \
              "/mnt/c/Program Files (x86)/Tencent/Androws/Application"; do
    [ -d "$base" ] || continue
    # 取版本号最大的那个目录
    candidates=$(find "$base" -maxdepth 2 -name adb.exe 2>/dev/null | sort -V | tail -1)
    [ -n "$candidates" ] && { echo "$candidates"; return 0; }
  done
  return 1
}

find_linux_adb() {
  for p in "$HOME/Android/Sdk/platform-tools/adb" "$(command -v adb 2>/dev/null)"; do
    [ -n "${p:-}" ] && [ -x "$p" ] && { echo "$p"; return 0; }
  done
  return 1
}

ANDROWS_ADB="$(find_androws_adb || true)"
LINUX_ADB="$(find_linux_adb || true)"

# Windows 侧 adb.exe 通过 WSL interop 调用；-s 静默掉 interop 的杂音
win_adb() {
  [ -n "$ANDROWS_ADB" ] || { err "找不到应用宝的 adb.exe"; return 1; }
  timeout 90 "$ANDROWS_ADB" "$@" 2>&1 | tr -d '\r'
}

adb_bin() {
  if [ -n "$LINUX_ADB" ]; then echo "$LINUX_ADB"; else echo "$ANDROWS_ADB"; fi
}

# ---------------------------------------------------------------- detect

cmd_detect() {
  echo "── 环境侦察 ────────────────────────────────────────────────"
  echo "宿主 CPU      : $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs)"
  echo "WSL 互操作    : $([ -f /proc/sys/fs/binfmt_misc/WSLInterop ] && echo '已启用' || echo '未启用（无法调用 Windows 程序）')"

  if [ -n "$ANDROWS_ADB" ]; then
    ok "应用宝 adb.exe: $ANDROWS_ADB"
    dim "$(win_adb version | head -2 | tr '\n' ' ')"
  else
    err "没找到应用宝的 adb.exe —— 应用宝电脑版可能没装，或装在非默认路径"
  fi

  if [ -n "$LINUX_ADB" ]; then
    ok "Linux 侧 adb  : $LINUX_ADB"
  else
    warn "Linux 侧没有 adb（可 sdkmanager --install platform-tools）"
  fi

  echo
  echo "── 虚拟机状态 ──────────────────────────────────────────────"
  local running
  running=$(timeout 25 /mnt/c/Windows/System32/tasklist.exe 2>/dev/null | grep -ciE "Androws\.exe|AndrowsVm\.exe" || true)
  if [ "${running:-0}" -gt 0 ]; then
    ok "应用宝安卓虚拟机正在运行"
  else
    warn "应用宝安卓虚拟机没有在运行（只有后台服务 AndrowsSvr）"
    dim "adb 要能认出设备，必须先在桌面打开「应用宝」把虚拟机跑起来"
  fi
  local server
  server=$(timeout 25 /mnt/c/Windows/System32/netstat.exe -ano 2>/dev/null | grep -c ':5037 .*LISTENING' || true)
  [ "${server:-0}" -gt 0 ] && ok "Windows 侧 adb server 已在 5037 监听" || warn "Windows 侧还没有 adb server（脚本会帮你起）"

  echo
  echo "── 设备列表 ────────────────────────────────────────────────"
  if [ -n "$ANDROWS_ADB" ]; then
    win_adb start-server >/dev/null 2>&1
    win_adb devices -l | sed 's/^/  /'
  fi

  echo
  echo "── 其它可用通道 ────────────────────────────────────────────"
  dim "真机 USB ：WSL2 看不到 USB，需要在 Windows 侧 usbipd attach 之后再 adb connect"
  dim "无线调试 ：手机上开「无线调试」后用  $0 connect <手机IP>:<端口>"
  dim "本机 APK ：$APK  ($([ -f "$APK" ] && du -h "$APK" | cut -f1 || echo '尚未构建'))"
}

# ---------------------------------------------------------------- start

cmd_start() {
  local launcher="/mnt/d/Desktop/应用宝.lnk"
  if [ -n "$ANDROWS_ADB" ]; then
    local dir; dir="$(dirname "$ANDROWS_ADB")"
    if [ -x "$dir/AndrowsLauncher.exe" ]; then
      warn "即将在 Windows 桌面启动应用宝（会在你的桌面上弹出窗口）"
      (cd "$dir" && timeout 20 ./AndrowsLauncher.exe >/dev/null 2>&1 &) || true
      ok "已请求启动；虚拟机冷启动通常 30–90 秒"
      return 0
    fi
  fi
  err "无法自动启动：请手动双击桌面上的「应用宝」"
  dim "快捷方式实际指向：$launcher"
}

# ---------------------------------------------------------------- wait

cmd_wait() {
  [ -n "$ANDROWS_ADB" ] || { err "没有可用的应用宝 adb"; return 1; }
  local timeout_s="${1:-150}" waited=0
  echo "等待 adb 认出设备（最多 ${timeout_s}s）…"
  while [ "$waited" -lt "$timeout_s" ]; do
    local devices
    devices=$(win_adb devices | awk 'NR>1 && $2=="device" {print $1}')
    if [ -n "$devices" ]; then
      ok "已连接：$devices"
      win_adb shell getprop ro.product.cpu.abi | sed 's/^/    ABI: /'
      win_adb shell getprop ro.build.version.release | sed 's/^/    Android: /'
      return 0
    fi
    sleep 5; waited=$((waited+5))
    printf "\r  ${C_DIM}已等待 %ss…${C_RESET}  " "$waited"
  done
  echo
  err "超时：adb 仍然没有看到设备"
  dim "排查顺序：① 应用宝是否真的开着了  ② 应用宝内是否开了「ADB 调试助手」"
  dim "          ③ 设备是否显示为 offline（adb kill-server 后重试）"
  return 1
}

# ---------------------------------------------------------------- install

cmd_install() {
  [ -f "$APK" ] || { err "APK 不存在：$APK（先跑 gradle :app:assembleDebug）"; return 1; }
  local abis
  abis=$(unzip -l "$APK" 2>/dev/null | grep -oE 'lib/[a-z0-9_-]+/' | sort -u | tr '\n' ' ')
  echo "APK    : $APK  ($(du -h "$APK" | cut -f1))"
  echo "内置ABI: $abis"

  local devabi
  devabi=$(win_adb shell getprop ro.product.cpu.abi | tr -d '\r')
  echo "设备ABI: ${devabi:-未知}"
  case "$devabi" in
    x86_64)  [[ "$abis" == *"x86_64"*    ]] || { err "设备是 x86_64，但 APK 里没有 x86_64 运行时"; return 1; } ;;
    arm64*)  [[ "$abis" == *"arm64-v8a"* ]] || { err "设备是 arm64，但 APK 里没有 arm64-v8a 运行时"; return 1; } ;;
  esac

  echo
  echo "安装中（-r 覆盖安装，-d 允许降级，-g 授予全部运行时权限）…"
  local out
  out=$(win_adb install -r -d -g "$APK" 2>&1)
  echo "$out" | sed 's/^/  /'
  if echo "$out" | grep -q "Success"; then
    ok "安装成功"
    return 0
  fi
  err "安装失败"
  dim "若提示 INSTALL_FAILED_NO_MATCHING_ABIS → 镜像缺少该 ABI 的翻译层"
  dim "若提示 INSTALL_PARSE_FAILED → APK 本身有问题，看上面详情"
  dim "若应用宝弹窗拒绝    → 那是分发器策略，不是 APK 的问题；换 adb 直装即可绕过"
  return 1
}

# ---------------------------------------------------------------- launch

cmd_launch() {
  local stamp; stamp=$(date +%Y%m%d-%H%M%S)
  local log="$LOG_DIR/launch-$stamp.log"
  win_adb logcat -c >/dev/null 2>&1
  echo "启动 $ACTIVITY …"
  win_adb shell am start -n "$ACTIVITY" | sed 's/^/  /'
  echo "抓取 12 秒启动日志 -> $log"
  ( timeout 12 "$ANDROWS_ADB" logcat 2>/dev/null | tr -d '\r' > "$log" ) || true
  echo
  echo "── 关键行 ──────────────────────────────────────────────────"
  grep -E "DSH/|AndroidRuntime|FATAL|io\.dsh\.fusion|linker|dlopen|exec" "$log" | head -30 | sed 's/^/  /'
  echo
  local size; size=$(wc -l < "$log")
  ok "完整日志 $size 行 -> $log"
  if grep -q "FATAL EXCEPTION" "$log"; then
    err "启动崩了，见上面 AndroidRuntime 段"
    return 1
  fi
  ok "没有致命异常"
}

# ---------------------------------------------------------------- connect

cmd_connect() {
  local target="${1:-}"
  [ -n "$target" ] || { err "用法: $0 connect <IP:端口>"; return 1; }
  local bin; bin=$(adb_bin)
  "$bin" connect "$target" 2>&1 | sed 's/^/  /'
  "$bin" devices -l | sed 's/^/  /'
}

# ---------------------------------------------------------------- main

case "${1:-detect}" in
  detect)  cmd_detect ;;
  start)   cmd_start ;;
  wait)    cmd_wait "${2:-150}" ;;
  install) cmd_install ;;
  launch)  cmd_launch ;;
  connect) cmd_connect "${2:-}" ;;
  all)
    cmd_detect
    if ! win_adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | grep -q .; then
      cmd_start
      cmd_wait 180 || exit 1
    fi
    cmd_install && cmd_launch
    ;;
  *) sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
esac
