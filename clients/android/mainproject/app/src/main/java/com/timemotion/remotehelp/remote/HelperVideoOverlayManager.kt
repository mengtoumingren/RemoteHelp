package com.timemotion.remotehelp.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.MainActivity
import com.timemotion.remotehelp.R
import android.graphics.drawable.GradientDrawable

class HelperVideoOverlayManager(private val appContext: Context) {
    private val lock = Any()

    @Volatile
    private var overlayView: View? = null

    fun hasPermission(): Boolean = Settings.canDrawOverlays(appContext)

    fun show(): Boolean {
        if (!hasPermission()) {
            AppLog.d(
                "HelperVideoOverlay",
                "拒绝显示悬浮窗 hasPermission=${hasPermission()}"
            )
            return false
        }
        synchronized(lock) {
            return runCatching {
                AppLog.d(
                    "HelperVideoOverlay",
                    "show overlay exists=${overlayView != null}"
                )
                if (overlayView == null) {
                    addOverlay()
                }
            }.onFailure {
                AppLog.logThrowable("HelperVideoOverlay", it, "创建或刷新悬浮窗失败")
            }.isSuccess
        }
    }

    fun hide() {
        synchronized(lock) {
            removeOverlay()
        }
    }

    private fun addOverlay() {
        val windowManager = appContext.getSystemService(WindowManager::class.java)
        val root = FrameLayout(appContext).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { openApp() }
            isClickable = true
        }
        val bubble = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xCC111827.toInt())
                setStroke(dp(1), 0x22FFFFFF)
            }
            setOnClickListener { openApp() }
            isClickable = true
        }
        val logo = ImageView(appContext).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val label = TextView(appContext).apply {
            text = "协助中"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
        }
        bubble.addView(
            logo,
            LinearLayout.LayoutParams(dp(34), dp(34))
        )
        bubble.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
        )
        root.addView(
            bubble,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        )
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(6)
            y = dp(6)
        }
        windowManager.addView(root, params)
        overlayView = root
        AppLog.d("HelperVideoOverlay", "addOverlay completed")
    }

    private fun removeOverlay() {
        val root = overlayView ?: return
        AppLog.d("HelperVideoOverlay", "removeOverlay root=${root.javaClass.simpleName}")
        runCatching {
            appContext.getSystemService(WindowManager::class.java)?.removeViewImmediate(root)
        }.onFailure {
            AppLog.logThrowable("HelperVideoOverlay", it, "移除悬浮窗失败")
        }
        overlayView = null
    }

    private fun openApp() {
        runCatching {
            val intent = appContext.packageManager
                .getLaunchIntentForPackage(appContext.packageName)
                ?.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                ?: Intent(appContext, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            appContext.startActivity(intent)
        }.onFailure {
            AppLog.logThrowable("HelperVideoOverlay", it, "点击悬浮窗打开应用失败")
        }
    }

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
