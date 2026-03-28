package com.timemotion.remotehelp.remote

import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.MainActivity
import com.timemotion.remotehelp.R
import android.graphics.drawable.GradientDrawable
import kotlin.math.abs

class HelperVideoOverlayManager(
    private val appContext: Context,
    private val onHangUpClick: () -> Unit,
    private val onToggleSpeakerClick: () -> Unit
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop

    @Volatile
    private var overlayView: View? = null
    @Volatile
    private var previewImageView: ImageView? = null
    @Volatile
    private var speakerButton: Button? = null
    @Volatile
    private var speakerEnabled: Boolean = true
    @Volatile
    private var currentPreviewBitmap: Bitmap? = null

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

    fun updateSpeakerState(enabled: Boolean) {
        speakerEnabled = enabled
        val button = speakerButton ?: return
        val textStr = if (enabled) "外放" else "听筒"
        val apply = Runnable {
            button.text = textStr
            button.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (enabled) 0xCC1F2937.toInt() else 0xCC374151.toInt())
                setStroke(dp(1), if (enabled) 0x33FFFFFF else 0x22FFFFFF)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run()
        } else {
            mainHandler.post(apply)
        }
    }

    fun updatePreview(bitmap: Bitmap?) {
        val imageView = previewImageView ?: run {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            return
        }
        val applyBitmap = Runnable {
            val previousBitmap = currentPreviewBitmap
            currentPreviewBitmap = bitmap
            if (bitmap == null) {
                imageView.setImageResource(R.mipmap.ic_launcher_round)
            } else {
                imageView.setImageBitmap(bitmap)
            }
            if (previousBitmap != null && previousBitmap !== bitmap && !previousBitmap.isRecycled) {
                previousBitmap.recycle()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyBitmap.run()
        } else {
            mainHandler.post(applyBitmap)
        }
    }

    private fun addOverlay() {
        val windowManager = appContext.getSystemService(WindowManager::class.java)
        val root = DragOverlayLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clipToOutline = true
            }
            isClickable = true
            minimumWidth = dp(76)
            minimumHeight = dp(184)
            setOnTapListener { openApp() }
            setOnDragListener { newX, newY, params ->
                params.x = newX.coerceIn(0, appContext.resources.displayMetrics.widthPixels)
                params.y = newY.coerceIn(0, appContext.resources.displayMetrics.heightPixels)
                runCatching {
                    windowManager.updateViewLayout(this@apply, params)
                }
            }
        }
        val bgContainer = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xCC1A212A.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clipToOutline = true
                elevation = dp(8).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val previewContainer = FrameLayout(appContext).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF2A3644.toInt())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clipToOutline = true
            }
        }
        val preview = ImageView(appContext).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = false
        }
        previewContainer.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        previewImageView = preview
        bgContainer.addView(
            previewContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(100)
            ).apply {
                bottomMargin = dp(4)
            }
        )
        val speakerToggle = Button(appContext).apply {
            text = if (speakerEnabled) "外放" else "听筒"
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minWidth = 0
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (speakerEnabled) 0x66436182.toInt() else 0x4426364A.toInt())
            }
            setOnClickListener { onToggleSpeakerClick() }
        }
        speakerButton = speakerToggle
        bgContainer.addView(
            speakerToggle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
            ).apply {
                bottomMargin = dp(4)
            }
        )
        val hangUpButton = Button(appContext).apply {
            text = "挂断"
            isAllCaps = false
            textSize = 11f
            minHeight = 0
            minWidth = 0
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFD32F2F.toInt())
            }
            setOnClickListener { onHangUpClick() }
        }
        bgContainer.addView(
            hangUpButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
            )
        )
        root.addView(
            bgContainer,
            LinearLayout.LayoutParams(
                dp(76),
                LinearLayout.LayoutParams.WRAP_CONTENT
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
            width = dp(76)
            height = dp(184)
        }
        paramsRef = params
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
        val bitmap = currentPreviewBitmap
        currentPreviewBitmap = null
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        overlayView = null
        previewImageView = null
        speakerButton = null
        paramsRef = null
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

    private inner class DragOverlayLayout(context: Context) : LinearLayout(context) {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startLayoutX = 0
        private var startLayoutY = 0
        private var dragging = false
        private var onTap: (() -> Unit)? = null
        private var onDrag: ((newX: Int, newY: Int, params: WindowManager.LayoutParams) -> Unit)? = null

        fun setOnTapListener(listener: (() -> Unit)?) {
            onTap = listener
        }

        fun setOnDragListener(listener: ((newX: Int, newY: Int, params: WindowManager.LayoutParams) -> Unit)?) {
            onDrag = listener
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (paramsRef == null) {
                return super.onInterceptTouchEvent(ev)
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    paramsRef?.let {
                        startLayoutX = it.x
                        startLayoutY = it.y
                    }
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - startRawX).toInt()
                    val dy = (ev.rawY - startRawY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        return true
                    }
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    dragging = false
                }
            }
            return dragging && paramsRef != null
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val params = paramsRef ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        onDrag?.invoke(startLayoutX - dx, startLayoutY + dy, params)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        onTap?.invoke()
                    }
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    @Volatile
    private var paramsRef: WindowManager.LayoutParams? = null
}
