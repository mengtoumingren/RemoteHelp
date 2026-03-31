package com.timemotion.remotehelp.core

import android.os.Handler
import com.timemotion.remotehelp.remote.RemoteControlController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class RemoteHelpSessionManager(
    private val uiState: MutableStateFlow<RemoteHelpUiState>,
    private val historyManager: RemoteHelpHistoryManager,
    private val callController: com.timemotion.remotehelp.webrtc.CallController,
    private val remoteController: RemoteControlController,
    private val serverApiClient: ServerApiClient,
    private val configureCallController: (ActiveHelpSession) -> Unit,
    private val configureRemoteController: (ActiveHelpSession) -> Unit,
    private val ensureVerificationRoomConnected: () -> Unit,
    private val cancelHelperWaitTimeout: () -> Unit,
    private val mainHandler: Handler,
    private val scope: CoroutineScope
) {
    fun updateElderName(value: String) {
        uiState.value = uiState.value.copy(elderName = value)
    }

    fun updateElderPhone(value: String) {
        uiState.value = uiState.value.copy(elderPhone = value)
    }

    fun updateInviteEntry(value: String) {
        uiState.value = uiState.value.copy(inviteEntry = value)
    }

    fun switchSide(side: DeviceSide) {
        uiState.value = uiState.value.copy(
            side = side,
            bannerMessage = null,
            helperLocationPermissionGranted = false,
            helperLocationSummary = null,
            helperLocationUpdatedAt = null
        )
        val session = uiState.value.activeSession ?: return
        configureRemoteController(session)
        configureCallController(session)
    }

    fun consumeInvite(raw: String) {
        val state = uiState.value
        uiState.value = state.copy(bannerMessage = "正在校验协助链接")
        scope.launch {
            runCatching {
                serverApiClient.resolveInvite(state.serverUrl, raw)
            }.onSuccess { invite ->
                callController.leaveRoom()
                remoteController.disconnect()
                val session = ActiveHelpSession(
                    requestId = invite.requestId,
                    helperName = invite.helperName,
                    elderName = invite.elderName,
                    elderPhone = invite.elderPhone,
                    createdAt = invite.createdAt,
                    expiresAt = invite.expiresAt,
                    inviteToken = invite.inviteToken,
                    channelToken = invite.channelToken,
                    deepLink = invite.deepLink,
                    stage = HelpStage.VERIFYING
                )
                uiState.value = uiState.value.copy(
                    side = DeviceSide.ELDER,
                    helperName = invite.helperName,
                    elderName = invite.elderName,
                    elderPhone = invite.elderPhone,
                    inviteEntry = raw,
                    pendingInviteSession = session,
                    isVerificationRequestVisible = false,
                    activeSession = null,
                    currentScreen = AppScreen.DASHBOARD,
                    bannerMessage = "已识别协助链接，请确认信息后进入视频核验",
                    helperLocationPermissionGranted = false,
                    helperLocationSummary = null,
                    helperLocationUpdatedAt = null
                )
                configureCallController(session)
                ensureVerificationRoomConnected()
            }.onFailure {
                callController.leaveRoom()
                remoteController.disconnect()
                uiState.value = uiState.value.copy(
                    inviteEntry = "",
                    pendingInviteSession = null,
                    isVerificationRequestVisible = false,
                    activeSession = null,
                    currentScreen = AppScreen.DASHBOARD,
                    bannerMessage = it.message ?: "链接解析失败"
                )
            }
        }
    }

    fun createRequest() {
        val state = uiState.value
        val phone = state.elderPhone.trim()
        if (phone.isBlank()) {
            uiState.value = state.copy(bannerMessage = "请先输入协助对象手机号")
            return
        }
        cancelHelperWaitTimeout()
        callController.leaveRoom()
        remoteController.disconnect()
        val payload = HelpLinkCodec.createInvitePayload(
            helperName = state.helperName,
            elderName = state.elderName,
            elderPhone = phone,
            now = System.currentTimeMillis()
        )
        val pendingSession = ActiveHelpSession(
            requestId = payload.requestId,
            helperName = payload.helperName,
            elderName = payload.elderName,
            elderPhone = payload.elderPhone,
            createdAt = payload.createdAt,
            expiresAt = payload.expiresAt,
            inviteToken = "",
            channelToken = "",
            deepLink = "",
            stage = HelpStage.REQUEST_CREATED
        )
        uiState.value = state.copy(
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = pendingSession,
            side = DeviceSide.HELPER,
            currentScreen = AppScreen.SESSION,
            bannerMessage = "正在生成安全协助链接",
            helperLocationPermissionGranted = false,
            helperLocationSummary = null,
            helperLocationUpdatedAt = null
        )
        scope.launch {
            runCatching {
                serverApiClient.createInvite(state.serverUrl, payload)
            }.onSuccess { invite ->
                historyManager.saveRecentContact(invite.elderName, invite.elderPhone, invite.createdAt)
                val session = ActiveHelpSession(
                    requestId = invite.requestId,
                    helperName = invite.helperName,
                    elderName = invite.elderName,
                    elderPhone = invite.elderPhone,
                    createdAt = invite.createdAt,
                    expiresAt = invite.expiresAt,
                    inviteToken = invite.inviteToken,
                    channelToken = invite.channelToken,
                    deepLink = invite.deepLink,
                    stage = HelpStage.REQUEST_CREATED
                )
                historyManager.savePendingHelperSession(session)
                uiState.value = uiState.value.copy(
                    recentContacts = historyManager.loadRecentContacts(),
                    activeSession = session,
                    bannerMessage = "协助请求已生成，正在等待对方认证短信链接"
                )
                configureCallController(session)
                ensureVerificationRoomConnected()
            }.onFailure {
                historyManager.clearPendingHelperSession()
                callController.leaveRoom()
                uiState.value = uiState.value.copy(
                    activeSession = null,
                    currentScreen = AppScreen.DASHBOARD,
                    bannerMessage = it.message ?: "安全链接生成失败"
                )
            }
        }
    }

    fun applyRecentContact(contact: RecentContact) {
        uiState.value = uiState.value.copy(
            elderName = contact.name,
            elderPhone = contact.phone,
            bannerMessage = "已填入最近联系人"
        )
    }

    fun showCurrentSession() {
        if (uiState.value.activeSession == null) {
            uiState.value = uiState.value.copy(bannerMessage = "当前没有有效会话")
            return
        }
        uiState.value = uiState.value.copy(currentScreen = AppScreen.SESSION, bannerMessage = null)
    }

    fun backToDashboard() {
        uiState.value = uiState.value.copy(currentScreen = AppScreen.DASHBOARD)
    }

    fun dismissBanner() {
        uiState.value = uiState.value.copy(bannerMessage = null)
    }

    fun openSettings() {
        openDashboardPage(DashboardPage.SETTINGS)
    }

    fun closeSettings() {
        closeDashboardPage()
    }

    fun openLogs() {
        openDashboardPage(DashboardPage.LOGS)
    }

    fun openEvidence() {
        openDashboardPage(DashboardPage.EVIDENCE)
    }

    fun goBackDashboardPage() {
        val state = uiState.value
        val target = state.dashboardPreviousPage ?: DashboardPage.MAIN
        uiState.value = state.copy(
            dashboardPage = target,
            dashboardPreviousPage = null
        )
    }

    fun closeDashboardPage() {
        uiState.value = uiState.value.copy(
            dashboardPage = DashboardPage.MAIN,
            dashboardPreviousPage = null
        )
    }

    private fun openDashboardPage(page: DashboardPage) {
        val state = uiState.value
        if (state.dashboardPage == page) {
            return
        }
        uiState.value = state.copy(
            dashboardPreviousPage = state.dashboardPage,
            dashboardPage = page
        )
    }
}
