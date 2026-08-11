package com.agent.ta.domain.persona

import com.agent.ta.data.model.AgentConfig

/**
 * Module 1: Persona Trait 数据模型（Persona DB）
 *
 * 不是新增用户编辑字段，而是从现有 `AgentIdentity.personalityCore` / `Persona.personality` /
 * `Persona.interests` / `Persona.catchphrases` **自动派生**人格特征。
 *
 * 目的：让人格表达从"静态 Prompt 文本"升级为"可被运行时分析的 trait"，从而支持：
 * - 按用户消息话题动态激活/抑制特征（跳过主题过度聚焦）
 * - 为标志性词汇设置出现频率预算（防止滚雪球）
 * - 后置守卫（PersonaGuard）检测过度表达
 */
data class PersonaTrait(
    /** 内部标识，如 "risk_seeking" */
    val name: String,
    /** 中文标签，如 "高风险偏好" */
    val label: String,
    /** 优先级 0-10，越高越容易在相关话题时被激活 */
    val priority: Int,
    /** 触发话题（用户消息命中任一即激活） */
    val activationTopics: List<String>,
    /** 触发情境 */
    val activationSituations: List<String>,
    /** 表现方式（供 Prompt 注入，指导 LLM 如何自然表达该特征） */
    val expression: List<String>,
    /** 标志性词汇（可选，如 砂金 的 赌局/筹码/下注） */
    val lexicalMarkers: List<String>,
    /** 该类标志词每轮最多出现次数，默认 1 */
    val markerMaxFrequency: Int = 1
)

/**
 * 汇总后的完整人格模型
 */
data class PersonaModel(
    /** 全部派生特征 */
    val traits: List<PersonaTrait>,
    /** 汇总所有 marker（用于 Guard 密度检测） */
    val lexicalMarkers: List<String>,
    /** 默认人格表现等级（L1），仅当话题高度相关时才提升到 L2/L3 */
    val defaultExpressionLevel: Int = 1
)

/**
 * 从 [AgentConfig] 派生 [PersonaModel]（纯函数，可测）
 *
 * 派生规则：
 * - 从 `identity.personalityCore` / `persona.personality` 提取性格标签 → 映射为 trait
 * - 从 `persona.catchphrases` / `persona.interests` 提取标志性词汇 → lexicalMarkers
 * - 内置一张「性格标签 → trait」映射表，未命中落到默认 neutral trait
 *
 * 向下兼容：未命中任何已知标签时返回空模型，调用方回退到现有行为（零侵入）。
 */
object PersonaModelBuilder {

    /**
     * 内置性格标签 → trait 映射表。
     * 每个 key 是中文性格标签（可多词），value 是 trait 定义。
     *
     * 设计要点：把"性格底色"（如 嗜赌/冒险）映射为"决策倾向"（risk_seeking），
     * 而不是字面动作（去赌场）。expression 描述的是"遇事怎么想怎么做"，用于引导 LLM。
     */
    private val TRAIT_DEFINITIONS: List<PersonaTrait> = listOf(

        PersonaTrait(
            name = "risk_seeking",
            label = "风险偏好",
            priority = 8,
            activationTopics = listOf("选择", "机会", "风险", "未知", "困境", "竞争", "投资", "创业"),
            activationSituations = listOf("做决定", "遇不确定", "失败", "高风险", "博弈"),
            expression = listOf(
                "面对选择时倾向于搏一把、愿赌服输，但这是性格底色的从容，不是要去某个具体场所",
                "把不确定性看作机会而非威胁，敢于承担风险争取更大回报",
                "说话带一点淡然和赌性，但点到为止，不渲染具体的赌博场景"
            ),
            lexicalMarkers = listOf("赌局", "筹码", "下注", "赌一把", "搏一把", "梭哈", "加注"),
            markerMaxFrequency = 1
        ),

        PersonaTrait(
            name = "risk_averse",
            label = "谨慎稳重",
            priority = 7,
            activationTopics = listOf("风险", "选择", "安全", "安稳", "计划"),
            activationSituations = listOf("做决定", "遇不确定", "风险提示"),
            expression = listOf(
                "面对不确定时倾向于稳妥、先评估再行动",
                "做事留有后路，不喜欢孤注一掷",
                "会用理性分析安抚对方，不轻易冒险"
            ),
            lexicalMarkers = listOf("稳妥", "保险", "先想清楚", "别冲动", "留一手"),
            markerMaxFrequency = 1
        ),

        PersonaTrait(
            name = "warm",
            label = "温柔体贴",
            priority = 6,
            activationTopics = listOf("心情", "难过", "累", "关心", "照顾", "身体"),
            activationSituations = listOf("安慰", "呵护", "被求助"),
            expression = listOf(
                "语气温柔，会主动关心对方的感受",
                "在对方低落时给予陪伴和暖心的回应",
                "细节上体贴，让人觉得被在意"
            ),
            lexicalMarkers = listOf("抱抱", "好好休息", "我来陪你", "别太难过了"),
            markerMaxFrequency = 1
        ),

        PersonaTrait(
            name = "playful",
            label = "幽默活泼",
            priority = 5,
            activationTopics = listOf("笑话", "玩", "游戏", "开心", "有趣"),
            activationSituations = listOf("调侃", "玩笑", "活跃气氛"),
            expression = listOf(
                "爱开玩笑，语气轻快",
                "擅长用俏皮话化解尴尬",
                "聊天氛围轻松，不端着"
            ),
            lexicalMarkers = listOf("哈哈", "开玩笑的", "逗你玩", "皮一下"),
            markerMaxFrequency = 1
        ),

        PersonaTrait(
            name = "analytical",
            label = "理性分析",
            priority = 5,
            activationTopics = listOf("怎么办", "分析", "问题", "决定", "方案"),
            activationSituations = listOf("求解", "被请教", "理性讨论"),
            expression = listOf(
                "遇事条理清晰，习惯拆解问题",
                "用逻辑和事实说话，不情绪化",
                "给出可执行的建议"
            ),
            lexicalMarkers = listOf("首先", "其实关键在于", "从长远看"),
            markerMaxFrequency = 1
        ),

        PersonaTrait(
            name = "aloof",
            label = "高冷疏离",
            priority = 4,
            activationTopics = listOf(""),
            activationSituations = listOf("被打扰", "陌生", "无感"),
            expression = listOf(
                "话不多，语气偏淡",
                "不主动找人，但对亲近的人会放松",
                "有种生人勿近的距离感"
            ),
            lexicalMarkers = listOf("嗯", "随你", "没兴趣"),
            markerMaxFrequency = 1
        ),

        // 兜底中性特征：任何未命中的性格都落到这里，保证模型非空
        PersonaTrait(
            name = "neutral",
            label = "自然随和",
            priority = 1,
            activationTopics = emptyList(),
            activationSituations = emptyList(),
            expression = listOf("自然随和地回应，不刻意突出任何单一性格标签"),
            lexicalMarkers = emptyList(),
            markerMaxFrequency = 0
        )
    )

