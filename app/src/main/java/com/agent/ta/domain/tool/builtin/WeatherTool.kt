package com.agent.ta.domain.tool.builtin

import android.util.Log
import com.agent.ta.domain.tool.AgentTool
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * 天气查询工具（内置）
 *
 * 使用 Open-Meteo 免费 API：
 * - 无需 API Key
 * - 通过 geocoding-api 获取城市经纬度
 * - 通过 open-meteo 获取天气预报
 *
 * LLM 调用时机：
 * - 用户问"你那边天气怎么样""下雨了吗"
 * - Agent 想分享天气相关的话题
 */
class WeatherTool : AgentTool {
    override val name = "get_weather"
    override val description = "查询指定城市的天气情况。当用户问天气、或者你想分享天气相关话题时使用。"

    override val parameters: JsonElement = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "city" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("城市名（如北京、上海、杭州）")
            ))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("city")))
    ))

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(params: String, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            val city = parseCity(params) ?: return@withContext ToolResult.Error("参数解析失败：缺少 city")
            Log.d(TAG, "查询天气: $city")

            // 1. 获取城市经纬度
            val location = geocode(city) ?: return@withContext ToolResult.Error(
                "找不到城市：$city",
                retryable = false
            )

            // 2. 获取天气
            val weather = getWeather(location.latitude, location.longitude, location.timezone)

            val content = buildString {
                appendLine("城市：$city")
                appendLine("当前天气：${weather.description}")
                appendLine("温度：${weather.temperature}°C")
                appendLine("体感温度：${weather.apparentTemperature}°C")
                appendLine("湿度：${weather.humidity}%")
                appendLine("风速：${weather.windSpeed} km/h")
                if (weather.precipitation > 0) {
                    appendLine("降水：${weather.precipitation} mm")
                }
                appendLine()
                appendLine("请基于以上天气信息，用你的身份和性格自然回应。不要列出所有数据，挑重点说，像真人聊天一样。")
            }

            ToolResult.Success(content = content)
        } catch (e: Exception) {
            Log.e(TAG, "天气查询失败", e)
            ToolResult.Error(
                message = "天气查询失败：${e.message ?: "网络错误"}",
                retryable = true
            )
        }
    }

    private fun parseCity(params: String): String? {
        return try {
            val obj = json.parseToJsonElement(params).jsonObject
            obj["city"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    private data class Location(
        val latitude: Double,
        val longitude: Double,
        val timezone: String
    )

    private data class WeatherInfo(
        val temperature: Double,
        val apparentTemperature: Double,
        val humidity: Int,
        val windSpeed: Double,
        val precipitation: Double,
        val description: String
    )

    /**
     * 用 Open-Meteo Geocoding API 获取城市经纬度
     * https://geocoding-api.open-meteo.com/v1/search?name=北京&count=1&language=zh
     */
    private fun geocode(city: String): Location? {
        val encoded = URLEncoder.encode(city, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=zh"

        return try {
            val response = java.net.URL(url).readText()
            val obj = json.parseToJsonElement(response).jsonObject
            val results = obj["results"] ?: return null
            val firstResult = results.toString().let {
                json.parseToJsonElement(it)
            }
            // 重新解析为 JsonObject
            val resultsArr = obj["results"].toString()
            val arr = json.parseToJsonElement(resultsArr).let { it as? kotlinx.serialization.json.JsonArray }
                ?: return null
            if (arr.isEmpty()) return null

            val first = arr[0].jsonObject
            Location(
                latitude = first["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null,
                longitude = first["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null,
                timezone = first["timezone"]?.jsonPrimitive?.contentOrNull ?: "Asia/Shanghai"
            )
        } catch (e: Exception) {
            Log.e(TAG, "geocode 失败: $city", e)
            null
        }
    }

    /**
     * 用 Open-Meteo API 获取天气
     * https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4&current=temperature_2m,...
     */
    private fun getWeather(latitude: Double, longitude: Double, timezone: String): WeatherInfo {
        val url = "https://api.open-meteo.com/v1/forecast?" +
                "latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,precipitation,weather_code" +
                "&timezone=$timezone"

        val response = java.net.URL(url).readText()
        val obj = json.parseToJsonElement(response).jsonObject
        val current = obj["current"]?.jsonObject ?: throw Exception("无法获取当前天气")

        val weatherCode = current["weather_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return WeatherInfo(
            temperature = current["temperature_2m"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            apparentTemperature = current["apparent_temperature"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            humidity = current["relative_humidity_2m"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            windSpeed = current["wind_speed_10m"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            precipitation = current["precipitation"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            description = describeWeatherCode(weatherCode)
        )
    }

    /**
     * WMO Weather Code 转中文描述
     */
    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "晴朗"
        1 -> "大致晴朗"
        2 -> "局部多云"
        3 -> "阴天"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "阵雨"
        81 -> "强阵雨"
        82 -> "暴雨"
        85 -> "阵雪"
        86 -> "强阵雪"
        95 -> "雷暴"
        96, 99 -> "冰雹雷暴"
        else -> "未知"
    }

    companion object {
        private const val TAG = "WeatherTool"
    }
}
