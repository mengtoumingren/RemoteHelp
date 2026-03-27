package com.timemotion.remotehelp.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

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

suspend fun Context.readRealtimeLocationSnapshot(timeoutMillis: Long = 3_000L): LocationSnapshot? {
    if (!hasLocationPermission()) {
        return null
    }
    return withTimeoutOrNull(timeoutMillis) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readFreshLocationSnapshotApi30Plus()
        } else {
            readFreshLocationSnapshotLegacy()
        }
    }
}

private suspend fun Context.readFreshLocationSnapshotApi30Plus(): LocationSnapshot? {
    val appContext = this
    return suspendCancellableCoroutine { continuation ->
        val locationManager = appContext.getSystemService(LocationManager::class.java)
        if (locationManager == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }

        if (providers.isEmpty()) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val cancellationSignal = CancellationSignal()
        val remaining = AtomicInteger(providers.size)
        val completed = AtomicBoolean(false)

        continuation.invokeOnCancellation {
            cancellationSignal.cancel()
        }

        providers.forEach { provider ->
            runCatching {
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(appContext),
                ) { location ->
                    if (location != null && completed.compareAndSet(false, true)) {
                        cancellationSignal.cancel()
                        if (continuation.isActive) {
                            continuation.resume(location.toSnapshot(location.provider ?: provider))
                        }
                    } else if (remaining.decrementAndGet() == 0 && completed.compareAndSet(false, true)) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            }.onFailure {
                if (remaining.decrementAndGet() == 0 && completed.compareAndSet(false, true)) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }
}

private suspend fun Context.readFreshLocationSnapshotLegacy(): LocationSnapshot? {
    val appContext = this
    return suspendCancellableCoroutine { continuation ->
        val locationManager = appContext.getSystemService(LocationManager::class.java)
        if (locationManager == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }

        if (providers.isEmpty()) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        var requestedAny = false
        lateinit var listener: LocationListener

        fun cleanup() {
            runCatching { locationManager.removeUpdates(listener) }
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!continuation.isActive) {
                    return
                }
                cleanup()
                continuation.resume(location.toSnapshot(location.provider ?: providers.first()))
            }
        }

        continuation.invokeOnCancellation { cleanup() }

        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                requestedAny = true
            }
        }

        if (!requestedAny && continuation.isActive) {
            continuation.resume(null)
        }
    }
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
