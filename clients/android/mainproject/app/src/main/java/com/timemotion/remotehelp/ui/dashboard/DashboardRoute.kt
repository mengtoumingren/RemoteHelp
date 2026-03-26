package com.timemotion.remotehelp.ui.dashboard

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.timemotion.remotehelp.R
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.formatDateTime
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.ui.shared.VerificationRequestDialogHost

@Composable
fun DashboardRoute(viewModel: DashboardViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    VerificationRequestDialogHost(
        uiState = uiState,
        onAcceptRequest = viewModel::acceptVerificationRequest,
        onRejectRequest = viewModel::rejectVerificationRequest
    )
    InvitePreviewDialogHost(
        uiState = uiState,
        onDismissInvite = viewModel::dismissPendingInvite,
        onConfirmInvite = viewModel::confirmPendingInvite
    )
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF6EFE3)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFF0E7D6), Color(0xFFE6EDF7))))
                .padding(0.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { DashboardTopHero(uiState, viewModel) }
            uiState.bannerMessage?.let { message ->
                item { DashboardBannerCard(message = message, onDismiss = viewModel::dismissBanner) }
            }
            item {
                if (uiState.side == DeviceSide.HELPER) {
                    DashboardHelperHome(uiState, viewModel)
                } else {
                    DashboardElderHome(uiState, viewModel)
                }
            }
            if (uiState.side == DeviceSide.HELPER) {
                item { DashboardRecentContactsCard(uiState.recentContacts, viewModel) }
            }
            item { DashboardHistoryCard(uiState.history) }
        }
    }
}

@Composable
private fun DashboardTopHero(
    uiState: RemoteHelpUiState,
    viewModel: DashboardViewModel
) {
    DashboardCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "远程协助",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF183153)
                    )
                    Text(text = uiState.side.subtitle, color = Color(0xFF526277))
                }
                OutlinedButton(
                    onClick = viewModel::openSettings,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF183153)
                    )
                ) {
                    Text(
                        text = "设置",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashboardSideButton("我来协助", uiState.side == DeviceSide.HELPER) {
                    viewModel.switchSide(DeviceSide.HELPER)
                }
                DashboardSideButton("需要协助", uiState.side == DeviceSide.ELDER) {
                    viewModel.switchSide(DeviceSide.ELDER)
                }
            }
        }
    }
    if (uiState.isSettingsVisible) {
        SettingsDialog(
            uiState = uiState,
            onServerUrlChange = viewModel::updateServerUrl,
            onHelperNameChange = viewModel::updateHelperName,
            onSave = viewModel::saveSettings,
            onDismiss = viewModel::closeSettings
        )
    }
}

@Composable
private fun DashboardHelperHome(
    uiState: RemoteHelpUiState,
    viewModel: DashboardViewModel
) {
    DashboardCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = "发起远程协助", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
            OutlinedTextField(
                value = uiState.elderPhone,
                onValueChange = viewModel::updateElderPhone,
                label = { Text("请输入协助对象手机号") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.elderName,
                onValueChange = viewModel::updateElderName,
                label = { Text("联系人称呼") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    runCatching { viewModel.createRequest() }
                        .onFailure { AppLog.logThrowable("DashboardRoute", it, "发起协助失败") }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("发起协助")
            }
            Text(text = "协助说明", fontWeight = FontWeight.Medium, color = Color(0xFF183153))
            Text(text = "• 对方将收到短信验证链接", color = Color(0xFF526277))
            Text(text = "• 需视频确认后开始协助", color = Color(0xFF526277))
            Text(text = "• 当前连接配置从设置中读取并本地复用", color = Color(0xFF526277))
        }
    }
}

@Composable
private fun DashboardElderHome(
    uiState: RemoteHelpUiState,
    viewModel: DashboardViewModel
) {
    val context = LocalContext.current
    DashboardCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "远程协助", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
            Text(text = "点击短信链接后可先核对协助信息，再进入视频验证", color = Color(0xFF526277))
            OutlinedTextField(
                value = uiState.inviteEntry,
                onValueChange = viewModel::updateInviteEntry,
                label = { Text("粘贴短信链接") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.updateInviteEntry("")
                        val clipboardManager = context.getSystemService<android.content.ClipboardManager>()
                        val pastedText = clipboardManager?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (pastedText.isBlank()) {
                            Toast.makeText(context, "剪切板中没有可用链接", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        viewModel.updateInviteEntry(pastedText)
                    },
                    modifier = Modifier.widthIn(min = 84.dp, max = 96.dp),
                    shape = ButtonDefaults.shape,
                    border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF6E7B8B)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("粘贴链接")
                }
                Button(
                    onClick = {
                        runCatching { viewModel.consumeInvite(uiState.inviteEntry) }
                            .onFailure { AppLog.logThrowable("DashboardRoute", it, "查看协助信息失败") }
                    },
                    modifier = Modifier.widthIn(min = 168.dp)
                ) {
                    Text("查看协助信息")
                }
            }
        }
    }
}

@Composable
private fun DashboardRecentContactsCard(
    contacts: List<com.timemotion.remotehelp.core.RecentContact>,
    viewModel: DashboardViewModel
) {
    DashboardCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "最近协助", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
            if (contacts.isEmpty()) {
                Text(text = "还没有历史联系人", color = Color(0xFF526277))
            } else {
                contacts.forEach { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.applyRecentContact(contact) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = contact.name, fontWeight = FontWeight.Medium)
                            Text(text = contact.phone, color = Color(0xFF526277))
                        }
                        Text(text = formatDateTime(contact.lastHelpTime), color = Color(0xFF526277))
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHistoryCard(history: List<com.timemotion.remotehelp.core.SessionHistoryItem>) {
    DashboardCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "最近协助记录", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
            if (history.isEmpty()) {
                Text(text = "当前没有本地记录", color = Color(0xFF526277))
            } else {
                history.take(5).forEach { item ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "${item.elderName} · ${item.elderPhone}", fontWeight = FontWeight.Medium)
                        Text(text = "${formatDateTime(item.startedAt)} · ${item.endReason}", color = Color(0xFF526277))
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardBannerCard(message: String, onDismiss: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF183153))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = Color.White,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .fillMaxWidth(0.72f)
            )
            OutlinedButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun DashboardSideButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF215A6D) else Color(0xFFE8EDF3),
            contentColor = if (selected) Color.White else Color(0xFF183153)
        )
    ) {
        Text(text)
    }
}

@Composable
private fun DashboardCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFCF8))
    ) {
        Box(modifier = Modifier.padding(18.dp)) {
            content()
        }
    }
}
