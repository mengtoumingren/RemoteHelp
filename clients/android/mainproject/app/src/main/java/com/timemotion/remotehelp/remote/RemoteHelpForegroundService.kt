package com.timemotion.remotehelp.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.timemotion.remotehelp.MainActivity
import com.timemotion.remotehelp.R
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.AppScreen
import com.timemotion.remotehelp.core.SecurityEvidenceRecord
import com.timemotion.remotehelp.core.SecurityEvidenceStore
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.readRealtimeLocationSnapshot
import com.timemotion.remotehelp.webrtc.CallUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemoteHelpForegroundService : Service() {
    inner class LocalBinder : Binder() {
        val service: RemoteHelpForegroundService
            get() = this@RemoteHelpForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentNotificationText = "远程协助后台服务运行中"
    private lateinit var coordinatorInternal: RemoteHelpCoordinator
    private lateinit var helperOverlayManager: HelperVideoOverlayManager
    private lateinit var guideOverlayManager: GuideOverlayManager
    private lateinit var evidenceStore: SecurityEvidenceStore
    @Volatile
    private var helperOverlayRequested = false
    @Volatile
    private var helperOverlayRequestedSessionId: String? = null
    @Volatile
    private var helperOverlayAutoShownSessionId: String? = null
    private var helperOverlayRefreshJob: Job? = null
    private var helperOverlayAutoShowJob: Job? = null
    private var helperOverlayPreviewJob: Job? = null
    @Volatile
    private var helperOverlayPreviewSessionId: String? = null
    private var evidenceCaptureJob: Job? = null
    @Volatile
    private var evidenceCaptureSessionId: String? = null

    val coordinator: RemoteHelpCoordinator
        get() = coordinatorInternal

    override fun onCreate() {
        super.onCreate()
        runCatching {
            AppLog.install(applicationContext)
            createNotificationChannel()
            startAsForeground(currentNotificationText)
            coordinatorInternal = RemoteHelpCoordinator(applicationContext)
            evidenceStore = SecurityEvidenceStore(applicationContext)
            guideOverlayManager = GuideOverlayManager(applicationContext)
            helperOverlayManager = HelperVideoOverlayManager(
                appContext = applicationContext,
                onHangUpClick = {
                runCatching {
                    coordinatorInternal.endCurrentSession("已挂断远程协助")
                }.onFailure {
                    AppLog.logThrowable("RemoteHelpFgService", it, "悬浮窗挂断失败")
                }
                requestHelperOverlayHide()
                },
                onToggleSpeakerClick = {
                runCatching {
                    coordinatorInternal.callController.toggleSpeakerOutput()
                }.onFailure {
                    AppLog.logThrowable("RemoteHelpFgService", it, "悬浮窗切换扬声器失败")
                }
                }
            )
            coordinatorInternal.remoteController.onGuideCommandRequested = { command ->
                handleGuideCommand(command)
            }
            observeCoordinatorState()
        }.onFailure {
            AppLog.logThrowable("RemoteHelpFgService", it, "前台服务启动失败")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                ACTION_REFRESH -> {
                    if (this::coordinatorInternal.isInitialized) {
                        refreshNotification()
                    }
                }
                else -> {
                    if (!this::coordinatorInternal.isInitialized) {
                        startAsForeground(currentNotificationText)
                    }
                }
            }
        }.onFailure {
            AppLog.logThrowable("RemoteHelpFgService", it, "处理前台服务指令失败")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching {
            serviceScope.cancel()
            helperOverlayRefreshJob?.cancel()
            helperOverlayAutoShowJob?.cancel()
            helperOverlayPreviewJob?.cancel()
            evidenceCaptureJob?.cancel()
            helperOverlayRequested = false
            helperOverlayRequestedSessionId = null
            helperOverlayAutoShownSessionId = null
            helperOverlayPreviewSessionId = null
            evidenceCaptureSessionId = null
            if (this::helperOverlayManager.isInitialized) {
                runCatching { helperOverlayManager.hide() }
            }
            if (this::guideOverlayManager.isInitialized) {
                runCatching { guideOverlayManager.hide() }
            }
            if (this::coordinatorInternal.isInitialized) {
                coordinatorInternal.remoteController.onGuideCommandRequested = null
                coordinatorInternal.release()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            super.onDestroy()
        }.onFailure {
            AppLog.logThrowable("RemoteHelpFgService", it, "前台服务销毁失败")
            super.onDestroy()
        }
    }

    private fun observeCoordinatorState() {
        serviceScope.launch {
            combine(
                coordinatorInternal.uiState,
                coordinatorInternal.callController.uiState,
                coordinatorInternal.remoteController.uiState
            ) { uiState, callState, remoteState ->
                Triple(uiState, callState, remoteState)
            }.collectLatest { (uiState, callState, remoteState) ->
                currentNotificationText = buildNotificationText(uiState)
                refreshNotification()
                helperOverlayManager.updateSpeakerState(callState.isSpeakerOn)
                scheduleHelperOverlayRefresh(uiState, callState)
                refreshGuideOverlay(uiState, remoteState)
                scheduleEvidenceCapture(uiState, callState, remoteState)
            }
        }
    }

    fun requestHelperOverlayShow() {
        val sessionId = coordinatorInternal.uiState.value.activeSession?.requestId
        if (sessionId == null) {
            AppLog.d("RemoteHelpFgService", "请求显示协助者悬浮窗失败：当前没有会话")
            return
        }
        helperOverlayAutoShowJob?.cancel()
        helperOverlayRequested = true
        helperOverlayRequestedSessionId = sessionId
        helperOverlayAutoShownSessionId = sessionId
        AppLog.d("RemoteHelpFgService", "请求显示协助者悬浮窗")
        scheduleHelperOverlayRefresh(
            coordinatorInternal.uiState.value,
            coordinatorInternal.callController.uiState.value
        )
    }

    fun requestHelperOverlayHide() {
        helperOverlayAutoShowJob?.cancel()
        helperOverlayRequested = false
        helperOverlayRequestedSessionId = null
        AppLog.d("RemoteHelpFgService", "请求隐藏协助者悬浮窗")
        helperOverlayRefreshJob?.cancel()
        helperOverlayPreviewJob?.cancel()
        helperOverlayPreviewSessionId = null
        evidenceCaptureJob?.cancel()
        evidenceCaptureSessionId = null
        serviceScope.launch {
            runCatching { helperOverlayManager.hide() }
                .onFailure {
                    AppLog.logThrowable("RemoteHelpFgService", it, "隐藏协助者悬浮窗失败")
                }
            helperOverlayManager.updatePreview(null)
        }
    }

    private fun scheduleHelperOverlayRefresh(uiState: RemoteHelpUiState, callState: CallUiState) {
        helperOverlayRefreshJob?.cancel()
        helperOverlayRefreshJob = serviceScope.launch {
            yield()
            refreshHelperOverlay(uiState, callState)
        }
        scheduleHelperOverlayAutoShow(uiState)
    }

    private fun refreshHelperOverlay(uiState: RemoteHelpUiState, callState: CallUiState) {
        val currentSessionId = uiState.activeSession?.requestId
        if (helperOverlayRequestedSessionId != null && helperOverlayRequestedSessionId != currentSessionId) {
            helperOverlayRequested = false
            helperOverlayRequestedSessionId = null
        }
        if (helperOverlayAutoShownSessionId != null && helperOverlayAutoShownSessionId != currentSessionId) {
            helperOverlayAutoShownSessionId = null
        }
        AppLog.d(
            "RemoteHelpFgService",
            "刷新悬浮窗 requested=$helperOverlayRequested screen=${uiState.currentScreen} side=${uiState.side} inRoom=${callState.isInRoom}"
        )
        if (
            !helperOverlayRequested ||
            uiState.side != DeviceSide.ELDER ||
            uiState.currentScreen != AppScreen.ASSIST
        ) {
            helperOverlayPreviewJob?.cancel()
            helperOverlayPreviewJob = null
            helperOverlayPreviewSessionId = null
            runCatching { helperOverlayManager.hide() }
                .onFailure {
                    AppLog.logThrowable("RemoteHelpFgService", it, "隐藏协助者悬浮窗失败")
                }
            helperOverlayManager.updatePreview(null)
            return
        }
        runCatching {
            val shown = helperOverlayManager.show()
            AppLog.d("RemoteHelpFgService", "显示协助者悬浮窗结果=$shown")
            if (shown) {
                scheduleHelperOverlayPreview(uiState)
            }
        }.onFailure {
            AppLog.logThrowable("RemoteHelpFgService", it, "显示协助者悬浮窗失败")
        }
    }

    private fun scheduleHelperOverlayPreview(uiState: RemoteHelpUiState) {
        val sessionId = uiState.activeSession?.requestId ?: run {
            helperOverlayPreviewJob?.cancel()
            helperOverlayPreviewJob = null
            helperOverlayPreviewSessionId = null
            helperOverlayManager.updatePreview(null)
            return
        }
        if (
            helperOverlayPreviewJob?.isActive == true &&
            helperOverlayPreviewSessionId == sessionId
        ) {
            return
        }
        helperOverlayPreviewJob?.cancel()
        helperOverlayPreviewSessionId = sessionId
        helperOverlayPreviewJob = serviceScope.launch {
            while (isActive) {
                val latestUiState = coordinatorInternal.uiState.value
                val latestSessionId = latestUiState.activeSession?.requestId
                val shouldContinue =
                    helperOverlayRequested &&
                        latestUiState.side == DeviceSide.ELDER &&
                        latestUiState.currentScreen == AppScreen.ASSIST &&
                        latestSessionId == sessionId
                if (!shouldContinue) {
                    break
                }
                val bitmap = runCatching {
                    coordinatorInternal.callController.captureRemoteVideoBitmap()
                }.onFailure {
                    if (it !is CancellationException) {
                        AppLog.logThrowable("RemoteHelpFgService", it, "刷新协助者悬浮窗截图失败")
                    }
                }.getOrNull()
                if (bitmap != null) {
                    helperOverlayManager.updatePreview(bitmap)
                }
                delay(OVERLAY_PREVIEW_INTERVAL_MS)
            }
            helperOverlayManager.updatePreview(null)
        }
    }

    private fun scheduleEvidenceCapture(
        uiState: RemoteHelpUiState,
        callState: CallUiState,
        remoteState: RemoteControlUiState
    ) {
        val sessionId = uiState.activeSession?.requestId
        val shouldCapture =
            uiState.side == DeviceSide.ELDER &&
                uiState.activeSession?.verificationAcceptedAt != null &&
                uiState.currentScreen in setOf(AppScreen.VERIFICATION, AppScreen.ASSIST)
        if (!shouldCapture || sessionId == null) {
            evidenceCaptureJob?.cancel()
            evidenceCaptureJob = null
            evidenceCaptureSessionId = null
            return
        }
        if (
            evidenceCaptureJob?.isActive == true &&
            evidenceCaptureSessionId == sessionId
        ) {
            return
        }
        evidenceCaptureJob?.cancel()
        evidenceCaptureSessionId = sessionId
        evidenceCaptureJob = serviceScope.launch {
            while (isActive) {
                val latestUiState = coordinatorInternal.uiState.value
                val latestCallState = coordinatorInternal.callController.uiState.value
                val latestRemoteState = coordinatorInternal.remoteController.uiState.value
                val latestSessionId = latestUiState.activeSession?.requestId
                val latestShouldCapture =
                    latestUiState.side == DeviceSide.ELDER &&
                        latestUiState.activeSession?.verificationAcceptedAt != null &&
                        latestUiState.currentScreen in setOf(AppScreen.VERIFICATION, AppScreen.ASSIST) &&
                        latestSessionId == sessionId
                if (!latestShouldCapture) {
                    break
                }
                runCatching {
                    captureEvidenceSnapshot(
                        uiState = latestUiState,
                        callState = latestCallState,
                        remoteState = latestRemoteState
                    )
                }.onFailure {
                    if (it !is CancellationException) {
                        AppLog.logThrowable("RemoteHelpFgService", it, "定时证据采集失败")
                    }
                }
                delay(EVIDENCE_CAPTURE_INTERVAL_MS)
            }
        }
    }

    private fun refreshGuideOverlay(
        uiState: RemoteHelpUiState,
        remoteState: RemoteControlUiState
    ) {
        if (!this::guideOverlayManager.isInitialized) {
            return
        }
        if (shouldShowGuideOverlay(uiState, remoteState)) {
            runCatching { guideOverlayManager.show() }
                .onFailure {
                    AppLog.logThrowable("RemoteHelpFgService", it, "显示远程指引遮罩失败")
                }
        } else {
            runCatching { guideOverlayManager.hide() }
                .onFailure {
                    AppLog.logThrowable("RemoteHelpFgService", it, "隐藏远程指引遮罩失败")
                }
        }
    }

    private fun handleGuideCommand(command: RemoteCommand): Boolean {
        val uiState = coordinatorInternal.uiState.value
        val remoteState = coordinatorInternal.remoteController.uiState.value
        if (!shouldShowGuideOverlay(uiState, remoteState)) {
            return false
        }
        return runCatching {
            guideOverlayManager.show() && run {
                guideOverlayManager.renderCommand(command, remoteState.targetStatus)
                true
            }
        }.onFailure {
            AppLog.logThrowable("RemoteHelpFgService", it, "显示远程指引失败")
        }.getOrDefault(false)
    }

    private fun shouldShowGuideOverlay(
        uiState: RemoteHelpUiState,
        remoteState: RemoteControlUiState
    ): Boolean {
        return uiState.side == DeviceSide.ELDER &&
            uiState.currentScreen == AppScreen.ASSIST &&
            remoteState.targetStatus.captureActive &&
            !remoteState.targetStatus.accessibilityEnabled &&
            this::guideOverlayManager.isInitialized &&
            guideOverlayManager.hasPermission()
    }

    private suspend fun captureEvidenceSnapshot(
        uiState: RemoteHelpUiState,
        callState: CallUiState,
        remoteState: RemoteControlUiState
    ) {
        val sessionId = uiState.activeSession?.requestId ?: return
        val locationSnapshot = runCatching {
            applicationContext.readRealtimeLocationSnapshot()
        }.getOrElse {
            AppLog.logThrowable("RemoteHelpFgService", it, "获取实时定位失败")
            null
        }
        val helperBitmap = coordinatorInternal.callController.captureRemoteVideoBitmap()
            ?: throw IllegalStateException("协助方视频截图失败")
        val screenBitmap = coordinatorInternal.callController.captureAssistScreenBitmap()
            ?: throw IllegalStateException("屏幕采集截图失败")
        val timestamp = System.currentTimeMillis()
        val record = SecurityEvidenceRecord(
            sessionId = sessionId,
            helperName = uiState.activeSession?.helperName ?: uiState.helperName,
            elderName = uiState.activeSession?.elderName ?: uiState.elderName,
            capturedAt = timestamp,
            callStatus = callState.status,
            remoteStatus = remoteState.status,
            targetStatus = remoteState.targetStatus.message,
            locationSummary = locationSnapshot?.toSummary(),
            locationPermissionGranted = uiState.helperLocationPermissionGranted,
            helperLocationUpdatedAt = locationSnapshot?.capturedAt,
            helperCameraFileName = "helper_camera_${timestamp}.jpg",
            screenScreenshotFileName = "${timestamp}.jpg"
        )
        try {
            withContext(Dispatchers.IO) {
                evidenceStore.saveSnapshot(record, helperBitmap, screenBitmap)
            }
        } finally {
            if (!helperBitmap.isRecycled) {
                helperBitmap.recycle()
            }
            if (!screenBitmap.isRecycled) {
                screenBitmap.recycle()
            }
        }
    }

    private fun scheduleHelperOverlayAutoShow(uiState: RemoteHelpUiState) {
        val sessionId = uiState.activeSession?.requestId
        if (sessionId == null) {
            helperOverlayAutoShowJob?.cancel()
            return
        }
        if (uiState.side != DeviceSide.ELDER || uiState.currentScreen != AppScreen.ASSIST) {
            helperOverlayAutoShowJob?.cancel()
            return
        }
        if (helperOverlayRequested || helperOverlayAutoShownSessionId == sessionId || !helperOverlayManager.hasPermission()) {
            return
        }
        helperOverlayAutoShowJob?.cancel()
        helperOverlayAutoShowJob = serviceScope.launch {
            delay(AUTO_SHOW_DELAY_MS)
            val latestUiState = coordinatorInternal.uiState.value
            val latestSessionId = latestUiState.activeSession?.requestId
            if (
                latestUiState.side == DeviceSide.ELDER &&
                latestUiState.currentScreen == AppScreen.ASSIST &&
                latestSessionId == sessionId &&
                helperOverlayManager.hasPermission() &&
                !helperOverlayRequested
            ) {
                helperOverlayAutoShownSessionId = sessionId
                helperOverlayRequested = true
                helperOverlayRequestedSessionId = sessionId
                refreshHelperOverlay(latestUiState, coordinatorInternal.callController.uiState.value)
            }
        }
    }

    private fun buildNotificationText(state: RemoteHelpUiState): String {
        val session = state.activeSession
        return when {
            session == null -> "远程协助后台服务运行中"
            state.currentScreen == AppScreen.VERIFICATION -> "正在进行视频认证"
            state.currentScreen == AppScreen.ASSIST -> "正在进行远程协助"
            else -> "远程协助会话已保持"
        }
    }

    private fun refreshNotification() {
        if (!this::coordinatorInternal.isInitialized) {
            return
        }
        val notification = buildNotification(currentNotificationText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            pendingIntentFlags()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RemoteHelp Background",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "远程协助后台服务"
        }
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    companion object {
        private const val CHANNEL_ID = "remote_help_background"
        private const val NOTIFICATION_ID = 1202
        private const val ACTION_STOP = "remote_help_background_stop"
        private const val ACTION_REFRESH = "remote_help_background_refresh"
        private const val AUTO_SHOW_DELAY_MS = 1_200L
        private const val OVERLAY_PREVIEW_INTERVAL_MS = 100L
        private const val EVIDENCE_CAPTURE_INTERVAL_MS = 5_000L
        private const val OVERLAY_PREVIEW_SIZE_PX = 96

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteHelpForegroundService::class.java)
                )
            }.onFailure {
                AppLog.logThrowable("RemoteHelpFgService", it, "启动前台服务失败")
            }
        }

        fun refresh(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteHelpForegroundService::class.java)
                        .setAction(ACTION_REFRESH)
                )
            }.onFailure {
                AppLog.logThrowable("RemoteHelpFgService", it, "刷新前台服务失败")
            }
        }

        fun stop(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteHelpForegroundService::class.java)
                        .setAction(ACTION_STOP)
                )
            }.onFailure {
                AppLog.logThrowable("RemoteHelpFgService", it, "停止前台服务失败")
            }
        }
    }
}
