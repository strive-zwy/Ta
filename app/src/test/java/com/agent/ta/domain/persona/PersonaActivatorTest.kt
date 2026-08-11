package com.agent.ta.domain.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaActivatorTest {

    private val riskTrait = PersonaTrait(
        name = "risk_seeking",
        label = "风险偏好",
        priority = 8,
        activationTopics = listOf("选择", "风险", "投资", "创业"),
        activationSituations = listOf("做决定"),
        expression = listOf("从容"),
        lexicalMarkers = listOf("赌局", "筹码", "下注"),
        markerMaxFrequency = 1
    )

    private val warmTrait = PersonaTrait(
        name = "warm",
        label = "温柔体贴",
        priority = 6,
        activationTopics = listOf("心情", "难过"),
        activationSituations = listOf("安慰"),
        expression = listOf("温柔"),
        lexicalMarkers = listOf("抱抱"),
        markerMaxFrequency = 1
    )

    private val model = PersonaModel(
        traits = listOf(riskTrait, warmTrait),
        lexicalMarkers = listOf("赌局", "筹码", "下注", "抱抱"),
        defaultExpressionLevel = 1
    )

    @Test
    fun `风险话题激活 risk_seeking 并提升 marker 预算`() {
        val analysis = ContextAnalysis(
            topic = "investment",
            emotion = "uncertain",
            intent = "advice_seeking",
            riskLevel = 0.8f
        )
        val result = PersonaActivator.activate(model, analysis)

        assertTrue("应激活 risk_seeking", result.activatedTraits.any { it.name == "risk_seeking" })
        // 风险话题下 marker 预算应为正常（1.0）
        assertEquals(1.0f, result.markerBudgetMultipliers["赌局"] ?: -1f, 0.01f)
    }

    @Test
    fun `闲聊话题抑制含 marker 的无关特征`() {
        val analysis = ContextAnalysis(
            topic = "weather",
            emotion = "neutral",
            intent = "casual",
            riskLevel = 0f
        )
        val result = PersonaActivator.activate(model, analysis)

        // 天气话题与 risk_seeking/warm 都无关 → 两者都应被抑制（marker 预算为 0）
        assertTrue("应包含被抑制的 trait", result.suppressedTraits.isNotEmpty())
        assertEquals(0.0f, result.markerBudgetMultipliers["赌局"] ?: -1f, 0.01f)
        assertEquals(0.0f, result.markerBudgetMultipliers["抱抱"] ?: -1f, 0.01f)
    }

    @Test
    fun `最近 marker 频繁出现时预算压低`() {
        val analysis = ContextAnalysis(
            topic = "investment",
            emotion = "neutral",
            intent = "casual",
            riskLevel = 0.6f
        )
        val recentFreq = mapOf("赌局" to 2)  // 已出现 2 次 → 应禁止
        val result = PersonaActivator.activate(model, analysis, recentFreq)

        assertEquals("滚雪球后应禁止", 0.0f, result.markerBudgetMultipliers["赌局"] ?: -1f, 0.01f)
    }
}