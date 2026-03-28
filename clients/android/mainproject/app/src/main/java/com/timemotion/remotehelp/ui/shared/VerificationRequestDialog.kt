package com.timemotion.remotehelp.ui.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timemotion.remotehelp.core.ActiveHelpSession
import com.timemotion.remotehelp.core.formatRemaining
import kotlinx.coroutines.delay

@Composable
fun VerificationRequestDialog(
    session: ActiveHelpSession,
    expiresAt: Long,
    locationPermissionGranted: Boolean,
    onRequestLocationPermission: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    var currentTime by remember(session.requestId, expiresAt) {
        mutableStateOf(System.currentTimeMillis())
    }
    var actionHandled by remember(session.requestId, expiresAt) {
        mutableStateOf(false)
    }

    LaunchedEffect(session.requestId, expiresAt, actionHandled) {
        if (actionHandled) {
            return@LaunchedEffect
        }
        while (!actionHandled) {
            val now = System.currentTimeMillis()
            currentTime = now
            if (now >= expiresAt) {
                actionHandled = true
                onReject()
                break
            }
            delay(1000L)
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF215A6D),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("视频认证请求", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
            }
        },
        containerColor = Color.White,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    color = Color(0xFFFFF0F0),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFFFD6D6))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFB44C3B), modifier = Modifier.padding(end = 6.dp))
                            Text("留痕及权限授权通知", fontWeight = FontWeight.Bold, color = Color(0xFFB44C3B), style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            "协助过程中会每 10 秒采集一次前摄画面、定位和当前屏幕画面，作为被协助端本地留痕。\n\n点击“接受”即表示同意；随后会申请定位权限，若不授权定位，将视为不接受本次视频认证。",
                            color = Color(0xFFD32F2F),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                
                Surface(
                    color = Color(0xFFF9FAFB),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        InfoLine("协助对方", "${session.elderName} · ${session.elderPhone}")
                        InfoLine(
                            "剩余确认时间",
                            if (actionHandled) "已提交" else formatRemaining(expiresAt, currentTime),
                            isHighlight = true
                        )
                        InfoLine(
                            "当前定位权限",
                            if (locationPermissionGranted) "已授权" else "未授权",
                            isWarning = !locationPermissionGranted
                        )
                    }
                }
                
                if (!locationPermissionGranted) {
                    OutlinedButton(
                        onClick = onRequestLocationPermission,
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF215A6D)),
                        border = BorderStroke(1.dp, Color(0xFF215A6D))
                    ) {
                        Text("去授权定位", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    actionHandled = true
                    onAccept()
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF215A6D))
            ) {
                Text("接受请求", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    actionHandled = true
                    onReject()
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF526277))
            ) {
                Text("拒绝")
            }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String, isHighlight: Boolean = false, isWarning: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color(0xFF526277), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value, 
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                isWarning -> Color(0xFFB44C3B)
                isHighlight -> Color(0xFF215A6D)
                else -> Color(0xFF183153)
            }
        )
    }
}
