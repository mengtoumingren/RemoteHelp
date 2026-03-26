package com.timemotion.remotehelp.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.remote.RemoteControlUiState
import com.timemotion.remotehelp.remote.RemoteRole
import android.widget.FrameLayout
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun RemoteAssistScreen(
    side: DeviceSide,
    helperName: String,
    elderName: String,
    uiState: RemoteControlUiState,
    screenRenderer: SurfaceViewRenderer?,
    onEndClick: () -> Unit,
    onRequestCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    overlayPermissionGranted: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onConnectClick: () -> Unit,
    isSpeakerOn: Boolean,
    onToggleSpeaker: () -> Unit,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit,
    onFrameDrag: (Float, Float, Float, Float) -> Unit,
    onSendText: (String) -> Unit,
    onSendBackspace: () -> Unit,
    onSendEnter: () -> Unit,
    onSendBack: () -> Unit,
    onSendHome: () -> Unit,
    onSendRecents: () -> Unit,
) {
    val screenAspectRatio = if (uiState.targetStatus.screenWidth > 0 && uiState.targetStatus.screenHeight > 0) {
        uiState.targetStatus.screenWidth.toFloat() / uiState.targetStatus.screenHeight.toFloat()
    } else {
        9f / 16f
    }
    val controllerName = uiState.peers.firstOrNull { it.role == RemoteRole.CONTROLLER }?.displayName ?: helperName
    var isMoreMenuVisible by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF6EFE3)) {
        if (side == DeviceSide.HELPER) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0E1724), Color(0xFF17283B), Color(0xFF23384D))))
                    .systemBarsPadding(),
                bottomBar = {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        HelperAssistBottomBar(
                            onSendText = onSendText,
                            onSendBackspace = onSendBackspace,
                            onSendEnter = onSendEnter,
                            onSendBack = onSendBack,
                            onSendHome = onSendHome,
                            onSendRecents = onSendRecents,
                            onEndClick = onEndClick,
                            isMoreMenuVisible = isMoreMenuVisible,
                            onOpenMore = { isMoreMenuVisible = true },
                            onDismissMore = { isMoreMenuVisible = false },
                            isSpeakerOn = isSpeakerOn,
                            onToggleSpeaker = onToggleSpeaker,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
                {
                    HelperAssistBody(
                        modifier = Modifier.fillMaxSize(),
                        screenRenderer = screenRenderer,
                        screenAspectRatio = screenAspectRatio,
                        onFrameTap = onFrameTap,
                        onFrameSwipe = onFrameSwipe,
                        onFrameDrag = onFrameDrag
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart)
                            .zIndex(1f)
                    ) {
                        AssistTopOverlay(
                            title = "正在协助：$elderName",
                            subtitle = uiState.targetStatus.message
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFFBF4), Color(0xFFF0E7D7), Color(0xFFE4EDF6))))
                    .systemBarsPadding()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CardBlock(contentPadding = 10.dp) {
                    Text(
                        text = "正在协助：$helperName",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF183153)
                    )
                }
                ElderAssistBody(
                    controllerName = controllerName,
                    targetStatus = uiState.targetStatus.message,
                    captureActive = uiState.targetStatus.captureActive,
                    accessibilityEnabled = uiState.targetStatus.accessibilityEnabled,
                    overlayPermissionGranted = overlayPermissionGranted,
                    onRequestCapture = onRequestCapture,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    isConnected = uiState.isConnected,
                    onConnectClick = onConnectClick,
                    onEndClick = onEndClick
                )
            }
        }
    }
}

@Composable
private fun HelperAssistBody(
    modifier: Modifier,
    screenRenderer: SurfaceViewRenderer?,
    screenAspectRatio: Float,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit,
    onFrameDrag: (Float, Float, Float, Float) -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                    .aspectRatio(screenAspectRatio, matchHeightConstraintsFirst = true)
            ) {
                ControlRendererPanel(
                    renderer = screenRenderer,
                    placeholder = "等待对方屏幕画面",
                    aspectRatio = screenAspectRatio,
                    modifier = Modifier.fillMaxSize(),
                    allowTouch = true,
                    onFrameTap = onFrameTap,
                    onFrameSwipe = onFrameSwipe,
                    onFrameDrag = onFrameDrag
                )
            }
        }
    }
}

@Composable
private fun AssistTopOverlay(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color(0xFFD6E1EA),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AssistEvidenceBanner(
    notice: String,
    locationPermissionGranted: Boolean,
    onRequestLocationPermission: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xD0141E2A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.72f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "定时留痕",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = notice,
                    color = Color(0xFFD6E1EA),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!locationPermissionGranted && onRequestLocationPermission != null) {
                OutlinedButton(
                    onClick = onRequestLocationPermission,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text("开启定位")
                }
            }
        }
    }
}

