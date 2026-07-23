package com.agent.ta.ui.screens.agent

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agent.ta.data.model.AvatarConfig
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 把 SAF Uri 指向的图片复制到内部存储，返回绝对路径。
 * 存放位置：filesDir/avatars/<avatarId>/avatar_<timestamp>.jpg
 */
private fun copyUriToInternal(context: Context, uri: Uri, avatarId: String): String? {
    return try {
        val dir = File(context.filesDir, "avatars/$avatarId").apply { mkdirs() }
        val fileName = "avatar_${System.currentTimeMillis()}.jpg"
        val destFile = File(dir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: return null
        destFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

/**
 * 头像管理页面（AI Agent Studio 风格）
 *
 * 用户只需上传多张头像图；具体选用哪张由 Agent 在运行时自行决定。
 * 不再要求用户配置状态 / 关键词 / 情绪绑定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentAvatarScreen(onBack: () -> Unit) {
    val editor = ServiceLocator.agentConfigEditor
    val scope = rememberCoroutineScope()

    val avatars = remember {
        ServiceLocator.agentConfigProvider.get().agent.avatars.toMutableStateList()
    }

    var showDialog by remember { mutableStateOf(false) }
    var editingAvatar by remember { mutableStateOf<AvatarConfig?>(null) }
    var justSaved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiBg)
    ) {
        VibeTopBar(title = "头像管理", onBack = onBack)
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
                Text(
                    text = "上传多张头像，Agent 会根据语境自行挑选使用",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = AiTextSecondary
                )

                Spacer(Modifier.height(2.dp))

                avatars.forEachIndexed { index, avatar ->
                    AvatarCardItem(
                        avatar = avatar,
                        index = index + 1,
                        onEdit = {
                            editingAvatar = avatar
                            showDialog = true
                        },
                        onDelete = { avatars.removeAt(index) }
                    )
                }

                if (avatars.isEmpty()) {
                    EmptyAvatarHint()
                }

                Spacer(Modifier.height(80.dp))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 16.dp, bottom = 88.dp)
                .size(56.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(50),
                    ambientColor = AiPrimary.copy(alpha = 0.3f),
                    spotColor = AiPrimary.copy(alpha = 0.3f)
                )
                .clip(RoundedCornerShape(50))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(AiPrimary, AiPrimaryDeep)
                    )
                )
                .clickable {
                    editingAvatar = null
                    showDialog = true
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "添加头像",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        AiSaveButton(
            justSaved = justSaved,
            enabled = true,
            onClick = {
                scope.launch {
                    // 保存时去掉状态/关键词/情绪绑定，交由 Agent 自由选用
                    val freeAvatars = avatars.map { av ->
                        av.copy(
                            bindMood = null,
                            bindState = null,
                            triggerKeywords = emptyList(),
                            emotionMapping = emptyList()
                        )
                    }
                    editor.update { config ->
                        config.copy(
                            agent = config.agent.copy(avatars = freeAvatars)
                        )
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

    if (showDialog) {
        AddEditAvatarDialog(
            initialAvatar = editingAvatar,
            onDismiss = {
                showDialog = false
                editingAvatar = null
            },
            onConfirm = { newAvatar ->
                if (editingAvatar != null) {
                    val index = avatars.indexOfFirst { it.id == editingAvatar?.id }
                    if (index >= 0) {
                        avatars[index] = newAvatar
                    } else {
                        avatars.add(newAvatar)
                    }
                } else {
                    avatars.add(newAvatar)
                }
                showDialog = false
                editingAvatar = null
            }
        )
    }
}

/**
 * 单个头像卡片：仅预览图 + 编辑/删除
 */
@Composable
private fun AvatarCardItem(
    avatar: AvatarConfig,
    index: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = AiShadowColor,
                spotColor = AiShadowColor
            )
            .clip(RoundedCornerShape(24.dp))
            .background(AiCard)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(AiPrimary, AiPrimaryDeep)
                    )
                )
                .clickable { onEdit() },
            contentAlignment = Alignment.Center
        ) {
            val bitmap = remember(avatar.file) {
                if (avatar.file.isNotBlank()) {
                    try {
                        BitmapFactory.decodeFile(avatar.file)
                    } catch (e: Exception) {
                        null
                    }
                } else null
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(32.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "更换",
                        fontSize = 10.sp,
                        color = Color.White
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "头像 $index",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AiTextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (avatar.file.isNotBlank()) "Agent 可自行选用" else "未上传图片",
                fontSize = 12.sp,
                color = AiTextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "更换图片",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AiPrimary,
                    modifier = Modifier.clickable { onEdit() }
                )
                Text(
                    text = "删除",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { onDelete() }
                )
            }
        }
    }
}

