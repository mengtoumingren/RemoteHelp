package com.timemotion.remotehelp.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.timemotion.remotehelp.R
import com.timemotion.remotehelp.core.ActiveHelpSession
import com.timemotion.remotehelp.core.AppScreen
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.HelpStage
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.core.RecentContact
import com.timemotion.remotehelp.core.SessionHistoryItem
import com.timemotion.remotehelp.core.formatDateTime
import com.timemotion.remotehelp.core.formatRemaining
import com.timemotion.remotehelp.core.shouldBroadcastVerificationRequested
import com.timemotion.remotehelp.core.shouldJoinHiddenVerificationRoom
import com.timemotion.remotehelp.core.shouldJoinVisibleVerificationRoom
import com.timemotion.remotehelp.core.shouldPrepareVerificationMedia
import com.timemotion.remotehelp.core.shouldShowAssistPermissionPrompt
import com.timemotion.remotehelp.core.shouldShowVerificationRequestDialog
import com.timemotion.remotehelp.core.shouldListenForAssistExit
import com.timemotion.remotehelp.remote.RemoteControlUiState
import com.timemotion.remotehelp.remote.RemoteRole
import com.timemotion.remotehelp.ui.remote.RemoteAssistScreen
import com.timemotion.remotehelp.ui.verification.VerificationScreen
import com.timemotion.remotehelp.webrtc.CallUiState
import com.timemotion.remotehelp.webrtc.VideoRendererBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun RemoteHelpApp(
    coordinator: RemoteHelpCoordinator,
    uiState: RemoteHelpUiState,
    callState: CallUiState,
    remoteState: RemoteControlUiState,
    projectionIntent: Intent
) {
    val context = LocalContext.current
    val latestUiState by rememberUpdatedState(uiState)
    val latestCallState by rememberUpdatedState(callState)
    var joinAfterPermission by remember { mutableStateOf(false) }
    var mediaPermissionsGranted by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) }
    var assistPermissionPromptVisible by remember { mutableStateOf(false) }
    var assistPermissionPromptSessionId by remember { mutableStateOf<String?>(null) }
    var openAccessibilitySettingsAfterCaptureSessionId by remember { mutableStateOf<String?>(null) }
    var elderControllerSeenSessionId by remember { mutableStateOf<String?>(null) }
    var elderControllerExitHandledSessionId by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { permissions ->
        mediaPermissionsGranted = permissions.values.all { it }
        if (!mediaPermissionsGranted) {
            Toast.makeText(context, context.getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        } else {
            coordinator.callController.startLocalMedia()
            if (joinAfterPermission) {
                coordinator.callController.joinRoom()
            }
        }
        joinAfterPermission = false
    }
    val projectionLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            coordinator.remoteController.startTargetCapture(result.resultCode, result.data!!)
            if (
                openAccessibilitySettingsAfterCaptureSessionId != null &&
                !remoteState.targetStatus.accessibilityEnabled
            ) {
                openAccessibilitySettingsAfterCaptureSessionId = null
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else {
            coordinator.remoteController.onCapturePermissionDenied()
            openAccessibilitySettingsAfterCaptureSessionId = null
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        notificationPermissionGranted = granted
        if (!granted) {
            Toast.makeText(context, "未授予通知权限时，通知栏可能无法显示常驻协助通知", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        UiFeedbackBus.topToasts.collect { message ->
            showTopToast(context, message)
        }
    }

    LaunchedEffect(
        uiState.currentScreen,
        uiState.side,
        uiState.activeSession?.requestId,
        uiState.activeSession?.stage
    ) {
        if (uiState.shouldPrepareVerificationMedia() && !mediaPermissionsGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    LaunchedEffect(
        uiState.currentScreen,
        uiState.side,
        uiState.activeSession?.requestId,
        uiState.activeSession?.stage,
        mediaPermissionsGranted,
        callState.isInRoom,
        callState.isConnecting
    ) {
        if (uiState.shouldJoinHiddenVerificationRoom() && !callState.isInRoom && !callState.isConnecting) {
            coordinator.callController.joinRoom(prepareLocalMedia = false)
        } else if (uiState.shouldJoinVisibleVerificationRoom()) {
            if (uiState.side == DeviceSide.HELPER) {
                if (mediaPermissionsGranted) {
                    coordinator.callController.startLocalMedia()
                    if (!callState.isInRoom && !callState.isConnecting) {
                        coordinator.callController.joinRoom()
                    }
                }
            } else {
                if (mediaPermissionsGranted) {
                    coordinator.callController.startLocalMedia()
                }
                if (!callState.isInRoom && !callState.isConnecting) {
                    coordinator.callController.joinRoom(prepareLocalMedia = false)
                }
            }
        }
    }

    LaunchedEffect(
        uiState.currentScreen,
        uiState.side,
        uiState.activeSession?.requestId,
        uiState.activeSession?.expiresAt
    ) {
        val session = uiState.activeSession ?: return@LaunchedEffect
        if (
            uiState.side == DeviceSide.HELPER &&
            uiState.currentScreen == AppScreen.SESSION &&
            session.isExpired()
        ) {
            coordinator.endCurrentSession("短信链接已过期，请重新发起协助")
        }
    }

    LaunchedEffect(
        uiState.side,
        uiState.currentScreen,
        uiState.pendingInviteSession?.requestId,
        uiState.activeSession?.requestId,
        uiState.activeSession?.stage,
        callState.isInRoom,
        callState.roomParticipantCount
    ) {
        if (
            uiState.side == DeviceSide.HELPER &&
            uiState.activeSession?.stage == HelpStage.REQUEST_CREATED &&
            callState.isInRoom &&
            callState.roomParticipantCount > 1
        ) {
            coordinator.cancelHelperWaitTimeout()
        }
        if (
            uiState.side == DeviceSide.ELDER &&
            callState.isInRoom &&
            callState.roomParticipantCount <= 1
        ) {
            if (uiState.pendingInviteSession != null) {
                coordinator.expirePendingInvite("链接已过期，请重新发起协助")
            } else if (
                uiState.currentScreen == AppScreen.VERIFICATION &&
                uiState.activeSession?.stage == HelpStage.VERIFYING
            ) {
                coordinator.failVerificationDueToRemoteTimeout()
            }
        }
    }

    LaunchedEffect(
        uiState.currentScreen,
        uiState.side,
        notificationPermissionGranted,
        remoteState.targetStatus.captureActive
    ) {
        if (
            uiState.currentScreen == AppScreen.ASSIST &&
            uiState.side == DeviceSide.ELDER &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            remoteState.targetStatus.captureActive &&
            !notificationPermissionGranted
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(
        uiState.side,
        uiState.currentScreen,
        uiState.activeSession?.requestId,
        uiState.activeSession?.stage,
        callState.isInRoom
    ) {
        if (!uiState.shouldBroadcastVerificationRequested() || !callState.isInRoom) {
            return@LaunchedEffect
        }
        while (
            latestUiState.shouldBroadcastVerificationRequested() &&
                latestCallState.isInRoom
        ) {
            coordinator.notifyVerificationRequested()
            delay(2000L)
        }
    }

    LaunchedEffect(
        uiState.side,
        uiState.currentScreen,
        uiState.activeSession?.requestId,
        remoteState.peers,
        remoteState.isConnected
    ) {
        if (!uiState.shouldListenForAssistExit()) {
            elderControllerSeenSessionId = null
            elderControllerExitHandledSessionId = null
            return@LaunchedEffect
        }
        val sessionId = uiState.activeSession?.requestId
            ?: return@LaunchedEffect
        val hasControllerPeer = remoteState.peers.any { it.role == RemoteRole.CONTROLLER }
        if (hasControllerPeer) {
            elderControllerSeenSessionId = sessionId
            elderControllerExitHandledSessionId = null
        } else if (
            elderControllerSeenSessionId == sessionId &&
            elderControllerExitHandledSessionId != sessionId &&
            remoteState.isConnected
        ) {
            elderControllerExitHandledSessionId = sessionId
            coordinator.onControllerLeftAssist()
        }
    }

    if (uiState.isSettingsVisible) {
        SettingsDialog(uiState, coordinator)
    }
    if (
        uiState.side == DeviceSide.HELPER &&
        uiState.isVerificationRequestVisible &&
        uiState.activeSession != null &&
        uiState.shouldShowVerificationRequestDialog()
    ) {
        VerificationRequestDialog(
            session = uiState.activeSession,
            onAccept = coordinator::acceptVerificationRequest,
            onReject = coordinator::rejectVerificationRequest
        )
    }
    uiState.pendingInviteSession?.let { session ->
        InvitePreviewDialog(
            session = session,
            onDismiss = coordinator::dismissPendingInvite,
            onConfirm = coordinator::confirmPendingInvite
        )
    }
    if (
        uiState.side == DeviceSide.HELPER &&
        uiState.currentScreen == AppScreen.VERIFICATION &&
        uiState.activeSession != null
    ) {
        KeepAliveVerificationCallHost(callState = callState)
    }

    when (uiState.currentScreen) {
        AppScreen.DASHBOARD -> DashboardScreen(uiState, coordinator)

        AppScreen.SESSION -> SessionScreen(
            side = uiState.side,
            session = uiState.activeSession,
            bannerMessage = uiState.bannerMessage,
            onDismissBanner = coordinator::dismissBanner,
            onBackClick = { coordinator.endCurrentSession("已取消本次请求") },
            onExpired = { coordinator.endCurrentSession("短信链接已过期，请重新发起协助") },
            onCopyLink = {
                uiState.activeSession?.deepLink?.let { link ->
                    val clipboard = android.content.ClipboardManager::class.java
                    val clipboardManager = context.getSystemService(clipboard)
                    clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText("remote_help_link", link))
                    Toast.makeText(context, "短信链接已复制", Toast.LENGTH_SHORT).show()
                }
            },
            onSendSms = {
                uiState.activeSession?.let { session ->
                    val smsIntent = Intent(
                        Intent.ACTION_SENDTO,
                        Uri.parse("smsto:${session.elderPhone}")
                    ).putExtra(
                        "sms_body",
                        "请点击下面的远程协助验证链接：\n${session.deepLink}"
                    )
                    runCatching { context.startActivity(smsIntent) }
                        .onFailure {
                            Toast.makeText(context, "未找到可发送短信的应用", Toast.LENGTH_SHORT).show()
                        }
                }
            }
        )

        AppScreen.VERIFICATION -> VerificationScreen(
            side = uiState.side,
            stage = uiState.activeSession?.stage ?: HelpStage.DRAFT,
            helperName = uiState.activeSession?.helperName ?: uiState.helperName,
            elderName = uiState.activeSession?.elderName ?: uiState.elderName,
            uiState = callState,
            onJoinClick = {
                if (mediaPermissionsGranted) {
                    coordinator.callController.startLocalMedia()
                    coordinator.callController.joinRoom()
                } else {
                    joinAfterPermission = true
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                }
            },
            onLeaveClick = coordinator.callController::leaveRoom,
            onToggleMic = coordinator.callController::toggleMic,
            onToggleCamera = coordinator.callController::toggleCamera,
            onToggleSpeaker = coordinator.callController::toggleSpeakerOutput,
            onAcceptClick = coordinator::acceptVerification,
            onRejectClick = coordinator::rejectVerification,
            onRemoteVideoTimeoutConfirm = coordinator::failVerificationDueToRemoteTimeout,
            onContinueAssist = coordinator::openAssist,
            onBackClick = coordinator::leaveVerification
        )

        AppScreen.ASSIST -> {
            BackHandler(enabled = true) {}
            LaunchedEffect(uiState.activeSession?.remoteRoomId, uiState.side) {
                if (!remoteState.isConnected) {
                    coordinator.remoteController.connect()
                }
            }
            val assistSessionId = uiState.activeSession?.requestId
            val requestAssistPermissions: () -> Unit = {
                if (
                    uiState.shouldShowAssistPermissionPrompt(remoteState) &&
                    assistSessionId != null
                ) {
                    assistPermissionPromptVisible = false
                    assistPermissionPromptSessionId = assistSessionId
                    val needsCapture = !remoteState.targetStatus.captureActive
                    val needsAccessibility = !remoteState.targetStatus.accessibilityEnabled
                    if (needsCapture) {
                        openAccessibilitySettingsAfterCaptureSessionId =
                            if (needsAccessibility) assistSessionId else null
                        projectionLauncher.launch(projectionIntent)
                    } else if (needsAccessibility) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                } else {
                    assistPermissionPromptVisible = false
                }
            }
            LaunchedEffect(
                uiState.currentScreen,
                uiState.side,
                assistSessionId,
                remoteState.targetStatus.captureActive,
                remoteState.targetStatus.accessibilityEnabled
            ) {
                if (
                    !uiState.shouldShowAssistPermissionPrompt(remoteState) ||
                    assistSessionId == null
                ) {
                    assistPermissionPromptVisible = false
                    openAccessibilitySettingsAfterCaptureSessionId = null
                    return@LaunchedEffect
                }
                val needsCapture = !remoteState.targetStatus.captureActive
                val needsAccessibility = !remoteState.targetStatus.accessibilityEnabled
                if (!needsCapture && !needsAccessibility) {
                    assistPermissionPromptVisible = false
                    openAccessibilitySettingsAfterCaptureSessionId = null
                    return@LaunchedEffect
                }
                if ((needsCapture || needsAccessibility) && assistPermissionPromptSessionId != assistSessionId) {
                    assistPermissionPromptVisible = true
                }
            }
            if (assistPermissionPromptVisible && assistSessionId != null) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("开始远程协助前需要权限") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "要让对方看到并操作你的手机，需要先开启屏幕共享和无障碍服务。",
                                color = Color(0xFF526277)
                            )
                            Text(
                                text = "屏幕共享：让对方看到你的实时画面，便于远程指导。",
                                color = Color(0xFF526277)
                            )
                            Text(
                                text = "无障碍服务：让对方执行点击、滑动和拖动操作。",
                                color = Color(0xFF526277)
                            )
                            Text(
                                text = "如果不授权，对方只能看到界面，不能真正帮你操作。",
                                color = Color(0xFF526277)
                            )
                            Text(
                                text = "当前状态：屏幕共享 ${if (remoteState.targetStatus.captureActive) "已开启" else "未开启"}，无障碍服务 ${if (remoteState.targetStatus.accessibilityEnabled) "已开启" else "未开启"}",
                                color = Color(0xFF526277)
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = requestAssistPermissions) {
                            Text("去授权")
                        }
                    }
                )
            }
            RemoteAssistScreen(
                side = uiState.side,
                helperName = uiState.activeSession?.helperName ?: uiState.helperName,
                elderName = uiState.activeSession?.elderName ?: uiState.elderName,
                uiState = remoteState,
                screenRenderer = if (remoteState.targetStatus.captureActive) callState.assistRenderer else null,
                onEndClick = { coordinator.endCurrentSession() },
                onRequestCapture = { projectionLauncher.launch(projectionIntent) },
                onOpenAccessibilitySettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onConnectClick = coordinator.remoteController::connect,
                isSpeakerOn = callState.isSpeakerOn,
                onToggleSpeaker = coordinator.callController::toggleSpeakerOutput,
                onSendText = coordinator.remoteController::sendTextCommand,
                onSendBackspace = coordinator.remoteController::sendBackspaceCommand,
                onSendEnter = coordinator.remoteController::sendEnterCommand,
                onFrameTap = coordinator.remoteController::sendTapCommand,
                onFrameSwipe = coordinator.remoteController::sendSwipeCommand,
                onFrameDrag = coordinator.remoteController::sendDragCommand,
                onSendBack = coordinator.remoteController::sendBackCommand,
                onSendHome = coordinator.remoteController::sendHomeCommand,
                onSendRecents = coordinator.remoteController::sendRecentsCommand
            )
        }
    }
}

@Composable
private fun KeepAliveVerificationCallHost(callState: CallUiState) {
    if (callState.localRenderer == null && callState.remoteRenderer == null) {
        return
    }
    Box(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
    ) {
        callState.remoteRenderer?.let { binding ->
            KeepAliveRenderer(binding = binding)
        }
        callState.localRenderer?.let { binding ->
            KeepAliveRenderer(binding = binding)
        }
    }
}

@Composable
private fun KeepAliveRenderer(binding: VideoRendererBinding) {
    val context = LocalContext.current
    val surfaceView = remember(binding) {
        SurfaceViewRenderer(context).apply {
            init(binding.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(binding.mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
    }

    DisposableEffect(binding) {
        binding.attach(surfaceView)
        onDispose { binding.detach(surfaceView) }
    }

    AndroidView(
        factory = { surfaceView },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun VerificationRequestDialog(
    session: ActiveHelpSession,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("视频认证请求") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("对方请求进行视频认证。", color = Color(0xFF526277))
                InfoLine("协助对象", "${session.elderName} · ${session.elderPhone}")
                InfoLine("剩余有效期", formatRemaining(session.expiresAt))
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
private fun InvitePreviewDialog(
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
                Text("请先核对协助链接中的信息，确认无误后再进入视频验证。", color = Color(0xFF526277))
                InfoLine("协助方", session.helperName)
                InfoLine("剩余有效时间", if (isExpired) "已过期" else formatRemaining(session.expiresAt, currentTime))
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
private fun DashboardScreen(
    uiState: RemoteHelpUiState,
    coordinator: RemoteHelpCoordinator
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF6EFE3)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFF0E7D6), Color(0xFFE6EDF7))))
                .systemBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { TopHero(uiState, coordinator) }
            uiState.bannerMessage?.let { message ->
                item { BannerCard(message = message, onDismiss = coordinator::dismissBanner) }
            }
            item {
                if (uiState.side == DeviceSide.HELPER) {
                    HelperHome(uiState, coordinator)
                } else {
                    ElderHome(uiState, coordinator)
                }
            }
            if (uiState.side == DeviceSide.HELPER) {
                item { RecentContactsCard(uiState.recentContacts, coordinator) }
            }
            item { HistoryCard(uiState.history) }
        }
    }
}

@Composable
private fun TopHero(
    uiState: RemoteHelpUiState,
    coordinator: RemoteHelpCoordinator
) {
    ProductCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "远程协助",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF183153)
                    )
                    Text(text = uiState.side.subtitle, color = Color(0xFF526277))
                }
                OutlinedButton(
                    onClick = coordinator::openSettings,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF183153)
                    )
                ) {
                    Text(
                        text = "设置",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SideButton("我来协助", uiState.side == DeviceSide.HELPER) {
                    coordinator.switchSide(DeviceSide.HELPER)
                }
                SideButton("需要协助", uiState.side == DeviceSide.ELDER) {
                    coordinator.switchSide(DeviceSide.ELDER)
                }
            }
        }
    }
}

