package com.timemotion.remotehelp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
fun LogBrowserDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA) }
    var logFiles by remember { mutableStateOf(emptyList<LogFileInfo>()) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedContent by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        logFiles = AppLog.listLogFiles(context)
        selectedFileName = selectedFileName?.takeIf { current ->
            logFiles.any { it.fileName == current }
        } ?: logFiles.firstOrNull()?.fileName
    }

    LaunchedEffect(selectedFileName) {
        val fileName = selectedFileName ?: run {
            selectedContent = ""
            return@LaunchedEffect
        }
        selectedContent = withContext(Dispatchers.IO) {
            AppLog.readLogFile(context, fileName)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "日志列表",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "点击日志文件即可预览内容",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                    if (logFiles.isEmpty()) {
                        Text("当前没有日志文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            items(logFiles, key = { it.fileName }) { file ->
                                LogFileRow(
                                    file = file,
                                    selected = file.fileName == selectedFileName,
                                    timeFormat = timeFormat,
                                    onClick = { selectedFileName = file.fileName }
                                )
                            }
                        }
                        Text(
                            text = selectedFileName ?: "未选择日志",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedTextField(
                            value = selectedContent,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 10,
                            label = { Text("日志内容") }
                        )
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(selectedContent))
                            },
                            enabled = selectedContent.isNotBlank()
                        ) {
                            Text("复制日志内容")
                        }
                    }
                }
            }
        }
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
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(file.fileName, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${timeFormat.format(file.lastModified)} · ${formatSize(file.sizeBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
