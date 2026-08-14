package com.agent.ta.ui.screens.chat

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.resolveCurrentAvatarFile
import com.agent.ta.di.ServiceLocator
import com.agent.ta.service.AgentEngine
import com.agent.ta.ui.theme.ReceivedBubbleShape
import com.agent.ta.ui.theme.SentBubbleShape
import com.agent.ta.ui.theme.TaMotion
import com.agent.ta.ui.theme.VibeBubbleInBg
import com.agent.ta.ui.theme.VibeBubbleInBorder
import com.agent.ta.ui.theme.VibePrimary
import com.agent.ta.ui.theme.VibePrimaryDeep
import com.agent.ta.ui.theme.VibePrimaryTint

// ===== AI 风格聊天页本地色板（仅作用于聊天页，不影响其他页面主题）=====
private val AiBg = Color(0xFFF7F9F8)                  // 页面背景 奶白
private val AiPrimary = Color(0xFF2F8F89)             // 主品牌色 青绿
private val AiPrimaryHover = Color(0xFF3AA39A)        // hover 渐变终点
private val AiAccentBlue = Color(0xFF5B8DEF)          // 辅助蓝（转文字）
private val AiSystemGreen = Color(0xFF34C759)         // 在线状态系统绿
private val AiTimeText = Color(0xFF98A2B3)            // 时间弱化灰
private val AiTimePillBg = Color(0xFFF4F6F8)          // 时间胶囊底
private val AiActionText = Color(0xFF6B8E86)          // 动作描写青灰
private val AiHeaderBg = Color(0xFFFFFFFF)            // header 白底（用 alpha 实现 0.82）
private val AiHeaderDivider = Color(0xFFEEF2F4)       // header 底部分割线
private val AiInputBg = Color(0xFFFFFFFF)             // 输入栏白底
private val AiInputFieldBg = Color(0xFFFAFBFC)        // 输入框底
private val AiRoundBtnBg = Color(0xFFF3F6F7)          // 圆形按钮底（麦/表情）

