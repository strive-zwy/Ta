package com.agent.ta.ui.screens.profile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.WorkHistory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.ta.data.local.entity.DailyScheduleEntity
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.DailySlot
import com.agent.ta.di.ServiceLocator
import com.agent.ta.ui.theme.AmbientBackground
import com.agent.ta.ui.theme.GlassSurface
import com.agent.ta.ui.theme.TaMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * TodayScheduleScreen — Misty Lake Ambient Glassmorph BOLD 重设计
 *
 * 设计特征：
 * 1. AmbientBackground 流动 mesh 背景，颜色随当前 AgentState 联动
 * 2. 沉浸式顶部栏（displaySmall "今日作息"）
 * 3. Hero 当前时段卡：超大玻璃卡 + sweepGradient 旋转光晕 + radialGradient 呼吸光圈
 * 4. Stagger 入场：每个时段错峰滑入
 * 5. GlassSurface 时段卡：圆点节点 spring 呼吸 + 玻璃质感内容
 * 6. 当前时段光晕：220dp 外圈旋转 + 180dp 中圈呼吸
 * 7. 时间轴 morph：圆点节点 spring 微缩放 + 颜色过渡
 * 8. 加载态：GlassSurface 容器 + spring pulse
 */
@Composable
fun TodayScheduleScreen(onBack: () -> Unit) {
    var slots by remember { mutableStateOf<List<DailySlot>>(emptyList()) }
    var isAdjusted by remember { mutableStateOf(false) }
    var hasSchedule by remember { mutableStateOf<Boolean?>(null) }
    val agentConfig by ServiceLocator.agentConfigProvider.config.collectAsState()
    val agentName = agentConfig.agent.name.ifBlank { "小雅" }
    val agentState = com.agent.ta.service.AgentEngine.currentState.value
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
        withContext(Dispatchers.IO) {
            val dao = ServiceLocator.dailyScheduleDao
            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val entity: DailyScheduleEntity? = dao.getByDate(today)
            if (entity != null) {
                val json = Json { ignoreUnknownKeys = true }
                slots = try {
                    json.decodeFromString<List<DailySlot>>(entity.slotsJson)
                } catch (e: Exception) {
                    emptyList()
                }
                isAdjusted = entity.isAdjusted
                hasSchedule = true
            } else {
                hasSchedule = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 氛围背景：随当前 AgentState 联动
        AmbientBackground(state = agentState, intensity = 0.8f)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // ===== 沉浸式顶部栏 =====
            TopHeader(onBack = onBack, agentName = agentName)

            // ===== 主内容 =====
            when (hasSchedule) {
                null -> LoadingState()
                false -> EmptyState(agentName)
                true -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 32.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isAdjusted) {
                            item {
                                StaggerItem(index = 0, entered = entered) {
                                    AdjustedBadge()
                                }
                            }
                        }

                        // 当前时段 Hero 卡
                        val currentSlot = slots.find { slot ->
                            val now = LocalTime.now()
                            val slotStart = runCatching { LocalTime.parse(slot.start) }.getOrNull()
                            val slotEnd = runCatching {
                                if (slot.end == "24:00") LocalTime.MIDNIGHT
                                else LocalTime.parse(slot.end)
                            }.getOrNull()
                            slotStart != null && slotEnd != null &&
                                if (slotStart <= slotEnd) now >= slotStart && now < slotEnd
                                else now >= slotStart || now < slotEnd
                        }
                        if (currentSlot != null) {
                            item {
                                StaggerItem(index = 0, entered = entered) {
                                    HeroCurrentSlotCard(slot = currentSlot)
                                }
                            }
                        }

                        // 时段列表
                        itemsIndexed(slots) { index, slot ->
                            StaggerItem(index = index + 1, entered = entered) {
                                TimelineSlotItem(slot = slot, isCurrent = slot == currentSlot)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 沉浸式顶部栏：返回按钮 + displaySmall 标题 + 副标题
 */
@Composable
private fun TopHeader(onBack: () -> Unit, agentName: String) {
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
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = "今日作息",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$agentName 的今天 · ${LocalDate.now().format(DateTimeFormatter.ofPattern("MM月dd日"))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Stagger 容器：用 index 计算 delay，启动后逐个入场
 */
@Composable
private fun StaggerItem(
    index: Int,
    entered: Boolean,
    content: @Composable () -> Unit
) {
    val delayMs = TaMotion.staggerDelayMs(index)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(entered) {
        if (entered) {
            delay(delayMs.toLong())
            visible = true
        }
    }
    val enterAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 380f
        ),
        label = "staggerAlpha"
    )
    val enterTranslationY by animateFloatAsState(
        targetValue = if (visible) 0f else 60f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 380f
        ),
        label = "staggerY"
    )
    val enterScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 380f
        ),
        label = "staggerScale"
    )
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = enterAlpha
            translationY = enterTranslationY
            scaleX = enterScale
            scaleY = enterScale
        }
    ) {
        content()
    }
}

