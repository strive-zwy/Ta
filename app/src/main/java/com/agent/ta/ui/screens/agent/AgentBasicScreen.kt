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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * AgentBasicScreen — Agent 基础信息编辑页面（AI Agent Studio 风格）
 *
 * 与人格 / 声音 / 行为页统一使用 ConfigCard：纯白底 + 24dp 大圆角 + 标题
 * 1. VibeTopBar：标题"基础信息" + 返回
 * 2. 配置卡片：
 *    - 基本信息：名字 / 性别（芯片选择：女/男/其他） / 年龄
 *    - 关系与称呼：对用户的称呼 / 自称 / 与用户的关系设定
 *    - 背景与风格：背景故事 / 说话风格简述
 *    - 导演模板：文本导演模板 / 语音导演模板
 * 3. 底部保存按钮（AiSaveButton）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentBasicScreen(onBack: () -> Unit) {
    val editor = ServiceLocator.agentConfigEditor
    val scope = rememberCoroutineScope()

    val initial = remember { editor.get() }
    val agent = initial.agent
    val persona = agent.persona

    var name by remember { mutableStateOf(agent.name) }
    var gender by remember { mutableStateOf(agent.gender) }
    var age by remember { mutableStateOf(if (agent.age > 0) agent.age.toString() else "") }
    var nicknameForUser by remember { mutableStateOf(persona.nicknameForUser) }
    var selfNickname by remember { mutableStateOf(persona.selfNickname) }
    var relationshipToUser by remember { mutableStateOf(persona.relationshipToUser) }
    var background by remember { mutableStateOf(persona.background) }
    var speakingStyle by remember { mutableStateOf(persona.speakingStyle) }
    var directorRoleTemplate by remember { mutableStateOf(persona.directorRoleTemplate) }
    var voiceDirectorTemplate by remember { mutableStateOf(persona.voiceDirectorTemplate) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBg)
    ) {
        VibeTopBar(title = "基础信息", onBack = onBack)
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ===== 卡片 1：基本信息 =====
                ConfigCard(title = "基本信息") {
                    VibeTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "名字",
                        placeholder = "请输入 Agent 名字",
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "性别",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = AiTextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GenderChip(
                                    text = "女",
                                    selected = gender == "female",
                                    onClick = { gender = "female" }
                                )
                                GenderChip(
                                    text = "男",
                                    selected = gender == "male",
                                    onClick = { gender = "male" }
                                )
                                GenderChip(
                                    text = "其他",
                                    selected = gender == "other",
                                    onClick = { gender = "other" }
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            VibeTextField(
                                value = age,
                                onValueChange = { newVal ->
                                    age = newVal.filter { it.isDigit() }.take(3)
                                },
                                label = "年龄",
                                placeholder = "年龄",
                                singleLine = true
                            )
                        }
                    }
                }

                // ===== 卡片 2：关系与称呼 =====
                ConfigCard(title = "关系与称呼") {
                    VibeTextField(
                        value = nicknameForUser,
                        onValueChange = { nicknameForUser = it },
                        label = "对用户的称呼",
                        placeholder = "如：主人 / 那个笨蛋 / 你的名字",
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeTextField(
                        value = selfNickname,
                        onValueChange = { selfNickname = it },
                        label = "自称",
                        placeholder = "如：人家 / 我 / 本小姐",
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeTextField(
                        value = relationshipToUser,
                        onValueChange = { relationshipToUser = it },
                        label = "与用户的关系设定",
                        placeholder = "如：青梅竹马 / 暗恋对象 / 网恋女友",
                        singleLine = true
                    )
                }

                // ===== 卡片 3：背景与风格 =====
                ConfigCard(title = "背景与风格") {
                    VibeTextField(
                        value = background,
                        onValueChange = { background = it },
                        label = "背景故事",
                        placeholder = "角色背景故事"
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeTextField(
                        value = speakingStyle,
                        onValueChange = { speakingStyle = it },
                        label = "说话风格简述",
                        placeholder = "一句话概括，如：温柔、幽默、毒舌",
                        singleLine = true
                    )
                }

                // ===== 卡片 4：导演模板 =====
                ConfigCard(title = "导演模板") {
                    VibeTextField(
                        value = directorRoleTemplate,
                        onValueChange = { directorRoleTemplate = it },
                        label = "文本导演模板",
                        placeholder = "导演模式使用的角色模板（用于文本生成）"
                    )
                    Spacer(Modifier.height(10.dp))
                    VibeTextField(
                        value = voiceDirectorTemplate,
                        onValueChange = { voiceDirectorTemplate = it },
                        label = "语音导演模板",
                        placeholder = "TTS 声学特征指导：节奏/呼吸/情绪强度/停顿习惯"
                    )
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // ===== 底部保存按钮 =====
        AiSaveButton(
            justSaved = false,
            enabled = true,
            onClick = {
                scope.launch {
                    val parsedAge = age.toIntOrNull() ?: 0
                    editor.update { config ->
                        val updatedAgent = config.agent.copy(
                            name = name,
                            gender = gender,
                            age = parsedAge,
                            persona = config.agent.persona.copy(
                                nicknameForUser = nicknameForUser,
                                selfNickname = selfNickname,
                                relationshipToUser = relationshipToUser,
                                background = background,
                                speakingStyle = speakingStyle,
                                directorRoleTemplate = directorRoleTemplate,
                                voiceDirectorTemplate = voiceDirectorTemplate
                            )
                        )
                        config.copy(agent = updatedAgent)
                    }
                    onBack()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        }
    }
}

/**
 * 性别选择芯片（统一 AiPrimary 选中态）
 */
@Composable
private fun GenderChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AiPrimary else AiInputBg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else AiTextSecondary
        )
    }
}
