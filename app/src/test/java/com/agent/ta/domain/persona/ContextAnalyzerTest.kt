package com.agent.ta.domain.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAnalyzerTest {

    @Test
    fun `创业风险消息识别为决策话题且高风险`() {
        val analysis = ContextAnalyzer.analyze("我准备辞职创业，但风险挺大，不知道该不该")

        assertEquals("career_decision", analysis.topic)
        assertTrue("风险度应较高", analysis.riskLevel >= 0.5f)
        assertEquals("advice_seeking", analysis.intent)
    }

    @Test
    fun `天气消息识别为天气话题且低风险`() {
        val analysis = ContextAnalyzer.analyze("今天天气不错")

        assertEquals("weather", analysis.topic)
        assertTrue("风险度应低", analysis.riskLevel < 0.5f)
        assertEquals("casual", analysis.intent)
    }

    @Test
    fun `犹豫消息识别为 uncertain 情绪`() {
        val analysis = ContextAnalyzer.analyze("我好纠结，要不要跳槽")
        assertEquals("uncertain", analysis.emotion)
    }

    @Test
    fun `投资话题识别 risk_uncertainty`() {
        val analysis = ContextAnalyzer.analyze("股票最近一直在跌，我要不要加仓")
        assertEquals("investment", analysis.topic)
        assertTrue(analysis.riskLevel > 0f)
    }

    @Test
    fun `无命中回退 casual`() {
        val analysis = ContextAnalyzer.analyze("嗯嗯 好的")
        assertEquals("casual", analysis.topic)
        assertEquals("neutral", analysis.emotion)
    }
}