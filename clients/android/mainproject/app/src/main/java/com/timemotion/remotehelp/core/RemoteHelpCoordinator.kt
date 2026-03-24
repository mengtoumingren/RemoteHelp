package com.timemotion.remotehelp.core

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private val store = LocalHistoryStore(appContext)
    private val settingsStore = ConnectionSettingsStore(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var helperWaitTimeoutRunnable: Runnable? = null
    private val restoredHelperSession = store.loadPendingHelperSession()
        ?.takeUnless { it.isExpired() }
        ?.copy(stage = HelpStage.REQUEST_CREATED, verificationAcceptedAt = null, endReason = null)
    private val _uiState = MutableStateFlow(
        RemoteHelpUiState(
            serverUrl = settingsStore.loadServerUrl("ws://10.0.2.2:3000/ws"),
            helperName = settingsStore.loadHelperName("张三"),
            activeSession = restoredHelperSession,
            recentContacts = store.loadRecentContacts(),
            history = store.loadHistory(),
            bannerMessage = if (restoredHelperSession != null) "已恢复最近协助请求，等待对方进入视频核验" else null
        )
    )

    val callController = CallController(appContext, ::onCallSignal)
    val remoteController = RemoteControlController(appContext)
    val uiState: StateFlow<RemoteHelpUiState> = _uiState.asStateFlow()

    init {
        if (restoredHelperSession == null) {
            store.clearPendingHelperSession()
        } else {
            configureCallController(restoredHelperSession)
            ensureVerificationRoomConnected()
            startHelperWaitTimeout(restoredHelperSession.requestId)
        }
    }

    fun updateServerUrl(value: String) {
        _uiState.value = _uiState.value.copy(serverUrl = value)
    }

    fun updateHelperName(value: String) {
        _uiState.value = _uiState.value.copy(helperName = value)
    }

    fun updateElderName(value: String) {
        _uiState.value = _uiState.value.copy(elderName = value)
    }

    fun updateElderPhone(value: String) {
        _uiState.value = _uiState.value.copy(elderPhone = value)
    }

    fun updateInviteEntry(value: String) {
        _uiState.value = _uiState.value.copy(inviteEntry = value)
    }

    fun switchSide(side: DeviceSide) {
        _uiState.value = _uiState.value.copy(side = side, bannerMessage = null)
        val session = _uiState.value.activeSession ?: return
        configureRemoteController(session)
        configureCallController(session)
    }

    fun consumeInvite(raw: String) {
        HelpLinkCodec.parse(raw).onSuccess { payload ->
            callController.leaveRoom()
            remoteController.disconnect()
            val token = HelpLinkCodec.encode(payload)
            val session = ActiveHelpSession(
                requestId = payload.requestId,
                helperName = payload.helperName,
                elderName = payload.elderName,
                elderPhone = payload.elderPhone,
                createdAt = payload.createdAt,
                expiresAt = payload.expiresAt,
                inviteToken = token,
                deepLink = HelpLinkCodec.buildDeepLink(token, payload.sessionId),
                stage = HelpStage.VERIFYING
            )
            _uiState.value = _uiState.value.copy(
                side = DeviceSide.ELDER,
                helperName = payload.helperName,
                elderName = payload.elderName,
                elderPhone = payload.elderPhone,
                inviteEntry = raw,
                pendingInviteSession = session,
                isVerificationRequestVisible = false,
                activeSession = null,
                currentScreen = AppScreen.DASHBOARD,
                bannerMessage = "已识别协助链接，请确认信息后进入视频核验"
            )
            configureCallController(session)
            ensureVerificationRoomConnected()
        }.onFailure {
            callController.leaveRoom()
            remoteController.disconnect()
            _uiState.value = _uiState.value.copy(
                inviteEntry = "",
                pendingInviteSession = null,
                isVerificationRequestVisible = false,
                activeSession = null,
                currentScreen = AppScreen.DASHBOARD,
                bannerMessage = it.message ?: "链接解析失败"
            )
        }
    }

    fun createRequest() {
        val state = _uiState.value
        val phone = state.elderPhone.trim()
        if (phone.isBlank()) {
            _uiState.value = state.copy(bannerMessage = "请先输入协助对象手机号")
            return
        }
        cancelHelperWaitTimeout()
        callController.leaveRoom()
        val now = System.currentTimeMillis()
        val (payload, token) = HelpLinkCodec.createInvite(
            helperName = state.helperName,
            elderName = state.elderName,
            elderPhone = phone,
            now = now
        )
        store.saveRecentContact(payload.elderName, payload.elderPhone, now)
        val session = ActiveHelpSession(
            requestId = payload.requestId,
            helperName = payload.helperName,
            elderName = payload.elderName,
            elderPhone = payload.elderPhone,
            createdAt = payload.createdAt,
            expiresAt = payload.expiresAt,
            inviteToken = token,
            deepLink = HelpLinkCodec.buildDeepLink(token, payload.sessionId),
            stage = HelpStage.REQUEST_CREATED
        )
        _uiState.value = state.copy(
            recentContacts = store.loadRecentContacts(),
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = session,
            side = DeviceSide.HELPER,
            currentScreen = AppScreen.SESSION,
            bannerMessage = "协助请求已生成，正在等待对方认证短信链接"
        )
        store.savePendingHelperSession(session)
        configureCallController(session)
        ensureVerificationRoomConnected()
        startHelperWaitTimeout(session.requestId)
    }

    fun applyRecentContact(contact: RecentContact) {
        _uiState.value = _uiState.value.copy(
            elderName = contact.name,
            elderPhone = contact.phone,
            bannerMessage = "已填入最近联系人"
        )
    }

    fun openVerification() {
        val session = currentSessionOrNotify() ?: return
        if (session.isExpired()) {
            _uiState.value = _uiState.value.copy(bannerMessage = "请求已过期，请重新发起")
            return
        }
        configureCallController(session)
        ensureVerificationRoomConnected()
        _uiState.value = _uiState.value.copy(
            currentScreen = AppScreen.VERIFICATION,
            activeSession = session.copy(stage = HelpStage.VERIFYING),
            bannerMessage = null
        )
    }

    fun confirmPendingInvite() {
        val session = _uiState.value.pendingInviteSession ?: return
        if (session.isExpired()) {
            _uiState.value = _uiState.value.copy(
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
        _uiState.value = _uiState.value.copy(
            side = DeviceSide.ELDER,
            activeSession = session,
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            currentScreen = AppScreen.VERIFICATION,
            bannerMessage = "已确认协助信息，正在进入视频核验"
        )
    }

    fun dismissPendingInvite() {
        _uiState.value = _uiState.value.copy(
            inviteEntry = "",
            pendingInviteSession = null,
            isVerificationRequestVisible = false
        )
    }

    fun showCurrentSession() {
        if (_uiState.value.activeSession == null) {
            _uiState.value = _uiState.value.copy(bannerMessage = "当前没有有效会话")
            return
        }
        _uiState.value = _uiState.value.copy(currentScreen = AppScreen.SESSION, bannerMessage = null)
    }

    fun leaveVerification() {
        cancelHelperWaitTimeout()
        val state = _uiState.value
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
            store.savePendingHelperSession(resetSession)
            configureCallController(resetSession)
            _uiState.value = state.copy(
                activeSession = resetSession,
                isVerificationRequestVisible = false,
                currentScreen = AppScreen.SESSION,
                bannerMessage = "已退出视频认证，等待对方重新接入"
            )
            return
        }
        _uiState.value = state.copy(
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
        val state = _uiState.value
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
        _uiState.value = state.copy(
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
        val state = _uiState.value
        val session = state.activeSession ?: return
        callController.leaveRoom()
        remoteController.disconnect()
        if (session.isExpired()) {
            if (state.side == DeviceSide.HELPER) {
                finishSession("短信链接已过期，请重新发起协助")
            } else {
                _uiState.value = state.copy(
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
            store.savePendingHelperSession(resetSession)
            configureCallController(resetSession)
            _uiState.value = state.copy(
                currentScreen = AppScreen.SESSION,
                activeSession = resetSession,
                pendingInviteSession = null,
                isVerificationRequestVisible = false,
                bannerMessage = "${session.elderName} 已退出身份验证，可在有效期内重新通过短信链接认证"
            )
            return
        }
        _uiState.value = state.copy(
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = session,
            currentScreen = AppScreen.DASHBOARD,
            bannerMessage = "${session.helperName} 已退出身份验证，可重新打开短信链接再次进入"
        )
    }

    fun onVerificationPeerReady() {
        val session = currentSessionOrNotify() ?: return
        val state = _uiState.value
        if (state.side != DeviceSide.HELPER || !state.shouldShowVerificationRequestDialog()) {
            return
        }
        cancelHelperWaitTimeout()
        configureCallController(session)
        _uiState.value = _uiState.value.copy(
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
        _uiState.value = _uiState.value.copy(activeSession = acceptedSession)
        configureCallController(acceptedSession)
        if (!session.isExpired()) {
            callController.sendAppSignal(
                SIGNAL_VERIFICATION_ACCEPTED,
                JSONObject().put("requestId", session.requestId),
                broadcast = true
            )
        }
        _uiState.value = _uiState.value.copy(isVerificationRequestVisible = false)
        openVerification()
    }

    fun rejectVerificationRequest() {
        cancelHelperWaitTimeout()
        val state = _uiState.value
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
        store.savePendingHelperSession(resetSession)
        configureCallController(resetSession)
        _uiState.value = state.copy(
            activeSession = resetSession,
            isVerificationRequestVisible = false,
            bannerMessage = "已拒绝视频认证请求"
        )
    }

    fun expirePendingInvite(reason: String) {
        val state = _uiState.value
        if (state.side != DeviceSide.ELDER || state.pendingInviteSession == null) {
            return
        }
        cancelHelperWaitTimeout()
        callController.leaveRoom()
        _uiState.value = state.copy(
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
        callController.sendAppSignal(SIGNAL_HELP_ACCEPT, JSONObject().put("requestId", session.requestId))
        _uiState.value = _uiState.value.copy(
            activeSession = session.copy(
                stage = HelpStage.VERIFIED,
                verificationAcceptedAt = System.currentTimeMillis()
            ),
            bannerMessage = "已接受协助，可进入远程协助"
        )
        openAssist()
    }

    fun rejectVerification() {
        val session = currentSessionOrNotify() ?: return
        callController.sendAppSignal(SIGNAL_HELP_REJECT, JSONObject().put("requestId", session.requestId))
        finishSession("对方拒绝协助", clearInviteEntry = true)
    }

    fun notifyVerificationRequested() {
        val state = _uiState.value
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

    fun openAssist() {
        val session = currentSessionOrNotify() ?: return
        if (session.stage !in listOf(HelpStage.VERIFIED, HelpStage.ASSISTING)) {
            _uiState.value = _uiState.value.copy(bannerMessage = "请先完成视频验证")
            return
        }
        if (_uiState.value.side == DeviceSide.HELPER) {
            store.clearPendingHelperSession()
            callController.setSpeakerOutputEnabled(false)
        }
        configureRemoteController(session)
        _uiState.value = _uiState.value.copy(
            currentScreen = AppScreen.ASSIST,
            activeSession = session.copy(stage = HelpStage.ASSISTING),
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            bannerMessage = null
        )
    }

    fun backToDashboard() {
        _uiState.value = _uiState.value.copy(currentScreen = AppScreen.DASHBOARD)
    }

    fun dismissBanner() {
        _uiState.value = _uiState.value.copy(bannerMessage = null)
    }

    fun openSettings() {
        _uiState.value = _uiState.value.copy(isSettingsVisible = true)
    }

    fun openLogsDirectory() {
        val currentPath = AppLog.logDirectoryPath(appContext)
        runCatching {
            AppLog.openLogsDirectory(appContext)
        }.onFailure {
            _uiState.value = _uiState.value.copy(
                bannerMessage = if (currentPath.isNotBlank()) {
                    "无法打开日志目录：$currentPath"
                } else {
                    "无法打开日志目录"
                }
            )
            AppLog.logThrowable("RemoteHelpCoordinator", it, "打开日志目录失败")
        }
    }

    fun closeSettings() {
        _uiState.value = _uiState.value.copy(isSettingsVisible = false)
    }

    fun saveSettings() {
        settingsStore.save(
            serverUrl = _uiState.value.serverUrl.trim(),
            helperName = _uiState.value.helperName.trim()
        )
        _uiState.value = _uiState.value.copy(
            isSettingsVisible = false,
            bannerMessage = "设置已保存"
        )
    }

    fun endCurrentSession(reason: String = "手动结束") {
        finishSession(
            reason,
            clearInviteEntry = _uiState.value.side == DeviceSide.ELDER &&
                _uiState.value.currentScreen == AppScreen.ASSIST
        )
    }

    fun onControllerLeftAssist() {
        if (_uiState.value.side != DeviceSide.ELDER || _uiState.value.currentScreen != AppScreen.ASSIST) {
            return
        }
        finishSession("协助方已退出远程协助")
    }

    fun release() {
        cancelHelperWaitTimeout()
        callController.release()
        remoteController.release()
    }

    private fun finishSession(reason: String, clearInviteEntry: Boolean = false) {
        cancelHelperWaitTimeout()
        val session = _uiState.value.activeSession
        callController.leaveRoom()
        remoteController.disconnect()
        if (session != null) {
            store.saveHistory(
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
        store.clearPendingHelperSession()
        _uiState.value = _uiState.value.copy(
            history = store.loadHistory(),
            inviteEntry = if (clearInviteEntry) "" else _uiState.value.inviteEntry,
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = null,
            currentScreen = AppScreen.DASHBOARD,
            bannerMessage = reason
        )
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
                store.clearPendingHelperSession()
                _uiState.value = state.copy(
                    activeSession = null,
                    isVerificationRequestVisible = false,
                    currentScreen = AppScreen.DASHBOARD,
                    bannerMessage = "长时间未收到对方接入，已自动断开"
                )
            }
        }
        mainHandler.postDelayed(helperWaitTimeoutRunnable!!, HELPER_WAIT_TIMEOUT_MS)
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

    private fun onCallSignal(signalType: String, payload: JSONObject, fromDisplayName: String) {
        val session = _uiState.value.activeSession ?: return
        runCatching {
            when (signalType) {
                SIGNAL_HELP_ACCEPT -> {
                    _uiState.value = _uiState.value.copy(
                        activeSession = session.copy(
                            stage = HelpStage.VERIFIED,
                            verificationAcceptedAt = System.currentTimeMillis()
                        ),
                        bannerMessage = "${fromDisplayName.ifBlank { "对端" }} 已通过视频验证，正在进入远程协助"
                    )
                    openAssist()
                }

                SIGNAL_HELP_REJECT -> {
                    finishSession("${fromDisplayName.ifBlank { "对端" }} 拒绝了协助")
                }

                SIGNAL_VERIFICATION_LEFT -> {
                    val requestId = payload.optString("requestId")
                    if (requestId == session.requestId) {
                        onVerificationParticipantLeft()
                    }
                }

                SIGNAL_VERIFICATION_REQUESTED -> {
                    val requestId = payload.optString("requestId")
                    if (requestId == session.requestId && _uiState.value.side == DeviceSide.HELPER) {
                        onVerificationPeerReady()
                    }
                }

                SIGNAL_VERIFICATION_ACCEPTED -> {
                    val requestId = payload.optString("requestId")
                    if (requestId == session.requestId && _uiState.value.side == DeviceSide.ELDER) {
                        _uiState.value = _uiState.value.copy(
                            bannerMessage = "对方已接受视频认证，正在接入画面"
                        )
                    }
                }
            }
        }.onFailure {
            AppLog.logThrowable("RemoteHelpCoordinator", it, "处理协议信令失败: $signalType")
            _uiState.value = _uiState.value.copy(
                bannerMessage = "网络/信令异常，请检查连接"
            )
        }
    }

    companion object {
        private const val HELPER_WAIT_TIMEOUT_MS = 90_000L
        private const val SIGNAL_HELP_ACCEPT = "help_accept"
        private const val SIGNAL_HELP_REJECT = "help_reject"
        private const val SIGNAL_VERIFICATION_LEFT = "verification_left"
        private const val SIGNAL_VERIFICATION_REQUESTED = "verification_requested"
        private const val SIGNAL_VERIFICATION_ACCEPTED = "verification_accepted"
    }
}
