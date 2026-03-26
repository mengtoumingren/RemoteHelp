package com.timemotion.remotehelp.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

data class SecurityEvidenceSessionInfo(
    val sessionId: String,
    val recordCount: Int,
    val lastCapturedAt: Long?,
    val helperName: String,
    val elderName: String
)

data class SecurityEvidenceRecord(
    val sessionId: String,
    val helperName: String,
    val elderName: String,
    val capturedAt: Long,
    val callStatus: String,
    val remoteStatus: String,
    val targetStatus: String,
    val locationSummary: String?,
    val locationPermissionGranted: Boolean,
    val helperLocationUpdatedAt: Long?,
    val helperCameraFileName: String,
    val screenScreenshotFileName: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sessionId", sessionId)
        .put("helperName", helperName)
        .put("elderName", elderName)
        .put("capturedAt", capturedAt)
        .put("callStatus", callStatus)
        .put("remoteStatus", remoteStatus)
        .put("targetStatus", targetStatus)
        .put("locationSummary", locationSummary)
        .put("locationPermissionGranted", locationPermissionGranted)
        .put("helperLocationUpdatedAt", helperLocationUpdatedAt)
        .put("helperCameraFileName", helperCameraFileName)
        .put("screenScreenshotFileName", screenScreenshotFileName)

    companion object {
        fun fromJson(json: JSONObject): SecurityEvidenceRecord = SecurityEvidenceRecord(
            sessionId = json.optString("sessionId"),
            helperName = json.optString("helperName"),
            elderName = json.optString("elderName"),
            capturedAt = json.optLong("capturedAt"),
            callStatus = json.optString("callStatus"),
            remoteStatus = json.optString("remoteStatus"),
            targetStatus = json.optString("targetStatus"),
            locationSummary = json.optString("locationSummary").takeIf { it.isNotBlank() },
            locationPermissionGranted = json.optBoolean("locationPermissionGranted"),
            helperLocationUpdatedAt = json.optLong("helperLocationUpdatedAt").takeIf { it > 0L },
            helperCameraFileName = json.optString("helperCameraFileName"),
            screenScreenshotFileName = json.optString("screenScreenshotFileName")
        )
    }
}

class SecurityEvidenceStore(context: Context) {
    private val rootDir = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
        ROOT_DIR_NAME
    ).apply { mkdirs() }

    @Synchronized
    fun saveSnapshot(
        record: SecurityEvidenceRecord,
        helperCameraBitmap: Bitmap,
        screenBitmap: Bitmap
    ): SecurityEvidenceRecord {
        val sessionDir = File(rootDir, sanitize(record.sessionId)).apply { mkdirs() }
        val helperCameraDir = File(sessionDir, HELPER_CAMERA_DIR_NAME).apply { mkdirs() }
        val screenshotsDir = File(sessionDir, SCREENSHOT_DIR_NAME).apply { mkdirs() }
        val helperCameraFile = File(helperCameraDir, record.helperCameraFileName)
        val screenScreenshotFile = File(screenshotsDir, record.screenScreenshotFileName)
        FileOutputStream(helperCameraFile).use { output ->
            if (!helperCameraBitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, output)) {
                throw IllegalStateException("写入协助方摄像头截图失败")
            }
            output.flush()
        }
        FileOutputStream(screenScreenshotFile).use { output ->
            if (!screenBitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, output)) {
                throw IllegalStateException("写入当前屏幕截图失败")
            }
            output.flush()
        }
        val persisted = record.copy(
            helperCameraFileName = helperCameraFile.name,
            screenScreenshotFileName = screenScreenshotFile.name
        )
        appendManifest(sessionDir, persisted)
        return persisted
    }

    fun listSnapshots(sessionId: String): List<SecurityEvidenceRecord> {
        val manifest = manifestFile(sessionId)
        if (!manifest.exists()) {
            return emptyList()
        }
        return runCatching {
            manifest.readLines(StandardCharsets.UTF_8)
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        null
                    } else {
                        SecurityEvidenceRecord.fromJson(JSONObject(trimmed))
                    }
                }
        }.getOrDefault(emptyList())
    }

    fun listSessions(): List<SecurityEvidenceSessionInfo> {
        return runCatching {
            rootDir.mkdirs()
            rootDir.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.map { sessionDir ->
                    val sessionId = sessionDir.name
                    val records = listSnapshots(sessionId)
                    val latestRecord = records.maxByOrNull { it.capturedAt }
                    SecurityEvidenceSessionInfo(
                        sessionId = sessionId,
                        recordCount = records.size,
                        lastCapturedAt = latestRecord?.capturedAt ?: sessionDir.lastModified().takeIf { it > 0L },
                        helperName = latestRecord?.helperName.orEmpty(),
                        elderName = latestRecord?.elderName.orEmpty()
                    )
                }
                ?.sortedWith(
                    compareByDescending<SecurityEvidenceSessionInfo> { it.lastCapturedAt ?: 0L }
                        .thenByDescending { it.sessionId }
                )
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun resolveScreenScreenshotFile(sessionId: String, fileName: String): File {
        return File(File(File(rootDir, sanitize(sessionId)), SCREENSHOT_DIR_NAME), fileName)
    }

    fun resolveHelperCameraFile(sessionId: String, fileName: String): File {
        return File(File(File(rootDir, sanitize(sessionId)), HELPER_CAMERA_DIR_NAME), fileName)
    }

    private fun appendManifest(sessionDir: File, record: SecurityEvidenceRecord) {
        val manifest = File(sessionDir, MANIFEST_FILE_NAME)
        BufferedWriter(OutputStreamWriter(FileOutputStream(manifest, true), StandardCharsets.UTF_8)).use { writer ->
            writer.append(record.toJson().toString())
            writer.newLine()
        }
    }

    private fun manifestFile(sessionId: String): File = File(File(rootDir, sanitize(sessionId)), MANIFEST_FILE_NAME)

    private fun sanitize(value: String): String = value.replace(UNSAFE_FILE_CHARS, "_")

    companion object {
        private const val ROOT_DIR_NAME = "RemoteHelp/evidence"
        private const val HELPER_CAMERA_DIR_NAME = "helper_camera"
        private const val SCREENSHOT_DIR_NAME = "screenshots"
        private const val MANIFEST_FILE_NAME = "manifest.jsonl"
        private const val SCREENSHOT_QUALITY = 92
        private val UNSAFE_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")
    }
}
