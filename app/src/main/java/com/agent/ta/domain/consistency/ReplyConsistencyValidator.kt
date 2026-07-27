package com.agent.ta.domain.consistency

import android.util.Log
import com.agent.ta.data.remote.dto.AgentReply
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.data.remote.dto.ReplyItem
import com.agent.ta.domain.anchor.ActivityAnchor

/**
 * 回复一致性校验引擎
 *
 * 在 LLM 输出后、持久化前校验回复内容，检测逻辑矛盾：
 * 1. 活动矛盾：reply 提到与 ActivityAnchor 不同的活动
 * 2. 多 reply 一致性：同一轮多条 reply 之间相互矛盾
 * 3. 跨轮一致性：本轮 reply 与近期历史 reply 矛盾
 *
 * 校验失败时返回修正指令，ChatInteractor 追加到 messages 末尾重试 LLM（最多 2 次）。
 *
 * 设计原则：
 * - 规则基于关键词匹配，不调 LLM（快速、零成本）
 * - 宁可漏检不可误报（避免阻断正常回复）
 * - 只检测明确矛盾，不检测语义模糊
 */
class ReplyConsistencyValidator {

    /**
     * 校验结果
     */
    data class ValidationResult(
        val passed: Boolean,
        val issues: List<String> = emptyList(),
        /** 追加到重试 prompt 的修正指令（校验失败时） */
        val correctionHint: String = ""
    ) {
        companion object {
            val PASS = ValidationResult(passed = true)
        }
    }

    /**
     * 校验 LLM 回复
     *
     * @param reply LLM 输出的回复
     * @param activityAnchor 当前活动锚点（应用侧权威状态）
     * @param recentHistory 最近对话历史（用于跨轮一致性检查）
     * @return 校验结果
     */
    fun validate(
        reply: AgentReply,
        activityAnchor: ActivityAnchor?,
        recentHistory: List<ChatMessage>
    ): ValidationResult {
        val issues = mutableListOf<String>()

        // 展开所有 reply 文本
        val replyTexts = extractReplyTexts(reply)
        if (replyTexts.isEmpty()) return ValidationResult.PASS

        // 1. 活动矛盾检查
        val activityIssue = checkActivityContradiction(replyTexts, activityAnchor)
        if (activityIssue != null) {
            issues.add(activityIssue)
        }

        // 2. 多 reply 一致性检查
        val multiReplyIssue = checkMultiReplyConsistency(replyTexts)
        if (multiReplyIssue != null) {
            issues.add(multiReplyIssue)
        }

        // 3. 跨轮一致性检查
        val crossTurnIssue = checkCrossTurnConsistency(replyTexts, recentHistory, activityAnchor)
        if (crossTurnIssue != null) {
            issues.add(crossTurnIssue)
        }

        if (issues.isEmpty()) {
            return ValidationResult.PASS
        }

        // 构造修正指令
        val correctionHint = buildCorrectionHint(issues, activityAnchor)
        Log.w(TAG, "回复一致性校验失败：${issues.joinToString("; ")}")
        return ValidationResult(
            passed = false,
            issues = issues,
            correctionHint = correctionHint
        )
    }

    /**
     * 提取所有 reply 文本（兼容单条 replyText 和多条 replies）
     */
    private fun extractReplyTexts(reply: AgentReply): List<String> {
        val texts = mutableListOf<String>()
        if (reply.replies.isNotEmpty()) {
            reply.replies.forEach { item ->
                if (item.replyText.isNotBlank()) {
                    texts.add(item.replyText)
                }
            }
        } else if (reply.replyText.isNotBlank()) {
            texts.add(reply.replyText)
        }
        return texts
    }

    /**
     * 检查活动矛盾：reply 提到与锚点不同的活动
     *
     * 检测逻辑：
     * - 从 reply 文本中提取提到的活动关键词
     * - 与锚点活动比较
     * - 如果提到不同的活动（且不是"接下来要去"等未来时态），标记矛盾
     */
    private fun checkActivityContradiction(
        replyTexts: List<String>,
        anchor: ActivityAnchor?
    ): String? {
        if (anchor == null || anchor.activity.isBlank()) return null

        val anchorActivity = anchor.activity
        val anchorKeywords = extractActivityKeywords(anchorActivity)

        // 收集所有 reply 中提到的活动
        val mentionedActivities = mutableSetOf<String>()
        replyTexts.forEach { text ->
            ACTIVITY_KEYWORDS.forEach { (keyword, activity) ->
                if (text.contains(keyword) && !isFutureTense(text, keyword)) {
                    mentionedActivities.add(activity)
                }
            }
        }

        // 过滤掉与锚点相同的活动
        val differentActivities = mentionedActivities.filter { mentioned ->
            anchorKeywords.none { anchorKw ->
                ACTIVITY_SYNONYMS[anchorKw]?.any { it in mentioned } == true ||
                mentioned.contains(anchorKw) || anchorKw.contains(mentioned)
            }
        }

        return if (differentActivities.isNotEmpty()) {
            "回复中提到「${differentActivities.joinToString("、")}」，但当前活动锚点是「$anchorActivity」，存在活动矛盾"
        } else null
    }

