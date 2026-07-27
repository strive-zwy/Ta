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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Upload
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.di.ServiceLocator
import com.agent.ta.ui.theme.VibePrimary
import com.agent.ta.ui.theme.VibePrimaryDeep
import com.agent.ta.ui.theme.VibePrimaryGlow
import com.agent.ta.ui.theme.VibePrimarySoft
import com.agent.ta.ui.theme.VibePrimaryTint
import com.agent.ta.ui.theme.VibeCardDark
import com.agent.ta.ui.theme.VibeTagAmberBg
import com.agent.ta.ui.theme.VibeTagAmberFg
import com.agent.ta.ui.theme.VibeTagGreenBg
import com.agent.ta.ui.theme.VibeTagGreenFg
import com.agent.ta.ui.theme.VibeTagPinkBg
import com.agent.ta.ui.theme.VibeTagPinkFg
import com.agent.ta.ui.theme.VibeTagSkyBg
import com.agent.ta.ui.theme.VibeTagSkyFg
import androidx.compose.foundation.BorderStroke
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.agent.ta.domain.AgentConfigExporter
import com.agent.ta.domain.AgentImportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// 功能图标辅助色（低饱和，仅本页使用；Ai* 主色板见 AgentConfigComponents.kt）
private val AiAccentTeal = Color(0xFF2F8F89)
private val AiAccentPink = Color(0xFFEC4899)
private val AiAccentPurple = Color(0xFF8B5CF6)
private val AiAccentAmber = Color(0xFFF59E0B)
private val AiAccentBlue = Color(0xFF3B82F6)

/**
 * AgentConfigScreen — 现代 AI Agent 风格配置入口页
 *
 * 设计参考：ChatGPT Gpts / Character.AI / Apple Settings
 * - 背景 #F7F9F8，纯白卡片 + 24dp 大圆角 + 轻微阴影
 * - 主品牌色低饱和青绿 #2F8F89
 * - 顶部 Agent 信息卡增强 AI 身份感（头像 + AI 角标）
 * - 导入=主操作青绿填充，导出=次操作白底描边
 * - 功能列表 icon 辅助色区分（青绿/粉/紫/橙/蓝）
 */
