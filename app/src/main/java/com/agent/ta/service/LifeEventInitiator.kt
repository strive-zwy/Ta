package com.agent.ta.service

import android.content.Context
import android.util.Log
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.DailySlot
import com.agent.ta.domain.ChatInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 生活节点型主动消息触发器
 *
 * 与 [BoredInitiator] 互补的双轨触发机制之一：
 * - BoredInitiator：情境型，idle 超时后概率性发起（已有）
 * - LifeEventInitiator：生活节点型，状态切换时触发（本类）
 *
 * 触发时机：
 * - 起床：unavailable → 非 unavailable（起床节点）
 * - 睡觉：非 unavailable → unavailable（睡觉节点）
 * - 吃饭：activity 包含"早饭/午饭/晚饭/吃饭"等
 * - 洗澡：activity 包含"洗澡/泡澡"
 * - 工作开始/结束：切换到 busy / 从 busy 切出
 * - 其他切换：通用生活分享（如"刚到家""准备看个电影"）
 *
 * 去程式化策略：
 * - 概率触发：每次节点命中只有 60% 概率真正发起，避免每次切换都发消息
 * - 随机延迟：0-15 分钟随机延迟，避免状态切换瞬间就发（不像真人）
 * - 节点去重：同一节点类型 24 小时内只触发一次（避免每天起床都发同样消息）
 * - 静音时段：23:00-08:00 不主动发（避免凌晨打扰）
 *
 * 与 BoredInitiator 的协调：
 * - LifeEvent 触发后会写入 chat 表，BoredInitiator 的 30 分钟冷却会自动跳过
 * - 两者不互相直接依赖，通过 chat 表冷却实现去重
 */
