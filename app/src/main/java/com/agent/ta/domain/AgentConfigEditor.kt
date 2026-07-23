package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Agent 配置编辑器
 *
 * 职责：
 * - 读取当前激活的 AgentConfig（通过 AgentConfigProvider 内存缓存，非阻塞）
 * - 保存修改后的 AgentConfig 到 DB 并刷新内存缓存
 * - 提供便捷的 update(transform) 方法进行局部修改
 *
 * 使用场景：
 * - 手动配置页面（6 个配置页面编辑各模块后保存）
 * - 斜杠命令配置模式（Agent 对话引导式修改后保存）
 */
class AgentConfigEditor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** 读取当前配置（内存缓存，非阻塞） */
    fun get(): AgentConfig = ServiceLocator.agentConfigProvider.get()

    /**
     * 保存配置到 DB 并刷新内存缓存
     * 若 DB 中无激活记录，则插入新记录
     */
    suspend fun save(config: AgentConfig) = withContext(Dispatchers.IO) {
        val configJson = json.encodeToString(AgentConfig.serializer(), config)
        val agentName = config.agent.name.ifBlank { "未命名" }
        try {
            val existing = ServiceLocator.agentConfigDao.getActive()
            if (existing != null) {
                ServiceLocator.agentConfigDao.updateActive(configJson, agentName)
            } else {
                ServiceLocator.agentConfigDao.deactivateAll()
                ServiceLocator.agentConfigDao.insert(
                    AgentConfigEntity(
                        configJson = configJson,
                        agentName = agentName,
                        importedAt = System.currentTimeMillis(),
                        isActive = true
                    )
                )
            }
            ServiceLocator.agentConfigProvider.reload()
            Log.d(TAG, "AgentConfig 已保存：${config.agent.name}")
        } catch (e: Exception) {
            Log.e(TAG, "保存 AgentConfig 失败", e)
        }
    }

    /**
     * 局部更新配置
     * @param transform 接收当前配置，返回修改后的配置
     */
    suspend fun update(transform: (AgentConfig) -> AgentConfig) {
        val current = get()
        val updated = transform(current)
        save(updated)
    }

    companion object {
        private const val TAG = "AgentConfigEditor"
    }
}
