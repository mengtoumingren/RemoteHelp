package com.timemotion.remotehelp.core

import com.timemotion.remotehelp.remote.RemoteControlUiState

/*
 * Screen contract:
 * - DASHBOARD / SESSION: can surface verification requests.
 * - VERIFICATION: can prepare verification media, join verification WS room, and broadcast verification requests.
 * - ASSIST: can handle assist permissions and controller-exit signals.
 */
internal fun RemoteHelpUiState.shouldPrepareVerificationMedia(): Boolean =
    currentScreen == AppScreen.VERIFICATION

internal fun RemoteHelpUiState.shouldJoinHiddenVerificationRoom(): Boolean =
    side == DeviceSide.HELPER &&
        activeSession?.stage == HelpStage.REQUEST_CREATED &&
        currentScreen in setOf(AppScreen.DASHBOARD, AppScreen.SESSION)

internal fun RemoteHelpUiState.shouldJoinVisibleVerificationRoom(): Boolean =
    currentScreen == AppScreen.VERIFICATION

internal fun RemoteHelpUiState.shouldShowVerificationRequestDialog(): Boolean =
    side == DeviceSide.HELPER &&
        activeSession != null &&
        currentScreen in setOf(AppScreen.DASHBOARD, AppScreen.SESSION)

internal fun RemoteHelpUiState.shouldBroadcastVerificationRequested(): Boolean =
    side == DeviceSide.ELDER &&
        currentScreen == AppScreen.VERIFICATION &&
        activeSession?.stage == HelpStage.VERIFYING

internal fun RemoteHelpUiState.shouldShowAssistPermissionPrompt(remoteState: RemoteControlUiState): Boolean =
    currentScreen == AppScreen.ASSIST &&
        side == DeviceSide.ELDER &&
        activeSession != null &&
        (!remoteState.targetStatus.captureActive || !remoteState.targetStatus.accessibilityEnabled)

internal fun RemoteHelpUiState.shouldListenForAssistExit(): Boolean =
    side == DeviceSide.ELDER &&
        currentScreen == AppScreen.ASSIST &&
        activeSession != null
