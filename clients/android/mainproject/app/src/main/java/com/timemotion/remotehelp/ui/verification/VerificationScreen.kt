package com.timemotion.remotehelp.ui.verification

import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.HelpStage
import com.timemotion.remotehelp.webrtc.CallUiState
import com.timemotion.remotehelp.webrtc.VideoRendererBinding
import kotlinx.coroutines.delay
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
    onToggleSpeaker: () -> Unit,
    onAcceptClick: () -> Unit,
    onRejectClick: () -> Unit,
    onRemoteVideoTimeoutConfirm: () -> Unit,
    onContinueAssist: () -> Unit,
    onBackClick: () -> Unit
) {
    var acceptCooldownRemaining by remember(side, stage, uiState.remoteRenderer) { mutableStateOf(15) }
    var remoteVideoTimeoutRemaining by remember(side, stage, uiState.remoteRenderer) { mutableStateOf(5 * 60) }
    var showRemoteVideoTimeoutDialog by remember(side, uiState.remoteRenderer, stage) { mutableStateOf(false) }
    LaunchedEffect(side, stage, uiState.remoteRenderer) {
        if (side != DeviceSide.ELDER || stage != HelpStage.VERIFYING || uiState.remoteRenderer == null) {
            acceptCooldownRemaining = 15
            return@LaunchedEffect
        }
        acceptCooldownRemaining = 15
        while (acceptCooldownRemaining > 0) {
            delay(1000L)
            acceptCooldownRemaining -= 1
        }
    }
    LaunchedEffect(side, stage, uiState.remoteRenderer) {
        showRemoteVideoTimeoutDialog = false
        if (side != DeviceSide.ELDER || stage != HelpStage.VERIFYING || uiState.remoteRenderer != null) {
            remoteVideoTimeoutRemaining = 5 * 60
            return@LaunchedEffect
        }
        remoteVideoTimeoutRemaining = 5 * 60
        while (remoteVideoTimeoutRemaining > 0) {
            delay(1000L)
            remoteVideoTimeoutRemaining -= 1
        }
        if (uiState.remoteRenderer == null && remoteVideoTimeoutRemaining == 0) {
            showRemoteVideoTimeoutDialog = true
        }
    }

    if (showRemoteVideoTimeoutDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("认证失败") },
            text = {
                Text("对方长时间未接入视频画面，认证失败，即将退出视频认证。")
            },
            confirmButton = {
                Button(onClick = onRemoteVideoTimeoutConfirm) {
                    Text("确认")
                }
            }
        )
    }

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
                        OutlinedButton(
                            onClick = onBackClick,
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
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                }
            }

            CardBlock(contentPadding = 0.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                    ) {
                        RendererPanel(
                            renderer = uiState.remoteRenderer,
                            placeholder = if (side == DeviceSide.ELDER) {
                                "等待协助方确认接入（${remoteVideoTimeoutRemaining}s 后超时退出）"
                            } else {
                                "等待对方画面接入"
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        VerificationInfoOverlay(
                            statusText = when {
                                uiState.isConnecting -> "正在接入视频通话"
                                uiState.isInRoom -> "已接入视频通话"
                                else -> "尚未接入视频通话"
                            },
                            titleText = if (side == DeviceSide.ELDER) {
                                "协助申请方：$helperName"
                            } else {
                                "正在等待 $elderName 确认您的身份"
                            },
                            detailLines = if (side == DeviceSide.ELDER) {
                                listOf(
                                    OverlayHintLine(
                                        text = "防诈骗提醒：如对方催促转账、索要验证码或要求共享敏感信息，请立即拒绝。",
                                        color = OverlayWarnTextColor
                                    ),
                                    OverlayHintLine(
                                        text = "核验建议：先看周围环境，再让对方转头、转一圈，并说几句熟悉的家乡话。",
                                        color = OverlayGuideTextColor
                                    ),
                                    OverlayHintLine(
                                        text = "如仍不放心，请挂断后用您平时保存的号码主动回拨确认。",
                                        color = OverlayNeutralTextColor
                                    )
                                )
                            } else {
                                listOf(
                                    OverlayHintLine(
                                        text = "请保持画面清晰，并配合展示环境、转头或说几句熟悉的话，方便对方确认身份。",
                                        color = OverlayGuideTextColor
                                    )
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                        )
                    }
                    VerificationControlPanel(
                        localRenderer = uiState.localRenderer,
                        isMicEnabled = uiState.isMicEnabled,
                        isCameraEnabled = uiState.isCameraEnabled,
                        isSpeakerOn = uiState.isSpeakerOn,
                        onToggleMic = onToggleMic,
                        onToggleCamera = onToggleCamera,
                        onToggleSpeaker = onToggleSpeaker
                    )
                }
            }

            CardBlock {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (side == DeviceSide.ELDER) {
                        Text(
                            text = if (uiState.remoteRenderer == null) {
                                "已发送视频认证请求，等待协助方确认接入。"
                            } else if (acceptCooldownRemaining > 0) {
                                "请先观察对方画面，剩余 ${acceptCooldownRemaining}s 后才能接受协助。"
                            } else {
                                "请确认对方身份和沟通内容无异常后，再决定是否接受协助。"
                            },
                            color = Color(0xFF526277)
                        )
                        Button(
                            onClick = onAcceptClick,
                            enabled = uiState.remoteRenderer != null && acceptCooldownRemaining == 0,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                if (uiState.remoteRenderer == null) {
                                    "等待画面接入"
                                } else if (acceptCooldownRemaining > 0) {
                                    "确认身份中 ${acceptCooldownRemaining}s"
                                } else {
                                    "接受协助"
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = onRejectClick,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("拒绝本次请求")
                        }
                    } else {
                        if (stage == HelpStage.VERIFIED) {
                            Button(
                                onClick = onContinueAssist,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("进入远程协助")
                            }
                        }
                        OutlinedButton(
                            onClick = onBackClick,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
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
private val ControlButtonShape = RoundedCornerShape(12.dp)
private val ControlPanelHeight = 100.dp
private val ControlItemGap = 10.dp
private val OverlayGradientTop = Color(0x14122131)
private val OverlayGradientBottom = Color(0xCC122131)
private val OverlayWarnTextColor = Color(0xFFFFC58E)
private val OverlayGuideTextColor = Color(0xFFB8E7B7)
private val OverlayNeutralTextColor = Color(0xFFE3EBF2)

private data class OverlayHintLine(
    val text: String,
    val color: Color
)

@Composable
private fun VerificationControlPanel(
    localRenderer: VideoRendererBinding?,
    isMicEnabled: Boolean,
    isCameraEnabled: Boolean,
    isSpeakerOn: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
    ) {
        val itemWidth = (maxWidth - ControlItemGap * 3) / 4
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ControlPanelHeight),
            horizontalArrangement = Arrangement.spacedBy(ControlItemGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallPreviewPanel(
                renderer = localRenderer,
                placeholder = "本机预览",
                modifier = Modifier
                    .width(itemWidth)
                    .height(ControlPanelHeight)
            )
            VerificationControlButton(
                modifier = Modifier
                    .width(itemWidth)
                    .height(ControlPanelHeight),
                title = "麦克风",
                subtitle = if (isMicEnabled) "已开启" else "已关闭",
                active = isMicEnabled,
                onClick = onToggleMic
            )
            VerificationControlButton(
                modifier = Modifier
                    .width(itemWidth)
                    .height(ControlPanelHeight),
                title = "摄像头",
                subtitle = if (isCameraEnabled) "已开启" else "已关闭",
                active = isCameraEnabled,
                onClick = onToggleCamera
            )
            VerificationControlButton(
                modifier = Modifier
                    .width(itemWidth)
                    .height(ControlPanelHeight),
                title = "声音外放",
                subtitle = if (isSpeakerOn) "已开启" else "已关闭",
                active = isSpeakerOn,
                onClick = onToggleSpeaker
            )
        }
    }
}

@Composable
private fun VerificationControlButton(
    modifier: Modifier,
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    if (active) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = ControlButtonShape,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFDCE7E4),
                contentColor = Color(0xFF27424C)
            )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title)
                Text(subtitle, color = Color(0xFF5E7480), textAlign = TextAlign.Center)
            }
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = ControlButtonShape,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xFFF7F4EE),
                contentColor = Color(0xFF6A7680)
            )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title)
                Text(subtitle, color = Color(0xFF526277), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun VerificationInfoOverlay(
    statusText: String,
    titleText: String,
    detailLines: List<OverlayHintLine>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(OverlayGradientTop, OverlayGradientBottom)
                )
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(statusText, color = Color(0xFFD8E4EE))
            Text(titleText, color = Color.White, fontWeight = FontWeight.SemiBold)
            detailLines.forEach { line ->
                Text(line.text, color = line.color)
            }
        }
    }
}

