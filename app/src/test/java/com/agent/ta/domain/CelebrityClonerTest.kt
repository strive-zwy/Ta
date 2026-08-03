package com.agent.ta.domain

import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.AgentIdentity
import com.agent.ta.data.model.PublicProfile
import com.agent.ta.data.model.VoiceConfig
import com.agent.ta.data.model.AvatarConfig
import com.agent.ta.data.model.BehaviorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * CelebrityCloner 单元测试（Phase 4 Step 11）
 *
 * 验证：
 * - applyToConfig：写入策略正确（覆盖 identity + 部分 persona，保留 voice/avatars/behavior）
 * - parseCloneResult：JSON 解析容错（完整/缺失字段/Markdown 代码块/格式错误）
 *
 * generate() 依赖 LLM + WebSearch，需 mock 网络，留待集成测试覆盖
 */
class CelebrityClonerTest {

    private lateinit var cloner: CelebrityCloner

    @Before
    fun setup() {
        cloner = CelebrityCloner()
    }

    // ===== applyToConfig 测试 =====

    @Test
    fun applyToConfig_overwrites_identity_and_persona() {
        val current = makeConfigWithCustomVoiceAndAvatars()
        val result = makeCloneResult()

        val applied = cloner.applyToConfig(current, result, customNickname = "深深")

        // identity 被覆盖
        assertEquals(result.identity, applied.identity)
        assertEquals("我是周深，在音乐圈工作", applied.identity.worldSetting)
        // persona 联动字段被覆盖
        assertEquals("一位男歌手", applied.agent.persona.background)
        assertEquals(listOf("温柔", "有主见"), applied.agent.persona.personality)
        assertEquals(listOf("音乐", "动漫"), applied.agent.persona.interests)
        assertEquals("句子短，多语气词", applied.agent.persona.speakingStyle)
        assertEquals("年轻男性，声音清亮", applied.agent.persona.directorRoleTemplate)
    }

    @Test
    fun applyToConfig_preserves_voice_avatars_behavior() {
        val current = makeConfigWithCustomVoiceAndAvatars()
        val result = makeCloneResult()

        val applied = cloner.applyToConfig(current, result, customNickname = "深深")

        // voice 完全保留
        assertEquals(current.voice, applied.voice)
        assertEquals("voice/custom.wav", applied.voice.sampleFile)
        // avatars 完全保留
        assertEquals(current.agent.avatars, applied.agent.avatars)
        assertEquals(1, applied.agent.avatars.size)
        assertEquals("avatars/01.jpg", applied.agent.avatars[0].file)
        // behavior 完全保留
        assertEquals(current.behavior, applied.behavior)
        // persona 其他字段保留（catchphrases / taboos / memorySeeds 等）
        assertEquals(current.agent.persona.catchphrases, applied.agent.persona.catchphrases)
        assertEquals(current.agent.persona.taboos, applied.agent.persona.taboos)
    }

    @Test
    fun applyToConfig_sets_custom_nickname() {
        val current = AgentConfig()
        val result = makeCloneResult()

        val applied = cloner.applyToConfig(current, result, customNickname = "  深深  ")

        // 用户自定义昵称覆盖 agent.name（trim 空白）
        assertEquals("深深", applied.agent.name)
    }

    @Test
    fun applyToConfig_sets_reference_celebrity_from_career_field() {
        val current = AgentConfig()
        val result = makeCloneResult()

        val applied = cloner.applyToConfig(current, result, customNickname = "深深")

        // referenceCelebrity 写入 careerField
        assertEquals("音乐", applied.referenceCelebrity)
    }

    @Test
    fun applyToConfig_with_null_public_profile_sets_empty_reference() {
        val current = AgentConfig()
        val result = makeCloneResult().copy(
            identity = makeCloneResult().identity.copy(publicProfile = null)
        )

        val applied = cloner.applyToConfig(current, result, customNickname = "昵称")

        assertEquals("", applied.referenceCelebrity)
    }

