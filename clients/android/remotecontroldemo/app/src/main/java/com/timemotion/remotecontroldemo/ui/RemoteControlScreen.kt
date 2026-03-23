package com.timemotion.remotecontroldemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.timemotion.remotecontroldemo.remote.RemoteAction
import com.timemotion.remotecontroldemo.remote.RemoteCommand
import com.timemotion.remotecontroldemo.remote.RemoteControlUiState
import com.timemotion.remotecontroldemo.remote.RemotePeer
import com.timemotion.remotecontroldemo.remote.RemoteRole
import com.timemotion.remotecontroldemo.remote.VideoRendererBinding
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun RemoteControlScreen(
    uiState: RemoteControlUiState,
    onServerUrlChanged: (String) -> Unit,
    onRoomIdChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onRoleSelected: (RemoteRole) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onRequestCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onCommand: (RemoteCommand) -> Unit,
    onQuickCommand: (RemoteCommand) -> Unit,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit
) {
    var isFullscreenViewerVisible by remember { mutableStateOf(false) }
    val videoAspectRatio = if (uiState.targetStatus.screenWidth > 0 && uiState.targetStatus.screenHeight > 0) {
        uiState.targetStatus.screenWidth.toFloat() / uiState.targetStatus.screenHeight.toFloat()
    } else {
        9f / 16f
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF04111D)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF020617), Color(0xFF082F49), Color(0xFF04111D))
                    )
                ),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "远程设备控制 Demo",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            item {
                Text(
                    text = "画面走 WebRTC，控制走 WebSocket。被控端负责屏幕采集和无障碍执行，操控端负责观看与下发指令。",
                    color = Color(0xFFBFDBFE)
                )
            }
            item {
                ConfigCard(
                    uiState = uiState,
                    onServerUrlChanged = onServerUrlChanged,
                    onRoomIdChanged = onRoomIdChanged,
                    onDisplayNameChanged = onDisplayNameChanged,
                    onRoleSelected = onRoleSelected,
                    onConnectClick = onConnectClick,
                    onDisconnectClick = onDisconnectClick,
                    onRequestCapture = onRequestCapture,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings
                )
            }
            item {
                VideoCard(
                    title = if (uiState.selectedRole == RemoteRole.CONTROLLER) "被控端实时画面" else "本机屏幕流预览",
                    renderer = if (uiState.selectedRole == RemoteRole.CONTROLLER) uiState.remoteRenderer else uiState.localRenderer,
                    allowTap = uiState.selectedRole == RemoteRole.CONTROLLER,
                    onFrameTap = onFrameTap,
                    onFrameSwipe = onFrameSwipe,
                    placeholder = if (uiState.selectedRole == RemoteRole.CONTROLLER) "等待 WebRTC 屏幕流" else "请先授权屏幕采集",
                    aspectRatio = videoAspectRatio,
                    onFullscreenRequest = if (uiState.selectedRole == RemoteRole.CONTROLLER) {
                        { isFullscreenViewerVisible = true }
                    } else {
                        null
                    }
                )
            }
            if (uiState.selectedRole == RemoteRole.TARGET) {
                item {
                    VideoCard(
                        title = "操控端回看",
                        renderer = uiState.remoteRenderer,
                        allowTap = false,
                        onFrameTap = onFrameTap,
                        onFrameSwipe = onFrameSwipe,
                        placeholder = "操控端加入后会看到远端渲染",
                        aspectRatio = videoAspectRatio,
                        onFullscreenRequest = null
                    )
                }
            }
            item {
                if (uiState.selectedRole == RemoteRole.CONTROLLER) {
                    ControllerActions(onCommand, onQuickCommand)
                } else {
                    TargetHelp(uiState)
                }
            }
            item {
                PresenceCard(uiState.peers, uiState.status)
            }
            item {
                LogsCard(uiState.logs)
            }
        }
    }
    if (isFullscreenViewerVisible && uiState.selectedRole == RemoteRole.CONTROLLER) {
        FullscreenRemoteViewer(
            renderer = uiState.remoteRenderer,
            aspectRatio = videoAspectRatio,
            onDismiss = { isFullscreenViewerVisible = false },
            onFrameTap = onFrameTap,
            onFrameSwipe = onFrameSwipe
        )
    }
}

