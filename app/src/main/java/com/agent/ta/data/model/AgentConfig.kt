package com.agent.ta.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent 完整配置（对应 agent.json）
 * 不含任何 API Key / Base URL / Model 信息，纯人格配置
 *
 * 兼容 Admin v1.0 与 v2.0 导出格式
 * v2.0 新增字段全部带默认值，旧 v1.0 包仍能正常解析
 */
@Serializable
data class AgentConfig(
    val version: String = "1.0",
    val agent: AgentInfo = AgentInfo(),
    val voice: VoiceConfig = VoiceConfig(),
    val behavior: BehaviorConfig = BehaviorConfig(),
    /**
     * 参考明星/人物（仅作为作息规划的参考灵感，非强制）
     */
    val referenceCelebrity: String = ""
)

@Serializable
data class AgentInfo(
    val name: String = "",
    val gender: String = "",
    val age: Int = 0,
    val avatars: List<AvatarConfig> = emptyList(),
    val persona: Persona = Persona()
)

@Serializable
data class AvatarConfig(
    val id: String = "",
    val file: String = "",
    @SerialName("bind_state") val bindState: String? = null,
    @SerialName("mood") val bindMood: String? = null,
    /** Admin v2: 触发关键词数组，Agent 回复命中时自动切换到此头像 */
    @SerialName("trigger_keywords") val triggerKeywords: List<String> = emptyList(),
    /** Admin v2: 情绪映射（如 ["happy","excited"]） */
    @SerialName("emotion_mapping") val emotionMapping: List<String> = emptyList()
)

@Serializable
data class Persona(
    val background: String = "",
    val personality: List<String> = emptyList(),
    @SerialName("speaking_style") val speakingStyle: String = "",
    /**
     * Admin v2: 说话风格结构化详情
     * - 字符串值（tone/pace/sentence_length/vocabulary_level）：原样保留
     * - 数组值（filler_words）：拍平为 "项1/项2/项3" 格式字符串
     */
    @SerialName("speaking_style_detail")
    @Serializable(with = SpeakingStyleDetailSerializer::class)
    val speakingStyleDetail: Map<String, String> = emptyMap(),
    @SerialName("example_dialogues") val exampleDialogues: List<ExampleDialogue> = emptyList(),
    @SerialName("director_role_template") val directorRoleTemplate: String = "",
    /** Admin v2: 语音导演模板（TTS 声学特征指导：节奏/呼吸/情绪强度/停顿） */
    @SerialName("voice_director_template") val voiceDirectorTemplate: String = "",
    /** Admin v1 兼容字段，部分老包会用这个名称 */
    @SerialName("system_prompt_template") val systemPromptTemplate: String = "",
    /** Admin v2: 口头禅 */
    @SerialName("catchphrases") val catchphrases: List<String> = emptyList(),
    /** Admin v2: 对用户的称呼 */
    @SerialName("nickname_for_user") val nicknameForUser: String = "",
    /** Admin v2: 自称 */
    @SerialName("self_nickname") val selfNickname: String = "",
    /** Admin v2: 与用户的关系设定 */
    @SerialName("relationship_to_user") val relationshipToUser: String = "",
    /** Admin v2: 禁忌话题 */
    @SerialName("taboos") val taboos: List<String> = emptyList(),
    /** Admin v2: 兴趣/话题 */
    @SerialName("interests") val interests: List<String> = emptyList(),
    /** Admin v2: 初始共享记忆（在 Agent 导入时一次性注入到记忆库） */
    @SerialName("memory_seeds") val memorySeeds: List<String> = emptyList(),
    /** Admin v2: 关系阶段提示（如 {"初识": "...", "熟悉": "...", "亲密": "..."}） */
    @SerialName("conversation_stage_hints") val conversationStageHints: Map<String, String> = emptyMap()
)

/**
 * 示例对话
 *
 * 兼容两种格式：
 * - Admin v1: {"user": "...", "agent": "...", "scenario": "..."}
 * - Admin v2: {"scene": "...", "turns": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]}
 *
 * 解析后统一拍平为 user/agent 两个字段（取第一对 user→assistant 配对）。
 * scenario 优先取 v1 的 scenario 字段，否则用 v2 的 scene 字段。
 */
@Serializable(with = ExampleDialogueSerializer::class)
data class ExampleDialogue(
    val user: String = "",
    val agent: String = "",
    val scenario: String = ""
)

