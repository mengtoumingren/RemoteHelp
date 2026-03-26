package com.timemotion.remotehelp.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.timemotion.remotehelp.core.ActiveHelpSession
import com.timemotion.remotehelp.core.formatRemaining

@Composable
fun VerificationRequestDialog(
    session: ActiveHelpSession,
    locationPermissionGranted: Boolean,
    onRequestLocationPermission: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("视频认证请求") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "协助过程中会定时采集前摄画面、定位等信息，作为被协助端本地留痕。点击“接受”即表示同意；随后会申请定位权限，若不授权定位，将视为不接受本次视频认证。",
                    color = Color(0xFF526277)
                )
                InfoLine("协助对象", "${session.elderName} · ${session.elderPhone}")
                InfoLine("剩余有效期", formatRemaining(session.expiresAt))
                InfoLine(
                    "当前定位权限",
                    if (locationPermissionGranted) "已授权" else "未授权"
                )
                if (!locationPermissionGranted) {
                    OutlinedButton(
                        onClick = onRequestLocationPermission,
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Text("去授权定位")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("接受")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject) {
                Text("拒绝")
            }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label)
        Text(text = value)
    }
}
