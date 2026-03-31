package com.timemotion.remotehelp.core

import android.net.Uri
import java.util.UUID

data class HelpInvitePayload(
    val sessionId: String,
    val requestId: String,
    val helperName: String,
    val elderName: String,
    val elderPhone: String,
    val createdAt: Long,
    val expiresAt: Long
)

data class ServerInvitePayload(
    val requestId: String,
    val sessionId: String,
    val helperName: String,
    val elderName: String,
    val elderPhone: String,
    val createdAt: Long,
    val expiresAt: Long,
    val inviteToken: String,
    val channelToken: String,
    val deepLink: String
)

object HelpLinkCodec {
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

    fun buildDeepLink(token: String): String = buildString {
        append("https://help.yourdomain.com/r/")
        append(token)
    }

    fun extractToken(raw: String): String {
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
}