/**
 * "已根据互动调整" 徽章
 */
@Composable
private fun AdjustedBadge() {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        tonalElevation = TaMotion.DEFAULT_ELEVATION,
        alpha = 0.65f
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "今日作息已根据互动调整",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Hero 当前时段卡：超大玻璃卡 + 旋转光晕 + 呼吸光圈
 */
@Composable
private fun HeroCurrentSlotCard(slot: DailySlot) {
    val stateLabel = slot.state.toStateLabel()
    val stateIcon = slot.state.toStateIcon()
    val stateColor = slot.state.toStateColor()

    // 外圈 sweepGradient 旋转动画
    val transition = rememberInfiniteTransition(label = "heroSweep")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "heroRotation"
    )
    // 中圈 radialGradient 呼吸
    val breathAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(TaMotion.STATUS_BREATH_DURATION_MS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heroBreath"
    )
    // Hero 卡片 spring pulse（微小缩放呼吸）
    val cardPulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(TaMotion.STATUS_BREATH_DURATION_MS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cardPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = cardPulse; scaleY = cardPulse },
        contentAlignment = Alignment.Center
    ) {
        // 背景光晕（外层）
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = breathAlpha * 0.5f }
                .clip(RoundedCornerShape(32.dp))
                .background(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            stateColor.copy(alpha = 0f),
                            stateColor.copy(alpha = 0.4f),
                            stateColor.copy(alpha = 0f),
                            stateColor.copy(alpha = 0.3f),
                            stateColor.copy(alpha = 0f)
                        )
                    )
                )
        )
        // 旋转光晕装饰
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    rotationZ = rotation
                    alpha = breathAlpha
                }
                .clip(RoundedCornerShape(32.dp))
                .background(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            stateColor.copy(alpha = 0.6f),
                            Color.Transparent
                        )
                    )
                )
        )

        // 主玻璃卡
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = TaMotion.HERO_ELEVATION,
            alpha = 0.85f,
            borderAlpha = 0.25f
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // 渐变背景层
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    stateColor.copy(alpha = 0.18f),
                                    Color.Transparent,
                                    stateColor.copy(alpha = 0.08f)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 状态图标圆环
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(stateColor.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = stateIcon,
                                    contentDescription = null,
                                    tint = stateColor,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "正在进行",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stateColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stateLabel,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        // "现在" 时间标签
                        val nowStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                        GlassSurface(
                            shape = RoundedCornerShape(50),
                            tonalElevation = 0.dp,
                            alpha = 0.5f
                        ) {
                            Text(
                                text = nowStr,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = stateColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (slot.activity.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = slot.activity,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${slot.start} → ${slot.end}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        // 呼吸进行中指示
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val breathDot by transition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "breathDot"
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .graphicsLayer { alpha = breathDot }
                                    .clip(CircleShape)
                                    .background(stateColor)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "LIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = stateColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 时间轴单个时段：左侧圆点节点 + 右侧玻璃质感内容
 */
@Composable
private fun TimelineSlotItem(slot: DailySlot, isCurrent: Boolean) {
    val stateLabel = slot.state.toStateLabel()
    val stateIcon = slot.state.toStateIcon()
    val stateColor = slot.state.toStateColor()

    Row(modifier = Modifier.fillMaxWidth()) {
        // 左侧：时间线 + 圆点节点（spring 呼吸）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 圆点节点
            val nodeScale by animateFloatAsState(
                targetValue = if (isCurrent) 1.15f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = 380f
                ),
                label = "nodeScale"
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer { scaleX = nodeScale; scaleY = nodeScale }
                    .clip(CircleShape)
                    .background(
                        if (isCurrent) stateColor
                        else stateColor.copy(alpha = 0.18f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = stateIcon,
                    contentDescription = null,
                    tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                           else stateColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            // 垂直连接线
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(56.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                stateColor.copy(alpha = 0.4f),
                                stateColor.copy(alpha = 0.05f)
                            )
                        )
                    )
            )
        }

        Spacer(Modifier.width(14.dp))

        // 右侧：玻璃质感内容卡
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = if (isCurrent) TaMotion.HERO_ELEVATION else TaMotion.DEFAULT_ELEVATION,
            alpha = if (isCurrent) 0.92f else 0.75f,
            borderAlpha = if (isCurrent) 0.25f else 0.12f
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // 状态色渐变背景
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    stateColor.copy(alpha = if (isCurrent) 0.22f else 0.08f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${slot.start} - ${slot.end}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = stateColor.copy(alpha = if (isCurrent) 0.3f else 0.15f)
                        ) {
                            Text(
                                text = stateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = stateColor,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                    if (slot.activity.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = slot.activity,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isCurrent) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = stateColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "正在进行",
                                style = MaterialTheme.typography.labelSmall,
                                color = stateColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 加载态：玻璃质感容器 + spring pulse 加载指示
 */
@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 双层呼吸圈
            val transition = rememberInfiniteTransition(label = "loadingPulse")
            val outerScale by transition.animateFloat(
                initialValue = 0.7f,
                targetValue = 1.4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(TaMotion.GLOW_PULSE_DURATION_MS),
                    repeatMode = RepeatMode.Restart
                ),
                label = "outerScale"
            )
            val outerAlpha by transition.animateFloat(
                initialValue = 0.6f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(TaMotion.GLOW_PULSE_DURATION_MS),
                    repeatMode = RepeatMode.Restart
                ),
                label = "outerAlpha"
            )
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            scaleX = outerScale; scaleY = outerScale; alpha = outerAlpha
                        }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "正在加载今日作息…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 空态：玻璃质感容器 + 图标 + 文案
 */
@Composable
private fun EmptyState(agentName: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = TaMotion.DEFAULT_ELEVATION,
            alpha = 0.75f
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 图标带呼吸光圈
                val transition = rememberInfiniteTransition(label = "emptyBreath")
                val iconScale by transition.animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(TaMotion.STATUS_BREATH_DURATION_MS),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "iconScale"
                )
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "$agentName 今天还没有规划作息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "启动 App 后会自动生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ===== 状态映射扩展 =====

private fun String.toStateLabel(): String = when (this) {
    "sleep" -> "睡觉"
    "work" -> "工作"
    "game" -> "游戏"
    "bath" -> "洗澡"
    "bored" -> "空闲"
    "happy" -> "开心"
    else -> this
}

private fun String.toStateIcon(): ImageVector = when (this) {
    "sleep" -> Icons.Default.Bedtime
    "work" -> Icons.Default.Work
    "game" -> Icons.Default.SentimentSatisfied
    "bath" -> Icons.Default.Shower
    "bored" -> Icons.Default.HourglassEmpty
    "happy" -> Icons.Default.GraphicEq
    else -> Icons.Default.WorkHistory
}

@Composable
private fun String.toStateColor(): Color = when (this) {
    "sleep" -> Color(0xFF6B7AB5)        // 静谧夜蓝
    "work" -> MaterialTheme.colorScheme.primary
    "game" -> MaterialTheme.colorScheme.tertiary
    "bath" -> MaterialTheme.colorScheme.secondary
    "bored" -> MaterialTheme.colorScheme.outline
    "happy" -> Color(0xFFE8A555)       // 暖橙
    else -> MaterialTheme.colorScheme.outline
}
