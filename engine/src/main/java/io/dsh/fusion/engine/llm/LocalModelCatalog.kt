package io.dsh.fusion.engine.llm

/**
 * 可以在手机上跑的模型清单。
 *
 * ── 为什么选这几个 ──────────────────────────────────────────────
 * 手机端跑大模型，瓶颈是**内存**而不是算力：一个 Q4_K_M 量化的模型，加载时
 * 大致要占「文件大小 + 一个上下文窗口的 KV cache」，而 Android 给单个应用的
 * 上限通常只有整机内存的一小部分。所以清单按参数量分档，并明确标出内存要求 ——
 * 选大了的表现是系统把应用杀掉，用户只会看到「闪退」。
 *
 * 全是 GGUF 格式（llama.cpp 的原生格式）。
 *
 * ── 下载源 ──────────────────────────────────────────────────────
 * 主源 ModelScope（国内直连快），备用 hf-mirror。两个都实测过可用且支持
 * Range 断点续传（HTTP 206）—— 手机下载 GB 级文件一定会断，能续传是刚需。
 */
object LocalModelCatalog {

    data class LocalModel(
        val id: String,
        val title: String,
        val params: String,
        val fileName: String,
        val modelScope: String,
        val hfMirror: String,
        val bytes: Long,
        /** 建议的最低整机内存（GB）。低于这个值很可能被系统杀掉。 */
        val minRamGb: Double,
        val note: String,
    ) {
        val prettySize: String get() = "%.2f GB".format(bytes / 1073741824.0)
    }

    private const val MODELSCOPE = "https://modelscope.cn/models/"
    private const val HF = "https://hf-mirror.com/"

    val MODELS: List<LocalModel> = listOf(
        LocalModel(
            id = "qwen2.5-0.5b",
            title = "Qwen2.5 0.5B",
            params = "0.5B",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            modelScope = MODELSCOPE + "Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            hfMirror = HF + "Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            bytes = 397_000_000,
            minRamGb = 3.0,
            note = "最省资源，能跑但能力有限 —— 适合先验证整条链路通不通",
        ),
        LocalModel(
            id = "qwen2.5-1.5b",
            title = "Qwen2.5 1.5B",
            params = "1.5B",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            modelScope = MODELSCOPE + "Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            hfMirror = HF + "Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            bytes = 1_120_000_000,
            minRamGb = 6.0,
            note = "手机上的主力档：能理解指令、能调工具，速度也还能接受",
        ),
        LocalModel(
            id = "qwen2.5-3b",
            title = "Qwen2.5 3B",
            params = "3B",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            modelScope = MODELSCOPE + "Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf",
            hfMirror = HF + "Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            bytes = 1_930_000_000,
            minRamGb = 8.0,
            note = "接近可用的质量，但 8 GB 以下机型不要试 —— 会被系统直接杀掉",
        ),
    )

    fun byId(id: String): LocalModel? = MODELS.firstOrNull { it.id == id }

    /**
     * 按整机内存推荐一档。
     *
     * 取 minRamGb 的 1.5 倍作为门槛：模型要的内存之外，系统、引擎、Node
     * 自己还要占一份，只有「内存明显够」才推得动。
     */
    fun recommend(ramGb: Double): LocalModel? =
        MODELS.filter { ramGb >= it.minRamGb * 1.5 }.maxByOrNull { it.bytes }
            ?: MODELS.firstOrNull { ramGb >= it.minRamGb }
}