@Composable
private fun EmptyAvatarHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = AiShadowColor,
                spotColor = AiShadowColor
            )
            .clip(RoundedCornerShape(24.dp))
            .background(AiCard)
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = AiTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "暂无头像，点击右下角 + 添加",
                fontSize = 13.sp,
                color = AiTextSecondary
            )
        }
    }
}

/**
 * 添加/编辑头像弹窗：只上传图片，不再配置状态/关键词/情绪
 */
@Composable
private fun AddEditAvatarDialog(
    initialAvatar: AvatarConfig?,
    onDismiss: () -> Unit,
    onConfirm: (AvatarConfig) -> Unit
) {
    val context = LocalContext.current
    val isEdit = initialAvatar != null
    var imagePath by remember { mutableStateOf(initialAvatar?.file ?: "") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val avatarId = initialAvatar?.id?.takeIf { it.isNotBlank() }
                ?: System.currentTimeMillis().toString(16)
            val path = copyUriToInternal(context, uri, avatarId)
            if (path != null) {
                imagePath = path
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = AiCard
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEdit) "更换头像" else "添加头像",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AiTextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(50))
                                .background(AiInputBg)
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = AiTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "只需上传图片，Agent 会在对话中自行决定何时使用",
                        fontSize = 12.sp,
                        color = AiTextSecondary
                    )
                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "头像图片",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AiTextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AiInputBg)
                            .border(
                                width = 2.dp,
                                color = AiBorder,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { imagePicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (imagePath.isBlank()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Upload,
                                    contentDescription = null,
                                    tint = AiTextSecondary,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "点击选择图片",
                                    fontSize = 13.sp,
                                    color = AiTextSecondary
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "支持 JPG / PNG，建议正方形",
                                    fontSize = 11.sp,
                                    color = AiTextTertiary
                                )
                            }
                        } else {
                            val previewBitmap = remember(imagePath) {
                                try {
                                    BitmapFactory.decodeFile(imagePath)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (previewBitmap != null) {
                                Image(
                                    bitmap = previewBitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = AiTextSecondary,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "图片加载失败，点击重新选择",
                                        fontSize = 13.sp,
                                        color = AiTextSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    val canSave = imagePath.isNotBlank()
                    val saveGradient = Brush.linearGradient(
                        colors = listOf(AiPrimary, AiPrimaryDeep)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                brush = if (canSave) saveGradient
                                else Brush.linearGradient(listOf(AiInputBg, AiInputBg))
                            )
                            .clickable(enabled = canSave) {
                                val avatarId = initialAvatar?.id?.takeIf { it.isNotBlank() }
                                    ?: System.currentTimeMillis().toString(16)
                                // 不绑定状态/关键词/情绪，交由 Agent 自由选用
                                onConfirm(
                                    AvatarConfig(
                                        id = avatarId,
                                        file = imagePath,
                                        bindMood = null,
                                        bindState = null,
                                        triggerKeywords = emptyList(),
                                        emotionMapping = emptyList()
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "保存头像",
                            color = if (canSave) Color.White else AiTextSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }
}
