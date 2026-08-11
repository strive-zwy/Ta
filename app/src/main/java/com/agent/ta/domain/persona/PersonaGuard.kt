package com.agent.ta.domain.persona

import com.agent.ta.data.remote.dto.ReplyItem

/**
 * Module 5: Persona Guard（代码层后置守卫）
 *
 * 检测 Agent 回复是否过度表达角色标志性特征，检测不合格时由调用方（ChatInteractor）
 * 触发重新生成（追加"减少标志性词汇"指令，最多重试 1 次）。
 *
 * 检测维度：
 * - marker 密度：单条 reply 内 marker 词出现次数 > markerMaxFrequency → FLAG
 * - 语义机械度：marker 是否被"强贴"（如"今天吃什么？这可是一次赌局"），
 *   用 mark 前后是否紧邻高频机械连接词近似判断；合理语境（如"人生本来就是一场赌局"）不算
 * - 跨轮重复：某 marker 最近几轮已频繁出现，本轮再出现即 FLAG
 */
data class GuardCheckResult(
    val isFlagged: Boolean,
    val reason: String? = null,
    /** 命中 items 的下标 */
    val flaggedItems: List<Int> = emptyList()
)

object PersonaGuard {

    /**
     * 高频机械连接词，紧邻 marker 出现时提示"强贴"。
     * 用于区分：合理语境 vs 生硬贴标签。
     */
    private val MECHANICAL_NEIGHBORS = listOf(
        "这", "可", "就是", "也是一次", "就像", "不过", "然而", "顺便说", "对了", "说起来"
    )

    /**
     * 检测回复是否过度表达角色标志性特征。
     *
     * @param model 人格模型（含 markers）
     * @param items 待入库的 reply items
     * @param recentMarkerFreq 最近几轮各 marker 出现次数（跨轮重复检测）
     */
    fun check(
        model: PersonaModel,
        items: List<ReplyItem>,
        recentMarkerFreq: Map<String, Int> = emptyMap()
    ): GuardCheckResult {
        if (model.lexicalMarkers.isEmpty() || items.isEmpty()) {
            return GuardCheckResult(isFlagged = false)
        }

        val reasons = mutableListOf<String>()
        val flaggedItems = mutableListOf<Int>()

        // 每个 trait 的 marker → 允许的最大次数
        val markerMaxFreq = model.traits
            .flatMap { trait -> trait.lexicalMarkers.map { marker -> marker to trait.markerMaxFrequency } }
            .toMap()

        items.forEachIndexed { index, item ->
            val text = item.replyText
            if (text.isBlank()) return@forEachIndexed

            // 1. marker 密度检测（单条内）
            for (marker in model.lexicalMarkers) {
                val count = text.countMarker(marker)
                if (count == 0) continue

                val maxAllowed = markerMaxFreq[marker] ?: 1
                // 若该 marker 在模型里被标记为"默认 maxFrequency=0"（如 neutral），任何出现都算过密
                if (maxAllowed <= 0) {
                    reasons.add("marker密度超标: $marker x$count")
                    flaggedItems.add(index)
                    break
                }
                if (count > maxAllowed) {
                    reasons.add("marker密度超标: $marker x$count")
                    flaggedItems.add(index)
                    break
                }

                // 2. 语义机械度检测：marker 被强贴（紧邻机械连接词）
                if (isMechanicallyPasted(text, marker)) {
                    reasons.add("机械贴标签: $marker")
                    flaggedItems.add(index)
                    break
                }
            }
        }

        // 3. 跨轮重复检测：某 marker 最近已频繁出现，本轮又出现
        for (marker in model.lexicalMarkers) {
            val recentCount = recentMarkerFreq[marker] ?: 0
            if (recentCount >= CROSS_ROUND_THRESHOLD) {
                val appearsThisRound = items.any { it.replyText.contains(marker) }
                if (appearsThisRound) {
                    reasons.add("跨轮重复: $marker 近${recentCount}轮已频繁出现")
                    flaggedItems.addAll(items.indices.filter { items[it].replyText.contains(marker) })
                }
            }
        }

        return if (flaggedItems.isEmpty()) {
            GuardCheckResult(isFlagged = false)
        } else {
            GuardCheckResult(
                isFlagged = true,
                reason = reasons.distinct().joinToString("; "),
                flaggedItems = flaggedItems.distinct()
            )
        }
    }

    /** 跨轮重复阈值：某 marker 最近几轮已出现此次数，本轮再出现即 FLAG */
    private const val CROSS_ROUND_THRESHOLD = 2

    /** 统计 marker 在文本中出现的次数（子串匹配，独立完整词） */
    private fun String.countMarker(marker: String): Int {
        if (marker.isBlank()) return 0
        var count = 0
        var idx = 0
        while (idx < length) {
            val found = indexOf(marker, idx)
            if (found < 0) break
            count++
            idx = found + marker.length
        }
        return count
    }

    /**
     * 判断 marker 是否被"强贴"（前一位是机械连接词，或与前文无语义关联）。
     * 简化：仅检查 marker 前 1-2 个字符是否命中机械连接词开头。
     */
    private fun isMechanicallyPasted(text: String, marker: String): Boolean {
        val idx = text.indexOf(marker)
        if (idx < 0) return false
        val before = text.substring(0, idx).takeLast(2)
        return MECHANICAL_NEIGHBORS.any { before.contains(it) }
    }
}