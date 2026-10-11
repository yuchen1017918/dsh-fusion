package io.dsh.fusion.engine.llm

/**
 * 可以在手机上跑的模型清单。
 *
 * ── 为什么主力是 DeepSeek 的蒸馏模型 ────────────────────────────
 * 这是 DeepSeek Harness，推荐自家的开源模型最合逻辑。**DeepSeek-R1-Distill** 系列
 * 是把 R1 的推理能力蒸馏进小模型（Qwen / Llama 骨干），是当前手机尺寸下
 * 能力最强的开源选择之一。三档设备各配一个：
 *
 *   入门 8 GB  → 1.5B    主流 12 GB → 7B    高配 16 GB → 14B
 *
 * ── 但有个必须先说清的事 ────────────────────────────────────────
 * **R1-Distill 是推理模型**：它会先输出一大段思考链，再给答案。
 * 在手机上（约 3–15 token/s）这意味着**问题问完要等几十秒到几分钟**才开始出正答。
 * 它不是「不好用」，而是体验预期与云端对话完全不同 —— 用之前要有心理准备。
 *
 * ── 手机端真正的瓶颈是内存 ──────────────────────────────────────
 * 不是算力。加载一个 Q4 模型要占「文件大小 + 一个上下文窗口的 KV cache」，
 * 而 Android 给单个应用的上限通常只有整机内存的一小部分。选大了的表现是
 * **被系统直接杀掉**，用户只会看到「闪退」。所以 [minRamGb] 是硬门槛，
 * 而且应用会按实际内存推荐而不是让人自己瞎选。
 */
object LocalModelCatalog {

    /** 三档设备配置。用来给用户一个「我这台机器该选什么」的直观答案。 */
    enum class Tier(val label: String, val ramGb: Int, val hint: String) {
        ENTRY("入门", 8, "8 GB 内存"),
        MAINSTREAM("主流", 12, "12 GB 内存"),
        HIGH("高配", 16, "16 GB 内存"),
    }

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
        /** 对应的设备档位；验证用小模型为 null。 */
        val tier: Tier?,
        val note: String,
        /** 是否为推理模型（会输出思考链）。 */
        val reasoning: Boolean = false,
    ) {
        /**
         * 用**十进制 GB**（÷1e9）而不是 GiB。
         *
         * 这不是吹毛求疵：ModelScope 的文件列表、系统下载对话框、手机存储设置
         * 用的都是十进制。用 GiB 显示出来会比用户预期的数字小 7%，
         * 而「为什么下载完了显示的大小和说好的不一样」是个会被问的问题。
         */
        val prettySize: String get() = "%.2f GB".format(bytes / 1e9)
    }

    private const val MODELSCOPE = "https://modelscope.cn/models/"
    private const val HF = "https://hf-mirror.com/"

    /** 拼一个 unsloth 的 GGUF 文件地址（两个源用同一套路径结构）。 */
    private fun ms(repo: String, file: String) = "$MODELSCOPE$repo/resolve/master/$file"
    private fun hf(repo: String, file: String) = "$HF$repo/resolve/main/$file"

    // 大小全部是实测的（拉仓库文件列表拿到的精确字节），不是估的
    private val R1_1_5B = "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF"
    private val R1_7B = "unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF"
    private val R1_14B = "unsloth/DeepSeek-R1-Distill-Qwen-14B-GGUF"

    val MODELS: List<LocalModel> = listOf(
        LocalModel(
            id = "deepseek-r1-1.5b",
            title = "DeepSeek-R1-Distill-Qwen 1.5B",
            params = "1.5B",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            modelScope = ms(R1_1_5B, "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
            hfMirror = hf(R1_1_5B, "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
            bytes = 1_117_321_312,          // 实测：ModelScope 文件列表的精确 Size
            minRamGb = 6.0,
            tier = Tier.ENTRY,
            reasoning = true,
            note = "入门档。手机上下载最快的 DeepSeek 模型，先验证整条链路通不通",
        ),
        LocalModel(
            id = "deepseek-r1-7b",
            title = "DeepSeek-R1-Distill-Qwen 7B",
            params = "7B",
            fileName = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            modelScope = ms(R1_7B, "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"),
            hfMirror = hf(R1_7B, "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"),
            bytes = 4_683_073_248,          // 实测：ModelScope 文件列表的精确 Size
            minRamGb = 10.0,
            tier = Tier.MAINSTREAM,
            reasoning = true,
            note = "主流档。能力明显强于 1.5B，速度仍是「能用」的范围",
        ),
        LocalModel(
            id = "deepseek-r1-14b",
            title = "DeepSeek-R1-Distill-Qwen 14B",
            params = "14B",
            fileName = "DeepSeek-R1-Distill-Qwen-14B-Q4_K_M.gguf",
            modelScope = ms(R1_14B, "DeepSeek-R1-Distill-Qwen-14B-Q4_K_M.gguf"),
            hfMirror = hf(R1_14B, "DeepSeek-R1-Distill-Qwen-14B-Q4_K_M.gguf"),
            bytes = 8_988_109_984,          // 实测：ModelScope 文件列表的精确 Size
            minRamGb = 16.0,
            tier = Tier.HIGH,
            reasoning = true,
            note = "高配档，只建议 16 GB 机型。内存吃紧时先换 Q3_K_M（7.34 GB）",
        ),
        LocalModel(
            id = "qwen2.5-0.5b",
            title = "Qwen2.5 0.5B（验证用）",
            params = "0.5B",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            modelScope = MODELSCOPE + "Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            hfMirror = HF + "Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            bytes = 491_400_032,            // 实测：ModelScope 文件列表的精确 Size
            minRamGb = 3.0,
            tier = null,
            note = "非推理模型、秒回。**只用来确认服务能跑通**，别指望它干活",
        ),
    )

    fun byId(id: String): LocalModel? = MODELS.firstOrNull { it.id == id }

    fun forTier(tier: Tier): LocalModel? = MODELS.firstOrNull { it.tier == tier }

    /**
     * 按整机内存推荐一档。
     *
     * 取 `minRamGb` 的 1.25 倍作为门槛：模型之外，系统、引擎、Node、
     * KV cache 都还要各占一份。只按「刚好够」推会有很大概率被 OOM 杀掉，
     * 而那时的现象是闪退 —— 用户完全不知道是自己选大了。
     */
    fun recommend(ramGb: Double): LocalModel? =
        MODELS.filter { it.tier != null && ramGb >= it.minRamGb * 1.25 }.maxByOrNull { it.bytes }
            ?: MODELS.firstOrNull { ramGb >= it.minRamGb }
}
