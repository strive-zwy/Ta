package com.agent.ta.ui.screens.profile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agent.ta.di.ServiceLocator

/**
 * 权限引导页
 *
 * V1 真实跳转：
 * 1. 通知权限（Android 13+）：运行时申请 POST_NOTIFICATIONS
 * 2. 电池优化：跳转系统电池优化设置，可加入白名单
 * 3. 自启动/锁定后台：跳转应用详情页（不同 ROM 入口不同，统一跳详情页让用户手动加锁）
 */
@Composable
fun PermissionGuideScreen(onCompleted: () -> Unit) {
    val context = LocalContext.current
    // 订阅 AgentConfig 变化，文案中的 Agent 名字随配置更新
    val agentConfig by ServiceLocator.agentConfigProvider.config.collectAsState()
    val agentName = agentConfig.agent.name.ifBlank { "小雅" }

    var notificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var batteryOptimizedOut by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    // 通知权限运行时申请
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
    }

    // 电池优化设置页跳转返回
    val batterySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptimizedOut = isIgnoringBatteryOptimizations(context)
    }

    // 应用详情页跳转返回
    val appDetailsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptimizedOut = isIgnoringBatteryOptimizations(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "开启后台运行权限",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "为保证$agentName 能在后台正常生活作息，请完成以下设置：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 1. 通知权限（仅 Android 13+ 需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                title = "通知权限",
                status = if (notificationGranted) "已开启" else "未开启",
                done = notificationGranted,
                buttonText = if (notificationGranted) "已开启" else "去开启"
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 2. 电池优化白名单
        PermissionCard(
            title = "电池优化",
            status = if (batteryOptimizedOut) "已加入白名单" else "已被电池优化（可能被杀）",
            done = batteryOptimizedOut,
            buttonText = if (batteryOptimizedOut) "已设置" else "去设置"
        ) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                batterySettingsLauncher.launch(intent)
            } catch (e: Exception) {
                // 某些 ROM 不支持直接弹窗，跳转电池优化列表页
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                batterySettingsLauncher.launch(intent)
            }
        }

        // 3. 应用详情页（自启动 / 锁定后台）
        PermissionCard(
            title = "自启动 / 锁定后台",
            status = "请在应用详情页手动开启自启动并锁定后台",
            done = false,
            buttonText = "去应用详情"
        ) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            appDetailsLauncher.launch(intent)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "提示：部分国产 ROM（小米/华为/OPPO/vivo 等）需要在「自启动管理」中手动允许$agentName 自启动，并在最近任务列表中锁定$agentName 卡片，才能保证长期后台运行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onCompleted,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("完成，开始聊天")
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    status: String,
    done: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (done) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧文案占满剩余空间，避免挤压按钮
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // 按钮固定宽度，避免随文字长度变形
            Button(
                onClick = onClick,
                enabled = !done,
                modifier = Modifier
                    .defaultMinSize(minWidth = 96.dp)
                    .height(42.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (done) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Text(buttonText)
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }
    return true
}
