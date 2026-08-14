package com.agent.ta.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Agent 回复结构（LLM 输出）
 * 要求 LLM 同时输出回复文本、导演指令、记忆更新
 */
@Serializable
data class AgentReply(
    /** 单条回复（向后兼容：LLM 若只输出 replyText 则用这个） */
    val replyText: String = "",
    /** 旁白/动作描述（不进入语音合成，仅在 UI 以浅色斜体展示）
     *  如 "在沙发上躺着回复消息"、"边吃苹果边打字" */
    val action: String = "",
    val directorPrompt: String = "",
    val memoryUpdates: List<MemoryUpdate> = emptyList(),
    /** LLM 从对话中识别到的未来事件（如用户提到"后天某明星演唱会"） */
    val futureEvents: List<FutureEventItem> = emptyList(),
    /** LLM 从对话中识别到的承诺/约定/提醒（如"下午3点一起看电影"） */
    val commitments: List<CommitmentItem> = emptyList(),
    /** LLM 从对话中识别到的承诺状态更新（如用户说"看完了"） */
    val commitmentUpdates: List<CommitmentUpdateItem> = emptyList(),
    /**
     * 多条回复（新格式，让 Agent 可以一次发多条消息，更像真人微信聊天）
     * - 如果 LLM 输出了 replies，则用 replies（每条独立合成语音 + 独立入库）
     * - 如果 LLM 没输出 replies，则 fallback 到单条 replyText
     */
    val replies: List<ReplyItem> = emptyList(),
    /** Agent 自主作息调整（用户撒娇请求陪伴时，Agent 自己判断是否调整） */
    val scheduleAdjustment: ScheduleAdjustment = ScheduleAdjustment(),
    /** 配置模式下的配置变更建议（LLM 输出，ChatInteractor 应用到 AgentConfig） */
    val configUpdate: ConfigUpdate? = null,
    /**
     * 关系里程碑声明（Phase 2 关系系统）
     * LLM 主动声明的里程碑 type，如 "first_vulnerability" / "first_argument" / "first_secret_shared"
     * 若本次回复涉及关系节点（首次袒露脆弱/首次吵架等）则输出对应 type；否则留空
     */
    val milestoneDeclared: String? = null,
    /**
     * 情绪强度声明（Phase 3 情感势能驱动主动发起）
     * LLM 自报本次回复内心未充分表达的情绪波动强度
     * -2.0 = 强烈负面（委屈/愤怒）/ -1.0 = 轻微低落 / 0 = 平静 / +1.0 = 轻微开心 / +2.0 = 强烈兴奋
     * 0 表示情绪平淡无波动；非 0 表示 Agent 内心有情绪但回复未完全表达
     * 用于驱动 EmotionalEngine 的势能积累
     */
    val emotionIntensity: Float = 0f,
    /**
     * Agent 自主切换头像：本次回复希望显示的头像 id（来自 AvatarConfig.id）。
     * - null/空字符串 = 不切换（保持当前头像）
     * - 非空字符串 = 切换到指定 id 的头像；ChatInteractor 会更新 AgentInfo.currentAvatarId
     * - 指向的 id 不存在时由 ChatInteractor 兜底（保持原头像）
     * Agent 根据 AvatarConfig.description 自主判断何时换头像（如情绪转变/活动场景切换）
     */
    val wantAvatarId: String? = null,
    /**
     * 首次见面元数据（Task 12）
     *
     * 仅在 FIRST_MEETING_GREETING / FIRST_MEETING_REPLY 场景下由 LLM 输出。
     * ChatInteractor 校验 introducedSelf 和 askedForNickname：
     * - GREETING 场景要求两者都为 true；失败时纠正重试一次，再失败用最小兜底问句
     * - REPLY 场景不强制（用户先发消息时 Agent 自然回应即可）
     * 普通对话场景不输出此字段，默认 null。
     */
    val firstMeetingMeta: FirstMeetingMeta? = null,
    /**
     * 称呼解析结果（Task 14）
     *
     * 在首次见面 WAITING_NICKNAME / FOLLOW_UP_ASKED 阶段，以及首次见面完成后用户修改称呼时，
     * LLM 在普通回复中同时输出此字段，避免额外调用导致回复与提取不一致。
     *
     * ChatInteractor 通过 NicknameResolver.parse 清洗后，再由 NicknameResolver.decideSave 判断是否保存。
     * 普通对话场景不输出此字段，默认 null。
     */
    val nicknameResolution: NicknameResolution? = null
)

