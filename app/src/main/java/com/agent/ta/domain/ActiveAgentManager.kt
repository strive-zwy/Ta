package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 活跃 Agent 实例管理器
 *
 * 职责：
 * - 维护当前激活的 agentId（应用级唯一权威状态）
 * - 首次启动时确保默认 Agent 已持久化并激活
 * - 切换 Agent 实例时原子地更新激活状态并刷新配置缓存
 *
 * 设计原则：所有异步请求在开始时捕获 agentId，结果只写回该实例，
 *           禁止完成时临时查询当前 Agent。
 */
class ActiveAgentManager {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val _activeAgentId = MutableStateFlow<Long?>(null)
    val activeAgentId: StateFlow<Long?> = _activeAgentId.asStateFlow()

    private val switchMutex = Mutex()

    /**
     * 首次启动 / 无激活记录时调用：
     * - 若 DB 已有配置，按确定性规则（importedAt DESC, id DESC）选一个并激活
     * - 若 DB 为空，插入默认 Agent 配置并激活
     *
     * 完成后刷新 AgentConfigProvider 内存缓存。
     */
    suspend fun ensureDefaultAgentPersisted() = withContext(Dispatchers.IO) {
        val dao = ServiceLocator.agentConfigDao
        val existing = dao.getActiveDeterministic()
        val activeId = if (existing != null) {
            if (!existing.isActive) {
                dao.activateById(existing.id)
            }
            existing.id
        } else {
            val config = DefaultAgent.create()
            val configJson = json.encodeToString(AgentConfig.serializer(), config)
            val id = dao.insert(
                AgentConfigEntity(
                    configJson = configJson,
                    agentName = config.agent.name.ifBlank { "未命名" },
                    importedAt = System.currentTimeMillis(),
                    isActive = true
                )
            )
            dao.activateById(id)
            id
        }
        _activeAgentId.value = activeId
        ServiceLocator.agentConfigProvider.reload()
        Log.d(TAG, "ensureDefaultAgentPersisted: activeAgentId=$activeId")
    }

    /**
     * 切换到指定 Agent 实例（原子操作）。
     * 切换后刷新配置缓存，使 UI 与领域层立即看到新 Agent。
     */
    suspend fun switchTo(agentId: Long) = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            val dao = ServiceLocator.agentConfigDao
            val target = dao.getById(agentId)
            if (target == null) {
                Log.w(TAG, "switchTo: agentId=$agentId 不存在，忽略")
                return@withContext
            }
            dao.activateById(agentId)
            _activeAgentId.value = agentId
            ServiceLocator.agentConfigProvider.reload()
            Log.d(TAG, "switchTo: 已切换到 agentId=$agentId")
        }
    }

    /**
     * 获取当前激活的 agentId。
     * 若尚未初始化则抛异常（调用方应先调用 ensureDefaultAgentPersisted()）。
     */
    fun getRequiredActiveAgentId(): Long =
        _activeAgentId.value
            ?: throw IllegalStateException("尚未初始化活跃 Agent，请先调用 ensureDefaultAgentPersisted()")

    companion object {
        private const val TAG = "ActiveAgentManager"
    }
}
