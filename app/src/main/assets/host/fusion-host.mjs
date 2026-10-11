/**
 * DSH Fusion 的宿主入口。
 *
 * ── 为什么不能直接用官方的 `dsh web` ────────────────────────────────
 * 官方 CLI（lib/bin.js 的 "profile" 分支）调用 runProfile 时是这样的：
 *
 *     await runProfile({ environment, profile, patchFiles, args })
 *
 * 注意它**没有传 packageManager**。于是插件安装器会退回到
 * `{ command: 'pnpm' }`，去 PATH 里找一个叫 pnpm 的可执行文件。
 * 而 Android 上这条路是死的，有两重原因：
 *   1) 我们的运行时里没有 pnpm（设备上更没有）；
 *   2) 就算装了，Android 10+ 也禁止 execve 应用数据目录里的文件（W^X），
 *      而 pnpm 的入口是个 `#!/usr/bin/env node` 的脚本 —— /usr/bin/env 在 Android 上不存在。
 *
 * ── 官方其实留了口子 ────────────────────────────────────────────────
 * ProfileContext.packageManager 的类型注释原话：
 *     "Packaged applications supply their bundled runtime instead of a PATH executable."
 * 桌面版就是这么用的（apps/desktop/src/host-process.ts 把 pnpm 路径与 node 路径
 * 作为位置参数传给自己的 dsh-desktop-host）。
 *
 * ── 这个文件做的事 ──────────────────────────────────────────────────
 * 和官方 bin.js 的 profile 分支**完全相同**，只多传一个 packageManager。
 * 它只 import Node 内置模块（fs / path / url），其余全走绝对路径动态导入，
 * 所以放在任何位置都能跑，不依赖 node_modules 解析。
 */

import { readdirSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const DSH_DIR = process.env.DSH_FUSION_DSH_DIR
const NODE_BIN = process.env.DSH_FUSION_NODE_BIN
const PNPM_ENTRY = process.env.DSH_FUSION_PNPM_ENTRY
const PROFILE = process.env.DSH_FUSION_PROFILE || 'web'
const APP_ARGS = process.env.DSH_FUSION_APP_ARGS
  ? process.env.DSH_FUSION_APP_ARGS.split('\u0000').filter(part => part.length > 0)
  : []

/**
 * 找到导出了 runProfile 的那个 chunk。
 *
 * 官方 lib 下的文件名带内容哈希（profile-boot-BnJoK_kl.js 之类），每次发布都会变，
 * 所以只能扫不能写死。只扫 profile-boot-* 前缀，避免引入无关 chunk 的顶层副作用。
 *
 * @param libDir - dsh 包的 lib 目录绝对路径。
 * @returns runProfile 函数，或 null。
 */
async function resolveRunProfile(libDir) {
  let candidates
  try {
    candidates = readdirSync(libDir).filter(name => name.startsWith('profile-boot-') && name.endsWith('.js'))
  } catch (error) {
    console.error(`[fusion-host] 读不到 ${libDir}：${error?.message ?? error}`)
    return null
  }
  for (const name of candidates) {
    try {
      const mod = await import(pathToFileURL(join(libDir, name)).href)
      if (typeof mod.runProfile === 'function') return mod.runProfile
    } catch {
      // 这个 chunk 不是我们要的（或它依赖别的上下文），试下一个
    }
  }
  return null
}

if (!DSH_DIR) {
  console.error('[fusion-host] 缺少 DSH_FUSION_DSH_DIR，无法定位 dsh 包')
  process.exit(86)
}

const runProfile = await resolveRunProfile(join(DSH_DIR, 'lib'))
if (runProfile === null) {
  console.error(`[fusion-host] 在 ${join(DSH_DIR, 'lib')} 里找不到导出 runProfile 的 chunk`)
  process.exit(86)
}

// 自带运行时：command 是我们包内的 node，args 是 pnpm 的入口脚本。
// 这与桌面版传 { pnpm, nodeBin } 的做法同源。
const packageManager = (NODE_BIN && PNPM_ENTRY)
  ? { command: NODE_BIN, args: [PNPM_ENTRY], env: {} }
  : undefined

if (packageManager !== undefined) {
  console.log(`[fusion-host] 插件安装在带内运行时执行：${NODE_BIN} ${PNPM_ENTRY}`)
} else {
  console.warn('[fusion-host] 未提供 pnpm，官方插件安装页会去找 PATH 里的 pnpm（Android 上大概率失败）')
}

// environment 直接用 process.env：RuntimeEnv 已经把它精心构造好了
// （LD_LIBRARY_PATH / NODE_EXTRA_CA_CERTS / DSH_HOME / 代理……），
// 再包一层官方的 loadLayeredEnv 只会多一个 node_modules 解析依赖。
await runProfile({
  environment: process.env,
  profile: PROFILE,
  patchFiles: [],
  args: APP_ARGS,
  ...(packageManager === undefined ? {} : { packageManager }),
})
