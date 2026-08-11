package com.agent.ta.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agent.ta.domain.CloneResult

/**
 * AI 辅助克隆页面（Phase 4）
 *
 * 流程：输入启发人物名 + 自定义昵称 → 生成 → 预览/微调 → 确认应用
 *
 * 法律定位：worldSetting 内部写"我是 XX（真名）"，App 显示用户自定义昵称
 * 应用后保留 voice/avatars/behavior，仅覆盖 identity + 部分 persona
 */
@Composable
fun CelebrityClonerScreen(
    onBack: () -> Unit,
    viewModel: CloneViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val starName by viewModel.starName.collectAsState()
    val nickname by viewModel.customNickname.collectAsState()
    val saveState by viewModel.saveState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBg)
    ) {
        VibeTopBar(title = "AI 辅助克隆", onBack = onBack, subtitle = "输入明星名字，AI 自动生成身份设定")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 100.dp)  // 给底部按钮留空间
        ) {
            // ===== 输入区 =====
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                ConfigCard(title = "克隆来源") {
                    VibeTextField(
                        value = starName,
                        onValueChange = viewModel::onStarNameChange,
                        label = "启发人物名",
                        placeholder = "如：周深",
                        singleLine = true,
                        enabled = uiState !is CloneUiState.Loading
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeTextField(
                        value = nickname,
                        onValueChange = viewModel::onNicknameChange,
                        label = "自定义 Agent 昵称",
                        placeholder = "如：深深（App 显示用，非真名）",
                        singleLine = true,
                        enabled = uiState !is CloneUiState.Loading
                    )
                    Spacer(Modifier.height(14.dp))
                    // 生成按钮
                    GenerateButton(
                        enabled = starName.isNotBlank() && nickname.isNotBlank() &&
                            uiState !is CloneUiState.Loading,
                        loading = uiState is CloneUiState.Loading,
                        onClick = { viewModel.generate() }
                    )
                }
            }

            // ===== 状态区 =====
            when (val state = uiState) {
                is CloneUiState.Idle -> {
                    // 提示文案
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        ConfigCard(title = "使用说明") {
                            Text(
                                text = "1. 输入想克隆的明星名字\n" +
                                    "2. 自定义 Agent 显示昵称（避免直接使用真名）\n" +
                                    "3. AI 自动生成完整身份设定\n" +
                                    "4. 预览并微调生成结果\n" +
                                    "5. 确认后应用为新 Agent 配置\n\n" +
                                    "注：voice / avatars / behavior 等配置会保留，\n" +
                                    "可在 /config 的对应子页面单独修改。",
                                fontSize = 13.sp,
                                color = AiTextSecondary,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }

                is CloneUiState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = AiPrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "AI 正在搜索资料并生成身份设定...",
                            fontSize = 13.sp,
                            color = AiTextSecondary
                        )
                    }
                }

                is CloneUiState.Success -> {
                    // 可编辑副本：用户可在预览区微调
                    var editedResult by remember(state.result) { mutableStateOf(state.result) }
                    ResultPreviewSection(
                        result = editedResult,
                        onResultChange = { editedResult = it }
                    )
                    Spacer(Modifier.height(16.dp))
                    ConfirmApplyButton(
                        saving = saveState is SaveState.Saving,
                        saved = saveState is SaveState.Saved,
                        enabled = saveState !is SaveState.Saving,
                        onClick = { viewModel.applyAndSave(editedResult) }
                    )
                    if (saveState is SaveState.Saved) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "✓ 已应用为新 Agent 配置，返回配置页查看",
                            fontSize = 13.sp,
                            color = AiPrimary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    if (saveState is SaveState.SaveError) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "保存失败：${(saveState as SaveState.SaveError).message}",
                            fontSize = 13.sp,
                            color = Color(0xFFDC2626),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                is CloneUiState.Error -> {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        ConfigCard(title = "生成失败") {
                            Text(
                                text = state.message,
                                fontSize = 14.sp,
                                color = Color(0xFFDC2626),
                                lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            GenerateButton(
                                enabled = starName.isNotBlank() && nickname.isNotBlank(),
                                loading = false,
                                text = "重试",
                                onClick = { viewModel.generate() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 生成按钮（青绿渐变填充）
 */
@Composable
private fun GenerateButton(
    enabled: Boolean,
    loading: Boolean,
    text: String = "✨ 开始生成",
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) Brush.linearGradient(listOf(AiPrimary, AiPrimaryDeep))
                else Brush.linearGradient(listOf(Color(0xFFB8D4D2), Color(0xFFA8C4C2)))
            )
            .clickable(enabled = enabled && !loading) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("生成中...", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 结果预览/微调区
 *
 * - identity 11 字段：用 IdentityEditCard 复用组件
 * - persona 联动 5 字段：background / personality 标签 / interests 标签 / speakingStyle / directorRoleTemplate
 */
@Composable
private fun ResultPreviewSection(
    result: CloneResult,
    onResultChange: (CloneResult) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        ConfigCard(
            title = "生成结果预览",
            description = "可直接编辑任何字段，确认后应用"
        ) {
            IdentityEditCard(
                identity = result.identity,
                onIdentityChange = { newIdentity ->
                    onResultChange(result.copy(identity = newIdentity))
                }
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("人格联动")

            VibeTextField(
                value = result.personaBackground,
                onValueChange = { onResultChange(result.copy(personaBackground = it)) },
                label = "背景描述（第三人称）"
            )
            Spacer(Modifier.height(10.dp))

            // personality 标签
            TagListEditor(
                label = "性格标签",
                tags = result.personaPersonality,
                onTagsChange = { onResultChange(result.copy(personaPersonality = it)) },
                placeholder = "如：温柔"
            )
            Spacer(Modifier.height(10.dp))

            TagListEditor(
                label = "兴趣标签",
                tags = result.personaInterests,
                onTagsChange = { onResultChange(result.copy(personaInterests = it)) },
                placeholder = "如：音乐"
            )
            Spacer(Modifier.height(10.dp))

            VibeTextField(
                value = result.personaSpeakingStyle,
                onValueChange = { onResultChange(result.copy(personaSpeakingStyle = it)) },
                label = "说话风格"
            )
            Spacer(Modifier.height(10.dp))

            VibeTextField(
                value = result.personaDirectorRoleTemplate,
                onValueChange = { onResultChange(result.copy(personaDirectorRoleTemplate = it)) },
                label = "TTS 导演模板"
            )
        }
    }
}

/**
 * 标签列表编辑器（Chip + 输入添加 + ×删除）
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagListEditor(
    label: String,
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    placeholder: String
) {
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AiTextSecondary,
            modifier = Modifier.padding(bottom = 5.dp)
        )
        if (tags.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEachIndexed { index, tag ->
                    VibeChip(
                        text = tag,
                        onDelete = { onTagsChange(tags.toMutableList().also { it.removeAt(index) }) },
                        chipType = if (label.contains("性格")) VibeChipType.PERSONALITY else VibeChipType.INTEREST
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AiInputBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (input.isEmpty()) {
                    Text(text = placeholder, fontSize = 13.sp, color = AiTextTertiary)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = AiTextPrimary
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AiPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (input.isNotBlank()) AiPrimary else AiInputBg)
                    .clickable(enabled = input.isNotBlank()) {
                        onTagsChange(tags + input.trim())
                        input = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    fontSize = 22.sp,
                    color = if (input.isNotBlank()) Color.White else AiTextTertiary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 确认应用按钮（底部悬浮）
 */
@Composable
private fun ConfirmApplyButton(
    saving: Boolean,
    saved: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .background(AiBg.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = AiPrimary.copy(alpha = 0.3f),
                    spotColor = AiPrimary.copy(alpha = 0.3f)
                )
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (saved) Brush.linearGradient(listOf(Color(0xFF15803D), Color(0xFF166534)))
                    else Brush.linearGradient(listOf(AiPrimary, AiPrimaryDeep))
                )
                .clickable(enabled = enabled && !saving && !saved) { onClick() }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center
        ) {
            if (saving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("保存中...", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    text = if (saved) "✓ 已应用" else "✓ 确认应用",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
