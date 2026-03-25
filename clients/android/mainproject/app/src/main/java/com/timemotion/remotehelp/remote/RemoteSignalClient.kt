package com.timemotion.remotehelp.remote

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.ui.UiFeedbackBus

sealed interface RemoteSignalEvent {
    data object Connected : RemoteSignalEvent
    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val message: String
    ) : RemoteSignalEvent
    data object Disconnected : RemoteSignalEvent
    data class Error(val message: String) : RemoteSignalEvent
    data class Joined(
        val clientId: String,
        val peers: List<RemotePeer>,
        val targetStatus: RemoteTargetStatus?
    ) : RemoteSignalEvent
    data class PeerUpdate(val peers: List<RemotePeer>) : RemoteSignalEvent
    data class TargetStatusReceived(val status: RemoteTargetStatus) : RemoteSignalEvent
    data class CommandReceived(val command: RemoteCommand, val fromDisplayName: String) : RemoteSignalEvent
    data class SignalReceived(
        val signalType: String,
        val payload: JSONObject,
        val fromDisplayName: String
    ) : RemoteSignalEvent
}

class RemoteSignalClient(
    private val okHttpClient: OkHttpClient,
    private val onEvent: (RemoteSignalEvent) -> Unit
) {
    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 6
        private const val BASE_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 15_000L
    }

    private data class ConnectParams(
        val url: String,
        val roomId: String,
        val role: RemoteRole,
        val displayName: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var roomId: String = ""
    private var webSocket: WebSocket? = null
    private var connectParams: ConnectParams? = null
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null
    private var manualDisconnect = false

    fun connect(url: String, roomId: String, role: RemoteRole, displayName: String) {
        runCatching {
            this.roomId = roomId.trim()
            connectParams = ConnectParams(url.trim(), roomId.trim(), role, displayName.trim())
            manualDisconnect = false
            reconnectAttempt = 0
            cancelReconnect()
            closeCurrentSocket(1000, "reconnect")
            openSocket()
        }.onFailure {
            AppLog.logThrowable("RemoteSignalClient", it, "发起远控信令连接失败")
            UiFeedbackBus.emitTopToast("远控信令连接失败，已记录日志")
        }
    }

    fun sendTargetStatus(status: RemoteTargetStatus) {
        webSocket?.send(
            JSONObject()
                .put("type", "rc_target_status")
                .put("roomId", roomId)
                .put("targetStatus", status.toJson())
                .toString()
        )
    }

    fun sendSignal(signalType: String, payload: JSONObject) {
        webSocket?.send(
            JSONObject()
                .put("type", "rc_signal")
                .put("roomId", roomId)
                .put("signalType", signalType)
                .put("payload", payload)
                .toString()
        )
    }

    fun sendCommand(command: RemoteCommand) {
        webSocket?.send(
            JSONObject()
                .put("type", "rc_command")
                .put("roomId", roomId)
                .put("command", command.toJson())
                .toString()
        )
    }

    fun disconnect() {
        manualDisconnect = true
        cancelReconnect()
        webSocket?.send(JSONObject().put("type", "rc_leave").put("roomId", roomId).toString())
        closeCurrentSocket(1000, "leave")
        webSocket = null
        connectParams = null
        reconnectAttempt = 0
    }

    private fun openSocket() {
        val params = connectParams ?: return
        runCatching {
            val socket = okHttpClient.newWebSocket(
                Request.Builder().url(params.url).build(),
                object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (this@RemoteSignalClient.webSocket !== webSocket) {
                        return
                    }
                    reconnectAttempt = 0
                    onEvent(RemoteSignalEvent.Connected)
                    webSocket.send(
                        JSONObject()
                            .put("type", "rc_join")
                            .put("roomId", params.roomId)
                            .put("role", params.role.wireValue)
                            .put("displayName", params.displayName)
                            .toString()
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (this@RemoteSignalClient.webSocket !== webSocket) {
                        return
                    }
                    runCatching {
                        AppLog.d("RemoteSignalClient", "ws <= $text")
                        val json = JSONObject(text)
                        when (json.getString("type")) {
                            "rc_joined" -> onEvent(
                                RemoteSignalEvent.Joined(
                                    clientId = json.getString("clientId"),
                                    peers = json.getJSONArray("peers").toPeers(),
                                    targetStatus = json.optJSONObject("targetStatus")?.let(RemoteTargetStatus::fromJson)
                                )
                            )

                            "rc_peer_update" -> onEvent(
                                RemoteSignalEvent.PeerUpdate(
                                    peers = json.getJSONArray("peers").toPeers()
                                )
                            )

                            "rc_target_status" -> onEvent(
                                RemoteSignalEvent.TargetStatusReceived(
                                    status = RemoteTargetStatus.fromJson(json.getJSONObject("targetStatus"))
                                )
                            )

                            "rc_command" -> onEvent(
                                RemoteSignalEvent.CommandReceived(
                                    command = RemoteCommand.fromJson(json.getJSONObject("command")),
                                    fromDisplayName = json.optString("fromDisplayName")
                                )
                            )

                            "rc_signal" -> onEvent(
                                RemoteSignalEvent.SignalReceived(
                                    signalType = json.getString("signalType"),
                                    payload = json.getJSONObject("payload"),
                                    fromDisplayName = json.optString("fromDisplayName")
                                )
                            )

                            "error" -> onEvent(RemoteSignalEvent.Error(json.getString("message")))
                        }
                    }.onFailure {
                        AppLog.logThrowable("RemoteSignalClient", it, "处理远控信令消息失败")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@RemoteSignalClient.webSocket !== webSocket) {
                        return
                    }
                    this@RemoteSignalClient.webSocket = null
                    if (manualDisconnect || connectParams == null) {
                        onEvent(RemoteSignalEvent.Disconnected)
                        return
                    }
                    scheduleReconnect("远控信令已断开: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@RemoteSignalClient.webSocket !== webSocket) {
                        return
                    }
                    this@RemoteSignalClient.webSocket = null
                    if (manualDisconnect || connectParams == null) {
                        onEvent(RemoteSignalEvent.Disconnected)
                        return
                    }
                    scheduleReconnect("远控连接失败: ${t.message ?: "unknown"}")
                }
                }
            )
            webSocket = socket
        }.onFailure {
            AppLog.logThrowable("RemoteSignalClient", it, "创建远控 WebSocket 失败")
            UiFeedbackBus.emitTopToast("远控服务不可用，已记录日志")
            onEvent(RemoteSignalEvent.Error("远控服务不可用"))
        }
    }

    private fun scheduleReconnect(message: String) {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            UiFeedbackBus.emitTopToast("远控信令连接失败，请检查网络后重试")
            AppLog.w("RemoteSignalClient", "远控信令重连达到上限: $message")
            onEvent(RemoteSignalEvent.Error(message))
            onEvent(RemoteSignalEvent.Disconnected)
            return
        }
        val delayMs = (BASE_RECONNECT_DELAY_MS shl reconnectAttempt).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectAttempt += 1
        if (reconnectAttempt == 1) {
            UiFeedbackBus.emitTopToast("网络异常，远控信令正在重连")
        }
        AppLog.w("RemoteSignalClient", "远控信令准备重连 attempt=$reconnectAttempt delayMs=$delayMs message=$message")
        onEvent(RemoteSignalEvent.Reconnecting(reconnectAttempt, delayMs, message))
        cancelReconnect()
        reconnectRunnable = Runnable {
            if (manualDisconnect || connectParams == null) {
                return@Runnable
            }
            openSocket()
        }
        reconnectRunnable?.let { mainHandler.postDelayed(it, delayMs) }
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun closeCurrentSocket(code: Int, reason: String) {
        webSocket?.close(code, reason)
        webSocket = null
    }
}
