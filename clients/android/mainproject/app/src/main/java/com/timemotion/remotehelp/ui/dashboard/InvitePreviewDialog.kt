package com.timemotion.remotehelp.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
fun InvitePreviewDialog(
    session: ActiveHelpSession,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var currentTime by remember(session.requestId, session.expiresAt) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(session.requestId, session.expiresAt) {
        while (true) {
            val now = System.currentTimeMillis()
            currentTime = now
            if (now >= session.expiresAt) {
                break
            }
            delay(1000L)
        }
    }
    val isExpired = currentTime >= session.expiresAt
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFFE5ECF6),
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info, 
                        contentDescription = null, 
                        tint = Color(0xFF215A6D),
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Text("确认协助链接信息", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "请先核对协助链接中的信息，确认无误后再进入视频验证环节。", 
                    color = Color(0xFF526277),
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Surface(
                    color = Color(0xFFF9FAFB),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SessionInfoLine("协助方名称", session.helperName)
                        SessionInfoLine(
                            "剩余有效时间", 
                            if (isExpired) "已过期" else formatRemaining(session.expiresAt, currentTime),
                            isHighlight = !isExpired,
                            isExpired = isExpired
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm, 
                enabled = !isExpired,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF215A6D))
            ) {
                Text("进入视频验证", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF526277))
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun SessionInfoLine(label: String, value: String, isHighlight: Boolean = false, isExpired: Boolean = false) {
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
                isExpired -> Color(0xFFB44C3B)
                isHighlight -> Color(0xFF215A6D)
                else -> Color(0xFF183153)
            }
        )
    }
}
