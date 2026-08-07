package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** update 序列化锁，避免并发读-改-写覆盖 */
    private val updateMutex = Mutex()

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
        updateMutex.withLock {
            val current = get()
            val updated = transform(current)
            save(updated)
        }
    }

    /**
     * 局部更新指定 Agent 实例的配置（不依赖 active 记录）
     *
     * 用于异步回调场景：请求开始时捕获 agentId，结果只写回该实例，
     * 禁止完成时临时查询当前 active agent 替代。
     *
     * @param agentId 目标 Agent 实例 ID
     * @param transform 接收该 Agent 当前配置，返回修改后的配置
     */
    suspend fun updateAgent(agentId: Long, transform: (AgentConfig) -> AgentConfig) {
        updateMutex.withLock {
            val dao = ServiceLocator.agentConfigDao
            val entity = dao.getById(agentId) ?: run {
                Log.w(TAG, "updateAgent: agentId=$agentId 不存在，忽略")
                return@withLock
            }
            val current = json.decodeFromString(AgentConfig.serializer(), entity.configJson)
            val updated = transform(current)
            val configJson = json.encodeToString(AgentConfig.serializer(), updated)
            val agentName = updated.agent.name.ifBlank { "未命名" }
            dao.updateById(agentId, configJson, agentName)
            // 若更新的是当前激活 Agent，刷新内存缓存
            if (entity.isActive) {
                ServiceLocator.agentConfigProvider.reload()
            }
            Log.d(TAG, "AgentConfig 已按 ID 更新：agentId=$agentId")
        }
    }

    companion object {
        private const val TAG = "AgentConfigEditor"
    }
}
