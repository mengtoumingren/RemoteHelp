package com.timemotion.remotehelp.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.AppScreen
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.core.SIGNAL_HELPER_LOCATION
import com.timemotion.remotehelp.core.readRealtimeLocationSnapshot
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun HelperLocationSignalHost(
    coordinator: RemoteHelpCoordinator,
    uiState: RemoteHelpUiState
) {
    val context = LocalContext.current
    val latestUiState by rememberUpdatedState(uiState)

    LaunchedEffect(
        uiState.side,
        uiState.currentScreen,
        uiState.activeSession?.requestId,
        uiState.activeSession?.verificationAcceptedAt
    ) {
        if (
            uiState.side != DeviceSide.HELPER ||
            uiState.activeSession?.verificationAcceptedAt == null ||
            uiState.currentScreen !in setOf(AppScreen.VERIFICATION, AppScreen.ASSIST)
        ) {
            return@LaunchedEffect
        }
        delay(1500L)
        while (
            latestUiState.side == DeviceSide.HELPER &&
            latestUiState.currentScreen in setOf(AppScreen.VERIFICATION, AppScreen.ASSIST) &&
            latestUiState.activeSession?.verificationAcceptedAt != null
        ) {
            val locationSummary = runCatching {
                context.readRealtimeLocationSnapshot()?.toSummary()
            }.getOrElse {
                AppLog.logThrowable("RemoteHelpApp", it, "读取协助方定位失败")
                null
            }
            val payload = JSONObject()
                .put("requestId", latestUiState.activeSession?.requestId.orEmpty())
                .put("capturedAt", System.currentTimeMillis())
                .put("locationPermissionGranted", latestUiState.helperLocationPermissionGranted)
                .put("locationSummary", locationSummary)
            runCatching {
                coordinator.callController.sendAppSignal(SIGNAL_HELPER_LOCATION, payload)
            }.onFailure {
                AppLog.logThrowable("RemoteHelpApp", it, "上报协助方定位失败")
            }
            delay(10_000L)
        }
    }
}
