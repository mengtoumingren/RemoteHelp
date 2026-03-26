package com.timemotion.remotehelp.core

import com.timemotion.remotehelp.remote.RemoteControlController
import com.timemotion.remotehelp.remote.RemoteRole
import com.timemotion.remotehelp.webrtc.CallController
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class RemoteHelpAssistManager(
    private val uiState: MutableStateFlow<RemoteHelpUiState>,
    private val historyManager: RemoteHelpHistoryManager,
    private val callController: CallController,
    private val remoteController: RemoteControlController,
    private val configureRemoteController: (ActiveHelpSession) -> Unit,
    private val cancelHelperWaitTimeout: () -> Unit,
    private val currentSessionOrNotify: () -> ActiveHelpSession?
) {
    fun openAssist() {
        val session = currentSessionOrNotify() ?: return
        if (session.stage !in listOf(HelpStage.VERIFIED, HelpStage.ASSISTING)) {
            uiState.value = uiState.value.copy(bannerMessage = "请先完成视频验证")
            return
        }
        if (uiState.value.side == DeviceSide.HELPER) {
            historyManager.clearPendingHelperSession()
            callController.setSpeakerOutputEnabled(false)
        }
        configureRemoteController(session)
        uiState.value = uiState.value.copy(
            currentScreen = AppScreen.ASSIST,
            activeSession = session.copy(stage = HelpStage.ASSISTING),
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            bannerMessage = null,
            helperLocationPermissionGranted = false,
            helperLocationSummary = null,
            helperLocationUpdatedAt = null
        )
    }

    fun finishSession(
        reason: String,
        clearInviteEntry: Boolean = false,
        broadcastAssistEnded: Boolean = true
    ) {
        cancelHelperWaitTimeout()
        val session = uiState.value.activeSession
        if (
            broadcastAssistEnded &&
            session != null &&
            uiState.value.currentScreen == AppScreen.ASSIST
        ) {
            runCatching {
                callController.sendAppSignal(
                    SIGNAL_ASSIST_ENDED,
                    JSONObject()
                        .put("requestId", session.requestId)
                        .put("reason", reason),
                    broadcast = true
                )
            }.onFailure {
                AppLog.logThrowable("RemoteHelpCoordinator", it, "广播协助结束失败")
            }
        }
        callController.leaveRoom()
        remoteController.disconnect()
        if (session != null) {
            historyManager.saveHistory(
                SessionHistoryItem(
                    requestId = session.requestId,
                    helperName = session.helperName,
                    elderName = session.elderName,
                    elderPhone = session.elderPhone,
                    startedAt = session.createdAt,
                    endedAt = System.currentTimeMillis(),
                    endReason = reason
                )
            )
        }
        historyManager.clearPendingHelperSession()
        uiState.value = uiState.value.copy(
            history = historyManager.loadHistory(),
            inviteEntry = if (clearInviteEntry) "" else uiState.value.inviteEntry,
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = null,
            currentScreen = AppScreen.DASHBOARD,
            bannerMessage = reason,
            helperLocationPermissionGranted = false,
            helperLocationSummary = null,
            helperLocationUpdatedAt = null
        )
    }

    fun endCurrentSession(reason: String = "手动结束", clearInviteEntry: Boolean = false) {
        finishSession(reason, clearInviteEntry)
    }

    fun onControllerLeftAssist() {
        if (uiState.value.side != DeviceSide.ELDER || uiState.value.currentScreen != AppScreen.ASSIST) {
            return
        }
        finishSession("协助方已退出远程协助", broadcastAssistEnded = false)
    }

    fun handleCallSignal(signalType: String, payload: JSONObject): Boolean {
        val session = uiState.value.activeSession ?: return false
        return when (signalType) {
            SIGNAL_ASSIST_ENDED -> {
                val requestId = payload.optString("requestId")
                if (requestId == session.requestId && uiState.value.currentScreen == AppScreen.ASSIST) {
                    finishSession(payload.optString("reason").ifBlank {
                        "协助方已退出远程协助"
                    }, broadcastAssistEnded = false)
                    true
                } else {
                    false
                }
            }

            SIGNAL_HELPER_LOCATION -> {
                val requestId = payload.optString("requestId")
                if (requestId == session.requestId && uiState.value.side == DeviceSide.ELDER) {
                    val locationGranted = payload.optBoolean("locationPermissionGranted")
                    val locationSummary = payload.optString("locationSummary")
                        .takeIf { it.isNotBlank() }
                        ?: if (locationGranted) null else "待授权"
                    uiState.value = uiState.value.copy(
                        helperLocationPermissionGranted = locationGranted,
                        helperLocationSummary = locationSummary,
                        helperLocationUpdatedAt = payload.optLong("capturedAt").takeIf { it > 0L }
                    )
                    true
                } else {
                    false
                }
            }

            else -> false
        }
    }

    fun release() {
        cancelHelperWaitTimeout()
        callController.release()
        remoteController.release()
    }
}
private const val SIGNAL_ASSIST_ENDED = "assist_ended"