class LifeEventInitiator(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已触发的节点记录：key=节点类型，value=触发时间戳（用于 24 小时去重） */
    private val triggeredNodes = ConcurrentHashMap<NodeType, Long>()

    companion object {
        private const val TAG = "LifeEventInitiator"

        /** 触发概率（0-1），避免每次状态切换都发消息 */
        private const val TRIGGER_PROBABILITY = 0.6f

        /** 随机延迟范围（毫秒）：0-15 分钟 */
        private const val MAX_DELAY_MS = 15 * 60 * 1000L

        /** 同一节点 24 小时内不重复触发 */
        private const val NODE_COOLDOWN_MS = 24 * 60 * 60 * 1000L

        /** 静音时段：23:00-08:00 不主动发 */
        private val SILENT_START = 23
        private val SILENT_END = 8
    }

    /**
     * 状态切换时调用
     *
     * @param newState 新状态
     * @param prevSlot 前一时段（可能为 null，如启动时）
     * @param newSlot 当前时段
     */
    fun onStateSwitched(newState: AgentState, prevSlot: DailySlot?, newSlot: DailySlot?) {
        if (newSlot == null) return

        // 识别节点类型
        val nodeType = identifyNodeType(newState, prevSlot, newSlot) ?: return

        // 24 小时去重
        val lastTriggered = triggeredNodes[nodeType]
        val now = System.currentTimeMillis()
        if (lastTriggered != null && now - lastTriggered < NODE_COOLDOWN_MS) {
            Log.d(TAG, "节点 $nodeType 24 小时内已触发过，跳过")
            return
        }

        // 概率判定
        if (Random.nextFloat() > TRIGGER_PROBABILITY) {
            Log.d(TAG, "节点 $nodeType 概率未通过，跳过")
            // 概率未通过不记录去重，允许午睡后起床等合理的二次发生
            return
        }

        // 随机延迟后执行
        val delayMs = Random.nextLong(0, MAX_DELAY_MS)
        Log.d(TAG, "节点 $nodeType 命中，${delayMs / 1000}秒后触发主动消息")

        scope.launch {
            delay(delayMs)

            // 再次检查静音时段（延迟后可能进入静音时段）
            // 与作息表统一使用 Asia/Shanghai 时区
            val hour = LocalTime.now(java.time.ZoneId.of("Asia/Shanghai")).hour
            if (hour >= SILENT_START || hour < SILENT_END) {
                Log.d(TAG, "延迟后进入静音时段，取消发起")
                return@launch
            }

            // 再次检查状态是否仍匹配（用户可能调整了作息）
            val currentState = AgentEngine.currentState.value
            if (currentState == AgentState.UNAVAILABLE && nodeType != NodeType.SLEEP) {
                Log.d(TAG, "延迟后状态变为 UNAVAILABLE，取消发起（非睡觉节点）")
                return@launch
            }

            // 执行主动发起
            try {
                // 跨天检测：确保作息是今天的
                AgentEngine.ensureTodayScheduleFresh(context)
                val interactor = ChatInteractor(context)
                interactor.agentInitiate()
                // 真正成功触发后才记录 24h 去重（概率未通过/发起失败均不记录）
                triggeredNodes[nodeType] = now
                Log.d(TAG, "节点 $nodeType 主动消息已发起")
            } catch (e: Exception) {
                Log.e(TAG, "节点 $nodeType 主动发起失败", e)
            }
        }
    }

    /**
     * 识别生活节点类型
     *
     * 基于状态切换方向 + activity 文本关键词判断
     *
     * @return 节点类型，null 表示不是关键节点（不触发主动消息）
     */
    private fun identifyNodeType(
        newState: AgentState,
        prevSlot: DailySlot?,
        newSlot: DailySlot
    ): NodeType? {
        val prevWasUnavailable = prevSlot?.state == "unavailable" || prevSlot?.state == "sleep"
        // Phase 1 分级睡眠：浅睡视为"已醒"，不触发 WAKE_UP
        val prevWasLightSleep = prevSlot?.state == "light_sleep" ||
            (prevSlot?.state == "unavailable" && prevSlot?.sleepDepth == "light")
        val newIsUnavailable = newState == AgentState.UNAVAILABLE

        // 深睡 → 浅睡（惊醒）：不触发任何节点
        if (prevSlot?.state == "unavailable" && prevSlot?.sleepDepth == "deep" &&
            newState == AgentState.LIGHT_SLEEP) {
            return null
        }

        // 起床：从 unavailable 切换到非 unavailable（排除浅睡，浅睡视为已醒）
        if (prevWasUnavailable && !prevWasLightSleep && !newIsUnavailable) {
            return NodeType.WAKE_UP
        }

        // 睡觉：从非 unavailable 切换到 unavailable（排除从浅睡切入，浅睡已算睡着）
        if (!prevWasUnavailable && !prevWasLightSleep && newIsUnavailable) {
            // 区分睡觉和洗澡
            val activity = newSlot.activity
            return if (containsAny(activity, "洗澡", "泡澡", "淋浴")) {
                NodeType.BATH
            } else {
                NodeType.SLEEP
            }
        }

        // 以下为非 unavailable 之间的切换，根据 activity 判断
        val activity = newSlot.activity

        // 吃饭节点
        if (containsAny(activity, "早饭", "早餐", "午饭", "午餐", "晚饭", "晚餐", "吃饭", "觅食", "做饭")) {
            return NodeType.MEAL
        }

        // 洗澡节点（unavailable 状态下已处理，这里处理 idle/busy 中的泡澡放松）
        if (containsAny(activity, "洗澡", "泡澡", "淋浴")) {
            return NodeType.BATH
        }

        // 工作开始：切换到 busy 且 activity 包含工作关键词
        if (newState == AgentState.BUSY && containsAny(
                activity, "工作", "写", "赶", "做", "学", "练", "画", "设计", "码", "开", "会议", "讨论"
            )
        ) {
            return NodeType.WORK_START
        }

        // 工作结束：从 busy 切换到 idle/normal
        if (prevSlot?.state == "busy" && (newState == AgentState.IDLE || newState == AgentState.NORMAL)) {
            return NodeType.WORK_END
        }

        // 其他切换：通用生活分享（如"刚到家""准备看个电影"）
        // 只在 idle/normal 之间切换时触发，避免过于频繁
        if (newState == AgentState.IDLE || newState == AgentState.NORMAL) {
            return NodeType.OTHER
        }

        return null
    }

    /**
     * 检查文本是否包含任一关键词
     */
    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }

    /**
     * 停止所有延迟中的协程（Service 销毁时调用，避免延迟协程仍执行 agentInitiate）
     */
    fun stop() {
        scope.cancel()
    }

    /**
     * 清理过期的节点触发记录（避免内存无限增长）
     * 可在 AgentEngine.stop 时调用
     */
    fun cleanupExpiredRecords() {
        val now = System.currentTimeMillis()
        val iterator = triggeredNodes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > NODE_COOLDOWN_MS * 2) {
                iterator.remove()
            }
        }
    }

    /**
     * 生活节点类型
     */
    enum class NodeType {
        WAKE_UP,    // 起床
        SLEEP,      // 睡觉
        MEAL,       // 吃饭
        BATH,       // 洗澡
        WORK_START, // 工作开始
        WORK_END,   // 工作结束
        OTHER       // 其他生活节点
    }
}
