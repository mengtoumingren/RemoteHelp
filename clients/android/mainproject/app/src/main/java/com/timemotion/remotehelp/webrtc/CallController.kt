package com.timemotion.remotehelp.webrtc

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.remote.readDeviceScreenMetrics
import com.timemotion.remotehelp.ui.UiFeedbackBus

data class CallUiState(
    val serverUrl: String = "ws://10.0.2.2:3000/ws",
    val roomId: String = "demo-room",
    val displayName: String = "Android-${UUID.randomUUID().toString().take(4)}",
    val status: String = "等待加入房间",
    val isConnecting: Boolean = false,
    val isInRoom: Boolean = false,
    val isMicEnabled: Boolean = true,
    val isCameraEnabled: Boolean = true,
    val isSpeakerOn: Boolean = true,
    val remotePeerName: String = "",
    val roomParticipantCount: Int = 0,
    val localRenderer: VideoRendererBinding? = null,
    val remoteRenderer: VideoRendererBinding? = null,
    val assistRenderer: VideoRendererBinding? = null
)

data class VideoRendererBinding(
    val eglBaseContext: EglBase.Context,
    val mirror: Boolean,
    val attach: (org.webrtc.VideoSink) -> Unit,
    val detach: (org.webrtc.VideoSink) -> Unit
)

