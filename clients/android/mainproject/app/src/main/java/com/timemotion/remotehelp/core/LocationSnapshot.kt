package com.timemotion.remotehelp.core

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.Locale

data class LocationSnapshot(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val capturedAt: Long
) {
    fun toSummary(): String {
        val accuracyText = accuracyMeters?.let { " ±${String.format(Locale.CHINA, "%.0f", it)}m" }.orEmpty()
        return "${provider.uppercase(Locale.CHINA)} ${formatCoord(latitude)}, ${formatCoord(longitude)}$accuracyText"
    }

    private fun formatCoord(value: Double): String = String.format(Locale.CHINA, "%.6f", value)
}

fun Context.readLatestLocationSnapshot(): LocationSnapshot? {
    if (!hasLocationPermission()) {
        return null
    }
    val locationManager = getSystemService(LocationManager::class.java) ?: return null
    val candidates = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )
    val bestLocation = candidates.asSequence()
        .mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }
                .getOrNull()
                ?.let { provider to it }
        }
        .maxByOrNull { it.second.time }
        ?: return null
    return bestLocation.second.toSnapshot(bestLocation.first)
}

private fun Context.hasLocationPermission(): Boolean {
    val fineGranted = ContextCompat.checkSelfPermission(
        this,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
        this,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fineGranted || coarseGranted
}

private fun Location.toSnapshot(provider: String): LocationSnapshot = LocationSnapshot(
    provider = provider,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    capturedAt = time.takeIf { it > 0L } ?: System.currentTimeMillis()
)
