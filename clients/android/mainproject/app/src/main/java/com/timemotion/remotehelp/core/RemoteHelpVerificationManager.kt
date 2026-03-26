package com.timemotion.remotehelp.core

import android.os.Handler
import com.timemotion.remotehelp.remote.RemoteControlController
import com.timemotion.remotehelp.webrtc.CallController
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class RemoteHelpVerificationManager(
    private val uiState: MutableStateFlow<RemoteHelpUiState>,
    private val historyManager: RemoteHelpHistoryManager,
    private val callController: CallController,
    private val remoteController: RemoteControlController,
    private val configureCallController: (ActiveHelpSession) -> Unit,
    private val cancelHelperWaitTimeout: () -> Unit,
    private val currentSessionOrNotify: () -> ActiveHelpSession?,
    private val shouldAutoEnterAssist: () -> Boolean,
    private val openAssist: () -> Unit,
    private val finishSession: (String, Boolean) -> Unit,
    private val mainHandler: Handler
) {
    fun openVerification(session: ActiveHelpSession) {
        if (session.isExpired()) {
            uiState.value = uiState.value.copy(bannerMessage = "请求已过期，请重新发起")
            return
        }
        configureCallController(session)
        ensureVerificationRoomConnected()
        uiState.value = uiState.value.copy(
            currentScreen = AppScreen.VERIFICATION,
            activeSession = session.copy(stage = HelpStage.VERIFYING),
            bannerMessage = null
        )
    }

    fun confirmPendingInvite(session: ActiveHelpSession) {
        if (session.isExpired()) {
            uiState.value = uiState.value.copy(
                inviteEntry = "",
                pendingInviteSession = null,
                activeSession = null,
                currentScreen = AppScreen.DASHBOARD,
                bannerMessage = "链接已过期，请重新发起协助"
            )
            return
        }
        configureCallController(session)
        ensureVerificationRoomConnected()
        uiState.value = uiState.value.copy(
            side = DeviceSide.ELDER,
            activeSession = session,
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            currentScreen = AppScreen.VERIFICATION,
            bannerMessage = "已确认协助信息，正在进入视频核验"
        )
    }

    fun dismissPendingInvite() {
        uiState.value = uiState.value.copy(
            inviteEntry = "",
            pendingInviteSession = null,
            isVerificationRequestVisible = false
        )
    }

    fun leaveVerification() {
        cancelHelperWaitTimeout()
        val state = uiState.value
        val session = state.activeSession
        if (session != null && !session.isExpired()) {
            callController.sendAppSignal(
                SIGNAL_VERIFICATION_LEFT,
                JSONObject().put("requestId", session.requestId)
            )
        }
        callController.leaveRoom()
        if (state.side == DeviceSide.HELPER && session != null) {
            val resetSession = session.copy(
                stage = HelpStage.REQUEST_CREATED,
                verificationAcceptedAt = null,
                endReason = null
            )
            historyManager.savePendingHelperSession(resetSession)
            configureCallController(resetSession)
            uiState.value = state.copy(
                activeSession = resetSession,
                isVerificationRequestVisible = false,
                currentScreen = AppScreen.SESSION,
                bannerMessage = "已退出视频认证，等待对方重新接入"
            )
            return
        }
        uiState.value = state.copy(
            inviteEntry = if (session?.isExpired() == true) "" else state.inviteEntry,
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = if (session?.isExpired() == true) null else session,
            currentScreen = AppScreen.DASHBOARD,
            bannerMessage = if (session?.isExpired() == true) {
                "链接已过期，请重新发起协助"
            } else {
                "已退出身份验证，可重新打开短信链接再次进入"
            }
        )
    }

    fun failVerificationDueToRemoteTimeout() {
        val state = uiState.value
        val session = state.activeSession ?: return
        if (state.side != DeviceSide.ELDER) {
            return
        }
        if (!session.isExpired()) {
            callController.sendAppSignal(
                SIGNAL_VERIFICATION_LEFT,
                JSONObject().put("requestId", session.requestId)
            )
        }
        callController.leaveRoom()
        remoteController.disconnect()
        uiState.value = state.copy(
            inviteEntry = "",
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = null,
            currentScreen = AppScreen.DASHBOARD,
            bannerMessage = "对方长时间未接入视频画面，认证失败"
        )
    }

    fun onVerificationParticipantLeft() {
        cancelHelperWaitTimeout()
        val state = uiState.value
        val session = state.activeSession ?: return
        callController.leaveRoom()
        remoteController.disconnect()
        if (session.isExpired()) {
            if (state.side == DeviceSide.HELPER) {
                finishSession("短信链接已过期，请重新发起协助", false)
            } else {
                uiState.value = state.copy(
                    inviteEntry = "",
                    pendingInviteSession = null,
                    isVerificationRequestVisible = false,
                    activeSession = null,
                    currentScreen = AppScreen.DASHBOARD,
                    bannerMessage = "链接已过期，请重新发起协助"
                )
            }
            return
        }
        if (state.side == DeviceSide.HELPER) {
            val resetSession = session.copy(
                stage = HelpStage.REQUEST_CREATED,
                verificationAcceptedAt = null,
                endReason = null
            )
            historyManager.savePendingHelperSession(resetSession)
            configureCallController(resetSession)
            uiState.value = state.copy(
                currentScreen = AppScreen.SESSION,
                activeSession = resetSession,
                pendingInviteSession = null,
                isVerificationRequestVisible = false,
                bannerMessage = "${session.elderName} 已退出身份验证，可在有效期内重新通过短信链接认证"
            )
            return
        }
        uiState.value = state.copy(
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = session,
            currentScreen = AppScreen.DASHBOARD,
            bannerMessage = "${session.helperName} 已退出身份验证，可重新打开短信链接再次进入"
        )
    }

    fun onVerificationPeerReady() {
        val session = currentSessionOrNotify() ?: return
        val state = uiState.value
        if (state.side != DeviceSide.HELPER || !state.shouldShowVerificationRequestDialog()) {
            return
        }
        cancelHelperWaitTimeout()
        configureCallController(session)
        uiState.value = uiState.value.copy(
            activeSession = session.copy(stage = HelpStage.VERIFYING),
            isVerificationRequestVisible = true,
            pendingInviteSession = null,
            bannerMessage = "对方请求进行视频核验"
        )
    }

    fun acceptVerificationRequest() {
        val session = currentSessionOrNotify() ?: return
        cancelHelperWaitTimeout()
        val acceptedSession = session.copy(
            verificationAcceptedAt = System.currentTimeMillis()
        )
        uiState.value = uiState.value.copy(activeSession = acceptedSession)
        configureCallController(acceptedSession)
        if (!session.isExpired()) {
            callController.sendAppSignal(
                SIGNAL_VERIFICATION_ACCEPTED,
                JSONObject().put("requestId", session.requestId),
                broadcast = true
            )
        }
        uiState.value = uiState.value.copy(isVerificationRequestVisible = false)
        openVerification(acceptedSession)
    }

    fun rejectVerificationRequest() {
        cancelHelperWaitTimeout()
        val state = uiState.value
        val session = state.activeSession ?: return
        if (state.side != DeviceSide.HELPER) {
            return
        }
        if (!session.isExpired()) {
            callController.sendAppSignal(
                SIGNAL_VERIFICATION_LEFT,
                JSONObject().put("requestId", session.requestId)
            )
        }
        callController.leaveRoom()
        val resetSession = session.copy(
            stage = HelpStage.REQUEST_CREATED,
            verificationAcceptedAt = null,
            endReason = null
        )
        historyManager.savePendingHelperSession(resetSession)
        configureCallController(resetSession)
        uiState.value = state.copy(
            activeSession = resetSession,
            isVerificationRequestVisible = false,
            bannerMessage = "已拒绝视频认证请求"
        )
    }

    fun expirePendingInvite(reason: String) {
        val state = uiState.value
        if (state.side != DeviceSide.ELDER || state.pendingInviteSession == null) {
            return
        }
        cancelHelperWaitTimeout()
        callController.leaveRoom()
        uiState.value = state.copy(
            inviteEntry = "",
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = null,
            currentScreen = AppScreen.DASHBOARD,
            bannerMessage = reason
        )
    }

    fun acceptVerification() {
        val session = currentSessionOrNotify() ?: return
        runCatching {
            callController.sendAppSignal(SIGNAL_HELP_ACCEPT, JSONObject().put("requestId", session.requestId))
            uiState.value = uiState.value.copy(
                activeSession = session.copy(
                    stage = HelpStage.VERIFIED,
                    verificationAcceptedAt = System.currentTimeMillis()
                ),
                bannerMessage = if (shouldAutoEnterAssist()) {
                    "已接受协助，可进入远程协助"
                } else {
                    "已接受协助，请点击进入远程协助"
                }
            )
            if (shouldAutoEnterAssist()) {
                mainHandler.post {
                    runCatching { openAssist() }
                        .onFailure { AppLog.logThrowable("RemoteHelpCoordinator", it, "打开远程协助失败") }
                }
            }
        }.onFailure {
            AppLog.logThrowable("RemoteHelpCoordinator", it, "接受视频验证失败")
        }
    }

    fun rejectVerification() {
        val session = currentSessionOrNotify() ?: return
        callController.sendAppSignal(SIGNAL_HELP_REJECT, JSONObject().put("requestId", session.requestId))
        finishSession("${session.helperName} 拒绝了协助", true)
    }

    fun notifyVerificationRequested() {
        val state = uiState.value
        val session = state.activeSession ?: return
        if (!state.shouldBroadcastVerificationRequested() || session.isExpired()) {
            return
        }
        callController.sendAppSignal(
            SIGNAL_VERIFICATION_REQUESTED,
            JSONObject().put("requestId", session.requestId),
            broadcast = true
        )
    }

    fun handleCallSignal(signalType: String, payload: JSONObject, fromDisplayName: String): Boolean {
        val session = uiState.value.activeSession ?: return false
        return when (signalType) {
            SIGNAL_HELP_ACCEPT -> {
                runCatching {
                    uiState.value = uiState.value.copy(
                        activeSession = session.copy(
                            stage = HelpStage.VERIFIED,
                            verificationAcceptedAt = System.currentTimeMillis()
                        ),
                        bannerMessage = if (shouldAutoEnterAssist()) {
                            "${fromDisplayName.ifBlank { "对端" }} 已通过视频验证，正在进入远程协助"
                        } else {
                            "${fromDisplayName.ifBlank { "对端" }} 已通过视频验证，请手动进入远程协助"
                        }
                    )
                    if (uiState.value.side == DeviceSide.HELPER) {
                        mainHandler.post {
                            runCatching { openAssist() }
                                .onFailure { AppLog.logThrowable("RemoteHelpCoordinator", it, "打开远程协助失败") }
                        }
                    } else if (shouldAutoEnterAssist()) {
                        mainHandler.post {
                            runCatching { openAssist() }
                                .onFailure { AppLog.logThrowable("RemoteHelpCoordinator", it, "打开远程协助失败") }
                        }
                    }
                }.onFailure {
                    AppLog.logThrowable("RemoteHelpCoordinator", it, "处理协助接受信令失败")
                }
                true
            }

            SIGNAL_HELP_REJECT -> {
                finishSession("${fromDisplayName.ifBlank { "对端" }} 拒绝了协助", true)
                true
            }

            SIGNAL_VERIFICATION_LEFT -> {
                val requestId = payload.optString("requestId")
                if (requestId == session.requestId) {
                    onVerificationParticipantLeft()
                    true
                } else {
                    false
                }
            }

            SIGNAL_VERIFICATION_REQUESTED -> {
                val requestId = payload.optString("requestId")
                if (requestId == session.requestId) {
                    onVerificationPeerReady()
                    true
                } else {
                    false
                }
            }

            SIGNAL_VERIFICATION_ACCEPTED -> {
                val requestId = payload.optString("requestId")
                if (requestId == session.requestId) {
                    if (uiState.value.side == DeviceSide.ELDER) {
                        uiState.value = uiState.value.copy(
                            bannerMessage = "对方已接受视频认证，正在接入画面"
                        )
                    }
                    true
                } else {
                    false
                }
            }

            else -> false
        }
    }

    private fun ensureVerificationRoomConnected() {
        val callState = callController.uiState.value
        if (callState.isInRoom || callState.isConnecting) {
            return
        }
        callController.joinRoom(prepareLocalMedia = false)
    }
}

private const val SIGNAL_HELP_ACCEPT = "help_accept"
private const val SIGNAL_HELP_REJECT = "help_reject"
private const val SIGNAL_VERIFICATION_LEFT = "verification_left"
private const val SIGNAL_VERIFICATION_REQUESTED = "verification_requested"
private const val SIGNAL_VERIFICATION_ACCEPTED = "verification_accepted"
