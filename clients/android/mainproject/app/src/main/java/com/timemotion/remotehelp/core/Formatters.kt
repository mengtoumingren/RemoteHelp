package com.timemotion.remotehelp.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

fun formatDateTime(value: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(value))

fun formatRemaining(expiresAt: Long, now: Long = System.currentTimeMillis()): String {
    val totalSeconds = max(0L, (expiresAt - now + 999L) / 1000L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
