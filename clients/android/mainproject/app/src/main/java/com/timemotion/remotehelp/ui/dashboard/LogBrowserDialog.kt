package com.timemotion.remotehelp.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.LogFileInfo
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LogBrowserPage(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA) }
    var logFiles by remember { mutableStateOf(emptyList<LogFileInfo>()) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedContent by remember { mutableStateOf("") }

    var showLogDetailDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        logFiles = AppLog.listLogFiles(context)
        selectedFileName = null // 默认不选中，只有点击后才去读取
    }

    LaunchedEffect(selectedFileName) {
        val fileName = selectedFileName ?: run {
            selectedContent = ""
            return@LaunchedEffect
        }
        selectedContent = withContext(Dispatchers.IO) {
            AppLog.readLogFile(context, fileName)
        }
        showLogDetailDialog = true // 获取到内容后打开弹窗
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = androidx.compose.ui.graphics.Color(0xFFF6EFE3)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = androidx.compose.ui.graphics.Color(0xFF183153))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "运行日志",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color(0xFF183153)
                        )
                        Text(
                            text = "点击文件列表预览诊断内容",
                            color = androidx.compose.ui.graphics.Color(0xFF526277),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (logFiles.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("当前没有已保存的日志文件", color = androidx.compose.ui.graphics.Color(0xFF526277), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(logFiles, key = { it.fileName }) { file ->
                        LogFileRow(
                            file = file,
                            selected = false,
                            timeFormat = timeFormat,
                            onClick = { selectedFileName = file.fileName }
                        )
                    }
                }
            }
        }
    }

    if (showLogDetailDialog) {
        AlertDialog(
            onDismissRequest = {
                showLogDetailDialog = false
                selectedFileName = null
            },
            title = {
                Text(
                    text = selectedFileName ?: "日志详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color(0xFF183153)
                )
            },
            text = {
                OutlinedTextField(
                    value = selectedContent,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedContent.isNotBlank()) {
                            clipboardManager.setText(AnnotatedString(selectedContent))
                        }
                    },
                    enabled = selectedContent.isNotBlank(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF215A6D))
                ) {
                    Text("复制内容", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showLogDetailDialog = false
                        selectedFileName = null
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Text("关闭", color = androidx.compose.ui.graphics.Color(0xFF526277))
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            containerColor = androidx.compose.ui.graphics.Color.White
        )
    }
}

@Composable
private fun LogFileRow(
    file: LogFileInfo,
    selected: Boolean,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) androidx.compose.ui.graphics.Color(0xFF215A6D)
            else androidx.compose.ui.graphics.Color.White
        ),
        elevation = if (selected) CardDefaults.cardElevation(4.dp) else CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = if (selected) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF526277),
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = file.fileName, 
                    fontWeight = FontWeight.Bold,
                    color = if (selected) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF183153)
                )
                Text(
                    text = "${timeFormat.format(file.lastModified)} · ${formatSize(file.sizeBytes)}",
                    color = if (selected) androidx.compose.ui.graphics.Color(0xFFE5ECF6) else androidx.compose.ui.graphics.Color(0xFF8A99A8),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun formatSize(sizeBytes: Long): String {
    return when {
        sizeBytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", sizeBytes / 1024f / 1024f)
        sizeBytes >= 1024 -> String.format(Locale.CHINA, "%.1f KB", sizeBytes / 1024f)
        else -> "$sizeBytes B"
    }
}
