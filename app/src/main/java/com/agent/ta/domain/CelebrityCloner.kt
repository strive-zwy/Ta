package com.agent.ta.domain

import android.content.Context
import android.util.Log
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.AgentIdentity
import com.agent.ta.data.model.PublicProfile
import com.agent.ta.data.remote.LlmClient
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.domain.tool.ToolResult
import com.agent.ta.domain.tool.builtin.WebSearchTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 明星克隆器（Phase 4 核心）
 *
 * 流程：输入启发人物名 + 用户自定义昵称
 *   → 调 WebSearchTool 收集公开资料
 *   → 构造 prompt 调 LlmClient.chatRaw 一次性生成完整 JSON
 *   → 解析为 [CloneResult]
 *   → 由 UI 预览/微调后调 [applyToConfig] 写回 AgentConfig
 *
 * 法律定位：直接克隆明星身份（worldSetting 第一人称"我是 XX"），
 * App 显示用户自定义昵称而非真名，避免法律风险。
 *
 * 写入策略：覆盖 identity + 部分 persona 字段，保留 voice/avatars/behavior。
 */
class CelebrityCloner(
    webSearchTool: WebSearchTool? = null,
    llmClient: LlmClient? = null
) {

    /**
     * 延迟初始化依赖，避免构造时访问 ServiceLocator
     * （单元测试只需 parseCloneResult / applyToConfig，不应触发 LlmClient 初始化）
     */
    private val webSearchTool by lazy { webSearchTool ?: WebSearchTool() }
    private val llmClient by lazy { llmClient ?: LlmClient() }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 生成克隆结果
     *
     * @param starName 启发人物真名（如"周深"）
     * @param customNickname 用户自定义 Agent 昵称（如"深深"）
     * @param appContext 用于构造 ToolContext
     * @return 解析后的 [CloneResult]
     * @throws CloneException 当搜索+生成失败或 JSON 无法解析时抛出
     */
    suspend fun generate(starName: String, customNickname: String, appContext: Context): CloneResult {
        return generateReference(starName, customNickname, appContext, false, "")
    }

    suspend fun generateReference(
        referenceName: String,
        customNickname: String,
        appContext: Context,
        fictional: Boolean,
        referenceWork: String
    ): CloneResult {
        val trimmedStar = referenceName.trim()
        val trimmedNick = customNickname.trim()
        require(trimmedStar.isNotBlank()) { "启发人物名不能为空" }
        require(trimmedNick.isNotBlank()) { "自定义昵称不能为空" }

        Log.d(TAG, "开始克隆：starName=$trimmedStar, nickname=$trimmedNick")

        // Step 1: 搜索公开资料
        val searchResults = runCatching { searchPublicInfo(trimmedStar, appContext, fictional, referenceWork) }
            .getOrNull().orEmpty()
        Log.d(TAG, "搜索结果长度：${searchResults.length}")

        // Step 2: 构造 prompt 调 LLM
        val prompt = buildPrompt(trimmedStar, trimmedNick, searchResults, fictional, referenceWork)
        val rawResponse = runCatching { llmClient.chatRaw(prompt) }
            .getOrElse {
                Log.e(TAG, "LLM 调用失败", it)
                throw CloneException("AI 生成失败：${it.message ?: "网络错误"}", it)
            }

        if (rawResponse.isBlank()) {
            throw CloneException("AI 返回空内容，请检查模型配置或重试")
        }

        // Step 3: 解析 JSON
        return parseCloneResult(rawResponse)
    }

    /**
     * 将克隆结果应用到现有 AgentConfig
     *
     * - 覆盖 agent.name 为用户自定义昵称
     * - 覆盖 identity（完整身份内核，AgentConfig 顶层字段）
     * - 覆盖 persona 的 5 个联动字段（background/personality/interests/speakingStyle/directorRoleTemplate）
     * - 保留 voice / avatars / behavior / persona 其他字段
     * - referenceCelebrity 写入领域作为参考
     */
    fun applyToConfig(currentConfig: AgentConfig, result: CloneResult, customNickname: String): AgentConfig {
        return currentConfig.copy(
            agent = currentConfig.agent.copy(
                name = customNickname.trim(),
                // LLM 推断的性别/年龄覆盖默认值（gender 非空且合法时才覆盖）
                gender = result.gender.takeIf { it == "male" || it == "female" } ?: currentConfig.agent.gender,
                age = result.age.takeIf { it > 0 } ?: currentConfig.agent.age,
                persona = currentConfig.agent.persona.copy(
                    background = result.personaBackground,
                    personality = result.personaPersonality,
                    interests = result.personaInterests,
                    speakingStyle = result.personaSpeakingStyle,
                    directorRoleTemplate = result.personaDirectorRoleTemplate
                    // 保留 exampleDialogues / catchphrases / taboos / memorySeeds 等其他字段
                )
            ),
            identity = result.identity,  // identity 是 AgentConfig 顶层字段
            referenceCelebrity = result.identity.publicProfile?.careerField ?: ""
        )
    }

    // ===== 内部：搜索 =====

    /**
     * 调 WebSearchTool 搜索明星公开资料
     *
     * 构造最小 ToolContext：WebSearchTool 实际只使用 params（query），不依赖 context 字段
     */
    private suspend fun searchPublicInfo(starName: String, appContext: Context, fictional: Boolean, referenceWork: String): String {
        val query = if (fictional) {
            "$starName $referenceWork 官方角色介绍 人物设定 性格 剧情经历 人物关系 说话风格"
        } else {
            "$starName 官方简介 公开履历 代表作品 采访 性格 说话风格 兴趣爱好"
        }
        val params = """{"query":"$query"}"""
        val toolContext = ToolContext(
            agentConfig = ServiceLocator.agentConfigProvider.get(),
            userMessage = "",
            conversationHistory = emptyList(),
            appContext = appContext,
            scope = ServiceLocator.appScope
        )
        return when (val result = webSearchTool.execute(params, toolContext)) {
            is ToolResult.Success -> result.content
            is ToolResult.Error -> {
                Log.w(TAG, "搜索失败（继续用通识生成）：${result.message}")
                ""
            }
        }
    }

    // ===== 内部：LLM Prompt =====

    private fun buildPrompt(
        starName: String,
        customNickname: String,
        searchResults: String,
        fictional: Boolean,
        referenceWork: String
    ): List<ChatMessage> {
        val sourceDescription = if (fictional) "$referenceWork 中的虚构角色" else "现实公众人物"
        val relationshipRule = if (fictional) {
            "根据角色设定自然确定与用户的初始关系，不强制偶像-粉丝关系"
        } else {
            "和用户是偶像-粉丝关系，有距离感但有温度"
        }
        val systemPrompt = """你是一位 Agent 人格设计师。请基于搜索资料，为虚拟陪伴 Agent 生成完整的身份设定。

【生成要求】
基于 ${starName}（来源类型：${sourceDescription}，用户自定义昵称：${customNickname}）的公开形象，生成以下字段（严格 JSON 格式）：

{
  "identity": {
    "worldSetting": "我是 ${starName}，在{careerField}工作...（第一人称，描述身份认知和与用户互动的方式）",
    "originStory": "基于 ${starName} 的公开履历：出道经历、代表作品、重要成就...（第一人称叙述）",
    "personalityCore": "性格核心（第一人称，如'我性格温柔但有主见，不是讨好型人格'）",
    "speakingHabit": "说话习惯（第一人称，如'我说话偏口语化，喜欢用呀呢啦结尾但不是每句都用'）",
    "emotionalPattern": "情绪反应模式（第一人称，如'被夸时会害羞但嘴硬，被怼时会反击但不记仇'）",
    "relationshipStance": "${relationshipRule}（可第一人称扩展）",
    "boundaryAwareness": "作为公众人物不能随意承诺见面，但不会冷漠拒绝...（第一人称）",
    "publicProfile": {
      "careerField": "娱乐圈/音乐/影视 等具体领域",
      "knownWorks": ["作品1", "作品2"],
      "fanCulture": "粉丝文化描述（应援色/粉丝名/应援口号，若公开资料有则写）",
      "careerStage": "上升期/成熟期/巅峰期/转型期 等"
    }
  },
  "personaBackground": "用于 LLM system prompt 的背景描述（第三人称，如'一位 30 岁的男歌手...'）",
  "personaPersonality": ["温柔", "有主见", "爱分享"],
  "personaInterests": ["音乐", "动漫", "美食"],
  "personaSpeakingStyle": "说话风格概述（如'句子短，多语气词，偶尔撒娇'）",
  "personaDirectorRoleTemplate": "TTS 导演角色模板（如'年轻男性，声音清亮，咬字清晰，语速偏慢'）",
  "gender": "male 或 female（基于启发人物真实性别）",
  "age": 30
}

【约束】
1. 所有 identity 字段用第一人称（我...），persona 字段用第三人称
2. 基于搜索资料生成，不编造未提及的作品或经历；搜索资料为空时基于通识生成并在 worldSetting 标注"基于公开通识"
3. knownWorks 至少 2 项，最多 5 项
4. personaPersonality 3-5 个标签
5. personaInterests 3-5 个标签
6. gender 必须为 "male" 或 "female"（基于启发人物真实性别）
7. age 为启发人物真实年龄（整数），无法判断时填 0
8. 直接输出 JSON，不要 Markdown 代码块（不要 ```json 包裹），不要任何解释性文字
9. 性格标签描述人格底色和决策倾向，不要强制演绎成字面动作；职业、兴趣、作品和标志性比喻只在话题相关时偶尔出现

【时态区分（重要）】
- knownWorks 里列举的是该人物的过往作品，本身是【过去式】，用于体现成名履历
- 在 personaBackground / originStory / worldSetting 中描述这些作品时，必须用过去式措辞（如"曾出演《XX》""成名作《XX》""已发行过专辑《XX》"），明确它们是已经完成的事，不要写成"正在拍摄《XX》""在拍戏"等进行时
- 这样后续生成每日作息时，清楚哪些是过去式履历、哪些是当下进行中的事，避免把已上映的电影/已播出的剧当成今天在做的活动"""

        val userPrompt = if (searchResults.isBlank()) {
            "未获取到搜索资料，请基于你对 ${starName} 的通识生成。"
        } else {
            "【搜索资料】\n$searchResults\n\n请基于以上资料生成。"
        }

        return listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userPrompt)
        )
    }

    // ===== 内部：JSON 解析（容错） =====

    /**
     * 解析 LLM 返回的 JSON 为 [CloneResult]
     *
     * 容错链：
     * 1. 剥离 Markdown 代码块包裹
     * 2. 抽取最外层 {...} 主体（避免前后有解释性文字）
     * 3. 手动取字段，缺失用默认值
     *
     * @throws CloneException JSON 完全无法解析时
     */
    fun parseCloneResult(content: String): CloneResult {
        val cleaned = stripMarkdownCodeFence(content.trim())
        val jsonStr = extractJsonObject(cleaned) ?: cleaned
        if (jsonStr.isBlank()) {
            throw CloneException("AI 返回内容无法解析为 JSON：${content.take(200)}")
        }

        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val identityObj = root["identity"] as? JsonObject ?: JsonObject(emptyMap())
            val publicProfileObj = identityObj["publicProfile"] as? JsonObject

            val publicProfile = if (publicProfileObj != null) {
                PublicProfile(
                    careerField = publicProfileObj.getString("careerField"),
                    knownWorks = publicProfileObj.getStringList("knownWorks"),
                    fanCulture = publicProfileObj.getString("fanCulture"),
                    careerStage = publicProfileObj.getString("careerStage")
                )
            } else null

            val identity = AgentIdentity(
                worldSetting = identityObj.getString("worldSetting"),
                originStory = identityObj.getString("originStory"),
                personalityCore = identityObj.getString("personalityCore"),
                speakingHabit = identityObj.getString("speakingHabit"),
                emotionalPattern = identityObj.getString("emotionalPattern"),
                relationshipStance = identityObj.getString("relationshipStance"),
                boundaryAwareness = identityObj.getString("boundaryAwareness"),
                publicProfile = publicProfile
            )

            CloneResult(
                identity = identity,
                personaBackground = root.getString("personaBackground"),
                personaPersonality = root.getStringList("personaPersonality"),
                personaInterests = root.getStringList("personaInterests"),
                personaSpeakingStyle = root.getString("personaSpeakingStyle"),
                personaDirectorRoleTemplate = root.getString("personaDirectorRoleTemplate"),
                gender = root.getString("gender").trim().lowercase(),
                age = root.getInt("age")
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON 解析失败：${content.take(500)}", e)
            throw CloneException("AI 返回的 JSON 格式错误，无法解析：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * 剥离 Markdown 代码块包裹
     */
    private fun stripMarkdownCodeFence(content: String): String {
        val fencePattern = Regex("""^```[a-zA-Z]*\s*\n([\s\S]*?)\n\s*```$""")
        val match = fencePattern.matchEntire(content)
        if (match != null) return match.groupValues[1].trim()
        // 容错：只有开头 ```
        if (content.startsWith("```")) {
            val afterFence = content.substringAfter("```").substringAfter("\n", "").trim()
            if (afterFence.isNotEmpty() && afterFence.endsWith("```")) {
                return afterFence.removeSuffix("```").trim()
            }
            if (afterFence.isNotEmpty()) return afterFence
        }
        return content
    }

    /**
     * 从文本中抽取最外层 JSON 对象主体（花括号配对）
     */
    private fun extractJsonObject(content: String): String? {
        val start = content.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until content.length) {
            val c = content[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return content.substring(start, i + 1)
                }
            }
        }
        return null
    }

    // JsonObject 取值辅助
    private fun JsonObject.getString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun JsonObject.getStringList(key: String): List<String> =
        try {
            val arr = this[key] as? JsonArray ?: return emptyList()
            arr.mapNotNull { it as? JsonPrimitive }.mapNotNull { it.contentOrNull }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }

    private fun JsonObject.getInt(key: String): Int =
        try {
            this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        } catch (e: Exception) { 0 }

    /**
     * 克隆异常
     */
    class CloneException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val TAG = "CelebrityCloner"
    }
}