    /** 名称 → trait 快速查找 */
    private val BY_NAME: Map<String, PersonaTrait> = TRAIT_DEFINITIONS.associateBy { it.name }

    /**
     * 判断某性格文本是否命中某个 trait 的语义。
     * 用关键词匹配（性格标签通常短，直接包含判断足够）。
     */
    private fun textHitsTrait(text: String, trait: PersonaTrait): Boolean {
        val keywords = when (trait.name) {
            "risk_seeking" -> listOf("赌", "冒险", "搏", "风险偏好", "下注", "敢搏", "赌性", "嗜赌")
            "risk_averse" -> listOf("谨慎", "保守", "稳妥", "稳重", "小心", "求稳")
            "warm" -> listOf("温柔", "体贴", "关怀", "照顾", "暖心", "善良", "细腻")
            "playful" -> listOf("幽默", "爱开玩笑", "活泼", "皮", "逗", "有趣", "风趣")
            "analytical" -> listOf("理性", "冷静", "分析", "逻辑", "条理", "聪明", "思维")
            "aloof" -> listOf("高冷", "冷漠", "疏离", "冷淡", "话少", "不爱说话", "生人勿近", "独立")
            else -> emptyList()
        }
        return keywords.any { text.contains(it) }
    }

    /**
     * 从 [AgentConfig] 派生 [PersonaModel]。
     * 纯函数，无副作用，可单元测试。
     */
    fun build(config: AgentConfig): PersonaModel {
        val identity = config.identity
        val persona = config.agent.persona

        // 收集所有"性格描述文本"来源
        val personalityTexts = mutableListOf<String>()
        if (identity.personalityCore.isNotBlank()) personalityTexts.add(identity.personalityCore)
        persona.personality.forEach { if (it.isNotBlank()) personalityTexts.add(it) }

        // 命中各 trait
        val matched = mutableListOf<PersonaTrait>()
        for (trait in TRAIT_DEFINITIONS) {
            if (trait.name == "neutral") continue  // neutral 仅兜底
            val hit = personalityTexts.any { textHitsTrait(it, trait) }
            if (hit) matched.add(trait)
        }

        // 收集标志性词汇：来自命中 trait 的 marker + 口头禅 + 兴趣（兴趣里明显是"主题词"的）
        val markers = mutableListOf<String>()
        matched.forEach { markers.addAll(it.lexicalMarkers) }
        persona.catchphrases.forEach { if (it.isNotBlank()) markers.add(it) }

        // 若一个 trait 都没命中，回退到 neutral（保证模型非空，但不算"聚焦"特征）
        val finalTraits = if (matched.isEmpty()) listOf(BY_NAME.getValue("neutral")) else matched

        return PersonaModel(
            traits = finalTraits,
            lexicalMarkers = markers.distinct().filter { it.isNotBlank() },
            defaultExpressionLevel = 1
        )
    }
}