object ExampleDialogueSerializer : KSerializer<ExampleDialogue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ExampleDialogue")

    override fun serialize(encoder: Encoder, value: ExampleDialogue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ExampleDialogueSerializer 只支持 JSON 编码")
        val obj = JsonObject(mutableMapOf<String, kotlinx.serialization.json.JsonElement>().apply {
            if (value.scenario.isNotBlank()) put("scenario", JsonPrimitive(value.scenario))
            if (value.user.isNotBlank()) put("user", JsonPrimitive(value.user))
            if (value.agent.isNotBlank()) put("agent", JsonPrimitive(value.agent))
        })
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): ExampleDialogue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ExampleDialogueSerializer 只支持 JSON 解码")
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonObject) return ExampleDialogue()

        // v1 格式：直接读 user/agent/scenario
        val user = element["user"]?.jsonPrimitive?.contentOrNull ?: ""
        val agent = element["agent"]?.jsonPrimitive?.contentOrNull ?: ""
        val scenario = element["scenario"]?.jsonPrimitive?.contentOrNull
            ?: element["scene"]?.jsonPrimitive?.contentOrNull
            ?: ""

        if (user.isNotBlank() || agent.isNotBlank()) {
            return ExampleDialogue(user = user, agent = agent, scenario = scenario)
        }

        // v2 格式：从 turns 数组取第一对 user→assistant
        val turns = element["turns"] as? JsonArray ?: return ExampleDialogue(scenario = scenario)
        var firstUser = ""
        var firstAgent = ""
        for (turnEl in turns) {
            val turn = turnEl as? JsonObject ?: continue
            val role = turn["role"]?.jsonPrimitive?.contentOrNull ?: ""
            val content = turn["content"]?.jsonPrimitive?.contentOrNull ?: ""
            if (role == "user" && firstUser.isEmpty()) {
                firstUser = content
            } else if (role == "assistant" && firstUser.isNotEmpty() && firstAgent.isEmpty()) {
                firstAgent = content
                break
            }
        }
        return ExampleDialogue(user = firstUser, agent = firstAgent, scenario = scenario)
    }
}

@Serializable
data class VoiceConfig(
    /** v1 兼容字段：单样本路径 */
    @SerialName("sample_file") val sampleFile: String = "voice/sample.wav",
    @SerialName("director_mode") val directorMode: Boolean = true,
    /** Admin v2: TTS 参数（speed/pitch/volume/emotion/intonation） */
    @SerialName("voice_params") val voiceParams: Map<String, String> = emptyMap(),
    /** Admin v2: 声音文本描述（部分 API 可作为 voice prompt） */
    @SerialName("voice_description") val voiceDescription: String = "",
    /** Admin v2: 标点风格 normal/ellipses/tilde/mixed */
    @SerialName("punctuation_style") val punctuationStyle: String = "",
    /** Admin v2: 口头缀词处理 keep/strip */
    @SerialName("filler_words_handling") val fillerWordsHandling: String = "",
    /** Admin v2: 数字读法 literal/contextual */
    @SerialName("number_reading") val numberReading: String = "",
    /** Admin v2: Emoji 处理 skip/translate */
    @SerialName("emoji_handling") val emojiHandling: String = "",
    /** Admin v2: 多情绪样本列表 */
    @SerialName("sample_files") val sampleFiles: List<VoiceSampleFile> = emptyList()
)

/**
 * 多情绪音频样本文件（Admin v2 新增）
 * 一个 Agent 可挂多条不同情绪的样本，TTS 按当前情绪选择最匹配的样本
 */
@Serializable
data class VoiceSampleFile(
    val id: String = "",
    val file: String = "",
    /** neutral/happy/sad/angry/excited/soft/serious */
    val emotion: String = "neutral",
    /** 参考文本（提升克隆相似度） */
    val transcript: String = "",
    @SerialName("duration_sec") val durationSec: Float = 0f,
    @SerialName("sample_rate") val sampleRate: Int = 0,
    /** 是否为主样本（TTS 默认使用） */
    val primary: Boolean = false
)

/**
 * 当天作息时间段（由 LLM 每天生成，可随时调整）
 * start/end 格式 "HH:MM"，state 取 AgentState.id
 */
@Serializable
data class DailySlot(
    val start: String = "",
    val end: String = "",
    val state: String = "",
    /** 该时段的活动描述（LLM 生成，如"写设计稿"、"看新番"、"泡澡放松"） */
    val activity: String = ""
)

/**
 * SpeakingStyleDetail 序列化器
 *
 * Admin v2 的 speaking_style_detail 是 map[string]any，值既可以是字符串也可以是字符串数组：
 * - tone/pace/sentence_length/vocabulary_level 是字符串
 * - filler_words 是字符串数组（如 ["咯","嘛","哈哈"]）
 *
 * 此序列化器把数组拍平为 "项1/项2/项3" 格式字符串，让 App 端统一用 Map<String, String> 处理。
 */
object SpeakingStyleDetailSerializer : KSerializer<Map<String, String>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SpeakingStyleDetail")

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("SpeakingStyleDetailSerializer 只支持 JSON 编码")
        val obj = JsonObject(value.mapValues { JsonPrimitive(it.value) })
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("SpeakingStyleDetailSerializer 只支持 JSON 解码")
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonObject) return emptyMap()

        return element.entries.associate { (key, value) ->
            val str = when (value) {
                is JsonPrimitive -> value.contentOrNull ?: ""
                is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }.joinToString("/")
                else -> ""
            }
            key to str
        }
    }
}

