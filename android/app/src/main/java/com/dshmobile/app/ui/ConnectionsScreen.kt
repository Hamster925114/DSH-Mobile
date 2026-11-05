package com.dshmobile.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dshmobile.app.data.ConnectionKind
import com.dshmobile.app.data.ConnectionProfile
import com.dshmobile.app.data.DiagnosticStep
import com.dshmobile.app.data.DshUrl
import com.dshmobile.app.data.baseUrlHint
import com.dshmobile.app.data.withDefaultScheme
import kotlinx.coroutines.launch

@Composable
fun ConnectionsScreen(
    profiles: List<ConnectionProfile>,
    activeProfileId: String?,
    onBack: () -> Unit,
    onActivate: (String) -> Unit,
    onSave: (ConnectionProfile) -> Unit,
    onDelete: (String) -> Unit,
    onTest: suspend (ConnectionProfile) -> Result<String>,
    onDiagnose: suspend (ConnectionProfile) -> List<DiagnosticStep>,
) {
    var editorTarget by remember { mutableStateOf<ConnectionProfile?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ConnectionProfile?>(null) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var diagnoseTarget by remember { mutableStateOf<ConnectionProfile?>(null) }
    var diagnoseResults by remember { mutableStateOf<List<DiagnosticStep>>(emptyList()) }
    var diagnoseRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val runDiagnostics: (ConnectionProfile) -> Unit = { profile ->
        diagnoseTarget = profile
        diagnoseResults = emptyList()
        diagnoseRunning = true
        scope.launch {
            diagnoseResults = onDiagnose(profile)
            diagnoseRunning = false
        }
    }

    BackHandler(onBack = onBack)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("连接管理", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "局域网 / Tailscale / frp + VPS 可同时保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        editorTarget = null
                        editorOpen = true
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加连接")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ConnectionCard(
                        profile = profile,
                        active = profile.id == activeProfileId,
                        testing = testingId == profile.id,
                        onActivate = { onActivate(profile.id) },
                        onTest = {
                            scope.launch {
                                testingId = profile.id
                                testMessage = onTest(profile).fold(
                                    onSuccess = { it },
                                    onFailure = { error ->
                                        "连接失败：" + (error.message ?: "未知错误")
                                    },
                                )
                                testingId = null
                            }
                        },
                        onEdit = {
                            editorTarget = profile
                            editorOpen = true
                        },
                        onDelete = { deleteTarget = profile },
                        onDiagnose = { runDiagnostics(profile) },
                    )
                }
            }
        }
    }

    if (editorOpen) {
        ConnectionEditorDialog(
            initial = editorTarget,
            onDismiss = { editorOpen = false },
            onSave = { profile ->
                onSave(profile)
                editorOpen = false
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除连接？") },
            text = { Text("将删除「${target.label}」的地址，不会影响其他连接。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        deleteTarget = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    testMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { testMessage = null },
            title = { Text("连接测试") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { testMessage = null }) { Text("关闭") }
            },
        )
    }

    diagnoseTarget?.let { target ->
        ConnectionDiagnosticsDialog(
            profile = target,
            running = diagnoseRunning,
            results = diagnoseResults,
            onRun = { runDiagnostics(target) },
            onDismiss = {
                if (!diagnoseRunning) diagnoseTarget = null
            },
        )
    }
}

@Composable
private fun ConnectionCard(
    profile: ConnectionProfile,
    active: Boolean,
    testing: Boolean,
    onActivate: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDiagnose: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = profile.kindLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active) {
                    Text(
                        text = "当前连接",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = profile.baseUrl.ifBlank { "尚未配置地址" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onActivate,
                    enabled = profile.baseUrl.isNotBlank() && !active,
                ) {
                    Text(if (active) "已使用" else "使用")
                }
                OutlinedButton(
                    onClick = onTest,
                    enabled = profile.baseUrl.isNotBlank() && !testing,
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text("测试")
                }
                TextButton(
                    onClick = onDiagnose,
                    enabled = profile.baseUrl.isNotBlank(),
                ) {
                    Text("诊断")
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun ConnectionEditorDialog(
    initial: ConnectionProfile?,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfile) -> Unit,
) {
    var label by remember(initial?.id) { mutableStateOf(initial?.label.orEmpty()) }
    var kind by remember(initial?.id) { mutableStateOf(initial?.kind ?: ConnectionKind.CUSTOM) }
    var baseUrl by remember(initial?.id) { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var error by remember(initial?.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加连接" else "编辑连接") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConnectionKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = {
                                Text(
                                    when (option) {
                                        ConnectionKind.LAN -> "局域网"
                                        ConnectionKind.TAILSCALE -> "Tailscale"
                                        ConnectionKind.FRP -> "frp + VPS"
                                        ConnectionKind.CUSTOM -> "自定义"
                                    },
                                )
                            },
                        )
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    placeholder = { Text("例如：家里局域网") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DSH 地址") },
                    placeholder = { Text(baseUrlHint(kind)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )

                Text(
                    text = when (kind) {
                        ConnectionKind.LAN -> "电脑和手机在同一 Wi-Fi；DSH 需要绑定电脑的局域网 IP。"
                        ConnectionKind.TAILSCALE -> "手机和电脑都加入同一个 Tailscale 网络。"
                        ConnectionKind.FRP -> "通过 VPS 上的 frp 和 HTTPS 域名访问。"
                        ConnectionKind.CUSTOM -> "任意可达的 http(s) 地址。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = if (baseUrl.isBlank()) {
                        ""
                    } else {
                        runCatching {
                            DshUrl.normalize(withDefaultScheme(kind, baseUrl))
                        }.getOrNull()
                    }
                    when {
                        label.isBlank() -> error = "名称不能为空"
                        baseUrl.isNotBlank() && normalized == null ->
                            error = "地址无效，请检查主机和端口"
                        else -> onSave(
                            ConnectionProfile(
                                id = initial?.id ?: "",
                                label = label.trim(),
                                kind = kind,
                                baseUrl = normalized.orEmpty(),
                            ),
                        )
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
