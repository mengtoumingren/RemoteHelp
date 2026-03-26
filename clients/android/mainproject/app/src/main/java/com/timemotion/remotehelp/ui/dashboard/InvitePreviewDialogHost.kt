package com.timemotion.remotehelp.ui.dashboard

import androidx.compose.runtime.Composable
import com.timemotion.remotehelp.core.ActiveHelpSession
import com.timemotion.remotehelp.core.RemoteHelpUiState

@Composable
fun InvitePreviewDialogHost(
    uiState: RemoteHelpUiState,
    onDismissInvite: () -> Unit,
    onConfirmInvite: () -> Unit
) {
    uiState.pendingInviteSession?.let { session: ActiveHelpSession ->
        InvitePreviewDialog(
            session = session,
            onDismiss = onDismissInvite,
            onConfirm = onConfirmInvite
        )
    }
}
