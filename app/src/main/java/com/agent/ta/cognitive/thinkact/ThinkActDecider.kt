package com.agent.ta.cognitive.thinkact

import android.util.Log
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.remote.LlmClient
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.infrastructure.observer.ObserverSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Think/Act 决策器（L2 认知层）
 *
 * 设计参考：MochiBot 的 Think/Act 解耦
 *
 * 与 MochiBot 的关键差异：
 * - MochiBot 的 Think 输出直接驱动 Act，缺少 [SKIP] 否决场景
 * - 本项目 Think 阶段有 [SKIP] 否决权，避免骚扰用户
 *
 * Think 阶段（无状态扫描器）：
 * - 输入：观察者数据 + today_proactive_sent + prior_attempts
 * - 输出：JSON findings 或 [SKIP]
 * - 规则：
 *   * 用户 30 分钟内发过消息，且无特别话题 → [SKIP]
 *   * prior_attempts 失败 2 次以上 → [SKIP]
 *   * 23:00-08:00 静音时段 → [SKIP]
 *
 * Act 阶段（persona 呈现）：
 * - 输入：ThinkResult + AgentConfig + 当前状态
 * - 输出：最终回复文本
 *
 * 使用场景：Heartbeat 检测到状态变化时调用
 */
class ThinkActDecider(
    private val llmClient: LlmClient
) {

    /**
     * Think 阶段：判断是否适合主动发起
     *
     * @param observerSnapshots 观察者快照（来自 Heartbeat 的 collectChanged）
     * @param todayProactiveSent 今日已主动发起次数
     * @param priorAttempts 最近失败尝试次数
     * @return ThinkResult.SKIP 表示不发起，否则包含 topic 和 reason
     */
    suspend fun think(
        observerSnapshots: List<ObserverSnapshot>,
        todayProactiveSent: Int,
        priorAttempts: Int
    ): ThinkResult {
        return withContext(Dispatchers.IO) {
            try {
                // 快速否决规则（不调 LLM，省 Token）
                val quickVeto = checkQuickVeto(observerSnapshots, todayProactiveSent, priorAttempts)
                if (quickVeto != null) {
                    Log.d(TAG, "Think 快速否决：${quickVeto.reason}")
                    return@withContext quickVeto
                }

                // 调用 LLM 进行深度判断
                val prompt = buildThinkPrompt(observerSnapshots, todayProactiveSent, priorAttempts)
                val messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", "请输出 JSON 决策结果")
                )

                val response = llmClient.chat(messages)
                parseThinkResult(response.replyText)
            } catch (e: Exception) {
                Log.e(TAG, "Think 阶段异常: ${e.message}", e)
                ThinkResult.SKIP
            }
        }
    }

    /**
     * 快速否决检查（不调 LLM）
     *
     * 返回非 null 表示直接 SKIP，不进入 LLM 判断
     */
    private fun checkQuickVeto(
        snapshots: List<ObserverSnapshot>,
        todayProactiveSent: Int,
        priorAttempts: Int
    ): ThinkResult? {
        // 1. 静音时段检查（23:00-08:00）
        val hour = java.time.LocalTime.now().hour
        if (hour >= SILENT_START || hour < SILENT_END) {
            return ThinkResult.skip("静音时段($hour:00)")
        }

        // 2. 失败次数检查
        if (priorAttempts >= MAX_PRIOR_ATTEMPTS) {
            return ThinkResult.skip("最近失败 $priorAttempts 次")
        }

        // 3. 今日主动发起次数上限
        if (todayProactiveSent >= MAX_PROACTIVE_PER_DAY) {
            return ThinkResult.skip("今日已达上限 $MAX_PROACTIVE_PER_DAY 次")
        }

        // 4. 用户最近活跃检查（30 分钟内发过消息）
        val recentConversation = snapshots.find { it.observerId == "recent_conversation" }
        if (recentConversation != null) {
            val isUserSilent = recentConversation.data["is_user_silent"] as? Boolean ?: false
            val silenceMinutes = recentConversation.data["silence_minutes"] as? Long ?: 0L

            // 用户在线活跃时，除非有特别话题，否则不主动打扰
            if (!isUserSilent && silenceMinutes < USER_ACTIVE_THRESHOLD_MINUTES) {
                return ThinkResult.skip("用户 ${silenceMinutes}分钟前刚发过消息")
            }
        }

        // 5. 状态检查：UNAVAILABLE 不主动发
        val currentState = com.agent.ta.service.AgentEngine.currentState.value
        if (currentState == AgentState.UNAVAILABLE) {
            return ThinkResult.skip("当前状态不可回复(${currentState.displayName})")
        }

        return null
    }

    /**
     * 构造 Think Prompt
     */
    private fun buildThinkPrompt(
        snapshots: List<ObserverSnapshot>,
        todayProactiveSent: Int,
        priorAttempts: Int
    ): String {
        return buildString {
            appendLine("你是 Agent 的 Think 模块。基于以下观察数据判断是否适合主动发起对话。")
            appendLine()
            appendLine("【观察数据】")
            snapshots.forEach { snapshot ->
                if (snapshot.promptHint.isNotBlank()) {
                    appendLine(snapshot.promptHint)
                }
            }
            appendLine()
            appendLine("【今日已主动发起次数】$todayProactiveSent")
            appendLine("【最近失败尝试】$priorAttempts 次")
            appendLine()
            appendLine("【决策规则】")
            appendLine("- 如果用户 30 分钟内发过消息，且当前无特别话题，输出 SKIP")
            appendLine("- 如果 prior_attempts 失败 2 次以上，输出 SKIP")
            appendLine("- 适合主动发起的场景：用户长时间未响应、跨时段切换、特殊事件")
            appendLine("- 主动发起话题要自然，不要生硬")
            appendLine()
            appendLine("【输出格式】严格输出以下 JSON（不要 markdown 代码块）：")
            appendLine("""{"should_act": true/false, "topic": "话题简述", "reason": "决策理由"}""")
            appendLine("如果 should_act=false，topic 留空。")
        }
    }

    /**
     * Act 阶段：基于 persona 呈现 Think 阶段的话题
     *
     * 与 MochiBot 的关键差异：
     * - MochiBot Act 阶段直接调 LLM 生成回复
     * - 本项目 Act 阶段只构造"话题引导"，最终回复由 ChatInteractor 走完整 PromptBuilder 流程生成
     *   （避免绕过 ActivityAnchor / 一致性校验 / 状态锚定，保证主动发起回复与被动回复同等质量）
     *
     * Act 不调 LLM，只做结构化转换：
     * - 输入：ThinkResult（topic/reason/findings） + AgentConfig（persona） + 当前状态 + 观察者快照
     * - 输出：ActResult（topicHint 注入 PromptBuilder Zone C，引导 LLM 基于 persona 自然呈现话题）
     *
     * @param thinkResult Think 阶段输出（shouldAct=true 才进入 Act）
     * @param config Agent 配置（用于读取 persona 兴趣/口头禅等）
     * @param state 当前状态
     * @param observerSnapshots 观察者快照（提供实时上下文）
     * @return ActResult 包含话题引导字符串；shouldProceed=false 表示 Act 阶段否决（极少见）
     */
    fun act(
        thinkResult: ThinkResult,
        config: AgentConfig,
        state: AgentState,
        observerSnapshots: List<ObserverSnapshot> = emptyList()
    ): ActResult {
        if (!thinkResult.shouldAct) {
            return ActResult(shouldProceed = false, topicHint = "")
        }

        val persona = config.agent.persona
        val identity = config.identity

        val topicHint = buildString {
            appendLine("【Think 模块决策】")
            appendLine("话题方向：${thinkResult.topic}")
            if (thinkResult.reason.isNotBlank()) {
                appendLine("决策理由：${thinkResult.reason}")
            }
            appendLine()

            // 注入 persona 兴趣引导（让 LLM 知道可以结合哪些兴趣展开）
            if (persona.interests.isNotEmpty()) {
                appendLine("可结合你的兴趣话题：${persona.interests.take(3).joinToString("、")}")
            }

            // 口头禅引导（让主动发起也保持 persona 风格）
            if (persona.catchphrases.isNotEmpty()) {
                appendLine("自然融入口头禅（不要硬加）：${persona.catchphrases.joinToString(" / ")}")
            }

            // 自称引导
            if (persona.selfNickname.isNotBlank()) {
                appendLine("可用自称：${persona.selfNickname}")
            }

            // 状态上下文引导
            appendLine("当前状态：${state.displayName}（回复风格需与此状态一致）")

            // 观察者上下文摘要
            val observerHint = observerSnapshots
                .mapNotNull { it.promptHint.takeIf(String::isNotBlank) }
                .joinToString("\n")
            if (observerHint.isNotBlank()) {
                appendLine()
                appendLine("【实时观察上下文】")
                appendLine(observerHint)
            }

            // 身份内核引导（若有 v2 identity）
            if (identity.personalityCore.isNotBlank()) {
                appendLine()
                appendLine("【人格内核提醒】${identity.personalityCore}")
            }
        }

        return ActResult(
            shouldProceed = true,
            topicHint = topicHint
        )
    }

    /**
     * 解析 LLM 返回的 Think 结果
     */
    private fun parseThinkResult(replyText: String): ThinkResult {
        return try {
            // 剥离可能的 ```json ``` 代码块
            val cleaned = replyText.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = org.json.JSONObject(cleaned)
            val shouldAct = json.optBoolean("should_act", false)
            val topic = json.optString("topic", "")
            val reason = json.optString("reason", "")

            if (shouldAct && topic.isNotBlank()) {
                ThinkResult(shouldAct = true, topic = topic, reason = reason)
            } else {
                ThinkResult.skip("LLM 判定不发起: $reason")
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 Think 结果失败，降级为 SKIP: ${e.message}")
            ThinkResult.skip("解析失败")
        }
    }

    /**
     * Think 结果数据类
     */
    data class ThinkResult(
        val shouldAct: Boolean,
        val topic: String = "",
        val reason: String = "",
        val findings: String = ""
    ) {
        companion object {
            val SKIP = ThinkResult(shouldAct = false, reason = "默认跳过")

            fun skip(reason: String) = ThinkResult(shouldAct = false, reason = reason)
        }
    }

    /**
     * Act 结果数据类
     *
     * topicHint 会作为引导注入 PromptBuilder Zone C 的主动发起场景，
     * 让 LLM 基于 persona 自然呈现话题（而非机械执行 Think 输出）。
     */
    data class ActResult(
        val shouldProceed: Boolean,
        val topicHint: String
    )

    companion object {
        private const val TAG = "ThinkActDecider"

        /** 静音时段：23:00-08:00 不主动发 */
        private const val SILENT_START = 23
        private const val SILENT_END = 8

        /** 最近失败尝试上限 */
        private const val MAX_PRIOR_ATTEMPTS = 2

        /** 每日主动发起上限 */
        private const val MAX_PROACTIVE_PER_DAY = 8

        /** 用户活跃阈值（分钟）：30 分钟内发过消息视为活跃 */
        private const val USER_ACTIVE_THRESHOLD_MINUTES = 30L
    }
}
