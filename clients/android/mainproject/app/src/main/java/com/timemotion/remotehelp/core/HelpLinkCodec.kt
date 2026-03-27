package com.timemotion.remotehelp.core

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class HelpInvitePayload(
    val sessionId: String,
    val requestId: String,
    val helperName: String,
    val elderName: String,
    val elderPhone: String,
    val createdAt: Long,
    val expiresAt: Long
)

object HelpLinkCodec {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SECRET = "remotehelp-demo-secret"
    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L

    fun createInvitePayload(
        helperName: String,
        elderName: String,
        elderPhone: String,
        now: Long = System.currentTimeMillis()
    ): HelpInvitePayload {
        val sessionId = "sess-${UUID.randomUUID().toString().take(8)}"
        return HelpInvitePayload(
            sessionId = sessionId,
            requestId = sessionId,
            helperName = helperName.trim().ifBlank { "协助方" },
            elderName = elderName.trim().ifBlank { "协助对象" },
            elderPhone = elderPhone.trim(),
            createdAt = now,
            expiresAt = now + DEFAULT_TTL_MS
        )
    }

    fun createInvite(
        helperName: String,
        elderName: String,
        elderPhone: String,
        now: Long = System.currentTimeMillis()
    ): Pair<HelpInvitePayload, String> {
        val payload = createInvitePayload(helperName, elderName, elderPhone, now)
        return payload to encode(payload)
    }

    fun buildDeepLink(token: String, sessionId: String? = null): String =
        buildString {
            append("https://help.yourdomain.com/r/")
            append(token)
            if (!sessionId.isNullOrBlank()) {
                append("?sessionId=")
                append(Uri.encode(sessionId))
            }
        }

    fun encode(payload: HelpInvitePayload): String {
        val json = JSONObject()
            .put("sessionId", payload.sessionId)
            .put("requestId", payload.requestId)
            .put("helperName", payload.helperName)
            .put("elderName", payload.elderName)
            .put("elderPhone", payload.elderPhone)
            .put("createdAt", payload.createdAt)
            .put("expiresAt", payload.expiresAt)
        val encodedPayload = Base64.encodeToString(
            json.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$encodedPayload.${sign(encodedPayload)}"
    }

    fun parse(raw: String): Result<HelpInvitePayload> = runCatching {
        val token = extractToken(raw).ifBlank { error("未识别到请求令牌") }
        val parts = token.split(".")
        require(parts.size == 2) { "请求令牌格式不正确" }
        val payloadPart = parts[0]
        val signaturePart = parts[1]
        require(sign(payloadPart) == signaturePart) { "签名校验失败" }
        val jsonBytes = Base64.decode(payloadPart, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val json = JSONObject(String(jsonBytes, StandardCharsets.UTF_8))
        val sessionId = json.optString("sessionId").ifBlank { json.getString("requestId") }
        val payload = HelpInvitePayload(
            sessionId = sessionId,
            requestId = json.optString("requestId").ifBlank { sessionId },
            helperName = json.optString("helperName"),
            elderName = json.optString("elderName"),
            elderPhone = json.optString("elderPhone"),
            createdAt = json.optLong("createdAt"),
            expiresAt = json.optLong("expiresAt")
        )
        require(payload.expiresAt > System.currentTimeMillis()) { "链接已过期，请重新发起协助" }
        payload
    }

    private fun extractToken(raw: String): String {
        val trimmed = raw.trim()
        val normalized = trimmed.substringBefore("#Intent")
        if (!normalized.contains("://")) {
            return trimmed
        }
        val uri = Uri.parse(normalized)
        return uri.getQueryParameter("token")
            ?: uri.lastPathSegment
            ?: ""
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(SECRET.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { each -> "%02x".format(each) }
    }
}
