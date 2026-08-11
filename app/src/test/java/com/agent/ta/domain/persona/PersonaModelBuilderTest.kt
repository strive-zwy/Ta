package com.agent.ta.domain.persona

import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.AgentIdentity
import com.agent.ta.data.model.AgentInfo
import com.agent.ta.data.model.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaModelBuilderTest {

    /** 模拟砂金：personalityCore 含"嗜赌/赌性/冒险" → 应派生 risk_seeking trait + 标志词 */
    private fun shajinConfig(): AgentConfig {
        return AgentConfig(
            identity = AgentIdentity(
                personalityCore = "带点赌性的男人，敢冒险，嗜赌，高风险偏好，从容冷静"
            ),
            agent = AgentInfo(
                name = "砂金",
                persona = Persona(
                    catchphrases = listOf("下注吧", "赌一把"),
                    interests = listOf("博弈", "投资", "商业")
                )
            )
        )
    }

    /** 普通角色：无任何风险/赌博关键词 → 应回退到 neutral */
    private fun normalConfig(): AgentConfig {
        return AgentConfig(
            identity = AgentIdentity(
                personalityCore = "温柔体贴，喜欢照顾人"
            ),
            agent = AgentInfo(
                name = "小雅",
                persona = Persona(
                    catchphrases = listOf("好的呀")
                )
            )
        )
    }

    @Test
    fun `砂金 config 派生含 risk_seeking trait`() {
        val model = PersonaModelBuilder.build(shajinConfig())

        val riskSeeking = model.traits.firstOrNull { it.name == "risk_seeking" }
        assertTrue("应包含 risk_seeking trait", riskSeeking != null)
        assertEquals("风险偏好", riskSeeking?.label)
    }

    @Test
    fun `砂金 config 派生含赌局类标志词`() {
        val model = PersonaModelBuilder.build(shajinConfig())

        assertTrue("应包含 赌局", model.lexicalMarkers.contains("赌局"))
        assertTrue("应包含 筹码", model.lexicalMarkers.contains("筹码"))
        assertTrue("应包含 下注", model.lexicalMarkers.contains("下注"))
        assertTrue("口头禅应进入 markers", model.lexicalMarkers.contains("下注吧"))
    }

    @Test
    fun `普通 config 派生 warm 且无聚焦 marker`() {
        val model = PersonaModelBuilder.build(normalConfig())

        // 温柔类 config 应派生 warm trait，且不应有"赌局"类聚焦标志词
        assertTrue("应包含 warm trait", model.traits.any { it.name == "warm" })
        assertTrue("不应有赌局类 marker", !model.lexicalMarkers.contains("赌局"))
    }

    @Test
    fun `空 config 不崩溃且回退 neutral`() {
        val model = PersonaModelBuilder.build(AgentConfig())
        assertTrue(model.traits.isNotEmpty())
        assertTrue(model.traits.any { it.name == "neutral" })
    }
}