@Composable
private fun ConfigCard(
    uiState: RemoteControlUiState,
    onServerUrlChanged: (String) -> Unit,
    onRoomIdChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onRoleSelected: (RemoteRole) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onRequestCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    DemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = onServerUrlChanged,
                label = { Text("WebSocket 服务地址") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isConnected
            )
            OutlinedTextField(
                value = uiState.roomId,
                onValueChange = onRoomIdChanged,
                label = { Text("房间号") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isConnected
            )
            OutlinedTextField(
                value = uiState.displayName,
                onValueChange = onDisplayNameChanged,
                label = { Text("设备名称") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isConnected
            )
            RoleButton(text = "操控端", selected = uiState.selectedRole == RemoteRole.CONTROLLER, modifier = Modifier.fillMaxWidth()) {
                onRoleSelected(RemoteRole.CONTROLLER)
            }
            RoleButton(text = "被控端", selected = uiState.selectedRole == RemoteRole.TARGET, modifier = Modifier.fillMaxWidth()) {
                onRoleSelected(RemoteRole.TARGET)
            }
            Text(text = "状态: ${uiState.status}", color = Color(0xFF0F172A))
            if (uiState.selectedRole == RemoteRole.TARGET) {
                Text(
                    text = "屏幕流: ${if (uiState.targetStatus.captureActive) "已启动" else "未启动"}  |  无障碍: ${if (uiState.targetStatus.accessibilityEnabled) "已开启" else "未开启"}",
                    color = Color(0xFF0F172A)
                )
                Text(text = uiState.targetStatus.message, color = Color(0xFF334155))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConnectClick, enabled = !uiState.isConnected) {
                    Text("连接房间")
                }
                OutlinedButton(onClick = onDisconnectClick, enabled = uiState.isConnected) {
                    Text("断开")
                }
            }
            if (uiState.selectedRole == RemoteRole.TARGET) {
                OutlinedButton(onClick = onRequestCapture, modifier = Modifier.fillMaxWidth()) {
                    Text("授权屏幕采集")
                }
                OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                    Text("开启无障碍服务")
                }
            }
        }
    }
}

@Composable
private fun VideoCard(
    title: String,
    renderer: VideoRendererBinding?,
    allowTap: Boolean,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit,
    placeholder: String,
    aspectRatio: Float,
    onFullscreenRequest: (() -> Unit)?
) {
    DemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                if (onFullscreenRequest != null) {
                    OutlinedButton(onClick = onFullscreenRequest) {
                        Text("全屏控制")
                    }
                }
            }
            if (renderer == null) {
                PlaceholderPanel(placeholder)
            } else {
                RemoteVideoPanel(
                    renderer = renderer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .background(Color.Black, RoundedCornerShape(20.dp)),
                    allowTap = allowTap,
                    aspectRatio = aspectRatio,
                    onFrameTap = onFrameTap,
                    onFrameSwipe = onFrameSwipe
                )
            }
            if (allowTap) {
                Text(text = "轻点发送点击，拖动发送滑动。建议使用全屏控制。", color = Color(0xFF475569))
            }
        }
    }
}

private data class ContentRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(offset: Offset): Boolean {
        return offset.x in left..right && offset.y in top..bottom
    }

    fun normalize(offset: Offset): Pair<Float, Float>? {
        if (!contains(offset) || width <= 0f || height <= 0f) {
            return null
        }
        val normalizedX = ((offset.x - left) / width).coerceIn(0f, 1f)
        val normalizedY = ((offset.y - top) / height).coerceIn(0f, 1f)
        return normalizedX to normalizedY
    }
}

private fun calculateContentRect(size: IntSize, aspectRatio: Float): ContentRect? {
    if (size.width <= 0 || size.height <= 0 || aspectRatio <= 0f) {
        return null
    }
    val containerWidth = size.width.toFloat()
    val containerHeight = size.height.toFloat()
    val containerRatio = containerWidth / containerHeight
    return if (containerRatio > aspectRatio) {
        val contentWidth = containerHeight * aspectRatio
        val left = (containerWidth - contentWidth) / 2f
        ContentRect(left = left, top = 0f, right = left + contentWidth, bottom = containerHeight)
    } else {
        val contentHeight = containerWidth / aspectRatio
        val top = (containerHeight - contentHeight) / 2f
        ContentRect(left = 0f, top = top, right = containerWidth, bottom = top + contentHeight)
    }
}