    /**
     * 检查多 reply 一致性：同一轮多条 reply 之间是否矛盾
     *
     * 检测逻辑：
     * - 多条 reply 提到不同活动
     * - 多条 reply 时间状态矛盾（如"快结束" + "刚开始"）
     */
    private fun checkMultiReplyConsistency(replyTexts: List<String>): String? {
        if (replyTexts.size < 2) return null

        // 检测不同活动
        val activitiesPerReply = replyTexts.map { text ->
            ACTIVITY_KEYWORDS.entries
                .filter { (keyword, _) -> text.contains(keyword) && !isFutureTense(text, keyword) }
                .map { it.value }
                .toSet()
        }

        // 找出 reply 间不同的活动
        for (i in activitiesPerReply.indices) {
            for (j in (i + 1) until activitiesPerReply.size) {
                val set1 = activitiesPerReply[i]
                val set2 = activitiesPerReply[j]
                if (set1.isNotEmpty() && set2.isNotEmpty()) {
                    val onlyIn1 = set1 - set2
                    val onlyIn2 = set2 - set1
                    if (onlyIn1.isNotEmpty() && onlyIn2.isNotEmpty()) {
                        return "同一轮 reply 中提到不同活动：第${i + 1}条「${onlyIn1.joinToString("、")}」vs 第${j + 1}条「${onlyIn2.joinToString("、")}」，存在矛盾"
                    }
                }
            }
        }

        // 检测时间状态矛盾
        val timeStates = replyTexts.map { text ->
            when {
                text.contains(Regex("快结束|马上完|快完了|还有.*分钟")) -> "ending"
                text.contains(Regex("刚开始|刚做|正要开始|准备开始")) -> "starting"
                else -> "middle"
            }
        }
        if (timeStates.contains("ending") && timeStates.contains("starting")) {
            return "同一轮 reply 中时间状态矛盾：既有「快结束」又有「刚开始」"
        }

        return null
    }

    /**
     * 检查跨轮一致性：本轮 reply 与近期历史是否矛盾
     *
     * 检测逻辑：
     * - 从历史中提取最近 assistant 消息提到的活动
     * - 与本轮 reply 提到的活动比较
     * - 如果历史说"去洗澡了"，本轮说"还在健身"，标记矛盾
     */
    private fun checkCrossTurnConsistency(
        replyTexts: List<String>,
        recentHistory: List<ChatMessage>,
        anchor: ActivityAnchor?
    ): String? {
        // 取最近 3 条 assistant 消息
        val recentAssistantTexts = recentHistory
            .filter { it.role == "assistant" && it.content.isNotBlank() }
            .takeLast(3)

        if (recentAssistantTexts.isEmpty()) return null

        // 提取历史中提到的活动
        val historyActivities = mutableSetOf<String>()
        recentAssistantTexts.forEach { msg ->
            ACTIVITY_KEYWORDS.forEach { (keyword, activity) ->
                if (msg.content.contains(keyword) && !isFutureTense(msg.content, keyword)) {
                    historyActivities.add(activity)
                }
            }
        }

        if (historyActivities.isEmpty()) return null

        // 提取本轮 reply 提到的活动
        val currentActivities = mutableSetOf<String>()
        replyTexts.forEach { text ->
            ACTIVITY_KEYWORDS.forEach { (keyword, activity) ->
                if (text.contains(keyword) && !isFutureTense(text, keyword)) {
                    currentActivities.add(activity)
                }
            }
        }

        if (currentActivities.isEmpty()) return null

        // 检测矛盾：历史和当前提到完全不同的活动
        // 例外：如果锚点活动已切换（时段切换），允许活动变化
        if (anchor != null) {
            val anchorKeywords = extractActivityKeywords(anchor.activity)
            val anchorMatchesHistory = historyActivities.any { histAct ->
                anchorKeywords.any { kw -> histAct.contains(kw) || kw.contains(histAct) }
            }
            // 锚点与历史不一致 → 时段已切换，跳过跨轮检查
            if (!anchorMatchesHistory) {
                return null
            }
        }

        // 锚点与历史一致，但当前 reply 提到不同活动 → 矛盾
        val differentActivities = currentActivities.filter { currAct ->
            historyActivities.none { histAct ->
                ACTIVITY_SYNONYMS.entries.any { (synKey, synValues) ->
                    (synKey in histAct || histAct.contains(synKey)) && synValues.any { it in currAct }
                } || currAct.contains(histAct) || histAct.contains(currAct)
            }
        }

        return if (differentActivities.isNotEmpty()) {
            "历史回复中提到「${historyActivities.joinToString("、")}」，但本轮回复提到「${differentActivities.joinToString("、")}」，跨轮活动矛盾"
        } else null
    }