@Composable
private fun SmallPreviewPanel(
    renderer: VideoRendererBinding?,
    placeholder: String,
    modifier: Modifier
) {
    val cornerRadius = 12.dp
    val shape = RoundedCornerShape(cornerRadius)
    if (renderer == null) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(CardPanelColor, shape)
                .border(1.dp, Color(0xFFE0D6C7), shape),
            contentAlignment = Alignment.Center
        ) {
            Text(placeholder, color = PlaceholderTextColor)
        }
        return
    }
    RendererView(
        renderer = renderer,
        modifier = modifier.border(1.dp, Color(0xFFE0D6C7), shape),
        overlay = false,
        cornerRadius = cornerRadius
    )
}

@Composable
private fun RendererPanel(
    renderer: VideoRendererBinding?,
    placeholder: String,
    modifier: Modifier
) {
    val cornerRadius = 20.dp
    val shape = RoundedCornerShape(cornerRadius)
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
    RendererView(renderer = renderer, modifier = modifier, overlay = false, cornerRadius = cornerRadius)
}

@Composable
private fun RendererView(
    renderer: VideoRendererBinding,
    modifier: Modifier,
    overlay: Boolean,
    cornerRadius: Dp
) {
    val context = LocalContext.current
    val cornerRadiusPx = remember(cornerRadius, context) { with(context.resources.displayMetrics) { cornerRadius.value * density } }
    val outlineProvider = remember(cornerRadiusPx) {
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }
    val surfaceView = remember {
        SurfaceViewRenderer(context).apply {
            init(renderer.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(renderer.mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setZOrderMediaOverlay(overlay)
            setZOrderOnTop(overlay)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            clipToOutline = true
            this.outlineProvider = outlineProvider
        }
    }

    DisposableEffect(renderer) {
        renderer.attach(surfaceView)
        onDispose { renderer.detach(surfaceView) }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(CardPanelColor, RoundedCornerShape(cornerRadius))
    ) {
        AndroidView(
            factory = {
                FrameLayout(it).apply {
                    clipToOutline = true
                    this.outlineProvider = outlineProvider
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    (surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
                    addView(
                        surfaceView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(if (overlay) 0.dp else 2.dp),
            update = {
                it.clipToOutline = true
                it.outlineProvider = outlineProvider
                if (surfaceView.parent !== it) {
                    (surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
                    it.removeAllViews()
                    it.addView(
                        surfaceView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                surfaceView.clipToOutline = true
                surfaceView.outlineProvider = outlineProvider
                surfaceView.setMirror(renderer.mirror)
            }
        )
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
