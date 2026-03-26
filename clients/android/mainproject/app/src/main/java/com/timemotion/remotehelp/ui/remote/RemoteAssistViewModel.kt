package com.timemotion.remotehelp.ui.remote

import android.content.Intent
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.remote.RemoteControlUiState
import kotlinx.coroutines.flow.StateFlow

class RemoteAssistViewModel(
    private val coordinator: RemoteHelpCoordinator
) {
    val uiState: StateFlow<RemoteControlUiState> = coordinator.remoteController.uiState

    fun endSession() = coordinator.endCurrentSession()
    fun requestCapture(data: Intent) = coordinator.remoteController.startTargetCapture(0, data)
    fun openAccessibilitySettings() = coordinator.remoteController.refreshLocalCapabilities()
    fun connect() = coordinator.remoteController.connect()
    fun toggleSpeaker() = coordinator.callController.toggleSpeakerOutput()
    fun sendText(text: String) = coordinator.remoteController.sendTextCommand(text)
    fun sendBackspace() = coordinator.remoteController.sendBackspaceCommand()
    fun sendEnter() = coordinator.remoteController.sendEnterCommand()
    fun sendBack() = coordinator.remoteController.sendBackCommand()
    fun sendHome() = coordinator.remoteController.sendHomeCommand()
    fun sendRecents() = coordinator.remoteController.sendRecentsCommand()
    fun sendTap(normalizedX: Float, normalizedY: Float) = coordinator.remoteController.sendTapCommand(normalizedX, normalizedY)
    fun sendSwipe(startNormalizedX: Float, startNormalizedY: Float, endNormalizedX: Float, endNormalizedY: Float) =
        coordinator.remoteController.sendSwipeCommand(startNormalizedX, startNormalizedY, endNormalizedX, endNormalizedY)
    fun sendDrag(startNormalizedX: Float, startNormalizedY: Float, endNormalizedX: Float, endNormalizedY: Float) =
        coordinator.remoteController.sendDragCommand(startNormalizedX, startNormalizedY, endNormalizedX, endNormalizedY)
}
