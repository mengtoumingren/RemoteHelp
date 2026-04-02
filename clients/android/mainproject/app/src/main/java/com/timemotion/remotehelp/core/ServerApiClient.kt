package com.timemotion.remotehelp.core

import com.timemotion.remotehelp.BuildConfig
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
    suspend fun createInvite(serverUrl: String, payload: HelpInvitePayload, inviteApiKey: String = ""): ServerInvitePayload = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("helperName", payload.helperName)
            .put("elderName", payload.elderName)
            .put("elderPhone", payload.elderPhone)
        executeInviteRequest(serverUrl, "/invites", body, inviteApiKey)
    }

    suspend fun resolveInvite(serverUrl: String, rawInvite: String, inviteApiKey: String = ""): ServerInvitePayload = withContext(Dispatchers.IO) {
        val token = HelpLinkCodec.extractToken(rawInvite).ifBlank { error("未识别到请求令牌") }
        executeInviteRequest(serverUrl, "/invites/resolve", JSONObject().put("token", token), inviteApiKey)
    }

    private fun executeInviteRequest(serverUrl: String, path: String, body: JSONObject, inviteApiKey: String): ServerInvitePayload {
        val url = resolveHttpBaseUrl(serverUrl).newBuilder()
            .encodedPath(path)
            .build()
        val effectiveInviteApiKey = inviteApiKey.trim().ifBlank { BuildConfig.INVITE_API_KEY }
        val request = Request.Builder()
            .url(url)
            .apply {
                if (effectiveInviteApiKey.isNotBlank()) {
                    header("x-invite-api-key", effectiveInviteApiKey)
                }
            }
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
            normalized.startsWith("ws://") -> {
                if (!BuildConfig.ALLOW_INSECURE_TRANSPORT) {
                    error("生产环境仅允许 wss:// 地址")
                }
                "http://${normalized.removePrefix("ws://")}" 
            }
            normalized.startsWith("https://") -> normalized
            normalized.startsWith("http://") -> {
                if (!BuildConfig.ALLOW_INSECURE_TRANSPORT) {
                    error("生产环境仅允许 HTTPS 接口")
                }
                normalized
            }
            else -> error("服务地址不正确，请填写例如 wss://help.yourdomain.com/ws")
        }
        httpUrlText.toHttpUrlOrNull()
            ?.newBuilder()
            ?.query(null)
            ?.fragment(null)
            ?.build()
            ?: error("服务地址不正确，请填写例如 wss://help.yourdomain.com/ws")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
