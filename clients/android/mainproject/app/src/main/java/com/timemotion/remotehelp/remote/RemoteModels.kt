package com.timemotion.remotehelp.remote

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class RemoteRole(val wireValue: String, val title: String) {
    CONTROLLER("controller", "操控端"),
    TARGET("target", "被协助端");

    companion object {
        fun fromWire(value: String): RemoteRole = entries.firstOrNull { it.wireValue == value } ?: TARGET
    }
}

enum class RemoteAction {
    TAP,
    SWIPE,
    DRAG,
    BACK,
    HOME,
    RECENTS
}

data class RemotePeer(
    val clientId: String,
    val displayName: String,
    val role: RemoteRole
)

data class RemoteCommand(
    val action: RemoteAction,
    val normalizedX: Float? = null,
    val normalizedY: Float? = null,
    val screenX: Int? = null,
    val screenY: Int? = null,
    val endNormalizedX: Float? = null,
    val endNormalizedY: Float? = null,
    val endScreenX: Int? = null,
    val endScreenY: Int? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("action", action.name)
        .put("normalizedX", normalizedX?.toDouble())
        .put("normalizedY", normalizedY?.toDouble())
        .put("screenX", screenX)
        .put("screenY", screenY)
        .put("endNormalizedX", endNormalizedX?.toDouble())
        .put("endNormalizedY", endNormalizedY?.toDouble())
        .put("endScreenX", endScreenX)
        .put("endScreenY", endScreenY)

    companion object {
        fun fromJson(json: JSONObject): RemoteCommand = RemoteCommand(
            action = RemoteAction.valueOf(json.getString("action")),
            normalizedX = if (json.has("normalizedX")) json.getDouble("normalizedX").toFloat() else null,
            normalizedY = if (json.has("normalizedY")) json.getDouble("normalizedY").toFloat() else null,
            screenX = if (json.has("screenX")) json.optInt("screenX") else null,
            screenY = if (json.has("screenY")) json.optInt("screenY") else null,
            endNormalizedX = if (json.has("endNormalizedX")) json.getDouble("endNormalizedX").toFloat() else null,
            endNormalizedY = if (json.has("endNormalizedY")) json.getDouble("endNormalizedY").toFloat() else null,
            endScreenX = if (json.has("endScreenX")) json.optInt("endScreenX") else null,
            endScreenY = if (json.has("endScreenY")) json.optInt("endScreenY") else null
        )
    }
}

data class RemoteTargetStatus(
    val captureActive: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val softKeyboardHidden: Boolean = false,
    val message: String = "等待被协助端授权",
    val screenWidth: Int = 1080,
    val screenHeight: Int = 1920
) {
    fun toJson(): JSONObject = JSONObject()
        .put("captureActive", captureActive)
        .put("accessibilityEnabled", accessibilityEnabled)
        .put("softKeyboardHidden", softKeyboardHidden)
        .put("message", message)
        .put("screenWidth", screenWidth)
        .put("screenHeight", screenHeight)

    companion object {
        fun fromJson(json: JSONObject): RemoteTargetStatus = RemoteTargetStatus(
            captureActive = json.optBoolean("captureActive"),
            accessibilityEnabled = json.optBoolean("accessibilityEnabled"),
            softKeyboardHidden = json.optBoolean("softKeyboardHidden", false),
            message = json.optString("message"),
            screenWidth = json.optInt("screenWidth", 1080),
            screenHeight = json.optInt("screenHeight", 1920)
        )
    }
}

data class RemoteControlUiState(
    val serverUrl: String = "ws://10.0.2.2:3000/ws",
    val roomId: String = "remote-device",
    val displayName: String = "Device-${UUID.randomUUID().toString().take(4)}",
    val selectedRole: RemoteRole = RemoteRole.TARGET,
    val isConnected: Boolean = false,
    val status: String = "等待连接",
    val peers: List<RemotePeer> = emptyList(),
    val targetStatus: RemoteTargetStatus = RemoteTargetStatus(),
    val logs: List<String> = listOf("远程协助控制台已就绪")
)

fun JSONArray.toPeers(): List<RemotePeer> = buildList {
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        add(
            RemotePeer(
                clientId = item.getString("clientId"),
                displayName = item.getString("displayName"),
                role = RemoteRole.fromWire(item.getString("role"))
            )
        )
    }
}
