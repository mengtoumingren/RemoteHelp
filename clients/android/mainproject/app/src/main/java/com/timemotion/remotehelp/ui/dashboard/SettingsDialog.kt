package com.timemotion.remotehelp.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.timemotion.remotehelp.core.RemoteHelpUiState

@Composable
fun SettingsPage(
    uiState: RemoteHelpUiState,
    onServerUrlChange: (String) -> Unit,
    onHelperNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenEvidence: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF6EFE3)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val narrow = maxWidth < 560.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, top = 34.dp, end = 20.dp, bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("连接设置")
                        Text(
                            text = "设置、日志、证据已拆成独立页面",
                            color = Color(0xFF526277)
                        )
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text("返回")
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("基础配置")
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
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("跳转页面")
                        if (narrow) {
                            OutlinedButton(
                                onClick = onOpenLogs,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("日志")
                            }
                            OutlinedButton(
                                onClick = onOpenEvidence,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("证据")
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = onOpenLogs,
                                    modifier = Modifier.fillMaxWidth(0.48f)
                                ) {
                                    Text("日志")
                                }
                                OutlinedButton(
                                    onClick = onOpenEvidence,
                                    modifier = Modifier.fillMaxWidth(0.48f)
                                ) {
                                    Text("证据")
                                }
                            }
                        }
                    }
                }

                if (narrow) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(0.48f)
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = onSave,
                            modifier = Modifier.fillMaxWidth(0.48f)
                        ) {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }
}