@Composable
private fun HelperHome(
    uiState: RemoteHelpUiState,
    coordinator: RemoteHelpCoordinator
) {
    ProductCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = "发起远程协助", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
            OutlinedTextField(
                value = uiState.elderPhone,
                onValueChange = coordinator::updateElderPhone,
                label = { Text("请输入协助对象手机号") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.elderName,
                onValueChange = coordinator::updateElderName,
                label = { Text("联系人称呼") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = coordinator::createRequest, modifier = Modifier.fillMaxWidth()) {
                Text("发起协助")
            }
            Text(text = "协助说明", fontWeight = FontWeight.Medium, color = Color(0xFF183153))
            Text(text = "• 对方将收到短信验证链接", color = Color(0xFF526277))
            Text(text = "• 需视频确认后开始协助", color = Color(0xFF526277))
            Text(text = "• 当前连接配置从设置中读取并本地复用", color = Color(0xFF526277))
        }
    }
}

@Composable
private fun ElderHome(
    uiState: RemoteHelpUiState,
    coordinator: RemoteHelpCoordinator
) {
    val context = LocalContext.current
    ProductCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "远程协助", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
            Text(text = "点击短信链接后可先核对协助信息，再进入视频验证", color = Color(0xFF526277))
            OutlinedTextField(
                value = uiState.inviteEntry,
                onValueChange = coordinator::updateInviteEntry,
                label = { Text("粘贴短信链接") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        coordinator.updateInviteEntry("")
                        val clipboardManager = context.getSystemService(android.content.ClipboardManager::class.java)
                        val pastedText = clipboardManager?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (pastedText.isBlank()) {
                            Toast.makeText(context, "剪切板中没有可用链接", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        coordinator.updateInviteEntry(pastedText)
                    },
                    modifier = Modifier
                        .widthIn(min = 84.dp, max = 96.dp),
                    shape = ButtonDefaults.shape,
                    border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF6E7B8B)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("粘贴链接")
                }
                Button(
                    onClick = { coordinator.consumeInvite(uiState.inviteEntry) },
                    modifier = Modifier.widthIn(min = 168.dp)
                ) {
                    Text("查看协助信息")
                }
            }
        }
    }
}

