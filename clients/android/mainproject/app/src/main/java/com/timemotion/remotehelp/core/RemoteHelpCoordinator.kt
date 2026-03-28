package com.timemotion.remotehelp.core

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.remote.RemoteControlController
import com.timemotion.remotehelp.remote.RemoteRole
import com.timemotion.remotehelp.webrtc.CallController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class RemoteHelpCoordinator(
    context: Context
) {
    private val appContext = context.applicationContext
    init {
        AppLog.install(appContext)
    }
    private val historyManager = RemoteHelpHistoryManager(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var helperWaitTimeoutRunnable: Runnable? = null
    private val restoredHelperSession = historyManager.loadRestoredHelperSession()
    private val _uiState = MutableStateFlow(
        RemoteHelpUiState(
            serverUrl = ConnectionSettingsStore(appContext).loadServerUrl("ws://10.0.2.2:3000/ws"),
            helperName = ConnectionSettingsStore(appContext).loadHelperName("张三"),
            stunServer = ConnectionSettingsStore(appContext).loadStunServer("stun:stun.timemotion.top:3478"),
            turnServer = ConnectionSettingsStore(appContext).loadTurnServer(""),
            turnUsername = ConnectionSettingsStore(appContext).loadTurnUsername(""),
            turnPassword = ConnectionSettingsStore(appContext).loadTurnPassword(""),
            activeSession = restoredHelperSession,
            recentContacts = historyManager.loadRecentContacts(),
            history = historyManager.loadHistory(),
            bannerMessage = if (restoredHelperSession != null) "已恢复最近协助请求，等待对方进入视频核验" else null
        )
    )

    val callController = CallController(appContext, ::onCallSignal)
    val remoteController = RemoteControlController(appContext)
    val uiState: StateFlow<RemoteHelpUiState> = _uiState.asStateFlow()
    private val settingsManager = RemoteHelpSettingsManager(appContext, _uiState)
    private val assistManager = RemoteHelpAssistManager(
        uiState = _uiState,
        historyManager = historyManager,
        callController = callController,
        remoteController = remoteController,
        configureRemoteController = ::configureRemoteController,
        cancelHelperWaitTimeout = ::cancelHelperWaitTimeout,
        currentSessionOrNotify = ::currentSessionOrNotify
    )
    private val verificationManager = RemoteHelpVerificationManager(
        uiState = _uiState,
        historyManager = historyManager,
        callController = callController,
        remoteController = remoteController,
        configureCallController = ::configureCallController,
        cancelHelperWaitTimeout = ::cancelHelperWaitTimeout,
        currentSessionOrNotify = ::currentSessionOrNotify,
        shouldAutoEnterAssist = ::shouldAutoEnterAssist,
        openAssist = assistManager::openAssist,
        finishSession = assistManager::finishSession,
        mainHandler = mainHandler
    )
    private val sessionManager = RemoteHelpSessionManager(
        uiState = _uiState,
        historyManager = historyManager,
        callController = callController,
        remoteController = remoteController,
        configureCallController = ::configureCallController,
        configureRemoteController = ::configureRemoteController,
        ensureVerificationRoomConnected = ::ensureVerificationRoomConnected,
        cancelHelperWaitTimeout = ::cancelHelperWaitTimeout,
        mainHandler = mainHandler
    )

    init {
        remoteController.onScreenShareStartRequested = callController::startScreenShareCapture
        remoteController.onScreenShareStopRequested = callController::stopScreenShareCapture
        if (restoredHelperSession == null) {
            historyManager.clearPendingHelperSession()
        } else {
            configureCallController(restoredHelperSession)
            ensureVerificationRoomConnected()
            startHelperWaitTimeout(restoredHelperSession.requestId)
        }
    }

    fun updateServerUrl(value: String) {
        settingsManager.updateServerUrl(value)
    }

    fun updateHelperName(value: String) {
        settingsManager.updateHelperName(value)
    }

    fun updateStunServer(value: String) {
        settingsManager.updateStunServer(value)
    }

    fun updateTurnServer(value: String) {
        settingsManager.updateTurnServer(value)
    }

    fun updateTurnUsername(value: String) {
        settingsManager.updateTurnUsername(value)
    }

    fun updateTurnPassword(value: String) {
        settingsManager.updateTurnPassword(value)
    }

    fun updateElderName(value: String) {
        sessionManager.updateElderName(value)
    }

    fun updateElderPhone(value: String) {
        sessionManager.updateElderPhone(value)
    }

    fun updateInviteEntry(value: String) {
        sessionManager.updateInviteEntry(value)
    }

    fun switchSide(side: DeviceSide) {
        sessionManager.switchSide(side)
    }

    fun consumeInvite(raw: String) {
        sessionManager.consumeInvite(raw)
    }

    fun createRequest() {
        sessionManager.createRequest()
    }

    fun ensureCurrentSessionRoomConnected() {
        val session = _uiState.value.activeSession ?: return
        configureCallController(session)
        ensureVerificationRoomConnected()
    }

    fun applyRecentContact(contact: RecentContact) {
        sessionManager.applyRecentContact(contact)
    }

    fun openVerification() {
        currentSessionOrNotify()?.let { verificationManager.openVerification(it) }
    }

    fun confirmPendingInvite() {
        _uiState.value.pendingInviteSession?.let { verificationManager.confirmPendingInvite(it) }
    }

    fun dismissPendingInvite() {
        verificationManager.dismissPendingInvite()
    }

    fun showCurrentSession() {
        sessionManager.showCurrentSession()
    }

    fun leaveVerification() {
        verificationManager.leaveVerification()
    }

    fun failVerificationDueToRemoteTimeout() {
        verificationManager.failVerificationDueToRemoteTimeout()
    }

    fun onVerificationParticipantLeft() {
        verificationManager.onVerificationParticipantLeft()
    }

    fun onVerificationPeerReady() {
        verificationManager.onVerificationPeerReady()
    }

    fun acceptVerificationRequest() {
        verificationManager.acceptVerificationRequest()
    }

    fun rejectVerificationRequest() {
        verificationManager.rejectVerificationRequest()
    }

    fun expirePendingInvite(reason: String) {
        verificationManager.expirePendingInvite(reason)
    }

    fun acceptVerification() {
        verificationManager.acceptVerification()
    }

    fun rejectVerification() {
        verificationManager.rejectVerification()
    }

    fun notifyVerificationRequested() {
        verificationManager.notifyVerificationRequested()
    }

    fun openAssist() {
        assistManager.openAssist()
    }

    fun backToDashboard() {
        sessionManager.backToDashboard()
    }

    fun dismissBanner() {
        sessionManager.dismissBanner()
    }

    fun openSettings() {
        sessionManager.openSettings()
    }

    fun closeSettings() {
        sessionManager.closeSettings()
    }

    fun openLogs() {
        sessionManager.openLogs()
    }

    fun openEvidence() {
        sessionManager.openEvidence()
    }

    fun goBackDashboardPage() {
        sessionManager.goBackDashboardPage()
    }

    fun closeDashboardPage() {
        sessionManager.closeDashboardPage()
    }

    fun updateHelperLocationPermissionGranted(granted: Boolean) {
        _uiState.value = _uiState.value.copy(
            helperLocationPermissionGranted = granted,
            helperLocationSummary = if (granted) _uiState.value.helperLocationSummary else null,
            helperLocationUpdatedAt = if (granted) _uiState.value.helperLocationUpdatedAt else null
        )
    }

    fun saveSettings() {
        settingsManager.saveSettings()
    }

    fun endCurrentSession(reason: String = "手动结束") {
        assistManager.endCurrentSession(
            reason,
            clearInviteEntry = _uiState.value.side == DeviceSide.ELDER &&
                _uiState.value.currentScreen == AppScreen.ASSIST
        )
    }

    fun onControllerLeftAssist() {
        assistManager.onControllerLeftAssist()
    }

    fun release() {
        assistManager.release()
    }

    private fun configureCallController(session: ActiveHelpSession) {
        callController.updateServerUrl(_uiState.value.serverUrl.trim())
        callController.updateRoomId(session.verificationRoomId)
        callController.updateDisplayName(currentSideDisplayName(session))
        callController.setOfferInitiator(_uiState.value.side == DeviceSide.HELPER)
        callController.setRtcOfferAllowed(session.verificationAcceptedAt != null)
    }

    private fun configureRemoteController(session: ActiveHelpSession) {
        remoteController.updateServerUrl(_uiState.value.serverUrl.trim())
        remoteController.updateRoomId(session.remoteRoomId)
        remoteController.updateDisplayName(currentSideDisplayName(session))
        remoteController.selectRole(
            if (_uiState.value.side == DeviceSide.HELPER) {
                RemoteRole.CONTROLLER
            } else {
                RemoteRole.TARGET
            }
        )
    }

    private fun ensureVerificationRoomConnected() {
        val callState = callController.uiState.value
        if (callState.isInRoom || callState.isConnecting) {
            return
        }
        callController.joinRoom(prepareLocalMedia = false)
    }

    private fun startHelperWaitTimeout(sessionId: String) {
        cancelHelperWaitTimeout()
        helperWaitTimeoutRunnable = Runnable {
            val state = _uiState.value
            val session = state.activeSession ?: return@Runnable
            if (
                state.side == DeviceSide.HELPER &&
                state.currentScreen == AppScreen.SESSION &&
                state.activeSession?.requestId == sessionId &&
                session.stage == HelpStage.REQUEST_CREATED
            ) {
                callController.leaveRoom()
                historyManager.clearPendingHelperSession()
                _uiState.value = state.copy(
                    activeSession = null,
                    isVerificationRequestVisible = false,
                    currentScreen = AppScreen.DASHBOARD,
                    bannerMessage = "长时间未收到对方接入，已自动断开"
                )
            }
        }
        helperWaitTimeoutRunnable?.let { mainHandler.postDelayed(it, HELPER_WAIT_TIMEOUT_MS) }
    }

    fun cancelHelperWaitTimeout() {
        helperWaitTimeoutRunnable?.let(mainHandler::removeCallbacks)
        helperWaitTimeoutRunnable = null
    }

    private fun currentSideDisplayName(session: ActiveHelpSession): String {
        return if (_uiState.value.side == DeviceSide.HELPER) {
            session.helperName.ifBlank { "我要协助" }
        } else {
            session.elderName.ifBlank { "需要协助" }
        }
    }

    private fun currentSessionOrNotify(): ActiveHelpSession? {
        val session = _uiState.value.activeSession
        if (session == null) {
            _uiState.value = _uiState.value.copy(bannerMessage = "当前没有有效会话")
        }
        return session
    }

    private fun shouldAutoEnterAssist(): Boolean = !isLikelyEmulator()

    private fun isLikelyEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.PRODUCT.contains("emulator", ignoreCase = true) ||
            Build.PRODUCT.contains("vbox", ignoreCase = true)
    }

    private fun onCallSignal(signalType: String, payload: JSONObject, fromDisplayName: String) {
        val handled = verificationManager.handleCallSignal(signalType, payload, fromDisplayName) ||
            assistManager.handleCallSignal(signalType, payload)
        if (!handled) return
    }

    companion object {
        private const val HELPER_WAIT_TIMEOUT_MS = 90_000L
    }
}