    @Test
    fun applyToConfig_overwrites_gender_and_age() {
        val current = AgentConfig()  // 默认 gender="", age=0
        val result = makeCloneResult()  // gender="male", age=32

        val applied = cloner.applyToConfig(current, result, customNickname = "深深")

        assertEquals("male", applied.agent.gender)
        assertEquals(32, applied.agent.age)
    }

    @Test
    fun applyToConfig_invalid_gender_keeps_current() {
        val current = AgentConfig().copy(
            agent = AgentConfig().agent.copy(gender = "female", age = 25)
        )
        val result = makeCloneResult().copy(gender = "unknown", age = 0)

        val applied = cloner.applyToConfig(current, result, customNickname = "昵称")

        // gender 非法时保留原值，age<=0 时保留原值
        assertEquals("female", applied.agent.gender)
        assertEquals(25, applied.agent.age)
    }

    // ===== parseCloneResult 测试 =====

    @Test
    fun parseCloneResult_valid_json_returns_all_fields() {
        val json = """
            {
              "identity": {
                "worldSetting": "我是周深，在音乐圈工作",
                "originStory": "2014年出道",
                "personalityCore": "温柔但有主见",
                "speakingHabit": "多用语气词",
                "emotionalPattern": "被夸会害羞",
                "relationshipStance": "偶像-粉丝关系",
                "boundaryAwareness": "不随意承诺见面",
                "publicProfile": {
                  "careerField": "音乐",
                  "knownWorks": ["大鱼", "达拉崩吧"],
                  "fanCulture": "生米",
                  "careerStage": "巅峰期"
                }
              },
              "personaBackground": "一位男歌手",
              "personaPersonality": ["温柔", "有主见", "爱分享"],
              "personaInterests": ["音乐", "动漫", "美食"],
              "personaSpeakingStyle": "句子短，多语气词",
              "personaDirectorRoleTemplate": "年轻男性，声音清亮",
              "gender": "male",
              "age": 32
            }
        """.trimIndent()

        val result = cloner.parseCloneResult(json)

        assertEquals("我是周深，在音乐圈工作", result.identity.worldSetting)
        assertEquals("2014年出道", result.identity.originStory)
        assertEquals("温柔但有主见", result.identity.personalityCore)
        assertEquals(listOf("大鱼", "达拉崩吧"), result.identity.publicProfile?.knownWorks)
        assertEquals("生米", result.identity.publicProfile?.fanCulture)
        assertEquals("巅峰期", result.identity.publicProfile?.careerStage)
        assertEquals("一位男歌手", result.personaBackground)
        assertEquals(listOf("温柔", "有主见", "爱分享"), result.personaPersonality)
        assertEquals(listOf("音乐", "动漫", "美食"), result.personaInterests)
        assertEquals("句子短，多语气词", result.personaSpeakingStyle)
        assertEquals("年轻男性，声音清亮", result.personaDirectorRoleTemplate)
        assertEquals("male", result.gender)
        assertEquals(32, result.age)
    }

    @Test
    fun parseCloneResult_missing_optional_field_uses_default() {
        // 缺少 publicProfile 和 personaInterests
        val json = """
            {
              "identity": {
                "worldSetting": "我是周深",
                "originStory": "",
                "personalityCore": "",
                "speakingHabit": "",
                "emotionalPattern": "",
                "relationshipStance": "",
                "boundaryAwareness": ""
              },
              "personaBackground": "背景",
              "personaPersonality": ["温柔"],
              "personaSpeakingStyle": "",
              "personaDirectorRoleTemplate": ""
            }
        """.trimIndent()

        val result = cloner.parseCloneResult(json)

        // publicProfile 为 null
        assertEquals(null, result.identity.publicProfile)
        // personaInterests 缺失用空列表
        assertTrue(result.personaInterests.isEmpty())
        // 其他字段用空字符串默认值
        assertEquals("", result.identity.originStory)
        assertEquals("", result.personaSpeakingStyle)
    }

