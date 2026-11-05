package com.dshmobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dshmobile.app.data.ConnectionProfile
import com.dshmobile.app.data.DiagnosticStatus
import com.dshmobile.app.data.DiagnosticStep

@Composable
fun ConnectionDiagnosticsDialog(
    profile: ConnectionProfile,
    running: Boolean,
    results: List<DiagnosticStep>,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("连接诊断", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${profile.label} · ${profile.baseUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                if (running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                } else {
                    val pass = results.count { it.status == DiagnosticStatus.PASS }
                    val fail = results.count { it.status == DiagnosticStatus.FAIL }
                    Text(
                        text = if (results.isEmpty()) "点击「开始诊断」检查整条链路。" else "通过 $pass 项 · 失败 $fail 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                HorizontalDivider()

                if (results.isEmpty() && !running) {
                    Text(
                        text = "诊断不会切换连接，也不会修改配置。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(results, key = { it.id }) { step ->
                            DiagnosticRow(step)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onRun,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (results.isEmpty()) "开始诊断" else "重新诊断")
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(step: DiagnosticStep) {
    val color = when (step.status) {
        DiagnosticStatus.PASS -> Color(0xFF2E7D32)
        DiagnosticStatus.WARN -> Color(0xFFB26A00)
        DiagnosticStatus.FAIL -> MaterialTheme.colorScheme.error
        DiagnosticStatus.SKIP -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val mark = when (step.status) {
        DiagnosticStatus.PASS -> "✓"
        DiagnosticStatus.WARN -> "!"
        DiagnosticStatus.FAIL -> "×"
        DiagnosticStatus.SKIP -> "–"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = mark,
                color = color,
                style = MaterialTheme.typography.titleMedium,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                step.hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
