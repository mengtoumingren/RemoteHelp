package com.timemotion.remotehelp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ServerApiClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build()
) {
    suspend fun createInvite(serverUrl: String, payload: HelpInvitePayload): ServerInvitePayload = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("helperName", payload.helperName)
            .put("elderName", payload.elderName)
            .put("elderPhone", payload.elderPhone)
        executeInviteRequest(serverUrl, "/invites", body)
    }

    suspend fun resolveInvite(serverUrl: String, rawInvite: String): ServerInvitePayload = withContext(Dispatchers.IO) {
        val token = HelpLinkCodec.extractToken(rawInvite).ifBlank { error("未识别到请求令牌") }
        executeInviteRequest(serverUrl, "/invites/resolve", JSONObject().put("token", token))
    }

    private fun executeInviteRequest(serverUrl: String, path: String, body: JSONObject): ServerInvitePayload {
        val url = resolveHttpBaseUrl(serverUrl).newBuilder()
            .encodedPath(path)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            val json = if (payload.isBlank()) JSONObject() else JSONObject(payload)
            if (!response.isSuccessful || !json.optBoolean("ok", response.isSuccessful)) {
                error(json.optString("message").ifBlank { "服务端请求失败: ${response.code}" })
            }
            val inviteJson = json.optJSONObject("invite") ?: error("服务端未返回邀请数据")
            return ServerInvitePayload(
                requestId = inviteJson.getString("requestId"),
                sessionId = inviteJson.optString("sessionId").ifBlank { inviteJson.getString("requestId") },
                helperName = inviteJson.optString("helperName"),
                elderName = inviteJson.optString("elderName"),
                elderPhone = inviteJson.optString("elderPhone"),
                createdAt = inviteJson.optLong("createdAt"),
                expiresAt = inviteJson.optLong("expiresAt"),
                inviteToken = inviteJson.optString("inviteToken"),
                channelToken = inviteJson.optString("channelToken"),
                deepLink = inviteJson.optString("deepLink")
            )
        }
    }

    private fun resolveHttpBaseUrl(serverUrl: String) = run {
        val normalized = serverUrl.trim()
        val httpUrlText = when {
            normalized.startsWith("wss://") -> "https://${normalized.removePrefix("wss://")}"
            normalized.startsWith("ws://") -> "http://${normalized.removePrefix("ws://")}"
            normalized.startsWith("https://") || normalized.startsWith("http://") -> normalized
            else -> error("服务地址不正确，请填写例如 ws://10.0.2.2:3000/ws")
        }
        httpUrlText.toHttpUrlOrNull()
            ?.newBuilder()
            ?.query(null)
            ?.fragment(null)
            ?.build()
            ?: error("服务地址不正确，请填写例如 ws://10.0.2.2:3000/ws")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
