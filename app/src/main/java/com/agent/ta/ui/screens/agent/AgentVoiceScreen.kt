package com.agent.ta.ui.screens.agent

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.data.model.VoiceConfig
import com.agent.ta.data.model.VoiceEmotionConfig
import com.agent.ta.di.ServiceLocator
import com.agent.ta.util.VoicePlayer
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 声音工作室（v4 - 现代 AI Agent 声音设计中心）
 *
 * 设计目标：从开发者 TTS 参数面板 → 高级 AI Agent 声音工作室
 * 视觉参考：ChatGPT GPTs / Character.AI 角色声音配置
 *
 * 结构：
 * 1. VibeTopBar：标题"声音工作室" + 副标题"Voice Identity"
 * 2. Hero 卡片：Agent 声音身份（装饰波形图标 + 介绍文案）
 * 3. 声音样本卡片：3 个情绪紧凑列表（隐藏路径，状态 chip + 试听 + 替换）
 * 4. 声音风格卡片：情绪 Tab 切换 + 4 个 iOS 风格滑块（语速/声线/音量/情绪张力）
 * 5. 情绪表达卡片：导演模式开关 + 强化说明（控制语气/停顿/情绪强度）
 * 6. 高级 TTS 规则卡片：折叠展开（默认收起）
 * 7. 底部悬浮保存按钮
 *
 * 设计原则：
 * - 隐藏技术参数（不显示 speed/pitch/volume 英文标识与数值）
 * - 文件路径不显示，用状态描述替代
 * - iOS 风格滑块（描述性文字 + 渐变 track + 圆形 thumb）
 * - 强化导演模式的语义说明，让用户理解它的价值
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentVoiceScreen(onBack: () -> Unit) {
    val editor = ServiceLocator.agentConfigEditor
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val agentConfig by ServiceLocator.agentConfigProvider.config.collectAsState()

    val currentConfig = agentConfig.voice

    // 三个情绪的可编辑状态
    val emotionStates = remember {
        mutableStateOf(
            VoiceEmotionConfig.SUPPORTED.associateWith { emotion ->
                currentConfig.emotions[emotion] ?: VoiceEmotionConfig()
            }
        )
    }

    // 全局字段
    var voiceDescription by remember { mutableStateOf(currentConfig.voiceDescription) }
    var punctuationStyle by remember { mutableStateOf(currentConfig.punctuationStyle) }
    var fillerWordsHandling by remember { mutableStateOf(currentConfig.fillerWordsHandling) }
    var numberReading by remember { mutableStateOf(currentConfig.numberReading) }
    var directorMode by remember { mutableStateOf(currentConfig.directorMode) }
    var justSaved by remember { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var activeEmotion by rememberSaveable { mutableStateOf(VoiceEmotionConfig.NEUTRAL) }

    // 试听播放器
    val previewPlayer = remember { VoicePlayer(context) }
    val playingPath by previewPlayer.currentPath.collectAsState()
    val isPlaying by previewPlayer.isPlaying.collectAsState()

    DisposableEffect(Unit) {
        onDispose { previewPlayer.release() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBg)
    ) {
        VibeTopBar(title = "语音配置", onBack = onBack, subtitle = "Voice Identity")
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ===== 1. Hero 声音身份卡 =====
                    VoiceHeroCard(agentName = agentConfig.agent.name)

                    // ===== 2. 声音样本卡（3 情绪紧凑列表） =====
                    VoiceSamplesCard(
                        emotionStates = emotionStates.value,
                        playingPath = playingPath,
                        isPlaying = isPlaying,
                        onPreview = { path ->
                            val absolute = resolveAbsoluteSamplePath(context, path)
                            if (absolute != null) {
                                if (playingPath == absolute && isPlaying) {
                                    previewPlayer.pause()
                                } else {
                                    previewPlayer.playFromFile(absolute)
                                }
                            } else {
                                Toast.makeText(context, "样本文件不存在", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onReplace = { emotion, newConfig ->
                            emotionStates.value = emotionStates.value.toMutableMap().apply {
                                put(emotion, newConfig)
                            }
                        }
                    )

                    // ===== 3. 声音风格卡（Tab + iOS 滑块） =====
                    VoiceStyleCard(
                        activeEmotion = activeEmotion,
                        onActiveEmotionChange = { activeEmotion = it },
                        emotionStates = emotionStates.value,
                        onChange = { emotion, newConfig ->
                            emotionStates.value = emotionStates.value.toMutableMap().apply {
                                put(emotion, newConfig)
                            }
                        }
                    )

                    // ===== 4. 情绪表达与导演模式 =====
                    DirectorModeCard(
                        enabled = directorMode,
                        onToggle = { directorMode = it }
                    )

                    // ===== 5. 高级 TTS 规则（折叠） =====
                    AdvancedTtsRulesCard(
                        expanded = advancedExpanded,
                        onToggle = { advancedExpanded = it },
                        voiceDescription = voiceDescription,
                        punctuationStyle = punctuationStyle,
                        fillerWordsHandling = fillerWordsHandling,
                        numberReading = numberReading,
                        onVoiceDescriptionChange = { voiceDescription = it },
                        onPunctuationStyleChange = { punctuationStyle = it },
                        onFillerWordsHandlingChange = { fillerWordsHandling = it },
                        onNumberReadingChange = { numberReading = it }
                    )

                    Spacer(Modifier.height(80.dp))
                }
            }

            // ===== 底部悬浮保存按钮（Action Bar 风格） =====
            VoiceSaveActionBar(
                justSaved = justSaved,
                onClick = {
                    scope.launch {
                        // v1 兼容：用 neutral 的 sampleFile 作为默认值
                        val neutralSampleFile = emotionStates.value[VoiceEmotionConfig.NEUTRAL]?.sampleFile ?: ""
                        val newVoice = VoiceConfig(
                            sampleFile = neutralSampleFile,
                            directorMode = directorMode,
                            voiceDescription = voiceDescription,
                            punctuationStyle = punctuationStyle,
                            fillerWordsHandling = fillerWordsHandling,
                            numberReading = numberReading,
                            emotions = emotionStates.value
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
}

// =============================================================
// Hero 卡片
// =============================================================

/**
 * Hero 声音身份卡
 *
 * 多层动态背景（暖橙 #EFBD8A → 玫红 #D343BA 液态流动）：
 * 1. 底层：暖橙→玫红 的水平流动渐变（12s 往返，缓慢柔和）
 * 2. 中层：径向呼吸光晕（从右上角扩散，玫红色调，5s 呼吸）
 * 3. 装饰：右侧 4 根声波竖条，做高度跳动动画（模拟正在发声）
 * 4. 文字区：从左到右的半透明深色渐变 scrim，确保文字在任何颜色上都清晰可读
 *
 * 整体目标：暖橙→玫红的液态光影流动，柔和自然，不出现白色高光。
 */
@Composable
private fun VoiceHeroCard(agentName: String) {
    // 暖橙→玫红 色系（无白色高光）
    val colorWarm = Color(0xFFEFBD8A)        // 暖橙
    val colorPinkDeep = Color(0xFFD343BA)    // 玫红
    // 中间过渡色：在暖橙和玫红之间取中点色，让渐变更柔和
    val colorMid = Color(0xFFE3809F)         // 暖橙与玫红的中间过渡色
    // 深色 scrim 用更深的玫红，保证文字可读
    val colorScrim = Color(0xFF7A1E5E)       // 深玫红（用于 scrim）
    val flowColors = listOf(colorWarm, colorMid, colorPinkDeep, colorMid, colorWarm)

    // 1. 流动动画：offset 0..1 往返（12s 缓慢柔和）
    val transition = rememberInfiniteTransition(label = "voiceHero")
    val flowOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flowOffset"
    )

    // 2. 呼吸光晕：0.5..1.0 往返（5s 呼吸，玫红色调）
    val breatheAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheAlpha"
    )

    // 3. 声波竖条高度动画（4 根，各自不同节奏，错落跳动）
    val wave1 by transition.animateFloat(0.4f, 1.0f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), "wave1")
    val wave2 by transition.animateFloat(0.6f, 1.0f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), "wave2")
    val wave3 by transition.animateFloat(0.3f, 0.9f, infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse), "wave3")
    val wave4 by transition.animateFloat(0.5f, 1.0f, infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Reverse), "wave4")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = colorPinkDeep.copy(alpha = 0.28f),
                spotColor = colorPinkDeep.copy(alpha = 0.28f)
            )
            .clip(RoundedCornerShape(28.dp))
            .drawBehind {
                val w = size.width
                val h = size.height

                // 第 1 层：水平流动渐变（暖橙→玫红）
                val shift = flowOffset * w
                val flowBrush = Brush.horizontalGradient(
                    colors = flowColors,
                    startX = -w + shift,
                    endX = w + shift,
                    tileMode = androidx.compose.ui.graphics.TileMode.Mirror
                )
                drawRect(brush = flowBrush)

                // 第 2 层：径向呼吸光晕（玫红色调，从右上角扩散）
                val glowCenter = Offset(w * 0.82f, h * 0.32f)
                val glowRadius = w * 0.55f
                val glowBrush = Brush.radialGradient(
                    colors = listOf(
                        colorPinkDeep.copy(alpha = 0.40f * breatheAlpha),
                        colorMid.copy(alpha = 0.16f * breatheAlpha),
                        Color.Transparent
                    ),
                    center = glowCenter,
                    radius = glowRadius
                )
                drawRect(brush = glowBrush)

                // 第 3 层：左侧文字区 scrim（从左到右深→透明，提升文字可读性）
                val scrimBrush = Brush.horizontalGradient(
                    colors = listOf(
                        colorScrim.copy(alpha = 0.34f),
                        colorScrim.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    startX = 0f,
                    endX = w * 0.7f
                )
                drawRect(brush = scrimBrush)
            }
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VOICE IDENTITY",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.78f),
                    letterSpacing = 2.5.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = agentName.ifBlank { "小雅" } + " 的声音",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "为不同情绪设计独立声线，让 Agent 真正「像在说话」",
                    fontSize = 12.5.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    lineHeight = 17.sp
                )
            }
            // 右侧：声波竖条（自绘 4 根跳动竖条，模拟正在发声）
            Row(
                modifier = Modifier.size(width = 44.dp, height = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WaveBar(heightFraction = wave1, color = Color.White.copy(alpha = 0.85f))
                WaveBar(heightFraction = wave2, color = Color.White.copy(alpha = 0.95f))
                WaveBar(heightFraction = wave3, color = Color.White.copy(alpha = 0.7f))
                WaveBar(heightFraction = wave4, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

/**
 * 声波竖条：根据 heightFraction (0..1) 决定高度，宽度 3dp，圆角
 */
@Composable
private fun WaveBar(heightFraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .width(width = 3.dp)
            .height(height = (4 + 22 * heightFraction).dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

// =============================================================
// 声音样本卡（3 情绪紧凑列表）
// =============================================================

/**
 * 卡片标题：左侧 3dp 宽青绿色竖条 + 标题文字 + 可选副标题
 *
 * 统一所有卡片标题的视觉层级，竖条作为品牌色锚点强化"系统设置"质感。
 */
@Composable
private fun CardTitle(
    title: String,
    subtitle: String? = null,
    accentColor: Color = AiPrimary
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(width = 3.dp)
                .height(height = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(accentColor)
        )
        Column {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AiTextPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = AiTextTertiary,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

/**
 * 声音样本卡：3 个情绪一行一行排列，每行显示状态 + 试听 + 替换
 *
 * 隐藏文件路径，用「已就绪 / 使用默认 / 未配置」描述状态。
 */
@Composable
private fun VoiceSamplesCard(
    emotionStates: Map<String, VoiceEmotionConfig>,
    playingPath: String?,
    isPlaying: Boolean,
    onPreview: (String) -> Unit,
    onReplace: (String, VoiceEmotionConfig) -> Unit
) {
    val context = LocalContext.current

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
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        // 标题
        CardTitle(
            title = "声音样本",
            subtitle = "为每个情绪上传参考音频，Agent 会克隆你的声线特征"
        )

        Spacer(Modifier.height(16.dp))

        // 3 个情绪行
        VoiceEmotionConfig.SUPPORTED.forEachIndexed { idx, emotion ->
            if (idx > 0) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AiBorder)
                )
                Spacer(Modifier.height(4.dp))
            }
            SampleRow(
                emotion = emotion,
                config = emotionStates[emotion] ?: VoiceEmotionConfig(),
                playingPath = playingPath,
                isPlaying = isPlaying,
                onPreview = onPreview,
                onReplace = { newConfig -> onReplace(emotion, newConfig) }
            )
        }
    }
}

/**
 * 单个情绪的样本行
 *
 * 圆点颜色按情绪区分：neutral=蓝色、happy=红色、calm=紫色（低饱和柔和色）
 */
@Composable
private fun SampleRow(
    emotion: String,
    config: VoiceEmotionConfig,
    playingPath: String?,
    isPlaying: Boolean,
    onPreview: (String) -> Unit,
    onReplace: (VoiceEmotionConfig) -> Unit
) {
    val context = LocalContext.current
    val isNeutral = emotion == VoiceEmotionConfig.NEUTRAL
    val titleZh = when (emotion) {
        VoiceEmotionConfig.NEUTRAL -> "中性"
        VoiceEmotionConfig.HAPPY -> "开朗"
        VoiceEmotionConfig.CALM -> "沉静"
        else -> emotion
    }
    val subtitle = when (emotion) {
        VoiceEmotionConfig.NEUTRAL -> "日常基底 · 必传"
        VoiceEmotionConfig.HAPPY -> "开心 / 兴奋 / 撒娇"
        VoiceEmotionConfig.CALM -> "温柔 / 慵懒 / 低落"
        else -> ""
    }
    // 情绪主色（圆点 + 试听按钮统一）：低饱和柔和色
    // neutral=蓝色、happy=红色、calm=紫色
    val emotionColor = when (emotion) {
        VoiceEmotionConfig.NEUTRAL -> Color(0xFF5B8DEF)    // 柔和蓝
        VoiceEmotionConfig.HAPPY -> Color(0xFFE07A6B)      // 珊瑚红（低饱和）
        VoiceEmotionConfig.CALM -> Color(0xFF9B8AC4)       // 柔和紫
        else -> AiPrimary
    }
    val emotionBgAlpha = emotionColor.copy(alpha = 0.14f)
    val hasOwnSample = config.sampleFile.isNotBlank()
    val absolutePath = resolveAbsoluteSamplePath(context, config.sampleFile)
    val canPreview = absolutePath != null
    val isThisPlaying = isPlaying && playingPath == absolutePath

    // 文件选择器
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val path = copySampleToInternal(context, uri)
            if (path != null) {
                onReplace(config.copy(sampleFile = path))
                Toast.makeText(context, "样本已上传", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "样本上传失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 左侧：状态圆点（带光晕） + 情绪名 + 副标题
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp),
                contentAlignment = Alignment.Center
            ) {
                // 外层光晕（半透明大圆）
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                hasOwnSample && canPreview -> emotionColor.copy(alpha = 0.22f)
                                hasOwnSample && !canPreview -> AiChipAmberFg.copy(alpha = 0.22f)
                                isNeutral -> AiChipRedFg.copy(alpha = 0.22f)
                                else -> AiTextTertiary.copy(alpha = 0.18f)
                            }
                        )
                )
                // 内层实心圆
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                hasOwnSample && canPreview -> emotionColor
                                hasOwnSample && !canPreview -> AiChipAmberFg
                                isNeutral -> AiChipRedFg
                                else -> AiTextTertiary.copy(alpha = 0.6f)
                            }
                        )
                )
            }
            Column {
                Text(
                    text = titleZh,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AiTextPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        hasOwnSample && canPreview -> "样本已就绪 · $subtitle"
                        hasOwnSample && !canPreview -> "样本文件丢失 · $subtitle"
                        isNeutral -> "未配置 · $subtitle"
                        else -> "使用中性默认 · $subtitle"
                    },
                    fontSize = 11.sp,
                    color = AiTextTertiary
                )
            }
        }

        // 右侧：试听 + 替换
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 试听按钮（情绪色圆形，带阴影）
            if (canPreview) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(
                            elevation = if (isThisPlaying) 4.dp else 2.dp,
                            shape = CircleShape,
                            ambientColor = emotionColor.copy(alpha = if (isThisPlaying) 0.4f else 0.2f),
                            spotColor = emotionColor.copy(alpha = if (isThisPlaying) 0.4f else 0.2f)
                        )
                        .clip(CircleShape)
                        .background(if (isThisPlaying) emotionColor else emotionBgAlpha)
                        .clickable { onPreview(config.sampleFile) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isThisPlaying) "暂停" else "试听",
                        tint = if (isThisPlaying) Color.White else emotionColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            // 替换/上传按钮（描边胶囊）
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Transparent)
                    .border(width = 1.dp, color = AiBorder, shape = RoundedCornerShape(50))
                    .clickable { picker.launch("audio/*") }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = null,
                        tint = AiTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (hasOwnSample) "替换" else "上传",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AiTextSecondary
                    )
                }
            }
        }
    }
}

