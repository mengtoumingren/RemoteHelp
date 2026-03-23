package com.timemotion.remotehelp.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

sealed interface RemoteSignalEvent {
    data object Connected : RemoteSignalEvent
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
    private var roomId: String = ""
    private var webSocket: WebSocket? = null

    fun connect(url: String, roomId: String, role: RemoteRole, displayName: String) {
        this.roomId = roomId
        webSocket?.close(1000, "reconnect")
        webSocket = okHttpClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onEvent(RemoteSignalEvent.Connected)
                    webSocket.send(
                        JSONObject()
                            .put("type", "rc_join")
                            .put("roomId", roomId)
                            .put("role", role.wireValue)
                            .put("displayName", displayName)
                            .toString()
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
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
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onEvent(RemoteSignalEvent.Disconnected)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onEvent(RemoteSignalEvent.Error("连接失败: ${t.message ?: "unknown"}"))
                }
            }
        )
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
        webSocket?.send(JSONObject().put("type", "rc_leave").put("roomId", roomId).toString())
        webSocket?.close(1000, "leave")
        webSocket = null
    }
}
