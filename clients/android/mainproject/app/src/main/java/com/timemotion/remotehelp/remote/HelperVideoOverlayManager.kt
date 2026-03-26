package com.timemotion.remotehelp.remote

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.timemotion.remotehelp.core.AppLog
import org.webrtc.SurfaceViewRenderer

class HelperVideoOverlayManager(private val appContext: Context) {
    private val lock = Any()

    @Volatile
    private var overlayView: View? = null

    @Volatile
    private var overlaySurfaceView: SurfaceViewRenderer? = null

    @Volatile
    private var previousParent: ViewGroup? = null

    @Volatile
    private var previousLayoutParams: ViewGroup.LayoutParams? = null

    fun hasPermission(): Boolean = Settings.canDrawOverlays(appContext)

    fun show(surfaceView: SurfaceViewRenderer?): Boolean {
        if (!hasPermission() || surfaceView == null) {
            AppLog.d(
                "HelperVideoOverlay",
                "拒绝显示悬浮窗 hasPermission=${hasPermission()} renderer=${surfaceView != null}"
            )
            return false
        }
        synchronized(lock) {
            return runCatching {
                AppLog.d(
                    "HelperVideoOverlay",
                    "show overlay exists=${overlayView != null} sameSurface=${overlaySurfaceView === surfaceView}"
                )
                if (overlayView == null) {
                    addOverlay(surfaceView)
                } else {
                    updateOverlay(surfaceView)
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

    private fun addOverlay(surfaceView: SurfaceViewRenderer) {
        val windowManager = appContext.getSystemService(WindowManager::class.java)
        AppLog.d(
            "HelperVideoOverlay",
            "addOverlay parent=${surfaceView.parent?.javaClass?.simpleName}"
        )

        previousParent = surfaceView.parent as? ViewGroup
        previousLayoutParams = surfaceView.layoutParams
        previousParent?.removeView(surfaceView)

        val root = FrameLayout(appContext).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                dp(58),
                dp(88)
            )
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
        overlaySurfaceView = surfaceView
        AppLog.d("HelperVideoOverlay", "addOverlay completed")
    }

    private fun updateOverlay(surfaceView: SurfaceViewRenderer) {
        val currentSurface = overlaySurfaceView ?: run {
            removeOverlay()
            addOverlay(surfaceView)
            return
        }
        if (currentSurface !== surfaceView) {
            removeOverlay()
            addOverlay(surfaceView)
            return
        }
    }

    private fun removeOverlay() {
        val root = overlayView ?: return
        val surfaceView = overlaySurfaceView
        AppLog.d("HelperVideoOverlay", "removeOverlay root=${root.javaClass.simpleName}")
        runCatching {
            appContext.getSystemService(WindowManager::class.java)?.removeViewImmediate(root)
        }.onFailure {
            AppLog.logThrowable("HelperVideoOverlay", it, "移除悬浮窗失败")
        }
        surfaceView?.let { view ->
            runCatching {
                (view.parent as? ViewGroup)?.removeView(view)
                previousParent?.let { parent ->
                    val layoutParams = previousLayoutParams ?: ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    parent.addView(view, layoutParams)
                }
            }.onFailure {
                AppLog.logThrowable("HelperVideoOverlay", it, "恢复页面渲染容器失败")
            }
        }
        overlayView = null
        overlaySurfaceView = null
        previousParent = null
        previousLayoutParams = null
    }

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
