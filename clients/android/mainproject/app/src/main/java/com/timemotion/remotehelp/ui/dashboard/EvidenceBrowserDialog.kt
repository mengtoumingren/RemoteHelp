package com.timemotion.remotehelp.ui.dashboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.SecurityEvidenceRecord
import com.timemotion.remotehelp.core.SecurityEvidenceSessionInfo
import com.timemotion.remotehelp.core.SecurityEvidenceStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EvidenceBrowserDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val store = remember(context) { SecurityEvidenceStore(context.applicationContext) }
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA) }
    var sessions by remember { mutableStateOf(emptyList<SecurityEvidenceSessionInfo>()) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var selectedRecords by remember { mutableStateOf(emptyList<SecurityEvidenceRecord>()) }
    var selectedRecordIndex by remember { mutableStateOf(0) }
    var selectedDetailText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) {
            store.listSessions()
        }
        selectedSessionId = selectedSessionId?.takeIf { current ->
            sessions.any { it.sessionId == current }
        } ?: sessions.firstOrNull()?.sessionId
    }

    LaunchedEffect(selectedSessionId) {
        val sessionId = selectedSessionId
        if (sessionId.isNullOrBlank()) {
            selectedRecords = emptyList()
            selectedRecordIndex = 0
            selectedDetailText = ""
            return@LaunchedEffect
        }
        selectedRecords = withContext(Dispatchers.IO) {
            store.listSnapshots(sessionId)
        }
        selectedRecordIndex = selectedRecords.indices.firstOrNull() ?: 0
    }

    LaunchedEffect(selectedRecords, selectedRecordIndex) {
        val record = selectedRecords.getOrNull(selectedRecordIndex)
        selectedDetailText = record?.toJson()?.toString(2).orEmpty()
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
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "证据列表",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "按会话查看本地留痕记录，点击条目查看详细内容",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }

                    if (sessions.isEmpty()) {
                        Text(
                            text = "当前没有证据文件",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "会话目录",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            items(sessions, key = { it.sessionId }) { session ->
                                EvidenceSessionRow(
                                    session = session,
                                    selected = session.sessionId == selectedSessionId,
                                    timeFormat = timeFormat,
                                    onClick = {
                                        selectedSessionId = session.sessionId
                                    }
                                )
                            }
                        }

                        val selectedSession = sessions.firstOrNull { it.sessionId == selectedSessionId }
                        if (selectedSession != null) {
                            Text(
                                text = "当前会话：${selectedSession.sessionId}",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (selectedRecords.isEmpty()) {
                                Text(
                                    text = "该会话下还没有留痕记录",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 2.dp)
                                ) {
                                    items(selectedRecords.withIndex().toList(), key = { it.index }) { entry ->
                                        val record = entry.value
                                        EvidenceRecordRow(
                                            record = record,
                                            selected = entry.index == selectedRecordIndex,
                                            timeFormat = timeFormat,
                                            onClick = {
                                                selectedRecordIndex = entry.index
                                            }
                                        )
                                    }
                                }
                                Text(
                                    text = "证据详情",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                OutlinedTextField(
                                    value = selectedDetailText,
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 12,
                                    label = { Text("当前条目内容") }
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(selectedDetailText))
                                        },
                                        enabled = selectedDetailText.isNotBlank()
                                    ) {
                                        Text("复制内容")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            selectedSessionId = sessions.firstOrNull()?.sessionId
                                            selectedRecordIndex = 0
                                        },
                                        enabled = sessions.size > 1
                                    ) {
                                        Text("切换到最新")
                                    }
                                }
                                selectedRecords.getOrNull(selectedRecordIndex)?.let { record ->
                                    val screenshotFile = store.resolveScreenScreenshotFile(
                                        selectedSession.sessionId,
                                        record.screenScreenshotFileName
                                    )
                                    EvidenceAttachmentLine(
                                        label = "屏幕截图",
                                        value = record.screenScreenshotFileName
                                    )
                                    EvidenceImageCard(
                                        label = "屏幕截图预览",
                                        file = screenshotFile
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
private fun EvidenceSessionRow(
    session: SecurityEvidenceSessionInfo,
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
            Text(
                text = session.sessionId,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("记录 ")
                    append(session.recordCount)
                    append(" 条")
                    session.lastCapturedAt?.let {
                        append(" · ")
                        append(timeFormat.format(Date(it)))
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (session.helperName.isNotBlank() || session.elderName.isNotBlank()) {
                Text(
                    text = buildString {
                        if (session.helperName.isNotBlank()) {
                            append(session.helperName)
                        }
                        if (session.elderName.isNotBlank()) {
                            if (length > 0) {
                                append(" · ")
                            }
                            append(session.elderName)
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EvidenceRecordRow(
    record: SecurityEvidenceRecord,
    selected: Boolean,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = timeFormat.format(Date(record.capturedAt)),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = buildString {
                    append(record.callStatus)
                    append(" · ")
                    append(record.remoteStatus)
                    append(" · ")
                    append(record.targetStatus)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = buildString {
                    append("仅屏幕截图")
                    record.locationSummary?.let {
                        append(" · 定位 ")
                        append(it)
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EvidenceAttachmentLine(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun EvidenceImageCard(
    label: String,
    file: File
) {
    var imageBitmap by remember(file.absolutePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var fileExists by remember(file.absolutePath) { mutableStateOf(file.exists()) }

    LaunchedEffect(file.absolutePath) {
        fileExists = file.exists()
        imageBitmap = if (fileExists) {
            withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                }.getOrNull()
            }
        } else {
            null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = label, fontWeight = FontWeight.SemiBold)
            if (fileExists && imageBitmap != null) {
                Image(
                    bitmap = imageBitmap!!,
                    contentDescription = label,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "图片文件不存在或无法读取：${file.name}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
