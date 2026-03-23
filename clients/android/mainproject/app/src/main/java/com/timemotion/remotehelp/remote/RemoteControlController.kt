package com.timemotion.remotehelp.remote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

class RemoteControlController(
    context: Context
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eglBase = EglBase.create()
    private val okHttpClient = OkHttpClient.Builder().build()
    private val signalClient = RemoteSignalClient(okHttpClient, ::onSignalEvent)
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    private val peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var localVideoSource: VideoSource? = null
    private var localScreenTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localVideoSender: RtpSender? = null
    private var isMakingOffer = false

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
        clearPeerConnection()
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
                message = "等待重新连接"
            )
        )
    }

    fun onCapturePermissionDenied() {
        updateTargetStatus(
            _uiState.value.targetStatus.copy(
                captureActive = false,
                accessibilityEnabled = isAccessibilityEnabled(),
                message = "屏幕采集授权被拒绝"
            )
        )
    }

    fun startTargetCapture(resultCode: Int, data: Intent) {
        if (_uiState.value.selectedRole != RemoteRole.TARGET) {
            pushStatus("当前不是被协助端")
            return
        }
        val captureProfile = currentCaptureProfile()
        ScreenCaptureForegroundService.start(appContext, currentNotificationText())
        mainHandler.postDelayed({
            startScreenShareInternal(data, captureProfile.captureWidth, captureProfile.captureHeight)
            updateTargetStatus(
                _uiState.value.targetStatus.copy(
                    captureActive = true,
                    accessibilityEnabled = isAccessibilityEnabled(),
                    message = if (isAccessibilityEnabled()) "屏幕流已启动，可接受远程协助" else "屏幕流已启动，请开启无障碍服务",
                    screenWidth = captureProfile.screenWidth,
                    screenHeight = captureProfile.screenHeight
                )
            )
            if (resultCode != 0 && hasControllerPeer()) {
                maybeStartStreamingOffer()
            }
        }, 250)
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

    fun refreshLocalCapabilities() {
        val captureProfile = currentCaptureProfile()
        updateTargetStatus(
            _uiState.value.targetStatus.copy(
                accessibilityEnabled = isAccessibilityEnabled(),
                message = when {
                    _uiState.value.selectedRole != RemoteRole.TARGET -> _uiState.value.targetStatus.message
                    _uiState.value.targetStatus.captureActive && isAccessibilityEnabled() -> "屏幕流已就绪，可接受远控"
                    _uiState.value.targetStatus.captureActive -> "屏幕流已启动，请开启无障碍服务"
                    else -> "请先授权屏幕采集"
                },
                screenWidth = captureProfile.screenWidth,
                screenHeight = captureProfile.screenHeight
            )
        )
    }

    fun release() {
        disconnect()
        RemoteAccessibilityService.stateListener = null
        ScreenCaptureForegroundService.statusListener = null
        ScreenCaptureForegroundService.messageListener = null
        peerConnectionFactory.dispose()
        eglBase.release()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private fun startScreenShareInternal(permissionData: Intent, captureWidth: Int, captureHeight: Int) {
        stopLocalTrackOnly()
        val helper = SurfaceTextureHelper.create("ScreenShareCaptureThread", eglBase.eglBaseContext)
        val source = peerConnectionFactory.createVideoSource(true)
        val capturer = ScreenCapturerAndroid(
            permissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        stopScreenShare()
                        updateTargetStatus(
                            _uiState.value.targetStatus.copy(
                                captureActive = false,
                                accessibilityEnabled = isAccessibilityEnabled(),
                                message = "系统停止了屏幕采集"
                            )
                        )
                    }
                }
            }
        )
        capturer.initialize(helper, appContext, source.capturerObserver)
        capturer.startCapture(captureWidth, captureHeight, SCREEN_SHARE_MAX_FPS)
        surfaceTextureHelper = helper
        screenCapturer = capturer
        localVideoSource = source
        localScreenTrack = peerConnectionFactory.createVideoTrack("remote-screen-track", source)
        publishRendererBindings()
        clearPeerConnection()
        maybeStartStreamingOffer()
    }

    private fun stopScreenShare() {
        ScreenCaptureForegroundService.stop(appContext)
        stopLocalTrackOnly()
        clearPeerConnection()
    }

    private fun stopLocalTrackOnly() {
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose()
        localScreenTrack?.dispose()
        localVideoSource?.dispose()
        surfaceTextureHelper?.dispose()
        screenCapturer = null
        localScreenTrack = null
        localVideoSource = null
        localVideoSender = null
        surfaceTextureHelper = null
        publishRendererBindings()
    }

    private fun createPeerConnectionIfNeeded(): PeerConnection {
        peerConnection?.let { return it }
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.timemotion.top:3478").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = peerConnectionFactory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    pushStatus("ICE: $state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) {
                    signalClient.sendSignal(
                        signalType = "candidate",
                        payload = JSONObject()
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                            .put("candidate", candidate.sdp)
                    )
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    val track = receiver.track() as? VideoTrack ?: return
                    remoteVideoTrack = track
                    publishRendererBindings()
                }
            }
        ) ?: error("Failed to create peer connection")

        if (_uiState.value.selectedRole == RemoteRole.TARGET) {
            localScreenTrack?.let { track ->
                localVideoSender = connection.addTrack(track)
                configureLocalVideoSender()
            }
        }
        peerConnection = connection
        return connection
    }

    private fun maybeStartStreamingOffer() {
        if (_uiState.value.selectedRole != RemoteRole.TARGET) return
        if (localScreenTrack == null || !hasControllerPeer() || isMakingOffer) return
        val connection = createPeerConnectionIfNeeded()
        isMakingOffer = true
        connection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        signalClient.sendSignal(
                            signalType = "offer",
                            payload = JSONObject().put("sdp", description.description)
                        )
                        isMakingOffer = false
                        pushStatus("已发送屏幕流 offer")
                    }

                    override fun onSetFailure(error: String) {
                        isMakingOffer = false
                        pushStatus("设置本地 offer 失败: $error")
                    }
                }, description)
            }

            override fun onCreateFailure(error: String) {
                isMakingOffer = false
                pushStatus("创建屏幕流 offer 失败: $error")
            }
        }, MediaConstraints())
    }

    private fun handleOffer(payload: JSONObject) {
        val connection = createPeerConnectionIfNeeded()
        val offer = SessionDescription(SessionDescription.Type.OFFER, payload.getString("sdp"))
        connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                flushPendingIce()
                connection.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        connection.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                signalClient.sendSignal(
                                    signalType = "answer",
                                    payload = JSONObject().put("sdp", description.description)
                                )
                                pushStatus("已发送 answer")
                            }
                        }, description)
                    }
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String) {
                pushStatus("设置远端 offer 失败: $error")
            }
        }, offer)
    }

    private fun handleAnswer(payload: JSONObject) {
        val connection = peerConnection ?: return
        val answer = SessionDescription(SessionDescription.Type.ANSWER, payload.getString("sdp"))
        connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                flushPendingIce()
                pushStatus("远端 answer 已应用")
            }
        }, answer)
    }

    private fun handleCandidate(payload: JSONObject) {
        val candidate = IceCandidate(
            payload.optString("sdpMid"),
            payload.getInt("sdpMLineIndex"),
            payload.getString("candidate")
        )
        val connection = peerConnection
        if (connection == null || connection.remoteDescription == null) {
            pendingRemoteIce += candidate
        } else {
            connection.addIceCandidate(candidate)
        }
    }

    private fun flushPendingIce() {
        val connection = peerConnection ?: return
        if (connection.remoteDescription == null) return
        pendingRemoteIce.forEach(connection::addIceCandidate)
        pendingRemoteIce.clear()
    }

    private fun clearPeerConnection() {
        remoteVideoTrack = null
        localVideoSender = null
        pendingRemoteIce.clear()
        isMakingOffer = false
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        publishRendererBindings()
    }

    private fun publishRendererBindings() {
        val localBinding = localScreenTrack?.let { track ->
            VideoRendererBinding(
                eglBaseContext = eglBase.eglBaseContext,
                mirror = false,
                attach = { renderer -> track.addSink(renderer) },
                detach = { renderer -> track.removeSink(renderer) }
            )
        }
        val remoteBinding = remoteVideoTrack?.let { track ->
            VideoRendererBinding(
                eglBaseContext = eglBase.eglBaseContext,
                mirror = false,
                attach = { renderer -> track.addSink(renderer) },
                detach = { renderer -> track.removeSink(renderer) }
            )
        }
        _uiState.value = _uiState.value.copy(localRenderer = localBinding, remoteRenderer = remoteBinding)
    }

    private fun onSignalEvent(event: RemoteSignalEvent) {
        mainHandler.post {
            when (event) {
                is RemoteSignalEvent.Connected -> pushStatus("已连上服务")
                is RemoteSignalEvent.Joined -> {
                    _uiState.value = _uiState.value.copy(
                        isConnected = true,
                        peers = event.peers,
                        targetStatus = event.targetStatus ?: _uiState.value.targetStatus,
                        status = "已加入房间"
                    )
                    appendLog("加入房间成功")
                    if (_uiState.value.selectedRole == RemoteRole.TARGET) {
                        refreshForegroundNotification()
                        publishTargetStatus()
                        maybeStartStreamingOffer()
                    }
                }
                is RemoteSignalEvent.PeerUpdate -> {
                    _uiState.value = _uiState.value.copy(peers = event.peers)
                    appendLog("成员更新: ${event.peers.joinToString { "${it.displayName}-${it.role.title}" }}")
                    if (_uiState.value.selectedRole == RemoteRole.TARGET) {
                        refreshForegroundNotification()
                        maybeStartStreamingOffer()
                    } else if (!hasTargetPeer()) {
                        clearPeerConnection()
                    }
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
                                message = "未开启无障碍服务，无法执行远控指令"
                            )
                        )
                        appendLog("无障碍服务未开启，命令未执行")
                        return@post
                    }
                    val success = accessibility.execute(event.command)
                    updateTargetStatus(
                        _uiState.value.targetStatus.copy(
                            accessibilityEnabled = true,
                            message = if (success) "${event.fromDisplayName} 已执行 ${event.command.action.name}" else "远控指令执行失败"
                        )
                    )
                    appendLog("${event.fromDisplayName} -> ${event.command.action.name}")
                }
                is RemoteSignalEvent.SignalReceived -> {
                    when (event.signalType) {
                        "offer" -> handleOffer(event.payload)
                        "answer" -> handleAnswer(event.payload)
                        "candidate" -> handleCandidate(event.payload)
                    }
                }
                is RemoteSignalEvent.Error -> pushStatus(event.message)
                is RemoteSignalEvent.Disconnected -> {
                    clearPeerConnection()
                    _uiState.value = _uiState.value.copy(
                        isConnected = false,
                        peers = emptyList(),
                        status = "连接关闭"
                    )
                    appendLog("连接已关闭")
                    refreshForegroundNotification()
                }
            }
        }
    }

    private fun onForegroundServiceChanged(active: Boolean) {
        mainHandler.post {
            updateTargetStatus(
                _uiState.value.targetStatus.copy(
                    captureActive = active || localScreenTrack != null,
                    accessibilityEnabled = isAccessibilityEnabled(),
                    message = if (active || localScreenTrack != null) "前台投屏服务已启动" else "前台投屏服务已关闭"
                )
            )
        }
    }

    private fun hasControllerPeer(): Boolean = _uiState.value.peers.any { it.role == RemoteRole.CONTROLLER }
    private fun hasTargetPeer(): Boolean = _uiState.value.peers.any { it.role == RemoteRole.TARGET }

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

    private fun configureLocalVideoSender() {
        val sender = localVideoSender ?: return
        val parameters = sender.parameters
        val encodings = parameters.encodings
        if (encodings.isEmpty()) return
        encodings.forEach { encoding ->
            encoding.maxBitrateBps = SCREEN_SHARE_MAX_BITRATE_BPS
            encoding.minBitrateBps = null
            encoding.maxFramerate = SCREEN_SHARE_MAX_FPS
            encoding.scaleResolutionDownBy = 1.0
        }
        runCatching { sender.parameters = parameters }
            .onFailure { appendLog("应用视频编码参数失败: ${it.message ?: "unknown"}") }
    }

    private fun publishTargetStatus() {
        val status = _uiState.value.targetStatus.copy(
            accessibilityEnabled = isAccessibilityEnabled(),
            captureActive = localScreenTrack != null
        )
        signalClient.sendTargetStatus(status)
    }

    private fun updateTargetStatus(status: RemoteTargetStatus) {
        _uiState.value = _uiState.value.copy(targetStatus = status)
        if (_uiState.value.selectedRole == RemoteRole.TARGET && _uiState.value.isConnected) {
            signalClient.sendTargetStatus(status)
        }
    }

    private fun isAccessibilityEnabled(): Boolean = RemoteAccessibilityService.isEnabled(appContext)

    private fun onAccessibilityAvailabilityChanged(enabled: Boolean) {
        val current = _uiState.value.targetStatus
        val message = when {
            _uiState.value.selectedRole != RemoteRole.TARGET -> current.message
            current.captureActive && enabled -> "屏幕流已就绪，可接受远控"
            current.captureActive -> "屏幕流已启动，请开启无障碍服务"
            enabled -> "无障碍服务已开启，请继续授权屏幕采集"
            else -> current.message
        }
        updateTargetStatus(
            current.copy(
                accessibilityEnabled = enabled,
                message = message
            )
        )
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

private const val SCREEN_SHARE_MIN_WIDTH = 360
private const val SCREEN_SHARE_MIN_HEIGHT = 640
private const val SCREEN_SHARE_MAX_LONG_SIDE = 960
private const val SCREEN_SHARE_MAX_BITRATE_BPS = 1_500_000
private const val SCREEN_SHARE_MAX_FPS = 12

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}