    /**
     * 判断关键词在文本中是否为未来时态（"等下去XX"、"接下来要XX"）
     *
     * 未来时态的活动不算当前活动，不构成矛盾
     */
    private fun isFutureTense(text: String, keyword: String): Boolean {
        val futurePatterns = listOf(
            "等下.*$keyword",
            "接下来.*$keyword",
            "马上.*$keyword",
            "准备.*$keyword",
            "要去.*$keyword",
            "快结束了.*$keyword",
            "忙完.*$keyword",
            "做完.*$keyword"
        )
        return futurePatterns.any { pattern ->
            Regex(pattern).containsMatchIn(text)
        }
    }

    /**
     * 从活动描述中提取关键词
     */
    private fun extractActivityKeywords(activity: String): Set<String> {
        val keywords = mutableSetOf<String>()
        ACTIVITY_KEYWORDS.keys.forEach { keyword ->
            if (activity.contains(keyword)) {
                keywords.add(keyword)
            }
        }
        // 如果没匹配到预设关键词，用活动描述本身
        if (keywords.isEmpty() && activity.isNotBlank()) {
            keywords.add(activity.take(2))
        }
        return keywords
    }

    /**
     * 构造修正指令（追加到重试 prompt）
     */
    private fun buildCorrectionHint(issues: List<String>, anchor: ActivityAnchor?): String {
        val sb = StringBuilder()
        sb.appendLine("【系统校验发现以下问题，请修正后重新回复】")
        issues.forEach { issue ->
            sb.appendLine("- $issue")
        }
        if (anchor != null) {
            sb.appendLine()
            sb.appendLine("当前活动锚点（权威事实）：「${anchor.activity}」（${anchor.state.displayName}）")
            sb.appendLine("进度：${anchor.progressDescription()}")
            sb.appendLine("请基于此活动锚点重新组织回复，确保：")
            sb.appendLine("1. 所有 reply 围绕「${anchor.activity}」展开，不提其他活动")
            sb.appendLine("2. 多条 reply 之间逻辑一致，不矛盾")
            sb.appendLine("3. 与对话历史中你之前说过的活动状态保持一致")
            sb.appendLine("4. 如果确实改变了活动，请调用 set_activity 工具更新系统记录")
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "ReplyValidator"

        /**
         * 活动关键词映射（关键词 → 活动名称）
         *
         * 用于从回复文本中提取提到的活动
         */
        private val ACTIVITY_KEYWORDS: Map<String, String> = mapOf(
            // 日常生活活动
            "洗澡" to "洗澡",
            "洗漱" to "洗漱",
            "健身" to "健身",
            "运动" to "健身",
            "锻炼" to "健身",
            "跑步" to "跑步",
            "工作" to "工作",
            "写代码" to "写代码",
            "编码" to "写代码",
            "编程" to "写代码",
            "开会" to "开会",
            "睡觉" to "睡觉",
            "睡了" to "睡觉",
            "休息" to "休息",
            "吃饭" to "吃饭",
            "吃早饭" to "吃饭",
            "吃午饭" to "吃饭",
            "吃晚饭" to "吃饭",
            "做饭" to "做饭",
            "游戏" to "游戏",
            "打游戏" to "游戏",
            "看书" to "看书",
            "阅读" to "看书",
            "学习" to "学习",
            "聊天" to "聊天",
            "看电影" to "看电影",
            "听音乐" to "听音乐",
            "逛街" to "逛街",
            "购物" to "购物",
            "化妆" to "化妆",
            "护肤" to "护肤",
            "通勤" to "通勤",
            "出门" to "出门",
            "上班" to "工作",
            "下班" to "下班"
        )

        /**
         * 活动同义词映射（主关键词 → 同义活动列表）
         *
         * 用于判断两个不同关键词是否指同一活动
         */
        private val ACTIVITY_SYNONYMS: Map<String, List<String>> = mapOf(
            "健身" to listOf("健身", "运动", "锻炼"),
            "工作" to listOf("工作", "上班", "写代码", "编码", "编程", "开会"),
            "睡觉" to listOf("睡觉", "睡了", "休息"),
            "吃饭" to listOf("吃饭", "吃早饭", "吃午饭", "吃晚饭"),
            "看书" to listOf("看书", "阅读", "学习"),
            "游戏" to listOf("游戏", "打游戏")
        )
    }
}
