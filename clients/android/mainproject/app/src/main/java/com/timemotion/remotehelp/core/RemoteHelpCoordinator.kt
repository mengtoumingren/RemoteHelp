package com.timemotion.remotehelp.core

import android.content.Context
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
    private val store = LocalHistoryStore(appContext)
    private val settingsStore = ConnectionSettingsStore(appContext)
    private val _uiState = MutableStateFlow(
        RemoteHelpUiState(
            serverUrl = settingsStore.loadServerUrl("ws://10.0.2.2:3000/ws"),
            helperName = settingsStore.loadHelperName("张三"),
            recentContacts = store.loadRecentContacts(),
            history = store.loadHistory()
        )
    )

    val callController = CallController(appContext, ::onCallSignal)
    val remoteController = RemoteControlController(appContext)
    val uiState: StateFlow<RemoteHelpUiState> = _uiState.asStateFlow()

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
            val token = HelpLinkCodec.encode(payload)
            val session = ActiveHelpSession(
                requestId = payload.requestId,
                helperName = payload.helperName,
                elderName = payload.elderName,
                elderPhone = payload.elderPhone,
                createdAt = payload.createdAt,
                expiresAt = payload.expiresAt,
                inviteToken = token,
                deepLink = HelpLinkCodec.buildDeepLink(token),
                stage = HelpStage.VERIFYING
            )
            _uiState.value = _uiState.value.copy(
                side = DeviceSide.ELDER,
                helperName = payload.helperName,
                elderName = payload.elderName,
                elderPhone = payload.elderPhone,
                inviteEntry = raw,
                activeSession = session,
                currentScreen = AppScreen.VERIFICATION,
                bannerMessage = "已识别短信验证链接，正在进入视频核验"
            )
            configureCallController(session)
        }.onFailure {
            callController.leaveRoom()
            remoteController.disconnect()
            _uiState.value = _uiState.value.copy(
                inviteEntry = "",
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
            _uiState.value = state.copy(bannerMessage = "请先输入长辈手机号")
            return
        }
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
            deepLink = HelpLinkCodec.buildDeepLink(token),
            stage = HelpStage.REQUEST_CREATED
        )
        _uiState.value = state.copy(
            recentContacts = store.loadRecentContacts(),
            activeSession = session,
            side = DeviceSide.HELPER,
            currentScreen = AppScreen.SESSION,
            bannerMessage = "协助请求已生成，正在等待长辈认证短信链接"
        )
        configureCallController(session)
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
        _uiState.value = _uiState.value.copy(
            currentScreen = AppScreen.VERIFICATION,
            activeSession = session.copy(stage = HelpStage.VERIFYING),
            bannerMessage = null
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
        val state = _uiState.value
        val session = state.activeSession
        if (session != null && state.side == DeviceSide.ELDER && !session.isExpired()) {
            callController.sendAppSignal(
                SIGNAL_VERIFICATION_LEFT,
                JSONObject().put("requestId", session.requestId)
            )
        }
        callController.leaveRoom()
        if (state.side == DeviceSide.HELPER && session != null) {
            showCurrentSession()
            return
        }
        _uiState.value = state.copy(
            inviteEntry = if (session?.isExpired() == true) "" else state.inviteEntry,
            activeSession = if (session?.isExpired() == true) null else session,
            currentScreen = AppScreen.DASHBOARD,
            bannerMessage = if (session?.isExpired() == true) {
                "链接已过期，请让子女重新发起"
            } else {
                "已退出身份验证，可重新打开短信链接再次进入"
            }
        )
    }

    fun onVerificationPeerReady() {
        val session = currentSessionOrNotify() ?: return
        if (_uiState.value.currentScreen == AppScreen.VERIFICATION) {
            return
        }
        configureCallController(session)
        _uiState.value = _uiState.value.copy(
            currentScreen = AppScreen.VERIFICATION,
            activeSession = session.copy(stage = HelpStage.VERIFYING),
            bannerMessage = "长辈已完成短信认证，正在进入视频核验"
        )
    }

    fun onRemoteParticipantUnexpectedExit() {
        val session = _uiState.value.activeSession ?: return
        if (_uiState.value.side != DeviceSide.HELPER) {
            return
        }
        callController.leaveRoom()
        remoteController.disconnect()
        if (session.isExpired()) {
            finishSession("短信链接已过期，请重新发起协助")
            return
        }
        val resetSession = session.copy(
            stage = HelpStage.REQUEST_CREATED,
            verificationAcceptedAt = null,
            endReason = null
        )
        configureCallController(resetSession)
        _uiState.value = _uiState.value.copy(
            currentScreen = AppScreen.SESSION,
            activeSession = resetSession,
            bannerMessage = "${session.elderName} 已退出，可在有效期内重新通过短信链接认证"
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
        finishSession("长辈拒绝协助")
    }

    fun openAssist() {
        val session = currentSessionOrNotify() ?: return
        if (session.stage !in listOf(HelpStage.VERIFIED, HelpStage.ASSISTING)) {
            _uiState.value = _uiState.value.copy(bannerMessage = "请先完成视频验证")
            return
        }
        configureRemoteController(session)
        _uiState.value = _uiState.value.copy(
            currentScreen = AppScreen.ASSIST,
            activeSession = session.copy(stage = HelpStage.ASSISTING),
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
        finishSession(reason)
    }

    fun release() {
        callController.release()
        remoteController.release()
    }

    private fun finishSession(reason: String) {
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
        _uiState.value = _uiState.value.copy(
            history = store.loadHistory(),
            activeSession = null,
            currentScreen = AppScreen.DASHBOARD,
            bannerMessage = reason
        )
    }

    private fun configureCallController(session: ActiveHelpSession) {
        callController.updateServerUrl(_uiState.value.serverUrl.trim())
        callController.updateRoomId(session.verificationRoomId)
        callController.updateDisplayName(currentSideDisplayName(session))
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

    private fun currentSideDisplayName(session: ActiveHelpSession): String {
        return if (_uiState.value.side == DeviceSide.HELPER) {
            session.helperName.ifBlank { "子女端" }
        } else {
            session.elderName.ifBlank { "长辈端" }
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
                    onRemoteParticipantUnexpectedExit()
                }
            }
        }
    }

    companion object {
        private const val SIGNAL_HELP_ACCEPT = "help_accept"
        private const val SIGNAL_HELP_REJECT = "help_reject"
        private const val SIGNAL_VERIFICATION_LEFT = "verification_left"
    }
}
