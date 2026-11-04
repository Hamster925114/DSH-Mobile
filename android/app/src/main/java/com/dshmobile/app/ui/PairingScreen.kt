package com.dshmobile.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dshmobile.app.data.ConnectionProfile
import com.dshmobile.app.data.PairingPayload
import com.dshmobile.app.data.baseUrlHint
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun PairingScreen(
    busy: Boolean,
    message: String?,
    initialBaseUrl: String,
    profileId: String?,
    profiles: List<ConnectionProfile>,
    onSelectProfile: (String) -> Unit,
    onPair: (baseUrl: String, code: String) -> Unit,
    onManageConnections: () -> Unit,
) {
    var baseUrl by rememberSaveable(profileId) { mutableStateOf(initialBaseUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedProfile = profiles.firstOrNull { it.id == profileId }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@rememberLauncherForActivityResult
        val payload = PairingPayload.parse(contents)
        if (payload == null) {
            scanError = "无法识别这个二维码，请手动输入地址和配对码"
        } else {
            baseUrl = payload.baseUrl
            code = payload.code
            scanError = null
            val matching = profiles.firstOrNull { it.baseUrl == payload.baseUrl }
            if (matching != null && matching.id != profileId) onSelectProfile(matching.id)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "DSH Mobile",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "远程控制桌面版 DeepSeek Harness",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "1. 在电脑上打开 DSH 的配对页，拿到配对码。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "2. 选择局域网 / Tailscale / frp + VPS，填入地址和配对码，或直接扫码。",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "连接方式",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profiles.forEach { profile ->
                    FilterChip(
                        selected = profile.id == profileId,
                        onClick = { onSelectProfile(profile.id) },
                        label = { Text(profile.label) },
                        enabled = !busy,
                    )
                }
            }
            Text(
                text = selectedProfile?.let { profile ->
                    if (profile.baseUrl.isBlank()) {
                        "${profile.kindLabel} · 尚未配置地址"
                    } else {
                        "${profile.kindLabel} · ${profile.baseUrl}"
                    }
                } ?: "未选择连接槽位；直接连接会保存为自定义连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    scanError = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("DSH 地址") },
                placeholder = {
                    Text(
                        selectedProfile?.let { baseUrlHint(it.kind) }
                            ?: "http://192.168.1.10:53985",
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                enabled = !busy,
            )

            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("配对码") },
                placeholder = { Text("16 位十六进制") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Go,
                ),
                enabled = !busy,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onPair(baseUrl, code) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    enabled = baseUrl.isNotBlank() && code.isNotBlank() && !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("连接")
                    }
                }

                OutlinedButton(
                    onClick = {
                        val options = ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("扫描 DSH Mobile 配对码")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                        scanLauncher.launch(options)
                    },
                    modifier = Modifier.height(52.dp),
                    enabled = !busy,
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("扫码")
                }
            }

            OutlinedButton(
                onClick = onManageConnections,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text("连接管理（添加 / 切换 / 测试）")
            }

            val error = message ?: scanError
            error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "地址示例",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "局域网：http://192.168.1.10:53985\n" +
                    "Tailscale：https://<machine>.<tailnet>.ts.net\n" +
                    "frp + VPS：https://dsh.example.com",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "配对码一次性有效，默认 120 秒。三种连接可以分别配对，之后在「连接管理」里切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
