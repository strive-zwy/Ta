package com.agent.ta.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.data.model.BehaviorConfig
import com.agent.ta.data.model.EmojiBehavior
import com.agent.ta.data.model.ReplyDelay
import com.agent.ta.data.model.StateInitiate
import com.agent.ta.data.model.StateInitiateCandidate
import com.agent.ta.data.model.TimeWindow
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 状态码 → 中文名映射（与设计稿 chat-agent-behavior.html 一致）
 * key 与 AgentState.id 对齐（小写），与配置数据一致
 * 顺序：开心/无聊/工作/游戏/睡觉/洗澡
 */
private val STATE_LABELS: LinkedHashMap<String, String> = linkedMapOf(
    "happy" to "开心",
    "bored" to "无聊",
    "work" to "工作",
    "game" to "游戏",
    "sleep" to "睡觉",
    "bath" to "洗澡"
)

/**
 * 行为配置页面（Vibe Chat 风格，参考设计稿 chat-agent-behavior.html）
 *
 * 编辑 AgentConfig.behavior 字段：
 * - 回复延迟 replyDelaySec（每个状态：延迟回复 min/max 或 暂不回复 Defer）
 * - 状态导演提示 stateDirectorHints（每个状态多行文本）
 * - Emoji 配置（enabled + preferredEmojis + maxPerMessage + 各状态发送频率 frequencyPerState）
 * - 各状态主动发起 perStateInitiate（可折叠卡片：启用/触发间隔/触发概率/冷却/时间窗/候选消息）
 *
 * 底部保存按钮调用 AgentConfigEditor.update() 写入 behavior 字段。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentBehaviorScreen(onBack: () -> Unit) {
    val editor = ServiceLocator.agentConfigEditor
    val behavior = remember { editor.get().behavior }

    // 合并 STATE_LABELS 的固定状态与配置中已有的状态 key
    val allStates = remember {
        linkedSetOf<String>().apply {
            addAll(STATE_LABELS.keys)
            addAll(behavior.replyDelaySec.keys)
            addAll(behavior.perStateInitiate.keys)
        }
    }

    // ===== 回复延迟：3 档位（秒回 / 正常 / 忙碌），用 RangeSlider 编辑 =====
    // 状态 → 档位映射：neutral→正常, happy/bored→秒回, work/game→忙碌, sleep/bath 走 pending 队列不配
    // 读取时从代表状态取值；保存时把档位值映射回各状态写入 replyDelaySec
    fun ReplyDelay?.rangeOr(defaultMin: Int, defaultMax: Int): Pair<Float, Float> =
        (this as? ReplyDelay.Range)?.let { it.min.toFloat() to it.max.toFloat() } ?: (defaultMin.toFloat() to defaultMax.toFloat())

    val (instantMin, instantMax) = remember {
        behavior.replyDelaySec["happy"].rangeOr(1, 3)   // 秒回档：开心/无聊
    }
    var replyInstantMin by remember { mutableStateOf(instantMin) }
    var replyInstantMax by remember { mutableStateOf(instantMax) }

    val (normalMin, normalMax) = remember {
        behavior.replyDelaySec["neutral"].rangeOr(3, 8)  // 正常档：默认
    }
    var replyNormalMin by remember { mutableStateOf(normalMin) }
    var replyNormalMax by remember { mutableStateOf(normalMax) }

    val (busyMin, busyMax) = remember {
        behavior.replyDelaySec["work"].rangeOr(30, 120)  // 忙碌档：工作/游戏
    }
    var replyBusyMin by remember { mutableStateOf(busyMin) }
    var replyBusyMax by remember { mutableStateOf(busyMax) }

    // ===== 状态导演提示 =====
    val stateHints = remember {
        mutableStateMapOf<String, String>().apply {
            allStates.forEach { state ->
                this[state] = behavior.stateDirectorHints[state] ?: ""
            }
        }
    }

    // ===== Emoji 配置 =====
    var emojiEnabled by remember { mutableStateOf(behavior.emoji.enabled) }
    var maxPerMessage by remember { mutableStateOf(behavior.emoji.maxPerMessage.toString()) }
    val preferredEmojis = remember { behavior.emoji.preferredEmojis.toMutableStateList() }
    var emojiInput by remember { mutableStateOf("") }

    // Emoji 各状态发送频率（0f-1f）
    val emojiFrequency = remember {
        mutableStateMapOf<String, Float>().apply {
            allStates.forEach { state ->
                this[state] = behavior.emoji.frequencyPerState[state] ?: 0f
            }
        }
    }

    // ===== 各状态主动发起 =====
    val initiateEnabled = remember {
        mutableStateMapOf<String, Boolean>().apply {
            allStates.forEach { state -> this[state] = behavior.perStateInitiate[state]?.enabled ?: false }
        }
    }
    val initiateInterval = remember {
        mutableStateMapOf<String, String>().apply {
            allStates.forEach { state ->
                this[state] = (behavior.perStateInitiate[state]?.intervalMin ?: 60).toString()
            }
        }
    }
    val initiateProbability = remember {
        mutableStateMapOf<String, String>().apply {
            allStates.forEach { state ->
                this[state] = (behavior.perStateInitiate[state]?.probability ?: 0.2f).toString()
            }
        }
    }
    val initiateCooldown = remember {
        mutableStateMapOf<String, String>().apply {
            allStates.forEach { state ->
                this[state] = (behavior.perStateInitiate[state]?.cooldownMin ?: 30).toString()
            }
        }
    }
    val initiateTimeStart = remember {
        mutableStateMapOf<String, String>().apply {
            allStates.forEach { state ->
                this[state] = behavior.perStateInitiate[state]?.timeWindow?.start ?: ""
            }
        }
    }
    val initiateTimeEnd = remember {
        mutableStateMapOf<String, String>().apply {
            allStates.forEach { state ->
                this[state] = behavior.perStateInitiate[state]?.timeWindow?.end ?: ""
            }
        }
    }
    // 候选消息：每个状态一个 SnapshotStateList<String>（仅存 text）
    val initiateCandidates = remember {
        mutableStateMapOf<String, SnapshotStateList<String>>().apply {
            allStates.forEach { state ->
                this[state] = mutableStateListOf<String>().apply {
                    addAll(behavior.perStateInitiate[state]?.candidates?.map { it.text } ?: emptyList())
                }
            }
        }
    }
    val initiateCandidateInput = remember { mutableStateMapOf<String, String>() }
    // 折叠展开状态
    val initiateExpanded = remember { mutableStateMapOf<String, Boolean>() }

    var justSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBg)
    ) {
        VibeTopBar(title = "行为配置", onBack = onBack)
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ===== 卡片1：回复延迟（3 档位） =====
                ConfigCard(title = "回复延迟（秒）") {
                    Text(
                        text = "按忙碌程度分 3 档，Agent 根据当前状态自动选用；睡觉/洗澡走待回复队列，不在此配置。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = AiTextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    ReplyDelayTierRow(
                        tierLabel = "秒回档",
                        tierHint = "开心 / 无聊",
                        minValue = replyInstantMin,
                        maxValue = replyInstantMax,
                        onMinChange = { replyInstantMin = it },
                        onMaxChange = { replyInstantMax = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    ReplyDelayTierRow(
                        tierLabel = "正常档",
                        tierHint = "默认",
                        minValue = replyNormalMin,
                        maxValue = replyNormalMax,
                        onMinChange = { replyNormalMin = it },
                        onMaxChange = { replyNormalMax = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    ReplyDelayTierRow(
                        tierLabel = "忙碌档",
                        tierHint = "工作 / 游戏",
                        minValue = replyBusyMin,
                        maxValue = replyBusyMax,
                        onMinChange = { replyBusyMin = it },
                        onMaxChange = { replyBusyMax = it }
                    )
                }

                // ===== 卡片2：状态导演提示 =====
                ConfigCard(title = "状态导演提示") {
                    allStates.forEachIndexed { index, state ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        StateHintRow(
                            stateKey = state,
                            stateLabel = STATE_LABELS[state] ?: state,
                            value = stateHints[state] ?: "",
                            onValueChange = { stateHints[state] = it }
                        )
                    }
                }

                // ===== 卡片3：Emoji 配置 =====
                ConfigCard(title = "Emoji 配置") {
                    // 启用开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "启用 Emoji",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = AiTextPrimary
                            )
                            Text(
                                text = "Agent 自主决策发送",
                                fontSize = 12.sp,
                                color = AiTextSecondary
                            )
                        }
                        Switch(
                            checked = emojiEnabled,
                            onCheckedChange = { emojiEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = AiPrimary
                            )
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    VibeTextField(
                        value = maxPerMessage,
                        onValueChange = { maxPerMessage = it.filter { c -> c.isDigit() } },
                        label = "单条上限",
                        placeholder = "如：2",
                        singleLine = true
                    )

                    Spacer(Modifier.height(10.dp))
                    SectionLabel("首选 Emoji 白名单")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        preferredEmojis.forEach { emoji ->
                            VibeChip(text = emoji, onDelete = { preferredEmojis.remove(emoji) }, chipType = VibeChipType.EMOJI)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    VibeTextField(
                        value = emojiInput,
                        onValueChange = { emojiInput = it },
                        label = "添加 Emoji",
                        placeholder = "如：😊 / ❤️",
                        singleLine = true,
                        trailingIcon = {
                            BehaviorAddIconButton(enabled = emojiInput.isNotBlank()) {
                                if (emojiInput.isNotBlank()) {
                                    preferredEmojis.add(emojiInput.trim())
                                    emojiInput = ""
                                }
                            }
                        }
                    )

                    // 各状态发送频率
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("各状态发送频率")
                    allStates.forEachIndexed { index, state ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        EmojiFrequencyRow(
                            stateKey = state,
                            stateLabel = STATE_LABELS[state] ?: state,
                            frequency = emojiFrequency[state] ?: 0f,
                            onFrequencyChange = { emojiFrequency[state] = it }
                        )
                    }
                }

                // ===== 卡片4：各状态主动发起 =====
                ConfigCard(title = "各状态主动发起") {
                    Text(
                        text = "每个状态可独立配置主动发起",
                        fontSize = 12.sp,
                        color = AiTextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    allStates.forEachIndexed { index, state ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        InitiateExpandableCard(
                            stateKey = state,
                            stateLabel = STATE_LABELS[state] ?: state,
                            expanded = initiateExpanded[state] ?: false,
                            onExpandedChange = { initiateExpanded[state] = it },
                            enabled = initiateEnabled[state] ?: false,
                            intervalMin = initiateInterval[state] ?: "",
                            probability = initiateProbability[state] ?: "",
                            cooldown = initiateCooldown[state] ?: "",
                            timeStart = initiateTimeStart[state] ?: "",
                            timeEnd = initiateTimeEnd[state] ?: "",
                            candidates = initiateCandidates[state] ?: mutableStateListOf(),
                            candidateInput = initiateCandidateInput[state] ?: "",
                            onEnabledChange = { initiateEnabled[state] = it },
                            onIntervalChange = { initiateInterval[state] = it },
                            onProbabilityChange = { initiateProbability[state] = it },
                            onCooldownChange = { initiateCooldown[state] = it },
                            onTimeStartChange = { initiateTimeStart[state] = it },
                            onTimeEndChange = { initiateTimeEnd[state] = it },
                            onCandidateInputChange = { initiateCandidateInput[state] = it },
                            onCandidateAdd = {
                                val text = initiateCandidateInput[state] ?: ""
                                if (text.isNotBlank()) {
                                    initiateCandidates[state]?.add(text.trim())
                                    initiateCandidateInput[state] = ""
                                }
                            },
                            onCandidateDelete = { idx ->
                                initiateCandidates[state]?.removeAt(idx)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(80.dp))
            }
        }

        // ===== 底部保存按钮 =====
        BehaviorSaveButton(
            modifier = Modifier.align(Alignment.BottomCenter),
            justSaved = justSaved,
            onClick = {
                // 回复延迟：3 档位映射到各状态
                // 秒回档 → happy / bored；正常档 → neutral；忙碌档 → work / game
                // sleep / bath 走 pending 队列，保留 Defer
                val instantRange = ReplyDelay.Range(replyInstantMin.toInt(), replyInstantMax.toInt())
                val normalRange = ReplyDelay.Range(replyNormalMin.toInt(), replyNormalMax.toInt())
                val busyRange = ReplyDelay.Range(replyBusyMin.toInt(), replyBusyMax.toInt())
                val newReplyDelay = mapOf(
                    "happy" to instantRange,
                    "bored" to instantRange,
                    "neutral" to normalRange,
                    "work" to busyRange,
                    "game" to busyRange,
                    "sleep" to ReplyDelay.Defer,
                    "bath" to ReplyDelay.Defer
                )
                // Emoji：包含各状态频率
                val newEmoji = EmojiBehavior(
                    enabled = emojiEnabled,
                    frequencyPerState = buildMap {
                        allStates.forEach { state ->
                            put(state, emojiFrequency[state] ?: 0f)
                        }
                    },
                    preferredEmojis = preferredEmojis.toList(),
                    maxPerMessage = maxPerMessage.toIntOrNull() ?: 1
                )
                // 主动发起：保留已有候选的 emotion/moodAfter/weight，仅更新 text
                val newInitiate = buildMap<String, StateInitiate> {
                    allStates.forEach { state ->
                        val existing = behavior.perStateInitiate[state]
                        val existingCandidates = existing?.candidates ?: emptyList()
                        val newCandidates = initiateCandidates[state]?.mapIndexed { idx, text ->
                            val existingCandidate = existingCandidates.getOrNull(idx)
                            StateInitiateCandidate(
                                text = text,
                                emotion = existingCandidate?.emotion ?: "",
                                moodAfter = existingCandidate?.moodAfter ?: "",
                                weight = existingCandidate?.weight ?: 1
                            )
                        } ?: emptyList()
                        put(
                            state,
                            (existing ?: StateInitiate()).copy(
                                enabled = initiateEnabled[state] ?: false,
                                intervalMin = initiateInterval[state]?.toIntOrNull() ?: 60,
                                probability = initiateProbability[state]?.toFloatOrNull() ?: 0.2f,
                                cooldownMin = initiateCooldown[state]?.toIntOrNull() ?: 30,
                                timeWindow = TimeWindow(
                                    start = initiateTimeStart[state] ?: "",
                                    end = initiateTimeEnd[state] ?: ""
                                ),
                                candidates = newCandidates
                            )
                        )
                    }
                }
                val newBehavior = BehaviorConfig(
                    replyDelaySec = newReplyDelay,
                    boredInitiate = behavior.boredInitiate,
                    stateDirectorHints = stateHints.toMap(),
                    emoji = newEmoji,
                    perStateInitiate = newInitiate,
                    typingIndicatorDuration = behavior.typingIndicatorDuration,
                    messageLengthHints = behavior.messageLengthHints
                )
                scope.launch {
                    editor.update { config ->
                        config.copy(behavior = newBehavior)
                    }
                    justSaved = true
                    delay(1000)
                    justSaved = false
                }
            }
        )
        }
    }
}

/**
 * 回复延迟档位行：档位名称 + 适用状态提示 + RangeSlider + 数值显示
 *
 * 用于 3 档位配置（秒回档 / 正常档 / 忙碌档）。
 */
