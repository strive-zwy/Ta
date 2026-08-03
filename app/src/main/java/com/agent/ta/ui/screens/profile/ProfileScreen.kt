package com.agent.ta.ui.screens.profile

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.resolveCurrentAvatarFile
import com.agent.ta.di.ServiceLocator
import com.agent.ta.service.AgentEngine
import com.agent.ta.ui.theme.VibePrimary
import com.agent.ta.ui.theme.VibePrimaryDeep
import com.agent.ta.ui.theme.VibePrimaryGlow
import com.agent.ta.ui.theme.VibePrimarySoft
import com.agent.ta.ui.theme.VibePrimaryTint
import com.agent.ta.ui.theme.VibeStateError
import com.agent.ta.ui.theme.VibeStateSuccess
import com.agent.ta.ui.theme.VibeTagAmberBg
import com.agent.ta.ui.theme.VibeTagAmberFg
import com.agent.ta.ui.theme.VibeTagGreenBg
import com.agent.ta.ui.theme.VibeTagGreenFg
import com.agent.ta.ui.theme.VibeTagIndigoBg
import com.agent.ta.ui.theme.VibeTagIndigoFg
import com.agent.ta.ui.theme.VibeTagRedBg
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.shadow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * ProfileScreen — Vibe Chat 风格设置页（参考设计稿 chat-settings.html）
 *
 * 结构：
 * 1. sticky 顶部返回栏（半透明白色 + blur）：← + "设置" 标题
 * 2. 头像资料卡（conic 渐变环 + 头像 + 名称年龄 + 状态 chip）
 * 3. 分组菜单（账户 / Agent / 设置 / 危险操作）
 *    - 每行：圆角 icon bg + 标题 + 副标题 + chevron
 *    - 危险操作：error 色 icon bg + 半透明红边框
 */
