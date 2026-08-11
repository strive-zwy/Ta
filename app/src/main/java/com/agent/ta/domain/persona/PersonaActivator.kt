package com.agent.ta.domain.persona

/**
 * Module 3: Persona Activator（纯函数）
 *
 * 根据 [ContextAnalysis] 决定本轮激活/抑制哪些人格特征，以及标志性词汇的表达预算。
 *
 * 核心目标：解决"主题过度聚焦"。
 * - 用户话题与某 trait 相关 → 激活（提升表达等级）
 * - 用户话题与某 trait（尤其含大量 marker 的）无关 → 抑制（压低 marker 预算）
 * - 最近几轮某 marker 已频繁出现 → 进一步压低预算（防滚雪球）
 */
data class ActivationResult(
    /** 本轮激活的特征 */
    val activatedTraits: List<PersonaTrait>,
    /** 本轮抑制的特征 */
    val suppressedTraits: List<PersonaTrait>,
    /** marker 词 → 预算倍率（0.0 表示完全禁止出现，1.0 表示正常） */
    val markerBudgetMultipliers: Map<String, Float>,
    /** 本轮人格表现等级 L0-L3（默认 L1，仅当话题高度相关才到 L3） */
    val expressionLevel: Int
)

object PersonaActivator {

    /** 表达等级上限 */
    const val MAX_EXPRESSION_LEVEL = 3

    /**
     * 计算激活结果。
     *
     * @param model 人格模型（由 [PersonaModelBuilder] 派生，可缓存）
     * @param analysis 上下文分析结果
     * @param recentMarkerFreq 最近几轮各 marker 出现次数（用于防滚雪球）
     */
    fun activate(
        model: PersonaModel,
        analysis: ContextAnalysis,
        recentMarkerFreq: Map<String, Int> = emptyMap()
    ): ActivationResult {
        val activated = mutableListOf<PersonaTrait>()
        val suppressed = mutableListOf<PersonaTrait>()

        // 找出本轮话题命中的 trait
        for (trait in model.traits) {
            val topicHit = trait.activationTopics.any { analysis.topic.contains(it) || it == analysis.topic }
            val situationHit = trait.activationSituations.isEmpty() // 空情境视为总是适用（如 neutral）
            val riskHit = trait.name == "risk_seeking" && analysis.riskLevel >= 0.5f ||
                trait.name == "risk_averse" && analysis.riskLevel >= 0.5f

            if (topicHit || situationHit || riskHit) {
                // 尤其：话题直接命中 trait 的 activationTopics → 激活
                if (topicHit || riskHit) {
                    activated.add(trait)
                } else {
                    // 仅情境命中（neutral 等）保持激活但不强调
                    activated.add(trait)
                }
            } else {
                // 有 markers 且与话题无关的 trait → 抑制（防止无关话题也带出标志词）
                if (trait.lexicalMarkers.isNotEmpty()) {
                    suppressed.add(trait)
                }
            }
        }

        // 表达等级：风险话题/决策话题高度相关 → 提升；否则默认 L1
        val expressionLevel = when {
            analysis.topic in setOf("risk_uncertainty", "career_decision", "investment")
                && analysis.riskLevel >= 0.5 -> 2.coerceAtMost(MAX_EXPRESSION_LEVEL)
            analysis.riskLevel >= 0.7 -> 3
            else -> model.defaultExpressionLevel.coerceIn(1, MAX_EXPRESSION_LEVEL)
        }

        // 计算 marker 预算倍率
        val budgetMultipliers = computeMarkerBudgets(model, analysis, recentMarkerFreq)

        return ActivationResult(
            activatedTraits = activated.distinctBy { it.name },
            suppressedTraits = suppressed.distinctBy { it.name },
            markerBudgetMultipliers = budgetMultipliers,
            expressionLevel = expressionLevel
        )
    }

    /**
     * 计算所有 marker 词汇的预算倍率。
     *
     * 规则：
     * - 激活 trait 的 marker：风险/决策话题时给正常预算（1.0），普通话题给偏低（0.5）
     * - 抑制 trait 的 marker：预算 0.0（完全禁止）
     * - 最近轮次已频繁出现的 marker：再压低（0.3 或更低）
     */
    private fun computeMarkerBudgets(
        model: PersonaModel,
        analysis: ContextAnalysis,
        recentMarkerFreq: Map<String, Int>
    ): Map<String, Float> {
        val result = mutableMapOf<String, Float>()
        val activatedNames = model.traits
            .filter { t -> analysis.topic in t.activationTopics || isRiskTrait(t, analysis) }
            .map { it.name }
            .toSet()

        for (trait in model.traits) {
            val isActivated = trait.name in activatedNames
            for (marker in trait.lexicalMarkers) {
                var budget = when {
                    !isActivated -> 0.0f  // 抑制：禁止出现
                    analysis.topic in setOf("risk_uncertainty", "career_decision", "investment") -> 1.0f
                    else -> 0.4f  // 普通话题：压低
                }

                // 最近轮次滚雪球惩罚
                val recentCount = recentMarkerFreq[marker] ?: 0
                if (recentCount >= 2) {
                    budget = 0.0f
                } else if (recentCount == 1) {
                    budget *= 0.3f
                }

                result[marker] = budget
            }
        }
        return result
    }

    private fun isRiskTrait(trait: PersonaTrait, analysis: ContextAnalysis): Boolean {
        return analysis.riskLevel >= 0.5f &&
            (trait.name == "risk_seeking" || trait.name == "risk_averse")
    }
}