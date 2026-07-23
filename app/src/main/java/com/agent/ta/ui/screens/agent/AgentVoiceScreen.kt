package com.agent.ta.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.data.model.VoiceConfig
import com.agent.ta.data.model.VoiceSampleFile
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 语音配置页面（AI Agent Studio 风格）
 *
 * 对应设计稿：chat-agent-voice.html
 *
 * 结构（4 卡片）：
 * 1. VibeTopBar：标题"语音配置" + 返回
 * 2. 卡片1 - 音频样本：展示 sampleFiles 列表，可添加/删除
 * 3. 卡片2 - 语音参数：speed/pitch/volume/intonation 滑块 + emotion 下拉
 * 4. 卡片3 - TTS 预处理规则：语音描述 + 标点风格 + 口头禅/语气词处理 + 数字读法 + Emoji 处理
 * 5. 卡片4 - 导演模式：directorMode 开关 + 副标题
 * 6. 底部保存按钮（AiSaveButton）：调用 AgentConfigEditor.update 更新 voice 字段
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentVoiceScreen(onBack: () -> Unit) {
    val editor = ServiceLocator.agentConfigEditor
    val scope = rememberCoroutineScope()

    // 当前 voice 配置的可编辑字段
    val currentAgentConfig = ServiceLocator.agentConfigProvider.get()
    val currentConfig = currentAgentConfig.voice
    val sampleFiles = remember { currentConfig.sampleFiles.toMutableStateList() }

    var voiceDescription by remember { mutableStateOf(currentConfig.voiceDescription) }

    // voiceParams 5 项（Map<String, String> → Float 读取，保存时 toString）
    // speed 范围 0.5-2.0 默认 1.0；pitch 范围 0.5-2.0 默认 1.0
    // volume 范围 0-1.0 默认 1.0；intonation 范围 0-1.0 默认 0.5
    var speed by remember {
        mutableStateOf(currentConfig.voiceParams["speed"]?.toFloatOrNull() ?: 1.0f)
    }
    var pitch by remember {
        mutableStateOf(currentConfig.voiceParams["pitch"]?.toFloatOrNull() ?: 1.0f)
    }
    var volume by remember {
        mutableStateOf(currentConfig.voiceParams["volume"]?.toFloatOrNull() ?: 1.0f)
    }
    var intonation by remember {
        mutableStateOf(currentConfig.voiceParams["intonation"]?.toFloatOrNull() ?: 0.5f)
    }
    // emotion 7 选项：中性/开心/悲伤/愤怒/兴奋/轻柔/严肃
    var emotion by remember {
        mutableStateOf(currentConfig.voiceParams["emotion"]?.ifBlank { "neutral" } ?: "neutral")
    }

    // TTS 预处理规则文本字段
    var punctuationStyle by remember { mutableStateOf(currentConfig.punctuationStyle) }
    var fillerWordsHandling by remember { mutableStateOf(currentConfig.fillerWordsHandling) }
    var numberReading by remember { mutableStateOf(currentConfig.numberReading) }
    var emojiHandling by remember { mutableStateOf(currentConfig.emojiHandling) }

    // 导演模式（directorMode 在 VoiceConfig；voiceDirectorTemplate 属于 Persona，设计稿在基础信息页，本页不展示）
    var directorMode by remember { mutableStateOf(currentConfig.directorMode) }

    var showAddSampleDialog by remember { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }

    val emotionOptions = listOf(
        "neutral" to "中性 neutral",
        "happy" to "开心 happy",
        "sad" to "悲伤 sad",
        "angry" to "愤怒 angry",
        "excited" to "兴奋 excited",
        "soft" to "轻柔 soft",
        "serious" to "严肃 serious"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBg)
    ) {
        // ===== 1. 顶栏 =====
        VibeTopBar(title = "语音配置", onBack = onBack)
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {

            // ===== 2. 内容卡片 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---- 卡片1：音频样本 ----
                ConfigCard(
                    title = "音频样本"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "支持 wav/mp3，建议为不同情绪分别上传样本",
                            fontSize = 12.sp,
                            color = AiTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AiInputBg)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${sampleFiles.size} 条",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AiTextSecondary
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 样本列表
                    if (sampleFiles.isEmpty()) {
                        Text(
                            text = "暂无音频样本",
                            fontSize = 13.sp,
                            color = AiTextTertiary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        sampleFiles.forEachIndexed { index, sample ->
                            VoiceSampleItem(
                                sample = sample,
                                onDelete = { sampleFiles.removeAt(index) }
                            )
                            if (index < sampleFiles.size - 1) {
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 上传样本按钮（边框样式）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, AiBorder, RoundedCornerShape(12.dp))
                            .clickable { showAddSampleDialog = true }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "上传音频样本",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = AiPrimary
                        )
                    }
                }

                // ---- 卡片2：语音参数 ----
                ConfigCard(
                    title = "语音参数"
                ) {
                    VibeSlider(
                        label = "语速 speed",
                        value = speed,
                        onValueChange = { speed = it },
                        valueRange = 0.5f..2.0f,
                        step = 0.05f
                    )
                    Spacer(Modifier.height(12.dp))

                    VibeSlider(
                        label = "音调 pitch",
                        value = pitch,
                        onValueChange = { pitch = it },
                        valueRange = 0.5f..2.0f,
                        step = 0.05f
                    )
                    Spacer(Modifier.height(12.dp))

                    VibeSlider(
                        label = "音量 volume",
                        value = volume,
                        onValueChange = { volume = it },
                        valueRange = 0f..1.0f,
                        step = 0.05f
                    )
                    Spacer(Modifier.height(12.dp))

                    VibeSlider(
                        label = "语调波动 intonation",
                        value = intonation,
                        onValueChange = { intonation = it },
                        valueRange = 0f..1.0f,
                        step = 0.05f
                    )
                    Spacer(Modifier.height(12.dp))

                    // 情绪标签 emotion 下拉选择
                    Text(
                        text = "情绪标签 emotion",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AiTextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    EmotionDropdown(
                        options = emotionOptions,
                        selected = emotion,
                        onSelect = { emotion = it }
                    )
                }

                // ---- 卡片3：TTS 预处理规则 ----
                ConfigCard(
                    title = "TTS 预处理规则"
                ) {
                    // 语音描述（多行）
                    VibeTextField(
                        value = voiceDescription,
                        onValueChange = { voiceDescription = it },
                        label = "语音描述",
                        placeholder = "用自然语言描述音色特征，如：少女音、声线偏柔、尾音略上扬"
                    )
                    Spacer(Modifier.height(12.dp))

                    // 标点风格
                    VibeTextField(
                        value = punctuationStyle,
                        onValueChange = { punctuationStyle = it },
                        label = "标点风格",
                        placeholder = "如：保留省略号与波浪号，弱化感叹号",
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))

                    // 口头禅/语气词处理
                    VibeTextField(
                        value = fillerWordsHandling,
                        onValueChange = { fillerWordsHandling = it },
                        label = "口头禅/语气词处理",
                        placeholder = "如：保留「嗯」「啊」「呢」等语气词",
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))

                    // 数字读法
                    VibeTextField(
                        value = numberReading,
                        onValueChange = { numberReading = it },
                        label = "数字读法",
                        placeholder = "如：年份读四位、价格读「块」",
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))

                    // Emoji 处理
                    VibeTextField(
                        value = emojiHandling,
                        onValueChange = { emojiHandling = it },
                        label = "Emoji 处理",
                        placeholder = "如：翻译为语气词或忽略",
                        singleLine = true
                    )
                }

                // ---- 卡片4：导演模式 ----
                ConfigCard(
                    title = "导演模式"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "开启后 TTS 参考模板改写台词",
                            fontSize = 12.sp,
                            color = AiTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = directorMode,
                            onCheckedChange = { directorMode = it },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = AiPrimary,
                                checkedThumbColor = Color.White,
                                uncheckedTrackColor = AiBorder,
                                uncheckedThumbColor = Color.White
                            )
                        )
                    }
                }

                Spacer(Modifier.height(80.dp))
            }
        }

        // ===== 3. 底部保存按钮（AiSaveButton）=====
        AiSaveButton(
            justSaved = justSaved,
            onClick = {
                scope.launch {
                    // voiceParams 是 Map<String, String>，保存时 Float.toString()
                    val newVoiceParams = buildMap {
                        put("speed", speed.toString())
                        put("pitch", pitch.toString())
                        put("volume", volume.toString())
                        put("emotion", emotion)
                        put("intonation", intonation.toString())
                    }
                    val newVoice = VoiceConfig(
                        sampleFile = currentConfig.sampleFile,
                        directorMode = directorMode,
                        voiceParams = newVoiceParams,
                        voiceDescription = voiceDescription,
                        punctuationStyle = punctuationStyle,
                        fillerWordsHandling = fillerWordsHandling,
                        numberReading = numberReading,
                        emojiHandling = emojiHandling,
                        sampleFiles = sampleFiles.toList()
                    )
                    editor.update { config ->
                        config.copy(voice = newVoice)
                    }
                    justSaved = true
                    kotlinx.coroutines.delay(1000)
                    justSaved = false
                    onBack()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        }
    }

    // ===== 添加音频样本对话框 =====
    if (showAddSampleDialog) {
        AddVoiceSampleDialog(
            onDismiss = { showAddSampleDialog = false },
            onConfirm = { emotionTag ->
                if (emotionTag.isNotBlank()) {
                    sampleFiles.add(
                        VoiceSampleFile(
                            id = System.currentTimeMillis().toString(16),
                            file = "",
                            emotion = emotionTag,
                            transcript = "",
                            durationSec = 0f,
                            sampleRate = 0,
                            primary = sampleFiles.isEmpty() // 第一个自动设为主样本
                        )
                    )
                }
                showAddSampleDialog = false
            }
        )
    }
}