/**
 * ChatScreen — Vibe Chat 风格聊天页（直接作为 App 入口）
 *
 * 设计原则：
 * 1. **整体背景**：pale tinted 纯色（primaryContainer 的极浅调），与 sticky header/input 半透明白色
 *    自然融合，不再是分段 mesh
 * 2. **顶部联系人栏**：sticky + 半透明白色 + blur，右上角设置图标 → 跳转配置页
 * 3. **消息气泡**：
 *    - Agent：白色气泡 + 细边框 + 左上角小圆角 + 左侧 Agent 头像
 *    - 用户：primary 纯色气泡 + 右上角小圆角 + 右侧用户头像
 * 4. **语音消息**：voice bar 在白色气泡内（play + waveform + duration），转文字 chip + 时间在气泡外
 * 5. **底部输入栏**：sticky + 半透明白色 + blur，pill 输入框
 */
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(),
    onOpenSettings: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val playingPath by viewModel.playingPath.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isReplying by viewModel.isReplying.collectAsState()
    val configCollectingCustom by viewModel.configCollectingCustom.collectAsState()
    val hasMoreOlder by viewModel.hasMoreOlder.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val listState = rememberLazyListState()
    val agentConfig by ServiceLocator.agentConfigProvider.config.collectAsState()
    val agentState by AgentEngine.currentState.collectAsState()
    var showEmojiPanel by rememberSaveable { mutableStateOf(false) }
    var emojiCategoryIndex by rememberSaveable { mutableStateOf(0) }

    // 仅当最后一条消息变化时（新消息追加）才自动滚到底部，
    // 加载更早消息（前置插入）不触发滚动
    var lastMsgId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(messages.lastOrNull()?.id, isReplying) {
        if (messages.isNotEmpty()) {
            val currentLastId = messages.last().id
            if (currentLastId != lastMsgId) {
                lastMsgId = currentLastId
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // 滚到顶部时自动加载更多
    var canLoadMore by remember { mutableStateOf(true) }
    val atTop by remember {
        derivedStateOf {
            hasMoreOlder && listState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(atTop) {
        if (atTop && canLoadMore && !isLoadingMore && messages.isNotEmpty()) {
            canLoadMore = false
            viewModel.loadMoreOlder()
        } else if (!atTop) {
            canLoadMore = true
        }
    }

    val emojiEnabled = EmojiCatalog.isEmojiEnabled(agentConfig)

    Box(modifier = modifier.fillMaxSize()) {
        // ===== 1. 整体奶白背景 #F7F9F8（柔和、减少阅读疲劳）=====
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AiBg)
        )

        // ===== 2. 内容层 =====
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // ===== 3. 顶部联系人栏（sticky + 半透明白色 + blur 模拟）=====
            ContactHeader(
                agentName = agentConfig.agent.name.ifBlank { "小雅" },
                agentState = agentState,
                agentConfig = agentConfig,
                onOpenSettings = onOpenSettings
            )

            // ===== 4. 消息列表 =====
            // emoji 面板打开时，点击消息区域关闭面板（clickable 只响应 tap，不影响滚动）
            val dismissModifier = if (showEmojiPanel && emojiEnabled) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showEmojiPanel = false }
            } else Modifier

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(dismissModifier)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // 加载更多指示器
                    if (hasMoreOlder) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                } else {
                                    Text(
                                        text = "继续上滑加载更多",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }

                    // 日期分隔条
                    item { DateSeparator(timestamp = System.currentTimeMillis()) }

                    itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                        MessageBubble(
                            message = msg,
                            isPlaying = playingPath == msg.audioPath && isPlaying,
                            onTogglePlay = { msg.audioPath?.let { viewModel.toggleVoicePlay(it) } },
                            quickOptions = ConfigQuickReplyPolicy.resolve(msg.text, configMode = configCollectingCustom && msg.direction == "outbound"),
                            onQuickReply = viewModel::sendQuickReply,
                            enterDelayMs = TaMotion.staggerDelayMs(index)
                        )
                    }

                    // 正在输入 pill 气泡（消息流尾部）
                    if (isReplying && agentState != AgentState.UNAVAILABLE) {
                        item { TypingBubble() }
                    }
                }
            }

            // ===== 5. emoji 面板 =====
            AnimatedVisibility(
                visible = emojiEnabled && showEmojiPanel,
                enter = fadeIn() + slideInVertically(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f),
                    initialOffsetY = { it / 3 }
                ),
                exit = fadeOut() + slideOutVertically()
            ) {
                EmojiPickerPanel(
                    emojis = EmojiCatalog.resolveActiveEmojis(agentConfig),
                    categoryIndex = emojiCategoryIndex,
                    onCategoryChange = { emojiCategoryIndex = it },
                    onEmojiClick = { emoji -> viewModel.appendEmoji(emoji) }
                )
            }

            // ===== 6. 底部输入栏（sticky + 半透明白色 + blur）=====
            VibeChatInputBar(
                text = inputText,
                onTextChange = viewModel::updateInput,
                onSend = { viewModel.sendMessage() },
                showEmojiPanel = showEmojiPanel,
                onToggleEmojiPanel = { showEmojiPanel = !showEmojiPanel },
                emojiEnabled = emojiEnabled
            )
        }
    }
}

/**
 * 顶部联系人栏 — sticky + 半透明白色 + blur + 底部边框
 *
 * 结构：Agent 头像(40dp) + 名称/在线状态(gap 10dp) → 设置图标(右上角 32dp 框 + 20dp 图标)
 */
@Composable
private fun ContactHeader(
    agentName: String,
    agentState: AgentState,
    agentConfig: com.agent.ta.data.model.AgentConfig,
    onOpenSettings: () -> Unit
) {
    // 悬浮玻璃 Header：半透明白底 0.82 + 底部分割线 #EEF2F4
    Surface(
        color = AiHeaderBg.copy(alpha = 0.82f),
        tonalElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 头像 + 在线绿点（系统绿 #34C759）
                Box {
                    AgentAvatar(
                        modifier = Modifier.size(40.dp),
                        circular = true
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(AiSystemGreen)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                // 名称 + 在线状态
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agentName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AiSystemGreen)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "在线",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 设置按钮：36×36 圆形 + 白底 + 柔和阴影
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .shadow(
                            elevation = 4.dp,
                            shape = CircleShape,
                            ambientColor = Color(0x141E323C),
                            spotColor = Color(0x141E323C)
                        )
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = AiHeaderDivider
            )
        }
    }
}

/**
 * 日期/时间分隔条 — 居中胶囊（#F4F6F8 底 + Radius 999 + #98A2B3 字）
 */