@Composable
private fun SessionScreen(
    side: DeviceSide,
    session: ActiveHelpSession?,
    bannerMessage: String?,
    onDismissBanner: () -> Unit,
    onBackClick: () -> Unit,
    onExpired: () -> Unit,
    onCopyLink: () -> Unit,
    onSendSms: () -> Unit
) {
    var currentTime by remember(session?.requestId, session?.expiresAt) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(session?.requestId, session?.expiresAt) {
        val activeSession = session ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            currentTime = now
            if (now >= activeSession.expiresAt) {
                onExpired()
                break
            }
            delay(1000L)
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF6EFE3)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFF0E7D6), Color(0xFFE6EDF7))))
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProductCard {
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
                            overflow = TextOverflow.Clip,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            bannerMessage?.let {
                BannerCard(message = it, onDismiss = onDismissBanner)
            }
            session?.let {
                ProductCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        InfoLine("当前阶段", it.stage.title)
                        InfoLine("协助对象", "${it.elderName} · ${it.elderPhone}")
                        InfoLine("剩余有效期", formatRemaining(it.expiresAt, currentTime))
                        if (side == DeviceSide.HELPER) {
                            LinkPreviewLine("短信链接", it.deepLink)
                            Button(onClick = onSendSms, modifier = Modifier.fillMaxWidth()) {
                                Text("发送短信")
                            }
                            OutlinedButton(onClick = onCopyLink, modifier = Modifier.fillMaxWidth()) {
                                Text("复制短信链接")
                            }
                            Text("对方完成短信认证后，将自动进入视频验证。", color = Color(0xFF526277))
                        } else {
                            InfoLine("协助方", it.helperName)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentContactsCard(
    contacts: List<RecentContact>,
    coordinator: RemoteHelpCoordinator
) {
    ProductCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "最近协助", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
            if (contacts.isEmpty()) {
                Text(text = "还没有历史联系人", color = Color(0xFF526277))
            } else {
                contacts.forEach { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { coordinator.applyRecentContact(contact) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = contact.name, fontWeight = FontWeight.Medium)
                            Text(text = contact.phone, color = Color(0xFF526277))
                        }
                        Text(text = formatDateTime(contact.lastHelpTime), color = Color(0xFF526277))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(history: List<SessionHistoryItem>) {
    ProductCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "最近协助记录", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
            if (history.isEmpty()) {
                Text(text = "当前没有本地记录", color = Color(0xFF526277))
            } else {
                history.take(5).forEach { item ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "${item.elderName} · ${item.elderPhone}", fontWeight = FontWeight.Medium)
                        Text(text = "${formatDateTime(item.startedAt)} · ${item.endReason}", color = Color(0xFF526277))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    uiState: RemoteHelpUiState,
    coordinator: RemoteHelpCoordinator
) {
    AlertDialog(
        onDismissRequest = coordinator::closeSettings,
        title = { Text("连接设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = uiState.serverUrl,
                    onValueChange = coordinator::updateServerUrl,
                    label = { Text("信令服务地址") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uiState.helperName,
                    onValueChange = coordinator::updateHelperName,
                    label = { Text("默认协助方姓名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "提示：`ws://192.168.2.109:3000/ws` 只适用于 Android 模拟器。真机联调请改成宿主机局域网 IP，例如 `ws://192.168.2.109:3000/ws`。",
                    color = Color(0xFF526277)
                )
                OutlinedButton(
                    onClick = coordinator::openLogsDirectory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看日志目录")
                }
            }
        },
        confirmButton = {
            Button(onClick = coordinator::saveSettings) {
                Text("保存")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = coordinator::closeSettings) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun BannerCard(message: String, onDismiss: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF183153))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = Color.White,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .fillMaxWidth(0.72f)
            )
            OutlinedButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun SideButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF215A6D) else Color(0xFFE8EDF3),
            contentColor = if (selected) Color.White else Color(0xFF183153)
        )
    ) {
        Text(text)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = Color(0xFF526277))
        Text(text = value, color = Color(0xFF183153), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LinkPreviewLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, color = Color(0xFF526277))
        Text(
            text = value,
            color = Color(0xFF526277),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProductCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFCF8))
    ) {
        Box(modifier = Modifier.padding(18.dp)) {
            content()
        }
    }
}
