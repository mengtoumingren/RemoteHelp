package com.timemotion.remotehelp.remote

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.timemotion.remotehelp.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import kotlin.math.roundToInt

class RemoteControlController(
    context: Context
) {
    companion object {
        private const val SCREEN_SHARE_MIN_WIDTH = 360
        private const val SCREEN_SHARE_MIN_HEIGHT = 640
        private const val SCREEN_SHARE_MAX_LONG_SIDE = 2560
        private const val SCREEN_SHARE_MAX_BITRATE_BPS = 3_000_000
        private const val SCREEN_SHARE_MAX_FPS = 12
        private const val TARGET_STATUS_STARTING = "屏幕采集权限已授权，正在启动屏幕流"
        private const val TARGET_STATUS_READY_WITH_ACCESSIBILITY = "屏幕流已启动，可接受远程协助"
        private const val TARGET_STATUS_READY_NEED_ACCESSIBILITY = "屏幕流已启动，请开启无障碍服务"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eglBase = EglBase.create()
    private val okHttpClient = okhttp3.OkHttpClient.Builder().build()
    private val signalClient = RemoteSignalClient(okHttpClient, ::onSignalEvent)

    private val peerConnectionFactory: PeerConnectionFactory

    var onScreenShareStartRequested: ((Intent) -> Boolean)? = null
    var onScreenShareStopRequested: (() -> Unit)? = null
    private var isForegroundServiceActive = false
    private var pendingScreenCaptureData: Intent? = null
    private var pendingScreenCaptureProfile: CaptureProfile? = null

    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        RemoteAccessibilityService.stateListener = { enabled ->
            mainHandler.post { onAccessibilityAvailabilityChanged(enabled) }
        }
        RemoteAccessibilityService.softKeyboardStateListener = { hidden ->
            mainHandler.post { onSoftKeyboardModeChanged(hidden) }
        }
        ScreenCaptureForegroundService.statusListener = ::onForegroundServiceChanged
        ScreenCaptureForegroundService.messageListener = ::pushStatus
    }

    fun updateServerUrl(value: String) {
        _uiState.value = _uiState.value.copy(serverUrl = value)
    }

    fun updateRoomId(value: String) {
        _uiState.value = _uiState.value.copy(roomId = value)
    }

    fun updateDisplayName(value: String) {
        _uiState.value = _uiState.value.copy(displayName = value)
    }

    fun selectRole(role: RemoteRole) {
        _uiState.value = _uiState.value.copy(selectedRole = role)
    }

    fun connect() {
        val state = _uiState.value
        if (state.serverUrl.isBlank() || state.roomId.isBlank()) {
            pushStatus("请填写服务地址和房间号")
            return
        }
        pushStatus("连接中")
        signalClient.connect(
            url = state.serverUrl.trim(),
            roomId = state.roomId.trim(),
            role = state.selectedRole,
            displayName = state.displayName.trim().ifBlank { state.selectedRole.title }
        )
    }

    fun disconnect() {
        stopScreenShare()
        signalClient.disconnect()
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            peers = emptyList(),
            status = "已断开"
        )
        updateTargetStatus(
            _uiState.value.targetStatus.copy(
                captureActive = false,
                accessibilityEnabled = isAccessibilityEnabled(),
                softKeyboardHidden = isSoftKeyboardHidden(),
                message = "等待重新连接"
            )
        )
    }

    fun onCapturePermissionDenied() {
        updateTargetStatus(
            _uiState.value.targetStatus.copy(
                captureActive = false,
                accessibilityEnabled = isAccessibilityEnabled(),
                softKeyboardHidden = isSoftKeyboardHidden(),
                message = "屏幕采集授权被拒绝"
            )
        )
    }

    fun startTargetCapture(resultCode: Int, data: Intent) {
        if (_uiState.value.selectedRole != RemoteRole.TARGET) {
            pushStatus("当前不是被协助端")
            return
        }
        if (_uiState.value.targetStatus.captureActive) {
            pushStatus("屏幕流已启动")
            return
        }

        val captureProfile = currentCaptureProfile()
        updateTargetStatus(
            _uiState.value.targetStatus.copy(
                captureActive = false,
                accessibilityEnabled = isAccessibilityEnabled(),
                softKeyboardHidden = isSoftKeyboardHidden(),
                message = TARGET_STATUS_STARTING,
                screenWidth = captureProfile.screenWidth,
                screenHeight = captureProfile.screenHeight
            )
        )
        pushStatus("已授权屏幕采集，正在启动屏幕流")
        pendingScreenCaptureData = data
        pendingScreenCaptureProfile = captureProfile
        ScreenCaptureForegroundService.start(appContext, currentNotificationText())
        if (isForegroundServiceActive) {
            mainHandler.post { startPendingScreenCapture() }
        }
    }

    fun sendCommand(command: RemoteCommand) {
        if (_uiState.value.selectedRole != RemoteRole.CONTROLLER) {
            pushStatus("当前不是操控端")
            return
        }
        signalClient.sendCommand(command)
        appendLog("已发送 ${command.action.name}")
    }

    fun sendTapCommand(normalizedX: Float, normalizedY: Float) {
        val screenWidth = _uiState.value.targetStatus.screenWidth.coerceAtLeast(1)
        val screenHeight = _uiState.value.targetStatus.screenHeight.coerceAtLeast(1)
        sendCommand(
            RemoteCommand(
                action = RemoteAction.TAP,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                screenX = (normalizedX * screenWidth).toInt().coerceIn(0, screenWidth),
                screenY = (normalizedY * screenHeight).toInt().coerceIn(0, screenHeight)
            )
        )
    }

    fun sendSwipeCommand(
        startNormalizedX: Float,
        startNormalizedY: Float,
        endNormalizedX: Float,
        endNormalizedY: Float
    ) {
        val screenWidth = _uiState.value.targetStatus.screenWidth.coerceAtLeast(1)
        val screenHeight = _uiState.value.targetStatus.screenHeight.coerceAtLeast(1)
        sendCommand(
            RemoteCommand(
                action = RemoteAction.SWIPE,
                normalizedX = startNormalizedX,
                normalizedY = startNormalizedY,
                screenX = (startNormalizedX * screenWidth).toInt().coerceIn(0, screenWidth),
                screenY = (startNormalizedY * screenHeight).toInt().coerceIn(0, screenHeight),
                endNormalizedX = endNormalizedX,
                endNormalizedY = endNormalizedY,
                endScreenX = (endNormalizedX * screenWidth).toInt().coerceIn(0, screenWidth),
                endScreenY = (endNormalizedY * screenHeight).toInt().coerceIn(0, screenHeight)
            )
        )
    }

    fun sendDragCommand(
        startNormalizedX: Float,
        startNormalizedY: Float,
        endNormalizedX: Float,
        endNormalizedY: Float
    ) {
        val screenWidth = _uiState.value.targetStatus.screenWidth.coerceAtLeast(1)
        val screenHeight = _uiState.value.targetStatus.screenHeight.coerceAtLeast(1)
        sendCommand(
            RemoteCommand(
                action = RemoteAction.DRAG,
                normalizedX = startNormalizedX,
                normalizedY = startNormalizedY,
                screenX = (startNormalizedX * screenWidth).toInt().coerceIn(0, screenWidth),
                screenY = (startNormalizedY * screenHeight).toInt().coerceIn(0, screenHeight),
                endNormalizedX = endNormalizedX,
                endNormalizedY = endNormalizedY,
                endScreenX = (endNormalizedX * screenWidth).toInt().coerceIn(0, screenWidth),
                endScreenY = (endNormalizedY * screenHeight).toInt().coerceIn(0, screenHeight)
            )
        )
    }

    fun sendBackCommand() {
        sendCommand(RemoteCommand(action = RemoteAction.BACK))
    }

    fun sendHomeCommand() {
        sendCommand(RemoteCommand(action = RemoteAction.HOME))
    }

    fun sendRecentsCommand() {
        sendCommand(RemoteCommand(action = RemoteAction.RECENTS))
    }

    fun refreshLocalCapabilities() {
        val captureProfile = currentCaptureProfile()
        val current = _uiState.value.targetStatus
        val pendingStates = setOf(
            TARGET_STATUS_STARTING
        )
        val neutralStates = setOf("等待重新连接", "屏幕采集启动失败", "前台投屏服务已关闭")
        val message = when {
            current.captureActive && isAccessibilityEnabled() -> TARGET_STATUS_READY_WITH_ACCESSIBILITY
            current.captureActive -> TARGET_STATUS_READY_NEED_ACCESSIBILITY
            current.message in pendingStates -> current.message
            current.message in neutralStates -> "请先授权屏幕采集"
            else -> current.message
        }
        updateTargetStatus(
            current.copy(
                accessibilityEnabled = isAccessibilityEnabled(),
                softKeyboardHidden = isSoftKeyboardHidden(),
                message = message,
                screenWidth = captureProfile.screenWidth,
                screenHeight = captureProfile.screenHeight
            )
        )
    }

    fun release() {
        disconnect()
        RemoteAccessibilityService.stateListener = null
        RemoteAccessibilityService.softKeyboardStateListener = null
        ScreenCaptureForegroundService.statusListener = null
        ScreenCaptureForegroundService.messageListener = null
        peerConnectionFactory.dispose()
        eglBase.release()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private fun stopScreenShare() {
        pendingScreenCaptureData = null
        pendingScreenCaptureProfile = null
        onScreenShareStopRequested?.invoke()
        ScreenCaptureForegroundService.stop(appContext)
    }

    private fun currentCaptureProfile(): CaptureProfile {
        val metrics = appContext.readDeviceScreenMetrics()
        val screenWidth = metrics.width
        val screenHeight = metrics.height
        val longSide = maxOf(screenWidth, screenHeight)
        val scale = minOf(1f, SCREEN_SHARE_MAX_LONG_SIDE.toFloat() / longSide.toFloat())
        val captureWidth = (screenWidth * scale).roundToInt().coerceAtLeast(SCREEN_SHARE_MIN_WIDTH).ensureEven()
        val captureHeight = (screenHeight * scale).roundToInt().coerceAtLeast(SCREEN_SHARE_MIN_HEIGHT).ensureEven()
        return CaptureProfile(screenWidth, screenHeight, captureWidth, captureHeight)
    }

    private fun onSignalEvent(event: RemoteSignalEvent) {
        mainHandler.post {
            runCatching {
                when (event) {
                    is RemoteSignalEvent.Connected -> pushStatus("已连上服务")
                    is RemoteSignalEvent.Reconnecting -> {
                        _uiState.value = _uiState.value.copy(isConnected = true)
                        pushStatus("${event.message}，${event.delayMs / 1000}s 后重连（${event.attempt}/6）")
                    }
                    is RemoteSignalEvent.Joined -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            peers = event.peers,
                            targetStatus = event.targetStatus ?: _uiState.value.targetStatus,
                            status = "已加入房间"
                        )
                        appendLog("加入房间成功")
                    }
                    is RemoteSignalEvent.PeerUpdate -> {
                        _uiState.value = _uiState.value.copy(peers = event.peers)
                        appendLog("成员更新: ${event.peers.joinToString { "${it.displayName}-${it.role.title}" }}")
                    }
                    is RemoteSignalEvent.TargetStatusReceived -> {
                        _uiState.value = _uiState.value.copy(targetStatus = event.status)
                    }
                    is RemoteSignalEvent.CommandReceived -> {
                        if (_uiState.value.selectedRole != RemoteRole.TARGET) return@post
                        appendLog("收到 ${event.fromDisplayName} 的 ${event.command.action.name}")
                        val accessibility = RemoteAccessibilityService.instance
                        if (accessibility == null) {
                            updateTargetStatus(
                                _uiState.value.targetStatus.copy(
                                    accessibilityEnabled = false,
                                    softKeyboardHidden = isSoftKeyboardHidden(),
                                    message = "未开启无障碍服务，无法执行远控指令"
                                )
                            )
                            appendLog("无障碍服务未开启，命令未执行")
                            return@post
                        }
                        val success = accessibility.execute(event.command)
                        val softKeyboardHidden = accessibility.isSoftKeyboardHidden()
                        updateTargetStatus(
                            _uiState.value.targetStatus.copy(
                                accessibilityEnabled = true,
                                softKeyboardHidden = softKeyboardHidden,
                                message = when {
                                    !success -> "远控指令执行失败"
                                    else -> "${event.fromDisplayName} 已执行 ${event.command.action.name}"
                                }
                            )
                        )
                        appendLog("${event.fromDisplayName} -> ${event.command.action.name}")
                    }
                    is RemoteSignalEvent.SignalReceived -> Unit
                    is RemoteSignalEvent.Error -> pushStatus(event.message)
                    is RemoteSignalEvent.Disconnected -> {
                        allowDisconnectCleanup()
                    }
                }
            }.onFailure {
                AppLog.logThrowable("RemoteControlController", it, "处理远控信令事件失败: ${event::class.simpleName}")
                pushStatus("远控处理异常，请重试")
            }
        }
    }

    private fun allowDisconnectCleanup() {
        stopScreenShare()
        updateTargetStatus(
            _uiState.value.targetStatus.copy(
                captureActive = false,
                accessibilityEnabled = isAccessibilityEnabled(),
                softKeyboardHidden = isSoftKeyboardHidden(),
                message = "等待重新连接"
            )
        )
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            peers = emptyList(),
            status = "连接关闭"
        )
        appendLog("连接已关闭")
        refreshForegroundNotification()
    }

    private fun onForegroundServiceChanged(active: Boolean) {
        mainHandler.post {
            isForegroundServiceActive = active
            val current = _uiState.value.targetStatus
            if (active && pendingScreenCaptureData != null && !current.captureActive) {
                startPendingScreenCapture()
                return@post
            }
            val pendingStates = setOf(TARGET_STATUS_STARTING)
            val neutralStates = setOf("等待重新连接", "屏幕采集启动失败", "前台投屏服务已关闭")
            val message = when {
                active && current.message in pendingStates -> current.message
                active && current.captureActive -> current.message
                active -> "前台投屏服务已启动"
                current.captureActive -> "前台投屏服务已关闭"
                current.message in pendingStates -> "屏幕采集中断"
                current.message in neutralStates -> "请先授权屏幕采集"
                else -> current.message
            }
            updateTargetStatus(
                current.copy(
                    captureActive = if (active) current.captureActive else false,
                    accessibilityEnabled = isAccessibilityEnabled(),
                    softKeyboardHidden = isSoftKeyboardHidden(),
                    message = message
                )
            )
        }
    }

    private fun startPendingScreenCapture() {
        val data = pendingScreenCaptureData ?: return
        val captureProfile = pendingScreenCaptureProfile ?: currentCaptureProfile()
        runCatching {
            AppLog.d(
                "RemoteControlController",
                "开始触发屏幕共享, foregroundActive=$isForegroundServiceActive, captureActive=${_uiState.value.targetStatus.captureActive}"
            )
            val started = onScreenShareStartRequested?.invoke(data) == true
            if (!started) {
                throw IllegalStateException("屏幕共享通道未准备好")
            }
            pendingScreenCaptureData = null
            pendingScreenCaptureProfile = null
            updateTargetStatus(
                _uiState.value.targetStatus.copy(
                    captureActive = true,
                    accessibilityEnabled = isAccessibilityEnabled(),
                    softKeyboardHidden = isSoftKeyboardHidden(),
                    message = if (isAccessibilityEnabled()) {
                        TARGET_STATUS_READY_WITH_ACCESSIBILITY
                    } else {
                        TARGET_STATUS_READY_NEED_ACCESSIBILITY
                    },
                    screenWidth = captureProfile.screenWidth,
                    screenHeight = captureProfile.screenHeight
                )
            )
            pushStatus("屏幕共享已启动")
        }.onFailure {
            pendingScreenCaptureData = null
            pendingScreenCaptureProfile = null
            stopScreenShare()
            updateTargetStatus(
                _uiState.value.targetStatus.copy(
                    captureActive = false,
                    accessibilityEnabled = isAccessibilityEnabled(),
                    softKeyboardHidden = isSoftKeyboardHidden(),
                    message = "屏幕采集启动失败"
                )
            )
            pushStatus("启动屏幕共享失败: ${it.message ?: "unknown"}")
            AppLog.logThrowable("RemoteControlController", it, "启动屏幕共享失败")
        }
    }

    private fun isAccessibilityEnabled(): Boolean = RemoteAccessibilityService.isEnabled(appContext)
    private fun isSoftKeyboardHidden(): Boolean = RemoteAccessibilityService.instance?.isSoftKeyboardHidden() ?: false

    private fun onAccessibilityAvailabilityChanged(enabled: Boolean) {
        val current = _uiState.value.targetStatus
        val message = when {
            _uiState.value.selectedRole != RemoteRole.TARGET -> current.message
            current.captureActive && enabled -> TARGET_STATUS_READY_WITH_ACCESSIBILITY
            current.captureActive -> TARGET_STATUS_READY_NEED_ACCESSIBILITY
            enabled -> "无障碍服务已开启，请继续授权屏幕采集"
            else -> current.message
        }
        updateTargetStatus(
            current.copy(
                accessibilityEnabled = enabled,
                softKeyboardHidden = isSoftKeyboardHidden(),
                message = message
            )
        )
    }

    private fun onSoftKeyboardModeChanged(hidden: Boolean) {
        val current = _uiState.value.targetStatus
        updateTargetStatus(current.copy(softKeyboardHidden = hidden))
    }

    private fun currentNotificationText(): String {
        val controllerName = _uiState.value.peers.firstOrNull { it.role == RemoteRole.CONTROLLER }?.displayName
        return if (controllerName.isNullOrBlank()) {
            "远程协助屏幕共享进行中"
        } else {
            "正在接受 $controllerName 的远程协助"
        }
    }

    private fun refreshForegroundNotification() {
        if (_uiState.value.selectedRole == RemoteRole.TARGET && _uiState.value.targetStatus.captureActive) {
            ScreenCaptureForegroundService.updateNotification(appContext, currentNotificationText())
        }
    }

    private fun updateTargetStatus(status: RemoteTargetStatus) {
        _uiState.value = _uiState.value.copy(targetStatus = status)
        if (_uiState.value.selectedRole == RemoteRole.TARGET && _uiState.value.isConnected) {
            signalClient.sendTargetStatus(status)
        }
    }

    private fun pushStatus(message: String) {
        _uiState.value = _uiState.value.copy(status = message)
    }

    private fun appendLog(message: String) {
        _uiState.value = _uiState.value.copy(logs = (_uiState.value.logs + message).takeLast(8))
    }
}

private data class CaptureProfile(
    val screenWidth: Int,
    val screenHeight: Int,
    val captureWidth: Int,
    val captureHeight: Int
)

private fun Int.ensureEven(): Int = if (this % 2 == 0) this else this - 1