@Composable
fun AgentConfigScreen(
    onBack: () -> Unit,
    onBasic: () -> Unit,
    onPersona: () -> Unit,
    onAvatar: () -> Unit,
    onVoice: () -> Unit,
    onBehavior: () -> Unit,
    onImport: () -> Unit = {},
    onExport: () -> Unit = {}
) {
    // 监听配置 Flow：导入后 provider 刷新 → config 更新 → UI 自动重组显示新数据
    val config by ServiceLocator.agentConfigProvider.config.collectAsState()
    val agent = config.agent

    // ===== SAF 导出/导入 launcher 接入 =====
    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            // 使用应用级 scope（SupervisorJob 不被 composition 生命周期取消）+ 主线程
            // 保证 Compose state 修改在主线程，避免线程安全崩溃
            ServiceLocator.appScope.launch(Dispatchers.Main) {
                exporting = true
                try {
                    val name = AgentConfigExporter().export(context, uri)
                    Toast.makeText(context, "已导出：$name", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("AgentConfigScreen", "导出失败", e)
                    Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    exporting = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            ServiceLocator.appScope.launch(Dispatchers.Main) {
                importing = true
                try {
                    val name = AgentImportManager(context).import(uri)
                    Toast.makeText(context, "已导入：$name", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("AgentConfigScreen", "导入失败", e)
                    Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    importing = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBg)
    ) {
        VibeTopBar(title = "Agent 配置", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {

        // ===== 当前 Agent 信息卡（增强 AI 身份感）=====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = AiShadowColor,
                    spotColor = AiShadowColor
                )
                .clip(RoundedCornerShape(24.dp))
                .background(AiCard)
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // AI 头像：优先显示头像管理第一张，无则渐变占位；右下角 AI 角标
                val avatarPath = remember(agent.avatars) {
                    agent.avatars.firstOrNull { it.file.isNotBlank() }?.file
                }
                val avatarBitmap = remember(avatarPath) {
                    avatarPath?.let { path ->
                        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
                    }
                }
                Box(
                    modifier = Modifier
                        .size(72.dp)
                ) {
                    // 头像主体：有图片显示图片，否则渐变占位 + Person 图标
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(AiPrimary, AiPrimaryDeep))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = avatarBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "Agent 头像",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.92f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    // 右下角 AI 角标（白底圆 + 青绿 "AI" 文字）
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .shadow(
                                elevation = 2.dp,
                                shape = CircleShape,
                                ambientColor = Color.Black.copy(alpha = 0.1f),
                                spotColor = Color.Black.copy(alpha = 0.1f)
                            )
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AI",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = AiPrimary,
                            letterSpacing = 0.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                // 右侧信息
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name.ifBlank { "未命名" },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AiTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // "使用中" 徽章 — 品牌青绿色（减少绿色滥用）
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AiPrimary.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "使用中",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AiPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    val genderText = when (agent.gender.lowercase()) {
                        "male" -> "男"
                        "female" -> "女"
                        else -> agent.gender.ifBlank { "未设置" }
                    }
                    val ageText = if (agent.age > 0) "${agent.age}岁" else "未知年龄"
                    Text(
                        text = "默认 Agent · $ageText · $genderText",
                        fontSize = 13.sp,
                        color = AiTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ===== 导入/导出按钮区 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 导入配置 — 主操作：柔和蓝渐变填充 + 白字
            val importBlue = Color(0xFF5B8DEF)
            val importBlueDeep = Color(0xFF3B7EA1)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .shadow(
                        elevation = 3.dp,
                        shape = RoundedCornerShape(16.dp),
                        ambientColor = importBlue.copy(alpha = 0.3f),
                        spotColor = importBlue.copy(alpha = 0.3f)
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(listOf(importBlue, importBlueDeep))
                    )
                    .clickable(enabled = !importing && !exporting) {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Upload,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "导入配置",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            // 导出配置 — 次操作：白底 + 柔和紫描边 + 柔和紫字
            val exportPurple = Color(0xFF9B8AC4)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AiCard)
                    .border(BorderStroke(1.5.dp, exportPurple.copy(alpha = 0.5f)), RoundedCornerShape(16.dp))
                    .clickable(enabled = !importing && !exporting) {
                        exportLauncher.launch("agent_${agent.name.ifBlank { "agent" }}.zip")
                    }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = exportPurple,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "导出配置",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = exportPurple
                )
            }
        }

        // ===== 配置入口列表（5 项） — 辅助色区分功能 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = AiShadowColor,
                    spotColor = AiShadowColor
                )
                .clip(RoundedCornerShape(24.dp))
                .background(AiCard)
        ) {
            ConfigEntryRow(
                icon = Icons.Default.ManageAccounts,
                title = "基础信息",
                subtitle = "名字、性别、称呼、背景故事",
                onClick = onBasic,
                showTopDivider = false,
                iconBgColor = AiAccentTeal.copy(alpha = 0.12f),
                iconTint = AiAccentTeal
            )
            ConfigEntryRow(
                icon = Icons.Default.Favorite,
                title = "人格设定",
                subtitle = "性格标签、说话风格、记忆、示例对话",
                onClick = onPersona,
                showTopDivider = true,
                iconBgColor = AiAccentPink.copy(alpha = 0.12f),
                iconTint = AiAccentPink
            )
            ConfigEntryRow(
                icon = Icons.Default.Image,
                title = "头像管理",
                subtitle = "上传多张头像，由 Agent 自行选用",
                onClick = onAvatar,
                showTopDivider = true,
                iconBgColor = AiAccentPurple.copy(alpha = 0.12f),
                iconTint = AiAccentPurple
            )
            ConfigEntryRow(
                icon = Icons.Default.Mic,
                title = "语音配置",
                subtitle = "音频样本、TTS 参数、导演模式",
                onClick = onVoice,
                showTopDivider = true,
                iconBgColor = AiAccentAmber.copy(alpha = 0.12f),
                iconTint = AiAccentAmber
            )
            ConfigEntryRow(
                icon = Icons.Default.Bolt,
                title = "行为配置",
                subtitle = "回复延迟、Emoji、主动发起",
                onClick = onBehavior,
                showTopDivider = true,
                iconBgColor = AiAccentBlue.copy(alpha = 0.12f),
                iconTint = AiAccentBlue
            )
        }

        // ===== 底部说明文字 =====
        Text(
            text = "首次使用时将加载默认 Agent 配置。可通过导入功能替换为自定义 Agent，或导出当前配置备份。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = AiTextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/**
 * 配置入口行：圆角图标 + 标题 + 副标题 + 右箭头
 *
 * 图标色由调用方传入（青绿/粉/紫/橙/蓝 区分功能）
 */
@Composable
private fun ConfigEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showTopDivider: Boolean,
    iconBgColor: Color,
    iconTint: Color
) {
    if (showTopDivider) {
        HorizontalDivider(
            thickness = 1.dp,
            color = AiBorder
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AiTextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = AiTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AiTextTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}
