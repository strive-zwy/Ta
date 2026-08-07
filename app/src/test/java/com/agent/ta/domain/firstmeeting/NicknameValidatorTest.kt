package com.agent.ta.domain.firstmeeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NicknameValidator 单元测试（Task 13）
 *
 * 验证：
 * 1. 去掉首尾空格、成对引号和"叫我/称呼我为/就行"等残留
 * 2. 仅允许 1～12 个可见字符
 * 3. 拒绝换行、URL、JSON、代码片段、纯标点、纯 emoji、完整长句
 * 4. 拒绝"随便""都行""你猜""不知道""以后再说"等无意义值
 * 5. 支持明确清空称呼的独立结果，不把空字符串当校验失败
 */
class NicknameValidatorTest {

    private val validator = NicknameValidator

    // ===== 有效输入 =====

    @Test
    fun valid_simple_chinese_name() {
        val result = validator.validate("阿哲")
        assertTrue("阿哲 应有效", result.isValid)
        assertEquals("阿哲", result.normalized)
        assertFalse("不应为清空", result.isClear)
    }

    @Test
    fun valid_english_name() {
        val result = validator.validate("Alex")
        assertTrue("Alex 应有效", result.isValid)
        assertEquals("Alex", result.normalized)
    }

    @Test
    fun valid_chinese_with_english() {
        val result = validator.validate("明哥Mike")
        assertTrue("中英混合应有效", result.isValid)
        assertEquals("明哥Mike", result.normalized)
    }

    @Test
    fun valid_with_leading_call_me_prefix() {
        val result = validator.validate("叫我阿哲")
        assertTrue("'叫我阿哲' 应有效", result.isValid)
        assertEquals("应剥离'叫我'前缀", "阿哲", result.normalized)
    }

    @Test
    fun valid_with_address_me_as_prefix() {
        val result = validator.validate("称呼我为小雅")
        assertTrue("'称呼我为小雅' 应有效", result.isValid)
        assertEquals("应剥离'称呼我为'前缀", "小雅", result.normalized)
    }

    @Test
    fun valid_with_trailing_jiuxing_suffix() {
        val result = validator.validate("阿哲就行")
        assertTrue("'阿哲就行' 应有效", result.isValid)
        assertEquals("应剥离'就行'后缀", "阿哲", result.normalized)
    }

    @Test
    fun valid_with_quotes_stripped() {
        val result = validator.validate("\"阿哲\"")
        assertTrue("带引号应有效", result.isValid)
        assertEquals("应剥离成对引号", "阿哲", result.normalized)
    }

    @Test
    fun valid_with_full_width_quotes_stripped() {
        val result = validator.validate("\u201C阿哲\u201D")  // " "
        assertTrue("带全角引号应有效", result.isValid)
        assertEquals("应剥离成对全角引号", "阿哲", result.normalized)
    }

    @Test
    fun valid_with_leading_and_trailing_whitespace() {
        val result = validator.validate("  阿哲  ")
        assertTrue("带首尾空格应有效", result.isValid)
        assertEquals("应剥离首尾空格", "阿哲", result.normalized)
    }

    @Test
    fun valid_combined_prefix_suffix_and_quotes() {
        val result = validator.validate("  \"叫我阿哲就行\"  ")
        assertTrue("组合修饰应有效", result.isValid)
        assertEquals("应剥离所有修饰", "阿哲", result.normalized)
    }

    @Test
    fun valid_single_character_name() {
        val result = validator.validate("明")
        assertTrue("单字称呼应有效（1 字符）", result.isValid)
        assertEquals("明", result.normalized)
    }

    @Test
    fun valid_max_12_characters() {
        val nickname = "哈哈哈哈哈哈哈哈哈哈哈哈"  // 12 字
        assertEquals(12, nickname.length)
        val result = validator.validate(nickname)
        assertTrue("12 字符应有效（边界）", result.isValid)
        assertEquals(nickname, result.normalized)
    }

    @Test
    fun valid_name_with_digits() {
        val result = validator.validate("小明007")
        assertTrue("含数字应有效", result.isValid)
        assertEquals("小明007", result.normalized)
    }

    @Test
    fun valid_nickname_with_punctuation_inside() {
        // 称呼内部可以有标点（如"阿-哲"、"小.明"），不是纯标点即可
        val result = validator.validate("阿-哲")
        assertTrue("含连字符应有效", result.isValid)
        assertEquals("阿-哲", result.normalized)
    }

    // ===== 清空意图 =====

    @Test
    fun clear_empty_string_returns_clear_result() {
        val result = validator.validate("")
        assertTrue("空字符串应为清空意图", result.isClear)
        assertFalse("清空不应为有效保存", result.isValid)
        assertNull("清空时 normalized 应为 null", result.normalized)
    }

    @Test
    fun clear_blank_only_returns_clear_result() {
        val result = validator.validate("   ")
        assertTrue("纯空格应为清空意图", result.isClear)
        assertFalse("清空不应为有效保存", result.isValid)
        assertNull(result.normalized)
    }

    @Test
    fun clear_explicit_phrase_returns_clear_result() {
        val result = validator.validate("直接叫你就行")
        assertTrue("'直接叫你就行' 应识别为清空意图", result.isClear)
        assertFalse("清空不应为有效保存", result.isValid)
    }

    @Test
    fun clear_no_need_to_call_returns_clear_result() {
        val result = validator.validate("不用叫了")
        assertTrue("'不用叫了' 应识别为清空意图", result.isClear)
    }

    @Test
    fun clear_clear_it_returns_clear_result() {
        val result = validator.validate("清空称呼")
        assertTrue("'清空称呼' 应识别为清空意图", result.isClear)
    }

    // ===== 无效输入：长度越界 =====

