package com.timemotion.remotehelp.ui.remote

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import org.webrtc.SurfaceViewRenderer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.SecurityEvidenceRecord
import com.timemotion.remotehelp.core.SecurityEvidenceStore
import com.timemotion.remotehelp.core.findActivity
import com.timemotion.remotehelp.remote.RemoteControlUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
fun SecurityEvidenceCaptureHost(
    enabled: Boolean,
    sessionId: String?,
    helperName: String,
    elderName: String,
    callStatus: String,
    remoteState: RemoteControlUiState,
    locationPermissionGranted: Boolean,
    helperLocationSummary: String?,
    helperLocationUpdatedAt: Long?,
    helperVideoRenderer: SurfaceViewRenderer?,
    onCaptureSaved: (SecurityEvidenceRecord) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val store = remember(context) { SecurityEvidenceStore(context.applicationContext) }
    val latestSessionId by rememberUpdatedState(sessionId)
    val latestHelperName by rememberUpdatedState(helperName)
    val latestElderName by rememberUpdatedState(elderName)
    val latestCallStatus by rememberUpdatedState(callStatus)
    val latestRemoteState by rememberUpdatedState(remoteState)
    val latestLocationPermissionGranted by rememberUpdatedState(locationPermissionGranted)
    val latestHelperLocationSummary by rememberUpdatedState(helperLocationSummary)
    val latestHelperLocationUpdatedAt by rememberUpdatedState(helperLocationUpdatedAt)
    val latestHelperVideoRenderer by rememberUpdatedState(helperVideoRenderer)
    val latestOnCaptureSaved by rememberUpdatedState(onCaptureSaved)

    LaunchedEffect(enabled, sessionId) {
        if (!enabled || latestSessionId.isNullOrBlank()) {
            return@LaunchedEffect
        }
        delay(INITIAL_CAPTURE_DELAY_MS)
        while (isActive && enabled && !latestSessionId.isNullOrBlank()) {
            runCatching {
                captureAndStoreEvidence(
                    context = context,
                    view = view,
                    store = store,
                    sessionId = latestSessionId.orEmpty(),
                    helperName = latestHelperName,
                    elderName = latestElderName,
                    callStatus = latestCallStatus,
                    remoteState = latestRemoteState,
                    locationPermissionGranted = latestLocationPermissionGranted,
                    helperLocationSummary = latestHelperLocationSummary,
                    helperLocationUpdatedAt = latestHelperLocationUpdatedAt,
                    helperVideoRenderer = latestHelperVideoRenderer
                ).also { record ->
                    latestOnCaptureSaved(record)
                }
            }.onFailure {
                AppLog.logThrowable("SecurityEvidenceCapture", it, "定时证据采集失败")
            }
            delay(CAPTURE_INTERVAL_MS)
        }
    }
}

private suspend fun captureAndStoreEvidence(
    context: android.content.Context,
    view: View,
    store: SecurityEvidenceStore,
    sessionId: String,
    helperName: String,
    elderName: String,
    callStatus: String,
    remoteState: RemoteControlUiState,
    locationPermissionGranted: Boolean,
    helperLocationSummary: String?,
    helperLocationUpdatedAt: Long?,
    helperVideoRenderer: SurfaceViewRenderer?
): SecurityEvidenceRecord {
    val activity = context.findActivity() ?: throw IllegalStateException("未找到 Activity，无法采集截图")
    val width = view.width.takeIf { it > 0 } ?: throw IllegalStateException("当前窗口宽度无效")
    val height = view.height.takeIf { it > 0 } ?: throw IllegalStateException("当前窗口高度无效")
    val helperRenderer = helperVideoRenderer ?: throw IllegalStateException("未找到协助方视频视图，无法采集证据")
    val helperBitmap = captureRendererBitmap(helperRenderer) ?: throw IllegalStateException("协助方视频截图失败")
    val screenBitmap = captureWindowBitmap(activity, width, height) ?: throw IllegalStateException("窗口截图失败")
    val timestamp = System.currentTimeMillis()
    val record = SecurityEvidenceRecord(
        sessionId = sessionId,
        helperName = helperName,
        elderName = elderName,
        capturedAt = timestamp,
        callStatus = callStatus,
        remoteStatus = remoteState.status,
        targetStatus = remoteState.targetStatus.message,
        locationSummary = helperLocationSummary,
        locationPermissionGranted = locationPermissionGranted,
        helperLocationUpdatedAt = helperLocationUpdatedAt,
        helperCameraFileName = "helper_camera_${timestamp}.jpg",
        screenScreenshotFileName = "${timestamp}.jpg"
    )
    val persistedRecord = try {
        withContext(Dispatchers.IO) {
            store.saveSnapshot(record, helperBitmap, screenBitmap)
        }
    } finally {
        if (!helperBitmap.isRecycled) {
            helperBitmap.recycle()
        }
        if (!screenBitmap.isRecycled) {
            screenBitmap.recycle()
        }
    }
    return persistedRecord
}

private suspend fun captureRendererBitmap(renderer: SurfaceViewRenderer): Bitmap? {
    val width = renderer.width.takeIf { it > 0 } ?: return null
    val height = renderer.height.takeIf { it > 0 } ?: return null
    val surface = renderer.holder.surface
    if (!surface.isValid) {
        return null
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        PixelCopy.request(surface, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                continuation.resume(bitmap)
            } else {
                bitmap.recycle()
                continuation.resume(null)
            }
        }, handler)
        continuation.invokeOnCancellation {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}

private suspend fun captureWindowBitmap(activity: Activity, width: Int, height: Int): Bitmap? {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        PixelCopy.request(activity.window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                continuation.resume(bitmap)
            } else {
                bitmap.recycle()
                continuation.resume(null)
            }
        }, handler)
        continuation.invokeOnCancellation {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}

private const val INITIAL_CAPTURE_DELAY_MS = 3_000L
private const val CAPTURE_INTERVAL_MS = 10_000L
