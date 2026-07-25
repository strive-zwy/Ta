package com.agent.ta.ui.screens.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.data.model.BehaviorConfig
import com.agent.ta.data.model.EmojiBehavior
import com.agent.ta.data.model.ReplyDelay
import com.agent.ta.data.model.StateInitiate
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ===== 行为配置页面设计色板 =====
// 复用 AgentConfigComponents 中的共享 Ai* 色板，保持与 Agent 配置页面一致
// 状态色仅作为局部点缀（Badge / 图标 / Slider / Switch）
private val StateColorNormal = AiPrimary              // 正常 - 青绿（主色）
private val StateColorBusy = Color(0xFF7B6EF6)        // 忙碌 - 紫
private val StateColorIdle = Color(0xFF4FA3E0)        // 空闲 - 天蓝

private fun stateColor(stateKey: String): Color = when (stateKey) {
    "normal" -> StateColorNormal
    "busy" -> StateColorBusy
    "idle" -> StateColorIdle
    else -> AiPrimary
}

private val STATE_LABELS: LinkedHashMap<String, String> = linkedMapOf(
    "normal" to "正常",
    "busy" to "忙碌",
    "idle" to "空闲",
    "unavailable" to "无法回复"
)

private fun stateSubtitle(stateKey: String): String = when (stateKey) {
    "normal" -> "可回复 · 标准积极性"
    "busy" -> "可回复 · 慢 / 简短"
    "idle" -> "可回复 · 快 / 主动"
    "unavailable" -> "不回复"
    else -> ""
}

private fun defaultStateHint(stateKey: String): String = when (stateKey) {
    "normal" -> "日常状态，语气平和自然，回复长度适中，积极参与对话。像平时和朋友聊天一样随意。"
    "busy" -> "正在忙碌，语速偏快，回复简短直接，可能会提及正在处理的事务。不闲聊，结束时可能说'先去忙了'。"
    "idle" -> "空闲状态，乐于交流，话变多，会主动找话题或分享趣事。随意拖音，慵懒俏皮，偶尔撒娇求关注。"
    "unavailable" -> "无法回复，处于睡觉或洗澡等状态，不会发送消息。"
    else -> "该状态下的语气、语速、情绪、回复风格指导"
}

/**
 * 行为配置页面（现代简洁版）
 *
 * 设计语言：Apple Settings × Linear × Notion
 * - 浅灰背景 + 白色单层 Card + 极淡阴影
 * - 状态色仅作为 Badge / 图标点缀
 * - 信息层级：状态名 18sp 加粗 + 13sp 灰色描述
 * - 留白代替边框，8px 栅格间距
 */
@Composable
fun AgentBehaviorScreen(onBack: () -> Unit) {
    val editor = ServiceLocator.agentConfigEditor
    val behavior = remember { editor.get().behavior }
    val allStates = remember { linkedSetOf("normal", "busy", "idle") }

    fun ReplyDelay?.rangeOr(defaultMin: Int, defaultMax: Int): Pair<Float, Float> =
        (this as? ReplyDelay.Range)?.let { it.min.toFloat() to it.max.toFloat() }
            ?: (defaultMin.toFloat() to defaultMax.toFloat())

    val replyDelayMap = remember {
        mutableStateMapOf<String, Pair<Float, Float>>().apply {
            this["idle"] = behavior.replyDelaySec["idle"].rangeOr(1, 3)
            this["normal"] = behavior.replyDelaySec["normal"].rangeOr(3, 8)
            this["busy"] = behavior.replyDelaySec["busy"].rangeOr(30, 120)
        }
    }

    val stateHints = remember {
        mutableStateMapOf<String, String>().apply {
            allStates.forEach { state -> this[state] = behavior.stateDirectorHints[state] ?: "" }
        }
    }

    val initiateEnabled = remember {
        mutableStateMapOf<String, Boolean>().apply {
            allStates.forEach { state -> this[state] = behavior.perStateInitiate[state]?.enabled ?: false }
        }
    }
    val initiateLevel = remember {
        mutableStateMapOf<String, String>().apply {
            allStates.forEach { state ->
                this[state] = behavior.perStateInitiate[state]?.initiateLevel ?: "normal"
            }
        }
    }

    var justSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(AiBg)) {
        VibeTopBar(title = "行为配置", subtitle = "回复延迟 · 状态导演 · 主动发起", onBack = onBack)
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                // 三个状态合并为一张大卡片，每行用分割线分隔
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 4.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = AiShadowColor,
                            spotColor = AiShadowColor
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .background(AiCard)
                ) {
                    allStates.forEachIndexed { idx, state ->
                        StateBehaviorRow(
                            stateKey = state,
                            stateLabel = STATE_LABELS[state] ?: state,
                            stateHint = stateHints[state] ?: "",
                            onStateHintChange = { stateHints[state] = it },
                            replyDelay = replyDelayMap[state],
                            onReplyDelayChange = { replyDelayMap[state] = it },
                            initiateEnabled = initiateEnabled[state] ?: false,
                            initiateLevel = initiateLevel[state] ?: "normal",
                            onInitiateEnabledChange = { initiateEnabled[state] = it },
                            onInitiateLevelChange = { initiateLevel[state] = it },
                            showTopDivider = idx != 0
                        )
                    }
                }
                Spacer(Modifier.height(80.dp))
            }

            // 底部悬浮保存按钮
            SaveActionBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                justSaved = justSaved,
                onClick = {
                    val newReplyDelay = buildMap<String, ReplyDelay> {
                        replyDelayMap.forEach { (state, range) ->
                            put(state, ReplyDelay.Range(range.first.toInt(), range.second.toInt()))
                        }
                        put("unavailable", ReplyDelay.Defer)
                    }
                    val newInitiate = buildMap<String, StateInitiate> {
                        allStates.forEach { state ->
                            val existing = behavior.perStateInitiate[state]
                            put(
                                state,
                                (existing ?: StateInitiate()).copy(
                                    enabled = initiateEnabled[state] ?: false,
                                    initiateLevel = initiateLevel[state] ?: "normal"
                                )
                            )
                        }
                    }
                    val newBehavior = BehaviorConfig(
                        replyDelaySec = newReplyDelay,
                        boredInitiate = behavior.boredInitiate,
                        stateDirectorHints = stateHints.toMap(),
                        emoji = EmojiBehavior(),
                        perStateInitiate = newInitiate,
                        typingIndicatorDuration = behavior.typingIndicatorDuration,
                        messageLengthHints = behavior.messageLengthHints
                    )
                    scope.launch {
                        editor.update { config -> config.copy(behavior = newBehavior) }
                        justSaved = true
                        delay(1000)
                        justSaved = false
                    }
                }
            )
        }
    }
}

