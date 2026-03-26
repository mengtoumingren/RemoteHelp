package com.timemotion.remotehelp.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.timemotion.remotehelp.core.RemoteHelpUiState

@Composable
fun SettingsDialog(
    uiState: RemoteHelpUiState,
    onServerUrlChange: (String) -> Unit,
    onHelperNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showLogs by remember { mutableStateOf(false) }
    var showEvidence by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = uiState.serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text("信令服务地址") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uiState.helperName,
                    onValueChange = onHelperNameChange,
                    label = { Text("默认协助方姓名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "提示：模拟器一般使用 `ws://10.0.2.2:3000/ws`，真机联调请改成宿主机局域网 IP，例如 `ws://192.168.2.109:3000/ws`。",
                    color = Color(0xFF526277)
                )
                OutlinedButton(
                    onClick = { showLogs = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看日志")
                }
                OutlinedButton(
                    onClick = { showEvidence = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看证据")
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("保存")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
    if (showLogs) {
        LogBrowserDialog(onDismiss = { showLogs = false })
    }
    if (showEvidence) {
        EvidenceBrowserDialog(onDismiss = { showEvidence = false })
    }
}
