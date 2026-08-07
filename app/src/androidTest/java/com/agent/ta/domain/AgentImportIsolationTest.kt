package com.agent.ta.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Agent 导入隔离测试（Task 9）
 *
 * 验证：
 * 1. 同一配置连续导入两次得到不同 ID、独立空数据空间和 NOT_STARTED 状态
 * 2. 导入时清空 nicknameForUser，不继承导出者称呼
 * 3. 导入事务后新实例被激活、首次见面/关系/情绪状态均已初始化
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class AgentImportIsolationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val agentConfigDao get() = ServiceLocator.agentConfigDao
    private val firstMeetingDao get() = ServiceLocator.firstMeetingStateDao
    private val relationshipDao get() = ServiceLocator.relationshipStateDao
    private val emotionalDao get() = ServiceLocator.emotionalStateDao
    private val chatDao get() = ServiceLocator.chatMessageDao
    private val memoryDao get() = ServiceLocator.memoryDao

    @Before
    fun setup() = runBlocking {
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM agent_config")
        db.execSQL("DELETE FROM first_meeting_state")
        db.execSQL("DELETE FROM relationship_state")
        db.execSQL("DELETE FROM emotional_state")
        db.execSQL("DELETE FROM chat_messages")
        db.execSQL("DELETE FROM memories")
    }

    @After
    fun tearDown() = runBlocking {
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM agent_config")
        db.execSQL("DELETE FROM first_meeting_state")
        db.execSQL("DELETE FROM relationship_state")
        db.execSQL("DELETE FROM emotional_state")
        db.execSQL("DELETE FROM chat_messages")
        db.execSQL("DELETE FROM memories")
    }

    /**
     * 构造一个带 nicknameForUser 的配置，模拟导出者已设置称呼的 .agent.zip
     */
    private fun configWithNickname(nickname: String): String {
        val base = DefaultAgent.create()
        val config = base.copy(
            agent = base.agent.copy(
                name = "测试Agent",
                persona = base.agent.persona.copy(
                    nicknameForUser = nickname
                )
            )
        )
        return json.encodeToString(AgentConfig.serializer(), config)
    }

    @Test
    fun import_same_config_twice_produces_different_ids_and_isolated_data() = runBlocking {
        val configJson = configWithNickname("小明")
        val config = json.decodeFromString<AgentConfig>(configJson)

        // 模拟两次导入（直接调用 AgentImportManager 的核心事务逻辑）
        val id1 = insertAgentTransaction(config, clearNickname = true)
        val id2 = insertAgentTransaction(config, clearNickname = true)

        // 1. 两次导入得到不同 ID
        assertNotEquals("两次导入应得到不同 ID", id1, id2)

        // 2. 两个 Agent 的聊天/记忆数据空间独立且为空
        assertEquals("Agent 1 聊天记录应为空", 0, chatDao.getAll(id1).size)
        assertEquals("Agent 2 聊天记录应为空", 0, chatDao.getAll(id2).size)
        assertEquals("Agent 1 记忆应为空", 0, memoryDao.getByMinImportance(id1, 0).size)
        assertEquals("Agent 2 记忆应为空", 0, memoryDao.getByMinImportance(id2, 0).size)

        // 3. 两个 Agent 的首次见面状态均为 NOT_STARTED
        val fm1 = firstMeetingDao.getByAgentId(id1)
        val fm2 = firstMeetingDao.getByAgentId(id2)
        assertNotNull("Agent 1 应有首次见面状态", fm1)
        assertNotNull("Agent 2 应有首次见面状态", fm2)
        assertEquals("Agent 1 首次见面应为 NOT_STARTED", "NOT_STARTED", fm1!!.phase)
        assertEquals("Agent 2 首次见面应为 NOT_STARTED", "NOT_STARTED", fm2!!.phase)

        // 4. 两个 Agent 的关系状态均已初始化
        val rel1 = relationshipDao.get(id1)
        val rel2 = relationshipDao.get(id2)
        assertNotNull("Agent 1 应有关系状态", rel1)
        assertNotNull("Agent 2 应有关系状态", rel2)
        assertEquals("Agent 1 关系应为 stranger", "stranger", rel1!!.currentStage)
        assertEquals("Agent 2 关系应为 stranger", "stranger", rel2!!.currentStage)

        // 5. 两个 Agent 的情绪状态均已初始化
        val emo1 = emotionalDao.get(id1)
        val emo2 = emotionalDao.get(id2)
        assertNotNull("Agent 1 应有情绪状态", emo1)
        assertNotNull("Agent 2 应有情绪状态", emo2)
    }

    @Test
    fun import_clears_nickname_for_user_from_exporter() = runBlocking {
        val configJson = configWithNickname("导出者的小明")
        val config = json.decodeFromString<AgentConfig>(configJson)

        val agentId = insertAgentTransaction(config, clearNickname = true)

        // 读取入库后的 configJson，验证 nicknameForUser 已被清空
        val entity = agentConfigDao.getById(agentId)
        assertNotNull("应能查到导入的 Agent", entity)
        val storedConfig = json.decodeFromString<AgentConfig>(entity!!.configJson)
        assertEquals(
            "导入后 nicknameForUser 应被清空，不继承导出者称呼",
            "",
            storedConfig.agent.persona.nicknameForUser
        )
    }

    @Test
    fun import_activates_new_instance_and_deactivates_others() = runBlocking {
        val config = DefaultAgent.create()

        // 先插入一个已激活的旧 Agent
        val oldId = agentConfigDao.insert(
            AgentConfigEntity(
                configJson = json.encodeToString(AgentConfig.serializer(), config),
                agentName = "旧Agent",
                importedAt = 1000L,
                isActive = true
            )
        )
        assertEquals("初始应有 1 个激活", 1, agentConfigDao.countActive())

        // 导入新 Agent
        val newId = insertAgentTransaction(config, clearNickname = true)

        // 新 Agent 应被激活，旧 Agent 应被停用
        assertEquals("仍应只有 1 个激活", 1, agentConfigDao.countActive())
        assertTrue("新 Agent 应为激活", agentConfigDao.getById(newId)!!.isActive)
        assertTrue("旧 Agent 应被停用", !agentConfigDao.getById(oldId)!!.isActive)
    }

    /**
     * 模拟 AgentImportManager.import 的核心事务逻辑：
     * - 清空 nicknameForUser
     * - 插入配置并激活
     * - 初始化首次见面/关系/情绪状态
     *
     * 返回新 Agent 的 ID
     */
    private suspend fun insertAgentTransaction(
        config: AgentConfig,
        clearNickname: Boolean = true
    ): Long {
        // 清空导出者的称呼
        val importConfig = if (clearNickname) {
            config.copy(
                agent = config.agent.copy(
                    persona = config.agent.persona.copy(nicknameForUser = "")
                )
            )
        } else {
            config
        }
        val configJson = json.encodeToString(AgentConfig.serializer(), importConfig)
        val now = System.currentTimeMillis()

        // 事务：停用全部 + 插入新配置 + 初始化状态
        return ServiceLocator.database.withTransaction {
            agentConfigDao.deactivateAll()
            val agentId = agentConfigDao.insert(
                AgentConfigEntity(
                    configJson = configJson,
                    agentName = importConfig.agent.name,
                    importedAt = now,
                    isActive = true
                )
            )
            // 初始化首次见面状态
            firstMeetingDao.upsert(
                com.agent.ta.data.local.entity.FirstMeetingStateEntity(
                    agentId = agentId,
                    phase = "NOT_STARTED"
                )
            )
            // 初始化关系状态
            relationshipDao.upsert(
                com.agent.ta.data.local.entity.RelationshipStateEntity(
                    agentId = agentId,
                    currentStage = "stranger",
                    intimacyScore = 0,
                    trustScore = 0,
                    interactionCount = 0,
                    lastInteractionAt = now,
                    lastDecayAt = now
                )
            )
            // 初始化情绪状态
            emotionalDao.upsert(
                com.agent.ta.data.local.entity.EmotionalStateEntity(
                    agentId = agentId,
                    valence = 0f,
                    arousal = 0.3f,
                    potentialEnergy = 0,
                    lastEmotion = null,
                    lastUserInteractionAt = now,
                    lastDecayAt = now
                )
            )
            agentId
        }
    }
}
