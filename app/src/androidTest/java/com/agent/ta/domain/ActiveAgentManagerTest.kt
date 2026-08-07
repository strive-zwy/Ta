package com.agent.ta.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ActiveAgentManager 仪器测试（Task 2）
 *
 * 验证：
 * 1. 同名 Agent 切换后只有目标 ID 为 active
 * 2. 无激活记录时按 importedAt DESC, id DESC 确定性 fallback
 * 3. 空库时插入默认 Agent 并激活
 * 4. 未初始化时 getRequiredActiveAgentId 抛异常
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class ActiveAgentManagerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private lateinit var manager: ActiveAgentManager

    @Before
    fun setup() {
        // 清空 agent_config 表，确保每个测试从干净状态开始
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM agent_config")
        manager = ActiveAgentManager()
    }

    private fun configJson(name: String = "测试Agent"): String {
        val base = DefaultAgent.create()
        val config = base.copy(agent = base.agent.copy(name = name))
        return json.encodeToString(AgentConfig.serializer(), config)
    }

    @Test
    fun switchTo_activates_only_target_agent() = runBlocking {
        val dao = ServiceLocator.agentConfigDao

        // 插入两个同名 Agent，都不激活
        val idA = dao.insert(
            AgentConfigEntity(
                configJson = configJson("同名"),
                agentName = "同名",
                importedAt = 1000L,
                isActive = false
            )
        )
        val idB = dao.insert(
            AgentConfigEntity(
                configJson = configJson("同名"),
                agentName = "同名",
                importedAt = 2000L,
                isActive = false
            )
        )

        // 切换到 A
        manager.switchTo(idA)
        assertEquals("切换到 A 后 activeAgentId 应为 idA", idA, manager.activeAgentId.value)
        assertEquals("应只有一个激活实例", 1, dao.countActive())
        assertTrue("A 应为激活", dao.getById(idA)!!.isActive)
        assertTrue("B 应为未激活", !dao.getById(idB)!!.isActive)

        // 切换到 B
        manager.switchTo(idB)
        assertEquals("切换到 B 后 activeAgentId 应为 idB", idB, manager.activeAgentId.value)
        assertEquals("仍应只有一个激活实例", 1, dao.countActive())
        assertTrue("B 应为激活", dao.getById(idB)!!.isActive)
        assertTrue("A 应为未激活", !dao.getById(idA)!!.isActive)
    }

    @Test
    fun ensureDefaultAgentPersisted_inserts_default_when_empty() = runBlocking {
        val dao = ServiceLocator.agentConfigDao

        manager.ensureDefaultAgentPersisted()

        val activeId = manager.activeAgentId.value
        assertNotNull("应产生非空 agentId", activeId)
        assertEquals("应只有一个激活实例", 1, dao.countActive())
        assertTrue("该实例应为激活", dao.getById(activeId!!)!!.isActive)
    }

    @Test
    fun ensureDefaultAgentPersisted_fallback_picks_most_recent_by_importedAt() = runBlocking {
        val dao = ServiceLocator.agentConfigDao

        // 无任何激活记录：插入三个都不激活的实例
        dao.insert(
            AgentConfigEntity(configJson = configJson("旧"), agentName = "旧", importedAt = 1000L, isActive = false)
        )
        val idNew = dao.insert(
            AgentConfigEntity(configJson = configJson("新"), agentName = "新", importedAt = 3000L, isActive = false)
        )
        dao.insert(
            AgentConfigEntity(configJson = configJson("中"), agentName = "中", importedAt = 2000L, isActive = false)
        )

        manager.ensureDefaultAgentPersisted()

        // 确定性 fallback：importedAt DESC, id DESC → 应选 idNew（importedAt=3000 最大）
        assertEquals("应选最近导入的实例", idNew, manager.activeAgentId.value)
        assertEquals("应只有一个激活实例", 1, dao.countActive())
    }

    @Test
    fun ensureDefaultAgentPersisted_fallback_tie_on_importedAt_uses_id_desc() = runBlocking {
        val dao = ServiceLocator.agentConfigDao

        // 相同 importedAt，id 更大者胜出
        dao.insert(
            AgentConfigEntity(configJson = configJson("低ID"), agentName = "低ID", importedAt = 5000L, isActive = false)
        )
        val idHigh = dao.insert(
            AgentConfigEntity(configJson = configJson("高ID"), agentName = "高ID", importedAt = 5000L, isActive = false)
        )

        manager.ensureDefaultAgentPersisted()

        assertEquals("相同导入时间应选 id 更大者", idHigh, manager.activeAgentId.value)
    }

    @Test
    fun getRequiredActiveAgentId_throws_when_not_initialized() {
        var threw = false
        try {
            manager.getRequiredActiveAgentId()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("未初始化时应抛 IllegalStateException", threw)
    }
}