// =============================================================
// 声音风格卡（Tab + iOS 滑块）
// =============================================================

/**
 * 声音风格卡：顶部情绪 Tab 切换 + 4 个带刻度的 iOS 风格滑块
 *
 * Tab 颜色随当前情绪切换；滑块带刻度（steps）让用户看到档位
 */
@Composable
private fun VoiceStyleCard(
    activeEmotion: String,
    onActiveEmotionChange: (String) -> Unit,
    emotionStates: Map<String, VoiceEmotionConfig>,
    onChange: (String, VoiceEmotionConfig) -> Unit
) {
    val config = emotionStates[activeEmotion] ?: VoiceEmotionConfig()
    val emotionLabelZh = when (activeEmotion) {
        VoiceEmotionConfig.NEUTRAL -> "中性"
        VoiceEmotionConfig.HAPPY -> "开朗"
        VoiceEmotionConfig.CALM -> "沉静"
        else -> activeEmotion
    }
    // 当前情绪主色（与样本卡一致：neutral=蓝色、happy=红色、calm=紫色）
    val activeColor = when (activeEmotion) {
        VoiceEmotionConfig.NEUTRAL -> Color(0xFF5B8DEF)    // 柔和蓝
        VoiceEmotionConfig.HAPPY -> Color(0xFFE07A6B)      // 珊瑚红（低饱和）
        VoiceEmotionConfig.CALM -> Color(0xFF9B8AC4)       // 柔和紫
        else -> AiPrimary
    }

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
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        // 标题（带情绪色竖条）
        CardTitle(
            title = "声音风格",
            subtitle = "调整 $emotionLabelZh 情绪下的声学表现",
            accentColor = activeColor
        )

        Spacer(Modifier.height(16.dp))

        // 情绪 Tab 切换（segmented chips，激活态用情绪色 + 阴影）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(AiInputBg)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            VoiceEmotionConfig.SUPPORTED.forEach { emotion ->
                val labelZh = when (emotion) {
                    VoiceEmotionConfig.NEUTRAL -> "中性"
                    VoiceEmotionConfig.HAPPY -> "开朗"
                    VoiceEmotionConfig.CALM -> "沉静"
                    else -> emotion
                }
                val isActive = emotion == activeEmotion
                val tabColor = when (emotion) {
                    VoiceEmotionConfig.NEUTRAL -> Color(0xFF5B8DEF)  // 柔和蓝
                    VoiceEmotionConfig.HAPPY -> Color(0xFFE07A6B)    // 珊瑚红
                    VoiceEmotionConfig.CALM -> Color(0xFF9B8AC4)     // 柔和紫
                    else -> AiPrimary
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .then(
                            if (isActive) Modifier.shadow(
                                elevation = 3.dp,
                                shape = RoundedCornerShape(50),
                                ambientColor = tabColor.copy(alpha = 0.3f),
                                spotColor = tabColor.copy(alpha = 0.3f)
                            ) else Modifier
                        )
                        .background(if (isActive) tabColor else Color.Transparent)
                        .clickable { onActiveEmotionChange(emotion) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelZh,
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isActive) Color.White else AiTextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 4 个带刻度的 iOS 风格滑块（密集刻度）
        // 每个滑块用不同辅助色增强辨识度，但整体保持低饱和柔和
        // 语速=青绿（主色）、声线=柔和橙、音量=柔和蓝、情绪张力=柔和紫
        // 步进：语速/声线 0.5..2.0 步进 0.1（共 16 档，中间 14 档 → steps=14）
        //       音量/情绪张力 0..1.0 步进 0.1（共 11 档，中间 9 档 → steps=9）
        val params = config.voiceParams
        IosStyleSlider(
            label = "语速",
            valueDescription = describeSpeed(params["speed"]?.toFloatOrNull() ?: 1.0f),
            value = params["speed"]?.toFloatOrNull() ?: 1.0f,
            onValueChange = { v ->
                onChange(activeEmotion, config.copy(voiceParams = params.toMutableMap().apply { put("speed", v.toString()) }))
            },
            valueRange = 0.5f..2.0f,
            steps = 14,
            tickColor = AiPrimary                      // 青绿（主色）
        )
        Spacer(Modifier.height(14.dp))
        IosStyleSlider(
            label = "声线",
            valueDescription = describePitch(params["pitch"]?.toFloatOrNull() ?: 1.0f),
            value = params["pitch"]?.toFloatOrNull() ?: 1.0f,
            onValueChange = { v ->
                onChange(activeEmotion, config.copy(voiceParams = params.toMutableMap().apply { put("pitch", v.toString()) }))
            },
            valueRange = 0.5f..2.0f,
            steps = 14,
            tickColor = Color(0xFFE0926B)              // 柔和橙
        )
        Spacer(Modifier.height(14.dp))
        IosStyleSlider(
            label = "音量",
            valueDescription = describeVolume(params["volume"]?.toFloatOrNull() ?: 1.0f),
            value = params["volume"]?.toFloatOrNull() ?: 1.0f,
            onValueChange = { v ->
                onChange(activeEmotion, config.copy(voiceParams = params.toMutableMap().apply { put("volume", v.toString()) }))
            },
            valueRange = 0f..1.0f,
            steps = 9,
            tickColor = Color(0xFF5B8DEF)              // 柔和蓝
        )
        Spacer(Modifier.height(14.dp))
        IosStyleSlider(
            label = "情绪张力",
            valueDescription = describeIntonation(params["intonation"]?.toFloatOrNull() ?: 0.5f),
            value = params["intonation"]?.toFloatOrNull() ?: 0.5f,
            onValueChange = { v ->
                onChange(activeEmotion, config.copy(voiceParams = params.toMutableMap().apply { put("intonation", v.toString()) }))
            },
            valueRange = 0f..1.0f,
            steps = 9,
            tickColor = Color(0xFF9B8AC4)              // 柔和紫
        )
    }
}

