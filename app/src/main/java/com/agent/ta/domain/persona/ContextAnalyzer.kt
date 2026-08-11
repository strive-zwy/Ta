package com.agent.ta.domain.persona

/**
 * Module 2: Context Analyzer（纯函数，内置中文词典）
 *
 * 轻量级文本分析，不调 LLM，可单元测试。
 * 分析用户消息的话题 / 情绪 / 意图 / 风险度，供 [PersonaActivator] 决策激活哪些特征。
 */
data class ContextAnalysis(
    /** 话题，如 career_decision / risk_uncertainty / food / weather / casual */
    val topic: String,
    /** 情绪维度：certain / uncertain / happy / sad / neutral */
    val emotion: String,
    /** 意图：advice_seeking / question / complaint / casual */
    val intent: String,
    /** 风险度 0-1 */
    val riskLevel: Float
)

object ContextAnalyzer {

    /** 话题 → 关键词表（命中得分取最高者确定 topic） */
    private val TOPIC_KEYWORDS: Map<String, List<String>> = mapOf(
        "career_decision" to listOf("辞职", "跳槽", "工作", "公司", "创业", "换工作", "offer", "面试", "升职"),
        "risk_uncertainty" to listOf("会不会", "怎么办", "风险", "不确定", "犹豫", "选择", "要不要", "该不该", "纠结", "赌", "冒险", "搏一把"),
        "investment" to listOf("股票", "买股", "投资", "基金", "理财", "涨", "跌", "亏", "赚", "行情", "大盘"),
        "food" to listOf("吃", "饭", "饿", "外卖", "菜", "早餐", "午餐", "晚餐", "夜宵", "火锅", "奶茶"),
        "weather" to listOf("天气", "下雨", "太阳", "冷", "热", "降温", "台风", "下雪"),
        "health" to listOf("生病", "难受", "头疼", "发烧", "感冒", "不舒服", "睡不好", "失眠", "胃"),
        "mood" to listOf("难过", "烦", "心情", "郁闷", "压力", "焦虑", "想哭", "开心", "累死")
    )

    /** 风险话题关键词（用于 riskLevel 计算） */
    private val RISK_KEYWORDS = listOf("风险", "赌", "冒险", "搏一把", "不确定", "选择", "要不要", "该不该", "值不值得", "押", "下注", "梭哈", "投资", "亏")

    /** 犹豫/不确定关键词（用于 emotion=uncertain） */
    private val UNCERTAIN_KEYWORDS = listOf("犹豫", "不知道", "要不要", "怎么办", "纠结", "该不该", "拿不准", "想不清")

    /** 负面情绪关键词（用于 emotion=sad） */
    private val SAD_KEYWORDS = listOf("难过", "难受", "烦", "烦死", "郁闷", "焦虑", "想哭", "压力大", "累死", "倒霉", "糟糕", "气死")

    /** 正面情绪关键词（用于 emotion=happy） */
    private val HAPPY_KEYWORDS = listOf("开心", "高兴", "太好了", "哈哈", "棒", "厉害", "爽", "惊喜", "幸福", "嘿嘿")

    /** 求助/建议关键词（用于 intent=advice_seeking） */
    private val ADVICE_KEYWORDS = listOf("怎么办", "要不要", "该不该", "给个建议", "你觉得", "好不好", "值不值", "帮我看看", "出出主意")

    /** 抱怨关键词（用于 intent=complaint） */
    private val COMPLAINT_KEYWORDS = listOf("烦", "烦死", "气死", "讨厌", "受不了", "服了", "无语", "太坑", "凭什么")

    /**
     * 分析一条用户消息。
     * 纯函数，无副作用。
     */
    fun analyze(text: String): ContextAnalysis {
        val normalized = text.trim()

        // 1. 话题：按关键词命中次数统计，取最高得分；无命中 → casual
        val topic = detectTopic(normalized)

        // 2. 风险度：RISK_KEYWORDS 命中比例映射到 0-1
        val riskLevel = computeRiskLevel(normalized)

        // 3. 情绪
        val emotion = detectEmotion(normalized)

        // 4. 意图
        val intent = detectIntent(normalized)

        return ContextAnalysis(
            topic = topic,
            emotion = emotion,
            intent = intent,
            riskLevel = riskLevel
        )
    }

    private fun detectTopic(text: String): String {
        var bestTopic = "casual"
        var bestScore = 0
        for ((topic, keywords) in TOPIC_KEYWORDS) {
            val score = keywords.count { text.contains(it) }
            if (score > bestScore) {
                bestScore = score
                bestTopic = topic
            }
        }
        return bestTopic
    }

    private fun computeRiskLevel(text: String): Float {
        // 命中关键词数量 / 总关键词数，映射到 0-1（命中越多越表示是高风险决策场景）
        val hits = RISK_KEYWORDS.count { text.contains(it) }
        return (hits * 0.25f).coerceIn(0f, 1f)
    }

    private fun detectEmotion(text: String): String {
        val uncertainHits = UNCERTAIN_KEYWORDS.count { text.contains(it) }
        if (uncertainHits > 0) {
            // 有犹豫词时，若同时有负面词则归为 uncertain（更贴近"拿不定主意"）
            return "uncertain"
        }
        val sadHits = SAD_KEYWORDS.count { text.contains(it) }
        if (sadHits > 0) return "sad"
        val happyHits = HAPPY_KEYWORDS.count { text.contains(it) }
        if (happyHits > 0) return "happy"
        return "neutral"
    }

    private fun detectIntent(text: String): String {
        val adviceHits = ADVICE_KEYWORDS.count { text.contains(it) }
        if (adviceHits > 0) return "advice_seeking"
        // 含问号且较短 → question
        if (text.contains("？") || text.contains("?")) {
            if (text.length <= 20) return "question"
        }
        val complaintHits = COMPLAINT_KEYWORDS.count { text.contains(it) }
        if (complaintHits > 0) return "complaint"
        return "casual"
    }
}