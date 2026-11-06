package com.dshmobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dshmobile.app.data.AuditEvent
import com.dshmobile.app.data.DeviceInfo
import com.dshmobile.app.data.DshApi
import kotlinx.coroutines.launch

@Composable
fun DevicesDialog(
    baseUrl: String,
    onDismiss: () -> Unit,
    onCurrentDeviceRevoked: () -> Unit,
) {
    val context = LocalContext.current
    val api = remember { DshApi(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var confirmRevoke by remember { mutableStateOf<DeviceInfo?>(null) }
    var showAudit by remember { mutableStateOf(false) }
    val ownId = remember { api.currentDeviceId(baseUrl) }

    fun reload() {
        scope.launch {
            error = null
            api.listDevices(baseUrl)
                .onSuccess { devices = it }
                .onFailure { error = it.message ?: "设备列表读取失败" }
        }
    }

    LaunchedEffect(baseUrl) { reload() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f)
                .imePadding(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "设备管理",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showAudit = true }) { Text("审计日志") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                HorizontalDivider()
                val current = devices
                when {
                    error != null -> Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    current == null -> Text(
                        text = "正在读取设备…",
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    current.isEmpty() -> Text(
                        text = "还没有已登记设备",
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(current, key = { it.id }) { device ->
                            DeviceRow(
                                device = device,
                                isCurrent = device.id == ownId,
                                busy = busyId == device.id,
                                onRevoke = {
                                    if (device.revoked) return@DeviceRow
                                    if (device.id == ownId) {
                                        confirmRevoke = device
                                    } else {
                                        busyId = device.id
                                        scope.launch {
                                            api.revokeDevice(baseUrl, device.id)
                                                .onSuccess { reload() }
                                                .onFailure { error = it.message ?: "撤销失败" }
                                            busyId = null
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                Text(
                    text = "设备令牌保存在 Android Keystore。撤销后该设备的下一次请求会立即被拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedButton(
                    onClick = { reload() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text("刷新")
                }
            }
        }
    }

    if (showAudit) {
        AuditDialog(baseUrl = baseUrl, onDismiss = { showAudit = false })
    }

    val pending = confirmRevoke
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text("撤销本机设备？") },
            text = { Text("撤销后本机需要重新扫码配对，当前登录会被清除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRevoke = null
                        busyId = pending.id
                        scope.launch {
                            api.revokeDevice(baseUrl, pending.id)
                                .onSuccess {
                                    onCurrentDeviceRevoked()
                                    onDismiss()
                                }
                                .onFailure { error = it.message ?: "撤销失败" }
                            busyId = null
                        }
                    },
                ) { Text("撤销并退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    isCurrent: Boolean,
    busy: Boolean,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name + if (isCurrent) "（本机）" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = listOfNotNull(
                    device.platform.ifBlank { null },
                    device.appVersion.ifBlank { null },
                    device.lastSeenAt?.let { "最近 ${it.take(16).replace('T', ' ')}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device.revoked) {
                Text(
                    text = "已撤销",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!device.revoked) {
            TextButton(onClick = onRevoke, enabled = !busy) {
                Text(if (busy) "处理中…" else "撤销")
            }
        }
    }
}

@Composable
private fun AuditDialog(baseUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val api = remember { DshApi(context.applicationContext) }
    var events by remember { mutableStateOf<List<AuditEvent>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(baseUrl) {
        api.listAudit(baseUrl)
            .onSuccess { events = it }
            .onFailure { error = it.message ?: "审计日志读取失败" }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.78f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "审计日志",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                HorizontalDivider()
                when {
                    error != null -> Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    events == null -> Text(
                        text = "正在读取审计日志…",
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    events!!.isEmpty() -> Text(
                        text = "还没有审计记录",
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(events!!) { event ->
                            AuditRow(event)
                        }
                    }
                }
                Text(
                    text = "记录配对、登录/续期、撤销、重命名和拒绝访问；不包含令牌或 Cookie。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun AuditRow(event: AuditEvent) {
    val label = when (event.event) {
        "pair" -> "配对"
        "login" -> "登录 / 续期"
        "revoke" -> "撤销"
        "rename" -> "重命名"
        "denied" -> "拒绝访问"
        else -> event.event
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "$label · ${event.ts.take(19).replace('T', ' ')}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = listOfNotNull(
                event.deviceName ?: event.deviceId,
                event.remote,
                event.detail,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
