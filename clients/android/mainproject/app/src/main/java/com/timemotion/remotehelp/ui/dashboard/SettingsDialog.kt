package com.timemotion.remotehelp.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFF0E7D6))))
                    .padding(start = 20.dp, top = 34.dp, end = 20.dp, bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color(0xFF183153))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "连接设置", 
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF183153)
                            )
                            Text(
                                text = "基础配置及开发调试工具",
                                color = Color(0xFF526277),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(24.dp),
                        spotColor = Color(0xFF183153).copy(alpha = 0.08f),
                        ambientColor = Color(0xFF183153).copy(alpha = 0.04f)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF215A6D), modifier = Modifier.padding(end = 8.dp))
                            Text("基础配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF183153))
                        }
                        OutlinedTextField(
                            value = uiState.serverUrl,
                            onValueChange = onServerUrlChange,
                            label = { Text("信令服务地址") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF215A6D),
                                focusedLabelColor = Color(0xFF215A6D)
                            ),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = uiState.helperName,
                            onValueChange = onHelperNameChange,
                            label = { Text("默认协助方姓名") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF215A6D),
                                focusedLabelColor = Color(0xFF215A6D)
                            ),
                            singleLine = true
                        )
                        
                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF526277), modifier = Modifier.padding(end = 8.dp))
                                Text(
                                    text = "模拟器一般使用 ws://10.0.2.2:3000/ws，真机联调请改成宿主机局域网 IP，例如 ws://192.168.2.109:3000/ws。",
                                    color = Color(0xFF526277),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(24.dp),
                        spotColor = Color(0xFF183153).copy(alpha = 0.08f),
                        ambientColor = Color(0xFF183153).copy(alpha = 0.04f)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFF215A6D), modifier = Modifier.padding(end = 8.dp))
                            Text("开发工具", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF183153))
                        }
                        if (narrow) {
                            OutlinedButton(
                                onClick = onOpenLogs,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                Text("查看运行日志", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
                            }
                            OutlinedButton(
                                onClick = onOpenEvidence,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                Text("查看留痕证据", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedButton(
                                    onClick = onOpenLogs,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                                    contentPadding = PaddingValues(vertical = 14.dp)
                                ) {
                                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                    Text("查看运行日志", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
                                }
                                OutlinedButton(
                                    onClick = onOpenEvidence,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                                    contentPadding = PaddingValues(vertical = 14.dp)
                                ) {
                                    Text("查看留痕证据", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
                                }
                            }
                        }
                    }
                }

                if (narrow) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                        Button(
                            onClick = onSave,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF215A6D)),
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            Text("保存并应用配置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF526277)),
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            Text("取消", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF526277)),
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            Text("取消", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        Button(
                            onClick = onSave,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF215A6D)),
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            Text("保存并应用配置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}
