package com.timemotion.webrtcdemo.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.timemotion.webrtcdemo.ui.VideoRendererBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

data class CallUiState(
    val serverUrl: String = "ws://10.0.2.2:3000/ws",
    val roomId: String = "demo-room",
    val displayName: String = "Android-${UUID.randomUUID().toString().take(4)}",
    val status: String = "等待加入房间",
    val isConnecting: Boolean = false,
    val isInRoom: Boolean = false,
    val isMicEnabled: Boolean = true,
    val isCameraEnabled: Boolean = true,
    val remotePeerName: String = "",
    val localRenderer: VideoRendererBinding? = null,
    val remoteRenderer: VideoRendererBinding? = null
)

class CallController(
    private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
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
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteClientId: String? = null
    private var localClientId: String? = null
    private var isMakingOffer = false

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
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

    fun startLocalMedia() {
        if (localVideoTrack != null && localAudioTrack != null) {
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
        capturer.startCapture(1280, 720, 30)
        surfaceTextureHelper = helper
        videoCapturer = capturer
        videoSource = source
        localVideoTrack = videoSource?.let {
            peerConnectionFactory.createVideoTrack("local-video", it)
        }
        localAudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = localAudioSource?.let {
            peerConnectionFactory.createAudioTrack("local-audio", it)
        }
        publishRendererBindings()
        setStatus("本地媒体已准备")
    }

    fun joinRoom() {
        if (_uiState.value.serverUrl.isBlank() || _uiState.value.roomId.isBlank()) {
            setStatus("请填写服务地址和房间号")
            return
        }
        startLocalMedia()
        remoteClientId = null
        _uiState.value = _uiState.value.copy(isConnecting = true, status = "连接信令服务中")
        signalClient.connect(
            url = _uiState.value.serverUrl.trim(),
            roomId = _uiState.value.roomId.trim(),
            displayName = _uiState.value.displayName.trim().ifBlank { "AndroidUser" }
        )
    }

    fun leaveRoom() {
        signalClient.leave()
        clearPeerConnection()
        remoteClientId = null
        localClientId = null
        _uiState.value = _uiState.value.copy(
            isConnecting = false,
            isInRoom = false,
            remotePeerName = "",
            status = "已挂断"
        )
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
        _uiState.value = _uiState.value.copy(isCameraEnabled = enabled)
    }

    fun release() {
        leaveRoom()
        videoCapturer?.dispose()
        videoSource?.dispose()
        localAudioSource?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnectionFactory.dispose()
        eglBase.release()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private fun createPeerConnectionIfNeeded(): PeerConnection {
        peerConnection?.let { return it }
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.timemotion.top:3478").createIceServer()
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val connection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
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

                override fun onRenegotiationNeeded() = Unit

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    val track = receiver.track() as? VideoTrack ?: return
                    remoteVideoTrack = track
                    publishRendererBindings()
                }
            }
        ) ?: error("Failed to create PeerConnection")

        localVideoTrack?.let { connection.addTrack(it) }
        localAudioTrack?.let { connection.addTrack(it) }
        peerConnection = connection
        return connection
    }

    private fun makeOffer() {
        if (remoteClientId == null || isMakingOffer) {
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

    private fun clearPeerConnection() {
        remoteVideoTrack = null
        pendingRemoteIce.clear()
        isMakingOffer = false
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    private fun publishRendererBindings() {
        val localBinding = localVideoTrack?.let { track ->
            VideoRendererBinding(
                eglBaseContext = eglBase.eglBaseContext,
                mirror = true,
                attach = { renderer ->
                    renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    track.addSink(renderer)
                },
                detach = { renderer ->
                    track.removeSink(renderer)
                }
            )
        }
        val remoteBinding = remoteVideoTrack?.let { track ->
            VideoRendererBinding(
                eglBaseContext = eglBase.eglBaseContext,
                mirror = false,
                attach = { renderer ->
                    renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    track.addSink(renderer)
                },
                detach = { renderer ->
                    track.removeSink(renderer)
                }
            )
        }
        _uiState.value = _uiState.value.copy(
            localRenderer = localBinding,
            remoteRenderer = remoteBinding
        )
    }

    private fun onSignalEvent(event: SignalEvent) {
        mainHandler.post {
            when (event) {
                is SignalEvent.Connected -> {
                    setStatus("已连接信令服务，等待房间加入")
                }

                is SignalEvent.Joined -> {
                    localClientId = event.clientId
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        isInRoom = true,
                        status = "已加入房间，当前人数 ${event.participants.size}"
                    )
                }

                is SignalEvent.PeerJoined -> {
                    remoteClientId = event.clientId
                    _uiState.value = _uiState.value.copy(remotePeerName = event.displayName)
                    setStatus("对端已加入，准备协商")
                    makeOffer()
                }

                is SignalEvent.PeerLeft -> {
                    setStatus("对端已离开房间")
                    _uiState.value = _uiState.value.copy(remotePeerName = "")
                    remoteClientId = null
                    clearPeerConnection()
                    publishRendererBindings()
                }

                is SignalEvent.SignalMessage -> {
                    remoteClientId = event.fromClientId
                    _uiState.value = _uiState.value.copy(remotePeerName = event.fromDisplayName)
                    when (event.signalType) {
                        "offer" -> handleOffer(event.fromClientId, event.payload)
                        "answer" -> handleAnswer(event.payload)
                        "candidate" -> handleCandidate(event.payload)
                    }
                }

                is SignalEvent.Error -> {
                    _uiState.value = _uiState.value.copy(isConnecting = false)
                    setStatus(event.message)
                }

                is SignalEvent.Disconnected -> {
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        isInRoom = false,
                        remotePeerName = ""
                    )
                    setStatus("信令连接已断开")
                }
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