/**
 * 称呼解析结果（Task 14）
 *
 * LLM 在回复中同时输出，用于结构化提取用户对称呼的意图。
 * 本地通过 NicknameResolver 解析和校验后决定是否保存。
 *
 * @param intent 意图：EXPLICIT_NICKNAME / SELF_INTRODUCTION / DECLINED / AMBIGUOUS / CORRECTION / CLEAR / NONE
 * @param nickname LLM 提取的原始称呼（可能含"叫我"等修饰词，由 NicknameValidator 清洗）
 * @param confidence 置信度 [0, 1]，越界由 NicknameResolver 钳制
 * @param evidence 证据短语（LLM 引用的用户原话片段）
 * @param shouldSave LLM 认为是否应该保存（本地仍需通过 confidence 阈值和 NicknameValidator 校验）
 */
@Serializable
data class NicknameResolution(
    val intent: String = "NONE",
    val nickname: String? = null,
    val confidence: Float = 0f,
    val evidence: String = "",
    val shouldSave: Boolean = false
)

/**
 * 首次见面元数据（Task 12）
 *
 * LLM 在首次见面场景输出，用于本地校验问候是否达成两个核心目标：
 * 1. 自我介绍（introducedSelf）
 * 2. 询问称呼（askedForNickname）
 */
@Serializable
data class FirstMeetingMeta(
    val introducedSelf: Boolean = false,
    val askedForNickname: Boolean = false
)

/**
 * 配置变更建议（配置模式下 LLM 输出）
 *
 * 只有用户明确要修改的字段才非 null。
 * ChatInteractor 收到后通过 AgentConfigEditor.update 应用变更。
 */
@Serializable
data class ConfigUpdate(
    val name: String? = null,
    val gender: String? = null,
    val age: Int? = null,
    val background: String? = null,
    val personality: List<String>? = null,
    val speakingStyle: String? = null,
    val selfNickname: String? = null,
    val nicknameForUser: String? = null,
    val relationshipToUser: String? = null,
    val catchphrases: List<String>? = null,
    val interests: List<String>? = null,
    val taboos: List<String>? = null,
    /** LLM 对本次配置变更的简短说明（如"已将名字改为小雅"） */
    val summary: String = ""
)

/**
 * 作息调整请求（由 LLM 输出，ChatInteractor 检查 shouldAdjust 后调用 ScheduleAdjuster）
 *
 * v3 事件驱动架构：LLM 输出具体的调整类型和参数，ScheduleAdjuster 局部修改 slots
 * 不再调 LLM 重新生成全天作息（省一次调用 + 保留已完成时段）
 *
 * 事件类型：
 * - EXTEND: 延长当前时段（如打游戏上瘾多玩会儿）
 * - SHORTEN: 缩短当前时段（如提前结束工作）
 * - SKIP: 跳过下一时段（如不洗澡直接睡觉）
 * - REPLACE: 替换当前时段活动内容（如把"工作"改为"陪用户聊天"）
 * - INSERT: 当前时段后插入新时段（如加一段陪聊时间）
 * - SHIFT: 后移后续时段（如所有时段顺延 30 分钟）
 */
@Serializable
data class ScheduleAdjustment(
    val shouldAdjust: Boolean = false,
    val reason: String = "",
    /** 调整类型：EXTEND / SHORTEN / SKIP / REPLACE / INSERT / SHIFT，空字符串表示未指定 */
    val adjustmentType: String = "",
    /** 调整参数（分钟数）：EXTEND/SHORTEN/SHIFT 的时长，INSERT 的新时段时长 */
    val durationMinutes: Int = 0,
    /** REPLACE/INSERT 的新活动内容 */
    val newActivity: String = "",
    /** REPLACE/INSERT 的新状态：normal/busy/idle/unavailable */
    val newState: String = ""
)

