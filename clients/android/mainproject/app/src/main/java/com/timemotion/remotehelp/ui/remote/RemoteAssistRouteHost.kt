package com.timemotion.remotehelp.ui.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.timemotion.remotehelp.R
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.AppScreen
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.core.shouldShowAssistPermissionPrompt
import com.timemotion.remotehelp.core.shouldListenForAssistExit
import com.timemotion.remotehelp.remote.RemoteControlUiState
import com.timemotion.remotehelp.remote.RemoteRole
import com.timemotion.remotehelp.webrtc.CallUiState
import androidx.compose.ui.unit.dp

@Composable
fun RemoteAssistRouteHost(
    coordinator: RemoteHelpCoordinator,
    uiState: RemoteHelpUiState,
    callState: CallUiState,
    remoteState: RemoteControlUiState,
    projectionIntent: Intent,
    onRequestHelperOverlayShow: () -> Unit
) {
    val context = LocalContext.current
    val latestUiState by rememberUpdatedState(uiState)
    var notificationPermissionGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) }
    var overlayPermissionGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var assistPermissionPromptVisible by remember { mutableStateOf(false) }
    var assistPermissionPromptSessionId by remember { mutableStateOf<String?>(null) }
    var elderControllerSeenSessionId by remember { mutableStateOf<String?>(null) }
    var elderControllerExitHandledSessionId by remember { mutableStateOf<String?>(null) }
    var assistPermissionPromptSuppressedSessionId by remember { mutableStateOf<String?>(null) }

    val projectionLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result: ActivityResult ->
        runCatching {
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                coordinator.remoteController.startTargetCapture(result.resultCode, data)
            } else {
                coordinator.remoteController.onCapturePermissionDenied()
            }
        }.onFailure {
            AppLog.logThrowable("RemoteHelpApp", it, "处理屏幕共享授权结果失败")
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
            runCatching {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }.onFailure {
                AppLog.logThrowable("RemoteHelpApp", it, "申请通知权限失败")
            }
        }
    }

    LaunchedEffect(uiState.currentScreen, uiState.side) {
        overlayPermissionGranted = Settings.canDrawOverlays(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayPermissionGranted = Settings.canDrawOverlays(context)
                if (assistPermissionPromptVisible && overlayPermissionGranted) {
                    assistPermissionPromptVisible = false
                    onRequestHelperOverlayShow()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
            assistPermissionPromptSuppressedSessionId = assistSessionId
            assistPermissionPromptSessionId = assistSessionId
            val needsCapture = !remoteState.targetStatus.captureActive
            val needsAccessibility = !remoteState.targetStatus.accessibilityEnabled
            if (needsCapture) {
                runCatching { projectionLauncher.launch(projectionIntent) }
                    .onFailure {
                        AppLog.logThrowable("RemoteHelpApp", it, "申请屏幕共享失败")
                    }
            } else if (needsAccessibility) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.onFailure {
                    AppLog.logThrowable("RemoteHelpApp", it, "打开无障碍设置失败")
                }
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
        remoteState.targetStatus.accessibilityEnabled,
        assistPermissionPromptSuppressedSessionId
    ) {
        if (
            !uiState.shouldShowAssistPermissionPrompt(remoteState) ||
            assistSessionId == null
        ) {
            assistPermissionPromptVisible = false
            return@LaunchedEffect
        }
        val needsCapture = !remoteState.targetStatus.captureActive
        val needsAccessibility = !remoteState.targetStatus.accessibilityEnabled
        if (!needsCapture && !needsAccessibility) {
            assistPermissionPromptVisible = false
            return@LaunchedEffect
        }
        if (
            (needsCapture || needsAccessibility) &&
            assistPermissionPromptSuppressedSessionId != assistSessionId &&
            assistPermissionPromptSessionId != assistSessionId
        ) {
            assistPermissionPromptVisible = true
        }
    }

    LaunchedEffect(
        uiState.currentScreen,
        uiState.side,
        assistSessionId,
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
            runCatching { coordinator.onControllerLeftAssist() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "处理协助方退出失败") }
        }
    }

    if (assistPermissionPromptVisible && assistSessionId != null) {
        AlertDialog(
            onDismissRequest = {
                assistPermissionPromptVisible = false
                assistPermissionPromptSuppressedSessionId = assistSessionId
            },
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
                    Text(
                        text = "如果暂时不授权，可以关闭此提示，之后仍可在对应权限卡片中再次发起授权。",
                        color = Color(0xFF526277)
                    )
                }
            },
            confirmButton = {
                Button(onClick = requestAssistPermissions) {
                    Text("去授权")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    assistPermissionPromptVisible = false
                    assistPermissionPromptSuppressedSessionId = assistSessionId
                }) {
                    Text("稍后再说")
                }
            }
        )
    }

    RemoteAssistScreen(
        side = uiState.side,
        helperName = uiState.activeSession?.helperName ?: uiState.helperName,
        elderName = uiState.activeSession?.elderName ?: uiState.elderName,
        uiState = remoteState,
        screenRenderer = callState.assistRenderer,
        onEndClick = {
            runCatching { coordinator.endCurrentSession() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "结束远程协助失败") }
        },
        onRequestCapture = {
            runCatching { projectionLauncher.launch(projectionIntent) }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "请求屏幕共享失败") }
        },
        onOpenAccessibilitySettings = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                AppLog.logThrowable("RemoteHelpApp", it, "打开无障碍设置失败")
            }
        },
        overlayPermissionGranted = overlayPermissionGranted,
        onOpenOverlaySettings = {
            if (overlayPermissionGranted) {
                onRequestHelperOverlayShow()
            } else {
                assistPermissionPromptVisible = false
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }.onFailure {
                    AppLog.logThrowable("RemoteHelpApp", it, "打开悬浮窗设置失败")
                }
            }
        },
        onConnectClick = {
            runCatching { coordinator.remoteController.connect() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "连接远控失败") }
        },
        isSpeakerOn = coordinator.callController.uiState.value.isSpeakerOn,
        onToggleSpeaker = {
            runCatching { coordinator.callController.toggleSpeakerOutput() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "切换扬声器失败") }
        },
        onSendText = { text ->
            runCatching { coordinator.remoteController.sendTextCommand(text) }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送文本失败") }
        },
        onSendBackspace = {
            runCatching { coordinator.remoteController.sendBackspaceCommand() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送退格失败") }
        },
        onSendEnter = {
            runCatching { coordinator.remoteController.sendEnterCommand() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送回车失败") }
        },
        onFrameTap = { x, y ->
            runCatching { coordinator.remoteController.sendTapCommand(x, y) }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送点击失败") }
        },
        onFrameSwipe = { sx, sy, ex, ey ->
            runCatching { coordinator.remoteController.sendSwipeCommand(sx, sy, ex, ey) }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送滑动失败") }
        },
        onFrameDrag = { sx, sy, ex, ey ->
            runCatching { coordinator.remoteController.sendDragCommand(sx, sy, ex, ey) }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送拖拽失败") }
        },
        onSendBack = {
            runCatching { coordinator.remoteController.sendBackCommand() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送返回键失败") }
        },
        onSendHome = {
            runCatching { coordinator.remoteController.sendHomeCommand() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送主页键失败") }
        },
        onSendRecents = {
            runCatching { coordinator.remoteController.sendRecentsCommand() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送最近任务失败") }
        },
    )
}