// =============================================================
// 状态行为行（大卡片内的一行，顶部带分割线，可独立折叠）
// =============================================================
@Composable
private fun StateBehaviorRow(
    stateKey: String,
    stateLabel: String,
    stateHint: String,
    onStateHintChange: (String) -> Unit,
    replyDelay: Pair<Float, Float>?,
    onReplyDelayChange: (Pair<Float, Float>) -> Unit,
    initiateEnabled: Boolean,
    initiateLevel: String,
    onInitiateEnabledChange: (Boolean) -> Unit,
    onInitiateLevelChange: (String) -> Unit,
    showTopDivider: Boolean
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val accent = stateColor(stateKey)
    val subtitle = stateSubtitle(stateKey)

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showTopDivider) {
            HorizontalDivider(thickness = 1.dp, color = AiBorder)
        }

        // 头部行：圆点 + 标题 + 描述 + 展开图标
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(accent)
                    )
                    Text(
                        text = stateLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AiTextPrimary
                    )
                }
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = AiTextTertiary,
                    modifier = Modifier.padding(top = 2.dp, start = 16.dp)
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = AiTextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }

        // 展开内容
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(220)) + expandVertically(tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (replyDelay != null) {
                    ReplyDelaySection(
                        minValue = replyDelay.first,
                        maxValue = replyDelay.second,
                        onMinChange = { onReplyDelayChange(it to replyDelay.second) },
                        onMaxChange = { onReplyDelayChange(replyDelay.first to it) },
                        accent = accent
                    )
                }

                DirectorHintSection(
                    stateKey = stateKey,
                    hint = stateHint,
                    onHintChange = onStateHintChange,
                    accent = accent
                )

                InitiateSection(
                    enabled = initiateEnabled,
                    onEnabledChange = onInitiateEnabledChange,
                    level = initiateLevel,
                    onLevelChange = onInitiateLevelChange,
                    accent = accent
                )
            }
        }
    }
}

// =============================================================
// 回复延迟模块
// =============================================================
@Composable
private fun ReplyDelaySection(
    minValue: Float,
    maxValue: Float,
    onMinChange: (Float) -> Unit,
    onMaxChange: (Float) -> Unit,
    accent: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "回复延迟",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AiTextSecondary
            )
            Text(
                text = "${minValue.toInt()}-${maxValue.toInt()} 秒",
                fontSize = 13.sp,
                color = accent,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(8.dp))
        SimpleRangeSlider(
            minValue = minValue,
            maxValue = maxValue,
            onMinChange = onMinChange,
            onMaxChange = onMaxChange,
            accent = accent
        )
    }
}

