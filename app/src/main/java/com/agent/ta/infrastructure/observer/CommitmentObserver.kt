package com.agent.ta.infrastructure.observer

import com.agent.ta.di.ServiceLocator
import com.agent.ta.infrastructure.time.TimeContext

/**
 * 承诺观察者（L0 基础设施层）
 *
 * 职责：
 * 1. 监控到期承诺（appointment / promise / reminder）
 * 2. 检测到新增到期承诺时通过 hasDelta=true 通知 Heartbeat
 * 3. 作为 AlarmManager 的兜底机制（App 打开时每 60 秒补检 AlarmManager 遗漏的触发）
 *
 * hasDelta 判定：
 * - 当前到期承诺数量 > 上次到期承诺数量（有新的到期承诺）
 *
 * 与 AlarmManager 的关系：
 * - AlarmManager 为主触发（App 关闭也能唤醒）
 * - 本观察者为兜底（App 打开时由 Heartbeat 补检）
 * - 实际触发动作由 AgentEngine 的 Heartbeat 回调执行，本观察者仅负责检测变化
 */
class CommitmentObserver : Observer {

    override val id: String = "commitment"

    private val timeContext = TimeContext.getInstance()

    override suspend fun collect(): ObserverSnapshot {
        val timestamp = timeContext.nowMillis()
        // 查询已到触发时间但还未触发的承诺
        val dueCommitments = ServiceLocator.commitmentDao.getDueCommitments(timestamp)

        val dueCount = dueCommitments.size
        // 简要信息列表（供程序读取）
        val briefList = dueCommitments.map { c ->
            mapOf(
                "id" to c.id,
                "type" to c.type,
                "content" to c.content,
                "trigger_at" to (c.triggerAt ?: 0L)
            )
        }

        val promptHint = buildString {
            appendLine("【承诺到期观察】")
            if (dueCount == 0) {
                appendLine("当前无到期承诺")
            } else {
                appendLine("到期承诺数：$dueCount")
                dueCommitments.take(3).forEach { c ->
                    val typeLabel = when (c.type) {
                        "appointment" -> "约定"
                        "promise" -> "承诺"
                        "reminder" -> "提醒"
                        else -> c.type
                    }
                    appendLine("- [$typeLabel] ${c.content}（触发时间：${timeContext.formatDateTime(c.triggerAt ?: 0L)}）")
                }
                if (dueCount > 3) {
                    appendLine("...及其他 ${dueCount - 3} 条")
                }
            }
        }

        return ObserverSnapshot(
            observerId = id,
            timestamp = timestamp,
            data = mapOf(
                "due_count" to dueCount,
                "due_brief" to briefList
            ),
            promptHint = promptHint
        )
    }

    override fun hasDelta(current: ObserverSnapshot, previous: ObserverSnapshot?): Boolean {
        if (previous == null) return true

        val currentCount = current.data["due_count"] as? Int ?: 0
        val prevCount = previous.data["due_count"] as? Int ?: 0

        // 当前到期数量 > 上次到期数量 → 有新的到期承诺
        return currentCount > prevCount
    }
}
