package com.agent.ta.ui.screens.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.agent.ta.ui.screens.chat.ChatScreen

/**
 * MainScreen — App 入口容器
 *
 * 新结构：进入 App 直接是 ChatScreen，无底部 tab。
 * 设置入口移到聊天页右上角图标，点击跳转到 ProfileScreen。
 *
 * @param onOpenSettings 跳转到设置页回调
 */
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit
) {
    ChatScreen(
        modifier = Modifier.fillMaxSize(),
        onOpenSettings = onOpenSettings
    )
}
