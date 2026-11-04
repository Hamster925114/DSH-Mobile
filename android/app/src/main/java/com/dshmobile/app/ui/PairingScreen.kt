package com.dshmobile.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.dshmobile.app.data.PairingPayload
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun PairingScreen(
    busy: Boolean,
    message: String?,
    initialBaseUrl: String,
    onPair: (baseUrl: String, code: String) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }

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

            Spacer(Modifier.height(16.dp))
            Text(
                text = "1. 在电脑上打开 DSH 的配对页，拿到配对码。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "2. 填入 Tailscale / frp 地址和配对码，或直接扫码。",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    scanError = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("DSH 地址") },
                placeholder = { Text("https://machine.tailnet.ts.net") },
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

            val error = message ?: scanError
            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
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
                text = "Tailscale：https://<machine>.<tailnet>.ts.net\n" +
                    "frp：https://dsh.example.com\n" +
                    "局域网调试：http://192.168.1.10:53985",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "配对码一次性有效，默认 120 秒。若提示 410，请在桌面重新生成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
