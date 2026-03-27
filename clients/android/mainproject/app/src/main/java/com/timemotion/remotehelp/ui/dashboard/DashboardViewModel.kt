package com.timemotion.remotehelp.ui.dashboard

import com.timemotion.remotehelp.core.ActiveHelpSession
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.core.RecentContact
import kotlinx.coroutines.flow.StateFlow

class DashboardViewModel(
    private val coordinator: RemoteHelpCoordinator
) {
    val uiState: StateFlow<RemoteHelpUiState> = coordinator.uiState

    fun updateServerUrl(value: String) = coordinator.updateServerUrl(value)
    fun updateHelperName(value: String) = coordinator.updateHelperName(value)
    fun openSettings() = coordinator.openSettings()
    fun closeSettings() = coordinator.closeSettings()
    fun openLogs() = coordinator.openLogs()
    fun openEvidence() = coordinator.openEvidence()
    fun goBackDashboardPage() = coordinator.goBackDashboardPage()
    fun closeDashboardPage() = coordinator.closeDashboardPage()
    fun saveSettings() = coordinator.saveSettings()
    fun dismissBanner() = coordinator.dismissBanner()
    fun switchSide(side: DeviceSide) = coordinator.switchSide(side)
    fun updateElderPhone(value: String) = coordinator.updateElderPhone(value)
    fun updateElderName(value: String) = coordinator.updateElderName(value)
    fun updateInviteEntry(value: String) = coordinator.updateInviteEntry(value)
    fun createRequest() = coordinator.createRequest()
    fun applyRecentContact(contact: RecentContact) = coordinator.applyRecentContact(contact)
    fun consumeInvite(raw: String) = coordinator.consumeInvite(raw)
    fun acceptVerificationRequest() = coordinator.acceptVerificationRequest()
    fun rejectVerificationRequest() = coordinator.rejectVerificationRequest()
    fun confirmPendingInvite() = coordinator.confirmPendingInvite()
    fun dismissPendingInvite() = coordinator.dismissPendingInvite()
    fun updateHelperLocationPermissionGranted(granted: Boolean) =
        coordinator.updateHelperLocationPermissionGranted(granted)

    val activeSession: ActiveHelpSession?
        get() = uiState.value.activeSession
}
