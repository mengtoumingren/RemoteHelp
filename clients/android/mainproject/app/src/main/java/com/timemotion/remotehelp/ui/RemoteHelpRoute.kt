package com.timemotion.remotehelp.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.timemotion.remotehelp.core.AppScreen
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.remote.RemoteControlUiState
import com.timemotion.remotehelp.ui.dashboard.DashboardRoute
import com.timemotion.remotehelp.ui.dashboard.DashboardViewModel
import com.timemotion.remotehelp.ui.remote.HelperLocationSignalHost
import com.timemotion.remotehelp.ui.remote.SecurityEvidenceCaptureHost
import com.timemotion.remotehelp.ui.remote.RemoteAssistRouteHost
import com.timemotion.remotehelp.ui.session.SessionRoute
import com.timemotion.remotehelp.ui.session.SessionViewModel
import com.timemotion.remotehelp.ui.shared.UiFeedbackHost
import com.timemotion.remotehelp.ui.verification.VerificationRouteHost
import com.timemotion.remotehelp.webrtc.CallUiState

@Composable
fun RemoteHelpRoute(
    coordinator: RemoteHelpCoordinator,
    uiState: RemoteHelpUiState,
    callState: CallUiState,
    remoteState: RemoteControlUiState,
    projectionIntent: Intent,
    onRequestHelperOverlayShow: () -> Unit
) {
    val context = LocalContext.current
    val dashboardViewModel = remember(coordinator) { DashboardViewModel(coordinator) }
    val sessionViewModel = remember(coordinator) { SessionViewModel(coordinator) }
    val shouldCaptureSecurityEvidence =
        uiState.side == DeviceSide.ELDER &&
            uiState.activeSession?.verificationAcceptedAt != null &&
            uiState.currentScreen in setOf(AppScreen.VERIFICATION, AppScreen.ASSIST)

    UiFeedbackHost()
    SecurityEvidenceCaptureHost(
        enabled = shouldCaptureSecurityEvidence,
        sessionId = uiState.activeSession?.requestId,
        helperName = uiState.activeSession?.helperName ?: uiState.helperName,
        elderName = uiState.activeSession?.elderName ?: uiState.elderName,
        callStatus = callState.status,
        remoteState = remoteState,
        locationPermissionGranted = uiState.helperLocationPermissionGranted,
        helperLocationSummary = uiState.helperLocationSummary,
        helperLocationUpdatedAt = uiState.helperLocationUpdatedAt,
        captureHelperCameraBitmap = { coordinator.callController.captureRemoteVideoBitmap() },
        onCaptureSaved = { }
    )
    HelperLocationSignalHost(
        coordinator = coordinator,
        uiState = uiState
    )

    when (uiState.currentScreen) {
        com.timemotion.remotehelp.core.AppScreen.DASHBOARD -> DashboardRoute(dashboardViewModel)
        com.timemotion.remotehelp.core.AppScreen.SESSION -> SessionRoute(sessionViewModel)
        com.timemotion.remotehelp.core.AppScreen.VERIFICATION -> VerificationRouteHost(
            coordinator = coordinator,
            uiState = uiState,
            callState = callState,
            context = context
        )
        com.timemotion.remotehelp.core.AppScreen.ASSIST -> RemoteAssistRouteHost(
            coordinator = coordinator,
            uiState = uiState,
            callState = callState,
            remoteState = remoteState,
            projectionIntent = projectionIntent,
            onRequestHelperOverlayShow = onRequestHelperOverlayShow
        )
    }
}
