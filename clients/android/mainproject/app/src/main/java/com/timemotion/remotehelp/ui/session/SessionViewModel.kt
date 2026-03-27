package com.timemotion.remotehelp.ui.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.webrtc.CallUiState
import kotlinx.coroutines.flow.StateFlow

class SessionViewModel(
    private val coordinator: RemoteHelpCoordinator
) {
    val uiState: StateFlow<RemoteHelpUiState> = coordinator.uiState
    val callState: StateFlow<CallUiState> = coordinator.callController.uiState

    fun dismissBanner() = coordinator.dismissBanner()
    fun endCurrentSession(reason: String = "手动结束") = coordinator.endCurrentSession(reason)
    fun onExpired() = coordinator.endCurrentSession("短信链接已过期，请重新发起协助")
    fun acceptVerificationRequest() = coordinator.acceptVerificationRequest()
    fun rejectVerificationRequest() = coordinator.rejectVerificationRequest()
    fun updateHelperLocationPermissionGranted(granted: Boolean) =
        coordinator.updateHelperLocationPermissionGranted(granted)

    fun onCopyLink(context: Context) {
        val link = uiState.value.activeSession?.deepLink?.takeIf { it.isNotBlank() }
        if (link == null) {
            Toast.makeText(context, "短信链接正在生成中，请稍后", Toast.LENGTH_SHORT).show()
            return
        }
        link.let {
            val clipboardManager = context.getSystemService(android.content.ClipboardManager::class.java)
            runCatching {
                clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText("remote_help_link", it))
                Toast.makeText(context, "短信链接已复制", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onSendSms(context: Context) {
        uiState.value.activeSession?.takeIf { it.deepLink.isNotBlank() }?.let { session ->
            val smsIntent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:${session.elderPhone}")
            ).putExtra(
                "sms_body",
                "请点击下面的远程协助验证链接：\n${session.deepLink}"
            )
            runCatching { context.startActivity(smsIntent) }
                .onFailure {
                    Toast.makeText(context, "未找到可发送短信的应用", Toast.LENGTH_SHORT).show()
                }
        } ?: Toast.makeText(context, "短信链接正在生成中，请稍后", Toast.LENGTH_SHORT).show()
    }

    fun reconnectCurrentSessionRoom() = coordinator.ensureCurrentSessionRoomConnected()
}