/**
 * iOS 风格双滑块 RangeSlider：与语音配置页 IosStyleSlider 视觉统一
 * - 5dp 高自绘渐变 track（min→max 区间为渐变色，两端为灰色）
 * - 7dp 白色 thumb + 情绪色光环
 * - 拖动时显示数值气泡
 * - 5s 量化吸附
 */
@Composable
private fun SimpleRangeSlider(
    minValue: Float,
    maxValue: Float,
    onMinChange: (Float) -> Unit,
    onMaxChange: (Float) -> Unit,
    accent: Color
) {
    val range = 0f..300f
    val span = range.endInclusive - range.start
    val minFrac = ((minValue - range.start) / span).coerceIn(0f, 1f)
    val maxFrac = ((maxValue - range.start) / span).coerceIn(0f, 1f)
    val gradientEndColor = lerp(accent, Color.White, 0.45f)

    var draggingMin by remember { mutableStateOf(false) }
    var draggingMax by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .drawBehind {
                val trackHeight = 5.dp.toPx()
                val trackY = size.height - trackHeight - 2.dp.toPx()
                val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                val minPosX = size.width * minFrac
                val maxPosX = size.width * maxFrac

                // 1. inactive track（灰色底，整条）
                drawRoundRect(
                    color = AiBorder,
                    topLeft = Offset(0f, trackY),
                    size = Size(size.width, trackHeight),
                    cornerRadius = cornerRadius
                )

                // 2. active track（渐变填充，min→max 区间）
                val activeWidth = (maxPosX - minPosX).coerceAtLeast(0f)
                if (activeWidth > 0f) {
                    val activeBrush = Brush.horizontalGradient(
                        colors = listOf(accent, gradientEndColor),
                        startX = minPosX,
                        endX = maxPosX
                    )
                    drawRoundRect(
                        brush = activeBrush,
                        topLeft = Offset(minPosX, trackY),
                        size = Size(activeWidth, trackHeight),
                        cornerRadius = cornerRadius
                    )
                }

                // 3. 两个 thumb：min 和 max
                val thumbRadius = 7.dp.toPx()
                val thumbY = trackY + trackHeight / 2f
                listOf(
                    Triple(minPosX, draggingMin, minValue),
                    Triple(maxPosX, draggingMax, maxValue)
                ).forEach { (thumbX, isDragging, thumbValue) ->
                    // 外圈光环
                    drawCircle(
                        color = accent.copy(alpha = 0.28f),
                        radius = thumbRadius + 3.dp.toPx(),
                        center = Offset(thumbX, thumbY)
                    )
                    // 白色实心圆
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadius,
                        center = Offset(thumbX, thumbY)
                    )

                    // 拖动时显示数值气泡
                    if (isDragging) {
                        val valueText = "${thumbValue.toInt()}"
                        val textPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 28f
                            typeface = android.graphics.Typeface.create(
                                android.graphics.Typeface.DEFAULT,
                                android.graphics.Typeface.BOLD
                            )
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        val fontMetrics = textPaint.fontMetrics
                        val textHeight = fontMetrics.descent - fontMetrics.ascent
                        val bubblePadH = 14f
                        val bubblePadV = 6f
                        val bubbleWidth = textPaint.measureText(valueText) + bubblePadH * 2
                        val bubbleHeight = textHeight + bubblePadV * 2
                        val bubbleBottom = thumbY - thumbRadius - 8.dp.toPx()
                        val bubbleTop = bubbleBottom - bubbleHeight
                        val bubbleLeft = (thumbX - bubbleWidth / 2f)
                            .coerceIn(0f, size.width - bubbleWidth)

                        drawRoundRect(
                            color = accent,
                            topLeft = Offset(bubbleLeft, bubbleTop),
                            size = Size(bubbleWidth, bubbleHeight),
                            cornerRadius = CornerRadius(bubbleHeight / 2f, bubbleHeight / 2f)
                        )
                        val arrowHalfWidth = 4.dp.toPx()
                        val arrowTipY = bubbleBottom + 1f
                        val arrowBaseY = bubbleBottom
                        val arrowCenterX = thumbX.coerceIn(
                            bubbleLeft + arrowHalfWidth,
                            bubbleLeft + bubbleWidth - arrowHalfWidth
                        )
                        val arrowPath = android.graphics.Path().apply {
                            moveTo(arrowCenterX - arrowHalfWidth, arrowBaseY)
                            lineTo(arrowCenterX + arrowHalfWidth, arrowBaseY)
                            lineTo(arrowCenterX, arrowTipY)
                            close()
                        }
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawPath(arrowPath, android.graphics.Paint().apply {
                                color = accent.toArgb()
                                isAntiAlias = true
                            })
                        }
                        val textCenterX = bubbleLeft + bubbleWidth / 2f
                        val textBaseline = bubbleTop + bubblePadV - fontMetrics.ascent
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(
                                valueText,
                                textCenterX,
                                textBaseline,
                                textPaint
                            )
                        }
                    }
                }
            }
    ) {
        RangeSlider(
            value = minValue..maxValue,
            onValueChange = { values ->
                val step = 5f
                val newMin = (values.start / step).toInt() * step
                val newMax = (values.endInclusive / step).toInt() * step
                if (newMin <= newMax) {
                    // 判断拖动的是哪个 thumb（比较变化量）
                    if (newMin != minValue) draggingMin = true
                    if (newMax != maxValue) draggingMax = true
                    onMinChange(newMin)
                    onMaxChange(newMax)
                }
            },
            valueRange = range,
            onValueChangeFinished = {
                draggingMin = false
                draggingMax = false
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// =============================================================
// 导演提示模块（行式 Cell，点击展开编辑）
// =============================================================
@Composable
private fun DirectorHintSection(
    stateKey: String,
    hint: String,
    onHintChange: (String) -> Unit,
    accent: Color
) {
    var editing by rememberSaveable(stateKey) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 标题行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = !editing },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "导演提示",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AiTextSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!editing) {
                    Text(
                        text = if (hint.isBlank()) "未配置" else "已配置 ${hint.length} 字",
                        fontSize = 12.sp,
                        color = if (hint.isBlank()) AiTextTertiary else AiTextSecondary
                    )
                }
                Icon(
                    imageVector = if (editing) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (editing) "收起" else "编辑",
                    tint = AiTextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = editing,
            enter = fadeIn(tween(220)) + expandVertically(tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing))
        ) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                SimpleTextField(
                    value = hint,
                    onValueChange = onHintChange,
                    placeholder = defaultStateHint(stateKey),
                    singleLine = false
                )
            }
        }
    }
}

