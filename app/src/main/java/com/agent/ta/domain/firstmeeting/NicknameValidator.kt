package com.agent.ta.domain.firstmeeting

/**
 * 称呼本地校验器（Task 13）
 *
 * 职责：
 * - 清洗用户输入：去空格、成对引号、"叫我/称呼我为/就行"等修饰词
 * - 校验长度（1-12 可见字符）
 * - 拒绝异常格式：换行、URL、JSON、代码片段、纯标点、纯 emoji
 * - 拒绝无意义值："随便""都行""你猜""不知道""以后再说"等
 * - 支持明确清空称呼的独立结果，不把空字符串当校验失败
 *
 * 设计原则：纯本地、确定性、无 LLM 依赖，用于在 NicknameResolver 之后做最后一道防线。
 */
object NicknameValidator {

    /** 称呼最大长度（可见字符） */
    private const val MAX_LENGTH = 12

    /** 清空意图的关键词（在输入中匹配到任意一个即视为清空） */
    private val CLEAR_PHRASES = listOf(
        "直接叫你就", "叫你就行", "叫我就行", "不用叫", "清空称呼",
        "不需要称呼", "不需要叫", "不用了", "不需要了",
        "清除称呼", "取消称呼", "删除称呼"
    )

    /** 无意义值集合（精确匹配，清洗后比对） */
    private val MEANINGLESS_VALUES = setOf(
        "随便", "都行", "你猜", "不知道", "以后再说", "无所谓",
        "随便吧", "都行吧", "随便啦", "都行啦", "随便咯", "都行咯",
        "不知道啊", "不知道呀", "不知道哦", "不知道啦", "不知道咯",
        "随便了", "都行了", "随便哈", "都行哈",
        "就行", "就好", "就可以了", "好了"
    )

    /** 代码片段特征（包含即视为代码） */
    private val CODE_PATTERNS = listOf(
        "val ", "var ", "fun ", "def ", "console.log", "print(", "printf(",
        "System.out", "import ", "package ", "console.", "alert(",
        "document.", "window.", "return ", "function ", "==", "!="
    )

    /**
     * 校验结果
     *
     * @param normalized 清洗后的称呼；清空或无效时为 null
     * @param isValid 是否为可保存的有效称呼
     * @param isClear 是否为明确清空意图
     * @param reason 拒绝原因（仅 isValid=false 且 isClear=false 时有意义）
     */
    data class Result(
        val normalized: String?,
        val isValid: Boolean,
        val isClear: Boolean,
        val reason: String
    )

    /**
     * 校验用户输入的原始称呼
     *
     * 流程：清空意图检测 → 清洗 → 格式校验 → 长度校验 → 无意义值校验
     */
    fun validate(raw: String): Result {
        // 1. 优先检测清空意图（基于原始输入，只去首尾空格）
        if (isClearIntent(raw)) {
            return Result(normalized = null, isValid = false, isClear = true, reason = "")
        }

        // 2. 清洗
        val normalized = normalize(raw) ?: return Result(
            normalized = null,
            isValid = false,
            isClear = false,
            reason = "清洗后为空且非清空意图"
        )

        // 3. 格式校验（先于长度，确保 URL 等异常格式能被准确识别）
        val formatError = checkFormat(normalized)
        if (formatError != null) {
            return Result(normalized = null, isValid = false, isClear = false, reason = formatError)
        }

        // 4. 长度校验
        val len = normalized.length
        if (len < 1) {
            return Result(normalized = null, isValid = false, isClear = false, reason = "长度为 0")
        }
        if (len > MAX_LENGTH) {
            return Result(
                normalized = null,
                isValid = false,
                isClear = false,
                reason = "长度 ${len} 超过 ${MAX_LENGTH} 个字符"
            )
        }

        // 5. 无意义值校验
        if (isMeaningless(normalized)) {
            return Result(normalized = null, isValid = false, isClear = false, reason = "无意义值：$normalized")
        }

        // 6. 通过
        return Result(normalized = normalized, isValid = true, isClear = false, reason = "")
    }