/**
 * 单个音频样本展示行
 *
 * - 左侧：情绪标签（+ "主"标记 if primary）
 * - 中间：转录文本（隐藏技术路径，避免暴露 file:// 等细节）
 * - 右侧：试听图标 + 删除按钮
 */
@Composable
private fun VoiceSampleItem(
    sample: VoiceSampleFile,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = AiShadowColor,
                spotColor = AiShadowColor
            )
            .clip(RoundedCornerShape(16.dp))
            .background(AiCard)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 左：标签列
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(AiChipPrimaryBg)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = sample.emotion.ifBlank { "neutral" },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = AiPrimary
                )
            }
            if (sample.primary) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AiPrimary)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "主",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }

        // 中：转录文本（隐藏技术路径）
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sample.transcript.ifBlank { "(无转录文本)" },
                fontSize = 13.sp,
                color = AiTextPrimary,
                maxLines = 1
            )
        }

        // 右：试听图标 + 删除按钮
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "试听样本",
            tint = AiPrimary,
            modifier = Modifier.size(20.dp)
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除样本",
                tint = AiTextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * AI Studio 风格滑块：label + 数值显示 + Material3 Slider
 *
 * 用于 voiceParams 中 speed/pitch/volume/intonation 等连续值参数。
 * 数值显示规则：step >= 1 显示整数；step < 1 保留 2 位小数。
 */
