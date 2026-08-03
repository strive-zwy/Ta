package com.agent.ta.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.data.model.AgentIdentity

/**
 * 身份内核编辑卡片（Phase 4 复用组件）
 *
 * 用于：
 * - CelebrityClonerScreen 的生成结果预览/微调
 * - AgentPersonaScreen 的 identity 编辑入口
 *
 * 编辑 identity 7 字段 + publicProfile 4 字段（publicProfile 为 null 时不显示公开履历区块）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentityEditCard(
    identity: AgentIdentity,
    onIdentityChange: (AgentIdentity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // ===== 身份内核 7 字段 =====
        SectionLabel("身份内核")

        VibeTextArea(
            value = identity.worldSetting,
            onValueChange = { onIdentityChange(identity.copy(worldSetting = it)) },
            label = "世界观背景",
            placeholder = "我是 XX，在...工作，通过这个方式和用户互动"
        )
        Spacer(Modifier.height(10.dp))

        VibeTextArea(
            value = identity.originStory,
            onValueChange = { onIdentityChange(identity.copy(originStory = it)) },
            label = "来历故事",
            placeholder = "出道经历、代表作品、重要成就..."
        )
        Spacer(Modifier.height(10.dp))

        VibeTextArea(
            value = identity.personalityCore,
            onValueChange = { onIdentityChange(identity.copy(personalityCore = it)) },
            label = "性格核心",
            placeholder = "温柔但有主见，不是讨好型人格..."
        )
        Spacer(Modifier.height(10.dp))

        VibeTextArea(
            value = identity.speakingHabit,
            onValueChange = { onIdentityChange(identity.copy(speakingHabit = it)) },
            label = "说话习惯",
            placeholder = "口头语/语速感/用词偏好..."
        )
        Spacer(Modifier.height(10.dp))

        VibeTextArea(
            value = identity.emotionalPattern,
            onValueChange = { onIdentityChange(identity.copy(emotionalPattern = it)) },
            label = "情绪反应模式",
            placeholder = "被夸/被怼/难过时怎么表现..."
        )
        Spacer(Modifier.height(10.dp))

        VibeTextArea(
            value = identity.relationshipStance,
            onValueChange = { onIdentityChange(identity.copy(relationshipStance = it)) },
            label = "关系定位",
            placeholder = "和用户是偶像-粉丝关系，有距离感但有温度"
        )
        Spacer(Modifier.height(10.dp))

        VibeTextArea(
            value = identity.boundaryAwareness,
            onValueChange = { onIdentityChange(identity.copy(boundaryAwareness = it)) },
            label = "边界认知",
            placeholder = "作为公众人物不能随意承诺见面..."
        )

        // ===== 公开履历 4 字段（偶像克隆模式）=====
        val profile = identity.publicProfile
        if (profile != null) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("公开履历")

            VibeTextField(
                value = profile.careerField,
                onValueChange = {
                    onIdentityChange(identity.copy(publicProfile = profile.copy(careerField = it)))
                },
                label = "领域",
                placeholder = "娱乐圈/音乐/影视"
            )
            Spacer(Modifier.height(10.dp))

            // 代表作品：Chip + 输入添加
            WorksEditor(
                works = profile.knownWorks,
                onWorksChange = {
                    onIdentityChange(identity.copy(publicProfile = profile.copy(knownWorks = it)))
                }
            )
            Spacer(Modifier.height(10.dp))

            VibeTextArea(
                value = profile.fanCulture,
                onValueChange = {
                    onIdentityChange(identity.copy(publicProfile = profile.copy(fanCulture = it)))
                },
                label = "粉丝文化",
                placeholder = "应援色/粉丝名/应援口号"
            )
            Spacer(Modifier.height(10.dp))

            VibeTextField(
                value = profile.careerStage,
                onValueChange = {
                    onIdentityChange(identity.copy(publicProfile = profile.copy(careerStage = it)))
                },
                label = "职业阶段",
                placeholder = "上升期/成熟期/巅峰期/转型期"
            )
        }
    }
}

/**
 * 代表作品编辑器：Chip 展示 + 输入框添加 + ×删除
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorksEditor(
    works: List<String>,
    onWorksChange: (List<String>) -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (works.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                works.forEachIndexed { index, work ->
                    VibeChip(
                        text = work,
                        onDelete = { onWorksChange(works.toMutableList().also { it.removeAt(index) }) },
                        chipType = VibeChipType.DEFAULT
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // 输入框 + 添加按钮
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
                    Text(
                        text = "添加作品后点 +",
                        fontSize = 13.sp,
                        color = AiTextTertiary
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = AiTextPrimary
                    ),
                    cursorBrush = SolidColor(AiPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (input.isNotBlank()) AiPrimary else AiInputBg)
                    .clickable(enabled = input.isNotBlank()) {
                        onWorksChange(works + input.trim())
                        input = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加",
                    tint = if (input.isNotBlank()) Color.White else AiTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 多行文本输入框（用于较长的身份设定字段）
 */
@Composable
private fun VibeTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = ""
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AiTextSecondary,
            modifier = Modifier.padding(bottom = 5.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AiInputBg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    fontSize = 13.sp,
                    color = AiTextTertiary,
                    lineHeight = 18.sp
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = AiTextPrimary,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(AiPrimary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