@Composable
private fun ReplyDelayTierRow(
    tierLabel: String,
    tierHint: String,
    minValue: Float,
    maxValue: Float,
    onMinChange: (Float) -> Unit,
    onMaxChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 第一行：档位名称 + 适用状态提示
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = tierLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AiTextPrimary
            )
            Text(
                text = "· $tierHint",
                fontSize = 12.sp,
                color = AiTextTertiary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${minValue.toInt()}-${maxValue.toInt()}s",
                fontSize = 12.sp,
                color = AiPrimary,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(4.dp))
        // 第二行：RangeSlider（min ~ max）
        RangeSlider(
            value = minValue..maxValue,
            onValueChange = { range ->
                onMinChange(range.start)
                onMaxChange(range.endInclusive)
            },
            valueRange = 0f..300f,
            steps = 29,  // 10 秒一档：300 / 10 - 1 = 29
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                activeTrackColor = AiPrimary,
                inactiveTrackColor = AiBorder,
                thumbColor = AiPrimary
            )
        )
    }
}

/** 状态导演提示行：状态标签 + 多行文本框 */
@Composable
private fun StateHintRow(
    stateKey: String,
    stateLabel: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StateChip(text = stateLabel, stateKey = stateKey)
        Spacer(Modifier.height(4.dp))
        VibeTextField(
            value = value,
            onValueChange = onValueChange,
            label = "提示文本",
            placeholder = "该状态下的提示文本",
            singleLine = false
        )
    }
}

