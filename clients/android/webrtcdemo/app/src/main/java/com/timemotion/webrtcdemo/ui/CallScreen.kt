package com.timemotion.webrtcdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.timemotion.webrtcdemo.webrtc.CallUiState
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(
    uiState: CallUiState,
    onServerUrlChanged: (String) -> Unit,
    onRoomIdChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onJoinClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF020617)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF082F49), Color(0xFF020617))
                    )
                ),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "WebRTC 音视频通话",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            item {
                Text(
                    text = "STUN: stun:stun.timemotion.top:3478",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFBAE6FD)
                )
            }
            item {
                ConfigCard(
                    uiState = uiState,
                    onServerUrlChanged = onServerUrlChanged,
                    onRoomIdChanged = onRoomIdChanged,
                    onDisplayNameChanged = onDisplayNameChanged,
                    onJoinClick = onJoinClick,
                    onLeaveClick = onLeaveClick
                )
            }
            item {
                VideoCard(
                    title = "远端画面",
                    renderer = uiState.remoteRenderer
                )
            }
            item {
                VideoCard(
                    title = "本地预览",
                    renderer = uiState.localRenderer
                )
            }
            item {
                ControlCard(
                    uiState = uiState,
                    onToggleMic = onToggleMic,
                    onToggleCamera = onToggleCamera
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(
    uiState: CallUiState,
    onServerUrlChanged: (String) -> Unit,
    onRoomIdChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onJoinClick: () -> Unit,
    onLeaveClick: () -> Unit
) {
    DemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = onServerUrlChanged,
                label = { Text("信令 WebSocket 地址") },
                placeholder = { Text("ws://10.0.2.2:3000/ws") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isInRoom
            )
            OutlinedTextField(
                value = uiState.roomId,
                onValueChange = onRoomIdChanged,
                label = { Text("房间 ID") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isInRoom
            )
            OutlinedTextField(
                value = uiState.displayName,
                onValueChange = onDisplayNameChanged,
                label = { Text("昵称") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isInRoom
            )
            Text(
                text = "状态: ${uiState.status}",
                color = Color(0xFF0F172A)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onJoinClick,
                    enabled = !uiState.isInRoom && !uiState.isConnecting
                ) {
                    Text("加入房间")
                }
                OutlinedButton(
                    onClick = onLeaveClick,
                    enabled = uiState.isInRoom || uiState.isConnecting
                ) {
                    Text("挂断")
                }
            }
        }
    }
}

@Composable
private fun VideoCard(
    title: String,
    renderer: VideoRendererBinding?
) {
    DemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            if (renderer == null) {
                PlaceholderVideo(title = if (title == "远端画面") "等待对端加入" else "正在准备相机")
            } else {
                RendererView(renderer = renderer)
            }
        }
    }
}

@Composable
private fun ControlCard(
    uiState: CallUiState,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit
) {
    DemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "设备控制", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("麦克风")
                Switch(checked = uiState.isMicEnabled, onCheckedChange = { onToggleMic() })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("摄像头")
                Switch(checked = uiState.isCameraEnabled, onCheckedChange = { onToggleCamera() })
            }
            if (uiState.remotePeerName.isNotBlank()) {
                Text(text = "对端: ${uiState.remotePeerName}")
            }
        }
    }
}

@Composable
private fun DemoCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun PlaceholderVideo(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color(0xFF0F172A), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF0EA5E9), RoundedCornerShape(24.dp))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, color = Color.White)
        }
    }
}

@Composable
private fun RendererView(renderer: VideoRendererBinding) {
    val context = LocalContext.current
    val surfaceView = remember {
        SurfaceViewRenderer(context).apply {
            init(renderer.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(renderer.mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
    }

    DisposableEffect(renderer) {
        renderer.attach(surfaceView)
        onDispose {
            renderer.detach(surfaceView)
        }
    }

    AndroidView(
        factory = { surfaceView },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, RoundedCornerShape(20.dp))
    )
}

data class VideoRendererBinding(
    val eglBaseContext: org.webrtc.EglBase.Context,
    val mirror: Boolean,
    val attach: (SurfaceViewRenderer) -> Unit,
    val detach: (SurfaceViewRenderer) -> Unit
)
