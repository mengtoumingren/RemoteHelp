package com.timemotion.remotehelp.ui.dashboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
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
        modifier = Modifier.fillMaxSize(),
        color = androidx.compose.ui.graphics.Color(0xFFF6EFE3)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(20.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
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
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp, bottom = 20.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "当前没有留痕证据记录",
                                    color = androidx.compose.ui.graphics.Color(0xFF526277),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack, 
                        contentDescription = "返回", 
                        tint = androidx.compose.ui.graphics.Color(0xFF183153)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "留痕证据",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color(0xFF183153)
                    )
                    Text(
                        text = "共 $sessionCount 次协助，记录 $recordCount 条数据",
                        color = androidx.compose.ui.graphics.Color(0xFF526277),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "会话 ID: ${session.sessionId.take(8)}...",
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color(0xFF183153),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    color = androidx.compose.ui.graphics.Color(0xFFE5ECF6),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${records.size} 条记录",
                        color = androidx.compose.ui.graphics.Color(0xFF215A6D),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = buildString {
                    if (session.helperName.isNotBlank()) append(session.helperName)
                    if (session.elderName.isNotBlank()) {
                        if (isNotBlank()) append(" · ")
                        append(session.elderName)
                    }
                },
                color = androidx.compose.ui.graphics.Color(0xFF526277),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "最后留痕：$lastCapturedText",
                color = androidx.compose.ui.graphics.Color(0xFF8A99A8),
                style = MaterialTheme.typography.bodySmall
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
                    .padding(20.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = androidx.compose.ui.graphics.Color.White,
                shadowElevation = 8.dp
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = onDismiss, modifier = Modifier.padding(end = 4.dp)) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowBack,
                                            contentDescription = "返回",
                                            tint = androidx.compose.ui.graphics.Color(0xFF183153)
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "会话记录明细",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = androidx.compose.ui.graphics.Color(0xFF183153)
                                        )
                                        Text(
                                            text = "ID: ${session.sessionId.take(12)}...",
                                            color = androidx.compose.ui.graphics.Color(0xFF8A99A8),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            Surface(
                                color = androidx.compose.ui.graphics.Color(0xFFF9FAFB),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = buildString {
                                        if (session.helperName.isNotBlank()) append(session.helperName)
                                        if (session.elderName.isNotBlank()) {
                                            if (isNotBlank()) append(" 协助 ")
                                            append(session.elderName)
                                        }
                                        if (isNotBlank()) append(" · ")
                                        append("共 ")
                                        append(records.size)
                                        append(" 条记录")
                                    },
                                    color = androidx.compose.ui.graphics.Color(0xFF526277),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
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
                                    text = "该会话下没有详细记录",
                                    color = androidx.compose.ui.graphics.Color(0xFF8A99A8)
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
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenRecord),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF9FAFB)),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFE5ECF6))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "记录 #${index + 1}",
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color(0xFF183153),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = timeFormat.format(Date(record.capturedAt)),
                    color = androidx.compose.ui.graphics.Color(0xFF8A99A8),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "概览：${record.callStatus} · ${record.remoteStatus} · ${record.targetStatus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color(0xFF526277)
                    )
                    Text(
                        text = buildString {
                            append("定位授权：")
                            append(if (record.locationPermissionGranted) "已开启" else "未开启")
                            record.locationSummary?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (record.locationPermissionGranted) androidx.compose.ui.graphics.Color(0xFF215A6D) else androidx.compose.ui.graphics.Color(0xFFB44C3B)
                    )
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val thumbnailGap = 12.dp
                val thumbnailWidth = (maxWidth - thumbnailGap) / 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(thumbnailGap)
                ) {
                    EvidenceThumbnailCard(
                        modifier = Modifier.width(thumbnailWidth),
                        label = "协助方摄像头",
                        file = helperCameraFile,
                        hint = "查看大图",
                        onClick = { onOpenHelperImage(helperCameraFile) }
                    )
                    EvidenceThumbnailCard(
                        modifier = Modifier.width(thumbnailWidth),
                        label = "屏幕截图",
                        file = screenshotFile,
                        hint = "查看大图",
                        onClick = { onOpenScreenImage(screenshotFile) }
                    )
                }
            }
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
                    .padding(20.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = androidx.compose.ui.graphics.Color.White,
                shadowElevation = 8.dp
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onDismiss, modifier = Modifier.padding(end = 4.dp)) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = androidx.compose.ui.graphics.Color(0xFF183153))
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "证据记录详情",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color(0xFF183153)
                                    )
                                    Text(
                                        text = timeFormat.format(Date(selection.record.capturedAt)),
                                        color = androidx.compose.ui.graphics.Color(0xFF8A99A8),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Surface(
                            color = androidx.compose.ui.graphics.Color(0xFFF9FAFB),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
                                EvidenceMetaLine("会话 ID", selection.session.sessionId)
                                EvidenceMetaLine("记录序号", "第 ${selection.index + 1} 条")
                                EvidenceMetaLine("通话状态", selection.record.callStatus)
                                EvidenceMetaLine("控制状态", selection.record.remoteStatus)
                                EvidenceMetaLine("终端状态", selection.record.targetStatus)
                                EvidenceMetaLine("定位信息", selection.record.locationSummary ?: "未记录")
                                EvidenceMetaLine("定位权限", if (selection.record.locationPermissionGranted) "已开启" else "未开启", highlight = true)
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = detailText,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 150.dp, max = 220.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            label = { Text("原始 JSON 数据") }
                        )
                        OutlinedButton(
                            onClick = { clipboardManager.setText(AnnotatedString(detailText)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ) {
                            Text("复制原始数据", fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF215A6D))
                        }
                    }

                    item {
                        EvidenceImageCard(
                            label = "协助方摄像头截图",
                            file = helperCameraFile,
                            hint = "点击放大查看",
                            maxImageHeight = 220.dp
                        ) {
                            onOpenImage(ImageSelection("协助方摄像头截图", helperCameraFile))
                        }
                    }
                    
                    item {
                        EvidenceImageCard(
                            label = "当前屏幕截图",
                            file = screenshotFile,
                            hint = "点击放大查看",
                            maxImageHeight = 220.dp
                        ) {
                            onOpenImage(ImageSelection("当前屏幕截图", screenshotFile))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceMetaLine(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = androidx.compose.ui.graphics.Color(0xFF526277), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value, 
            fontWeight = FontWeight.Bold, 
            color = if (highlight) androidx.compose.ui.graphics.Color(0xFF215A6D) else androidx.compose.ui.graphics.Color(0xFF183153),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun ImagePreviewDialog(
    selection: ImageSelection,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.95f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
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
                    } else null
                    scale = 1f
                    offset = Offset.Zero
                }

                if (fileExists && imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = selection.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 40.dp)
                            .pointerInput(selection.file.absolutePath) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    val panLimitX = (scale - 1) * size.width / 2f
                                    val panLimitY = (scale - 1) * size.height / 2f
                                    val targetOffsetX = offset.x + pan.x
                                    val targetOffsetY = offset.y + pan.y
                                    offset = Offset(
                                        x = targetOffsetX.coerceIn(-panLimitX, panLimitX),
                                        y = targetOffsetY.coerceIn(-panLimitY, panLimitY)
                                    )
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                            .clickable(onClick = onDismiss),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "图片无法加载",
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = androidx.compose.ui.graphics.Color.White)
                    }
                    Text(
                        text = selection.title,
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EvidenceThumbnailCard(
    modifier: Modifier = Modifier,
    label: String,
    file: File,
    hint: String,
    onClick: () -> Unit
) {
    EvidenceImageCard(
        modifier = modifier,
        label = label,
        file = file,
        hint = hint,
        maxImageHeight = 180.dp,
        onClick = onClick
    )
}

@Composable
private fun EvidenceImageCard(
    modifier: Modifier = Modifier,
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
            .then(modifier)
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF9FAFB)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = label, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF183153), style = MaterialTheme.typography.titleMedium)
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = maxImageHeight),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = androidx.compose.ui.graphics.Color.Black
            ) {
                if (fileExists && imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "图片文件不存在或无法读取：${file.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            
            Text(
                text = hint,
                color = androidx.compose.ui.graphics.Color(0xFF8A99A8),
                style = MaterialTheme.typography.bodySmall
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