@Serializable
data class BehaviorConfig(
    @SerialName("reply_delay_sec") val replyDelaySec: Map<String, ReplyDelay> = emptyMap(),
    /** v1 兼容字段，v2 已被 per_state_initiate 取代 */
    @SerialName("bored_initiate") val boredInitiate: BoredInitiate = BoredInitiate(),
    @SerialName("state_director_hints") val stateDirectorHints: Map<String, String> = emptyMap(),
    /** Admin v2: Emoji 配置（enabled/frequency_per_state/preferred_emojis/max_per_message） */
    val emoji: EmojiBehavior = EmojiBehavior(),
    /** Admin v2: 各状态主动发起配置 */
    @SerialName("per_state_initiate") val perStateInitiate: Map<String, StateInitiate> = emptyMap(),
    /** Admin v2: 各状态"正在输入"显示时长（[min, max] 秒） */
    @SerialName("typing_indicator_duration") val typingIndicatorDuration: Map<String, List<Int>> = emptyMap(),
    /** Admin v2: 各状态回复长度提示（{min, max} 字符数） */
    @SerialName("message_length_hints") val messageLengthHints: Map<String, LengthHint> = emptyMap()
)

/**
 * 回复延迟：要么是 [min, max] 范围，要么是 "defer"（延迟到状态结束）
 */
@Serializable(with = ReplyDelaySerializer::class)
sealed class ReplyDelay {
    @Serializable
    @SerialName("range")
    data class Range(val min: Int, val max: Int) : ReplyDelay()

    @Serializable
    @SerialName("defer")
    data object Defer : ReplyDelay()
}

object ReplyDelaySerializer : KSerializer<ReplyDelay> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ReplyDelay")

    override fun serialize(encoder: Encoder, value: ReplyDelay) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ReplyDelaySerializer 只支持 JSON 编码")
        when (value) {
            is ReplyDelay.Defer -> jsonEncoder.encodeJsonElement(JsonPrimitive("defer"))
            is ReplyDelay.Range -> jsonEncoder.encodeJsonElement(
                JsonArray(listOf(JsonPrimitive(value.min), JsonPrimitive(value.max)))
            )
        }
    }

    override fun deserialize(decoder: Decoder): ReplyDelay {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ReplyDelaySerializer 只支持 JSON 解码")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (element.content == "defer") ReplyDelay.Defer
                else throw SerializationException("无法解析 ReplyDelay 字符串：${element.content}")
            }
            is JsonArray -> {
                if (element.size >= 2) {
                    ReplyDelay.Range(element[0].jsonPrimitive.int, element[1].jsonPrimitive.int)
                } else {
                    throw SerializationException("ReplyDelay 数组格式应为 [min, max]，实际：$element")
                }
            }
            is JsonObject -> {
                when (element["type"]?.jsonPrimitive?.contentOrNull) {
                    "defer" -> ReplyDelay.Defer
                    "range" -> ReplyDelay.Range(
                        element["min"]?.jsonPrimitive?.intOrNull ?: 0,
                        element["max"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                    else -> throw SerializationException("无法解析 ReplyDelay 对象：$element")
                }
            }
            JsonNull -> throw SerializationException("ReplyDelay 不能为 null")
        }
    }
}

@Serializable
data class BoredInitiate(
    val enabled: Boolean = true,
    @SerialName("probability") val probabilityPer5min: Float = 0.3f,
    @SerialName("interval_min") val minIntervalMin: Int = 30,
    @SerialName("candidates") val candidateTopics: List<String> = emptyList()
)

/**
 * 各状态主动发起配置（Admin v2）
 * 用于替代 v1 的全局 bored_initiate
 *
 * 注意：
 * - candidates 是对象数组（Admin v2 导出格式），与 Admin 端 behavior.go 的 PerStateInitiate 对齐
 * - time_window 是对象（含 start/end 字段），非字符串
 */
@Serializable
data class StateInitiate(
    val enabled: Boolean = false,
    @SerialName("interval_min") val intervalMin: Int = 60,
    val probability: Float = 0.2f,
    @SerialName("time_window") val timeWindow: TimeWindow = TimeWindow(),
    @SerialName("cooldown_min") val cooldownMin: Int = 30,
    val candidates: List<StateInitiateCandidate> = emptyList()
)

/**
 * 主动发起候选消息（Admin v2）
 * 每条候选包含文本、情绪、触发后状态、权重
 */
@Serializable
data class StateInitiateCandidate(
    val text: String = "",
    val emotion: String = "",
    @SerialName("mood_after") val moodAfter: String = "",
    val weight: Int = 1
)

/**
 * 时间窗口（Admin v2）
 * 用于 per_state_initiate[state].time_window，限制主动发起的生效时段
 */
@Serializable
data class TimeWindow(
    val start: String = "",
    val end: String = ""
)

/**
 * Emoji 行为配置（Admin v2）
 */
@Serializable
data class EmojiBehavior(
    val enabled: Boolean = true,
    @SerialName("frequency_per_state") val frequencyPerState: Map<String, Float> = emptyMap(),
    @SerialName("preferred_emojis") val preferredEmojis: List<String> = emptyList(),
    @SerialName("max_per_message") val maxPerMessage: Int = 1
)

/**
 * 回复长度提示（Admin v2）
 */
@Serializable
data class LengthHint(
    val min: Int = 0,
    val max: Int = 0
)
