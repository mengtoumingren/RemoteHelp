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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.timemotion.remotehelp.R
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.DashboardPage
import com.timemotion.remotehelp.core.formatDateTime
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.ui.shared.VerificationRequestDialogHost

@Composable
fun DashboardRoute(viewModel: DashboardViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    VerificationRequestDialogHost(
        uiState = uiState,
        onAcceptRequest = viewModel::acceptVerificationRequest,
        onRejectRequest = viewModel::rejectVerificationRequest,
        onLocationPermissionChanged = viewModel::updateHelperLocationPermissionGranted
    )
    InvitePreviewDialogHost(
        uiState = uiState,
        onDismissInvite = viewModel::dismissPendingInvite,
        onConfirmInvite = viewModel::confirmPendingInvite
    )
    when (uiState.dashboardPage) {
        DashboardPage.MAIN -> DashboardMainPage(uiState, viewModel)
        DashboardPage.SETTINGS -> SettingsPage(
            uiState = uiState,
            onServerUrlChange = viewModel::updateServerUrl,
            onHelperNameChange = viewModel::updateHelperName,
            onSave = {
                viewModel.saveSettings()
                viewModel.closeDashboardPage()
            },
            onDismiss = viewModel::closeDashboardPage,
            onOpenLogs = viewModel::openLogs,
            onOpenEvidence = viewModel::openEvidence
        )
        DashboardPage.LOGS -> LogBrowserPage(onDismiss = viewModel::goBackDashboardPage)
        DashboardPage.EVIDENCE -> EvidenceBrowserPage(onDismiss = viewModel::goBackDashboardPage)
    }
}

@Composable
private fun DashboardMainPage(
    uiState: RemoteHelpUiState,
    viewModel: DashboardViewModel
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF6EFE3)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFF0E7D6), Color(0xFFE6EDF7))))
                .systemBarsPadding()
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
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "远程协助",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF183153)
                    )
                    Text(
                        text = uiState.side.subtitle, 
                        color = Color(0xFF526277),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                OutlinedButton(
                    onClick = viewModel::openSettings,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, Color(0xFFE5ECF6)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF183153),
                        containerColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings, 
                        contentDescription = "设置", 
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DashboardSideButton(
                    text = "我来协助", 
                    selected = uiState.side == DeviceSide.HELPER,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.switchSide(DeviceSide.HELPER)
                }
                DashboardSideButton(
                    text = "需要协助", 
                    selected = uiState.side == DeviceSide.ELDER,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.switchSide(DeviceSide.ELDER)
                }
            }
        }
    }
}

