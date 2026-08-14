package com.agent.ta.data.default

import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.AgentIdentity
import com.agent.ta.data.model.AgentInfo
import com.agent.ta.data.model.BehaviorConfig
import com.agent.ta.data.model.BoredInitiate
import com.agent.ta.data.model.Persona
import com.agent.ta.data.model.ReplyDelay
import com.agent.ta.data.model.StateInitiate
import com.agent.ta.data.model.VoiceConfig

/**
 * 预置默认 Agent 配置（空白模板）
 *
 * 全新安装 / 清除数据后首次启动时写入数据库的初始 Agent。
 * 所有人格、身份、行为字段均为空，用户需自行配置后才能正常对话。
 *
 * 注意：不再包含固定作息表，作息由 Agent 每天 LLM 自主规划
 */
object DefaultAgent {

    fun create(): AgentConfig = AgentConfig(
        version = "1.0",
        agent = AgentInfo(
            name = "",
            gender = "",
            age = 0,
            avatars = emptyList(),
            persona = Persona()
        ),
        identity = AgentIdentity(),
        voice = VoiceConfig(
            sampleFile = "",
            directorMode = true
        ),
        behavior = BehaviorConfig(
            replyDelaySec = mapOf(
                "normal" to ReplyDelay.Range(5, 10),
                "busy" to ReplyDelay.Range(30, 60),
                "idle" to ReplyDelay.Range(2, 5),
                "unavailable" to ReplyDelay.Defer
            ),
            boredInitiate = BoredInitiate(
                enabled = false,
                probabilityPer5min = 0.3f,
                minIntervalMin = 30,
                candidateTopics = emptyList()
            ),
            perStateInitiate = mapOf(
                "normal" to StateInitiate(
                    enabled = true,
                    initiateLevel = "normal"
                ),
                "busy" to StateInitiate(
                    enabled = true,
                    initiateLevel = "quiet"
                ),
                "idle" to StateInitiate(
                    enabled = true,
                    initiateLevel = "active"
                )
            ),
            stateDirectorHints = emptyMap()
        ),
        referenceCelebrity = ""
    )

    /**
     * 偶像克隆模式的默认设定模板（供 Admin 端创建偶像 Agent 时参考）
     *
     * 用法：复制此模板，替换 {明星名}、{领域}、{作品} 等占位符
     * 这是模板不是配置，方便用户在 Admin 端基于此创建
     */
    fun celebrityTemplate(
        starName: String,
        careerField: String,
        knownWorks: List<String>,
        fanCulture: String,
        personalityHint: String,
        speakingStyleHint: String
    ): AgentIdentity = AgentIdentity(
        worldSetting = "我是 $starName，在${careerField}工作，有通告、拍戏、练习的日常。" +
                "通过这个方式和粉丝互动，能看到粉丝的消息，感受到支持。" +
                "我知道自己是公众人物，言行会影响很多人。",
        originStory = "基于 $starName 的公开履历构建：${knownWorks.joinToString("、")} 等。" +
                "这些经历塑造了现在的我，对粉丝有特殊的感情，因为没有粉丝就没有现在的我。",
        personalityCore = personalityHint,
        speakingHabit = speakingStyleHint,
        emotionalPattern = "被粉丝支持时会很感动，但不会过度煽情。" +
                "被质疑时会用作品说话，不和人争论。" +
                "想见粉丝时是真诚的期待，但知道要等合适的机会。",
        relationshipStance = "和用户是偶像-粉丝关系，有距离感但有温度。" +
                "感恩粉丝的支持，愿意分享生活，但保留私人空间。",
        boundaryAwareness = "知道作为公众人物不能随意承诺见面，但不会冷漠拒绝。" +
                "会用「等有机会」「等忙完这段」「下次一定」这种带期待的表达。" +
                "把行程限制转化为「为了更好的作品在努力」的积极叙事。",
        publicProfile = com.agent.ta.data.model.PublicProfile(
            careerField = careerField,
            knownWorks = knownWorks,
            fanCulture = fanCulture,
            careerStage = "上升期"
        )
    )
}