@Composable
private fun DateSeparator(timestamp: Long) {
    val fmt = remember(timestamp) {
        val cal = java.util.Calendar.getInstance()
        val msgCal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val isToday = cal.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR) &&
                      cal.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR)
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        (if (isToday) "今天 " else "") + sdf.format(java.util.Date(timestamp))
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = fmt,
            fontSize = 13.sp,
            color = AiTimeText,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(AiTimePillBg)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/**
 * 正在输入 pill 气泡（消息流尾部）
 */
@Composable
private fun TypingBubble() {
    val transition = rememberInfiniteTransition(label = "typingBubble")
    val dots = listOf(0, 1, 2).map { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = TaMotion.TYPING_DOT_INTERVAL_MS,
                    delayMillis = index * (TaMotion.TYPING_DOT_INTERVAL_MS / 3),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "tdot$index"
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // 左侧 Agent 头像
        AgentAvatar(
            modifier = Modifier.size(32.dp),
            circular = true
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = TaMotion.DEFAULT_ELEVATION,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dots.forEach { dot ->
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .scale(dot.value)
                            .clip(CircleShape)
                            .background(AiPrimary.copy(alpha = dot.value))
                    )
                }
            }
        }
    }
}

/**
 * 消息气泡 — Vibe Chat 风格
 *
 * - 接收（Agent）：白底气泡 + 左上角小圆角 + 左侧 Agent 头像 + 气泡下时间
 * - 发送（用户）：primary 纯色气泡 + 右上角小圆角 + 右侧用户头像 + 气泡下已读 + 时间
 * - 纯 emoji：56sp 无气泡大字号
 * - 动作描述：在气泡上方以斜体小字展示
 */
