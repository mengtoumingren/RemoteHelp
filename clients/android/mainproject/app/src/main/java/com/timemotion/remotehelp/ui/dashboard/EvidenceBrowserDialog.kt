package com.timemotion.remotehelp.ui.dashboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
fun EvidenceBrowserPage(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val store = remember(context) { SecurityEvidenceStore(context.applicationContext) }
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA) }
    var sessions by remember { mutableStateOf(emptyList<SecurityEvidenceSessionInfo>()) }
    var recordsBySession by remember { mutableStateOf(emptyMap<String, List<SecurityEvidenceRecord>>()) }
    var selectedSession by remember { mutableStateOf<SecurityEvidenceSessionInfo?>(null) }
    var selectedRecord by remember { mutableStateOf<RecordSelection?>(null) }
    var selectedImage by remember { mutableStateOf<ImageSelection?>(null) }

    LaunchedEffect(Unit) {
        val loadedSessions = withContext(Dispatchers.IO) { store.listSessions() }
        val loadedRecords = withContext(Dispatchers.IO) {
            loadedSessions.associate { session ->
                session.sessionId to store.listSnapshots(session.sessionId)
            }
        }
        sessions = loadedSessions
        recordsBySession = loadedRecords
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                BrowserHeader(
                    sessionCount = sessions.size,
                    recordCount = sessions.sumOf { session ->
                        recordsBySession[session.sessionId].orEmpty().size
                    },
                    onDismiss = onDismiss
                )
            }

            if (sessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, bottom = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "当前没有证据文件",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(sessions, key = { it.sessionId }) { session ->
                    SessionCard(
                        session = session,
                        records = recordsBySession[session.sessionId].orEmpty(),
                        timeFormat = timeFormat,
                        onOpenSession = { selectedSession = session }
                    )
                }
            }
        }
    }

    selectedSession?.let { session ->
        SessionRecordsDialog(
            session = session,
            records = recordsBySession[session.sessionId].orEmpty(),
            timeFormat = timeFormat,
            store = store,
            onDismiss = { selectedSession = null },
            onOpenRecord = { selection -> selectedRecord = selection },
            onOpenImage = { selection -> selectedImage = selection }
        )
    }

    selectedRecord?.let { selection ->
        RecordDetailDialog(
            selection = selection,
            store = store,
            timeFormat = timeFormat,
            clipboardManager = clipboardManager,
            onDismiss = { selectedRecord = null },
            onOpenImage = { image -> selectedImage = image }
        )
    }

    selectedImage?.let { selection ->
        ImagePreviewDialog(
            selection = selection,
            onDismiss = { selectedImage = null }
        )
    }
}

