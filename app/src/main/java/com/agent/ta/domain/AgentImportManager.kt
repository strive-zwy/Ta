package com.agent.ta.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import com.agent.ta.util.AgentConfigImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Agent 导入流程编排
 *
 * 1. 通过 AgentConfigImporter 解压并解析 .agent.zip
 * 2. 持久化到 agent_config 表（设为激活）
 * 3. 刷新 AgentConfigProvider 缓存
 * 4. 通知 AgentEngine 重新加载作息与状态机
 */
class AgentImportManager(private val context: Context) {

    private val importer = AgentConfigImporter(context)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 导入 .agent.zip
     *
     * @return 导入成功的 Agent 名称
     * @throws IllegalArgumentException 校验失败
     */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val config = importer.importFromUri(uri)

        // 持久化配置到 DB，设为激活
        val configJson = json.encodeToString(AgentConfig.serializer(), config)
        ServiceLocator.agentConfigDao.deactivateAll()
        val entity = AgentConfigEntity(
            configJson = configJson,
            agentName = config.agent.name,
            importedAt = System.currentTimeMillis(),
            isActive = true
        )
        ServiceLocator.agentConfigDao.insert(entity)
        Log.d(TAG, "已持久化 Agent 配置：${config.agent.name}")

        // Admin v2: 注入 memory_seeds 到记忆库
        // 把 Agent 配置包里的初始共享记忆一次性写入，让 Agent 一开始就"记得"这些事
        injectMemorySeeds(config)

        // 刷新内存缓存
        ServiceLocator.agentConfigProvider.reload()

        // 通知引擎重载作息与状态机
        com.agent.ta.service.AgentEngine.reloadAfterConfigChanged(context)

        config.agent.name
    }

    /**
     * 把 persona.memorySeeds 注入到记忆库
     *
     * 这些是 Agent 与用户之间的"初始共享记忆"（如"我们在大学认识"），
     * 在导入时一次性写入，类型为 user_profile，来源标记为 seed
     * 后续 LLM 在对话中产生的记忆更新走 source=chat，区分开
     */
    private suspend fun injectMemorySeeds(config: AgentConfig) {
        val seeds = config.agent.persona.memorySeeds
        if (seeds.isEmpty()) return

        val memoryDao = ServiceLocator.memoryDao
        val now = System.currentTimeMillis()
        seeds.forEach { seed ->
            val content = seed.trim()
            if (content.isBlank()) return@forEach
            memoryDao.insert(
                com.agent.ta.data.local.entity.MemoryEntity(
                    type = "shared",
                    category = "初始记忆",
                    content = content,
                    importance = 4,  // 初始记忆重要性较高
                    source = "seed",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        Log.d(TAG, "已注入 ${seeds.size} 条初始记忆（memory_seeds）")
    }
}

private const val TAG = "AgentImportManager"
