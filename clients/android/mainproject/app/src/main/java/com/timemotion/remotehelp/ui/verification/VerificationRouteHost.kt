package com.timemotion.remotehelp.ui.verification

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.timemotion.remotehelp.core.ActiveHelpSession
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.AppScreen
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.HelpStage
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.core.shouldJoinHiddenVerificationRoom
import com.timemotion.remotehelp.core.shouldJoinVisibleVerificationRoom
import com.timemotion.remotehelp.core.shouldPrepareVerificationMedia
import com.timemotion.remotehelp.webrtc.CallUiState

@Composable
fun VerificationRouteHost(
    coordinator: RemoteHelpCoordinator,
    uiState: RemoteHelpUiState,
    callState: CallUiState,
    context: Context
) {
    val latestUiState by rememberUpdatedState(uiState)
    var joinAfterPermission by remember { mutableStateOf(false) }
    var mediaPermissionsGranted by remember { mutableStateOf(false) }
    var verificationRequestSent by remember(uiState.activeSession?.requestId) { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { permissions: Map<String, Boolean> ->
        mediaPermissionsGranted = permissions.values.all { it }
        if (!mediaPermissionsGranted) {
            Toast.makeText(context, context.getString(com.timemotion.remotehelp.R.string.permission_required), Toast.LENGTH_LONG).show()
        } else {
            coordinator.callController.startLocalMedia()
            if (joinAfterPermission) {
                coordinator.callController.joinRoom()
            }
        }
        joinAfterPermission = false
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
        uiState.activeSession?.expiresAt
    ) {
        runCatching {
            val session = uiState.activeSession ?: return@runCatching
            if (
                uiState.side == DeviceSide.HELPER &&
                uiState.currentScreen == AppScreen.SESSION &&
                session.isExpired()
            ) {
                coordinator.endCurrentSession("短信链接已过期，请重新发起协助")
            }
        }.onFailure {
            AppLog.logThrowable("RemoteHelpApp", it, "检查会话过期失败")
        }
    }

    LaunchedEffect(
        uiState.side,
        uiState.currentScreen,
        uiState.activeSession?.requestId,
        uiState.activeSession?.stage,
        callState.isInRoom,
        callState.isConnecting,
        callState.roomParticipantCount
    ) {
        if (
            uiState.side == DeviceSide.ELDER &&
            uiState.currentScreen == AppScreen.VERIFICATION &&
            uiState.activeSession?.stage == HelpStage.VERIFYING &&
            callState.isInRoom &&
            !callState.isConnecting &&
            callState.roomParticipantCount > 1 &&
            !verificationRequestSent
        ) {
            runCatching { coordinator.notifyVerificationRequested() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "发送视频认证请求失败") }
            verificationRequestSent = true
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
        runCatching {
            if (
                uiState.side == DeviceSide.HELPER &&
                uiState.activeSession?.stage == HelpStage.REQUEST_CREATED &&
                callState.isInRoom &&
                callState.roomParticipantCount > 1
            ) {
                runCatching { coordinator.cancelHelperWaitTimeout() }
                    .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "取消等待超时失败") }
            }
            if (
                uiState.side == DeviceSide.ELDER &&
                callState.isInRoom &&
                callState.roomParticipantCount <= 1
            ) {
                if (uiState.pendingInviteSession != null) {
                    runCatching { coordinator.expirePendingInvite("链接已过期，请重新发起协助") }
                        .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "处理邀请过期失败") }
                } else if (
                    uiState.currentScreen == AppScreen.VERIFICATION &&
                    uiState.activeSession?.stage == HelpStage.VERIFYING
                ) {
                    runCatching { coordinator.failVerificationDueToRemoteTimeout() }
                        .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "处理远程超时失败") }
                }
            }
        }.onFailure {
            AppLog.logThrowable("RemoteHelpApp", it, "检查房间成员失败")
        }
    }

    VerificationScreen(
        side = uiState.side,
        stage = uiState.activeSession?.stage ?: HelpStage.DRAFT,
        helperName = uiState.activeSession?.helperName ?: uiState.helperName,
        elderName = uiState.activeSession?.elderName ?: uiState.elderName,
        uiState = callState,
        onJoinClick = {
            runCatching {
                if (mediaPermissionsGranted) {
                    coordinator.callController.startLocalMedia()
                    coordinator.callController.joinRoom()
                } else {
                    joinAfterPermission = true
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                }
            }.onFailure {
                AppLog.logThrowable("RemoteHelpApp", it, "加入验证房间失败")
            }
        },
        onLeaveClick = {
            runCatching { coordinator.callController.leaveRoom() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "离开验证房间失败") }
        },
        onToggleMic = {
            runCatching { coordinator.callController.toggleMic() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "切换麦克风失败") }
        },
        onToggleCamera = {
            runCatching { coordinator.callController.toggleCamera() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "切换摄像头失败") }
        },
        onToggleSpeaker = {
            runCatching { coordinator.callController.toggleSpeakerOutput() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "切换扬声器失败") }
        },
        onAcceptClick = {
            runCatching { coordinator.acceptVerification() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "接受验证失败") }
        },
        onRejectClick = {
            runCatching { coordinator.rejectVerification() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "拒绝验证失败") }
        },
        onRemoteVideoTimeoutConfirm = {
            runCatching { coordinator.failVerificationDueToRemoteTimeout() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "处理视频超时失败") }
        },
        onContinueAssist = {
            runCatching { coordinator.openAssist() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "进入远程协助失败") }
        },
        onBackClick = {
            runCatching { coordinator.leaveVerification() }
                .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "返回验证页失败") }
        }
    )
}
