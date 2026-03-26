package com.timemotion.remotehelp.ui.remote

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.SecurityEvidenceRecord
import com.timemotion.remotehelp.core.SecurityEvidenceStore
import com.timemotion.remotehelp.remote.RemoteControlUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
    captureHelperCameraBitmap: suspend () -> Bitmap?,
    captureAssistScreenBitmap: suspend () -> Bitmap?,
    onCaptureSaved: (SecurityEvidenceRecord) -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { SecurityEvidenceStore(context.applicationContext) }
    val latestSessionId by rememberUpdatedState(sessionId)
    val latestHelperName by rememberUpdatedState(helperName)
    val latestElderName by rememberUpdatedState(elderName)
    val latestCallStatus by rememberUpdatedState(callStatus)
    val latestRemoteState by rememberUpdatedState(remoteState)
    val latestLocationPermissionGranted by rememberUpdatedState(locationPermissionGranted)
    val latestHelperLocationSummary by rememberUpdatedState(helperLocationSummary)
    val latestHelperLocationUpdatedAt by rememberUpdatedState(helperLocationUpdatedAt)
    val latestCaptureHelperCameraBitmap by rememberUpdatedState(captureHelperCameraBitmap)
    val latestCaptureAssistScreenBitmap by rememberUpdatedState(captureAssistScreenBitmap)
    val latestOnCaptureSaved by rememberUpdatedState(onCaptureSaved)

    LaunchedEffect(enabled, sessionId) {
        if (!enabled || latestSessionId.isNullOrBlank()) {
            return@LaunchedEffect
        }
        delay(FIRST_CAPTURE_DELAY_MS)
        while (isActive && enabled && !latestSessionId.isNullOrBlank()) {
            runCatching {
                captureAndStoreEvidence(
                    store = store,
                    sessionId = latestSessionId.orEmpty(),
                    helperName = latestHelperName,
                    elderName = latestElderName,
                    callStatus = latestCallStatus,
                    remoteState = latestRemoteState,
                    locationPermissionGranted = latestLocationPermissionGranted,
                    helperLocationSummary = latestHelperLocationSummary,
                    helperLocationUpdatedAt = latestHelperLocationUpdatedAt,
                    captureHelperCameraBitmap = latestCaptureHelperCameraBitmap,
                    captureAssistScreenBitmap = latestCaptureAssistScreenBitmap
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
    store: SecurityEvidenceStore,
    sessionId: String,
    helperName: String,
    elderName: String,
    callStatus: String,
    remoteState: RemoteControlUiState,
    locationPermissionGranted: Boolean,
    helperLocationSummary: String?,
    helperLocationUpdatedAt: Long?,
    captureHelperCameraBitmap: suspend () -> Bitmap?,
    captureAssistScreenBitmap: suspend () -> Bitmap?
): SecurityEvidenceRecord {
    val helperBitmap = captureHelperCameraBitmap() ?: throw IllegalStateException("协助方视频截图失败")
    val screenBitmap = captureAssistScreenBitmap() ?: throw IllegalStateException("屏幕采集截图失败")
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

private const val FIRST_CAPTURE_DELAY_MS = 0L
private const val CAPTURE_INTERVAL_MS = 5_000L