/**
 * 单条回复项（多条消息场景）
 *
 * emoji 字段：若 LLM 输出 emoji（如 "😄"），该条作为纯表情消息，不合成语音、不入 text。
 * 与 replyText 互斥：有 emoji 时 replyText 应为空。
 *
 * emotion 字段：该条回复的情绪标签（neutral/happy/calm）。
 * TTS 合成时按此选样本和参数。空字符串或未知值时 fallback 到 neutral。
 */
@Serializable
data class ReplyItem(
    val replyText: String = "",
    val action: String = "",
    val directorPrompt: String = "",
    val emoji: String = "",
    val emotion: String = ""
)

@Serializable
data class MemoryUpdate(
    val type: String,          // user_profile | event | preference | relationship
    val category: String = "",
    val content: String,
    val importance: Int = 3
)

/**
 * 未来事件（LLM 从用户消息中提取）
 * date 格式 "yyyy-MM-dd"，description 为事件描述
 */
@Serializable
data class FutureEventItem(
    val date: String,
    val description: String
)

/**
 * 承诺/约定/提醒（LLM 从对话中提取）
 *
 * type：
 * - appointment：双方约定（如"下午3点一起看电影"）
 * - promise：Agent 承诺（如"明天我帮你查 XXX"）
 * - reminder：提醒用户（如"明天叫你起床"）
 */
@Serializable
data class CommitmentItem(
    val type: String,           // appointment / promise / reminder
    val content: String,
    val triggerAt: String? = null,   // ISO 8601 字符串
    val participants: String = "agent"
)

/**
 * 承诺状态更新（LLM 从对话中识别用户完成/取消承诺）
 *
 * status：
 * - completed：用户说"看完了""做完了"
 * - cancelled：用户说"算了吧""不用了"
 */
@Serializable
data class CommitmentUpdateItem(
    val content: String,        // 承诺内容关键词
    val status: String          // completed / cancelled
)

/**
 * TTS 请求（OpenAI 兼容 + MiMo 导演模式）
 */
@Serializable
data class TtsRequest(
    val model: String,
    val messages: List<TtsMessage>,
    val audio: TtsAudioConfig? = null
)

@Serializable
data class TtsMessage(
    val role: String,          // user = 导演指令，assistant = 要合成的文本
    val content: String
)

@Serializable
data class TtsAudioConfig(
    val format: String = "wav"
)

/**
 * TTS 响应（OpenAI 兼容 chat/completions 格式）
 *
 * MiMo 返回结构：
 * {
 *   "choices": [
 *     { "message": { "audio": { "data": "<base64>", "format": "wav" } } }
 *   ]
 * }
 * 音频数据在 choices[0].message.audio.data（base64 编码）
 */
@Serializable
data class TtsResponse(
    val choices: List<TtsChoice> = emptyList()
)

@Serializable
data class TtsChoice(
    val message: TtsResponseMessage? = null
)

@Serializable
data class TtsResponseMessage(
    val audio: TtsResponseAudio? = null
)

@Serializable
data class TtsResponseAudio(
    val data: String? = null,            // base64 编码的音频
    val format: String? = null,
    @SerialName("download_url")
    val downloadUrl: String? = null      // 部分实现可能返回下载链接
)

/**
 * voiceclone 请求（带样本音频）
 */
@Serializable
data class VoiceCloneRequest(
    val model: String,                    // mimo-v2.5-tts-voiceclone
    val messages: List<TtsMessage>,
    val audio: VoiceCloneAudioInput
)

@Serializable
data class VoiceCloneAudioInput(
    val format: String = "wav",
    /**
     * 音色来源：
     * - voiceclone：base64 编码的样本音频（Data URL）
     * - preset：预置音色 ID（如 mimo_default）
     * - voicedesign：不支持此字段，必须为 null 以在序列化时省略
     */
    val voice: String? = null
)