/**
 * iOS 风格滑块：label + 描述性文字 + 自绘渐变 track + Material3 Slider（带刻度）
 *
 * - 不显示数值（如 1.20），改用描述性文字（如「偏快」）
 * - 不显示英文参数名（如 speed）
 * - steps > 0 时显示刻度线（tick marks）
 * - 滑块 track 用 drawBehind 自绘渐变（从情绪色到亮色），Material3 Slider 的 track 设为透明
 * - thumb 保持白色，与彩色 track 形成对比（iOS 风格）
 */
@Composable
private fun IosStyleSlider(
    label: String,
    valueDescription: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    tickColor: Color = AiPrimary
) {
    // 计算 value 在 range 中的比例（0..1），用于绘制 active track 宽度
    val fraction = if (valueRange.endInclusive > valueRange.start) {
        ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    } else 0f

    // 渐变终点色：在情绪色基础上混合白色，形成亮色高光
    val gradientEndColor = lerp(tickColor, Color.White, 0.45f)

    // 拖动状态：滑动时在旋钮上方显示数值
    var isDragging by remember { mutableStateOf(false) }

    // 数值气泡的文本（复用于绘制）
    val valueText = String.format("%.1f", value)

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
            // 右上角始终显示描述文字
            Text(
                text = valueDescription,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = tickColor
            )
        }
        Spacer(Modifier.height(8.dp))

        // 用 Box 包裹 Slider，在 Box 背景自绘：track + thumb + 数值气泡
        // 顶部预留 28dp 给数值气泡（仅拖动时显示）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .drawBehind {
                    val trackHeight = 5.dp.toPx()
                    val trackY = size.height - trackHeight - 2.dp.toPx()  // track 贴近底部
                    val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                    val activeWidth = size.width * fraction

                    // 1. inactive track（灰色底）
                    drawRoundRect(
                        color = AiBorder,
                        topLeft = Offset(0f, trackY),
                        size = Size(size.width, trackHeight),
                        cornerRadius = cornerRadius
                    )

                    // 2. active track（渐变填充）
                    if (activeWidth > 0f) {
                        val activeBrush = Brush.horizontalGradient(
                            colors = listOf(tickColor, gradientEndColor),
                            startX = 0f,
                            endX = activeWidth.coerceAtLeast(1f)
                        )
                        drawRoundRect(
                            brush = activeBrush,
                            topLeft = Offset(0f, trackY),
                            size = Size(activeWidth, trackHeight),
                            cornerRadius = cornerRadius
                        )
                    }

                    // 3. 小旋钮（白色小圆 + 情绪色光环）
                    val thumbRadius = 7.dp.toPx()
                    val thumbX = activeWidth
                    val thumbY = trackY + trackHeight / 2f

                    // 3a. 外圈光环（情绪色半透明）
                    drawCircle(
                        color = tickColor.copy(alpha = 0.28f),
                        radius = thumbRadius + 3.dp.toPx(),
                        center = Offset(thumbX, thumbY)
                    )
                    // 3b. 白色实心圆（小旋钮）
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadius,
                        center = Offset(thumbX, thumbY)
                    )

                    // 4. 拖动时在旋钮正上方画数值气泡
                    if (isDragging) {
                        val textPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 28f  // 约 14sp
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
                        // 气泡底部贴近旋钮顶部上方 8dp
                        val bubbleBottom = thumbY - thumbRadius - 8.dp.toPx()
                        val bubbleTop = bubbleBottom - bubbleHeight
                        val bubbleLeft = (thumbX - bubbleWidth / 2f)
                            .coerceIn(0f, size.width - bubbleWidth)  // 防止气泡超出边界

                        // 气泡背景（情绪色圆角矩形）
                        drawRoundRect(
                            color = tickColor,
                            topLeft = Offset(bubbleLeft, bubbleTop),
                            size = Size(bubbleWidth, bubbleHeight),
                            cornerRadius = CornerRadius(bubbleHeight / 2f, bubbleHeight / 2f)
                        )
                        // 气泡小三角箭头（指向旋钮）
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
                                color = tickColor.toArgb()
                                isAntiAlias = true
                            })
                        }
                        // 气泡内文字
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
        ) {
            // Material3 Slider 仅做交互，所有视觉元素都自绘
            Slider(
                value = value,
                onValueChange = {
                    isDragging = true
                    onValueChange(it)
                },
                onValueChangeFinished = { isDragging = false },
                valueRange = valueRange,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,        // 透明，用自绘小旋钮
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,    // 去掉所有刻度竖条
                    inactiveTickColor = Color.Transparent
                )
            )
        }
    }
}