/** Emoji 各状态发送频率行：状态标签 + Slider(0-1) + 数值显示 */
@Composable
private fun EmojiFrequencyRow(
    stateKey: String,
    stateLabel: String,
    frequency: Float,
    onFrequencyChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StateChip(text = stateLabel, stateKey = stateKey)
        Slider(
            value = frequency,
            onValueChange = onFrequencyChange,
            valueRange = 0f..1f,
            steps = 19,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = AiPrimary,
                activeTrackColor = AiPrimary,
                inactiveTrackColor = AiBorder
            )
        )
        Text(
            text = "%.2f".format(frequency),
            fontSize = 12.sp,
            color = AiPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp)
        )
    }
}

/**
 * 各状态主动发起：可折叠卡片
 * 展开后显示 6 项：启用 / 触发间隔 / 触发概率 / 冷却 / 时间窗 / 候选消息
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InitiateExpandableCard(
    stateKey: String,
    stateLabel: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    intervalMin: String,
    probability: String,
    cooldown: String,
    timeStart: String,
    timeEnd: String,
    candidates: SnapshotStateList<String>,
    candidateInput: String,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (String) -> Unit,
    onProbabilityChange: (String) -> Unit,
    onCooldownChange: (String) -> Unit,
    onTimeStartChange: (String) -> Unit,
    onTimeEndChange: (String) -> Unit,
    onCandidateInputChange: (String) -> Unit,
    onCandidateAdd: () -> Unit,
    onCandidateDelete: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 折叠头部（点击展开/收起）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StateChip(text = stateLabel, stateKey = stateKey)
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = AiTextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
        // 展开内容
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AiInputBg)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 启用
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "启用",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AiTextSecondary
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = AiPrimary
                        )
                    )
                }
                // 触发间隔（分钟）
                VibeTextField(
                    value = intervalMin,
                    onValueChange = { onIntervalChange(it.filter { c -> c.isDigit() }) },
                    label = "触发间隔（分钟）",
                    placeholder = "如：60",
                    singleLine = true
                )
                // 触发概率
                VibeTextField(
                    value = probability,
                    onValueChange = { onProbabilityChange(it.filter { c -> c.isDigit() || c == '.' }) },
                    label = "触发概率",
                    placeholder = "如：0.3",
                    singleLine = true
                )
                // 冷却（分钟）
                VibeTextField(
                    value = cooldown,
                    onValueChange = { onCooldownChange(it.filter { c -> c.isDigit() }) },
                    label = "冷却（分钟）",
                    placeholder = "如：30",
                    singleLine = true
                )
                // 时间窗
                SectionLabel("时间窗")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        VibeTextField(
                            value = timeStart,
                            onValueChange = onTimeStartChange,
                            label = "开始",
                            placeholder = "09:00",
                            singleLine = true
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        VibeTextField(
                            value = timeEnd,
                            onValueChange = onTimeEndChange,
                            label = "结束",
                            placeholder = "23:00",
                            singleLine = true
                        )
                    }
                }
                // 候选消息
                SectionLabel("候选消息")
                if (candidates.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        candidates.forEachIndexed { idx, text ->
                            VibeChip(
                                text = text.ifBlank { "(空)" },
                                onDelete = { onCandidateDelete(idx) },
                                chipType = VibeChipType.NEUTRAL
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                VibeTextField(
                    value = candidateInput,
                    onValueChange = onCandidateInputChange,
                    label = "添加候选",
                    placeholder = "输入候选消息",
                    singleLine = true,
                    trailingIcon = {
                        BehaviorAddIconButton(enabled = candidateInput.isNotBlank()) {
                            onCandidateAdd()
                        }
                    }
                )
            }
        }
    }
}

/**
 * 状态彩色小标签（按状态分色，对齐设计稿 chat-agent-behavior.html）
 *
 * 各状态配色：
 * - happy（开心）→ chip-amber
 * - work（工作）→ chip-primary（青绿）
 * - sleep（睡觉）→ chip-muted（灰色）
 * - bath（洗澡）→ chip-pink
 * - game（游戏）→ chip-green
 * - bored（无聊）→ chip-indigo
 * - 默认/其他（含 neutral）→ chip-primary
 */
@Composable
private fun StateChip(text: String, stateKey: String = "") {
    val (bgColor, fgColor) = when (stateKey) {
        "happy" -> AiChipAmberBg to AiChipAmberFg
        "work"  -> AiChipPrimaryBg to AiChipPrimaryFg
        "sleep" -> AiChipMutedBg to AiChipMutedFg
        "bath"  -> AiChipPinkBg to AiChipPinkFg
        "game"  -> AiChipGreenBg to AiChipGreenFg
        "bored" -> AiChipIndigoBg to AiChipIndigoFg
        else    -> AiChipPrimaryBg to AiChipPrimaryFg
    }
    Box(
        modifier = Modifier
            .width(60.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = fgColor,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 输入框尾部圆形添加按钮 */
@Composable
private fun BehaviorAddIconButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) AiPrimary.copy(alpha = 0.12f) else AiBorder)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "添加",
            tint = AiPrimary,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** AI Studio 保存按钮（委托共享 AiSaveButton） */
@Composable
private fun BehaviorSaveButton(modifier: Modifier = Modifier, justSaved: Boolean, onClick: () -> Unit) {
    AiSaveButton(justSaved = justSaved, onClick = onClick, modifier = modifier)
}
