package com.timemotion.remotehelp.ui.verification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.zIndex
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.HelpStage
import com.timemotion.remotehelp.webrtc.CallUiState
import com.timemotion.remotehelp.webrtc.VideoRendererBinding
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun VerificationScreen(
    side: DeviceSide,
    stage: HelpStage,
    helperName: String,
    elderName: String,
    uiState: CallUiState,
    onJoinClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onAcceptClick: () -> Unit,
    onRejectClick: () -> Unit,
    onContinueAssist: () -> Unit,
    onBackClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF6EFE3)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFF0E7D7), Color(0xFFE4EDF6))))
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CardBlock {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("身份验证", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
                        Text(stage.description, color = Color(0xFF526277))
                    }
                    if (side == DeviceSide.ELDER) {
                        OutlinedButton(onClick = onBackClick) {
                            Text("返回")
                        }
                    }
                }
            }

            CardBlock {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                    ) {
                        RendererPanel(
                            renderer = uiState.remoteRenderer,
                            placeholder = if (side == DeviceSide.ELDER) "等待子女画面接入" else "等待长辈画面接入",
                            modifier = Modifier.matchParentSize()
                        )
                        FloatingControlRail(
                            isMicEnabled = uiState.isMicEnabled,
                            isCameraEnabled = uiState.isCameraEnabled,
                            onToggleMic = onToggleMic,
                            onToggleCamera = onToggleCamera,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 12.dp)
                        )
                        SmallPreviewPanel(
                            renderer = uiState.localRenderer,
                            placeholder = "本机预览",
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .width(110.dp)
                                .aspectRatio(3f / 4f)
                        )
                    }
                    Text(
                        text = when {
                            uiState.isConnecting -> "正在接入视频通话"
                            uiState.isInRoom -> "已接入视频通话"
                            else -> "尚未接入视频通话"
                        },
                        color = Color(0xFF526277)
                    )
                    if (side == DeviceSide.ELDER) {
                        Text("正在请求：$helperName（您的子女）", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
                        Text("请确认画面中的人是您的孩子，再决定是否接受协助。", color = Color(0xFF526277))
                    } else {
                        Text("正在等待 $elderName 确认您的身份", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
                        Text("请保持画面清晰，等待长辈确认。", color = Color(0xFF526277))
                    }
                }
            }

            CardBlock {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (side == DeviceSide.ELDER) {
                        Button(
                            onClick = onAcceptClick,
                            enabled = uiState.isInRoom,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("接受协助")
                        }
                        OutlinedButton(onClick = onRejectClick, modifier = Modifier.fillMaxWidth()) {
                            Text("拒绝本次请求")
                        }
                    } else {
                        if (stage == HelpStage.VERIFIED) {
                            Button(onClick = onContinueAssist, modifier = Modifier.fillMaxWidth()) {
                                Text("进入远程协助")
                            }
                        }
                        OutlinedButton(onClick = onBackClick, modifier = Modifier.fillMaxWidth()) {
                            Text("返回当前会话")
                        }
                    }
                }
            }
        }
    }
}

private val CardPanelColor = Color(0xFFFFFCF8)
private val PlaceholderTextColor = Color(0xFF526277)
private val PlaceholderIconColor = Color(0xFFD7E2EC)

@Composable
private fun FloatingControlRail(
    isMicEnabled: Boolean,
    isCameraEnabled: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.zIndex(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        FloatingActionChip(
            title = if (isMicEnabled) "麦克风" else "已静音",
            subtitle = if (isMicEnabled) "关闭" else "打开",
            onClick = onToggleMic
        )
        FloatingActionChip(
            title = if (isCameraEnabled) "摄像头" else "已关闭",
            subtitle = if (isCameraEnabled) "关闭" else "打开",
            onClick = onToggleCamera
        )
    }
}

@Composable
private fun FloatingActionChip(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(96.dp)
            .height(64.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title)
            Text(subtitle, color = Color(0xFF526277))
        }
    }
}

@Composable
private fun SmallPreviewPanel(
    renderer: VideoRendererBinding?,
    placeholder: String,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    if (renderer == null) {
        Box(
            modifier = modifier.background(CardPanelColor, shape),
            contentAlignment = Alignment.Center
        ) {
            Text(placeholder, color = PlaceholderTextColor)
        }
        return
    }
    RendererView(renderer = renderer, modifier = modifier, overlay = true, shape = shape)
}

@Composable
private fun RendererPanel(
    renderer: VideoRendererBinding?,
    placeholder: String,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    if (renderer == null) {
        Box(
            modifier = modifier.background(CardPanelColor, shape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(PlaceholderIconColor, RoundedCornerShape(27.dp))
                )
                Text(placeholder, color = PlaceholderTextColor)
            }
        }
        return
    }
    RendererView(renderer = renderer, modifier = modifier, overlay = false, shape = shape)
}

@Composable
private fun RendererView(
    renderer: VideoRendererBinding,
    modifier: Modifier,
    overlay: Boolean,
    shape: RoundedCornerShape
) {
    val context = LocalContext.current
    val surfaceView = remember {
        SurfaceViewRenderer(context).apply {
            init(renderer.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(renderer.mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setZOrderMediaOverlay(overlay)
            setZOrderOnTop(overlay)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    DisposableEffect(renderer) {
        renderer.attach(surfaceView)
        onDispose { renderer.detach(surfaceView) }
    }

    Box(
        modifier = modifier
            .background(CardPanelColor, shape)
    ) {
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier
                .matchParentSize()
                .padding(if (overlay) 0.dp else 2.dp)
        )
    }
}

@Composable
private fun CardBlock(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFCF8))
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
