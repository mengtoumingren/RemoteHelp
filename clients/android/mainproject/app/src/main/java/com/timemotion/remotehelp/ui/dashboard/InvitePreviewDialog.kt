package com.timemotion.remotehelp.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        title = { Text("确认协助链接信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("请先核对协助链接中的信息，确认无误后再进入视频验证。")
                SessionInfoLine("协助方", session.helperName)
                SessionInfoLine("剩余有效时间", if (isExpired) "已过期" else formatRemaining(session.expiresAt, currentTime))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !isExpired) {
                Text("进入视频验证")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun SessionInfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label)
        Text(text = value)
    }
}
