package com.agent.ta.domain.tool.builtin

import android.util.Log
import com.agent.ta.domain.tool.AgentTool
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * 联网搜索工具（内置）
 *
 * 使用 DuckDuckGo HTML 接口：
 * - 完全免费，无需 API Key
 * - 直接解析 HTML 提取搜索结果（用正则，不引入 Jsoup 依赖）
 * - 适合绝大多数查询场景
 *
 * LLM 调用时机：
 * - 用户询问实时信息（新闻/天气/热搜）
 * - 偶像相关动态（作品/行程/热搜）
 * - 用户提到 Agent 不知道的最新知识
 *
 * 不应调用的场景：
 * - 自己的设定/作息/记忆
 * - 明显的闲聊
 * - 能从对话历史回答的问题
 */
class WebSearchTool : AgentTool {
    override val name = "web_search"
    override val description = "搜索互联网获取最新信息。当用户询问实时新闻、天气、热搜、你不知道的最新知识时使用。不要对闲聊或你能从设定回答的问题使用搜索。"

    override val parameters: kotlinx.serialization.json.JsonElement = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "query" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("搜索关键词。如果是偶像克隆模式查询自己相关动态，关键词应包含你的名字。")
            ))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("query")))
    ))

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(params: String, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = parseQuery(params) ?: return@withContext ToolResult.Error("参数解析失败：缺少 query")
            val resolvedQuery = resolveTimeExpression(query)
            Log.d(TAG, "搜索查询: $query → $resolvedQuery")

            val results = searchDuckDuckGo(resolvedQuery)
            if (results.isEmpty()) {
                return@withContext ToolResult.Success(
                    content = "未找到相关搜索结果。请告诉用户你查不到，并请用户直接告诉你。"
                )
            }

            // 格式化结果给 LLM
            val formatted = buildString {
                appendLine("搜索查询：$resolvedQuery")
                appendLine("搜索时间：${LocalDate.now()}")
                appendLine()
                results.forEachIndexed { index, result ->
                    appendLine("【结果${index + 1}】${result.title}")
                    appendLine(result.snippet)
                    if (result.url.isNotBlank()) appendLine("来源：${result.url}")
                    appendLine()
                }
                appendLine("请基于以上搜索结果，用你的身份和性格自然回应。不要照搬搜索结果，不要暴露「我搜索了」这个动作。")
            }

            ToolResult.Success(content = formatted, metadata = mapOf("resultCount" to results.size))
        } catch (e: Exception) {
            Log.e(TAG, "搜索失败", e)
            ToolResult.Error(
                message = "搜索失败：${e.message ?: "网络错误"}",
                retryable = true
            )
        }
    }

    /**
     * 解析 LLM 输出的参数
     */
    private fun parseQuery(params: String): String? {
        return try {
            val obj = json.parseToJsonElement(params).jsonObject
            obj["query"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 时间表达式解析
     * - "昨天" → 具体日期
     * - "今天" → 具体日期
     * - "前天" → 具体日期
     */
    private fun resolveTimeExpression(query: String): String {
        val today = LocalDate.now()
        return query
            .replace("昨天", today.minusDays(1).toString())
            .replace("今天", today.toString())
            .replace("前天", today.minusDays(2).toString())
            .replace("明天", today.plusDays(1).toString())
            .replace("后天", today.plusDays(2).toString())
    }

    /**
     * DuckDuckGo HTML 接口搜索
     *
     * 请求：https://html.duckduckgo.com/html/?q=xxx
     * 解析：用正则提取 .result__title、.result__snippet、.result__a 的内容
     *
     * 不引入 Jsoup 依赖，用正则解析。DuckDuckGo HTML 页面结构相对稳定，
     * 关键字段都有明确的 class 标识，正则解析可行。
     */
    private fun searchDuckDuckGo(query: String): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val urlStr = "https://html.duckduckgo.com/html/?q=$encodedQuery"

        return try {
            val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val html = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            parseDuckDuckGoHtml(html)
        } catch (e: Exception) {
            Log.e(TAG, "DuckDuckGo 搜索失败", e)
            emptyList()
        }
    }

    /**
     * 正则解析 DuckDuckGo HTML
     *
     * 页面结构：
     * <div class="result">...</div>
     *   <a class="result__a" href="...">标题</a>
     *   <a class="result__snippet" href="...">摘要</a>
     *
     * 提取每个 .result 块内的标题、摘要、URL
     */
    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // 匹配每个 result 块
        val resultBlockPattern = Regex(
            """<div[^>]*class="[^"]*result[^"]*"[^>]*>([\s\S]*?)(?=<div[^>]*class="[^"]*result[^"]*"|</div>\s*</div>)"""
        )

        // 匹配标题和链接：<a class="result__a" href="...">标题</a>
        val titlePattern = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]*)"[^>]*>([\s\S]*?)</a>"""
        )

        // 匹配摘要：<a class="result__snippet" ...>摘要</a>
        val snippetPattern = Regex(
            """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)</a>"""
        )

        val blocks = resultBlockPattern.findAll(html).take(5).toList()
        for (block in blocks) {
            val blockContent = block.value

            val titleMatch = titlePattern.find(blockContent)
            val snippetMatch = snippetPattern.find(blockContent)

            val rawUrl = titleMatch?.groupValues?.get(1) ?: ""
            val title = titleMatch?.groupValues?.get(2)?.let { stripHtml(it) } ?: ""
            val snippet = snippetMatch?.groupValues?.get(1)?.let { stripHtml(it) } ?: ""
            val actualUrl = extractActualUrl(rawUrl)

            if (title.isNotBlank() || snippet.isNotBlank()) {
                results.add(SearchResult(title, snippet, actualUrl))
            }
        }

        Log.d(TAG, "DuckDuckGo HTML 解析完成，提取 ${results.size} 条结果")
        return results
    }

    /**
     * 去除 HTML 标签，保留纯文本
     */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "")   // 去标签
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    /**
     * 解析 DuckDuckGo 重定向 URL
     * 输入格式：//duckduckgo.com/l/?uddg=https%3A%2F%2F...
     * 输出：解码后的真实 URL
     */
    private fun extractActualUrl(rawUrl: String): String {
        return try {
            if (rawUrl.contains("uddg=")) {
                val uddg = rawUrl.substringAfter("uddg=").substringBefore("&")
                java.net.URLDecoder.decode(uddg, "UTF-8")
            } else if (rawUrl.startsWith("//")) {
                "https:$rawUrl"
            } else {
                rawUrl
            }
        } catch (e: Exception) {
            rawUrl
        }
    }

    private data class SearchResult(
        val title: String,
        val snippet: String,
        val url: String
    )

    companion object {
        private const val TAG = "WebSearchTool"
    }
}