@Composable
private fun HelperAssistBottomBar(
    onSendText: (String) -> Unit,
    onSendBackspace: () -> Unit,
    onSendEnter: () -> Unit,
    onSendBack: () -> Unit,
    onSendHome: () -> Unit,
    onSendRecents: () -> Unit,
    onEndClick: () -> Unit,
    isMoreMenuVisible: Boolean,
    onOpenMore: () -> Unit,
    onDismissMore: () -> Unit,
    isSpeakerOn: Boolean,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier
) {
    var keyboardEnabled by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = modifier
            .background(Color(0xFF122030))
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (keyboardEnabled) {
                HelperAssistTextInputDock(
                    onSendText = onSendText,
                    onSendBackspace = onSendBackspace,
                    onSendEnter = onSendEnter
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val itemWidth = 56.dp
                AssistFloatingButton(
                    title = "返回",
                    onClick = onSendBack,
                    modifier = Modifier.width(itemWidth)
                )
                AssistFloatingButton(
                    title = "桌面",
                    onClick = onSendHome,
                    modifier = Modifier.width(itemWidth)
                )
                AssistFloatingButton(
                    title = "菜单",
                    onClick = onSendRecents,
                    modifier = Modifier.width(itemWidth)
                )
                AssistFloatingButton(
                    title = "键盘",
                    active = keyboardEnabled,
                    onClick = { keyboardEnabled = !keyboardEnabled },
                    modifier = Modifier.width(itemWidth)
                )
                Box(
                    modifier = Modifier.wrapContentSize(Alignment.TopEnd)
                ) {
                    OutlinedButton(
                        onClick = onOpenMore,
                        modifier = Modifier.width(itemWidth),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 5.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0x22324458),
                            contentColor = Color(0xFFE8EFF6)
                        )
                    ) {
                        Text("更多")
                    }
                    DropdownMenu(
                        expanded = isMoreMenuVisible,
                        onDismissRequest = onDismissMore
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isSpeakerOn) "关闭外放" else "开启外放") },
                            onClick = {
                                onDismissMore()
                                onToggleSpeaker()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("结束协助") },
                            onClick = {
                                onDismissMore()
                                onEndClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HelperAssistTextInputDock(
    onSendText: (String) -> Unit,
    onSendBackspace: () -> Unit,
    onSendEnter: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var inputValue by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Box(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
    ) {
        OutlinedTextField(
            value = inputValue,
            onValueChange = { nextText ->
                val previousText = inputValue
                when {
                    nextText == previousText -> Unit
                    nextText.startsWith(previousText) -> {
                        val inserted = nextText.substring(previousText.length)
                        if (inserted.isNotEmpty()) {
                            sendTypedText(inserted, onSendText, onSendEnter)
                        }
                    }
                    previousText.startsWith(nextText) -> {
                        val removedCount = previousText.length - nextText.length
                        repeat(removedCount.coerceAtLeast(0)) {
                            onSendBackspace()
                        }
                    }
                }
                inputValue = nextText
            },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .background(Color.Transparent),
            singleLine = true,
            placeholder = null,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }
}

private fun sendTypedText(
    text: String,
    onSendText: (String) -> Unit,
    onSendEnter: () -> Unit
) {
    if (text.isEmpty()) {
        return
    }
    val parts = text.split('\n')
    parts.forEachIndexed { index, part ->
        if (part.isNotEmpty()) {
            onSendText(part)
        }
        if (index < parts.lastIndex) {
            onSendEnter()
        }
    }
}

@Composable
private fun AssistFloatingButton(
    title: String,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 5.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF7189A6) else Color(0x2CFFFFFF),
            contentColor = Color.White
        )
    ) {
        Text(title)
    }
}

@Composable
private fun ElderAssistBody(
    controllerName: String,
    targetStatus: String,
    captureActive: Boolean,
    accessibilityEnabled: Boolean,
    overlayPermissionGranted: Boolean,
    onRequestCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    isConnected: Boolean,
    onConnectClick: () -> Unit,
    onEndClick: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        CardBlock {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("正在接受远程协助", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
                Text("当前协助人：$controllerName", color = Color(0xFF526277))
                Text("通知栏会显示当前正在协助您的人，误触退出后也可以从通知栏确认状态。", color = Color(0xFF526277))
                Text(targetStatus, color = Color(0xFF526277))
            }
        }
        PermissionStatusCard(
            title = "屏幕共享",
            status = if (captureActive) "已开启" else "未开启",
            description = if (captureActive) {
                "对方已经能看到你的屏幕，接下来可以继续远程操作。"
            } else {
                "对方需要先看到你的实时画面，才能判断位置、路径和下一步操作。"
            },
            buttonText = if (captureActive) "重新授权" else "授权屏幕采集",
            onClick = onRequestCapture
        )
        PermissionStatusCard(
            title = "无障碍服务",
            status = if (accessibilityEnabled) "已开启" else "未开启",
            description = if (accessibilityEnabled) {
                "开启后对方才能替你点击、滑动和拖动。"
            } else {
                "这是让对方真正执行远程操作的关键权限，不开启就只能看画面。"
            },
            buttonText = if (accessibilityEnabled) "前往设置" else "开启无障碍服务",
            onClick = onOpenAccessibilitySettings
        )
        PermissionStatusCard(
            title = "协助悬浮窗",
            status = if (overlayPermissionGranted) "已开启" else "未开启",
            description = if (overlayPermissionGranted) {
                "悬浮窗权限已具备，点击按钮后才会显示协助者实时视频的小悬浮窗，不会自动弹出。"
            } else {
                "点击去授权后才会打开系统悬浮窗设置页，授权后会显示协助者实时视频的小悬浮窗。"
            },
            buttonText = if (overlayPermissionGranted) "显示悬浮窗" else "去授权",
            onClick = onOpenOverlaySettings
        )
        CardBlock {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("协助通道", fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
                Text(
                    text = if (isConnected) "协助通道已连接，完成上面的权限后就可以继续。" else "协助通道未连接，先连接后再开启权限。",
                    color = Color(0xFF526277)
                )
                if (!isConnected) {
                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF183153),
                            contentColor = Color.White
                        )
                    ) {
                        Text("连接协助通道")
                    }
                }
                OutlinedButton(
                    onClick = onEndClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color(0xFF183153)
                    )
                ) {
                    Text("结束本次协助")
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    status: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    CardBlock {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Color(0xFF183153))
                Text(status, fontWeight = FontWeight.Medium, color = Color(0xFF526277))
            }
            Text(description, color = Color(0xFF526277))
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFF183153)
                )
            ) {
                Text(buttonText)
            }
        }
    }
}