    @Test
    fun parseCloneResult_strips_markdown_code_fence() {
        val json = """
            ```json
            {
              "identity": {
                "worldSetting": "我是周深",
                "originStory": "",
                "personalityCore": "",
                "speakingHabit": "",
                "emotionalPattern": "",
                "relationshipStance": "",
                "boundaryAwareness": ""
              },
              "personaBackground": "背景",
              "personaPersonality": [],
              "personaInterests": [],
              "personaSpeakingStyle": "",
              "personaDirectorRoleTemplate": ""
            }
            ```
        """.trimIndent()

        val result = cloner.parseCloneResult(json)

        assertEquals("我是周深", result.identity.worldSetting)
        assertEquals("背景", result.personaBackground)
    }

    @Test
    fun parseCloneResult_with_explanatory_text_around_json() {
        // LLM 在 JSON 前后加了解释性文字
        val json = """
            好的，这是生成的身份设定：
            {
              "identity": {
                "worldSetting": "我是周深",
                "originStory": "",
                "personalityCore": "",
                "speakingHabit": "",
                "emotionalPattern": "",
                "relationshipStance": "",
                "boundaryAwareness": ""
              },
              "personaBackground": "背景",
              "personaPersonality": [],
              "personaInterests": [],
              "personaSpeakingStyle": "",
              "personaDirectorRoleTemplate": ""
            }
            希望这个设定符合你的需求。
        """.trimIndent()

        val result = cloner.parseCloneResult(json)

        assertEquals("我是周深", result.identity.worldSetting)
        assertEquals("背景", result.personaBackground)
    }

    @Test
    fun parseCloneResult_malformed_json_throws_exception() {
        val malformed = "这不是 JSON，是纯文本回复"

        val exception = assertThrows(CelebrityCloner.CloneException::class.java) {
            cloner.parseCloneResult(malformed)
        }
        // 异常 message 包含原始内容片段
        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains("无法解析"))
    }

    @Test
    fun parseCloneResult_empty_string_throws_exception() {
        assertThrows(CelebrityCloner.CloneException::class.java) {
            cloner.parseCloneResult("")
        }
    }

    // ===== 测试数据辅助 =====

    private fun makeCloneResult(): CloneResult {
        return CloneResult(
            identity = AgentIdentity(
                worldSetting = "我是周深，在音乐圈工作",
                originStory = "2014年出道",
                personalityCore = "温柔但有主见",
                speakingHabit = "多用语气词",
                emotionalPattern = "被夸会害羞",
                relationshipStance = "偶像-粉丝关系",
                boundaryAwareness = "不随意承诺见面",
                publicProfile = PublicProfile(
                    careerField = "音乐",
                    knownWorks = listOf("大鱼", "达拉崩吧"),
                    fanCulture = "生米",
                    careerStage = "巅峰期"
                )
            ),
            personaBackground = "一位男歌手",
            personaPersonality = listOf("温柔", "有主见"),
            personaInterests = listOf("音乐", "动漫"),
            personaSpeakingStyle = "句子短，多语气词",
            personaDirectorRoleTemplate = "年轻男性，声音清亮",
            gender = "male",
            age = 32
        )
    }

    /**
     * 构造一个有自定义 voice/avatars/behavior 的 AgentConfig
     * 用于验证 applyToConfig 不会覆盖这些字段
     */
    private fun makeConfigWithCustomVoiceAndAvatars(): AgentConfig {
        return AgentConfig(
            agent = com.agent.ta.data.model.AgentInfo(
                name = "旧名字",
                avatars = listOf(
                    AvatarConfig(id = "1", file = "avatars/01.jpg")
                ),
                persona = com.agent.ta.data.model.Persona(
                    background = "旧背景",
                    catchphrases = listOf("口头禅1"),
                    taboos = listOf("禁忌1")
                )
            ),
            voice = VoiceConfig(sampleFile = "voice/custom.wav"),
            behavior = BehaviorConfig()
        )
    }
}
