package com.dshmobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dshmobile.app.BuildConfig

@Composable
fun SettingsSheet(
    baseUrl: String,
    profileLabel: String?,
    sessionCount: Int,
    version: String,
    notificationsEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    onManageConnections: () -> Unit,
    onManageDevices: () -> Unit,
    onReload: () -> Unit,
    onRefresh: () -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showPrivacy by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .imePadding(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }

                Text("连接", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = profileLabel ?: "未命名连接",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "会话 $sessionCount 个 · bridge $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                        Text("刷新会话数")
                    }
                    OutlinedButton(onClick = onReload, modifier = Modifier.weight(1f)) {
                        Text("重新加载")
                    }
                }

                OutlinedButton(
                    onClick = onManageConnections,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("连接管理（局域网 / Tailscale / frp）")
                }
                Text(
                    text = "在连接管理里点每条连接的「诊断」，会依次检查 DNS、TCP、TLS、HTTP、Cookie、API 和 WebSocket。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = onManageDevices,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("设备管理（查看 / 撤销设备）")
                }

                OutlinedButton(
                    onClick = { showPrivacy = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("隐私说明")
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("后台事件通知", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "仅前台服务；App 被杀或被强停后不保证收到。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = onToggleNotifications,
                    )
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = onUnpair,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("清除配对")
                }

                Text(
                    text = "DSH Mobile ${BuildConfig.VERSION_NAME} · M4",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("隐私说明") },
            text = {
                Text(
                    "DSH Mobile 不包含广告或统计 SDK。连接地址、Cookie、加密设备令牌只保存在本机；" +
                        "桌面端保存设备令牌的 SHA-256 和审计日志。下载文件写入公共 Download 目录。" +
                        "清除配对会删除本机 Cookie 和设备令牌。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) { Text("关闭") }
            },
        )
    }
}
