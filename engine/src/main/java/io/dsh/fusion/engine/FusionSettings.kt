package io.dsh.fusion.engine

import android.content.Context
import android.content.SharedPreferences

/** 融合体的用户偏好（全部即时生效，不需要重启应用）。 */
class FusionSettings(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("dsh-fusion", Context.MODE_PRIVATE)

    /** 运行时后端 */
    var backend: EngineBackend
        get() = EngineBackend.from(sp.getString(K_BACKEND, null))
        set(v) = sp.edit().putString(K_BACKEND, v.id).apply()

    /** 首选端口，0 表示自动挑选 */
    var preferredPort: Int
        get() = sp.getInt(K_PORT, DEFAULT_PORT)
        set(v) = sp.edit().putInt(K_PORT, v).apply()

    /** npm registry（国内默认走 npmmirror） */
    var npmRegistry: String
        get() = sp.getString(K_REGISTRY, "https://registry.npmmirror.com")!!
        set(v) = sp.edit().putString(K_REGISTRY, v).apply()

    /** 下载镜像优选顺序 */
    var mirrors: List<String>
        get() = (sp.getString(K_MIRRORS, DEFAULT_MIRRORS) ?: DEFAULT_MIRRORS)
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString(K_MIRRORS, v.joinToString("\n")).apply()

    /** 看门狗：引擎掉线自动拉回 */
    var watchdog: Boolean
        get() = sp.getBoolean(K_WATCHDOG, true)
        set(v) = sp.edit().putBoolean(K_WATCHDOG, v).apply()

    /** 悬浮球桌宠 */
    var petEnabled: Boolean
        get() = sp.getBoolean(K_PET, false)
        set(v) = sp.edit().putBoolean(K_PET, v).apply()

    /** 流体云：AI 输出时顶部浮出胶囊 */
    var cloudCapsule: Boolean
        get() = sp.getBoolean(K_CLOUD, true)
        set(v) = sp.edit().putBoolean(K_CLOUD, v).apply()

    /** 离线语音唤醒 */
    var wakeEnabled: Boolean
        get() = sp.getBoolean(K_WAKE, false)
        set(v) = sp.edit().putBoolean(K_WAKE, v).apply()

    var wakePhrase: String
        get() = sp.getString(K_WAKE_PHRASE, "小深小深")!!
        set(v) = sp.edit().putString(K_WAKE_PHRASE, v).apply()

    /** V8 堆上限（MB）。手机内存普遍吃紧，默认 512，别用桌面那套 2G。 */
    var heapMb: Int
        get() = sp.getInt(K_HEAP, 512)
        set(v) = sp.edit().putInt(K_HEAP, v.coerceIn(128, 3072)).apply()

    /** 给 Node 传 --expose-internals：cordis 的 HMR 插件依赖它。 */
    var exposeInternals: Boolean
        get() = sp.getBoolean(K_EXPOSE, true)
        set(v) = sp.edit().putBoolean(K_EXPOSE, v).apply()

    /** 设备工具总开关 */
    var deviceToolsEnabled: Boolean
        get() = sp.getBoolean(K_DEVTOOLS, true)
        set(v) = sp.edit().putBoolean(K_DEVTOOLS, v).apply()

    /** 需要确认的危险工具（默认开启，用户可整体放行） */
    var confirmDangerous: Boolean
        get() = sp.getBoolean(K_CONFIRM, true)
        set(v) = sp.edit().putBoolean(K_CONFIRM, v).apply()

    /** 桥接端口，供 dsh 侧插件回连 */
    var bridgePort: Int
        get() = sp.getInt(K_BRIDGE_PORT, 0)
        set(v) = sp.edit().putInt(K_BRIDGE_PORT, v).apply()

    /** 桥接 token：进程内随机生成后持久化，防止同设备其它应用乱调 */
    var bridgeToken: String
        get() {
            val existing = sp.getString(K_BRIDGE_TOKEN, null)
            if (existing != null && existing.length >= 16) return existing
            val fresh = java.util.UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString(K_BRIDGE_TOKEN, fresh).apply()
            return fresh
        }
        set(v) = sp.edit().putString(K_BRIDGE_TOKEN, v).apply()

    companion object {
        const val DEFAULT_PORT = 7301
        const val DEFAULT_MIRRORS =
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/\n" +
                "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/\n" +
                "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/"

        private const val K_HEAP = "heap_mb"
        private const val K_EXPOSE = "expose_internals"
        private const val K_BACKEND = "backend"
        private const val K_PORT = "port"
        private const val K_REGISTRY = "registry"
        private const val K_MIRRORS = "mirrors"
        private const val K_WATCHDOG = "watchdog"
        private const val K_PET = "pet"
        private const val K_CLOUD = "cloud"
        private const val K_WAKE = "wake"
        private const val K_WAKE_PHRASE = "wake_phrase"
        private const val K_DEVTOOLS = "devtools"
        private const val K_CONFIRM = "confirm_dangerous"
        private const val K_BRIDGE_PORT = "bridge_port"
        private const val K_BRIDGE_TOKEN = "bridge_token"
    }
}
