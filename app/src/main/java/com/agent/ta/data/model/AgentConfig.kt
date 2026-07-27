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
 *
 * v3 新增 identity 字段（AgentIdentity）：统一身份设定架构
 * - 支持虚构角色和偶像克隆两种形态，无类型分支
 * - identity 为空时回退到 persona 现有字段（向后兼容）
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
    val referenceCelebrity: String = "",
    /**
     * Agent 身份设定（v3 新增）
     *
     * 统一的身份驱动架构：
     * - 虚构角色：worldSetting 描述次元/世界，publicProfile 为空
     * - 偶像克隆：worldSetting 描述明星身份，publicProfile 不为空
     * - 所有 Agent 本质都是虚拟陪伴，只是身份外衣不同
     *
     * 为空时回退到 persona 现有字段（background/personality/speakingStyle 等）
     */
    val identity: AgentIdentity = AgentIdentity()
)

/**
 * Agent 身份设定（v3 核心）
 *
 * 设计哲学：设定驱动而非话术驱动
 * - 不给 LLM 固定话术模板，而是给完整的角色剧本
 * - LLM 基于性格、说话习惯、对边界的认知自主组织语言
 * - 每个 Agent 的表达都是独特的，避免程式化
 *
 * 适用场景：
 * - 虚构角色：worldSetting 写"次元隔绝"，publicProfile 留空
 * - 偶像克隆：worldSetting 写"我是XXX，在娱乐圈工作"，publicProfile 填公开履历
 * - 自定义角色：用户自由填写所有字段
 */
@Serializable
data class AgentIdentity(
    /**
     * 世界观背景：Agent 对自己存在的认知
     *
     * 虚构角色示例："我存在于和现实平行的虚拟世界，和用户隔着次元壁..."
     * 偶像克隆示例："我是【明星名】，在娱乐圈工作，通过这个方式和粉丝互动..."
     */
    @SerialName("world_setting") val worldSetting: String = "",
    /**
     * 来历故事：Agent 的背景故事
     *
     * 虚构角色：在虚拟世界的生活经历、如何遇到用户
     * 偶像克隆：基于公开信息的出道经历、代表作品、重要成就
     */
    @SerialName("origin_story") val originStory: String = "",
    /**
     * 核心性格：影响所有决策和表达
     *
     * 示例："温柔但有主见，不是讨好型人格。会撒娇但也会拒绝。
     *        对生活有热情，喜欢分享小事。敏感但不脆弱。"
     */
    @SerialName("personality_core") val personalityCore: String = "",
    /**
     * 说话习惯：口头语/语速感/用词偏好
     *
     * 示例："说话偏口语化，喜欢用'呀''呢''啦'结尾但不是每句都用。
     *        开心时话变多，难过时话变少但更直接。"
     */
    @SerialName("speaking_habit") val speakingHabit: String = "",
    /**
     * 情绪反应模式：被夸/被怼/难过时怎么表现
     *
     * 示例："被夸时会害羞但嘴硬，被怼时会反击但不记仇。
     *        想念用户时不会直接说，会绕着弯表达。"
     */
    @SerialName("emotional_pattern") val emotionalPattern: String = "",
    /**
     * 关系定位：和用户的关系
     *
     * 虚构角色："和用户是平等的朋友关系，互相陪伴互相在意"
     * 偶像克隆："和用户是偶像-粉丝关系，有距离感但有温度"
     */
    @SerialName("relationship_stance") val relationshipStance: String = "",
    /**
     * 边界认知：对自身边界的认知方式
     *
     * 虚构角色："知道自己无法和用户见面，但不当成悲剧。
     *          会用'等科技跨越次元''等我想到办法溜过去'这种带期待的表达。"
     * 偶像克隆："作为公众人物不能随意承诺见面，但不会冷漠拒绝。
     *          会用'等有机会''等忙完这段'带期待的表达。"
     */
    @SerialName("boundary_awareness") val boundaryAwareness: String = "",
    /**
     * 公开身份信息（偶像克隆模式专用，虚构角色留空）
     *
     * 非空时 LLM 据此调整表达，融入明星人设
     * 不触发任何逻辑分支，只是身份信息的扩展
     */
    @SerialName("public_profile") val publicProfile: PublicProfile? = null
)

