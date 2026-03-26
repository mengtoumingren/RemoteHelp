package com.timemotion.remotehelp.ui.verification

import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.webrtc.CallUiState
import kotlinx.coroutines.flow.StateFlow

class VerificationViewModel(
    private val coordinator: RemoteHelpCoordinator
) {
    val uiState: StateFlow<CallUiState> = coordinator.callController.uiState

    fun joinRoom(prepareLocalMedia: Boolean = true) = coordinator.callController.joinRoom(prepareLocalMedia)
    fun leaveRoom() = coordinator.callController.leaveRoom()
    fun toggleMic() = coordinator.callController.toggleMic()
    fun toggleCamera() = coordinator.callController.toggleCamera()
    fun toggleSpeaker() = coordinator.callController.toggleSpeakerOutput()
    fun acceptVerification() = coordinator.acceptVerification()
    fun rejectVerification() = coordinator.rejectVerification()
    fun remoteVideoTimeoutConfirm() = coordinator.failVerificationDueToRemoteTimeout()
    fun continueAssist() = coordinator.openAssist()
    fun back() = coordinator.leaveVerification()
}
