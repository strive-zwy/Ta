package com.agent.ta.domain.config

import com.agent.ta.data.local.dao.ConfigSessionDao
import com.agent.ta.data.local.entity.ConfigSessionEntity
import com.agent.ta.data.model.AgentConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigSessionManagerTest {
    @Test
    fun `start creates collecting session with current config draft`() = runBlocking {
        val dao = FakeConfigSessionDao()
        val manager = ConfigSessionManager(dao)

        val session = manager.start(7, AgentConfig())

        assertEquals(7L, session.agentId)
        assertEquals(ConfigSessionStage.SELECTING_MODE, session.stage)
        assertNotNull(manager.getDraft(7))
    }

    @Test
    fun `start resumes unfinished session`() = runBlocking {
        val dao = FakeConfigSessionDao()
        val manager = ConfigSessionManager(dao)
        val first = manager.start(7, AgentConfig())
        manager.selectMode(7, ConfigSessionMode.CELEBRITY_REFERENCE)

        val resumed = manager.start(7, AgentConfig())

        assertEquals(first.createdAt, resumed.createdAt)
        assertEquals(ConfigSessionMode.CELEBRITY_REFERENCE, resumed.mode)
        assertEquals(ConfigSessionStage.COLLECTING_REFERENCE, resumed.stage)
    }

    @Test
    fun `complete removes active session`() = runBlocking {
        val dao = FakeConfigSessionDao()
        val manager = ConfigSessionManager(dao)
        manager.start(7, AgentConfig())

        manager.complete(7)

        assertNull(manager.get(7))
    }

    private class FakeConfigSessionDao : ConfigSessionDao {
        private val rows = mutableMapOf<Long, ConfigSessionEntity>()

        override suspend fun upsert(entity: ConfigSessionEntity) {
            rows[entity.agentId] = entity
        }

        override suspend fun getByAgentId(agentId: Long): ConfigSessionEntity? = rows[agentId]

        override fun observeByAgentId(agentId: Long): Flow<ConfigSessionEntity?> = flowOf(rows[agentId])

        override suspend fun updateStageIf(
            agentId: Long,
            fromStage: String,
            toStage: String,
            updatedAt: Long
        ): Int {
            val current = rows[agentId] ?: return 0
            if (current.stage != fromStage) return 0
            rows[agentId] = current.copy(stage = toStage, updatedAt = updatedAt)
            return 1
        }

        override suspend fun deleteByAgentId(agentId: Long) {
            rows.remove(agentId)
        }
    }
}