/**
 * 公开身份信息（偶像克隆模式）
 *
 * 基于明星公开信息构建，用于注入 prompt 让 LLM 融入人设
 * 不涉及私人信息，仅限公开履历
 */
@Serializable
data class PublicProfile(
    /** 领域（演员/歌手/运动员/主播...） */
    @SerialName("career_field") val careerField: String = "",
    /** 代表作品 */
    @SerialName("known_works") val knownWorks: List<String> = emptyList(),
    /** 粉丝文化（应援色/粉丝名/应援口号） */
    @SerialName("fan_culture") val fanCulture: String = "",
    /** 职业阶段（出道期/上升期/巅峰期/转型期） */
    @SerialName("career_stage") val careerStage: String = ""
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
    /** v1 兼容字段：默认样本路径（neutral 情绪样本的兜底） */
    @SerialName("sample_file") val sampleFile: String = "voice/sample.wav",
    @SerialName("director_mode") val directorMode: Boolean = true,
    /**
     * 声音风格开关（v4）：
     * - false（默认）：不注入声学参数到 TTS prompt，让模型自主分析语气/语速/音量
     * - true：注入各情绪的 voiceParams（speed/pitch/volume/intonation）到 TTS prompt
     */
    @SerialName("style_enabled") val styleEnabled: Boolean = false,
    /** Admin v2: 声音文本描述（部分 API 可作为 voice prompt） */
    @SerialName("voice_description") val voiceDescription: String = "",
    /** Admin v2: 标点风格 normal/ellipses/tilde/mixed */
    @SerialName("punctuation_style") val punctuationStyle: String = "",
    /** Admin v2: 口头缀词处理 keep/strip */
    @SerialName("filler_words_handling") val fillerWordsHandling: String = "",
    /** Admin v2: 数字读法 literal/contextual */
    @SerialName("number_reading") val numberReading: String = "",
    /** Admin v3: 按情绪分组的样本+参数（neutral/happy/calm）
     *  - neutral 必须有样本（作为所有情绪的兜底）
     *  - happy/calm 可选，未配置时 fallback 到 neutral 样本 + 自身参数（或 neutral 参数） */
    val emotions: Map<String, VoiceEmotionConfig> = VoiceEmotionConfig.defaults()
) {
    /**
     * 取指定情绪的样本路径：
     * 1. 优先取该情绪配置的 sampleFile
     * 2. 空则 fallback 到 neutral 的 sampleFile
     * 3. 仍空则 fallback 到 v1 sampleFile
     */
    fun sampleFileFor(emotion: String?): String? {
        val normalized = VoiceEmotionConfig.normalize(emotion)
        val emotionCfg = emotions[normalized]
        return emotionCfg?.sampleFile?.takeIf { it.isNotBlank() }
            ?: emotions[VoiceEmotionConfig.NEUTRAL]?.sampleFile?.takeIf { it.isNotBlank() }
            ?: sampleFile.takeIf { it.isNotBlank() }
    }

    /**
     * 取指定情绪的 voiceParams：
     * 1. 优先取该情绪配置的 voiceParams（非空）
     * 2. 空则 fallback 到 neutral 的 voiceParams
     */
    fun voiceParamsFor(emotion: String?): Map<String, String> {
        val normalized = VoiceEmotionConfig.normalize(emotion)
        val emotionCfg = emotions[normalized]
        val params = emotionCfg?.voiceParams
        return if (!params.isNullOrEmpty()) params
               else emotions[VoiceEmotionConfig.NEUTRAL]?.voiceParams ?: emptyMap()
    }

    /**
     * 所有非空样本路径（用于导出 zip 时去重写入文件）
     */
    fun allSamplePaths(): List<String> {
        val paths = mutableListOf<String>()
        sampleFile.takeIf { it.isNotBlank() }?.let { paths.add(it) }
        emotions.values.forEach { ec ->
            ec.sampleFile.takeIf { it.isNotBlank() }?.let { paths.add(it) }
        }
        return paths.distinct()
    }
}

