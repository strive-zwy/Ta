package com.agent.ta.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.agent.ta.di.ServiceLocator
import com.agent.ta.ui.screens.main.MainScreen
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import com.agent.ta.ui.screens.profile.CommitmentScreen
import com.agent.ta.ui.screens.profile.ModelConfigScreen
import com.agent.ta.ui.screens.profile.PermissionGuideScreen
import com.agent.ta.ui.screens.profile.ProfileScreen
import com.agent.ta.ui.screens.profile.TodayScheduleScreen
import com.agent.ta.ui.screens.agent.AgentConfigScreen
import com.agent.ta.ui.screens.agent.AgentBasicScreen
import com.agent.ta.ui.screens.agent.AgentPersonaScreen
import com.agent.ta.ui.screens.agent.AgentAvatarScreen
import com.agent.ta.ui.screens.agent.AgentVoiceScreen
import com.agent.ta.ui.screens.agent.AgentBehaviorScreen

/**
 * TaNavHost — M3 Expressive 风格升级
 *
 * 视觉升级：
 * 1. 页面切换加入滑入/滑出过渡（SlideDirection.Left/Right）
 * 2. 叠加 fade 让过渡更柔和
 * 3. 时长 300ms，符合 M3E 默认 spatial 动效节奏
 */
@Composable
fun TaNavHost(navController: NavHostController) {
    val startDestination = if (ServiceLocator.userPreferences.isConfigured()) {
        Routes.MAIN
    } else {
        Routes.MODEL_CONFIG
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        // 进入动画：从右侧滑入 + 淡入
        enterTransition = {
            slideInHorizontally(
                animationSpec = tween(300),
                initialOffsetX = { fullWidth -> fullWidth }
            ) + fadeIn(animationSpec = tween(300))
        },
        // 退出动画：向左滑出 + 淡出
        exitTransition = {
            slideOutHorizontally(
                animationSpec = tween(300),
                targetOffsetX = { fullWidth -> -fullWidth }
            ) + fadeOut(animationSpec = tween(300))
        },
        // pop 时的进入动画：从左侧滑入
        popEnterTransition = {
            slideInHorizontally(
                animationSpec = tween(300),
                initialOffsetX = { fullWidth -> -fullWidth }
            ) + fadeIn(animationSpec = tween(300))
        },
        // pop 时的退出动画：向右滑出
        popExitTransition = {
            slideOutHorizontally(
                animationSpec = tween(300),
                targetOffsetX = { fullWidth -> fullWidth }
            ) + fadeOut(animationSpec = tween(300))
        }
    ) {
        composable(Routes.MODEL_CONFIG) {
            ModelConfigScreen(
                onConfigured = { navController.navigate(Routes.PERMISSION_GUIDE) }
            )
        }
        composable(Routes.PERMISSION_GUIDE) {
            PermissionGuideScreen(
                onCompleted = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.MODEL_CONFIG) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.MAIN) {
            MainScreen(
                onOpenSettings = { navController.navigate(Routes.PROFILE) }
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onModelConfig = { navController.navigate(Routes.MODEL_CONFIG_EDIT) },
                onAgentConfig = { navController.navigate(Routes.AGENT_CONFIG) },
                onTodaySchedule = { navController.navigate(Routes.TODAY_SCHEDULE) },
                onCommitmentTasks = { navController.navigate(Routes.COMMITMENT_TASKS) }
            )
        }
        composable(Routes.MODEL_CONFIG_EDIT) {
            ModelConfigScreen(
                onConfigured = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.TODAY_SCHEDULE) {
            TodayScheduleScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.COMMITMENT_TASKS) {
            CommitmentScreen(
                onBack = { navController.popBackStack() }
            )
        }
        // ===== Agent 配置页（从设置页 → Agent 配置入口 → 5 个子页面 + 克隆）=====
        composable(Routes.AGENT_CONFIG) {
            AgentConfigScreen(
                onBack = { navController.popBackStack() },
                onBasic = { navController.navigate(Routes.AGENT_BASIC) },
                onPersona = { navController.navigate(Routes.AGENT_PERSONA) },
                onAvatar = { navController.navigate(Routes.AGENT_AVATAR) },
                onVoice = { navController.navigate(Routes.AGENT_VOICE) },
                onBehavior = { navController.navigate(Routes.AGENT_BEHAVIOR) }
            )
        }
        composable(Routes.AGENT_BASIC) {
            AgentBasicScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AGENT_PERSONA) {
            AgentPersonaScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AGENT_AVATAR) {
            AgentAvatarScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AGENT_VOICE) {
            AgentVoiceScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AGENT_BEHAVIOR) {
            AgentBehaviorScreen(onBack = { navController.popBackStack() })
        }
    }
}