@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onModelConfig: () -> Unit,
    onAgentConfig: () -> Unit = {},
    onTodaySchedule: () -> Unit = {}
) {
    val prefs = ServiceLocator.userPreferences
    val context = LocalContext.current
    val agentConfig by ServiceLocator.agentConfigProvider.config.collectAsState()
    val agentState by AgentEngine.currentState.collectAsState()

    var nickname by remember { mutableStateOf(prefs.userNickname) }
    var userAvatarPath by remember { mutableStateOf(prefs.userAvatarPath) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var showResetChatDialog by remember { mutableStateOf(false) }
    var showResetMemoryDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // 用户头像选择器：选图后复制到内部存储 filesDir/user_avatar/avatar_<ts>.jpg
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = copyUserAvatarToInternal(context, uri)
            if (path != null) {
                prefs.userAvatarPath = path
                userAvatarPath = path
                Toast.makeText(context, "已设置用户头像", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "头像保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFFF6F9F9))) {
        // ===== 1. 顶部返回栏（固定，不随页面滑动）=====
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            tonalElevation = 0.dp,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "设置",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.size(36.dp))
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }

        // ===== 2. 消息列表 =====
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            // ===== 2. 头像资料卡 =====
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 装饰渐变环 + 外发光阴影 + 头像
                    // 设计稿：conic 渐变环 + box-shadow: 0 0 0 8px rgba(217,235,233,0.45), 0 10px 30px -12px var(--vibe-primary-glow)
                    Box(
                        modifier = Modifier
                            .size(128.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        VibePrimarySoft,
                                        Color(0xFFF5EFE3),
                                        VibePrimaryTint
                                    )
                                )
                            )
                            .border(8.dp, VibePrimarySoft.copy(alpha = 0.45f), CircleShape)
                            .padding(0.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AgentAvatar(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .border(4.dp, Color.White, CircleShape)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // 名称 + 年龄
                    val name = agentConfig.agent.name.ifBlank { "小雅" }
                    val age = agentConfig.agent.age
                    Text(
                        text = if (age > 0) "$name · ${age}岁" else name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // 状态 chip — 对齐设计稿：tag-green 底 + state-success 绿点
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(VibeTagGreenBg)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(VibeStateSuccess)
                        )
                        Text(
                            text = "状态：${agentState.displayName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = VibeTagGreenFg
                        )
                    }
                }
            }

            // ===== 5. 分组菜单 =====
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 分组 A：账户（头像 + 称呼合并一行）
                    MenuGroup(title = "账户") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showNicknameDialog = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 左侧：用户头像缩略图（36dp 圆角，有自定义图显示图片，否则 Person icon 兜底）
                            val avatarBmp = remember(userAvatarPath) {
                                if (userAvatarPath.isNotBlank()) {
                                    runCatching { BitmapFactory.decodeFile(userAvatarPath) }.getOrNull()
                                } else null
                            }
                            if (avatarBmp != null) {
                                Image(
                                    bitmap = avatarBmp.asImageBitmap(),
                                    contentDescription = "用户头像",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(VibePrimarySoft),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "用户头像",
                                        tint = VibePrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            // 中间：标题 + 副标题
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "账户信息",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "称呼：$nickname",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            // 右侧：chevron
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 分组 B：Agent
                    MenuGroup(title = "Agent") {
                        Column {
                            MenuRow(
                                icon = Icons.Default.Schedule,
                                title = "今日作息",
                                subtitle = "查看${agentConfig.agent.name.ifBlank { "小雅" }}今天的动态安排",
                                iconBgColor = VibeTagAmberBg,
                                iconTint = VibeTagAmberFg,
                                onClick = onTodaySchedule
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            MenuRow(
                                icon = Icons.Default.SmartToy,
                                title = "Agent 配置",
                                subtitle = "当前：${agentConfig.agent.name.ifBlank { "小雅" }} · 点击修改 Agent 配置",
                                iconBgColor = VibeTagIndigoBg,
                                iconTint = VibeTagIndigoFg,
                                onClick = onAgentConfig
                            )
                        }
                    }

                    // 分组 C：设置
                    MenuGroup(title = "设置") {
                        MenuRow(
                            icon = Icons.Default.Settings,
                            title = "模型配置",
                            subtitle = "${prefs.llmModel.ifBlank { "未选择" }} · LLM ${if (prefs.llmApiKey.isNotBlank()) "已配置" else "未配置"} · TTS ${if (prefs.ttsApiKey.isNotBlank()) "已配置" else "未配置"}",
                            onClick = onModelConfig
                        )
                    }

                    // 分组 D：危险操作 — 极浅红底（red-50）+ 深红标题 + 灰色详情 + 红色图标
                    MenuGroup(title = "危险操作", titleColor = VibeStateError, danger = true) {
                        Column {
                            MenuRow(
                                icon = Icons.Default.Refresh,
                                title = "重置聊天记录",
                                subtitle = "清空所有消息和记忆",
                                iconTint = Color(0xFFDC2626),
                                iconBgColor = Color(0xFFFECACA),
                                danger = true,
                                titleColor = Color(0xFFB91C1C),
                                onClick = { showResetChatDialog = true }
                            )
                            HorizontalDivider(
                                color = Color(0xFFFCA5A5),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            MenuRow(
                                icon = Icons.Default.Psychology,
                                title = "重置记忆",
                                subtitle = "仅清空记忆，保留聊天记录",
                                iconTint = Color(0xFFDC2626),
                                iconBgColor = Color(0xFFFECACA),
                                danger = true,
                                titleColor = Color(0xFFB91C1C),
                                onClick = { showResetMemoryDialog = true }
                            )
                        }
                    }
                }
            }
        }
    }

    // ===== 弹窗 =====
    if (showNicknameDialog) {
        var editing by remember { mutableStateOf(nickname) }
        AlertDialog(
            onDismissRequest = { showNicknameDialog = false },
            title = { Text("账户信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 头像选择
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val dlgBmp = remember(userAvatarPath) {
                            if (userAvatarPath.isNotBlank()) {
                                runCatching { BitmapFactory.decodeFile(userAvatarPath) }.getOrNull()
                            } else null
                        }
                        if (dlgBmp != null) {
                            Image(
                                bitmap = dlgBmp.asImageBitmap(),
                                contentDescription = "用户头像",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                    .clickable { avatarPicker.launch("image/*") }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(VibePrimarySoft)
                                    .clickable { avatarPicker.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = VibePrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "用户头像",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (userAvatarPath.isBlank()) "点击上传自定义头像" else "点击更换头像",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // 称呼输入
                    OutlinedTextField(
                        value = editing,
                        onValueChange = { editing = it },
                        label = { Text("Agent 对你的称呼") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = editing.ifBlank { "你" }
                    prefs.userNickname = value
                    nickname = value
                    showNicknameDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showNicknameDialog = false }) { Text("取消") }
            }
        )
    }

    if (showResetChatDialog) {
        AlertDialog(
            onDismissRequest = { showResetChatDialog = false },
            title = { Text("重置聊天记录") },
            text = { Text("将清空所有聊天消息和记忆，且无法恢复，确定继续吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                ServiceLocator.chatMessageDao.deleteAll()
                                ServiceLocator.memoryDao.deleteAll()
                                Toast.makeText(context, "已清空聊天记录和记忆", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "清空失败：${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            showResetChatDialog = false
                        }
                    }
                ) { Text("确定清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetChatDialog = false }) { Text("取消") }
            }
        )
    }

    if (showResetMemoryDialog) {
        val resetName = agentConfig.agent.name.ifBlank { "小雅" }
        AlertDialog(
            onDismissRequest = { showResetMemoryDialog = false },
            title = { Text("重置记忆") },
            text = { Text("将清空$resetName 的记忆，但保留聊天记录，确定继续吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                ServiceLocator.memoryDao.deleteAll()
                                Toast.makeText(context, "已清空记忆", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "清空失败：${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            showResetMemoryDialog = false
                        }
                    }
                ) { Text("确定清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetMemoryDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 分组标题（小字 + uppercase + letter spacing，对齐设计稿 text-[11px] uppercase tracking-wider）
 *
 * danger=true 时卡片用极浅红底（red-50）+ 深红标题 + 黑色详情，无边框
 */
@Composable
private fun MenuGroup(
    title: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    danger: Boolean = false,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        val shape = RoundedCornerShape(16.dp)
        // danger 卡片：极浅红底（red-50）+ 红色光晕阴影，无边框
        // 普通卡片：白色底 + 轻量阴影，悬浮感
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (danger) Modifier
                        .shadow(
                            elevation = 6.dp,
                            shape = shape,
                            ambientColor = VibeStateError.copy(alpha = 0.28f),
                            spotColor = VibeStateError.copy(alpha = 0.28f)
                        )
                        .clip(shape)
                        .background(Color(0xFFFEF2F2))
                    else Modifier
                        .shadow(
                            elevation = 6.dp,
                            shape = shape,
                            ambientColor = VibePrimaryGlow,
                            spotColor = VibePrimaryGlow
                        )
                        .clip(shape)
                        .background(Color.White)
                )
        ) {
            content()
        }
    }
}

/**
 * 菜单行（icon + 标题/副标题 + 可选右侧值 + chevron）
 *
 * 设计稿：
 * - 默认：icon + 标题 + 副标题 + chevron
 * - 用户称呼行：icon + 标题（无副标题） + 右侧值（如"宝宝"） + chevron
 */
@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconTint: Color = VibePrimary,
    iconBgColor: Color = VibePrimarySoft,
    trailingText: String? = null,
    trailingAvatarPath: String? = null,
    danger: Boolean = false,
    // 危险操作卡片文字颜色覆盖（深红字，浅红底用）
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dividerColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 圆角 icon（设计稿 w-9 h-9 rounded-xl = 36dp 12dp圆角）
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = subtitleColor,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        // 右侧头像缩略（用户头像行使用）
        if (trailingAvatarPath != null) {
            val bmp = remember(trailingAvatarPath) {
                runCatching { BitmapFactory.decodeFile(trailingAvatarPath) }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(VibePrimarySoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = VibePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        // 右侧值（设计稿：text-[13px] muted-foreground，如"用户称呼"行的"宝宝"）
        if (trailingText != null) {
            Text(
                text = trailingText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = if (danger) Color(0xFFDC2626)
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Agent 头像（圆形）
 *
 * 统一用当前头像（用户在头像管理页选中的那个），与聊天页保持一致。
 */
@Composable
private fun AgentAvatar(modifier: Modifier = Modifier) {
    val config by ServiceLocator.agentConfigProvider.config.collectAsState()
    val avatarPath = remember(config.agent.avatars, config.agent.currentAvatarId) {
        config.agent.resolveCurrentAvatarFile()
    }
    val bitmap = remember(avatarPath) {
        avatarPath?.let { path ->
            runCatching {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(VibePrimaryTint, VibePrimaryDeep)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Agent 头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = config.agent.name.firstOrNull()?.toString() ?: "雅",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 把 SAF Uri 指向的图片复制到内部存储，返回绝对路径。
 * 存放位置：filesDir/user_avatar/avatar_<timestamp>.jpg
 */
private fun copyUserAvatarToInternal(context: Context, uri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "user_avatar").apply { mkdirs() }
        val fileName = "avatar_${System.currentTimeMillis()}.jpg"
        val destFile = File(dir, fileName)
        // 先写入新文件，成功后再清理旧文件，避免复制失败时丢失原头像
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: return null
        // 写入成功后清理旧文件（排除刚创建的新文件）
        dir.listFiles()?.filter { it.name != fileName }?.forEach { it.delete() }
        destFile.absolutePath
    } catch (e: Exception) {
        null
    }
}
