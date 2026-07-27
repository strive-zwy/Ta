package com.agent.ta.infrastructure.observer

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 观察者注册中心（L0 基础设施层）
 *
 * 职责：
 * 1. 管理观察者注册/注销
 * 2. 维护每个观察者的上次快照（用于 hasDelta 比较）
 * 3. 提供 collectAll()（主回复路径，完整快照）和 collectChanged()（心跳路径，仅变化）
 *
 * 关键设计：
 * - collectAll() 用于被动回复路径，确保 LLM 始终看到完整当前状态
 *   即使无变化也返回，避免 MochiBot 的"主回复路径错失状态"问题
 * - collectChanged() 用于心跳路径，仅在变化时触发 Think 评估，节省 LLM 调用
 *
 * 线程安全：使用 Mutex 保护 observers 和 lastSnapshots
 */
class ObserverRegistry {

    private val mutex = Mutex()
    private val observers = mutableListOf<Observer>()
    private val lastSnapshots = mutableMapOf<String, ObserverSnapshot>()

    /**
     * 注册观察者
     * 重复 id 会被忽略
     */
    suspend fun register(observer: Observer) {
        mutex.withLock {
            if (observers.none { it.id == observer.id }) {
                observers.add(observer)
                Log.d(TAG, "观察者已注册：${observer.id}（当前共 ${observers.size} 个）")
            } else {
                Log.w(TAG, "观察者已存在，忽略重复注册：${observer.id}")
            }
        }
    }

    /**
     * 注销观察者
     */
    suspend fun unregister(observerId: String) {
        mutex.withLock {
            val removed = observers.removeAll { it.id == observerId }
            lastSnapshots.remove(observerId)
            if (removed) {
                Log.d(TAG, "观察者已注销：$observerId")
            }
        }
    }

    /**
     * 收集所有观察者的完整快照（不筛 delta）
     *
     * 用于主回复路径（ChatInteractor 调 LLM 前），确保 LLM 看到完整当前状态。
     * 同时更新 lastSnapshots，保证下次 hasDelta 比较基准最新。
     */
    suspend fun collectAll(): List<ObserverSnapshot> {
        val snapshotList = mutableListOf<ObserverSnapshot>()
        val toRemove = mutableListOf<String>()

        mutex.withLock {
            val iterator = observers.iterator()
            while (iterator.hasNext()) {
                val observer = iterator.next()
                try {
                    val snapshot = observer.collect()
                    snapshotList.add(snapshot)
                    lastSnapshots[observer.id] = snapshot
                } catch (e: Exception) {
                    Log.e(TAG, "观察者 ${observer.id} collect 失败: ${e.message}", e)
                    toRemove.add(observer.id)
                }
            }
        }

        // 清理异常观察者
        toRemove.forEach { id ->
            observers.removeAll { it.id == id }
            lastSnapshots.remove(id)
            Log.w(TAG, "已移除异常观察者：$id")
        }

        return snapshotList
    }

    /**
     * 仅收集有变化的观察者快照
     *
     * 用于心跳路径（Heartbeat tick），仅在 hasDelta=true 时返回，节省 LLM 调用。
     */
    suspend fun collectChanged(): List<ObserverSnapshot> {
        val changedList = mutableListOf<ObserverSnapshot>()

        mutex.withLock {
            for (observer in observers) {
                try {
                    val current = observer.collect()
                    val previous = lastSnapshots[observer.id]
                    if (observer.hasDelta(current, previous)) {
                        changedList.add(current)
                        lastSnapshots[observer.id] = current
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "观察者 ${observer.id} collectChanged 失败: ${e.message}", e)
                }
            }
        }

        if (changedList.isNotEmpty()) {
            Log.d(TAG, "检测到 ${changedList.size} 个观察者状态变化：${changedList.map { it.observerId }}")
        }

        return changedList
    }

    /**
     * 获取指定观察者的上次快照（不触发 collect）
     */
    suspend fun getLastSnapshot(observerId: String): ObserverSnapshot? {
        return mutex.withLock { lastSnapshots[observerId] }
    }

    /**
     * 获取已注册观察者数量
     */
    suspend fun size(): Int = mutex.withLock { observers.size }

    companion object {
        private const val TAG = "ObserverRegistry"
    }
}
