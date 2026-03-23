package com.timemotion.remotehelp.webrtc

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface SignalEvent {
    data object Connected : SignalEvent
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
    private var webSocket: WebSocket? = null
    private var roomId: String = ""
    private var displayName: String = ""
    private val sessionId = UUID.randomUUID().toString()

    fun connect(url: String, roomId: String, displayName: String) {
        this.roomId = roomId
        this.displayName = displayName
        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onEvent(SignalEvent.Connected)
                webSocket.send(
                    JSONObject()
                        .put("type", "join")
                        .put("roomId", roomId)
                        .put("displayName", displayName)
                        .put("sessionId", sessionId)
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.getString("type")) {
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
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onEvent(SignalEvent.Disconnected)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onEvent(SignalEvent.Error("信令连接失败: ${t.message ?: "unknown"}"))
            }
        })
    }

    fun sendSignal(type: String, targetClientId: String?, payload: JSONObject) {
        webSocket?.send(
            JSONObject()
                .put("type", "signal")
                .put("roomId", roomId)
                .put("targetClientId", targetClientId)
                .put("signalType", type)
                .put("payload", payload)
                .toString()
        )
    }

    fun leave() {
        webSocket?.send(
            JSONObject()
                .put("type", "leave")
                .put("roomId", roomId)
                .put("displayName", displayName)
                .toString()
        )
        webSocket?.close(1000, "leave")
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