@Composable
private fun MessageBubble(
    message: ChatMessageEntity,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    quickOptions: List<ConfigQuickReplyOption>,
    onQuickReply: (String) -> Unit,
    enterDelayMs: Int
) {
    val isUser = message.direction == "inbound"
    val isPureEmoji = !message.emoji.isNullOrBlank() &&
                      message.text.isNullOrBlank() &&
                      message.audioPath == null

    // 入场动画
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(enterDelayMs) {
        if (enterDelayMs > 0) kotlinx.coroutines.delay(enterDelayMs.toLong())
        visible = true
    }
    val enterScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 380f
        ),
        label = "enterScale"
    )
    val enterAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 500f
        ),
        label = "enterAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = enterScale
                scaleY = enterScale
                alpha = enterAlpha
                translationY = (1f - enterScale) * 60f
            },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Agent 头像（仅 Agent 消息，左侧）
        if (!isUser) {
            AgentAvatar(
                modifier = Modifier.size(32.dp),
                circular = true
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // 内容列：动作描述 → 气泡 → 时间
        Column(modifier = Modifier.widthIn(max = 280.dp)) {
            // 动作描写（仅 Agent，斜体 #6B8E86，比正文浅一级，更像小说）
            if (!isUser && !message.action.isNullOrBlank()) {
                Text(
                    text = "（${message.action}）",
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = AiActionText,
                    modifier = Modifier.padding(bottom = 10.dp, start = 6.dp)
                )
            }

            // ===== 纯 emoji 消息（无气泡，56sp 大字号）=====
            if (isPureEmoji) {
                Text(
                    text = message.emoji!!,
                    fontSize = 56.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                        .widthIn(min = 72.dp)
                )
                // 时间
                Text(
                    text = formatTime(message.createdAt),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 6.dp)
                )
                return@Column
            }

            // ===== 气泡 =====
            // 注：原 Surface 包裹会自带 .clip(shape) 裁掉内部 shadow，故改为 Box + shadow + clip 直接组合
            if (isUser) {
                // 用户消息：品牌色轻微渐变 #2F8F89 → #3BA39A + 圆角22 + 柔和阴影
                Box(
                    modifier = Modifier
                        .shadow(
                            elevation = 10.dp,
                            shape = RoundedCornerShape(22.dp),
                            ambientColor = Color(0x402F8F89),
                            spotColor = Color(0x592F8F89)
                        )
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(AiPrimary, AiPrimaryHover)
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    MessageContent(message, isUser, isPlaying, onTogglePlay)
                }
            } else {
                // Agent 消息：白底漂浮卡片 + 柔和阴影 + 圆角24（无描边，靠阴影建立层级）
                Box(
                    modifier = Modifier
                        .shadow(
                            elevation = 14.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = Color(0x331E323C),
                            spotColor = Color(0x4D1E323C)
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    MessageContent(message, isUser, isPlaying, onTogglePlay)
                }
            }

            if (!isUser && quickOptions.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickOptions.forEach { option ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onQuickReply(option.message) },
                            shape = RoundedCornerShape(16.dp),
                            color = AiPrimary.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AiPrimary.copy(alpha = 0.22f))
                        ) {
                            Text(
                                text = option.label,
                                color = AiPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            // ===== 气泡下时间 + 已读 =====
            // 设计稿变体 1：Agent 语音消息的"转文字"按钮在气泡外，与时间戳并排
            val isAgentVoice = !isUser && message.audioPath != null
            if (isAgentVoice) {
                // 转文字时把 emoji 拼到文字前面
                val transcript = buildString {
                    if (!message.emoji.isNullOrBlank()) {
                        append(message.emoji)
                        if (!message.text.isNullOrBlank()) append("  ")
                    }
                    if (!message.text.isNullOrBlank()) append(message.text)
                }.ifBlank { null }
                VoiceMessageFooter(
                    transcript = transcript,
                    timestamp = message.createdAt,
                    timeText = formatTime(message.createdAt),
                    modifier = Modifier
                        .padding(top = 2.dp, start = 6.dp, end = 6.dp)
                        .widthIn(max = 280.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .padding(top = 2.dp, start = 6.dp, end = 6.dp)
                        .widthIn(max = 280.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    if (isUser) {
                        // 根据 status 显示已读/未读：
                        // - pending: 未读（Agent 还没看到，busy 延迟期间或 unavailable）
                        // - received: 已读（Agent 已看到，正在回复）
                        // - sent: 已发送（理论上用户消息不会是 sent，兜底按已读显示）
                        val readStatus = when (message.status) {
                            "pending", "processing" -> "未读"
                            "received" -> "已读"
                            else -> "已读"
                        }
                        val readColor = if (message.status == "pending" || message.status == "processing") {
                            // 未读用更淡的颜色，降低视觉权重
                            AiTimeText.copy(alpha = 0.6f)
                        } else {
                            AiTimeText
                        }
                        Text(
                            text = readStatus,
                            fontSize = 10.sp,
                            color = readColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formatTime(message.createdAt),
                        fontSize = 10.sp,
                        color = AiTimeText
                    )
                }
            }
        }

        // 用户头像（仅用户消息，右侧）
        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            UserAvatar(modifier = Modifier.size(32.dp))
        }
    }
}

/**
 * 消息内容（语音 + 文字 + emoji）
 *
 * 注意：Agent 语音消息的"转文字"按钮和展开文字由 [VoiceMessageFooter] 在气泡外渲染，
 * 不在此处显示；用户语音消息不显示转文字按钮（无 transcript）。
 */
@Composable
private fun MessageContent(message: ChatMessageEntity, isUser: Boolean, isPlaying: Boolean, onTogglePlay: () -> Unit) {
    Column {
        if (message.audioPath != null) {
            VoiceBubble(
                isPlaying = isPlaying,
                durationSec = message.audioDurationSec ?: 1,
                onTogglePlay = onTogglePlay,
                isUser = isUser,
                modifier = Modifier.widthIn(min = 160.dp, max = 220.dp)
            )
        }
        val hasText = !message.text.isNullOrBlank()
        val hasEmoji = !message.emoji.isNullOrBlank()
        val showText = hasText && (isUser || message.audioPath == null)
        // 语音消息的 emoji 只在"转文字"中展示，不渲染在气泡内
        val showEmoji = hasEmoji && message.audioPath == null
        if (showText || showEmoji) {
            val combined = buildString {
                if (showEmoji) append(message.emoji)
                if (showEmoji && showText) append("  ")
                if (showText) append(message.text)
            }
            Text(
                text = combined,
                fontSize = if (showEmoji && !showText) 32.sp else 15.sp,
                color = if (isUser) Color.White
                        else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 悬浮 Dock 输入栏 — 白底圆角28 + 柔和阴影 + 距底16 左右16，不贴底部
 *
 * 结构：麦克风(44dp 圆形) → 输入框(#FAFBFC 圆角22) → emoji(44dp 圆形) → 发送(44dp 品牌渐变 + 阴影)
 */
@Composable
private fun VibeChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    showEmojiPanel: Boolean,
    onToggleEmojiPanel: () -> Unit,
    emojiEnabled: Boolean
) {
    // 输入框焦点：点击输入框容器任意位置都能聚焦 BasicTextField
    val inputFocusRequester = remember { FocusRequester() }

    // 悬浮 Dock：白底 + 圆角28 + 阴影，距底16/左右16
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color(0x141E323C),
                    spotColor = Color(0x141E323C)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(AiInputBg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 语音输入入口暂隐藏，后续实现"按住说话"功能后再显示
            // （原 44×44 圆形 Mic 按钮，git 历史可查）

            // 输入框（#FAFBFC 底 + 圆角22 + 无边框）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(AiInputFieldBg)
                    .clickable { inputFocusRequester.requestFocus() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AiPrimary),
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                "说点什么…",
                                color = AiTimeText,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                )
            }

            // emoji 按钮（44×44 圆形，#F3F6F7 底）
            if (emojiEnabled) {
                val iconScale by animateFloatAsState(
                    targetValue = if (showEmojiPanel) 1.15f else 1f,
                    animationSpec = TaMotion.fastSpatial(),
                    label = "emojiBtnScale"
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AiRoundBtnBg)
                        .clickable { onToggleEmojiPanel() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = "表情",
                        tint = if (showEmojiPanel) AiPrimary
                               else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.scale(iconScale).size(20.dp)
                    )
                }
            }

            // 发送按钮（44×44 圆形 + 品牌渐变 #2F8F89→#3AA39A + 阴影）
            val sendScale by animateFloatAsState(
                targetValue = if (text.isNotBlank()) 1f else 0.85f,
                animationSpec = TaMotion.fastSpatial(),
                label = "sendScale"
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .scale(sendScale)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x402F8F89),
                        spotColor = Color(0x402F8F89)
                    )
                    .clip(CircleShape)
                    .background(
                        if (text.isNotBlank()) Brush.linearGradient(
                            colors = listOf(AiPrimary, AiPrimaryHover)
                        )
                        else Brush.linearGradient(
                            colors = listOf(AiRoundBtnBg, AiRoundBtnBg)
                        )
                    )
                    .clickable(enabled = text.isNotBlank()) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (text.isNotBlank()) Color.White
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * emoji 面板（带分类 Carousel）
 */
@Composable
private fun EmojiPickerPanel(
    emojis: List<String>,
    categoryIndex: Int,
    onCategoryChange: (Int) -> Unit,
    onEmojiClick: (String) -> Unit
) {
    val categories = EmojiCatalog.categories
    val currentEmojis = if (categoryIndex in categories.indices) categories[categoryIndex].emojis
                        else emojis
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = TaMotion.FLOATING_ELEVATION
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .padding(8.dp)
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                itemsIndexed(categories) { idx, category ->
                    val isSelected = idx == categoryIndex
                    val chipColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                    val onChipColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                      else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(chipColor)
                            .clickable { onCategoryChange(idx) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = onChipColor,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
            ) {
                items(currentEmojis) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { onEmojiClick(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji,
                            fontSize = 24.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Agent 头像
 *
 * 微信/QQ 式：所有消息（历史 + 新）统一显示 Agent 的"当前头像"。
 * 当前头像 = 用户在头像管理页点「设为当前」选中的那个；
 * 未选中或指向的头像文件缺失时，回退到第一个 file 非空的头像。
 * 用户切换头像后，整个聊天列表会立即统一更新。
 */
@Composable
private fun AgentAvatar(
    modifier: Modifier = Modifier,
    circular: Boolean = false
) {
    val config by ServiceLocator.agentConfigProvider.config.collectAsState()
    // 统一用当前头像（用户在头像管理页选中的那个），不再按消息状态/文本动态匹配
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
    val shape = if (circular) CircleShape else RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(AiPrimary, AiPrimaryHover)
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
                config.agent.name.firstOrNull()?.toString() ?: "雅",
                color = Color.White,
                fontSize = if (circular) 16.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 用户头像（右侧）
 *
 * 优先加载用户自定义头像；未设置时回退到 primaryContainer 渐变 + 首字昵称。
 */
@Composable
private fun UserAvatar(modifier: Modifier = Modifier) {
    val prefs = ServiceLocator.userPreferences
    val nickname = remember { prefs.userNickname }
    val avatarPath = remember { prefs.userAvatarPath }
    val bitmap = remember(avatarPath) {
        if (avatarPath.isNotBlank()) {
            runCatching { BitmapFactory.decodeFile(avatarPath) }.getOrNull()
        } else null
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(AiPrimary, AiPrimaryHover)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "用户头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = nickname.firstOrNull()?.toString() ?: "我",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
