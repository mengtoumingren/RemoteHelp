package com.timemotion.remotehelp.webrtc

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.ui.shared.UiFeedbackBus

sealed interface SignalEvent {
    data object Connected : SignalEvent
    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val message: String
    ) : SignalEvent
    data object Disconnected : SignalEvent
    data class Error(val message: String) : SignalEvent
    data class Joined(
        val clientId: String,
        val participants: List<String>
    ) : SignalEvent
    data class PeerJoined(
        val clientId: String,
        val displayName: String
    ) : SignalEvent
    data class PeerLeft(val clientId: String) : SignalEvent
    data class SignalMessage(
        val fromClientId: String,
        val fromDisplayName: String,
        val signalType: String,
        val payload: JSONObject
    ) : SignalEvent
}

class SignalClient(
    private val okHttpClient: OkHttpClient,
    private val onEvent: (SignalEvent) -> Unit
) {
    companion object {
        private const val TAG = "SignalClient"
        private const val MAX_RECONNECT_ATTEMPTS = 6
        private const val BASE_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 15_000L
    }

    private data class ConnectParams(
        val url: String,
        val roomId: String,
        val displayName: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private var connectParams: ConnectParams? = null
    private val sessionId = UUID.randomUUID().toString()
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null
    private var manualDisconnect = false

    fun connect(url: String, roomId: String, displayName: String) {
        runCatching {
            connectParams = ConnectParams(url.trim(), roomId.trim(), displayName.trim())
            manualDisconnect = false
            reconnectAttempt = 0
            cancelReconnect()
            closeCurrentSocket(1000, "reconnect")
            openSocket()
        }.onFailure {
            AppLog.logThrowable(TAG, it, "发起信令连接失败")
            UiFeedbackBus.emitTopToast("信令连接失败，已记录日志")
        }
    }

    fun sendSignal(type: String, targetClientId: String?, payload: JSONObject) {
        val params = connectParams ?: return
        webSocket?.send(
            JSONObject()
                .put("type", "signal")
                .put("roomId", params.roomId)
                .put("targetClientId", targetClientId)
                .put("signalType", type)
                .put("payload", payload)
                .toString()
        )
    }

    fun leave() {
        manualDisconnect = true
        cancelReconnect()
        val params = connectParams
        if (params != null) {
            webSocket?.send(
                JSONObject()
                    .put("type", "leave")
                    .put("roomId", params.roomId)
                    .put("displayName", params.displayName)
                    .toString()
            )
        }
        closeCurrentSocket(1000, "leave")
        webSocket = null
        connectParams = null
        reconnectAttempt = 0
    }

    private fun openSocket() {
        val params = connectParams ?: return
        runCatching {
            val request = Request.Builder().url(params.url).build()
            val socket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (this@SignalClient.webSocket !== webSocket) {
                    return
                }
                reconnectAttempt = 0
                onEvent(SignalEvent.Connected)
                webSocket.send(
                    JSONObject()
                        .put("type", "join")
                        .put("roomId", params.roomId)
                        .put("displayName", params.displayName)
                        .put("sessionId", sessionId)
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@SignalClient.webSocket !== webSocket) {
                    return
                }
                runCatching {
                    AppLog.d(TAG, "ws <= $text")
                    val json = JSONObject(text)
                    val type = json.getString("type")
                    AppLog.d(TAG, "ws <= type=$type roomId=${params.roomId} sessionId=$sessionId")
                    when (type) {
                        "joined" -> onEvent(
                            SignalEvent.Joined(
                                clientId = json.getString("clientId"),
                                participants = json.optJSONArray("participants").toStringList()
                            )
                        )

                        "peer-joined" -> onEvent(
                            SignalEvent.PeerJoined(
                                clientId = json.getString("clientId"),
                                displayName = json.optString("displayName")
                            )
                        )

                        "peer-left" -> onEvent(
                            SignalEvent.PeerLeft(
                                clientId = json.getString("clientId")
                            )
                        )

                        "signal" -> onEvent(
                            SignalEvent.SignalMessage(
                                fromClientId = json.getString("fromClientId"),
                                fromDisplayName = json.optString("fromDisplayName"),
                                signalType = json.getString("signalType"),
                                payload = json.getJSONObject("payload")
                            )
                        )

                        "error" -> onEvent(SignalEvent.Error(json.getString("message")))
                    }
                }.onFailure {
                    AppLog.logThrowable(TAG, it, "处理信令消息失败")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@SignalClient.webSocket !== webSocket) {
                    return
                }
                this@SignalClient.webSocket = null
                if (manualDisconnect || connectParams == null) {
                    onEvent(SignalEvent.Disconnected)
                    return
                }
                scheduleReconnect("WS 已断开: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@SignalClient.webSocket !== webSocket) {
                    return
                }
                this@SignalClient.webSocket = null
                if (manualDisconnect || connectParams == null) {
                    onEvent(SignalEvent.Disconnected)
                    return
                }
                scheduleReconnect("信令连接失败: ${t.message ?: "unknown"}")
            }
            })
            webSocket = socket
        }.onFailure {
            AppLog.logThrowable(TAG, it, "创建信令 WebSocket 失败")
            UiFeedbackBus.emitTopToast("信令服务不可用，已记录日志")
            onEvent(SignalEvent.Error("信令服务不可用"))
        }
    }

    private fun scheduleReconnect(message: String) {
        val params = connectParams ?: return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            UiFeedbackBus.emitTopToast("信令网络连接失败，请检查网络后重试")
            AppLog.w(TAG, "信令重连达到上限: $message")
            onEvent(SignalEvent.Error(message))
            onEvent(SignalEvent.Disconnected)
            return
        }
        val delayMs = (BASE_RECONNECT_DELAY_MS shl reconnectAttempt).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectAttempt += 1
        if (reconnectAttempt == 1) {
            UiFeedbackBus.emitTopToast("网络异常，信令正在重连")
        }
        AppLog.w(TAG, "信令准备重连 attempt=$reconnectAttempt delayMs=$delayMs message=$message")
        onEvent(SignalEvent.Reconnecting(reconnectAttempt, delayMs, message))
        cancelReconnect()
        reconnectRunnable = Runnable {
            if (manualDisconnect || connectParams == null || params != connectParams) {
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

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until length()) {
            add(optString(index))
        }
    }
}
