package com.timemotion.remotehelp.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.formatDateTime
import com.timemotion.remotehelp.core.formatRemaining
import com.timemotion.remotehelp.ui.shared.VerificationRequestDialogHost
import kotlinx.coroutines.delay

@Composable
fun SessionRoute(viewModel: SessionViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val callState by viewModel.callState.collectAsState()
    val context = LocalContext.current
    val side = uiState.side
    val session = uiState.activeSession
    val bannerMessage = uiState.bannerMessage
    VerificationRequestDialogHost(
        uiState = uiState,
        onAcceptRequest = viewModel::acceptVerificationRequest,
        onRejectRequest = viewModel::rejectVerificationRequest,
        onLocationPermissionChanged = viewModel::updateHelperLocationPermissionGranted
    )
    var currentTime by remember(session?.requestId, session?.expiresAt) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(session?.requestId, session?.expiresAt) {
        val activeSession = session ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            currentTime = now
            if (now >= activeSession.expiresAt) {
                viewModel.onExpired()
                break
            }
            kotlinx.coroutines.delay(1000L)
        }
    }

    LaunchedEffect(
        session?.requestId,
        session?.expiresAt,
        callState.isInRoom,
        callState.isConnecting,
        side
    ) {
        val activeSession = session ?: return@LaunchedEffect
        if (side != DeviceSide.HELPER) {
            return@LaunchedEffect
        }
        if (activeSession.isExpired()) {
            return@LaunchedEffect
        }
        if (!callState.isInRoom && !callState.isConnecting) {
            viewModel.reconnectCurrentSessionRoom()
            delay(250L)
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF6EFE3)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFF0E7D6), Color(0xFFE6EDF7))))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SessionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (side == DeviceSide.HELPER) "当前会话" else "协助请求",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF183153)
                        )
                        Text("返回将结束这次会话", color = Color(0xFF526277))
                    }
                    OutlinedButton(
                        onClick = { viewModel.endCurrentSession("已取消本次请求") },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF183153)
                        )
                    ) {
                        Text(
                            text = "返回",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            bannerMessage?.let {
                SessionBannerCard(message = it, onDismiss = viewModel::dismissBanner)
            }
            session?.let {
                SessionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SessionInfoLine("当前阶段", it.stage.title)
                        SessionInfoLine("协助对象", "${it.elderName} · ${it.elderPhone}")
                        SessionInfoLine("剩余有效期", formatRemaining(it.expiresAt, currentTime))
                        if (side == DeviceSide.HELPER) {
                            SessionLinkPreviewLine("短信链接", it.deepLink.ifBlank { "房间连接中，稍后自动生成" })
                            Button(
                                onClick = { viewModel.onSendSms(context) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = it.deepLink.isNotBlank()
                            ) {
                                Text("发送短信")
                            }
                            OutlinedButton(
                                onClick = { viewModel.onCopyLink(context) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = it.deepLink.isNotBlank()
                            ) {
                                Text("复制短信链接")
                            }
                            Text("对方完成短信认证后，将自动进入视频验证。", color = Color(0xFF526277))
                        } else {
                            SessionInfoLine("协助方", it.helperName)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionBannerCard(message: String, onDismiss: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFFB44C3B),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = message,
                    color = Color(0xFFB44C3B),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB44C3B))
            ) {
                Text("关闭", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SessionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.padding(24.dp)) {
            content()
        }
    }
}

@Composable
private fun SessionInfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, color = Color(0xFF8A99A8), style = MaterialTheme.typography.labelMedium)
        Text(text = value, color = Color(0xFF183153), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SessionLinkPreviewLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, color = Color(0xFF8A99A8), style = MaterialTheme.typography.labelMedium)
        Surface(
            color = Color(0xFFF9FAFB),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = value,
                color = Color(0xFF526277),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
