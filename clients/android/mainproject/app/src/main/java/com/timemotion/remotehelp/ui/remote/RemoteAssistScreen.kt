package com.timemotion.remotehelp.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.remote.RemoteCommand
import com.timemotion.remotehelp.remote.RemoteControlUiState
import com.timemotion.remotehelp.remote.RemoteRole
import com.timemotion.remotehelp.remote.VideoRendererBinding as ControlBinding
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun RemoteAssistScreen(
    side: DeviceSide,
    helperName: String,
    elderName: String,
    uiState: RemoteControlUiState,
    onBackClick: () -> Unit,
    onEndClick: () -> Unit,
    onRequestCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit
) {
    val screenAspectRatio = if (uiState.targetStatus.screenWidth > 0 && uiState.targetStatus.screenHeight > 0) {
        uiState.targetStatus.screenWidth.toFloat() / uiState.targetStatus.screenHeight.toFloat()
    } else {
        9f / 16f
    }
    val controllerName = uiState.peers.firstOrNull { it.role == RemoteRole.CONTROLLER }?.displayName ?: helperName
    var isMoreMenuVisible by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF6EFE3)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFF0E7D7), Color(0xFFE4EDF6))))
                .systemBarsPadding()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CardBlock(contentPadding = 10.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (side == DeviceSide.HELPER) "正在协助：$elderName" else "正在协助：$helperName",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF183153)
                    )
                    if (side == DeviceSide.HELPER) {
                        Box {
                            OutlinedButton(
                                onClick = { isMoreMenuVisible = true },
                                modifier = Modifier.size(width = 58.dp, height = 34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF183153))
                            ) {
                                Text("更多")
                            }
                            DropdownMenu(
                                expanded = isMoreMenuVisible,
                                onDismissRequest = { isMoreMenuVisible = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("返回首页") },
                                    onClick = {
                                        isMoreMenuVisible = false
                                        onBackClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("结束协助") },
                                    onClick = {
                                        isMoreMenuVisible = false
                                        onEndClick()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (side == DeviceSide.HELPER) {
                HelperAssistBody(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                    screenRenderer = uiState.remoteRenderer,
                    screenAspectRatio = screenAspectRatio,
                    onFrameTap = onFrameTap,
                    onFrameSwipe = onFrameSwipe
                )
            } else {
                ElderAssistBody(
                    controllerName = controllerName,
                    targetStatus = uiState.targetStatus.message,
                    captureActive = uiState.targetStatus.captureActive,
                    accessibilityEnabled = uiState.targetStatus.accessibilityEnabled,
                    onRequestCapture = onRequestCapture,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    isConnected = uiState.isConnected,
                    onConnectClick = onConnectClick,
                    onDisconnectClick = onDisconnectClick,
                    onEndClick = onEndClick
                )
            }
        }
    }
}

@Composable
private fun HelperAssistBody(
    modifier: Modifier,
    screenRenderer: ControlBinding?,
    screenAspectRatio: Float,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit
) {
    CardBlock(modifier = modifier, contentPadding = 8.dp) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize()
                    .padding(bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(screenAspectRatio, matchHeightConstraintsFirst = true)
                ) {
                    ControlRendererPanel(
                        renderer = screenRenderer,
                        placeholder = "等待长辈屏幕画面",
                        aspectRatio = screenAspectRatio,
                        modifier = Modifier.matchParentSize(),
                        allowTouch = true,
                        onFrameTap = onFrameTap,
                        onFrameSwipe = onFrameSwipe
                    )
                }
            }
        }
    }
}

