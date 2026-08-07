package com.agent.ta.ui.screens.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.data.local.entity.CommitmentEntity
import com.agent.ta.di.ServiceLocator
import com.agent.ta.service.CommitmentScheduler
import com.agent.ta.service.CommitmentSchedulePolicy
import com.agent.ta.ui.theme.VibePrimary
import com.agent.ta.ui.theme.VibePrimarySoft
import com.agent.ta.ui.theme.VibeStateError
import com.agent.ta.ui.theme.VibeStateSuccess
import com.agent.ta.ui.theme.VibeTagAmberBg
import com.agent.ta.ui.theme.VibeTagAmberFg
import com.agent.ta.ui.theme.VibeTagIndigoBg
import com.agent.ta.ui.theme.VibeTagIndigoFg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CommitmentScreen - 定时任务管理页
 *
 * 展示 Agent 的承诺/约定/提醒列表，按状态分组：
 * - 待触发（pending）：未来要触发的任务，可取消
 * - 已触发（triggered）：已到时间但未完成
 * - 已完成/已取消/已过期（completed/cancelled/expired）
 *
 * 任务来源：
 * - LLM 在对话中提取（"明天叫我起床" → reminder）
 * - Agent 主动承诺（"明天我帮你查 XXX" → promise）
 * - 双方约定（"下午3点一起看电影" → appointment）
 */
@Composable
fun CommitmentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<List<CommitmentEntity>>(emptyList()) }
    var triggered by remember { mutableStateOf<List<CommitmentEntity>>(emptyList()) }
    var finished by remember { mutableStateOf<List<CommitmentEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var cancelTarget by remember { mutableStateOf<CommitmentEntity?>(null) }
    var timerEnabled by remember {
        mutableStateOf(ServiceLocator.userPreferences.commitmentTimerEnabled)
    }
    var updatingTimer by remember { mutableStateOf(false) }

    // 加载任务列表
    fun reload() {
        scope.launch {
            loading = true
            val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
            val p = ServiceLocator.commitmentDao.getByStatus(agentId, "pending")
            val t = ServiceLocator.commitmentDao.getByStatus(agentId, "claimed") +
                ServiceLocator.commitmentDao.getByStatus(agentId, "delivered")
            val c = ServiceLocator.commitmentDao.getByStatus(agentId, "completed")
            val cn = ServiceLocator.commitmentDao.getByStatus(agentId, "cancelled")
            val ex = ServiceLocator.commitmentDao.getByStatus(agentId, "expired")
            pending = p.sortedBy { it.triggerAt ?: Long.MAX_VALUE }
            triggered = t.sortedBy { it.triggerAt ?: Long.MAX_VALUE }
            finished = (c + cn + ex).sortedByDescending { it.updatedAt }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                tonalElevation = 0.dp,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text = "定时任务",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "管理 Agent 的承诺与提醒",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { reload() }) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            ReminderSettingCard(
                enabled = timerEnabled,
                updating = updatingTimer,
                onEnabledChange = { enabled ->
                    val previous = timerEnabled
                    timerEnabled = enabled
                    scope.launch {
                        updatingTimer = true
                        try {
                            withContext(Dispatchers.IO) {
                                val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
                                val pendingCommitments =
                                    ServiceLocator.commitmentDao.getByStatus(agentId, "pending")
                                val scheduler = CommitmentScheduler(context)
                                if (enabled) {
                                    CommitmentSchedulePolicy.forReschedule(
                                        pendingCommitments,
                                        System.currentTimeMillis()
                                    ).forEach(scheduler::scheduleCommitmentTrigger)
                                } else {
                                    pendingCommitments.forEach {
                                        scheduler.cancelCommitmentTrigger(agentId, it.id)
                                    }
                                }
                                ServiceLocator.userPreferences.commitmentTimerEnabled = enabled
                            }
                        } catch (_: Exception) {
                            timerEnabled = previous
                            Toast.makeText(context, "提醒设置更新失败", Toast.LENGTH_SHORT).show()
                        } finally {
                            updatingTimer = false
                        }
                    }
                }
            )

            // 内容
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = VibePrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "加载中...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val total = pending.size + triggered.size + finished.size
                if (total == 0) {
                    EmptyState()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 40.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (pending.isNotEmpty()) {
                            item("section_pending") {
                                SectionHeader(
                                    icon = Icons.Default.Alarm,
                                    title = "待触发",
                                    subtitle = "${pending.size} 个任务",
                                    iconBg = VibeTagAmberBg,
                                    iconTint = VibeTagAmberFg
                                )
                            }
                            items(pending, key = { it.id }) { item ->
                                CommitmentCard(
                                    item = item,
                                    onCancel = { cancelTarget = item }
                                )
                            }
                        }

                        if (triggered.isNotEmpty()) {
                            item("section_triggered") {
                                SectionHeader(
                                    icon = Icons.Default.NotificationsActive,
                                    title = "已触发",
                                    subtitle = "${triggered.size} 个任务",
                                    iconBg = VibeTagIndigoBg,
                                    iconTint = VibeTagIndigoFg
                                )
                            }
                            items(triggered, key = { it.id }) { item ->
                                CommitmentCard(
                                    item = item,
                                    onCancel = { cancelTarget = item }
                                )
                            }
                        }

                        if (finished.isNotEmpty()) {
                            item("section_finished") {
                                SectionHeader(
                                    icon = Icons.Default.CheckCircle,
                                    title = "历史记录",
                                    subtitle = "${finished.size} 个",
                                    iconBg = VibePrimarySoft,
                                    iconTint = VibePrimary
                                )
                            }
                            items(finished, key = { it.id }) { item ->
                                CommitmentCard(item = item, onCancel = null)
                            }
                        }
                    }
                }
            }
        }
    }

    // 取消确认对话框
    cancelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("取消定时任务") },
            text = {
                Text("确定要取消「${target.content}」吗？\n取消后该任务将不再触发。")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
                            ServiceLocator.commitmentDao.updateStatus(agentId, target.id, "cancelled", now)
                            CommitmentScheduler(context).cancelCommitmentTrigger(agentId, target.id)
                        }
                        Toast.makeText(context, "已取消", Toast.LENGTH_SHORT).show()
                        cancelTarget = null
                        reload()
                    }
                }) { Text("取消任务", color = VibeStateError) }
            },
            dismissButton = {
                TextButton(onClick = { cancelTarget = null }) { Text("保留") }
            }
        )
    }
}