// =============================================================
// 导演模式卡片
// =============================================================

/**
 * 情绪表达与导演模式卡片
 *
 * 强化说明：让用户理解导演模式的价值（控制语气、停顿、情绪强度）
 */
@Composable
private fun DirectorModeCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
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
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 标题（带青绿色竖条 + 主副标题）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .width(width = 3.dp)
                        .height(height = 28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (enabled) AiPrimary else AiTextTertiary.copy(alpha = 0.5f))
                )
                Column {
                    Text(
                        text = "情绪表达",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AiTextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "导演模式 · Director Mode",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = AiTextTertiary,
                        letterSpacing = 1.sp
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = AiPrimary,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = AiBorder,
                    uncheckedThumbColor = Color.White
                )
            )
        }

        Spacer(Modifier.height(14.dp))

        // 能力说明卡片（3 个能力 chip，各用不同辅助色）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DirectorCapabilityChip(
                text = "语气起伏",
                bgColor = Color(0xFFE8F3F1),   // 极浅青绿（降饱和）
                fgColor = AiPrimary
            )
            DirectorCapabilityChip(
                text = "停顿节奏",
                bgColor = Color(0xFFFBEFE6),   // 极浅橙（降饱和）
                fgColor = Color(0xFFB7865A)    // 柔和棕橙
            )
            DirectorCapabilityChip(
                text = "情绪强度",
                bgColor = Color(0xFFF1ECF6),   // 极浅紫（降饱和）
                fgColor = Color(0xFF7E6BA3)    // 柔和紫
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (enabled) {
                "已开启。Agent 在朗读时会自主控制语气、停顿和情绪强度，让声音更具感染力，而非机械朗读。"
            } else {
                "开启后，Agent 将像配音演员一样自主控制语气起伏、停顿节奏与情绪强度，让对话真正「有温度」。"
            },
            fontSize = 12.sp,
            color = AiTextSecondary,
            lineHeight = 18.sp
        )
    }
}

