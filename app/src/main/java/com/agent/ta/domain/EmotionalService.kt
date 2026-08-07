package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.local.entity.EmotionalStateEntity
import com.agent.ta.di.ServiceLocator
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 情绪业务编排（Phase 3 情感势能驱动主动发起）
 *
 * 封装 [EmotionalEngine] 的纯函数计算 + [com.agent.ta.data.local.dao.EmotionalStateDao] 持久化，
 * 对外提供业务语义化接口，由 ChatInteractor/BoredInitiator/AgentEngine 调用。
 *
 * 与 Phase 2 的 [RelationshipService] 完全解耦：
 * - 关系系统管 intimacyScore/trustScore/interactionCount（影响回复延迟）
 * - 情绪系统管 valence/arousal/potentialEnergy（影响主动发起门控 + 语气）
 * - 两者都通过 onTurnCompleted 触发，但各自独立更新
 *
 * 多 Agent 隔离：所有方法在内部捕获当前 active agentId，
 * 整个操作流程只读写该 Agent 的数据。
 */
class EmotionalService {

    private val dao = ServiceLocator.emotionalStateDao

    /**
     * 获取当前 Agent 的情绪状态（首次调用自动初始化中性状态）
     */
    suspend fun getCurrentState(): EmotionalStateEntity {
        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        return getState(agentId)
    }

    suspend fun getCurrentState(agentId: Long): EmotionalStateEntity = getState(agentId)

    /**
     * 对话轮驱动：LLM 回复后调用，根据自报情绪强度更新状态
     *
     * @param emotionIntensity -2.0(强烈负面) ~ +2.0(强烈兴奋)
     * @param emotion 情绪标签（happy/sad/angry/neutral...）
     */
    suspend fun onTurnCompleted(agentId: Long, emotionIntensity: Float, emotion: String) {
        val current = getState(agentId)
        val update = EmotionalEngine.applyTurnEnd(emotionIntensity, emotion, current)

        val newEnergy = (current.potentialEnergy + update.energyIncrement).coerceIn(0, 100)
        val now = System.currentTimeMillis()

        dao.updateState(
            agentId = agentId,
            valence = update.newValence,
            arousal = update.newArousal,
            potentialEnergy = newEnergy,
            lastEmotion = update.newLastEmotion,
            lastUserInteractionAt = current.lastUserInteractionAt,  // 不重置静默计时
            lastDecayAt = current.lastDecayAt,
            updatedAt = now
        )
        Log.d(TAG, "onTurnCompleted: intensity=$emotionIntensity, energy ${current.potentialEnergy}→$newEnergy (+${update.energyIncrement}), valence ${current.valence}→${update.newValence}")
    }

    /**
     * 用户发消息时调用：重置静默计时
     */
    suspend fun onUserMessageReceived(agentId: Long) {
        getState(agentId)
        dao.updateLastUserInteraction(agentId, System.currentTimeMillis())
        Log.d(TAG, "onUserMessageReceived: 重置静默计时")
    }

    private suspend fun getState(agentId: Long): EmotionalStateEntity {
        return dao.get(agentId) ?: run {
            val now = System.currentTimeMillis()
            EmotionalStateEntity(agentId, 0f, 0.3f, 0, null, now, now).also { dao.upsert(it) }
        }
    }

    /**
     * 每小时心跳触发：先静默积累，再衰减
     *
     * 顺序说明：先积累（势能 +）再衰减（势能 -2），这样积累的势能不会被衰减抵消。
     * 衰减在积累之后，更符合"想分享→时间过去→情绪淡化"的心理节奏。
     */
    suspend fun applyHourlyDecayAndAccumulation() {
        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        val current = getCurrentState()
        val now = System.currentTimeMillis()

        // 1. 静默积累
        val silentUpdate = EmotionalEngine.applySilentAccumulation(current, now)
        val energyAfterAccumulation = (current.potentialEnergy + silentUpdate.energyDelta).coerceIn(0, 100)

        // 2. 每小时衰减（基于积累后的状态）
        val tempState = current.copy(potentialEnergy = energyAfterAccumulation)
        val decayUpdate = EmotionalEngine.applyHourlyDecay(tempState)

        dao.updateState(
            agentId = agentId,
            valence = decayUpdate.newValence,
            arousal = decayUpdate.newArousal,
            potentialEnergy = decayUpdate.newEnergy,
            lastEmotion = current.lastEmotion,
            lastUserInteractionAt = current.lastUserInteractionAt,  // 不重置
            lastDecayAt = now,  // 更新衰减时间戳
            updatedAt = now
        )
        Log.d(TAG, "applyHourlyDecayAndAccumulation: energy ${current.potentialEnergy}→${decayUpdate.newEnergy} (silent ${silentUpdate.energyDelta}, decay -2)")
    }

    /**
     * 跨天触发：读取昨日 daily_state，应用睡眠基线
     */
    suspend fun applySleepBaselineIfNeeded() {
        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        val current = getCurrentState()
        val yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .minusDays(1)
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayState = ServiceLocator.dailyStateDao.getByDate(agentId, yesterday)

        val newState = EmotionalEngine.applySleepBaseline(yesterdayState, current)
        dao.updateState(
            agentId = agentId,
            valence = newState.valence,
            arousal = newState.arousal,
            potentialEnergy = current.potentialEnergy,  // 不重置势能
            lastEmotion = current.lastEmotion,
            lastUserInteractionAt = current.lastUserInteractionAt,
            lastDecayAt = current.lastDecayAt,
            updatedAt = System.currentTimeMillis()
        )
        Log.d(TAG, "applySleepBaselineIfNeeded: yesterday=$yesterday, sleepDur=${yesterdayState?.sleepDurationMin}, newValence=${newState.valence}, newArousal=${newState.arousal}")
    }

    /**
     * 消耗势能（BoredInitiator 主动发起成功后调用）
     */
    suspend fun consumeEnergy(amount: Int) {
        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        val current = getCurrentState()
        val newEnergy = (current.potentialEnergy - amount).coerceAtLeast(0)
        dao.updateEnergy(agentId, newEnergy)
        Log.d(TAG, "consumeEnergy: $amount, energy ${current.potentialEnergy}→$newEnergy")
    }

    companion object {
        private const val TAG = "EmotionalService"

        /** 势能门控阈值（BoredInitiator 引用） */
        const val POTENTIAL_THRESHOLD_LOW = 20      // < 20 拦截主动发起
        const val POTENTIAL_THRESHOLD_HIGH = 80      // >= 80 绕过 30 分钟冷却

        /** 发起成功后消耗的势能 */
        const val CONSUME_ON_INITIATE = 30
    }
}
