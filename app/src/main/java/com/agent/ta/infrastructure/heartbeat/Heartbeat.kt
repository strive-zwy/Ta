package com.agent.ta.infrastructure.heartbeat

import android.util.Log
import com.agent.ta.infrastructure.observer.ObserverRegistry
import com.agent.ta.infrastructure.observer.ObserverSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 心跳调度器（L0 基础设施层）
 *
 * 设计参考：MochiBot 的 Heartbeat 机制
 *
 * 职责：
 * 1. 每分钟 tick 一次，驱动 Observer 收集状态
 * 2. 检测到状态变化时（hasDelta=true）触发 ThinkActDecider 评估
 * 3. 处理状态变化事件（如锚点过期派生新锚点）
 *
 * 与 MochiBot 的关键差异：
 * - MochiBot 心跳数据只走 Think 路径，主回复路径错失状态
 * - 本项目 ObserverRegistry 同时支持 collectAll()（主回复路径）和 collectChanged()（心跳路径）
 * - Heartbeat 仅负责定时触发，不持有业务逻辑
 *
 * 阶段4 实现：
 * - 心跳定时驱动 ObserverRegistry.collectChanged()
 * - 检测到变化时记录日志（验证 Observer 工作正常）
 * - ThinkActDecider 在阶段6 实现，届时 tick 中调用 thinkActDecider.think()
 *
 * 启动时机：AgentEngine.start() 中启动
 * 停止时机：App 销毁时（通常不需要主动停止，前台服务存活期间持续运行）
 */
class Heartbeat(
    private val registry: ObserverRegistry,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /** 心跳间隔（毫秒），默认 60 秒 */
    private val intervalMs: Long = 60_000L

    /** 心跳任务 */
    private var tickJob: Job? = null

    /** 心跳回调：状态变化时调用（供上层注册业务逻辑） */
    @Volatile
    private var onStateChanged: suspend (List<ObserverSnapshot>) -> Unit = { _ -> }

    /** 心跳计数（用于日志和调试） */
    @Volatile
    private var tickCount: Long = 0

    /**
     * 启动心跳
     *
     * @param onStateChanged 状态变化回调（可选，由 AgentEngine 注册业务逻辑）
     *                       阶段4 默认为空实现，阶段6 注入 ThinkActDecider.think
     */
    fun start(onStateChanged: suspend (List<ObserverSnapshot>) -> Unit = { _ -> }) {
        if (tickJob?.isActive == true) {
            Log.w(TAG, "心跳已在运行，忽略重复启动")
            return
        }
        this.onStateChanged = onStateChanged
        tickJob = scope.launch {
            Log.d(TAG, "心跳启动（间隔 ${intervalMs / 1000}s）")
            while (true) {
                try {
                    tick()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "心跳被取消")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "心跳 tick 异常: ${e.message}", e)
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * 停止心跳
     */
    fun stop() {
        tickJob?.cancel()
        tickJob = null
        Log.d(TAG, "心跳已停止（共执行 $tickCount 次 tick）")
    }

    /**
     * 单次 tick
     *
     * 流程：
     * 1. collectChanged() 获取有变化的观察者快照
     * 2. 无变化 → 跳过（省 LLM 调用）
     * 3. 有变化 → 调用 onStateChanged 回调（阶段6 由 ThinkActDecider 处理）
     */
    private suspend fun tick() {
        tickCount++
        val changed = registry.collectChanged()

        if (changed.isEmpty()) {
            // 大部分 tick 都走这里（无变化），日志降级为 verbose 避免刷屏
            if (tickCount % 10 == 0L) {
                Log.v(TAG, "tick #$tickCount：无状态变化")
            }
            return
        }

        Log.d(TAG, "tick #$tickCount：检测到 ${changed.size} 个状态变化")
        changed.forEach { snapshot ->
            Log.d(TAG, "  - ${snapshot.observerId}: ${snapshot.data}")
        }

        // 调用状态变化回调（阶段6 后由 ThinkActDecider 评估是否主动发起）
        try {
            onStateChanged(changed)
        } catch (e: Exception) {
            Log.e(TAG, "onStateChanged 回调异常: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "Heartbeat"
    }
}