@Composable
private fun BrowserHeader(
    sessionCount: Int,
    recordCount: Int,
    onDismiss: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("返回")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "证据页面",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "会话 -> 记录 -> 概览 / 缩略图 -> 详情弹窗",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "会话 $sessionCount 个，记录 $recordCount 条",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionCard(
    session: SecurityEvidenceSessionInfo,
    records: List<SecurityEvidenceRecord>,
    timeFormat: SimpleDateFormat,
    onOpenSession: () -> Unit
) {
    val lastCapturedText = session.lastCapturedAt?.let { timeFormat.format(Date(it)) } ?: "暂无时间"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSession),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = session.sessionId,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (session.helperName.isNotBlank()) append(session.helperName)
                    if (session.elderName.isNotBlank()) {
                        if (isNotBlank()) append(" · ")
                        append(session.elderName)
                    }
                    if (isNotBlank()) append(" · ")
                    append("记录 ")
                    append(records.size)
                    append(" 条")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "最后留痕：$lastCapturedText",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "点击进入会话记录",
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SessionRecordsDialog(
    session: SecurityEvidenceSessionInfo,
    records: List<SecurityEvidenceRecord>,
    timeFormat: SimpleDateFormat,
    store: SecurityEvidenceStore,
    onDismiss: () -> Unit,
    onOpenRecord: (RecordSelection) -> Unit,
    onOpenImage: (ImageSelection) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 860.dp)
                    .padding(12.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 8.dp
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "会话记录",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = session.sessionId,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = onDismiss,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text("返回")
                                }
                            }
                            Text(
                                text = buildString {
                                    if (session.helperName.isNotBlank()) append(session.helperName)
                                    if (session.elderName.isNotBlank()) {
                                        if (isNotBlank()) append(" · ")
                                        append(session.elderName)
                                    }
                                    if (isNotBlank()) append(" · ")
                                    append("共 ")
                                    append(records.size)
                                    append(" 条记录")
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (records.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "该会话下没有记录",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(records.indices.toList()) { index ->
                            val record = records[index]
                            RecordCard(
                                session = session,
                                record = record,
                                index = index,
                                timeFormat = timeFormat,
                                store = store,
                                onOpenRecord = {
                                    onOpenRecord(RecordSelection(session, record, index))
                                },
                                onOpenHelperImage = { file ->
                                    onOpenImage(
                                        ImageSelection(
                                            title = "协助方摄像头截图",
                                            file = file
                                        )
                                    )
                                },
                                onOpenScreenImage = { file ->
                                    onOpenImage(
                                        ImageSelection(
                                            title = "当前屏幕截图",
                                            file = file
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordCard(
    session: SecurityEvidenceSessionInfo,
    record: SecurityEvidenceRecord,
    index: Int,
    timeFormat: SimpleDateFormat,
    store: SecurityEvidenceStore,
    onOpenRecord: () -> Unit,
    onOpenHelperImage: (File) -> Unit,
    onOpenScreenImage: (File) -> Unit
) {
    val helperCameraFile = remember(session.sessionId, record.helperCameraFileName) {
        store.resolveHelperCameraFile(session.sessionId, record.helperCameraFileName)
    }
    val screenshotFile = remember(session.sessionId, record.screenScreenshotFileName) {
        store.resolveScreenScreenshotFile(session.sessionId, record.screenScreenshotFileName)
    }
    val overviewText = buildString {
        append("概览：")
        append(record.callStatus)
        append(" · ")
        append(record.remoteStatus)
        append(" · ")
        append(record.targetStatus)
        record.locationSummary?.let {
            append(" · 协助方定位 ")
            append(it)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = timeFormat.format(Date(record.capturedAt)),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("记录 #")
                    append(index + 1)
                    append(" · ")
                    append(record.callStatus)
                    append(" · ")
                    append(record.remoteStatus)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = overviewText,
                modifier = Modifier.clickable(onClick = onOpenRecord),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = buildString {
                    append("定位权限：")
                    append(if (record.locationPermissionGranted) "已开启" else "未开启")
                    record.helperLocationUpdatedAt?.let {
                        append(" · 最近定位 ")
                        append(timeFormat.format(Date(it)))
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            EvidenceThumbnailCard(
                label = "协助方摄像头缩略图",
                file = helperCameraFile,
                hint = "点击查看大图",
                onClick = { onOpenHelperImage(helperCameraFile) }
            )
            EvidenceThumbnailCard(
                label = "当前屏幕缩略图",
                file = screenshotFile,
                hint = "点击查看大图",
                onClick = { onOpenScreenImage(screenshotFile) }
            )
        }
    }
}

@Composable
private fun RecordDetailDialog(
    selection: RecordSelection,
    store: SecurityEvidenceStore,
    timeFormat: SimpleDateFormat,
    clipboardManager: ClipboardManager,
    onDismiss: () -> Unit,
    onOpenImage: (ImageSelection) -> Unit
) {
    val helperCameraFile = remember(selection.session.sessionId, selection.record.helperCameraFileName) {
        store.resolveHelperCameraFile(selection.session.sessionId, selection.record.helperCameraFileName)
    }
    val screenshotFile = remember(selection.session.sessionId, selection.record.screenScreenshotFileName) {
        store.resolveScreenScreenshotFile(selection.session.sessionId, selection.record.screenScreenshotFileName)
    }
    val detailText = remember(selection.record) { selection.record.toJson().toString(2) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .padding(12.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "记录详情",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = timeFormat.format(Date(selection.record.capturedAt)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("关闭")
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        EvidenceMetaLine("会话", selection.session.sessionId)
                        EvidenceMetaLine("记录", "第 ${selection.index + 1} 条")
                        EvidenceMetaLine("状态", "${selection.record.callStatus} · ${selection.record.remoteStatus} · ${selection.record.targetStatus}")
                        EvidenceMetaLine(
                            "定位",
                            selection.record.locationSummary ?: "无"
                        )
                        EvidenceMetaLine(
                            "定位权限",
                            if (selection.record.locationPermissionGranted) "已开启" else "未开启"
                        )
                    }

                    OutlinedTextField(
                        value = detailText,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = 220.dp),
                        minLines = 7,
                        label = { Text("原始数据") }
                    )

                    OutlinedButton(
                        onClick = { clipboardManager.setText(AnnotatedString(detailText)) }
                    ) {
                        Text("复制原始数据")
                    }

                    EvidenceImageCard(
                        label = "协助方摄像头截图",
                        file = helperCameraFile,
                        hint = "点击缩略图放大",
                        maxImageHeight = 200.dp
                    ) {
                        onOpenImage(ImageSelection("协助方摄像头截图", helperCameraFile))
                    }
                    EvidenceImageCard(
                        label = "当前屏幕截图",
                        file = screenshotFile,
                        hint = "点击缩略图放大",
                        maxImageHeight = 200.dp
                    ) {
                        onOpenImage(ImageSelection("当前屏幕截图", screenshotFile))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreviewDialog(
    selection: ImageSelection,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.ui.graphics.Color(0xFF0B0F14)
        ) {
            var scale by remember(selection.file.absolutePath) { mutableStateOf(1f) }
            var offset by remember(selection.file.absolutePath) { mutableStateOf(Offset.Zero) }
            var imageBitmap by remember(selection.file.absolutePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            var fileExists by remember(selection.file.absolutePath) { mutableStateOf(selection.file.exists()) }

            LaunchedEffect(selection.file.absolutePath) {
                fileExists = selection.file.exists()
                imageBitmap = if (fileExists) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            BitmapFactory.decodeFile(selection.file.absolutePath)?.asImageBitmap()
                        }.getOrNull()
                    }
                } else {
                    null
                }
                scale = 1f
                offset = Offset.Zero
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (fileExists && imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(selection.file.absolutePath) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    offset += pan
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "图片文件不存在或无法读取",
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun EvidenceThumbnailCard(
    label: String,
    file: File,
    hint: String,
    onClick: () -> Unit
) {
    EvidenceImageCard(
        label = label,
        file = file,
        hint = hint,
        maxImageHeight = 180.dp,
        onClick = onClick
    )
}

@Composable
private fun EvidenceImageCard(
    label: String,
    file: File,
    hint: String,
    maxImageHeight: androidx.compose.ui.unit.Dp,
    onClick: (() -> Unit)? = null
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
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxImageHeight),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "图片文件不存在或无法读取：${file.name}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EvidenceMetaLine(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, color = MaterialTheme.colorScheme.onSurface)
    }
}

private data class RecordSelection(
    val session: SecurityEvidenceSessionInfo,
    val record: SecurityEvidenceRecord,
    val index: Int
)

private data class ImageSelection(
    val title: String,
    val file: File
)
