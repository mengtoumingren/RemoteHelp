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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.timemotion.remotehelp.remote.RemoteControlUiState
import com.timemotion.remotehelp.remote.RemoteRole
import com.timemotion.remotehelp.ui.remote.RemoteAssistScreen
import com.timemotion.remotehelp.ui.verification.VerificationScreen
import com.timemotion.remotehelp.webrtc.CallUiState

@Composable
fun RemoteHelpApp(
    coordinator: RemoteHelpCoordinator,
    uiState: RemoteHelpUiState,
    callState: CallUiState,
    remoteState: RemoteControlUiState,
    projectionIntent: Intent
) {
    val context = LocalContext.current
    var joinAfterPermission by remember { mutableStateOf(false) }
    var mediaPermissionsGranted by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) }
    var capturePromptedSessionId by remember { mutableStateOf<String?>(null) }
    var accessibilityPromptedSessionId by remember { mutableStateOf<String?>(null) }
    var helperPeerSeenSessionId by remember { mutableStateOf<String?>(null) }
    var helperPeerExitHandledSessionId by remember { mutableStateOf<String?>(null) }
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
        } else {
            coordinator.remoteController.onCapturePermissionDenied()
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        notificationPermissionGranted = granted
        if (!granted) {
            Toast.makeText(context, "未授予通知权限时，通知栏可能无法显示常驻协助通知", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(
        uiState.currentScreen,
        uiState.side,
        uiState.activeSession?.requestId,
        uiState.activeSession?.stage
    ) {
        val shouldPrepareVideo = (
            uiState.currentScreen == AppScreen.VERIFICATION ||
                (uiState.side == DeviceSide.HELPER && uiState.activeSession?.stage == HelpStage.REQUEST_CREATED)
            )
        if (shouldPrepareVideo && !mediaPermissionsGranted) {
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
        val shouldJoinHiddenVerification = (
            uiState.side == DeviceSide.HELPER &&
                uiState.activeSession?.stage == HelpStage.REQUEST_CREATED
            )
        val shouldJoinVisibleVerification = uiState.currentScreen == AppScreen.VERIFICATION
        if ((shouldJoinHiddenVerification || shouldJoinVisibleVerification) && mediaPermissionsGranted) {
            coordinator.callController.startLocalMedia()
            if (!callState.isInRoom && !callState.isConnecting) {
                coordinator.callController.joinRoom()
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
        uiState.activeSession?.stage,
        uiState.currentScreen,
        callState.remotePeerName
    ) {
        if (
            uiState.side == DeviceSide.HELPER &&
            uiState.activeSession?.stage == HelpStage.REQUEST_CREATED &&
            uiState.currentScreen != AppScreen.VERIFICATION &&
            callState.remotePeerName.isNotBlank()
        ) {
            coordinator.onVerificationPeerReady()
        }
    }

    LaunchedEffect(
        uiState.side,
        uiState.currentScreen,
        uiState.activeSession?.requestId,
        uiState.activeSession?.stage,
        callState.remotePeerName,
        remoteState.peers,
        remoteState.isConnected
    ) {
        val sessionId = uiState.activeSession?.requestId
        if (uiState.side != DeviceSide.HELPER || sessionId == null) {
            return@LaunchedEffect
        }
        val hasVerificationPeer = callState.remotePeerName.isNotBlank()
        val hasAssistPeer = remoteState.peers.any { it.role == RemoteRole.TARGET }
        val hasAnyPeer = hasVerificationPeer || hasAssistPeer
        val shouldWatchPeerExit = uiState.currentScreen == AppScreen.VERIFICATION || uiState.currentScreen == AppScreen.ASSIST
        if (hasAnyPeer) {
            helperPeerSeenSessionId = sessionId
            helperPeerExitHandledSessionId = null
        } else if (
            shouldWatchPeerExit &&
            helperPeerSeenSessionId == sessionId &&
            helperPeerExitHandledSessionId != sessionId &&
            uiState.activeSession?.stage != HelpStage.REQUEST_CREATED
        ) {
            helperPeerExitHandledSessionId = sessionId
            coordinator.onRemoteParticipantUnexpectedExit()
        }
    }

    if (uiState.isSettingsVisible) {
        SettingsDialog(uiState, coordinator)
    }

    when (uiState.currentScreen) {
        AppScreen.DASHBOARD -> DashboardScreen(uiState, coordinator)

        AppScreen.SESSION -> SessionScreen(
            side = uiState.side,
            session = uiState.activeSession,
            bannerMessage = uiState.bannerMessage,
            onDismissBanner = coordinator::dismissBanner,
            onBackClick = { coordinator.endCurrentSession("已取消本次请求") },
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
                        "请点击下面的家庭远程协助验证链接：\n${session.deepLink}"
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
            onAcceptClick = coordinator::acceptVerification,
            onRejectClick = coordinator::rejectVerification,
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
            LaunchedEffect(
                uiState.activeSession?.requestId,
                uiState.currentScreen,
                uiState.side,
                remoteState.targetStatus.captureActive
            ) {
                val sessionId = uiState.activeSession?.requestId
                if (
                    uiState.currentScreen == AppScreen.ASSIST &&
                    uiState.side == DeviceSide.ELDER &&
                    sessionId != null &&
                    !remoteState.targetStatus.captureActive &&
                    capturePromptedSessionId != sessionId
                ) {
                    capturePromptedSessionId = sessionId
                    projectionLauncher.launch(projectionIntent)
                }
            }
            LaunchedEffect(
                uiState.activeSession?.requestId,
                uiState.currentScreen,
                uiState.side,
                remoteState.targetStatus.captureActive,
                remoteState.targetStatus.accessibilityEnabled
            ) {
                val sessionId = uiState.activeSession?.requestId
                if (
                    uiState.currentScreen == AppScreen.ASSIST &&
                    uiState.side == DeviceSide.ELDER &&
                    sessionId != null &&
                    remoteState.targetStatus.captureActive &&
                    !remoteState.targetStatus.accessibilityEnabled &&
                    accessibilityPromptedSessionId != sessionId
                ) {
                    accessibilityPromptedSessionId = sessionId
                    Toast.makeText(context, "请开启无障碍服务后开始远程协助", Toast.LENGTH_LONG).show()
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            RemoteAssistScreen(
                side = uiState.side,
                helperName = uiState.activeSession?.helperName ?: uiState.helperName,
                elderName = uiState.activeSession?.elderName ?: uiState.elderName,
                uiState = remoteState,
                onBackClick = coordinator::backToDashboard,
                onEndClick = { coordinator.endCurrentSession() },
                onRequestCapture = { projectionLauncher.launch(projectionIntent) },
                onOpenAccessibilitySettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onConnectClick = coordinator.remoteController::connect,
                onDisconnectClick = coordinator.remoteController::disconnect,
                onFrameTap = coordinator.remoteController::sendTapCommand,
                onFrameSwipe = coordinator.remoteController::sendSwipeCommand
            )
        }
    }
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
                        text = "家庭远程协助",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF183153)
                    )
                    Text(text = uiState.side.subtitle, color = Color(0xFF526277))
                }
                OutlinedButton(onClick = coordinator::openSettings) {
                    Text("设置")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SideButton("子女端", uiState.side == DeviceSide.HELPER) {
                    coordinator.switchSide(DeviceSide.HELPER)
                }
                SideButton("长辈端", uiState.side == DeviceSide.ELDER) {
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
                label = { Text("请输入父母手机号") },
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
            Text(text = "• 父母将收到短信验证链接", color = Color(0xFF526277))
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
    ProductCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "家庭远程协助", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
            Text(text = "点击短信链接后会自动进入身份验证", color = Color(0xFF526277))
            OutlinedTextField(
                value = uiState.inviteEntry,
                onValueChange = coordinator::updateInviteEntry,
                label = { Text("粘贴短信链接") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { coordinator.consumeInvite(uiState.inviteEntry) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("进入身份验证")
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
    onCopyLink: () -> Unit,
    onSendSms: () -> Unit
) {
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
                    OutlinedButton(onClick = onBackClick) {
                        Text("返回")
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
                        InfoLine("剩余有效期", formatRemaining(it.expiresAt))
                        if (side == DeviceSide.HELPER) {
                            LinkPreviewLine("短信链接", it.deepLink)
                            OutlinedButton(onClick = onCopyLink, modifier = Modifier.fillMaxWidth()) {
                                Text("复制短信链接")
                            }
                            Button(onClick = onSendSms, modifier = Modifier.fillMaxWidth()) {
                                Text("发送短信")
                            }
                            Text("长辈完成短信认证后，将自动进入视频验证。", color = Color(0xFF526277))
                        } else {
                            InfoLine("发起人", it.helperName)
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    ProductCard {
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
                    label = { Text("默认子女姓名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "提示：`ws://10.0.2.2:3000/ws` 只适用于 Android 模拟器。真机联调请改成宿主机局域网 IP，例如 `ws://192.168.2.109:3000/ws`。",
                    color = Color(0xFF526277)
                )
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
private fun ProductCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFCF8))
    ) {
        Box(modifier = Modifier.padding(18.dp)) {
            content()
        }
    }
}
