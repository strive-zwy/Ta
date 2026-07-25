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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.data.model.ExampleDialogue
import com.agent.ta.data.model.Persona
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 示例对话 mood 选项（value → 显示标签），与 AgentState 4 状态对齐 */
private val MOOD_OPTIONS = listOf(
    "default" to "默认",
    "normal" to "正常",
    "busy" to "忙碌",
    "idle" to "空闲",
    "unavailable" to "无法回复"
)

/** 说话风格下拉选项 */
private val VOCABULARY_OPTIONS = listOf(
    "口语化" to "口语化",
    "普通" to "普通",
    "书面" to "书面",
    "古风" to "古风"
)
private val PACE_OPTIONS = listOf(
    "慢" to "慢",
    "中" to "中",
    "快" to "快",
    "多变" to "多变"
)
private val SENTENCE_LENGTH_OPTIONS = listOf(
    "短句" to "短句",
    "中等" to "中等",
    "长句" to "长句",
    "混合" to "混合"
)

/**
 * 人格设定页面（Vibe Chat 风格，参考设计稿 chat-agent-persona.html）
 *
 * 编辑 AgentConfig.agent.persona 的全部字段：
 * - 性格标签 / 口头禅（VibeChip 展示 + 输入添加 + ×删除）
 * - 说话风格（tone 文本 + pace/sentence_length/vocabulary_level 下拉 + filler_words 文本，双列布局）
 * - 兴趣话题 / 禁忌话题 / 初始共享记忆（VibeChip 展示，记忆支持单条删除）
 * - 关系阶段提示（可增删改，阶段名可编辑）
 * - 示例对话（可增删改，场景 + mood + user/agent 内容）
 *
 * 底部保存按钮调用 AgentConfigEditor.update() 写入 persona 字段。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentPersonaScreen(onBack: () -> Unit) {
    val editor = ServiceLocator.agentConfigEditor
    val persona = remember { editor.get().agent.persona }

    // 性格标签
    val personality = remember { persona.personality.toMutableStateList() }
    // 口头禅
    val catchphrases = remember { persona.catchphrases.toMutableStateList() }

    // 说话风格详情
    val detail = remember { persona.speakingStyleDetail }
    var tone by remember { mutableStateOf(detail["tone"] ?: "") }
    var pace by remember { mutableStateOf(detail["pace"] ?: "") }
    var sentenceLength by remember { mutableStateOf(detail["sentence_length"] ?: "") }
    var vocabularyLevel by remember { mutableStateOf(detail["vocabulary_level"] ?: "") }
    var fillerWords by remember { mutableStateOf(detail["filler_words"] ?: "") }

    // 兴趣
    val interests = remember { persona.interests.toMutableStateList() }
    // 禁忌
    val taboos = remember { persona.taboos.toMutableStateList() }
    // 初始共享记忆
    val memorySeeds = remember { persona.memorySeeds.toMutableStateList() }
    // 关系阶段提示（可增删改）
    val stageEntries = remember {
        persona.conversationStageHints.entries
            .map { StageEntry(it.key, it.value) }
            .toMutableStateList()
    }
    // 示例对话（可增删改）
    val dialogues = remember {
        persona.exampleDialogues.map { DialogueState(it) }.toMutableStateList()
    }

    // 各 chip 输入框
    var personalityInput by remember { mutableStateOf("") }
    var catchphraseInput by remember { mutableStateOf("") }
    var interestInput by remember { mutableStateOf("") }
    var tabooInput by remember { mutableStateOf("") }

    var justSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBg)
    ) {
        VibeTopBar(title = "人格设定", onBack = onBack)
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
                // ===== 卡片1：性格与口头禅 =====
                ConfigCard(title = "性格与口头禅") {
                    SectionLabel("性格标签")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        personality.forEach { tag ->
                            VibeChip(text = tag, onDelete = { personality.remove(tag) }, chipType = VibeChipType.PERSONALITY)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    VibeTextField(
                        value = personalityInput,
                        onValueChange = { personalityInput = it },
                        label = "添加性格标签",
                        placeholder = "如：温柔 / 好奇心强 / 略带毒舌",
                        singleLine = true,
                        trailingIcon = {
                            PersonaAddIconButton(enabled = personalityInput.isNotBlank()) {
                                if (personalityInput.isNotBlank()) {
                                    personality.add(personalityInput.trim())
                                    personalityInput = ""
                                }
                            }
                        }
                    )

                    SectionLabel("口头禅")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        catchphrases.forEach { phrase ->
                            VibeChip(text = phrase, onDelete = { catchphrases.remove(phrase) }, chipType = VibeChipType.CATCHPHRASE)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    VibeTextField(
                        value = catchphraseInput,
                        onValueChange = { catchphraseInput = it },
                        label = "添加口头禅",
                        placeholder = "如：哼 / 才不是呢",
                        singleLine = true,
                        trailingIcon = {
                            PersonaAddIconButton(enabled = catchphraseInput.isNotBlank()) {
                                if (catchphraseInput.isNotBlank()) {
                                    catchphrases.add(catchphraseInput.trim())
                                    catchphraseInput = ""
                                }
                            }
                        }
                    )
                }

                // ===== 卡片2：说话风格（单列布局 + 下拉选择） =====
                ConfigCard(title = "说话风格") {
                    VibeTextField(
                        value = tone,
                        onValueChange = { tone = it },
                        label = "语调",
                        placeholder = "如：温柔/活泼/冷淡",
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeDropdown(
                        label = "用词层级",
                        options = VOCABULARY_OPTIONS,
                        selectedValue = vocabularyLevel,
                        onSelect = { vocabularyLevel = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeDropdown(
                        label = "语速",
                        options = PACE_OPTIONS,
                        selectedValue = pace,
                        onSelect = { pace = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeDropdown(
                        label = "句长",
                        options = SENTENCE_LENGTH_OPTIONS,
                        selectedValue = sentenceLength,
                        onSelect = { sentenceLength = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    // 口头缀词 / 语气词：单列
                    VibeTextField(
                        value = fillerWords,
                        onValueChange = { fillerWords = it },
                        label = "口头缀词 / 语气词",
                        placeholder = "如：嗯... / 哼",
                        singleLine = true
                    )
                }

                // ===== 卡片3：兴趣与禁忌 =====
                ConfigCard(title = "兴趣与禁忌") {
                    SectionLabel("兴趣 / 可聊话题")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        interests.forEach { interest ->
                            VibeChip(text = interest, onDelete = { interests.remove(interest) }, chipType = VibeChipType.INTEREST)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    VibeTextField(
                        value = interestInput,
                        onValueChange = { interestInput = it },
                        label = "添加兴趣",
                        placeholder = "如：电影 / 美食 / 猫咪",
                        singleLine = true,
                        trailingIcon = {
                            PersonaAddIconButton(enabled = interestInput.isNotBlank()) {
                                if (interestInput.isNotBlank()) {
                                    interests.add(interestInput.trim())
                                    interestInput = ""
                                }
                            }
                        }
                    )

                    SectionLabel("禁忌话题")
                    Text(
                        text = "Agent 应主动回避",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        taboos.forEach { taboo ->
                            VibeChip(text = taboo, onDelete = { taboos.remove(taboo) }, chipType = VibeChipType.TABOO)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    VibeTextField(
                        value = tabooInput,
                        onValueChange = { tabooInput = it },
                        label = "添加禁忌话题",
                        placeholder = "如：政治话题",
                        singleLine = true,
                        trailingIcon = {
                            PersonaAddIconButton(enabled = tabooInput.isNotBlank()) {
                                if (tabooInput.isNotBlank()) {
                                    taboos.add(tabooInput.trim())
                                    tabooInput = ""
                                }
                            }
                        }
                    )
                }

                // ===== 卡片4：初始共享记忆（每条带删除按钮） =====
                ConfigCard(title = "初始共享记忆") {
                    Text(
                        text = "让 Agent 拥有过去可引用",
                        fontSize = 12.sp,
                        color = AiTextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    memorySeeds.forEachIndexed { index, seed ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                VibeTextField(
                                    value = seed,
                                    onValueChange = { memorySeeds[index] = it },
                                    label = "记忆 ${index + 1}",
                                    placeholder = "如：上周我们一起去看了《你的名字》"
                                )
                            }
                            DeleteIconButton(
                                onClick = { memorySeeds.removeAt(index) },
                                modifier = Modifier.padding(top = 20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    AddRowButton(text = "添加记忆") {
                        memorySeeds.add("")
                    }
                }

                // ===== 卡片5：关系阶段提示（可增删改） =====
                ConfigCard(title = "关系阶段提示") {
                    Text(
                        text = "不同关系阶段下 Agent 的语气/亲密度提示",
                        fontSize = 12.sp,
                        color = AiTextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    stageEntries.forEachIndexed { index, entry ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        StageHintEditor(
                            entry = entry,
                            onDelete = { stageEntries.removeAt(index) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    AddRowButton(text = "添加阶段") {
                        stageEntries.add(StageEntry("", ""))
                    }
                }

                // ===== 卡片6：示例对话（可增删改） =====
                ConfigCard(title = "示例对话") {
                    Text(
                        text = "多轮 few-shot 引导 LLM 复刻人设",
                        fontSize = 12.sp,
                        color = AiTextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    dialogues.forEachIndexed { index, state ->
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        ExampleDialogueEditor(
                            state = state,
                            onDelete = { dialogues.removeAt(index) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    AddRowButton(text = "添加示例对话") {
                        dialogues.add(DialogueState(ExampleDialogue()))
                    }
                }

                Spacer(Modifier.height(80.dp))
            }
        }

        // ===== 底部保存按钮（Vibe 渐变） =====
        PersonaSaveButton(
            modifier = Modifier.align(Alignment.BottomCenter),
            justSaved = justSaved,
            onClick = {
                val newDetail = buildMap {
                    if (tone.isNotBlank()) put("tone", tone)
                    if (pace.isNotBlank()) put("pace", pace)
                    if (sentenceLength.isNotBlank()) put("sentence_length", sentenceLength)
                    if (vocabularyLevel.isNotBlank()) put("vocabulary_level", vocabularyLevel)
                    if (fillerWords.isNotBlank()) put("filler_words", fillerWords)
                }
                val newPersona = Persona(
                    background = persona.background,
                    personality = personality.toList(),
                    speakingStyle = persona.speakingStyle,
                    speakingStyleDetail = newDetail,
                    exampleDialogues = dialogues.map { it.toExampleDialogue() },
                    directorRoleTemplate = persona.directorRoleTemplate,
                    voiceDirectorTemplate = persona.voiceDirectorTemplate,
                    systemPromptTemplate = persona.systemPromptTemplate,
                    catchphrases = catchphrases.toList(),
                    nicknameForUser = persona.nicknameForUser,
                    selfNickname = persona.selfNickname,
                    relationshipToUser = persona.relationshipToUser,
                    taboos = taboos.toList(),
                    interests = interests.toList(),
                    memorySeeds = memorySeeds.toList(),
                    conversationStageHints = stageEntries
                        .filter { it.name.isNotBlank() }
                        .associate { it.name to it.hint }
                )
                scope.launch {
                    editor.update { config ->
                        config.copy(agent = config.agent.copy(persona = newPersona))
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

// ============================================================
//  状态持有类
// ============================================================

/** 示例对话编辑状态（mood 编码到 scenario 字段前缀："[happy] 场景描述"） */
private class DialogueState(initial: ExampleDialogue) {
    var mood: String by mutableStateOf("")
    var scenarioText: String by mutableStateOf("")
    var user: String by mutableStateOf(initial.user)
    var agent: String by mutableStateOf(initial.agent)

