package com.timemotion.remotehelp.core

import android.os.Handler
import com.timemotion.remotehelp.remote.RemoteControlController
import kotlinx.coroutines.flow.MutableStateFlow

class RemoteHelpSessionManager(
    private val uiState: MutableStateFlow<RemoteHelpUiState>,
    private val historyManager: RemoteHelpHistoryManager,
    private val callController: com.timemotion.remotehelp.webrtc.CallController,
    private val remoteController: RemoteControlController,
    private val configureCallController: (ActiveHelpSession) -> Unit,
    private val configureRemoteController: (ActiveHelpSession) -> Unit,
    private val ensureVerificationRoomConnected: () -> Unit,
    private val cancelHelperWaitTimeout: () -> Unit,
    private val mainHandler: Handler
) {
    private var pendingInviteLinkBuildRunnable: Runnable? = null
    private var pendingInvitePayload: HelpInvitePayload? = null
    private var pendingInviteBuildStartedAt: Long = 0L

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
        cancelPendingInviteLinkBuild()
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
            uiState.value = uiState.value.copy(
                side = DeviceSide.ELDER,
                helperName = payload.helperName,
                elderName = payload.elderName,
                elderPhone = payload.elderPhone,
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

    fun createRequest() {
        val state = uiState.value
        val phone = state.elderPhone.trim()
        if (phone.isBlank()) {
            uiState.value = state.copy(bannerMessage = "请先输入协助对象手机号")
            return
        }
        cancelHelperWaitTimeout()
        callController.leaveRoom()
        val now = System.currentTimeMillis()
        val payload = HelpLinkCodec.createInvitePayload(
            helperName = state.helperName,
            elderName = state.elderName,
            elderPhone = phone,
            now = now
        )
        historyManager.saveRecentContact(payload.elderName, payload.elderPhone, now)
        val session = ActiveHelpSession(
            requestId = payload.requestId,
            helperName = payload.helperName,
            elderName = payload.elderName,
            elderPhone = payload.elderPhone,
            createdAt = payload.createdAt,
            expiresAt = payload.expiresAt,
            inviteToken = "",
            deepLink = "",
            stage = HelpStage.REQUEST_CREATED
        )
        uiState.value = state.copy(
            recentContacts = historyManager.loadRecentContacts(),
            pendingInviteSession = null,
            isVerificationRequestVisible = false,
            activeSession = session,
            side = DeviceSide.HELPER,
            currentScreen = AppScreen.SESSION,
            bannerMessage = "协助请求已生成，正在连接房间",
            helperLocationPermissionGranted = false,
            helperLocationSummary = null,
            helperLocationUpdatedAt = null
        )
        historyManager.savePendingHelperSession(session)
        configureCallController(session)
        ensureVerificationRoomConnected()
        waitForVerificationRoomReadyThenBuildInvite(session.requestId, payload)
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

    private fun waitForVerificationRoomReadyThenBuildInvite(sessionId: String, payload: HelpInvitePayload) {
        cancelPendingInviteLinkBuild()
        pendingInvitePayload = payload
        pendingInviteBuildStartedAt = System.currentTimeMillis()
        pendingInviteLinkBuildRunnable = object : Runnable {
            override fun run() {
                val currentSession = uiState.value.activeSession
                val currentSessionId = currentSession?.requestId
                if (
                    currentSessionId != sessionId ||
                    uiState.value.side != DeviceSide.HELPER ||
                    uiState.value.currentScreen != AppScreen.SESSION
                ) {
                    cancelPendingInviteLinkBuild()
                    return
                }
                val callState = callController.uiState.value
                if (callState.isInRoom) {
                    val payloadSnapshot = pendingInvitePayload ?: payload
                    val token = HelpLinkCodec.encode(payloadSnapshot)
                    val sessionWithLink = currentSession.copy(
                        inviteToken = token,
                        deepLink = HelpLinkCodec.buildDeepLink(token, payloadSnapshot.sessionId)
                    )
                    historyManager.savePendingHelperSession(sessionWithLink)
                    uiState.value = uiState.value.copy(
                        activeSession = sessionWithLink,
                        bannerMessage = "协助请求已生成，正在等待对方认证短信链接"
                    )
                    cancelPendingInviteLinkBuild()
                    return
                }
                val elapsed = System.currentTimeMillis() - pendingInviteBuildStartedAt
                if (elapsed >= PENDING_INVITE_ROOM_TIMEOUT_MS) {
                    cancelPendingInviteLinkBuild()
                    callController.leaveRoom()
                    historyManager.clearPendingHelperSession()
                    uiState.value = uiState.value.copy(
                        activeSession = null,
                        currentScreen = AppScreen.DASHBOARD,
                        bannerMessage = "房间创建失败，请重试"
                    )
                    return
                }
                mainHandler.postDelayed(this, PENDING_INVITE_POLL_INTERVAL_MS)
            }
        }
        mainHandler.post(pendingInviteLinkBuildRunnable!!)
    }

    private fun cancelPendingInviteLinkBuild() {
        pendingInviteLinkBuildRunnable?.let(mainHandler::removeCallbacks)
        pendingInviteLinkBuildRunnable = null
        pendingInvitePayload = null
        pendingInviteBuildStartedAt = 0L
    }
}

private const val PENDING_INVITE_POLL_INTERVAL_MS = 200L
private const val PENDING_INVITE_ROOM_TIMEOUT_MS = 15_000L
