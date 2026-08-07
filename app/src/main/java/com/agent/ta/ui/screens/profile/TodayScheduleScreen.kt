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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.ta.data.local.entity.DailyScheduleEntity
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.DailySlot
import com.agent.ta.di.ServiceLocator
import com.agent.ta.ui.theme.TaMotion
import com.agent.ta.ui.screens.agent.AiBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
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
    var regenerating by remember { mutableStateOf(false) }
    val agentConfig by ServiceLocator.agentConfigProvider.config.collectAsState()
    val agentName = agentConfig.agent.name.ifBlank { "小雅" }
    var entered by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current

    // 周期性 tick：每 60 秒触发重组，刷新 currentSlot / isPast 判断
    // 让 UI 在跨时段边界时自动更新（如 12:00 进入新时段）
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            tick = (tick + 1) % Int.MAX_VALUE
        }
    }

    // 重新读取当天作息（重新生成后调用）
    suspend fun reloadToday() = withContext(Dispatchers.IO) {
        val dao = ServiceLocator.dailyScheduleDao
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        val entity: DailyScheduleEntity? = dao.getByDate(agentId, today)
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

    LaunchedEffect(Unit) {
        entered = true
        reloadToday()
    }

    // 重新生成当天作息
    fun onRegenerate() {
        if (regenerating) return
        scope.launch {
            regenerating = true
            try {
                withContext(Dispatchers.IO) {
                    // 复用 AgentEngine 的重载流程：重新生成作息 + 更新状态机 + 重新调度
                    com.agent.ta.service.AgentEngine.reloadAfterConfigChanged(context)
                }
                reloadToday()
                Toast.makeText(context, "已重新生成今日作息", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "重新生成失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                regenerating = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // ===== 沉浸式顶部栏 =====
            TopHeader(
                onBack = onBack,
                agentName = agentName,
                onRegenerate = ::onRegenerate,
                regenerating = regenerating
            )

            // ===== 主内容 =====
            when (hasSchedule) {
                null -> LoadingState()
                false -> EmptyState(agentName)
                true -> {
                    // 当前时段判断：用日期+时间判断，正确处理跨午夜时段
                    // 今日作息表中的时段都是今天的，跨午夜时段（如睡觉 22:00-07:30）
                    // 实际跨越今天晚上到明天早上
                    // 引用 tick 让重组随周期 tick 触发，自动刷新跨时段边界
                    val todayDate = LocalDate.now()
                    val nowDateTime = LocalDateTime.now()
                    @Suppress("UNUSED_VARIABLE") val tickRef = tick  // 依赖 tick 触发重组
                    val nowTime = nowDateTime.toLocalTime()
                    val currentSlot = slots.find { slot ->
                        val slotStart = runCatching { LocalTime.parse(slot.start) }.getOrNull()
                        val slotEnd = runCatching {
                            if (slot.end == "24:00") LocalTime.of(23, 59, 59)
                            else LocalTime.parse(slot.end)
                        }.getOrNull()
                        slotStart != null && slotEnd != null && run {
                            if (slotStart <= slotEnd) {
                                // 普通时段：today [start, end]
                                // Phase 1 分级睡眠：凌晨睡眠子段（如将醒浅睡 05:00-07:30）
                                // start.hour < 12 → 属于次日清晨，不能当作"今日早晨时段"
                                val useNextDay = slot.sleepDepth != null && slotStart.hour < 12
                                val date = if (useNextDay) todayDate.plusDays(1) else todayDate
                                val s = LocalDateTime.of(date, slotStart)
                                val e = LocalDateTime.of(date, slotEnd)
                                !nowDateTime.isBefore(s) && !nowDateTime.isAfter(e)
                            } else {
                                // 跨午夜时段（如睡觉 22:00-07:30）：今晚 start 到明早 end
                                // 凌晨时(now < today start)不算 current（那是昨晚的睡眠延续）
                                val s = LocalDateTime.of(todayDate, slotStart)
                                val e = LocalDateTime.of(todayDate.plusDays(1), slotEnd)
                                !nowDateTime.isBefore(s) && !nowDateTime.isAfter(e)
                            }
                        }
                    }
                    val upcomingSlot = if (currentSlot == null) {
                        slots.firstOrNull { slot ->
                            val slotStart = runCatching { LocalTime.parse(slot.start) }.getOrNull()
                            slotStart != null && slotStart > nowTime
                        } ?: slots.firstOrNull()
                    } else null

                    // 自动滚动到当前时段
                    val listState = rememberLazyListState()
                    val heroIndex = slots.indexOfFirst { it == (currentSlot ?: upcomingSlot) }
                    LaunchedEffect(slots, entered) {
                        if (entered && heroIndex >= 0) {
                            // 让当前时段显示在视口顶部
                            listState.scrollToItem(heroIndex)
                        }
                    }

                    LazyColumn(
                        state = listState,
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

                        // 时段列表（当前时段卡片更凸显，已自动定位到视口顶部）
                        itemsIndexed(slots) { index, slot ->
                            val slotStart = runCatching { LocalTime.parse(slot.start) }.getOrNull()
                            val slotEnd = runCatching {
                                if (slot.end == "24:00") LocalTime.of(23, 59, 59)
                                else LocalTime.parse(slot.end)
                            }.getOrNull()
                            // 判断是否已结束（用日期+时间）
                            // - 普通时段：now > today end 即已结束
                            // - 跨午夜时段（如 22:00-07:30 睡觉）：今晚 start 到明早 end
                            //   isPast = now > 明早 end
                            //   在今日作息视图内 now 永远 <= today 23:59 < 明早 end → 永远 false
                            // - Phase 1 分级睡眠：凌晨子段（如将醒浅睡 05:00-07:30）
                            //   start.hour < 12 → 属于次日清晨，isPast = now > 明早 end
                            val isPast = slot != currentSlot && slot != upcomingSlot &&
                                slotStart != null && slotEnd != null && run {
                                if (slotStart <= slotEnd) {
                                    // 普通时段 或 凌晨睡眠子段
                                    val useNextDay = slot.sleepDepth != null && slotStart.hour < 12
                                    val endDate = if (useNextDay) todayDate.plusDays(1) else todayDate
                                    val endDt = LocalDateTime.of(endDate, slotEnd)
                                    nowDateTime.isAfter(endDt)
                                } else {
                                    // 跨午夜时段：明早 end 之后才算已结束
                                    val endTomorrow = LocalDateTime.of(todayDate.plusDays(1), slotEnd)
                                    nowDateTime.isAfter(endTomorrow)
                                }
                            }
                            StaggerItem(index = index + 1, entered = entered) {
                                TimelineSlotItem(
                                    slot = slot,
                                    isCurrent = slot == currentSlot || slot == upcomingSlot,
                                    isPast = isPast,
                                    isUpcoming = slot == upcomingSlot
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 沉浸式顶部栏：返回按钮 + displaySmall 标题 + 副标题 + 重新生成按钮
 */
@Composable
private fun TopHeader(
    onBack: () -> Unit,
    agentName: String,
    onRegenerate: () -> Unit,
    regenerating: Boolean
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
        // 重新生成按钮：旋转动画反馈
        val rotation by animateFloatAsState(
            targetValue = if (regenerating) 360f else 0f,
            animationSpec = if (regenerating) infiniteRepeatable(
                animation = tween(900, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = RepeatMode.Restart
            ) else tween(0),
            label = "refreshRot"
        )
        IconButton(onClick = onRegenerate, enabled = !regenerating) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "重新生成",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayer { rotationZ = rotation }
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(50),
                ambientColor = Color(0x141B5E5C),
                spotColor = Color(0x141B5E5C)
            )
            .clip(RoundedCornerShape(50))
            .background(Color.White)
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
 * 时间轴单个时段：左侧圆点节点 + 右侧白色内容卡
 * - isCurrent: 当前/即将开始时段（放大 + 主题色渐变背景 + LIVE 呼吸动画）
 * - isPast: 已过去时段（灰色半透明 + 对勾标记）
 * - isUpcoming: 即将开始（非进行中，显示"即将开始"标签）
 */
@Composable
private fun TimelineSlotItem(
    slot: DailySlot,
    isCurrent: Boolean,
    isPast: Boolean = false,
    isUpcoming: Boolean = false
) {
    // Phase 1 分级睡眠：含 sleepDepth 的睡眠时段显示具体睡眠阶段标签
    // - sleepDepth="deep"  → "深睡"（深夜蓝，更沉）
    // - sleepDepth="light" → 沿用 slot.activity（"入睡浅睡"/"将醒浅睡"，淡紫蓝）
    // - sleepDepth=null    → 沿用原状态标签
    val sleepDepthLabel = when (slot.sleepDepth) {
        "deep" -> "深睡"
        "light" -> slot.activity.ifBlank { "浅睡" }
        else -> null
    }
    val stateLabel = sleepDepthLabel ?: slot.state.toStateLabel()
    val stateIcon = slot.state.toStateIcon()
    val stateColor = when (slot.sleepDepth) {
        "deep" -> ColorDeepSleep
        "light" -> ColorLightSleep
        else -> slot.state.toStateColor()
    }

    // 已过去时段整体淡化
    val pastAlpha = if (isPast) 0.5f else 1f

    // LIVE 呼吸点动画（仅当前时段显示，借鉴原 Hero 卡设计）
    val breathTransition = rememberInfiniteTransition(label = "slotBreath")
    val breathDot by breathTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathDot"
    )
    // 卡片轻微呼吸缩放（仅正在进行中，非 upcoming）
    val cardBreath by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isCurrent && !isUpcoming) 1.006f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cardBreath"
    )

    // 当前时段整体放大
    val itemScale by animateFloatAsState(
        targetValue = if (isCurrent) 1.04f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 380f
        ),
        label = "itemScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = itemScale * cardBreath
                scaleY = itemScale * cardBreath
                alpha = pastAlpha
            }
    ) {
        // 左侧：时间线 + 圆点节点
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 圆点节点
            val nodeScale by animateFloatAsState(
                targetValue = if (isCurrent) 1.3f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = 380f
                ),
                label = "nodeScale"
            )
            // 节点颜色：已结束灰 / 当前主题色实底 / 未来淡状态色
            val nodeBgColor = when {
                isPast -> PastNodeColor
                isCurrent -> stateColor
                else -> stateColor.copy(alpha = 0.15f)
            }
            // 当前时段节点外圈呼吸光晕
            if (isCurrent && !isUpcoming) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer { alpha = breathDot * 0.4f }
                        .clip(CircleShape)
                        .background(stateColor.copy(alpha = 0.3f))
                )
            }
            Box(
                modifier = Modifier
                    .size(if (isCurrent) 44.dp else 32.dp)
                    .graphicsLayer { scaleX = nodeScale; scaleY = nodeScale }
                    .clip(CircleShape)
                    .background(nodeBgColor),
                contentAlignment = Alignment.Center
            ) {
                if (isPast) {
                    // 已过去：对勾图标
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (isCurrent) 24.dp else 18.dp)
                    )
                } else {
                    Icon(
                        imageVector = stateIcon,
                        contentDescription = null,
                        tint = if (isCurrent) Color.White else stateColor,
                        modifier = Modifier.size(if (isCurrent) 24.dp else 18.dp)
                    )
                }
            }
            // 垂直连接线：已结束灰 / 当前+未来浅状态色
            val lineStartColor = if (isPast) PastLineColor.copy(alpha = 0.6f)
                                 else stateColor.copy(alpha = if (isCurrent) 0.6f else 0.3f)
            val lineEndColor = if (isPast) PastLineColor.copy(alpha = 0.1f)
                               else stateColor.copy(alpha = 0.05f)
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(56.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(lineStartColor, lineEndColor)
                        )
                    )
            )
        }

        Spacer(Modifier.width(14.dp))

        // 右侧：内容卡
        // - 当前：主题色渐变背景（0.08f → White） + 主题色描边 + 强阴影
        // - 已结束：浅灰底 + 无描边 + 弱阴影
        // - 未来：白底 + 淡状态色描边 + 中阴影
        val cardBorder = when {
            isCurrent -> stateColor.copy(alpha = 0.4f)
            isPast -> Color.Transparent
            else -> stateColor.copy(alpha = 0.15f)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = when {
                        isCurrent -> 12.dp
                        isPast -> 1.dp
                        else -> 3.dp
                    },
                    shape = RoundedCornerShape(if (isCurrent) 20.dp else 16.dp),
                    ambientColor = when {
                        isCurrent -> stateColor.copy(alpha = 0.3f)
                        isPast -> Color(0x08000000)
                        else -> Color(0x141B5E5C)
                    },
                    spotColor = when {
                        isCurrent -> stateColor.copy(alpha = 0.3f)
                        isPast -> Color(0x08000000)
                        else -> Color(0x141B5E5C)
                    }
                )
                .clip(RoundedCornerShape(if (isCurrent) 20.dp else 16.dp))
                .then(
                    if (isCurrent) Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(stateColor.copy(alpha = 0.08f), Color.White)
                        )
                    ) else Modifier.background(if (isPast) PastCardBg else Color.White)
                )
                .then(
                    if (cardBorder != Color.Transparent) Modifier.border(
                        if (isCurrent) 1.5.dp else 1.dp,
                        cardBorder,
                        RoundedCornerShape(if (isCurrent) 20.dp else 16.dp)
                    ) else Modifier
                )
        ) {
            Column(modifier = Modifier.padding(if (isCurrent) 20.dp else 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${slot.start} - ${slot.end}",
                        style = if (isCurrent) MaterialTheme.typography.titleMedium
                                else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            isPast -> PastTextColor                       // 已过去：灰
                            isCurrent -> stateColor                        // 当前：主题色
                            else -> MaterialTheme.colorScheme.onSurface    // 未来：标准色
                        }
                    )
                    // 状态标签：当前用主题色实底 + 白字，其他用浅色背景
                    if (isCurrent) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(stateColor)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .graphicsLayer { alpha = if (isUpcoming) 1f else breathDot }
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                            Text(
                                text = if (isUpcoming) "即将开始" else "LIVE",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        // Phase 1 分级睡眠：含 sleepDepth 的标签配色与卡片状态色联动
                        val labelColors = when {
                            isPast -> StateLabelColors(PastLabelBg, PastTextColor)
                            slot.sleepDepth == "deep" ->
                                StateLabelColors(Color(0xFFE8EAF6), ColorDeepSleep)
                            slot.sleepDepth == "light" ->
                                StateLabelColors(Color(0xFFEEF0F8), ColorLightSleep)
                            else -> stateLabelColors(slot.state)
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = labelColors.bg
                        ) {
                            Text(
                                text = when {
                                    isPast -> "已结束"
                                    else -> stateLabel
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = labelColors.fg,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                if (slot.activity.isNotBlank()) {
                    Spacer(Modifier.height(if (isCurrent) 10.dp else 6.dp))
                    Text(
                        text = slot.activity,
                        style = if (isCurrent) MaterialTheme.typography.bodyLarge
                                else MaterialTheme.typography.bodyMedium,
                        color = if (isPast) PastTextColor
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 当前时段底部：时间范围条 + LIVE 指示（借鉴原 Hero 卡设计）
                if (isCurrent) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(stateColor.copy(alpha = 0.08f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${slot.start} → ${slot.end}",
                            style = MaterialTheme.typography.titleSmall,
                            color = stateColor,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .graphicsLayer { alpha = if (isUpcoming) 1f else breathDot }
                                    .clip(CircleShape)
                                    .background(stateColor)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isUpcoming) "SOON" else "正在进行",
                                style = MaterialTheme.typography.labelMedium,
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 3.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color(0x141B5E5C),
                    spotColor = Color(0x141B5E5C)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
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
    "normal" -> "正常"
    "busy" -> "忙碌"
    "idle" -> "空闲"
    "unavailable" -> "休息"
    // 兼容旧状态值
    "sleep" -> "休息"
    "work", "game" -> "忙碌"
    "bath" -> "休息"
    "bored" -> "空闲"
    "happy" -> "正常"
    else -> this
}

private fun String.toStateIcon(): ImageVector = when (this) {
    "normal" -> Icons.Default.SentimentSatisfied
    "busy" -> Icons.Default.Work
    "idle" -> Icons.Default.HourglassEmpty
    "unavailable" -> Icons.Default.Bedtime
    // 兼容旧状态值
    "sleep" -> Icons.Default.Bedtime
    "work", "game" -> Icons.Default.Work
    "bath" -> Icons.Default.Shower
    "bored" -> Icons.Default.HourglassEmpty
    "happy" -> Icons.Default.SentimentSatisfied
    else -> Icons.Default.WorkHistory
}

// ===== 状态色板（低饱和莫兰迪风格辅助色系统）=====
// 保留品牌绿色作为主色，引入辅助色增强状态辨识度

// 已结束灰阶
private val PastNodeColor = Color(0xFFBFC6CF)       // 节点灰
private val PastLineColor = Color(0xFFE8ECF1)        // 连线灰
private val PastTextColor = Color(0xFFA8B1BC)        // 文字灰
private val PastCardBg = Color(0xFFFAFBFC)           // 卡片浅灰底
private val PastLabelBg = Color(0xFFF1F3F5)          // 标签浅灰底

// 辅助色（低饱和莫兰迪风格）
private val ColorIdle = Color(0xFF4FA3E0)            // 空闲 - 天蓝
private val ColorIdleBg = Color(0xFFE8F4FC)
private val ColorBusy = Color(0xFF7C5CFF)            // 忙碌 - 柔紫
private val ColorBusyBg = Color(0xFFF5F2FF)
private val ColorWork = Color(0xFF5B8CFF)            // 工作 - 蓝
private val ColorWorkBg = Color(0xFFEEF3FF)
private val ColorGame = Color(0xFFFF8A3D)            // 娱乐 - 橙
private val ColorGameBg = Color(0xFFFFF1E8)
private val ColorUnavailable = Color(0xFF6B7AB5)     // 休息 - 静谧夜蓝
private val ColorUnavailableBg = Color(0xFFF2F4F8)
// Phase 1 分级睡眠色板（在 ColorUnavailable 基础上区分深浅）
private val ColorDeepSleep = Color(0xFF3F4A8C)         // 深睡 - 深夜蓝（比 ColorUnavailable 更沉）
private val ColorLightSleep = Color(0xFF8B95C7)       // 浅睡 - 淡紫蓝（比 ColorUnavailable 浅）
private val ColorHappy = Color(0xFFE8A555)           // 开心 - 橙黄
private val ColorHappyBg = Color(0xFFFFF6E8)

// 状态标签背景/文字色对（10% 浅色背景 + 对应色文字）
private data class StateLabelColors(val bg: Color, val fg: Color)

@Composable
private fun stateLabelColors(state: String): StateLabelColors = when (state) {
    "normal" -> StateLabelColors(Color(0xFFEAF8F3), MaterialTheme.colorScheme.primary)
    "idle", "bored" -> StateLabelColors(Color(0xFFE8F4FC), Color(0xFF2A7AB8))
    "busy" -> StateLabelColors(Color(0xFFF2ECFF), Color(0xFF7158E2))
    "work" -> StateLabelColors(Color(0xFFE8EFFF), Color(0xFF4A7AD9))
    "game" -> StateLabelColors(Color(0xFFFFF0E0), Color(0xFFD97706))
    "unavailable", "sleep", "bath" -> StateLabelColors(Color(0xFFEEF0F5), Color(0xFF5A6B95))
    "happy" -> StateLabelColors(Color(0xFFFFF6E8), Color(0xFFB8761A))
    else -> StateLabelColors(Color(0xFFEEF2F4), MaterialTheme.colorScheme.outline)
}

@Composable
private fun String.toStateColor(): Color = when (this) {
    "normal" -> MaterialTheme.colorScheme.primary
    "busy" -> ColorBusy
    "idle" -> ColorIdle
    "unavailable" -> ColorUnavailable
    // 兼容旧状态值
    "sleep" -> ColorUnavailable
    "work" -> ColorWork
    "game" -> ColorGame
    "bath" -> ColorUnavailable
    "bored" -> ColorIdle
    "happy" -> ColorHappy
    else -> MaterialTheme.colorScheme.outline
}
