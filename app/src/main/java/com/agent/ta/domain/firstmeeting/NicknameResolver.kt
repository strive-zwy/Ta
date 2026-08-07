package com.agent.ta.domain.firstmeeting

import com.agent.ta.data.remote.dto.NicknameResolution

/**
 * 称呼解析器（Task 14）
 *
 * 职责：
 * 1. 解析 LLM 输出的 [NicknameResolution]：处理缺字段、未知 intent、越界 confidence 和恶意格式
 * 2. 判断是否保存称呼：四道关卡（intent / shouldSave / confidence / 本地校验）
 * 3. 判断是否清空称呼：CLEAR intent 或本地清空意图
 * 4. 判断是否需要确认：SELF_INTRODUCTION 不直接保存，Agent 自然确认"以后叫你 X 可以吗"
 *
 * 设计原则：
 * - LLM 在同一次普通回复中输出 nicknameResolution，避免额外调用导致回复与提取不一致
 * - 本地校验作为最后一道防线，即使 LLM 被诱导也不能写入非法称呼
 * - 首次见面时基于本轮连续用户消息整体判断
 */
object NicknameResolver {

    /** 允许保存的 intent 集合 */
    private val SAVE_ALLOWED_INTENTS = setOf("EXPLICIT_NICKNAME", "CORRECTION")

    /** 全部合法 intent 集合 */
    private val VALID_INTENTS = setOf(
        "EXPLICIT_NICKNAME", "SELF_INTRODUCTION", "DECLINED",
        "AMBIGUOUS", "CORRECTION", "CLEAR", "NONE"
    )

    /** 允许保存的最低置信度阈值 */
    private const val MIN_SAVE_CONFIDENCE = 0.85f

    /**
     * 解析并清洗 LLM 输出的 [NicknameResolution]
     *
     * 处理：
     * - null 输入 → 默认 NONE
     * - 未知 intent → NONE，并重置 shouldSave=false
     * - confidence 越界（含 NaN / 无穷）→ 钳制到 [0, 1]
     * - 其他字段原样保留，由 [decideSave] 做进一步校验
     */
    fun parse(raw: NicknameResolution?): NicknameResolution {
        if (raw == null) return NicknameResolution()

        val validIntent = if (raw.intent in VALID_INTENTS) raw.intent else "NONE"
        // 未知 intent 时强制重置 shouldSave，防止 LLM 误标
        val shouldSave = if (validIntent == "NONE") false else raw.shouldSave

        // 钳制 confidence：NaN → 0，无穷 → 1，否则夹到 [0, 1]
        val clampedConfidence = when {
            raw.confidence.isNaN() -> 0f
            raw.confidence.isInfinite() -> if (raw.confidence > 0) 1f else 0f
            else -> raw.confidence.coerceIn(0f, 1f)
        }

        return raw.copy(
            intent = validIntent,
            shouldSave = shouldSave,
            confidence = clampedConfidence
        )
    }

    /**
     * 保存判定：综合 intent / shouldSave / confidence / 本地校验 四道关卡
     *
     * 只有同时满足以下条件才允许保存：
     * 1. intent 为 EXPLICIT_NICKNAME 或 CORRECTION
     * 2. shouldSave = true
     * 3. confidence >= 0.85
     * 4. NicknameValidator.validate 通过（含清洗、长度、格式、无意义值校验）
     *
     * @return [SaveDecision] 包含是否保存、清洗后的称呼和拒绝原因
     */
    fun decideSave(resolution: NicknameResolution): SaveDecision {
        // 1. intent 检查
        if (resolution.intent !in SAVE_ALLOWED_INTENTS) {
            return SaveDecision(
                shouldSave = false,
                normalizedNickname = null,
                reason = "intent=${resolution.intent} 不在允许保存的集合 $SAVE_ALLOWED_INTENTS 内"
            )
        }

        // 2. shouldSave 检查
        if (!resolution.shouldSave) {
            return SaveDecision(
                shouldSave = false,
                normalizedNickname = null,
                reason = "shouldSave=false"
            )
        }

        // 3. confidence 阈值检查
        if (resolution.confidence < MIN_SAVE_CONFIDENCE) {
            return SaveDecision(
                shouldSave = false,
                normalizedNickname = null,
                reason = "confidence=${resolution.confidence} 低于阈值 $MIN_SAVE_CONFIDENCE"
            )
        }

        // 4. nickname 非空检查
        val rawNickname = resolution.nickname
        if (rawNickname.isNullOrBlank()) {
            return SaveDecision(
                shouldSave = false,
                normalizedNickname = null,
                reason = "nickname 为空"
            )
        }

        // 5. 本地校验
        val validationResult = NicknameValidator.validate(rawNickname)

        // 本地校验识别为清空意图 → 不保存，交由 shouldClear 处理
        if (validationResult.isClear) {
            return SaveDecision(
                shouldSave = false,
                normalizedNickname = null,
                reason = "本地校验识别为清空意图"
            )
        }

        if (!validationResult.isValid) {
            return SaveDecision(
                shouldSave = false,
                normalizedNickname = null,
                reason = "本地校验失败：${validationResult.reason}"
            )
        }

        // 全部通过
        return SaveDecision(
            shouldSave = true,
            normalizedNickname = validationResult.normalized,
            reason = "通过"
        )
    }

    /**
     * 清空判定：CLEAR intent 或本地清空意图
     *
     * 即使 LLM 标为 EXPLICIT_NICKNAME，如果 nickname 本身是清空短语（如"直接叫你就行"），
     * 也应识别为清空意图。
     */
    fun shouldClear(resolution: NicknameResolution): Boolean {
        if (resolution.intent == "CLEAR") return true

        val rawNickname = resolution.nickname
        if (!rawNickname.isNullOrBlank()) {
            return NicknameValidator.isClearIntent(rawNickname)
        }
        return false
    }

    /**
     * 确认判定：SELF_INTRODUCTION 不直接保存
     *
     * LLM 识别为 SELF_INTRODUCTION 时，Agent 应自然确认"以后叫你 X 可以吗"，
     * 等用户明确同意后才转为 EXPLICIT_NICKNAME 保存。
     */
    fun needsConfirmation(resolution: NicknameResolution): Boolean {
        return resolution.intent == "SELF_INTRODUCTION"
    }

    /**
     * 保存决策结果
     *
     * @param shouldSave 是否应该保存
     * @param normalizedNickname 清洗后的称呼（仅 shouldSave=true 时非 null）
     * @param reason 拒绝原因（shouldSave=false 时有意义）
     */
    data class SaveDecision(
        val shouldSave: Boolean,
        val normalizedNickname: String?,
        val reason: String
    )
}
