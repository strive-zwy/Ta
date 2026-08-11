package com.agent.ta.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigQuickReplyPolicyTest {
    @Test
    fun `config entry message exposes three choices`() {
        val options = ConfigQuickReplyPolicy.resolve(ConfigQuickReplyPolicy.ENTRY_MESSAGE)

        assertEquals(
            listOf("对话式沟通自定义", "偶像参考（偶像克隆）", "动画或动漫人物参考"),
            options.map { it.label }
        )
    }

    @Test
    fun `other messages expose no choices`() {
        assertTrue(ConfigQuickReplyPolicy.resolve("普通消息").isEmpty())
    }

    @Test
    fun `manual aliases map to matching mode`() {
        assertEquals(ConfigQuickReplyAction.CUSTOM, ConfigQuickReplyPolicy.matchAction("我想自己描述"))
        assertEquals(ConfigQuickReplyAction.CELEBRITY, ConfigQuickReplyPolicy.matchAction("参考周深创建"))
        assertEquals(ConfigQuickReplyAction.FICTIONAL, ConfigQuickReplyPolicy.matchAction("参考动漫角色创建"))
    }
}
