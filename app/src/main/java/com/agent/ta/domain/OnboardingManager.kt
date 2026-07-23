package com.agent.ta.domain

import android.content.Context
import com.agent.ta.data.local.entity.OnboardingStateEntity
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Onboarding 对话流程管理器
 *
 * Agent 主动发起 3-5 轮对话了解用户：
 * 1. 用户配置完成后，Agent 主动打招呼
 * 2. 每轮 Agent 提一个问题，用户回答后提取记忆
 * 3. 完成 N 轮后切正常状态机
 */
class OnboardingManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val onboardingDao = ServiceLocator.onboardingStateDao
    private val interactor = ChatInteractor(context)

    /**
     * 启动 Onboarding（配置完成后调用）
     */
    fun start() {
        scope.launch {
            val state = onboardingDao.get()
            if (state != null && state.phase == "completed") return@launch

            // 标记开始
            onboardingDao.upsert(
                OnboardingStateEntity(
                    phase = "in_progress",
                    currentStep = 0,
                    totalSteps = 4,
                    startedAt = System.currentTimeMillis()
                )
            )

            // Agent 主动发起第一条消息（打招呼 + 问名字）
            interactor.triggerOnboardingMessage()
        }
    }

    /**
     * 用户回复后调用，推进 Onboarding 进度
     */
    fun onUserReplied() {
        scope.launch {
            val state = onboardingDao.get() ?: return@launch
            if (state.phase != "in_progress") return@launch

            val nextStep = state.currentStep + 1
            if (nextStep >= state.totalSteps) {
                // Onboarding 完成
                onboardingDao.complete(System.currentTimeMillis())
            } else {
                onboardingDao.updateProgress("in_progress", nextStep)
                // 触发下一轮 Agent 提问
                interactor.triggerOnboardingMessage()
            }
        }
    }

    /**
     * 检查是否在 Onboarding 阶段
     */
    suspend fun isOnboarding(): Boolean {
        val state = onboardingDao.get() ?: return false
        return state.phase == "in_progress"
    }

    /**
     * 获取当前进度
     */
    suspend fun getProgress(): OnboardingStateEntity? {
        return onboardingDao.get()
    }
}
