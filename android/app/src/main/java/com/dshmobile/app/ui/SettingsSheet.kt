package com.dshmobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    baseUrl: String,
    profileLabel: String?,
    sessionCount: Int,
    version: String,
    notificationsEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    onManageConnections: () -> Unit,
    onReload: () -> Unit,
    onRefresh: () -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("连接", style = MaterialTheme.typography.titleLarge)
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
                text = "DSH Mobile 0.1.0 · M1",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