// =============================================================
// 主动发起模块（标准 Setting Cell + 频率 Segmented Control）
// =============================================================
@Composable
private fun InitiateSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    level: String,
    onLevelChange: (String) -> Unit,
    accent: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Setting Cell：标题 + 辅助说明 + Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "主动发起",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AiTextSecondary
                )
                Text(
                    text = "允许 AI 主动开启聊天",
                    fontSize = 12.sp,
                    color = AiTextTertiary
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = accent,
                    uncheckedTrackColor = AiBorder
                )
            )
        }

        // 频率 Segmented Control（仅在启用时显示）
        AnimatedVisibility(
            visible = enabled,
            enter = fadeIn(tween(220)) + expandVertically(tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing))
        ) {
            Column(modifier = Modifier.padding(top = 14.dp)) {
                Text(
                    text = "主动发起频率",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AiTextSecondary
                )
                Spacer(Modifier.height(8.dp))
                SegmentedLevelSelector(
                    level = level,
                    onLevelChange = onLevelChange,
                    accent = accent
                )
            }
        }
    }
}

// =============================================================
// Segmented Control 风格的档位选择器
// =============================================================
@Composable
private fun SegmentedLevelSelector(
    level: String,
    onLevelChange: (String) -> Unit,
    accent: Color
) {
    val levels = StateInitiate.ALL_LEVELS
    val labels = levels.map { StateInitiate.levelToLabel(it) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AiBorder)
            .padding(3.dp)
    ) {
        // 用 Row 均分布局 + 选中项背景动画
        Row(modifier = Modifier.fillMaxWidth()) {
            levels.forEachIndexed { idx, lvl ->
                val isSelected = lvl == level
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) AiCard else Color.Transparent)
                        .clickable { onLevelChange(lvl) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labels[idx],
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) accent else AiTextSecondary
                    )
                }
            }
        }
    }
}

// =============================================================
// 简洁文本输入框
// =============================================================
@Composable
private fun SimpleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) AiInputBgFocused else AiInputBg)
            .border(
                width = 1.dp,
                color = if (focused) AiPrimary.copy(alpha = 0.3f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 13.sp,
                color = AiTextTertiary
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            interactionSource = interactionSource,
            textStyle = TextStyle(
                fontSize = 13.sp,
                color = AiTextPrimary,
                lineHeight = 20.sp
            ),
            cursorBrush = SolidColor(AiPrimary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// =============================================================
// 底部悬浮保存按钮（56dp 高度，圆角 18dp，渐变主色）
// =============================================================
@Composable
private fun SaveActionBar(
    modifier: Modifier = Modifier,
    justSaved: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (justSaved) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "saveScale"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .background(AiBg)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(18.dp),
                    ambientColor = AiPrimary.copy(alpha = 0.3f),
                    spotColor = AiPrimary.copy(alpha = 0.3f)
                )
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf(AiPrimary, AiPrimaryDeep)))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (justSaved) "已保存" else "保存修改",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}