@Composable
private fun DashboardHelperHome(
    uiState: RemoteHelpUiState,
    viewModel: DashboardViewModel
) {
    DashboardCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Call,
                    contentDescription = null,
                    tint = Color(0xFF215A6D),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "发起远程协助", 
                    fontWeight = FontWeight.Bold, 
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF183153)
                )
            }
            OutlinedTextField(
                value = uiState.elderPhone,
                onValueChange = viewModel::updateElderPhone,
                label = { Text("请输入协助对象手机号") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = "手机号", tint = Color(0xFF526277)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF215A6D),
                    focusedLabelColor = Color(0xFF215A6D)
                ),
                singleLine = true
            )
            OutlinedTextField(
                value = uiState.elderName,
                onValueChange = viewModel::updateElderName,
                label = { Text("联系人称呼") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = "称呼", tint = Color(0xFF526277)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF215A6D),
                    focusedLabelColor = Color(0xFF215A6D)
                ),
                singleLine = true
            )
            Button(
                onClick = {
                    runCatching { viewModel.createRequest() }
                        .onFailure { AppLog.logThrowable("DashboardRoute", it, "发起协助失败") }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF215A6D)),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("发起协助", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            
            Surface(
                color = Color(0xFFF1F5F9),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "说明",
                            tint = Color(0xFF183153),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(text = "协助说明", fontWeight = FontWeight.Bold, color = Color(0xFF183153))
                    }
                    Text(text = "• 对方将收到短信验证链接", color = Color(0xFF526277), style = MaterialTheme.typography.bodyMedium)
                    Text(text = "• 需视频确认后开始协助", color = Color(0xFF526277), style = MaterialTheme.typography.bodyMedium)
                    Text(text = "• 当前连接配置从设置中读取并本地复用", color = Color(0xFF526277), style = MaterialTheme.typography.bodyMedium)
                }
            }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color(0xFFFFF0F0),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFFFFD6D6))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "安全警告",
                        tint = Color(0xFFB44C3B),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column {
                        Text(
                            text = "防骗安全提示",
                            color = Color(0xFFB44C3B),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "任何人索要密码、验证码、或要求转账均是诈骗！协助过程中请勿操作网银。",
                            color = Color(0xFFD32F2F),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Text(
                text = "接收远程协助", 
                fontWeight = FontWeight.ExtraBold, 
                color = Color(0xFF183153),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "点击短信链接后可先核对协助信息，再进入视频验证", 
                color = Color(0xFF526277),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = uiState.inviteEntry,
                onValueChange = viewModel::updateInviteEntry,
                label = { Text("粘贴短信链接") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF215A6D),
                    focusedLabelColor = Color(0xFF215A6D)
                ),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
                    modifier = Modifier.weight(0.4f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFD4DCE6)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF183153)
                    ),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Text("粘贴", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        runCatching { viewModel.consumeInvite(uiState.inviteEntry) }
                            .onFailure { AppLog.logThrowable("DashboardRoute", it, "查看协助信息失败") }
                    },
                    modifier = Modifier.weight(0.6f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF215A6D)),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Text("查看协助信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "最近协助", 
                fontWeight = FontWeight.Bold, 
                color = Color(0xFF183153),
                style = MaterialTheme.typography.titleMedium
            )
            if (contacts.isEmpty()) {
                Text(text = "还没有历史联系人", color = Color(0xFF526277), style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    contacts.forEach { contact ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF9FAFB),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.applyRecentContact(contact) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = Color(0xFFE5ECF6),
                                        modifier = Modifier.padding(end = 12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = Color(0xFF215A6D),
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                    Column {
                                        Text(text = contact.name, fontWeight = FontWeight.Bold, color = Color(0xFF183153))
                                        Text(text = contact.phone, color = Color(0xFF526277), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text(
                                    text = formatDateTime(contact.lastHelpTime), 
                                    color = Color(0xFF8A99A8), 
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHistoryCard(history: List<com.timemotion.remotehelp.core.SessionHistoryItem>) {
    DashboardCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "最近协助记录", 
                fontWeight = FontWeight.Bold, 
                color = Color(0xFF183153),
                style = MaterialTheme.typography.titleMedium
            )
            if (history.isEmpty()) {
                Text(text = "当前没有本地记录", color = Color(0xFF526277), style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.take(5).forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF9FAFB),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${item.elderName} · ${item.elderPhone}", 
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF183153)
                                    )
                                    Text(
                                        text = "${formatDateTime(item.startedAt)} · ${item.endReason}", 
                                        color = Color(0xFF526277),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardBannerCard(message: String, onDismiss: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF183153)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF526277)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("关闭", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DashboardSideButton(
    text: String, 
    selected: Boolean, 
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.shadow(
            elevation = if (selected) 6.dp else 0.dp, 
            shape = RoundedCornerShape(16.dp),
            spotColor = Color(0xFF215A6D).copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF215A6D) else Color(0xFFE8EDF3),
            contentColor = if (selected) Color.White else Color(0xFF183153)
        ),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DashboardCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.shadow(
            elevation = 8.dp,
            shape = RoundedCornerShape(24.dp),
            spotColor = Color(0xFF183153).copy(alpha = 0.08f),
            ambientColor = Color(0xFF183153).copy(alpha = 0.04f)
        ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}