/**
 * 导演模式能力 chip（可自定义颜色）
 */
@Composable
private fun DirectorCapabilityChip(
    text: String,
    bgColor: Color = AiChipPrimaryBg,
    fgColor: Color = AiChipPrimaryFg
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = fgColor
        )
    }
}

// =============================================================
// 高级 TTS 规则卡片（折叠）
// =============================================================

/**
 * 高级 TTS 规则卡片（默认折叠）
 *
 * 折叠时只显示标题 + 展开图标
 * 展开时显示 4 个文本字段：语音描述 / 标点风格 / 口头缀词处理 / 数字读法
 */
@Composable
private fun AdvancedTtsRulesCard(
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    voiceDescription: String,
    punctuationStyle: String,
    fillerWordsHandling: String,
    numberReading: String,
    onVoiceDescriptionChange: (String) -> Unit,
    onPunctuationStyleChange: (String) -> Unit,
    onFillerWordsHandlingChange: (String) -> Unit,
    onNumberReadingChange: (String) -> Unit
) {
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
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        // 标题行（带青绿色竖条 + 可点击折叠/展开）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!expanded) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .width(width = 3.dp)
                        .height(height = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(AiPrimary.copy(alpha = 0.7f))
                )
                Column {
                    Text(
                        text = "高级 TTS 规则",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AiTextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "语音描述、标点风格、口头缀词、数字读法",
                        fontSize = 11.sp,
                        color = AiTextTertiary
                    )
                }
            }
            // 展开/收起图标（圆形背景 + 旋转 chevron）
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AiInputBg)
                    .clickable { onToggle(!expanded) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = AiTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 折叠内容
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                VibeTextField(
                    value = voiceDescription,
                    onValueChange = onVoiceDescriptionChange,
                    label = "语音描述",
                    placeholder = "用自然语言描述音色特征，如：少女音、声线偏柔、尾音略上扬"
                )
                Spacer(Modifier.height(12.dp))
                VibeTextField(
                    value = punctuationStyle,
                    onValueChange = onPunctuationStyleChange,
                    label = "标点风格",
                    placeholder = "如：保留省略号与波浪号，弱化感叹号",
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                VibeTextField(
                    value = fillerWordsHandling,
                    onValueChange = onFillerWordsHandlingChange,
                    label = "口头缀词处理",
                    placeholder = "如：保留「嗯」「啊」「呢」等语气词",
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                VibeTextField(
                    value = numberReading,
                    onValueChange = onNumberReadingChange,
                    label = "数字读法",
                    placeholder = "如：年份读四位、价格读「块」",
                    singleLine = true
                )
            }
        }
    }
}