    @Test
    fun invalid_too_long_13_characters() {
        val nickname = "哈哈哈哈哈哈哈哈哈哈哈哈哈"  // 13 字
        assertEquals(13, nickname.length)
        val result = validator.validate(nickname)
        assertFalse("13 字符应无效（超长）", result.isValid)
        assertFalse("不应为清空", result.isClear)
        assertTrue("原因应提及长度", result.reason.contains("长度") || result.reason.contains("长"))
    }

    @Test
    fun invalid_full_long_sentence() {
        val longSentence = "我就是一个很长很长很长很长很长的句子作为称呼完全不合理"
        val result = validator.validate(longSentence)
        assertFalse("完整长句应无效", result.isValid)
    }

    // ===== 无效输入：格式异常 =====

    @Test
    fun invalid_contains_newline() {
        val result = validator.validate("阿哲\n明哥")
        assertFalse("含换行应无效", result.isValid)
        assertTrue("原因应提及格式", result.reason.isNotBlank())
    }

    @Test
    fun invalid_url() {
        val result = validator.validate("https://example.com")
        assertFalse("URL 应无效", result.isValid)
        assertTrue("原因应提及 URL 或格式", result.reason.contains("URL") || result.reason.contains("格式"))
    }

    @Test
    fun invalid_json_object() {
        val result = validator.validate("{\"name\":\"阿哲\"}")
        assertFalse("JSON 应无效", result.isValid)
    }

    @Test
    fun invalid_code_snippet() {
        val result = validator.validate("val x = 1")
        assertFalse("代码片段应无效", result.isValid)
    }

    @Test
    fun invalid_pure_punctuation() {
        val result = validator.validate("。。。")
        assertFalse("纯标点应无效", result.isValid)
    }

    @Test
    fun invalid_pure_emoji() {
        val result = validator.validate("😊🎉")
        assertFalse("纯 emoji 应无效", result.isValid)
    }

    @Test
    fun invalid_pure_emoji_single() {
        val result = validator.validate("👍")
        assertFalse("单个纯 emoji 应无效", result.isValid)
    }

    // ===== 无效输入：无意义值 =====

    @Test
    fun invalid_meaningless_suibian() {
        val result = validator.validate("随便")
        assertFalse("'随便' 应无效", result.isValid)
        assertFalse("'随便' 不应识别为清空", result.isClear)
    }

    @Test
    fun invalid_meaningless_douxing() {
        val result = validator.validate("都行")
        assertFalse("'都行' 应无效", result.isValid)
    }

    @Test
    fun invalid_meaningless_nicai() {
        val result = validator.validate("你猜")
        assertFalse("'你猜' 应无效", result.isValid)
    }

    @Test
    fun invalid_meaningless_buzhidao() {
        val result = validator.validate("不知道")
        assertFalse("'不知道' 应无效", result.isValid)
    }

    @Test
    fun invalid_meaningless_yihouzaishuo() {
        val result = validator.validate("以后再说")
        assertFalse("'以后再说' 应无效", result.isValid)
    }

    @Test
    fun invalid_meaningless_wusuoqu() {
        val result = validator.validate("无所谓")
        assertFalse("'无所谓' 应无效", result.isValid)
    }

    // ===== isClearIntent 独立方法 =====

    @Test
    fun isClearIntent_empty_returns_true() {
        assertTrue(validator.isClearIntent(""))
    }

    @Test
    fun isClearIntent_blank_returns_true() {
        assertTrue(validator.isClearIntent("   "))
    }

    @Test
    fun isClearIntent_explicit_phrase_returns_true() {
        assertTrue(validator.isClearIntent("直接叫你就行"))
        assertTrue(validator.isClearIntent("不用叫了"))
        assertTrue(validator.isClearIntent("清空称呼"))
        assertTrue(validator.isClearIntent("不需要称呼"))
    }

    @Test
    fun isClearIntent_normal_nickname_returns_false() {
        assertFalse(validator.isClearIntent("阿哲"))
        assertFalse(validator.isClearIntent("叫我小明"))
    }

    @Test
    fun isClearIntent_meaningless_returns_false() {
        // "随便" 不是清空意图，是无效输入
        assertFalse(validator.isClearIntent("随便"))
        assertFalse(validator.isClearIntent("都行"))
    }

    // ===== normalize 独立方法（不校验） =====

    @Test
    fun normalize_strips_modifiers() {
        assertEquals("阿哲", validator.normalize("叫我阿哲就行"))
        assertEquals("阿哲", validator.normalize("\"阿哲\""))
        assertEquals("阿哲", validator.normalize("  阿哲  "))
    }

    @Test
    fun normalize_returns_null_for_blank() {
        assertNull(validator.normalize(""))
        assertNull(validator.normalize("   "))
    }

    // ===== 边界组合 =====

    @Test
    fun valid_combined_clear_phrase_with_prefix_should_be_clear() {
        // "叫我直接叫你就行" — 用户说"叫我[直接叫你就行]"
        // 这里"直接叫你就行"是清空意图的子串，应被识别为清空而非保存
        val result = validator.validate("叫我直接叫你就行")
        assertTrue("应识别为清空意图", result.isClear)
    }

    @Test
    fun invalid_after_strip_becomes_empty() {
        // "叫我" 单独出现，剥离后为空，既不是清空也不是有效
        val result = validator.validate("叫我")
        // "叫我" 单独出现无法识别为清空意图，应判定无效
        assertFalse("剥离后为空且非清空意图应无效", result.isValid)
    }

    @Test
    fun valid_emoji_mixed_with_text_still_valid() {
        // "阿哲😊" 含 emoji 但有文字，应有效（emoji 视为装饰）
        val result = validator.validate("阿哲😊")
        assertTrue("文字+emoji 应有效", result.isValid)
    }
}