@Composable
private fun ElderAssistBody(
    controllerName: String,
    targetStatus: String,
    captureActive: Boolean,
    accessibilityEnabled: Boolean,
    onRequestCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    isConnected: Boolean,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onEndClick: () -> Unit
) {
    CardBlock {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("正在接受远程协助", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
            Text("当前协助人：$controllerName", color = Color(0xFF526277))
            Text("通知栏会显示当前正在协助您的人，误触退出后也可以从通知栏确认状态。", color = Color(0xFF526277))
            Text(targetStatus, color = Color(0xFF526277))
            Text("屏幕共享：${if (captureActive) "已开启" else "未开启"}", color = Color(0xFF526277))
            Text("无障碍服务：${if (accessibilityEnabled) "已开启" else "未开启"}", color = Color(0xFF526277))
            Text("请保持当前页面开启；完成屏幕采集和无障碍授权后，子女端即可继续操作。", color = Color(0xFF526277))
            if (!isConnected) {
                Button(onClick = onConnectClick, modifier = Modifier.fillMaxWidth()) {
                    Text("连接协助通道")
                }
            } else {
                OutlinedButton(onClick = onDisconnectClick, modifier = Modifier.fillMaxWidth()) {
                    Text("断开协助通道")
                }
            }
            OutlinedButton(onClick = onRequestCapture, modifier = Modifier.fillMaxWidth()) {
                Text("授权屏幕采集")
            }
            OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                Text("开启无障碍服务")
            }
            OutlinedButton(onClick = onEndClick, modifier = Modifier.fillMaxWidth()) {
                Text("结束本次协助")
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

    fun normalize(offset: Offset): Pair<Float, Float>? {
        if (offset.x !in left..right || offset.y !in top..bottom || width <= 0f || height <= 0f) {
            return null
        }
        return ((offset.x - left) / width).coerceIn(0f, 1f) to ((offset.y - top) / height).coerceIn(0f, 1f)
    }
}

private fun calculateContentRect(size: IntSize, aspectRatio: Float): ContentRect? {
    if (size.width <= 0 || size.height <= 0 || aspectRatio <= 0f) return null
    val containerWidth = size.width.toFloat()
    val containerHeight = size.height.toFloat()
    val containerRatio = containerWidth / containerHeight
    return if (containerRatio > aspectRatio) {
        val contentWidth = containerHeight * aspectRatio
        val left = (containerWidth - contentWidth) / 2f
        ContentRect(left, 0f, left + contentWidth, containerHeight)
    } else {
        val contentHeight = containerWidth / aspectRatio
        val top = (containerHeight - contentHeight) / 2f
        ContentRect(0f, top, containerWidth, top + contentHeight)
    }
}

@Composable
private fun ControlRendererPanel(
    renderer: ControlBinding?,
    placeholder: String,
    aspectRatio: Float,
    modifier: Modifier,
    allowTouch: Boolean,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit
) {
    if (renderer == null) {
        Box(
            modifier = modifier.background(Color(0xFF203040), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(placeholder, color = Color.White)
        }
        return
    }
    val context = LocalContext.current
    val surfaceView = remember {
        SurfaceViewRenderer(context).apply {
            init(renderer.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(renderer.mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var swipeStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var swipeEnd by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    DisposableEffect(renderer) {
        renderer.attach(surfaceView)
        onDispose { renderer.detach(surfaceView) }
    }

    Box(
        modifier = modifier
            .background(Color.Black, RoundedCornerShape(20.dp))
            .onSizeChanged { size = it }
            .then(
                if (allowTouch) {
                    Modifier
                        .pointerInput(renderer, size) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    swipeStart = calculateContentRect(size, aspectRatio)?.normalize(offset)
                                    swipeEnd = swipeStart
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    swipeEnd = calculateContentRect(size, aspectRatio)?.normalize(change.position)
                                },
                                onDragEnd = {
                                    val start = swipeStart
                                    val end = swipeEnd
                                    swipeStart = null
                                    swipeEnd = null
                                    if (start != null && end != null) {
                                        onFrameSwipe(start.first, start.second, end.first, end.second)
                                    }
                                },
                                onDragCancel = {
                                    swipeStart = null
                                    swipeEnd = null
                                }
                            )
                        }
                        .pointerInput(renderer, size) {
                            detectTapGestures { offset ->
                                val normalized = calculateContentRect(size, aspectRatio)?.normalize(offset)
                                if (normalized != null) {
                                    onFrameTap(normalized.first, normalized.second)
                                }
                            }
                        }
                } else {
                    Modifier
                }
            )
    ) {
        AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CardBlock(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFCF8))
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