@Composable
private fun VibeSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float
) {
    val stepCount = (((valueRange.endInclusive - valueRange.start) / step).roundToInt() - 1)
        .coerceAtLeast(0)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = AiTextSecondary
            )
            Text(
                text = formatSliderValue(value, step),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = AiPrimary
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = stepCount,
            colors = SliderDefaults.colors(
                thumbColor = AiPrimary,
                activeTrackColor = AiPrimary,
                inactiveTrackColor = AiBorder
            )
        )
    }
}

/**
 * 滑块值显示格式化
 * - step >= 1：显示为整数（Math.round）
 * - step < 1：保留 2 位小数
 */
private fun formatSliderValue(value: Float, step: Float): String {
    return if (step >= 1f) {
        Math.round(value).toString()
    } else {
        String.format("%.2f", value)
    }
}

/**
 * 情绪标签下拉选择器
 *
 * 设计稿对应 HTML 中的 <select>，使用 Box + DropdownMenu 实现，
 * 视觉风格与 VibeTextField 保持一致（AiInputBg 背景 + 14dp 圆角）。
 */
@Composable
private fun EmotionDropdown(
    options: List<Pair<String, String>>, // (value, displayLabel)
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AiInputBg)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = selectedLabel,
                fontSize = 14.sp,
                color = AiTextPrimary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = AiTextSecondary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 14.sp) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 添加音频样本对话框
 *
 * 简化实现：输入情绪标签即可添加一个空的 VoiceSampleFile。
 * 实际音频文件路径需要后续手动填写。
 */
@Composable
private fun AddVoiceSampleDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var emotionTag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加音频样本", fontWeight = FontWeight.SemiBold, color = AiTextPrimary) },
        text = {
            Column {
                Text(
                    text = "输入该样本对应的情绪标签（如：neutral、happy、sad、angry、excited、soft、serious）",
                    fontSize = 12.sp,
                    color = AiTextSecondary
                )
                Spacer(Modifier.height(8.dp))
                VibeTextField(
                    value = emotionTag,
                    onValueChange = { emotionTag = it },
                    label = "情绪标签",
                    placeholder = "如：happy",
                    singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "提示：实际音频文件路径需后续手动填写；首个样本自动设为主样本",
                    fontSize = 11.sp,
                    color = AiTextTertiary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(emotionTag) }) {
                Text("添加", color = AiPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AiTextSecondary)
            }
        }
    )
}
