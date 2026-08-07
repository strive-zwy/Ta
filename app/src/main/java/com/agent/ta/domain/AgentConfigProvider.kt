package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Agent 配置提供者
 *
 * 职责：
 * - 启动时从 DB 读取激活的自定义 Agent 配置；若没有则用 DefaultAgent
 * - 提供内存缓存，避免每次调用都查 DB
 * - 导入新配置后调用 reload() 刷新缓存
 *
 * 设计：所有需要 AgentConfig 的地方（ChatInteractor、PromptBuilder、AgentEngine 等）
 *       统一通过本提供者取，而不是直接调 DefaultAgent.create()
 */
class AgentConfigProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val _config = MutableStateFlow(DefaultAgent.create())
    val config: StateFlow<AgentConfig> = _config.asStateFlow()

    /**
     * 当前已加载配置对应的 Agent 实例 ID（DB 主键）。
     * - 已持久化的激活 Agent：对应 entity.id
     * - 未持久化（仅内存默认 Agent）：null
     *
     * 所有需要写回数据的异步流程应优先使用 ActiveAgentManager.activeAgentId，
     * 此处仅供 UI / 同步读取场景使用。
     */
    private val _agentId = MutableStateFlow<Long?>(null)
    val agentId: StateFlow<Long?> = _agentId.asStateFlow()

    /**
     * 从 DB 加载激活配置（若存在）到内存缓存，同时刷新实例 ID。
     * App 启动 / 导入新配置后调用。
     *
     * 注意：调用方应先通过 ActiveAgentManager.ensureDefaultAgentPersisted()
     * 确保已有持久化的激活记录，避免长期停留在「仅内存默认 Agent」状态。
     */
    suspend fun reload() = withContext(Dispatchers.IO) {
        try {
            val entity = ServiceLocator.agentConfigDao.getActive()
            if (entity != null) {
                val parsed = json.decodeFromString<AgentConfig>(entity.configJson)
                _config.value = parsed
                _agentId.value = entity.id
                Log.d(TAG, "已加载自定义 Agent 配置：${parsed.agent.name}（id=${entity.id}）")
            } else {
                _config.value = DefaultAgent.create()
                _agentId.value = null
                Log.w(TAG, "未发现激活配置，临时使用内存默认 Agent（agentId=null）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载自定义配置失败，回退默认 Agent", e)
            _config.value = DefaultAgent.create()
            _agentId.value = null
        }
    }

    /**
     * 同步取当前配置（内存缓存，不会阻塞）
     */
    fun get(): AgentConfig = _config.value

    /**
     * 同步取当前已加载配置的 Agent 实例 ID（未持久化时为 null）
     */
    fun getAgentId(): Long? = _agentId.value

    companion object {
        private const val TAG = "AgentConfigProvider"
    }
}
