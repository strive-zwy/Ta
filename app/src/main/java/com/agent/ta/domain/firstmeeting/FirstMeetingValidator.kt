package com.agent.ta.domain.firstmeeting

import com.agent.ta.data.remote.dto.AgentReply
import com.agent.ta.data.remote.dto.FirstMeetingMeta
import com.agent.ta.data.remote.dto.ReplyItem

/**
 * 首次见面元数据校验器（Task 12）
 *
 * 职责：
 * - 校验 LLM 输出的 firstMeetingMeta 是否达成两个核心目标（自我介绍 + 询问称呼）
 * - 提供纠正重试的提示语
 * - 提供最小兜底问句（重试仍失败时使用）
 *
 * 校验规则：
 * - GREETING 场景：introducedSelf 和 askedForNickname 都必须为 true
 * - REPLY 场景：不强制（用户先发消息时 Agent 自然回应即可，但仍鼓励完成两个目标）
 *
 * 重试策略：
 * - 第一次失败 → 追加纠正提示重试一次
 * - 第二次仍失败 → 使用最小兜底问句（直接合成语音入库，不再调 LLM）
 */
object FirstMeetingValidator {

    /**
     * 校验首次见面元数据是否合格
     *
     * @param reply LLM 返回的 AgentReply
     * @param requireBothGoals 是否强制要求两个目标都达成（GREETING 场景为 true，REPLY 场景为 false）
     * @return true 表示合格，false 表示需要重试或使用兜底
     */
    fun isMetaValid(reply: AgentReply, requireBothGoals: Boolean = true): Boolean {
        val meta = reply.firstMeetingMeta ?: return !requireBothGoals
        if (!requireBothGoals) return true
        return meta.introducedSelf && meta.askedForNickname
    }

    /**
     * 构造纠正提示语（第一次校验失败时追加到 messages 末尾让 LLM 重新生成）
     *
     * 只列出缺失的目标，已达成的不重复提示，避免 LLM 误以为还需再做一次。
     *
     * @param meta LLM 上次输出的元数据（可能为 null）
     */
    fun buildCorrectionHint(meta: FirstMeetingMeta?): String {
        val missing = mutableListOf<String>()
        // (目标描述, 对应 firstMeetingMeta 字段重置指令)
        val resetFlags = mutableListOf<String>()
        if (meta == null) {
            missing.add("自我介绍（让用户知道你的名字）")
            missing.add("询问用户称呼")
            resetFlags.add("introducedSelf=true")
            resetFlags.add("askedForNickname=true")
        } else {
            if (!meta.introducedSelf) {
                missing.add("自我介绍（让用户知道你的名字）")
                resetFlags.add("introducedSelf=true")
            }
            if (!meta.askedForNickname) {
                missing.add("询问用户称呼")
                resetFlags.add("askedForNickname=true")
            }
        }
        return buildString {
            appendLine("【首次见面校验失败·必须纠正】")
            appendLine("上一轮回复缺少以下目标：${missing.joinToString("、")}")
            appendLine("请重新生成首次见面回复，必须补齐上述缺失目标：")
            appendLine("1. 在 replies 中自然完成缺失的目标（不要重复已达成的内容）")
            appendLine("2. 在 firstMeetingMeta 中将 ${resetFlags.joinToString(" 和 ")} 标记为 true")
            appendLine("保持 2-3 条短消息连发，每条 10-20 字。")
        }
    }

    /**
     * 最小兜底问句（重试仍失败时使用）
     *
     * 直接构造一个合格的首次问候回复，不再调 LLM。
     * 包含自我介绍 + 询问称呼，满足两个核心目标。
     */
    fun buildFallbackReply(agentName: String): AgentReply {
        return AgentReply(
            replies = listOf(
                ReplyItem(
                    replyText = "嗨～我是$agentName",
                    emotion = "happy"
                ),
                ReplyItem(
                    replyText = "你叫什么名字呀？",
                    emotion = "happy"
                )
            ),
            firstMeetingMeta = FirstMeetingMeta(
                introducedSelf = true,
                askedForNickname = true
            )
        )
    }
}