    /**
     * 检测是否为清空意图
     *
     * 规则：
     * - 纯空白（去首尾空格后为空）→ 清空
     * - 去引号后为空 → 清空
     * - 去前缀后包含清空关键词 → 清空
     */
    fun isClearIntent(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return true

        val unquoted = stripMatchingQuotes(trimmed)
        if (unquoted.isEmpty()) return true

        // 同时检查带前缀和不带前缀的版本，覆盖"叫我直接叫你就行"等组合
        val withoutPrefix = stripLeadingPrefixes(unquoted)
        return CLEAR_PHRASES.any { phrase ->
            unquoted.contains(phrase) || withoutPrefix.contains(phrase)
        }
    }

    /**
     * 清洗用户输入（不做校验）
     *
     * 步骤：去首尾空格 → 去成对引号 → 去前缀 → 去后缀 → 再去首尾空格
     *
     * @return 清洗后的称呼；空输入或清洗后为空时返回 null
     */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        s = stripMatchingQuotes(s)
        if (s.isEmpty()) return null

        s = stripLeadingPrefixes(s)
        s = stripTrailingSuffixes(s)

        s = s.trim()
        return s.takeIf { it.isNotEmpty() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 清洗辅助
    // ═══════════════════════════════════════════════════════════════════════

    /** 去掉成对引号（半角和全角，只剥一层） */
    private fun stripMatchingQuotes(s: String): String {
        if (s.length < 2) return s
        val pairs = listOf(
            '"' to '"',
            '\u201C' to '\u201D',  // “ ”
            '\'' to '\'',
            '\u2018' to '\u2019',  // ‘ ’
            '「' to '」',
            '『' to '』',
            '【' to '】',
            '[' to ']'
        )
        for ((open, close) in pairs) {
            if (s.startsWith(open) && s.endsWith(close)) {
                return s.substring(1, s.length - 1)
            }
        }
        return s
    }

    /** 去掉前缀（循环剥离：叫我/称呼我为/称呼我） */
    private fun stripLeadingPrefixes(s: String): String {
        val prefixes = listOf("称呼我为", "称呼我", "叫我")
        var result = s
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (result.startsWith(prefix) && result.length >= prefix.length) {
                    result = result.substring(prefix.length)
                    changed = true
                    break
                }
            }
        }
        return result
    }

    /** 去掉后缀（循环剥离：就可以了/就行/就好/好了） */
    private fun stripTrailingSuffixes(s: String): String {
        val suffixes = listOf("就可以了", "就行", "就好", "好了")
        var result = s
        var changed = true
        while (changed) {
            changed = false
            for (suffix in suffixes) {
                if (result.endsWith(suffix) && result.length >= suffix.length) {
                    result = result.substring(0, result.length - suffix.length)
                    changed = true
                    break
                }
            }
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 格式校验
    // ═══════════════════════════════════════════════════════════════════════

    private fun checkFormat(s: String): String? {
        // 换行
        if (s.contains('\n') || s.contains('\r')) {
            return "含换行符"
        }

        // URL
        if (isUrl(s)) {
            return "含 URL 链接"
        }

        // JSON
        if (isJson(s)) {
            return "含 JSON 结构"
        }

        // 代码片段
        if (isCodeSnippet(s)) {
            return "含代码片段"
        }

        // 纯标点或纯 emoji（无任何字母或数字）
        if (isPurePunctuationOrEmoji(s)) {
            return "纯标点或纯 emoji，无可见字符"
        }

        return null
    }

    private fun isUrl(s: String): Boolean {
        val lower = s.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
               lower.startsWith("ftp://") || lower.startsWith("www.")
    }

    private fun isJson(s: String): Boolean {
        val trimmed = s.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    private fun isCodeSnippet(s: String): Boolean {
        val lower = s.lowercase()
        return CODE_PATTERNS.any { lower.contains(it.lowercase()) }
    }

    /**
     * 纯标点或纯 emoji：字符串中无任何字母或数字
     *
     * CJK 汉字属于 Character.LOWERCASE_LETTER / OTHER_LETTER，所以"阿哲"不会被误判
     */
    private fun isPurePunctuationOrEmoji(s: String): Boolean {
        return s.none { ch -> Character.isLetter(ch) || Character.isDigit(ch) }
    }

    private fun isMeaningless(s: String): Boolean {
        return s in MEANINGLESS_VALUES
    }
}
