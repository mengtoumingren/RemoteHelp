package com.timemotion.remotehelp.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.R

class ScreenCaptureForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            when (intent?.action) {
                ACTION_START -> {
                    notificationText = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT) ?: notificationText
                    startAsForeground()
                    statusListener?.invoke(true)
                    messageListener?.invoke("前台屏幕共享服务已启动")
                }

                ACTION_UPDATE -> {
                    notificationText = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT) ?: notificationText
                    startAsForeground()
                }

                ACTION_STOP -> stopSelf()
            }
        }.onFailure {
            AppLog.logThrowable("ScreenCaptureService", it, "屏幕共享前台服务启动异常")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching {
            statusListener?.invoke(false)
            super.onDestroy()
        }.onFailure {
            AppLog.logThrowable("ScreenCaptureService", it, "屏幕共享前台服务销毁异常")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Share",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationText)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_START = "screen_share_start"
        private const val ACTION_UPDATE = "screen_share_update"
        private const val ACTION_STOP = "screen_share_stop"
        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIFICATION_ID = 1201
        private const val EXTRA_NOTIFICATION_TEXT = "notification_text"

        @Volatile
        var statusListener: ((Boolean) -> Unit)? = null

        @Volatile
        var messageListener: ((String) -> Unit)? = null

        @Volatile
        private var notificationText: String = "远程协助屏幕共享进行中"

        fun start(context: Context, notificationText: String = "远程协助屏幕共享进行中") {
            runCatching {
                val intent = Intent(context, ScreenCaptureForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_NOTIFICATION_TEXT, notificationText)
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                AppLog.logThrowable("ScreenCaptureService", it, "启动前台服务失败")
            }
        }

        fun updateNotification(context: Context, notificationText: String) {
            runCatching {
                val intent = Intent(context, ScreenCaptureForegroundService::class.java)
                    .setAction(ACTION_UPDATE)
                    .putExtra(EXTRA_NOTIFICATION_TEXT, notificationText)
                context.startService(intent)
            }.onFailure {
                AppLog.logThrowable("ScreenCaptureService", it, "更新前台通知失败")
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, ScreenCaptureForegroundService::class.java)
                    .setAction(ACTION_STOP)
                context.startService(intent)
            }.onFailure {
                AppLog.logThrowable("ScreenCaptureService", it, "停止前台服务失败")
            }
        }
    }
}