private data class ContentRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun normalize(offset: Offset): Pair<Float, Float>? {
        if (offset.x !in left..right || offset.y !in top..bottom || width <= 0f || height <= 0f) {
            return null
        }
        return ((offset.x - left) / width).coerceIn(0f, 1f) to ((offset.y - top) / height).coerceIn(0f, 1f)
    }
}

private fun calculateContentRect(size: IntSize, aspectRatio: Float): ContentRect? {
    if (size.width <= 0 || size.height <= 0 || aspectRatio <= 0f) return null
    val containerWidth = size.width.toFloat()
    val containerHeight = size.height.toFloat()
    val containerRatio = containerWidth / containerHeight
    return if (containerRatio > aspectRatio) {
        val contentWidth = containerHeight * aspectRatio
        val left = (containerWidth - contentWidth) / 2f
        ContentRect(left, 0f, left + contentWidth, containerHeight)
    } else {
        val contentHeight = containerWidth / aspectRatio
        val top = (containerHeight - contentHeight) / 2f
        ContentRect(0f, top, containerWidth, top + contentHeight)
    }
}

@Composable
private fun ControlRendererPanel(
    renderer: SurfaceViewRenderer?,
    placeholder: String,
    aspectRatio: Float,
    modifier: Modifier,
    allowTouch: Boolean,
    onFrameTap: (Float, Float) -> Unit,
    onFrameSwipe: (Float, Float, Float, Float) -> Unit,
    onFrameDrag: (Float, Float, Float, Float) -> Unit
) {
    if (renderer == null) {
        Box(
            modifier = modifier.background(Color(0xFF203040)),
            contentAlignment = Alignment.Center
        ) {
            Text(placeholder, color = Color.White)
        }
        return
    }
    val surfaceView = remember(renderer) {
        renderer
    }
    surfaceView.apply {
        setEnableHardwareScaler(true)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        setZOrderMediaOverlay(true)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var dragEnd by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var swipeStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var swipeEnd by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { size = it }
    ) {
        AndroidView(
            factory = {
                FrameLayout(it).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    if (surfaceView.parent == null) {
                        addView(
                            surfaceView,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (allowTouch) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .pointerInput(renderer, size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                dragStart = calculateContentRect(size, aspectRatio)?.normalize(offset)
                                dragEnd = dragStart
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragEnd = calculateContentRect(size, aspectRatio)?.normalize(change.position)
                            },
                            onDragEnd = {
                                val start = dragStart
                                val end = dragEnd
                                dragStart = null
                                dragEnd = null
                                if (start != null && end != null) {
                                    onFrameDrag(start.first, start.second, end.first, end.second)
                                }
                            },
                            onDragCancel = {
                                dragStart = null
                                dragEnd = null
                            }
                        )
                    }
                    .pointerInput(renderer, size) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                swipeStart = calculateContentRect(size, aspectRatio)?.normalize(offset)
                                swipeEnd = swipeStart
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                swipeEnd = calculateContentRect(size, aspectRatio)?.normalize(change.position)
                            },
                            onDragEnd = {
                                val start = swipeStart
                                val end = swipeEnd
                                swipeStart = null
                                swipeEnd = null
                                if (start != null && end != null) {
                                    onFrameSwipe(start.first, start.second, end.first, end.second)
                                }
                            },
                            onDragCancel = {
                                swipeStart = null
                                swipeEnd = null
                            }
                        )
                    }
                    .pointerInput(renderer, size) {
                        detectTapGestures { offset ->
                            val normalized = calculateContentRect(size, aspectRatio)?.normalize(offset)
                            if (normalized != null) {
                                onFrameTap(normalized.first, normalized.second)
                            }
                        }
                    }
            )
        }
    }
}

@Composable
private fun CardBlock(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFCF8))
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
