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
     * 从 DB 加载激活配置（若存在）到内存缓存
     * App 启动 / 导入新配置后调用
     */
    suspend fun reload() = withContext(Dispatchers.IO) {
        try {
            val entity = ServiceLocator.agentConfigDao.getActive()
            if (entity != null) {
                val parsed = json.decodeFromString<AgentConfig>(entity.configJson)
                _config.value = parsed
                Log.d(TAG, "已加载自定义 Agent 配置：${parsed.agent.name}")
            } else {
                _config.value = DefaultAgent.create()
                Log.d(TAG, "未发现自定义配置，使用默认 Agent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载自定义配置失败，回退默认 Agent", e)
            _config.value = DefaultAgent.create()
        }
    }

    /**
     * 同步取当前配置（内存缓存，不会阻塞）
     */
    fun get(): AgentConfig = _config.value

    companion object {
        private const val TAG = "AgentConfigProvider"
    }
}