@Composable
private fun RemoteVideoPanel(
    renderer: VideoRendererBinding,
    modifier: Modifier,
    allowTap: Boolean,
    aspectRatio: Float,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit
) {
    val context = LocalContext.current
    val surfaceView = remember {
        SurfaceViewRenderer(context).apply {
            init(renderer.eglBaseContext, null)
            setEnableHardwareScaler(false)
            setMirror(renderer.mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setZOrderMediaOverlay(false)
        }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var currentSwipeStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var currentSwipeEnd by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    DisposableEffect(renderer) {
        renderer.attach(surfaceView)
        onDispose {
            renderer.detach(surfaceView)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .then(
                if (allowTap) {
                    Modifier
                        .pointerInput(renderer, aspectRatio, size) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val contentRect = calculateContentRect(size, aspectRatio)
                                    if (contentRect != null) {
                                        val normalizedStart = contentRect.normalize(startOffset)
                                        if (normalizedStart != null) {
                                            currentSwipeStart = normalizedStart
                                            currentSwipeEnd = normalizedStart
                                        }
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val contentRect = calculateContentRect(size, aspectRatio)
                                    if (contentRect != null && currentSwipeStart != null) {
                                        val normalizedEnd = contentRect.normalize(change.position)
                                        if (normalizedEnd != null) {
                                            currentSwipeEnd = normalizedEnd
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val start = currentSwipeStart
                                    val end = currentSwipeEnd
                                    currentSwipeStart = null
                                    currentSwipeEnd = null
                                    if (start != null && end != null && dragDistance(start, end) > 0.02f) {
                                        onFrameSwipe(start.first, start.second, end.first, end.second)
                                    }
                                },
                                onDragCancel = {
                                    currentSwipeStart = null
                                    currentSwipeEnd = null
                                }
                            )
                        }
                        .pointerInput(renderer, aspectRatio, size) {
                            detectTapGestures { offset ->
                                val contentRect = calculateContentRect(size, aspectRatio)
                                if (contentRect != null) {
                                    val normalized = contentRect.normalize(offset)
                                    if (normalized != null) {
                                        onFrameTap(normalized.first, normalized.second)
                                    }
                                }
                            }
                        }
                } else {
                    Modifier
                }
            )
    ) {
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun FullscreenRemoteViewer(
    renderer: VideoRendererBinding?,
    aspectRatio: Float,
    onDismiss: () -> Unit,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .systemBarsPadding()
                    .padding(12.dp)
            ) {
                val videoModifier = if (maxWidth / aspectRatio <= maxHeight) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                } else {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(aspectRatio)
                }

                if (renderer == null) {
                    PlaceholderPanel("等待远端画面")
                } else {
                    RemoteVideoPanel(
                        renderer = renderer,
                        modifier = videoModifier
                            .align(Alignment.Center)
                            .background(Color.Black, RoundedCornerShape(20.dp)),
                        allowTap = true,
                        aspectRatio = aspectRatio,
                        onFrameTap = onFrameTap,
                        onFrameSwipe = onFrameSwipe
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

private fun dragDistance(start: Pair<Float, Float>, end: Pair<Float, Float>): Float {
    val dx = end.first - start.first
    val dy = end.second - start.second
    return kotlin.math.sqrt((dx * dx) + (dy * dy))
}

@Composable
private fun ControllerActions(
    onCommand: (RemoteCommand) -> Unit,
    onQuickCommand: (RemoteCommand) -> Unit
) {
    var remoteText by remember { mutableStateOf("") }
    DemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "操控端快捷操作", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = remoteText,
                onValueChange = { remoteText = it },
                label = { Text("远程输入文本") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val text = remoteText.trim()
                    if (text.isNotEmpty()) {
                        onCommand(
                            RemoteCommand(
                                action = RemoteAction.INPUT_TEXT,
                                text = text,
                                dismissKeyboard = true
                            )
                        )
                        remoteText = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = remoteText.isNotBlank()
            ) {
                Text("发送文本并收起远端键盘")
            }
            Button(onClick = { onCommand(RemoteCommand(RemoteAction.BACK)) }, modifier = Modifier.fillMaxWidth()) {
                Text("返回")
            }
            Button(onClick = { onQuickCommand(RemoteCommand(RemoteAction.HOME)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Home")
            }
        }
    }
}

@Composable
private fun TargetHelp(uiState: RemoteControlUiState) {
    DemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "被控端准备步骤", fontWeight = FontWeight.SemiBold)
            Text(text = "1. 连接房间。")
            Text(text = "2. 授权屏幕采集，启动前台服务。")
            Text(text = "3. 开启无障碍服务。")
            Text(text = "4. 等待操控端加入并完成 WebRTC 协商。")
            Text(text = "当前消息: ${uiState.targetStatus.message}")
        }
    }
}

@Composable
private fun PresenceCard(peers: List<RemotePeer>, status: String) {
    DemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "房间成员", fontWeight = FontWeight.SemiBold)
            Text(text = "连接状态: $status")
            if (peers.isEmpty()) {
                Text(text = "暂无其他成员")
            } else {
                peers.forEach { peer ->
                    Text(text = "${peer.displayName} · ${peer.role.title}")
                }
            }
        }
    }
}

@Composable
private fun LogsCard(logs: List<String>) {
    DemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "事件日志", fontWeight = FontWeight.SemiBold)
            logs.forEach { log ->
                Text(text = "• $log")
            }
        }
    }
}

@Composable
private fun PlaceholderPanel(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .background(Color(0xFF0F172A), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFF0EA5E9), RoundedCornerShape(28.dp))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, color = Color.White)
        }
    }
}

@Composable
private fun RoleButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF0EA5E9) else Color(0xFFE2E8F0),
            contentColor = if (selected) Color.White else Color(0xFF0F172A)
        )
    ) {
        Text(text)
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