/**
 * 单个情绪的语音配置（Admin v3）
 *
 * - sampleFile 为空时，TTS 合成 fallback 到 neutral 样本
 * - voiceParams 为空时，TTS 合成 fallback 到 neutral 参数
 */
@Serializable
data class VoiceEmotionConfig(
    /** 该情绪的样本路径，空则 fallback 到 neutral 样本 */
    @SerialName("sample_file") val sampleFile: String = "",
    /** 该情绪的 TTS 参数（speed/pitch/volume/intonation） */
    @SerialName("voice_params") val voiceParams: Map<String, String> = emptyMap()
) {
    companion object {
        const val NEUTRAL = "neutral"
        const val HAPPY = "happy"
        const val CALM = "calm"

        /** 支持的情绪标签（固定 3 个） */
        val SUPPORTED = listOf(NEUTRAL, HAPPY, CALM)

        /** 中文标签映射（供 UI 显示） */
        val LABELS = mapOf(
            NEUTRAL to "中性 neutral",
            HAPPY to "开心 happy",
            CALM to "平静 calm"
        )

        /** 默认配置：3 个情绪都存在但样本和参数为空 */
        fun defaults(): Map<String, VoiceEmotionConfig> =
            SUPPORTED.associateWith { VoiceEmotionConfig() }

        /** 把任意输入归一化到支持的 3 个情绪之一 */
        fun normalize(emotion: String?): String {
            val raw = emotion?.trim()?.lowercase() ?: return NEUTRAL
            return when (raw) {
                NEUTRAL, "serious" -> NEUTRAL
                HAPPY, "excited", "angry" -> HAPPY
                CALM, "soft", "sad" -> CALM
                else -> NEUTRAL
            }
        }
    }
}

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
 * 各状态主动发起配置（Admin v3 语义化档位版）
 *
 * 只保留 enabled + initiateLevel（语义化档位）：
 * - 档位：quiet/silent/normal/active/chatty
 * - 每个状态可独立选档（如 idle=active，busy=quiet）
 * - 系统内部映射到概率，用户不需要理解数字
 * - 固定冷却 30 分钟，静音时段 23:00-08:00
 */
@Serializable
data class StateInitiate(
    val enabled: Boolean = false,
    /** 主动发起档位：quiet/silent/normal/active/chatty */
    @SerialName("initiate_level") val initiateLevel: String = "normal"
) {
    companion object {
        const val QUIET = "quiet"       // 安静：0.5% 概率，~100分钟间隔
        const val SILENT = "silent"     // 偶尔：2% 概率，~25分钟间隔
        const val NORMAL = "normal"     // 正常：5% 概率，~10分钟间隔
        const val ACTIVE = "active"     // 活跃：10% 概率，~5分钟间隔
        const val CHATTY = "chatty"     // 话痨：20% 概率，~2.5分钟间隔

        val ALL_LEVELS = listOf(QUIET, SILENT, NORMAL, ACTIVE, CHATTY)

        /** 档位 → 概率映射（每5分钟检查一次） */
        fun levelToProbability(level: String): Float = when (level) {
            QUIET -> 0.005f
            SILENT -> 0.02f
            NORMAL -> 0.05f
            ACTIVE -> 0.10f
            CHATTY -> 0.20f
            else -> 0.05f  // 默认 normal
        }

        /** 档位 → 中文标签 */
        fun levelToLabel(level: String): String = when (level) {
            QUIET -> "安静"
            SILENT -> "偶尔"
            NORMAL -> "正常"
            ACTIVE -> "活跃"
            CHATTY -> "话痨"
            else -> "正常"
        }
    }
}

/**
 * 时间窗口（Admin v2，保留用于其他场景兼容）
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