@Composable
private fun ReminderSettingCard(
    enabled: Boolean,
    updating: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !updating) {
                    onEnabledChange(!enabled)
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(VibeTagAmberBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Alarm,
                    contentDescription = null,
                    tint = VibeTagAmberFg,
                    modifier = Modifier.size(21.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "承诺定时提醒",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (enabled) {
                        "已开启，到点后 Agent 会主动发送提醒"
                    } else {
                        "已关闭，任务仍会保留，可在对话中自然提醒"
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                enabled = !updating
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(VibePrimarySoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = VibePrimary,
                    modifier = Modifier.size(36.dp)
                )
            }
            Text(
                text = "暂无定时任务",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "在对话中告诉 Agent 约定或提醒\n（如\"明天 8 点叫我起床\"）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconBg: Color,
    iconTint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CommitmentCard(
    item: CommitmentEntity,
    onCancel: (() -> Unit)?
) {
    val typeLabel = when (item.type) {
        "appointment" -> "约定"
        "promise" -> "承诺"
        "reminder" -> "提醒"
        else -> "任务"
    }
    val typeIcon = when (item.type) {
        "appointment" -> Icons.Default.CalendarMonth
        "promise" -> Icons.Default.CheckCircle
        "reminder" -> Icons.Default.NotificationsActive
        else -> Icons.Default.Alarm
    }
    val statusLabel = when (item.status) {
        "pending" -> "待触发"
        "triggered" -> "已触发"
        "completed" -> "已完成"
        "cancelled" -> "已取消"
        "expired" -> "已过期"
        else -> item.status
    }
    val statusColor = when (item.status) {
        "pending" -> VibeTagAmberFg
        "triggered" -> VibeTagIndigoFg
        "completed" -> VibeStateSuccess
        "cancelled", "expired" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 顶部：类型标签 + 状态标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(VibeTagAmberBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            tint = VibeTagAmberFg,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = typeLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = VibeTagAmberFg
                    )
                }
                Text(
                    text = statusLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }

            // 内容
            Text(
                text = item.content,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 21.sp
            )

            // 触发时间
            item.triggerAt?.let { ts ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = formatTriggerTime(ts),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 底部操作
            if (onCancel != null && (item.status == "pending" || item.status == "triggered")) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = VibeStateError,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "取消任务",
                            fontSize = 13.sp,
                            color = VibeStateError
                        )
                    }
                }
            }
        }
    }
}

private fun formatTriggerTime(ts: Long): String {
    val date = Date(ts)
    val now = Date()
    val isToday = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(date) ==
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(now)

    return if (isToday) {
        "今天 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(date)
    } else {
        SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(date)
    }
}