// =============================================================
// 工具函数
// =============================================================

/**
 * 把 SAF Uri 指向的音频复制到内部存储
 * 存放位置：filesDir/voice_samples/sample_<timestamp>.wav
 */
private fun copySampleToInternal(context: Context, uri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "voice_samples").apply { mkdirs() }
        val fileName = "sample_${System.currentTimeMillis()}.wav"
        val destFile = File(dir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        destFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

/**
 * 解析样本路径为绝对路径
 * - 绝对路径且文件存在 → 直接返回
 * - 相对路径 → 尝试 filesDir/<relative> 解析
 * - 都不成功 → null
 */
private fun resolveAbsoluteSamplePath(context: Context, path: String): String? {
    if (path.isBlank()) return null
    return runCatching {
        val file = File(path)
        if (file.isAbsolute && file.exists()) {
            return file.absolutePath
        }
        val relativeFile = File(context.filesDir, path)
        if (relativeFile.exists()) {
            relativeFile.absolutePath
        } else {
            null
        }
    }.getOrNull()
}

// =============================================================
// 描述性文字：把数值转化为用户可读的语义
// =============================================================

private fun describeSpeed(v: Float): String = when {
    v < 0.7f -> "极慢"
    v < 0.9f -> "偏慢"
    v < 1.1f -> "标准"
    v < 1.3f -> "偏快"
    v < 1.6f -> "较快"
    else -> "急促"
}

private fun describePitch(v: Float): String = when {
    v < 0.7f -> "低沉"
    v < 0.9f -> "偏低"
    v < 1.1f -> "标准"
    v < 1.3f -> "清亮"
    v < 1.6f -> "高亢"
    else -> "尖锐"
}

private fun describeVolume(v: Float): String = when {
    v < 0.2f -> "极轻"
    v < 0.4f -> "轻柔"
    v < 0.7f -> "适中"
    v < 0.9f -> "响亮"
    else -> "洪亮"
}

private fun describeIntonation(v: Float): String = when {
    v < 0.2f -> "平淡"
    v < 0.4f -> "稳重"
    v < 0.6f -> "自然"
    v < 0.8f -> "生动"
    else -> "丰富"
}

// =============================================================
// 底部悬浮保存 Action Bar（语音页专用，比 AiSaveButton 更精致）
// =============================================================

/**
 * 语音配置页底部保存按钮
 *
 * - 顶部加一条极淡的渐变分割线，与内容区分隔
 * - 按钮圆角 24dp（比共享版 16dp 更柔）
 * - 双层阴影：内层柔光晕 + 外层轻阴影，强化"悬浮"质感
 * - 保存成功时按钮内显示打勾动画感（用文字过渡即可）
 */
@Composable
private fun VoiceSaveActionBar(
    justSaved: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        AiBg.copy(alpha = 0.85f),
                        AiBg
                    )
                )
            )
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .padding(bottom = 14.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = AiPrimary.copy(alpha = 0.22f),
                    spotColor = AiPrimary.copy(alpha = 0.22f)
                )
                .clip(RoundedCornerShape(24.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = if (justSaved) {
                            listOf(Color(0xFF3DA8A0), AiPrimary)
                        } else {
                            listOf(AiPrimary, AiPrimaryDeep)
                        }
                    )
                )
                .clickable(enabled = !justSaved) { onClick() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (justSaved) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow, // 简单复用作为"已保存"标记（无新引入图标）
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = if (justSaved) "已保存" else "保存声音配置",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