class CallController(
    private val context: Context,
    private val onAppSignal: (signalType: String, payload: JSONObject, fromDisplayName: String) -> Unit
) {
    companion object {
        private const val LOCAL_CAPTURE_WIDTH = 1280
        private const val LOCAL_CAPTURE_HEIGHT = 720
        private const val LOCAL_CAPTURE_FPS = 30
        private const val SCREEN_SHARE_MAX_BITRATE_BPS = 800_000
        private const val SCREEN_SHARE_MAX_FPS = 16
        private const val LOCAL_CAMERA_TRACK_ID = "verification-video"
        private const val LOCAL_SCREEN_TRACK_ID = "screen-share-video"
        private const val EXTRA_SCREEN_CAPTURE_WIDTH = "remotehelp.extra.screen_capture_width"
        private const val EXTRA_SCREEN_CAPTURE_HEIGHT = "remotehelp.extra.screen_capture_height"
        private const val MAX_RTC_RECONNECT_ATTEMPTS = 4
        private const val BASE_RTC_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RTC_RECONNECT_DELAY_MS = 10_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val eglBase = EglBase.create()
    private val okHttpClient = OkHttpClient.Builder().build()
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    private val peerConnectionFactory: PeerConnectionFactory
    private val signalClient = SignalClient(okHttpClient, ::onSignalEvent)

    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var screenSurfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null
    private var screenVideoSource: VideoSource? = null
    private var screenVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSender: RtpSender? = null
    private var screenVideoSender: RtpSender? = null
    private var localAudioSender: RtpSender? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteScreenTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var remoteClientId: String? = null
    private var isMakingOffer = false
    private var isLocalCaptureStarted = false
    private var areLocalTracksAttached = false
    private var shouldInitiateOffer = true
    private var isRtcOfferAllowed = false
    private var allowRtcReconnect = false
    private var rtcReconnectAttempt = 0
    private var rtcReconnectRunnable: Runnable? = null

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(HardwareVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(HardwareVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        publishRendererBindings()
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

    fun setOfferInitiator(value: Boolean) {
        shouldInitiateOffer = value
    }

    fun setRtcOfferAllowed(value: Boolean) {
        isRtcOfferAllowed = value
        if (value) {
            requestRtcNegotiationIfReady()
        }
    }

    fun startLocalMedia() {
        ensureScreenShareTrack()
        if (localVideoTrack != null && localAudioTrack != null) {
            if (!isLocalCaptureStarted) {
                resumeAfterForeground()
            }
            attachLocalTracksToPeerConnection()
            configureAudioRoute()
            requestRtcNegotiationIfReady()
            return
        }
        val capturer = createVideoCapturer()
        if (capturer == null) {
            setStatus("未找到可用摄像头")
            return
        }
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val source = peerConnectionFactory.createVideoSource(false)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(LOCAL_CAPTURE_WIDTH, LOCAL_CAPTURE_HEIGHT, LOCAL_CAPTURE_FPS)
        isLocalCaptureStarted = true
        surfaceTextureHelper = helper
        videoCapturer = capturer
        videoSource = source
        localVideoTrack = peerConnectionFactory.createVideoTrack(LOCAL_CAMERA_TRACK_ID, source)
        localAudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = localAudioSource?.let {
            peerConnectionFactory.createAudioTrack("local-audio", it)
        }
        ensureScreenShareTrack()
        attachLocalTracksToPeerConnection()
        configureAudioRoute()
        publishRendererBindings()
        setStatus("本地媒体已准备")
        requestRtcNegotiationIfReady()
    }

    fun joinRoom(prepareLocalMedia: Boolean = true) {
        if (_uiState.value.serverUrl.isBlank() || _uiState.value.roomId.isBlank()) {
            setStatus("请填写服务地址和房间号")
            return
        }
        allowRtcReconnect = true
        cancelRtcReconnect()
        rtcReconnectAttempt = 0
        if (prepareLocalMedia) {
            startLocalMedia()
        }
        remoteClientId = null
        _uiState.value = _uiState.value.copy(isConnecting = true, status = "连接信令服务中")
        signalClient.connect(
            url = _uiState.value.serverUrl.trim(),
            roomId = _uiState.value.roomId.trim(),
            displayName = _uiState.value.displayName.trim().ifBlank { "AndroidUser" }
        )
    }

    fun leaveRoom() {
        allowRtcReconnect = false
        cancelRtcReconnect()
        rtcReconnectAttempt = 0
        signalClient.leave()
        disposeLocalCameraResources()
        stopScreenShareCaptureInternal(restoreLocalCamera = false)
        disposeScreenShareResources()
        disposeCameraCaptureResources()
        localAudioSource?.dispose()
        localAudioSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        clearPeerConnection()
        remoteClientId = null
        resetAudioRoute()
        _uiState.value = _uiState.value.copy(
            isConnecting = false,
            isInRoom = false,
            roomParticipantCount = 0,
            remotePeerName = "",
            status = "已挂断"
        )
        isRtcOfferAllowed = false
        publishRendererBindings()
    }

    fun toggleMic() {
        val enabled = !_uiState.value.isMicEnabled
        localAudioTrack?.setEnabled(enabled)
        _uiState.value = _uiState.value.copy(isMicEnabled = enabled)
    }

    fun toggleCamera() {
        val enabled = !_uiState.value.isCameraEnabled
        localVideoTrack?.setEnabled(enabled)
        if (!enabled) {
            stopLocalCapture()
        } else if (localVideoTrack != null) {
            resumeAfterForeground()
        }
        _uiState.value = _uiState.value.copy(isCameraEnabled = enabled)
    }

    fun toggleSpeakerOutput() {
        setSpeakerOutputEnabled(!_uiState.value.isSpeakerOn)
    }

    fun startScreenShareCapture(data: Intent): Boolean {
        val track = ensureScreenShareTrack() ?: return false
        if (screenCapturer != null) {
            screenVideoTrack?.setEnabled(true)
            screenVideoSender?.setTrack(screenVideoTrack, false)
            configureScreenShareSender()
            return true
        }
        return runCatching {
            AppLog.d("CallController", "开始屏幕共享采集, screenSender=${screenVideoSender != null}, peer=${peerConnection != null}")
            stopLocalCapture()
            localVideoTrack?.setEnabled(false)
            val helper = SurfaceTextureHelper.create("ScreenShareCaptureThread", eglBase.eglBaseContext)
            val source = screenVideoSource ?: error("屏幕视频源未初始化")
            val metrics = context.readDeviceScreenMetrics()
            val captureWidth = data.getIntExtra(EXTRA_SCREEN_CAPTURE_WIDTH, metrics.width)
                .coerceAtLeast(1)
                .ensureEven()
            val captureHeight = data.getIntExtra(EXTRA_SCREEN_CAPTURE_HEIGHT, metrics.height)
                .coerceAtLeast(1)
                .ensureEven()
            val capturer = ScreenCapturerAndroid(
                data,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post {
                            stopScreenShareCaptureInternal(restoreLocalCamera = true)
                        }
                    }
                }
            )
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(captureWidth, captureHeight, SCREEN_SHARE_MAX_FPS)
            screenSurfaceTextureHelper = helper
            screenCapturer = capturer
            track.setEnabled(true)
            screenVideoSender?.setTrack(track, false)
            configureScreenShareSender()
            attachLocalTracksToPeerConnection()
            AppLog.d("CallController", "屏幕共享采集已启动, sender=${screenVideoSender != null}, track=${track.id()}")
            setStatus("屏幕共享已启动")
            true
        }.getOrElse {
            AppLog.logThrowable("CallController", it, "启动屏幕共享失败")
            if (_uiState.value.isCameraEnabled) {
                resumeAfterForeground()
            }
            setStatus("启动屏幕共享失败: ${it.message ?: "unknown"}")
            false
        }
    }

    fun stopScreenShareCapture() {
        stopScreenShareCaptureInternal(restoreLocalCamera = true)
        screenVideoTrack?.setEnabled(false)
    }

    private fun ensureScreenShareTrack(): VideoTrack? {
        screenVideoTrack?.let { return it }
        val source = peerConnectionFactory.createVideoSource(true)
        val track = peerConnectionFactory.createVideoTrack(LOCAL_SCREEN_TRACK_ID, source)
        track.setEnabled(false)
        screenVideoSource = source
        screenVideoTrack = track
        AppLog.d("CallController", "创建屏幕视频轨, track=${track.id()}")
        attachLocalTracksToPeerConnection()
        return track
    }

    fun setSpeakerOutputEnabled(enabled: Boolean) {
        if (_uiState.value.isSpeakerOn != enabled) {
            _uiState.value = _uiState.value.copy(isSpeakerOn = enabled)
        }
        if (localAudioTrack != null || _uiState.value.isInRoom || _uiState.value.isConnecting) {
            configureAudioRoute()
        }
    }

    fun sendAppSignal(
        signalType: String,
        payload: JSONObject = JSONObject(),
        broadcast: Boolean = false
    ) {
        signalClient.sendSignal(signalType, if (broadcast) null else remoteClientId, payload)
    }

    fun release() {
        allowRtcReconnect = false
        cancelRtcReconnect()
        leaveRoom()
        stopLocalCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()
        localAudioSource?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnectionFactory.dispose()
        eglBase.release()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    fun resumeAfterForeground() {
        if (localVideoTrack == null || !_uiState.value.isCameraEnabled) {
            return
        }
        val capturer = videoCapturer ?: return
        runCatching {
            if (isLocalCaptureStarted) {
                capturer.stopCapture()
            }
            capturer.startCapture(LOCAL_CAPTURE_WIDTH, LOCAL_CAPTURE_HEIGHT, LOCAL_CAPTURE_FPS)
            isLocalCaptureStarted = true
            setStatus("已恢复本地视频采集")
        }.onFailure { error ->
            setStatus("恢复本地视频失败: ${error.message ?: "unknown"}")
        }
    }

    private fun createPeerConnectionIfNeeded(): PeerConnection {
        peerConnection?.let { return it }
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.timemotion.top:3478").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    if (state == PeerConnection.IceConnectionState.CONNECTED ||
                        state == PeerConnection.IceConnectionState.COMPLETED
                    ) {
                        cancelRtcReconnect()
                    }
                    if (state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.CLOSED
                    ) {
                        handleRtcConnectionLost("ICE: $state")
                    }
                    setStatus("ICE: $state")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

                override fun onIceCandidate(candidate: IceCandidate) {
                    signalClient.sendSignal(
                        type = "candidate",
                        targetClientId = remoteClientId,
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
                override fun onRenegotiationNeeded() {
                    if (canInitiateOffer()) {
                        makeOffer()
                    }
                }

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    when (val track = receiver.track()) {
                        is VideoTrack -> {
                            val enabled = runCatching { track.enabled() }.getOrDefault(true)
                            AppLog.d("CallController", "收到远端视频轨 id=${track.id()}, enabled=$enabled")
                            val isScreenTrack = track.id() == LOCAL_SCREEN_TRACK_ID || !enabled
                            val isCameraTrack = track.id() == LOCAL_CAMERA_TRACK_ID || enabled
                            if (isScreenTrack && remoteScreenTrack == null) {
                                remoteScreenTrack = track
                            } else if (isCameraTrack && remoteVideoTrack == null) {
                                remoteVideoTrack = track
                            } else if (remoteVideoTrack == null) {
                                remoteVideoTrack = track
                            } else {
                                remoteScreenTrack = track
                            }
                            publishRendererBindings()
                            setStatus(
                                if (track.id() == LOCAL_SCREEN_TRACK_ID) {
                                    "已接入对方屏幕画面"
                                } else {
                                    "已接入对方画面"
                                }
                            )
                        }
                        is AudioTrack -> {
                            remoteAudioTrack = track
                            configureAudioRoute()
                            setStatus("已接入对方声音")
                        }
                    }
                }
            }
        ) ?: error("Failed to create PeerConnection")

        peerConnection = connection
        if (localVideoTrack != null || localAudioTrack != null) {
            attachLocalTracksToPeerConnection(connection)
        }
        return connection
    }

    private fun makeOffer() {
        if (!canInitiateOffer() || remoteClientId == null || isMakingOffer) {
            return
        }
        isMakingOffer = true
        val connection = createPeerConnectionIfNeeded()
        connection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        signalClient.sendSignal(
                            type = "offer",
                            targetClientId = remoteClientId,
                            payload = JSONObject().put("sdp", description.description)
                        )
                        isMakingOffer = false
                        setStatus("已发送 offer")
                    }

                    override fun onSetFailure(error: String) {
                        isMakingOffer = false
                        setStatus("设置本地 offer 失败: $error")
                    }
                }, description)
            }

            override fun onCreateFailure(error: String) {
                isMakingOffer = false
                setStatus("创建 offer 失败: $error")
            }
        }, MediaConstraints())
    }

    private fun handleOffer(fromClientId: String, payload: JSONObject) {
        remoteClientId = fromClientId
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
                                    type = "answer",
                                    targetClientId = fromClientId,
                                    payload = JSONObject().put("sdp", description.description)
                                )
                                setStatus("已发送 answer")
                            }

                            override fun onSetFailure(error: String) {
                                setStatus("设置本地 answer 失败: $error")
                            }
                        }, description)
                    }

                    override fun onCreateFailure(error: String) {
                        setStatus("创建 answer 失败: $error")
                    }
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String) {
                setStatus("设置远端 offer 失败: $error")
            }
        }, offer)
    }

    private fun handleAnswer(payload: JSONObject) {
        val connection = peerConnection ?: return
        val answer = SessionDescription(SessionDescription.Type.ANSWER, payload.getString("sdp"))
        connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                flushPendingIce()
                setStatus("远端 answer 已应用")
            }

            override fun onSetFailure(error: String) {
                setStatus("设置远端 answer 失败: $error")
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
        if (connection.remoteDescription == null) {
            return
        }
        pendingRemoteIce.forEach(connection::addIceCandidate)
        pendingRemoteIce.clear()
    }

    private fun clearPeerConnection(resetRtcRetryState: Boolean = true) {
        if (resetRtcRetryState) {
            cancelRtcReconnect()
            rtcReconnectAttempt = 0
        }
        remoteVideoTrack = null
        remoteScreenTrack = null
        remoteAudioTrack = null
        pendingRemoteIce.clear()
        isMakingOffer = false
        areLocalTracksAttached = false
        localVideoSender = null
        localAudioSender = null
        screenVideoSender = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    private fun handleRtcConnectionLost(status: String) {
        if (!allowRtcReconnect || !_uiState.value.isInRoom || _uiState.value.roomParticipantCount <= 1) {
            clearRemotePeerState()
            return
        }
        cancelRtcReconnect()
        clearPeerConnection(resetRtcRetryState = false)
        scheduleRtcReconnect(status)
    }

    private fun scheduleRtcReconnect(message: String) {
        if (!allowRtcReconnect || !_uiState.value.isInRoom || _uiState.value.roomParticipantCount <= 1) {
            clearRemotePeerState()
            return
        }
        if (rtcReconnectAttempt >= MAX_RTC_RECONNECT_ATTEMPTS) {
            clearRemotePeerState()
            setStatus("RTC 重连失败: $message")
            UiFeedbackBus.emitTopToast("视频通话重连失败，请检查网络后重试")
            return
        }
        val delayMs = (BASE_RTC_RECONNECT_DELAY_MS shl rtcReconnectAttempt).coerceAtMost(MAX_RTC_RECONNECT_DELAY_MS)
        rtcReconnectAttempt += 1
        setStatus("RTC 断开，${delayMs / 1000}s 后重连")
        if (rtcReconnectAttempt == 1) {
            UiFeedbackBus.emitTopToast("网络异常，视频通话正在重连")
        }
        cancelRtcReconnect()
        rtcReconnectRunnable = Runnable {
            if (!allowRtcReconnect || !_uiState.value.isInRoom || _uiState.value.roomParticipantCount <= 1) {
                return@Runnable
            }
            requestRtcNegotiationIfReady()
        }
        mainHandler.postDelayed(rtcReconnectRunnable!!, delayMs)
    }

    private fun cancelRtcReconnect() {
        rtcReconnectRunnable?.let(mainHandler::removeCallbacks)
        rtcReconnectRunnable = null
    }

    private fun stopLocalCapture() {
        val capturer = videoCapturer ?: return
        if (!isLocalCaptureStarted) {
            return
        }
        runCatching { capturer.stopCapture() }
        isLocalCaptureStarted = false
    }

    private fun disposeLocalCameraResources() {
        localVideoSender?.setTrack(null, false)
        localVideoSender = null
        localVideoTrack?.dispose()
        localVideoTrack = null
    }

    private fun disposeCameraCaptureResources() {
        stopLocalCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
    }

    private fun stopScreenShareCaptureInternal(restoreLocalCamera: Boolean) {
        val capturer = screenCapturer ?: return
        screenCapturer = null
        runCatching { capturer.stopCapture() }
        capturer.dispose()
        screenSurfaceTextureHelper?.dispose()
        screenSurfaceTextureHelper = null
        screenVideoTrack?.setEnabled(false)
        if (restoreLocalCamera && _uiState.value.isCameraEnabled) {
            resumeAfterForeground()
        }
    }

    private fun disposeScreenShareResources() {
        stopScreenShareCaptureInternal(restoreLocalCamera = false)
        screenVideoSender?.setTrack(null, false)
        screenVideoSender = null
        screenVideoTrack?.dispose()
        screenVideoTrack = null
        screenVideoSource?.dispose()
        screenVideoSource = null
    }

    private fun configureAudioRoute() {
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager?.isSpeakerphoneOn = _uiState.value.isSpeakerOn
    }

    private fun resetAudioRoute() {
        audioManager?.isSpeakerphoneOn = false
        audioManager?.mode = AudioManager.MODE_NORMAL
    }

    private fun clearRemotePeerState() {
        cancelRtcReconnect()
        rtcReconnectAttempt = 0
        remoteVideoTrack = null
        remoteScreenTrack = null
        remoteAudioTrack = null
        remoteClientId = null
        _uiState.value = _uiState.value.copy(remotePeerName = "")
        publishRendererBindings()
    }

    private fun canInitiateOffer(): Boolean =
        shouldInitiateOffer &&
            isRtcOfferAllowed &&
            remoteClientId != null &&
            !isMakingOffer &&
            hasLocalMediaTracks()

    private fun hasLocalMediaTracks(): Boolean =
        localVideoTrack != null || localAudioTrack != null

    fun requestRtcNegotiationIfReady() {
        if (canInitiateOffer()) {
            makeOffer()
        }
    }

    private fun publishRendererBindings() {
        val localBinding = localVideoTrack?.let { track ->
            VideoRendererBinding(
                eglBaseContext = eglBase.eglBaseContext,
                mirror = true,
                attach = { renderer ->
                    if (renderer is org.webrtc.SurfaceViewRenderer) {
                        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    }
                    track.addSink(renderer)
                },
                detach = { renderer -> track.removeSink(renderer) }
            )
        }
        val remoteBinding = remoteVideoTrack?.let { track ->
            VideoRendererBinding(
                eglBaseContext = eglBase.eglBaseContext,
                mirror = false,
                attach = { renderer ->
                    if (renderer is org.webrtc.SurfaceViewRenderer) {
                        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    }
                    track.addSink(renderer)
                },
                detach = { renderer -> track.removeSink(renderer) }
            )
        }
        val assistBinding = remoteScreenTrack?.let { track ->
            VideoRendererBinding(
                eglBaseContext = eglBase.eglBaseContext,
                mirror = false,
                attach = { renderer ->
                    if (renderer is org.webrtc.SurfaceViewRenderer) {
                        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    }
                    track.addSink(renderer)
                },
                detach = { renderer -> track.removeSink(renderer) }
            )
        }
        AppLog.d(
            "CallController",
            "刷新渲染绑定 local=${localBinding != null}, remote=${remoteBinding != null}, assist=${assistBinding != null}"
        )
        _uiState.value = _uiState.value.copy(
            localRenderer = localBinding,
            remoteRenderer = remoteBinding,
            assistRenderer = assistBinding
        )
    }

    private fun attachLocalTracksToPeerConnection(connection: PeerConnection? = peerConnection) {
        val targetConnection = connection ?: return
        if (localVideoTrack != null && localVideoSender == null) {
            localVideoSender = targetConnection.addTrack(localVideoTrack)
            configureLocalVideoSender()
        }
        if (localAudioTrack != null && localAudioSender == null) {
            localAudioSender = targetConnection.addTrack(localAudioTrack)
        }
        if (screenVideoTrack != null && screenVideoSender == null) {
            screenVideoSender = targetConnection.addTrack(screenVideoTrack)
            configureScreenShareSender()
        }
        areLocalTracksAttached =
            localVideoSender != null || localAudioSender != null || screenVideoSender != null
    }

    private fun configureLocalVideoSender() {
        val sender = localVideoSender ?: return
        val parameters = sender.parameters
        val encodings = parameters.encodings
        if (encodings.isEmpty()) {
            return
        }
        encodings.forEach { encoding ->
            encoding.maxBitrateBps = 2_000_000
            encoding.minBitrateBps = null
            encoding.maxFramerate = LOCAL_CAPTURE_FPS
            encoding.scaleResolutionDownBy = 1.0
        }
        runCatching { sender.parameters = parameters }
            .onFailure { AppLog.logThrowable("CallController", it, "应用本地视频编码参数失败") }
    }

    private fun configureScreenShareSender() {
        val sender = screenVideoSender ?: return
        val parameters = sender.parameters
        val encodings = parameters.encodings
        if (encodings.isEmpty()) {
            return
        }
        encodings.forEach { encoding ->
            encoding.maxBitrateBps = SCREEN_SHARE_MAX_BITRATE_BPS
            encoding.minBitrateBps = null
            encoding.maxFramerate = SCREEN_SHARE_MAX_FPS
            encoding.scaleResolutionDownBy = 1.0
        }
        runCatching { sender.parameters = parameters }
            .onFailure { AppLog.logThrowable("CallController", it, "应用屏幕共享编码参数失败") }
    }

    private fun onSignalEvent(event: SignalEvent) {
        mainHandler.post {
            runCatching {
                when (event) {
                    is SignalEvent.Connected -> setStatus("已连接信令服务，等待房间加入")
                    is SignalEvent.Reconnecting -> {
                        _uiState.value = _uiState.value.copy(isConnecting = true)
                        setStatus("${event.message}，${event.delayMs / 1000}s 后重连（${event.attempt}/6）")
                    }
                    is SignalEvent.Joined -> {
                        val hasExistingPeer = event.participants.any { it != event.clientId }
                        _uiState.value = _uiState.value.copy(
                            isConnecting = false,
                            isInRoom = true,
                            roomParticipantCount = event.participants.size,
                            remotePeerName = if (hasExistingPeer) {
                                _uiState.value.remotePeerName.ifBlank { "对端" }
                            } else {
                                _uiState.value.remotePeerName
                            },
                            status = "已加入房间，当前人数 ${event.participants.size}"
                        )
                        if (event.participants.size > 1) {
                            requestRtcNegotiationIfReady()
                        }
                    }

                    is SignalEvent.PeerJoined -> {
                        remoteClientId = event.clientId
                        _uiState.value = _uiState.value.copy(
                            remotePeerName = event.displayName,
                            roomParticipantCount = (_uiState.value.roomParticipantCount + 1).coerceAtLeast(1)
                        )
                        setStatus("对端已加入，准备协商")
                        if (canInitiateOffer()) {
                            makeOffer()
                        }
                    }

                    is SignalEvent.PeerLeft -> {
                        setStatus("对端已离开房间")
                        clearRemotePeerState()
                        _uiState.value = _uiState.value.copy(
                            roomParticipantCount = (_uiState.value.roomParticipantCount - 1).coerceAtLeast(1)
                        )
                        clearPeerConnection()
                    }

                    is SignalEvent.SignalMessage -> {
                        remoteClientId = event.fromClientId
                        _uiState.value = _uiState.value.copy(remotePeerName = event.fromDisplayName)
                        when (event.signalType) {
                            "offer" -> handleOffer(event.fromClientId, event.payload)
                            "answer" -> handleAnswer(event.payload)
                            "candidate" -> handleCandidate(event.payload)
                            else -> onAppSignal(event.signalType, event.payload, event.fromDisplayName)
                        }
                    }

                    is SignalEvent.Error -> {
                        _uiState.value = _uiState.value.copy(isConnecting = false)
                        setStatus(event.message)
                    }

                    is SignalEvent.Disconnected -> {
                        cancelRtcReconnect()
                        rtcReconnectAttempt = 0
                        clearRemotePeerState()
                        allowRtcReconnect = false
                        _uiState.value = _uiState.value.copy(
                            isConnecting = false,
                            isInRoom = false,
                            roomParticipantCount = 0,
                            remotePeerName = ""
                        )
                        setStatus("信令连接已断开")
                    }
                }
            }.onFailure {
                AppLog.logThrowable("CallController", it, "处理信令事件失败: ${event::class.simpleName}")
                setStatus("视频通话处理异常，请重试")
            }
        }
    }

    private fun createVideoCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val frontCamera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val cameraName = frontCamera ?: enumerator.deviceNames.firstOrNull()
        return cameraName?.let { enumerator.createCapturer(it, null) }
    }

    private fun setStatus(message: String) {
        _uiState.value = _uiState.value.copy(status = message)
    }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}

private fun Int.ensureEven(): Int = if (this % 2 == 0) this else this - 1
