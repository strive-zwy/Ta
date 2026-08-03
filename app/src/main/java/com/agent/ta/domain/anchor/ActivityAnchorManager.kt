package com.agent.ta.domain.anchor

import android.content.Context
import android.util.Log
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.DailySlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 活动锚点管理器
 *
 * 职责：
 * 1. 维护当前 ActivityAnchor（应用侧权威事实）
 * 2. 从作息表当前时段派生默认 anchor（SCHEDULE 来源）
 * 3. 接受 LLM 通过 set_activity 工具显式设置 anchor（LLM 来源）
 * 4. 过期检测：LLM anchor 到期后自动回退到作息表派生
 * 5. 持久化 LLM anchor 到 SharedPreferences，App 重启后恢复
 *
 * 优先级：
 * - LLM anchor 未过期 → 使用 LLM anchor
 * - LLM anchor 已过期 或 无 LLM anchor → 使用作息表派生 anchor
 *
 * 状态切换时：
 * - 作息表时段切换 → 重新派生 SCHEDULE anchor，并清除未过期的 LLM anchor
 *   （因为时段切换意味着活动自然结束，LLM 的微调不再适用）
 */
class ActivityAnchorManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentAnchor = MutableStateFlow<ActivityAnchor?>(null)
    val currentAnchor: StateFlow<ActivityAnchor?> = _currentAnchor.asStateFlow()

    /** LLM 设置的 anchor（未过期时优先于 SCHEDULE anchor） */
    @Volatile private var llmAnchor: ActivityAnchor? = null

    init {
        // 启动时从持久化恢复 LLM anchor
        loadPersistedLlmAnchor()
    }

    /**
     * 获取当前有效 anchor（自动处理过期回退）
     *
     * @param todaySchedule 今日作息（用于派生 SCHEDULE anchor）
     * @return 当前有效 anchor，null 表示无法确定（如作息表为空）
     */
    fun getEffectiveAnchor(todaySchedule: List<DailySlot>): ActivityAnchor? {
        val now = System.currentTimeMillis()

        // 1. 检查 LLM anchor 是否仍有效
        val llm = llmAnchor
        if (llm != null && !llm.isExpired(now)) {
            _currentAnchor.value = llm
            return llm
        }

        // 2. LLM anchor 过期或不存在 → 派生 SCHEDULE anchor
        if (llm != null && llm.isExpired(now)) {
            Log.d(TAG, "LLM anchor 已过期（${llm.activity}），回退到作息表派生")
            clearLlmAnchor()
        }

        val scheduleAnchor = deriveFromSchedule(todaySchedule, now)
        _currentAnchor.value = scheduleAnchor
        return scheduleAnchor
    }

    /**
     * LLM 显式设置当前活动
     *
     * @param activity 活动内容（如"洗澡"、"陪她聊天"）
     * @param state 对应的宏观状态
     * @param durationMinutes 预计持续时长（分钟），到期后自动回退到作息表
     * @param replyable 是否可回复消息（null 时用关键词推断）
     * @return 设置后的 anchor
     */
    fun setActivityFromLlm(
        activity: String,
        state: AgentState,
        durationMinutes: Int,
        replyable: Boolean? = null
    ): ActivityAnchor {
        val now = System.currentTimeMillis()
        val effectiveReplyable = replyable ?: inferReplyable(activity)
        val anchor = ActivityAnchor(
            activity = activity,
            state = state,
            startedAt = now,
            expectedEnd = now + durationMinutes * 60_000L,
            source = AnchorSource.LLM,
            replyable = effectiveReplyable
        )
        llmAnchor = anchor
        persistLlmAnchor(anchor)
        _currentAnchor.value = anchor
        Log.d(TAG, "LLM 设置活动：${activity}（${durationMinutes}分钟），状态=${state.id}，可回复=${effectiveReplyable}")
        return anchor
    }

    /**
     * 作息表时段切换时调用
     *
     * 清除未过期的 LLM anchor（因为时段切换意味着活动自然结束），
     * 然后从新时段派生 SCHEDULE anchor。
     */
    fun onSlotChanged(todaySchedule: List<DailySlot>) {
        if (llmAnchor != null) {
            Log.d(TAG, "时段切换，清除 LLM anchor（${llmAnchor?.activity}）")
            clearLlmAnchor()
        }
        val now = System.currentTimeMillis()
        val scheduleAnchor = deriveFromSchedule(todaySchedule, now)
        _currentAnchor.value = scheduleAnchor
        Log.d(TAG, "时段切换，派生新 SCHEDULE anchor：${scheduleAnchor?.activity ?: "无"}")
    }

    /**
     * 从作息表当前时段派生 anchor
     */
    private fun deriveFromSchedule(schedule: List<DailySlot>, now: Long): ActivityAnchor? {
        if (schedule.isEmpty()) return null

        val zone = ZoneId.of("Asia/Shanghai")
        val nowTime = ZonedDateTime.now(zone).toLocalTime()
        val todayDate = ZonedDateTime.now(zone).toLocalDate()

        val currentSlot = schedule.firstOrNull { slot ->
            val start = parseEndTime(slot.start)
            val end = parseEndTime(slot.end)
            if (start <= end) {
                nowTime >= start && nowTime < end
            } else {
                // 跨午夜：now 在 [start, 24:00) 或 [00:00, end) 内都算当前时段
                nowTime >= start || nowTime < end
            }
        } ?: return null

        val state = AgentState.fromId(currentSlot.state) ?: AgentState.IDLE

        val startLt = parseEndTime(currentSlot.start)
        val endLt = parseEndTime(currentSlot.end)
        val isCrossMidnight = startLt > endLt && endLt != LocalTime.MIDNIGHT

        // 计算 startedAt 和 expectedEnd，正确处理跨午夜时段
        // - 普通时段：今天 start 到今天 end
        // - 跨午夜时段 + nowTime >= start（晚间）：今天 start 到明天 end
        // - 跨午夜时段 + nowTime < start（凌晨，昨晚睡眠延续）：昨天 start 到今天 end
        // - "24:00" 结束：今天 23:59:59（视为当天结束，不跨午夜）
        val startDate: ZonedDateTime
        val endDate: ZonedDateTime

        if (currentSlot.end == "24:00" || currentSlot.end == "24:00:00") {
            // 24:00 表示当天结束，按 23:59:59 处理
            startDate = todayDate.atTime(startLt).atZone(zone)
            endDate = todayDate.atTime(LocalTime.of(23, 59, 59)).atZone(zone)
        } else if (isCrossMidnight) {
            if (nowTime >= startLt) {
                // 晚间：今天 start 到明天 end
                startDate = todayDate.atTime(startLt).atZone(zone)
                endDate = todayDate.plusDays(1).atTime(endLt).atZone(zone)
            } else {
                // 凌晨：昨天 start 到今天 end
                startDate = todayDate.minusDays(1).atTime(startLt).atZone(zone)
                endDate = todayDate.atTime(endLt).atZone(zone)
            }
        } else {
            // 普通时段：今天 start 到今天 end
            startDate = todayDate.atTime(startLt).atZone(zone)
            endDate = todayDate.atTime(endLt).atZone(zone)
        }

        return ActivityAnchor(
            activity = currentSlot.activity,
            state = state,
            startedAt = startDate.toInstant().toEpochMilli(),
            expectedEnd = endDate.toInstant().toEpochMilli(),
            source = AnchorSource.SCHEDULE,
            slotStart = currentSlot.start,
            slotEnd = currentSlot.end,
            replyable = inferReplyable(currentSlot.activity)
        )
    }

    /**
     * 推断活动是否可回复消息
     *
     * 不可回复的活动特征：需要双手、全神贯注、或不宜看手机
     * 可回复的活动特征：可以随时停下来看手机、双手空闲
     *
     * 关键词列表覆盖常见活动，未匹配的默认可回复（避免误判影响正常对话）
     */
    private fun inferReplyable(activity: String): Boolean {
        val lower = activity.lowercase()
        // 不可回复活动关键词
        val nonReplyableKeywords = listOf(
            // 运动
            "打球", "篮球", "足球", "羽毛球", "网球", "乒乓", "排球", "棒球",
            "健身", "跑步", "游泳", "瑜伽", "跳绳", "骑行", "骑车", "爬山",
            // 清洁
            "洗澡", "洗头", "淋浴", "泡澡",
            // 家务
            "做饭", "炒菜", "下厨", "烹饪", "洗碗", "拖地", "打扫", "洗衣服",
            // 交通
            "开车", "驾车",
            // 乐器
            "弹琴", "练琴", "吉他", "钢琴", "小提琴", "鼓",
            // 创作
            "画画", "绘画", "素描",
            // 其他
            "手术", "诊疗", "按摩", "理发"
        )
        return nonReplyableKeywords.none { keyword -> lower.contains(keyword) }
    }

    private fun parseEndTime(timeStr: String): LocalTime {
        return if (timeStr == "24:00" || timeStr == "24:00:00") {
            LocalTime.MIDNIGHT
        } else {
            LocalTime.parse(timeStr)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 持久化（仅持久化 LLM anchor，SCHEDULE anchor 每次重新派生）
    // ═══════════════════════════════════════════════════════════════════════════

    private fun persistLlmAnchor(anchor: ActivityAnchor) {
        try {
            val json = JSONObject().apply {
                put("activity", anchor.activity)
                put("state", anchor.state.id)
                put("startedAt", anchor.startedAt)
                put("expectedEnd", anchor.expectedEnd)
                put("source", anchor.source.name)
                put("replyable", anchor.replyable)
            }
            prefs.edit().putString(KEY_LLM_ANCHOR, json.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "持久化 LLM anchor 失败", e)
        }
    }

    private fun loadPersistedLlmAnchor() {
        try {
            val jsonStr = prefs.getString(KEY_LLM_ANCHOR, null) ?: return
            val json = JSONObject(jsonStr)
            val anchor = ActivityAnchor(
                activity = json.getString("activity"),
                state = AgentState.fromId(json.getString("state")) ?: AgentState.IDLE,
                startedAt = json.getLong("startedAt"),
                expectedEnd = json.getLong("expectedEnd"),
                source = AnchorSource.LLM,
                replyable = json.optBoolean("replyable", true)
            )
            // 只恢复未过期的 anchor
            if (!anchor.isExpired()) {
                llmAnchor = anchor
                Log.d(TAG, "恢复未过期的 LLM anchor：${anchor.activity}，可回复=${anchor.replyable}")
            } else {
                Log.d(TAG, "持久化的 LLM anchor 已过期，丢弃")
                clearLlmAnchor()
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载 LLM anchor 失败", e)
            clearLlmAnchor()
        }
    }

    private fun clearLlmAnchor() {
        llmAnchor = null
        prefs.edit().remove(KEY_LLM_ANCHOR).apply()
    }

    companion object {
        private const val TAG = "ActivityAnchorManager"
        private const val PREFS_NAME = "activity_anchor_prefs"
        private const val KEY_LLM_ANCHOR = "llm_anchor_json"
    }
}
