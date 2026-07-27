package com.agent.ta.infrastructure.observer

/**
 * 观察者接口（L0 基础设施层）
 *
 * 设计参考：MochiBot 的 Observer Pattern
 *
 * 与 MochiBot 的关键差异：
 * - MochiBot 的 Observer 数据只走 Heartbeat 路径，导致主回复路径错失状态
 * - 本项目 ObserverRegistry 同时支持 collectAll()（主回复路径）和 collectChanged()（心跳路径）
 * - 确保被动回复时 LLM 也能看到完整当前状态
 *
 * 使用方式：
 * 1. 实现 Observer 接口，在 collect() 中返回当前状态快照
 * 2. 在 hasDelta() 中判断与上次快照是否有变化
 * 3. 注册到 ObserverRegistry，由 Heartbeat 定期调用
 */
interface Observer {
    /** 观察者唯一标识 */
    val id: String

    /**
     * 收集当前状态快照
     * 每次 Heartbeat tick 调用，返回当前观察到的数据
     */
    suspend fun collect(): ObserverSnapshot

    /**
     * 增量检测：与上次快照对比是否有变化
     * - true: 有变化，需要通知订阅者
     * - false: 无变化，跳过后续处理（省 LLM 调用）
     */
    fun hasDelta(current: ObserverSnapshot, previous: ObserverSnapshot?): Boolean
}

/**
 * 观察者快照
 *
 * @param observerId 观察者ID
 * @param timestamp 快照时间戳（epochMilli）
 * @param data 结构化数据（供程序读取）
 * @param promptHint 可直接注入 prompt 的文本（供 LLM 阅读）
 */
data class ObserverSnapshot(
    val observerId: String,
    val timestamp: Long,
    val data: Map<String, Any> = emptyMap(),
    val promptHint: String = ""
)
