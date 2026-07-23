package com.agent.ta.ui.screens.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.data.model.ModelEntry
import com.agent.ta.data.remote.TtsClient
import com.agent.ta.data.remote.TtsDiagnosisResult
import com.agent.ta.di.ServiceLocator
import com.agent.ta.ui.theme.VibePrimary
import com.agent.ta.ui.theme.VibePrimaryDeep
import com.agent.ta.ui.theme.VibeCardDark
import com.agent.ta.ui.theme.VibePrimaryGlow
import com.agent.ta.ui.theme.VibePrimarySoft
import com.agent.ta.ui.theme.VibePrimaryTint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ModelConfigScreen — Vibe Chat 风格（参考设计稿 chat-model-config.html）
 *
 * 结构：
 * 1. sticky 顶部返回栏（半透明白色 + blur）：← + 主标题"模型配置" + 副标题
 * 2. 三张白色卡片（rounded-2xl + border）：
 *    - 卡片 A：用户称呼
 *    - 卡片 B：LLM 模型配置（Base URL + API Key + Model）
 *    - 卡片 C：TTS 模型配置（含"测试语音"圆角按钮）
 * 3. 底部保存按钮：Vibe 渐变 tint→primary 135deg + 阴影光晕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(onConfigured: () -> Unit, onBack: (() -> Unit)? = null) {
    val prefs = ServiceLocator.userPreferences

    var nickname by remember { mutableStateOf(prefs.userNickname) }

    // ===== LLM 多模型状态 =====
    val llmModels = remember { prefs.llmModels.toMutableStateList() }
    var llmActiveId by remember { mutableStateOf(prefs.llmActiveId.ifBlank { llmModels.firstOrNull()?.id ?: "" }) }
    // 当前激活模型的可编辑字段（与激活 entry 双向同步）
    var llmBaseUrl by remember(llmActiveId) {
        mutableStateOf(llmModels.firstOrNull { it.id == llmActiveId }?.baseUrl ?: "")
    }
    var llmApiKey by remember(llmActiveId) {
        mutableStateOf(llmModels.firstOrNull { it.id == llmActiveId }?.apiKey ?: "")
    }
    var llmModel by remember(llmActiveId) {
        mutableStateOf(llmModels.firstOrNull { it.id == llmActiveId }?.model ?: "")
    }
    var llmKeyVisible by remember { mutableStateOf(false) }

    // ===== TTS 配置状态（简化版：baseUrl + apiKey，模型固定为三个 MiMo 模型自动选择） =====
    var ttsBaseUrl by remember { mutableStateOf(prefs.ttsBaseUrl) }
    var ttsApiKey by remember { mutableStateOf(prefs.ttsApiKey) }
    var ttsKeyVisible by remember { mutableStateOf(false) }

    // ===== 弹窗状态 =====
    var showLlmPicker by remember { mutableStateOf(false) }
    var showLlmAddDialog by remember { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var diagnosis by remember { mutableStateOf<TtsDiagnosisResult?>(null) }
    var justSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 持久化当前编辑字段到激活模型 entry（每次字段变化时同步）
    fun syncLlmActive() {
        val idx = llmModels.indexOfFirst { it.id == llmActiveId }
        if (idx >= 0) {
            llmModels[idx] = llmModels[idx].copy(baseUrl = llmBaseUrl, apiKey = llmApiKey, model = llmModel)
        }
    }

    // 切换激活模型时，重置编辑字段为新激活 entry 的值
    fun selectLlm(id: String) {
        syncLlmActive() // 先保存当前编辑
        llmActiveId = id
        val m = llmModels.firstOrNull { it.id == id }
        if (m != null) {
            llmBaseUrl = m.baseUrl
            llmApiKey = m.apiKey
            llmModel = m.model
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        // ===== 1. 顶部返回栏（固定，不随页面滑动）=====
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            tonalElevation = 0.dp
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onBack?.invoke() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "模型配置",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "为 Agent 配置 LLM 与 TTS 接口",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
            // ===== 2. 表单区（三张卡片）=====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 卡片 A：用户称呼
                ConfigCard(
                    icon = Icons.Default.AccountCircle,
                    title = "用户称呼"
                ) {
                    VibeOutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = "称呼",
                        placeholder = "对你的称呼",
                        singleLine = true
                    )
                }

                // 卡片 B：LLM 模型配置
                ConfigCard(
                    icon = Icons.Default.Bolt,
                    title = "LLM 模型配置"
                ) {
                    // 模型选择器（点击打开选择对话框）
                    ModelSelector(
                        name = llmModels.firstOrNull { it.id == llmActiveId }?.name ?: "未选择",
                        onClick = { showLlmPicker = true }
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeOutlinedTextField(
                        value = llmBaseUrl,
                        onValueChange = { llmBaseUrl = it },
                        label = "Base URL",
                        placeholder = "https://api.deepseek.com/v1",
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeOutlinedTextField(
                        value = llmApiKey,
                        onValueChange = { llmApiKey = it },
                        label = "API Key",
                        singleLine = true,
                        isPassword = !llmKeyVisible,
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { llmKeyVisible = !llmKeyVisible },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (llmKeyVisible) Icons.Default.VisibilityOff
                                                  else Icons.Default.Visibility,
                                    contentDescription = if (llmKeyVisible) "隐藏" else "显示",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeOutlinedTextField(
                        value = llmModel,
                        onValueChange = { llmModel = it },
                        label = "Model",
                        placeholder = "如：deepseek-chat / grok-4.5",
                        singleLine = true
                    )
                }

                // 卡片 C：TTS 模型配置
                ConfigCard(
                    icon = Icons.Default.GraphicEq,
                    title = "TTS 模型配置"
                ) {
                    // TTS 模型说明（三个 MiMo 模型自动选择，无需用户配置）
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = VibePrimarySoft,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = "MiMo TTS · 三模型自动选择",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = VibePrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "有音频样本 → 音色复刻；有音色描述 → 音色设计；否则 → 预置音色",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    VibeOutlinedTextField(
                        value = ttsBaseUrl,
                        onValueChange = { ttsBaseUrl = it },
                        label = "Base URL",
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeOutlinedTextField(
                        value = ttsApiKey,
                        onValueChange = { ttsApiKey = it },
                        label = "API Key",
                        singleLine = true,
                        isPassword = !ttsKeyVisible,
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { ttsKeyVisible = !ttsKeyVisible },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (ttsKeyVisible) Icons.Default.VisibilityOff
                                                  else Icons.Default.Visibility,
                                    contentDescription = if (ttsKeyVisible) "隐藏" else "显示",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(12.dp))

                    // 测试语音按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        val testScale by animateFloatAsState(
                            targetValue = if (testing) 0.95f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = 380f
                            ),
                            label = "testScale"
                        )
                        Surface(
                            modifier = Modifier
                                .scale(testScale)
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !testing && ttsApiKey.isNotBlank() && ttsBaseUrl.isNotBlank()) {
                                    // 临时写入 baseUrl/apiKey 供 TtsClient 读取
                                    prefs.ttsBaseUrl = ttsBaseUrl
                                    prefs.ttsApiKey = ttsApiKey
                                    testing = true
                                    diagnosis = null
                                    scope.launch {
                                        val config = ServiceLocator.agentConfigProvider.get()
                                        val samplePath = config.voice.sampleFile.takeIf { it.isNotBlank() }
                                        val result = try {
                                            TtsClient().diagnose(
                                                text = "你好，我是你的 Agent，很高兴认识你。",
                                                directorPrompt = "用亲切温柔的语气说",
                                                voiceSamplePath = samplePath
                                            )
                                        } catch (e: Exception) {
                                            TtsDiagnosisResult(
                                                success = false,
                                                message = "调用异常：${e.javaClass.simpleName}: ${e.message}",
                                                baseUrl = ttsBaseUrl,
                                                apiKeyConfigured = ttsApiKey.isNotBlank(),
                                                model = "mimo-v2.5-tts",
                                                samplePath = samplePath,
                                                sampleExists = false,
                                                sampleSizeBytes = 0,
                                                sampleFormat = "wav",
                                                base64Length = 0,
                                                error = "exception:${e.javaClass.simpleName}"
                                            )
                                        }
                                        diagnosis = result
                                        testing = false
                                    }
                                },
                            color = VibePrimarySoft,
                            shape = RoundedCornerShape(50)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (testing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = VibePrimary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = VibePrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = if (testing) "正在测试…" else "测试语音",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = VibePrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp)) // 给底部保存按钮留空间
            }
        }
        }
        }

        // ===== 3. 底部保存按钮（Vibe 渐变 + 阴影光晕）=====
        // 设计稿：
        //   <div class="px-4 pt-2 pb-8">
        //     <button class="w-full rounded-2xl py-3.5 text-[15px] font-semibold text-white"
        //       style="background: linear-gradient(135deg, #2f8784, #1b5e5c);
        //              box-shadow: 0 8px 20px -8px var(--vibe-primary-glow);">保存</button>
        //   </div>
        val canSave = llmApiKey.isNotBlank() && ttsApiKey.isNotBlank()
        val saveScale by animateFloatAsState(
            targetValue = if (justSaved) 1.05f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = 380f
            ),
            label = "saveScale"
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            val gradient = Brush.linearGradient(
                colors = listOf(VibePrimaryTint, VibePrimary)
            )
            val successGradient = Brush.linearGradient(
                colors = listOf(VibePrimary, VibePrimaryDeep)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(saveScale)
                    .graphicsLayer { shadowElevation = 20f }
                    .clip(RoundedCornerShape(16.dp))
                    .background(brush = if (justSaved) successGradient else gradient)
                    .clickable(enabled = canSave && !justSaved) {
                        // 保存 LLM 模型列表 + 当前激活 id + TTS baseUrl/apiKey + 称呼
                        syncLlmActive()
                        prefs.userNickname = nickname.ifBlank { "你" }
                        prefs.llmModels = llmModels.toList()
                        prefs.llmActiveId = llmActiveId
                        prefs.ttsBaseUrl = ttsBaseUrl
                        prefs.ttsApiKey = ttsApiKey
                        scope.launch {
                            justSaved = true
                            delay(1200)
                            justSaved = false
                            onConfigured()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // 设计稿：py-3.5 = 14dp vertical padding + 15sp font-semibold
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedVisibility(
                        visible = justSaved,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "已保存",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (!justSaved) {
                        Text(
                            text = if (onBack != null) "保存" else "保存并继续",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    // ===== 诊断结果弹窗 =====
    val result = diagnosis
    if (result != null) {
        AlertDialog(
            onDismissRequest = { diagnosis = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val iconTint = if (result.success) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.error
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (result.success) "✓" else "✗",
                            color = iconTint,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(if (result.success) "语音测试成功" else "语音测试失败")
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    InfoRow("结果", if (result.success) "成功" else "失败")
                    InfoRow("总览", result.message)
                    Spacer(Modifier.height(8.dp))
                    SectionDivider()
                    SectionTitle("TTS 配置")
                    InfoRow("Base URL", result.baseUrl)
                    InfoRow("API Key", if (result.apiKeyConfigured) "已配置" else "未配置")
                    InfoRow("模型", result.model)
                    Spacer(Modifier.height(8.dp))
                    SectionDivider()
                    SectionTitle("样本音频")
                    InfoRow("路径", result.samplePath ?: "(空)")
                    InfoRow("是否存在", if (result.sampleExists) "是" else "否")
                    InfoRow("文件大小", "${result.sampleSizeBytes} bytes")
                    InfoRow("声明格式", result.sampleFormat)
                    InfoRow("Base64 长度", "${result.base64Length} chars")

                    if (result.httpStatus != null) {
                        Spacer(Modifier.height(8.dp))
                        SectionDivider()
                        InfoRow("HTTP 状态码", result.httpStatus.toString())
                    }
                    if (result.audioDataFound) {
                        Spacer(Modifier.height(8.dp))
                        SectionDivider()
                        InfoRow("解析到音频", "是，${result.audioBytes} bytes")
                    }
                    if (!result.responsePreview.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        SectionDivider()
                        SectionTitle("响应原始 body 预览")
                        Spacer(Modifier.height(4.dp))
                        CodeBlock(text = result.responsePreview)
                    }
                    if (!result.error.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        SectionDivider()
                        InfoRow("错误标识", result.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { diagnosis = null }) { Text("关闭") }
            }
        )
    }

    // ===== LLM 模型选择对话框 =====
    if (showLlmPicker) {
        ModelPickerDialog(
            title = "选择 LLM 模型",
            models = llmModels,
            activeId = llmActiveId,
            onDismiss = { showLlmPicker = false },
            onSelect = { id ->
                selectLlm(id)
                showLlmPicker = false
            },
            onAddClick = {
                showLlmPicker = false
                showLlmAddDialog = true
            },
            onDelete = { id ->
                if (llmModels.size > 1) {
                    llmModels.removeIf { it.id == id }
                    if (llmActiveId == id) {
                        llmActiveId = llmModels.first().id
                        val m = llmModels.first()
                        llmBaseUrl = m.baseUrl
                        llmApiKey = m.apiKey
                        llmModel = m.model
                    }
                }
            }
        )
    }

    // ===== 添加 LLM 模型对话框 =====
    if (showLlmAddDialog) {
        AddModelDialog(
            title = "添加 LLM 模型",
            onDismiss = { showLlmAddDialog = false },
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    val entry = ModelEntry(id = System.currentTimeMillis().toString(16) + (0..0xFFFF).random().toString(16), name = name)
                    llmModels.add(entry)
                    llmActiveId = entry.id
                    llmBaseUrl = ""
                    llmApiKey = ""
                    llmModel = ""
                }
                showLlmAddDialog = false
            }
        )
    }
}

/**
 * 模型选择器行：当前模型名 + 下拉箭头，点击触发 [onClick]
 *
 * 设计上对应配置卡片顶部的"模型切换条"：浅色圆角背景 + 模型名 + 下拉箭头。
 */
@Composable
private fun ModelSelector(
    name: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = VibePrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = "切换模型",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 模型选择对话框：列出所有模型，每行可选择/删除，底部"+ 添加模型"
 *
 * @param activeId 当前激活模型 id（高亮显示）
 * @param onSelect 选中某模型
 * @param onAddClick 点击"添加模型"按钮
 * @param onDelete 删除某模型（列表只剩 1 个时禁用删除）
 */
@Composable
private fun ModelPickerDialog(
    title: String,
    models: List<ModelEntry>,
    activeId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAddClick: () -> Unit,
    onDelete: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(VibePrimarySoft)
                        .clickable(onClick = onAddClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加模型",
                        tint = VibePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Column {
                models.forEach { entry ->
                    val isActive = entry.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isActive) VibePrimarySoft else Color.Transparent)
                            .clickable { onSelect(entry.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isActive) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(VibePrimary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Column {
                                Text(
                                    text = entry.name,
                                    fontSize = 14.sp,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isActive) VibePrimary else MaterialTheme.colorScheme.onSurface
                                )
                                if (entry.model.isNotBlank()) {
                                    Text(
                                        text = entry.model,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        // 删除按钮（至少保留 1 个）
                        if (models.size > 1) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable { onDelete(entry.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "点击模型切换为当前使用；点击右上 + 添加新模型",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 添加模型对话框：输入模型名称后创建空配置的新模型
 */
@Composable
private fun AddModelDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    text = "为这个模型起一个易记的名字（如 DeepSeek / Grok / MiMo）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                VibeOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "模型名称",
                    placeholder = "如：DeepSeek",
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Vibe Chat 配置卡片：浅青灰底 + 圆角 + icon header（对齐设计稿 vibe-card）
 */
@Composable
private fun ConfigCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = VibePrimaryGlow,
                spotColor = VibePrimaryGlow
            )
            .clip(RoundedCornerShape(16.dp))
            .background(VibeCardDark)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VibePrimarySoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = VibePrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = VibePrimary
            )
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * Vibe Chat TextField — 精确对应设计稿 chat-model-config.html
 *
 * 设计稿：
 * ```html
 * <div class="flex flex-col">
 *   <label class="text-[12px] font-medium mb-1" style="color: var(--vibe-muted-foreground);">Base URL</label>
 *   <input class="w-full rounded-xl px-3.5 py-2.5 text-[14px] outline-none"
 *          style="background-color: var(--vibe-muted); color: var(--vibe-foreground);">
 * </div>
 * ```
 * 即：label 在外上方（12sp medium，muted-foreground 色）+ 12dp 圆角 muted 背景输入框（无边框）
 */
@Composable
private fun VibeOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = false,
    isPassword: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        // label 在外上方：text-[12px] font-medium mb-1
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isFocused) VibePrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        // 输入框：rounded-xl + px-3.5 py-2.5 + muted bg + 无边框
        // 设计稿：flex items-center justify-between gap-2
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        Text(
                            text = placeholder,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = singleLine,
                        visualTransformation = if (isPassword) PasswordVisualTransformation()
                                               else VisualTransformation.None,
                        interactionSource = interactionSource,
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (isPassword) FontFamily.Monospace else null
                        ),
                        cursorBrush = SolidColor(VibePrimary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // trailing icon（如密码显示切换）放在右侧
                if (trailingIcon != null) {
                    trailingIcon()
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = VibePrimary
    )
}

@Composable
private fun CodeBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .padding(10.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
