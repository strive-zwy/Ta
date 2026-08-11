package com.agent.ta.domain.persona

import com.agent.ta.data.remote.dto.ReplyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaGuardTest {

    private val model = PersonaModel(
        traits = listOf(
            PersonaTrait(
                name = "risk_seeking",
                label = "风险偏好",
                priority = 8,
                activationTopics = listOf("投资"),
                activationSituations = listOf("做决定"),
                expression = listOf("从容"),
                lexicalMarkers = listOf("赌局", "筹码"),
                markerMaxFrequency = 1
            )
        ),
        lexicalMarkers = listOf("赌局", "筹码"),
        defaultExpressionLevel = 1
    )

    @Test
    fun `单条回复 marker 超频 FLAG`() {
        val items = listOf(ReplyItem(replyText = "这这是一次赌局，我押上全部筹码，输了就当赌局了"))
        val result = PersonaGuard.check(model, items)

        assertTrue("赌局出现多次应 FLAG", result.isFlagged)
    }

    @Test
    fun `合理语境不 FLAG`() {
        val items = listOf(ReplyItem(replyText = "人生本来就是一场赌局，从容点就好"))
        val result = PersonaGuard.check(model, items)

        assertFalse("合理语境不应 FLAG", result.isFlagged)
    }

    @Test
    fun `跨轮重复 FLAG`() {
        val items = listOf(ReplyItem(replyText = "说到这个就是一场赌局"))
        val recentFreq = mapOf("赌局" to 3)  // 最近 3 轮频繁出现
        val result = PersonaGuard.check(model, items, recentFreq)

        assertTrue("跨轮重复应 FLAG", result.isFlagged)
    }

    @Test
    fun `无 marker 时不 FLAG`() {
        val emptyModel = PersonaModel(traits = emptyList(), lexicalMarkers = emptyList())
        val items = listOf(ReplyItem(replyText = "随便聊聊"))
        val result = PersonaGuard.check(emptyModel, items)

        assertFalse(result.isFlagged)
    }
}