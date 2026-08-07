package com.agent.ta.infrastructure.observer

import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.anchor.ActivityAnchorManager
import com.agent.ta.service.AgentEngine

/**
 * 活动锚点观察者（L0 基础设施层）
 *
 * 职责：
 * 1. 监控当前 ActivityAnchor 的状态（活动内容、进度、是否过期）
 * 2. 锚点过期时通过 hasDelta=true 通知 Heartbeat
 * 3. 提供 promptHint 供主回复路径注入 Prompt
 *
 * 与 ActivityAnchorManager 的关系：
 * - ActivityAnchorManager 是状态持有者（L1）
 * - ActivityAnchorObserver 是状态读取者（L0），负责向 ObserverRegistry 暴露快照
 *
 * hasDelta 判定：
 * - 活动内容变化（如从"健身"切换到"洗澡"）
 * - 进度阶段变化（early → mid → late → expired，基于剩余时长占比）
 * - 来源变化（LLM → SCHEDULE）
 */
class ActivityAnchorObserver : Observer {

    override val id: String = "activity_anchor"

    override suspend fun collect(): ObserverSnapshot {
        val anchor = AgentEngine.getCurrentActivityAnchor()
        val timestamp = System.currentTimeMillis()

        return if (anchor != null) {
            val progress = anchor.progressDescription(timestamp)
            val sourceTag = when (anchor.source) {
                com.agent.ta.domain.anchor.AnchorSource.LLM -> "你之前设置的"
                com.agent.ta.domain.anchor.AnchorSource.SCHEDULE -> "作息表当前时段"
                com.agent.ta.domain.anchor.AnchorSource.INFERRED -> "推断"
            }
            val elapsed = anchor.elapsedMinutes(timestamp)
            val remaining = anchor.remainingMinutes(timestamp)
            val total = elapsed + remaining
            val stage = when {
                remaining <= 0 -> "expired"
                total <= 0 -> "unknown"
                remaining.toFloat() / total < 0.2f -> "late"
                remaining.toFloat() / total < 0.5f -> "mid"
                else -> "early"
            }

            val promptHint = buildString {
                appendLine("【活动锚点观察】")
                appendLine("当前活动：${anchor.activity}（$sourceTag）")
                appendLine("时段：${anchor.slotStart}-${anchor.slotEnd}")
            }

            ObserverSnapshot(
                observerId = id,
                timestamp = timestamp,
                data = mapOf(
                    "activity" to anchor.activity,
                    "progress" to progress,
                    "source" to anchor.source.name,
                    "state" to anchor.state.id,
                    "remaining_minutes" to remaining,
                    "stage" to stage
                ),
                promptHint = promptHint
            )
        } else {
            ObserverSnapshot(
                observerId = id,
                timestamp = timestamp,
                data = emptyMap(),
                promptHint = "【活动锚点观察】当前无活动锚点"
            )
        }
    }

    override fun hasDelta(current: ObserverSnapshot, previous: ObserverSnapshot?): Boolean {
        if (previous == null) return true

        val currentActivity = current.data["activity"] as? String ?: ""
        val currentStage = current.data["stage"] as? String ?: ""
        val currentSource = current.data["source"] as? String ?: ""
        val prevActivity = previous.data["activity"] as? String ?: ""
        val prevStage = previous.data["stage"] as? String ?: ""
        val prevSource = previous.data["source"] as? String ?: ""

        return currentActivity != prevActivity ||
            currentStage != prevStage ||
            currentSource != prevSource
    }
}