    init {
        val parsed = parseMoodAndScenario(initial.scenario)
        mood = parsed.first
        scenarioText = parsed.second
    }

    fun toExampleDialogue(): ExampleDialogue {
        val finalScenario = when {
            mood == "default" || mood.isBlank() -> scenarioText
            scenarioText.isBlank() -> "[$mood]"
            else -> "[$mood] $scenarioText"
        }
        return ExampleDialogue(user = user, agent = agent, scenario = finalScenario)
    }
}

/** 关系阶段条目（阶段名 + 提示文本，均可编辑） */
private class StageEntry(initialName: String, initialHint: String) {
    var name: String by mutableStateOf(initialName)
    var hint: String by mutableStateOf(initialHint)
}

/** 从 scenario 字段解析 mood 前缀和场景文本 */
private fun parseMoodAndScenario(scenario: String): Pair<String, String> {
    val regex = Regex("^\\[(\\w+)\\]\\s*(.*)")
    val match = regex.find(scenario)
    return if (match != null) {
        match.groupValues[1] to match.groupValues[2]
    } else {
        "default" to scenario
    }
}

// ============================================================
//  自定义 UI 组件
// ============================================================

/** Vibe 风格下拉选择器（label + 带箭头的 select 盒 + DropdownMenu） */
@Composable
private fun VibeDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified
) {
    var expanded by remember { mutableStateOf(false) }
    val actualColor = if (containerColor == Color.Unspecified)
        AiInputBg
    else
        containerColor
    val selectedLabel = options.find { it.first == selectedValue }?.second
        ?: selectedValue.ifBlank { "请选择" }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = AiTextSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(actualColor)
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedLabel,
                        fontSize = 14.sp,
                        color = if (selectedValue.isBlank())
                            AiTextTertiary
                        else
                            AiTextPrimary
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = AiTextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, displayLabel) ->
                    DropdownMenuItem(
                        text = { Text(displayLabel, color = AiTextPrimary) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** 无标签的内联输入盒（用于 Row 中 weight/width 布局） */
@Composable
private fun VibeInlineInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    containerColor: Color = Color.Unspecified
) {
    val actualColor = if (containerColor == Color.Unspecified)
        AiInputBg
    else
        containerColor

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(actualColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty() && placeholder.isNotBlank()) {
            Text(
                text = placeholder,
                fontSize = 14.sp,
                color = AiTextTertiary
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = AiTextPrimary
            ),
            cursorBrush = SolidColor(AiPrimary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 圆形红色删除按钮 */
@Composable
private fun DeleteIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(50))
            .background(AiChipRedBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "删除",
            tint = AiChipRedFg,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** 关系阶段提示编辑器：可编辑阶段名 + 提示文本框 + 删除按钮 */
@Composable
private fun StageHintEditor(
    entry: StageEntry,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 第一行：阶段名 + 删除按钮（AiChipPrimaryBg 浅青绿强调关系）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VibeInlineInput(
                value = entry.name,
                onValueChange = { entry.name = it },
                placeholder = "阶段名",
                containerColor = AiChipPrimaryBg,
                modifier = Modifier.weight(1f)
            )
            DeleteIconButton(onClick = onDelete)
        }
        // 第二行：提示文本框（AiInputBg 浅灰，次级提示）
        VibeInlineInput(
            value = entry.hint,
            onValueChange = { entry.hint = it },
            placeholder = "该阶段的语气/亲密度提示",
            singleLine = false,
            containerColor = AiInputBg,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 示例对话编辑器：场景 + mood + user/agent 内容，全部可编辑 */
@Composable
private fun ExampleDialogueEditor(
    state: DialogueState,
    onDelete: () -> Unit
) {
    val inputBg = AiCard

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AiInputBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 头部：场景输入 + mood 下拉 + 删除对话
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VibeInlineInput(
                value = state.scenarioText,
                onValueChange = { state.scenarioText = it },
                placeholder = "场景如：用户深夜找她",
                containerColor = inputBg,
                modifier = Modifier.weight(1f)
            )
            VibeDropdown(
                label = "",
                options = MOOD_OPTIONS,
                selectedValue = state.mood,
                onSelect = { state.mood = it },
                containerColor = inputBg,
                modifier = Modifier.width(100.dp)
            )
            DeleteIconButton(onClick = onDelete)
        }

        // 用户消息轮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(inputBg)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "用户",
                    fontSize = 13.sp,
                    color = AiTextSecondary
                )
            }
            VibeInlineInput(
                value = state.user,
                onValueChange = { state.user = it },
                placeholder = "用户消息",
                containerColor = inputBg,
                modifier = Modifier.weight(1f)
            )
            DeleteIconButton(onClick = { state.user = "" })
        }

        // Agent 回复轮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(inputBg)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "助手",
                    fontSize = 13.sp,
                    color = AiTextSecondary
                )
            }
            VibeInlineInput(
                value = state.agent,
                onValueChange = { state.agent = it },
                placeholder = "Agent 回复",
                containerColor = inputBg,
                modifier = Modifier.weight(1f)
            )
            DeleteIconButton(onClick = { state.agent = "" })
        }
    }
}

/** 输入框尾部 + 圆形添加按钮 */
@Composable
private fun PersonaAddIconButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) AiPrimary else AiBorder)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "添加",
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 整行的虚线添加按钮 */
@Composable
private fun AddRowButton(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AiInputBg)
            .border(1.dp, AiBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = AiTextSecondary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = AiTextSecondary
        )
    }
}

/** 底部悬浮保存按钮（委托给共享 AiSaveButton） */
@Composable
private fun PersonaSaveButton(
    justSaved: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AiSaveButton(justSaved = justSaved, onClick = onClick, modifier = modifier)
}
