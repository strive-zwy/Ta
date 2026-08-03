package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.local.entity.MilestoneEventEntity
import com.agent.ta.data.local.entity.RelationshipStateEntity
import com.agent.ta.data.model.RelationshipStage
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 关系状态业务编排服务
 *
 * 封装 RelationshipEngine + DAO 的调用，提供：
 * - onTurnCompleted：对话结束后推进数值 + 检测阶段切换
 * - applyDailyDecayIfNeeded：跨天调用衰减
 * - recordMilestone：写入里程碑事件（含去重）
 * - getCurrentState：读取当前状态（首次自动初始化）
 * - getRecentMilestones：读取最近里程碑供 prompt 注入
 */
class RelationshipService {

    private val engine = RelationshipEngine()
    private val initializer = RelationshipInitializer()

    companion object {
        private const val TAG = "RelationshipService"

        /**
         * 里程碑 type → title 映射表
         * 未匹配的 type 直接用 type 字符串作为 title
         */
        val MILESTONE_TITLE_MAP: Map<String, String> = mapOf(
            "first_vulnerability" to "第一次袒露脆弱",
            "first_argument" to "第一次争吵",
            "first_secret_shared" to "第一次分享秘密",
            "first_initiative_care" to "第一次主动关心你",
            "first_emoji_to_user" to "第一次对你用表情",
            "late_night_confidant" to "愿深夜相伴",
            "consistent_chat" to "持续陪伴",
            "stage_transition_to_acquaintance" to "关系进入初识阶段",
            "stage_transition_to_familiar" to "关系进入熟悉阶段",
            "stage_transition_to_intimate" to "关系进入亲密阶段",
            "stage_transition_to_confidant" to "关系进入知己阶段"
        )

        private val DEDUP_WINDOW_MS = 24 * 60 * 60 * 1000L  // 24 小时
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * 读取当前关系状态（首次自动初始化）
     */
    suspend fun getCurrentState(): RelationshipStateEntity = withContext(Dispatchers.IO) {
        initializer.ensureInitialized()
    }

    /**
     * 对话结束后推进关系数值
     * @param emotion LLM 回复的情绪（happy/sad/angry/neutral 等）
     * @param isUserInitiated 是否为用户主动发起
     * @param messageLength 回复文本长度
     */
    suspend fun onTurnCompleted(emotion: String, isUserInitiated: Boolean, messageLength: Int) {
        withContext(Dispatchers.IO) {
            val state = initializer.ensureInitialized()
            val ctx = RelationshipEngine.TurnContext(
                emotion = emotion,
                isUserInitiated = isUserInitiated,
                messageLength = messageLength
            )
            val update = engine.applyTurnEnd(ctx, state)

            val newIntimacy = (state.intimacyScore + update.intimacyIncrement)
                .coerceIn(0.0, 100.0).toInt()
            val newTrust = (state.trustScore + update.trustIncrement)
                .coerceIn(0.0, 100.0).toInt()
            val now = System.currentTimeMillis()

            ServiceLocator.relationshipStateDao.updateScores(
                intimacy = newIntimacy,
                trust = newTrust,
                interactionCount = update.newInteractionCount,
                lastInteractionAt = now,
                updatedAt = now
            )

            // 阶段切换自动触发里程碑
            update.stageTransition?.let { newStage ->
                ServiceLocator.relationshipStateDao.updateStage(newStage.id, now)
                val type = "stage_transition_to_${newStage.id}"
                val title = MILESTONE_TITLE_MAP[type] ?: "关系进入${newStage.displayName}阶段"
                recordMilestoneInternal(type, title, "engine_detected", mapOf("newStage" to newStage.id, "intimacy" to newIntimacy))
                Log.d(TAG, "关系阶段切换：${state.currentStage} → ${newStage.id}（intimacy=$newIntimacy）")
            }

            // Engine 兜底检测模式触发里程碑
            val recentMilestones = ServiceLocator.milestoneEventDao.getRecent(10)
            val patternType = engine.shouldTriggerMilestoneByPattern(ctx, recentMilestones, update.newInteractionCount)
            patternType?.let {
                val title = MILESTONE_TITLE_MAP[it] ?: it
                recordMilestoneInternal(it, title, "engine_detected", mapOf("emotion" to emotion, "turnCount" to update.newInteractionCount))
                Log.d(TAG, "Engine 检测到模式触发里程碑：$it")
            }

            Log.d(TAG, "对话轮完成：intimacy +${update.intimacyIncrement.format(2)} (=${newIntimacy}), trust +${update.trustIncrement.format(2)} (=${newTrust}), turns=${update.newInteractionCount}")
        }
    }

    /**
     * 跨天调用：检查并应用每日衰减
     */
    suspend fun applyDailyDecayIfNeeded() {
        withContext(Dispatchers.IO) {
            val state = initializer.ensureInitialized()
            val now = System.currentTimeMillis()
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            val lastDecayDate = java.time.Instant.ofEpochMilli(state.lastDecayAt)
                .atZone(zone).toLocalDate()
            val today = java.time.Instant.ofEpochMilli(now)
                .atZone(zone).toLocalDate()

            // 若上次衰减日期早于今天，则执行衰减
            if (lastDecayDate.isBefore(today)) {
                val newState = engine.applyDailyDecay(state)
                ServiceLocator.relationshipStateDao.updateScores(
                    intimacy = newState.intimacyScore,
                    trust = newState.trustScore,
                    interactionCount = state.interactionCount,
                    lastInteractionAt = state.lastInteractionAt,
                    updatedAt = now
                )
                ServiceLocator.relationshipStateDao.updateDecayTime(now, now)
                Log.d(TAG, "每日衰减完成：intimacy ${state.intimacyScore} → ${newState.intimacyScore}, trust ${state.trustScore} → ${newState.trustScore}")
            }
        }
    }

    /**
     * 记录里程碑事件（含 24 小时去重）
     * @param type 里程碑 type（如 "first_vulnerability"）
     * @param title 显示名
     * @param source 触发来源（"llm_declared" / "engine_detected"）
     * @param context 上下文键值对，会被序列化为 JSON
     * @return 是否成功写入（true 表示写入，false 表示被去重跳过）
     */
    suspend fun recordMilestone(
        type: String,
        title: String,
        source: String,
        context: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        recordMilestoneInternal(type, title, source, context)
    }

    private suspend fun recordMilestoneInternal(
        type: String,
        title: String,
        source: String,
        context: Map<String, Any>
    ): Boolean {
        // 去重检查：同 type 在 24 小时内不重复（stage_transition 永不重复同类型）
        val now = System.currentTimeMillis()
        val sinceTs = now - DEDUP_WINDOW_MS
        val recentSameType = ServiceLocator.milestoneEventDao.getByType(type)
            .filter { it.triggeredAt >= sinceTs }

        // stage_transition 类型永不重复同 type
        val isStageTransition = type.startsWith("stage_transition_")
        if (isStageTransition && recentSameType.isNotEmpty()) {
            Log.d(TAG, "里程碑 $type 已存在（stage_transition 不重复），跳过")
            return false
        }

        // 普通类型 24 小时内不重复
        if (!isStageTransition && recentSameType.isNotEmpty()) {
            Log.d(TAG, "里程碑 $type 24h 内已触发，跳过去重")
            return false
        }

        val contextJson = buildJsonObject {
            context.forEach { (k, v) ->
                when (v) {
                    is String -> put(k, v)
                    is Number -> put(k, v.toDouble())
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }.toString()

        val event = MilestoneEventEntity(
            type = type,
            title = title,
            triggeredAt = now,
            triggerSource = source,
            contextSnapshot = contextJson
        )
        ServiceLocator.milestoneEventDao.insert(event)
        Log.d(TAG, "里程碑触发：$type ($title) 来源=$source")
        return true
    }

    /**
     * 读取最近 N 条里程碑（供 prompt 注入）
     */
    suspend fun getRecentMilestones(limit: Int = 5): List<MilestoneEventEntity> = withContext(Dispatchers.IO) {
        ServiceLocator.milestoneEventDao.getRecent(limit)
    }

    /**
     * Double 格式化到指定小数位
     */
    private fun Double.format(decimals: Int): String {
        return String.format("%.${decimals}f", this)
    }
}
