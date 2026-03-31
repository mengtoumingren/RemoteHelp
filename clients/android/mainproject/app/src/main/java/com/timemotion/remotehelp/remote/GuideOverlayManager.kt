package com.timemotion.remotehelp.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import com.timemotion.remotehelp.core.AppLog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class GuideOverlayManager(
    private val appContext: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var overlayView: GuideOverlayView? = null
    @Volatile
    private var clearRunnable: Runnable? = null

    fun hasPermission(): Boolean = Settings.canDrawOverlays(appContext)

    fun show(): Boolean {
        if (!hasPermission()) {
            return false
        }
        if (overlayView != null) {
            return true
        }
        return runCatching {
            val windowManager = appContext.getSystemService(WindowManager::class.java)
            val view = GuideOverlayView(appContext)
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            windowManager.addView(view, params)
            overlayView = view
            true
        }.onFailure {
            AppLog.logThrowable("GuideOverlay", it, "显示远程指引遮罩失败")
        }.getOrDefault(false)
    }

    fun hide() {
        clearRunnable?.let(mainHandler::removeCallbacks)
        clearRunnable = null
        val view = overlayView ?: return
        runCatching {
            appContext.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        }.onFailure {
            AppLog.logThrowable("GuideOverlay", it, "移除远程指引遮罩失败")
        }
        overlayView = null
    }

    fun renderCommand(command: RemoteCommand, targetStatus: RemoteTargetStatus) {
        val view = overlayView ?: return
        val normalizedBounds = resolveNormalizedBounds(command, targetStatus)
        val indicator = when (command.action) {
            RemoteAction.TAP -> GuideIndicator.Tap(normalizedBounds.startX, normalizedBounds.startY)
            RemoteAction.SWIPE -> GuideIndicator.Path(
                normalizedBounds.startX,
                normalizedBounds.startY,
                normalizedBounds.endX,
                normalizedBounds.endY,
                false
            )
            RemoteAction.DRAG -> GuideIndicator.Path(
                normalizedBounds.startX,
                normalizedBounds.startY,
                normalizedBounds.endX,
                normalizedBounds.endY,
                true
            )
            RemoteAction.TEXT_INPUT -> GuideIndicator.Message("请点击输入框并输入内容")
            RemoteAction.BACKSPACE -> GuideIndicator.Message("请删除上一个字符")
            RemoteAction.CURSOR_LEFT -> GuideIndicator.Message("请将光标向左移动")
            RemoteAction.CURSOR_RIGHT -> GuideIndicator.Message("请将光标向右移动")
            RemoteAction.ENTER -> GuideIndicator.Message("请点击回车")
            RemoteAction.BACK -> GuideIndicator.Message("请点击返回键")
            RemoteAction.HOME -> GuideIndicator.Message("请回到桌面")
            RemoteAction.RECENTS -> GuideIndicator.Message("请打开最近任务")
        }
        clearRunnable?.let(mainHandler::removeCallbacks)
        view.showIndicator(indicator)
        clearRunnable = Runnable {
            overlayView?.clearIndicator()
        }.also { mainHandler.postDelayed(it, GUIDE_DURATION_MS) }
    }

    private fun resolveNormalizedBounds(
        command: RemoteCommand,
        targetStatus: RemoteTargetStatus
    ): NormalizedBounds {
        val deviceMetrics = appContext.readDeviceScreenMetrics()
        val targetSize = resolveTargetSize(
            targetWidth = targetStatus.screenWidth,
            targetHeight = targetStatus.screenHeight,
            actualWidth = deviceMetrics.width,
            actualHeight = deviceMetrics.height
        )
        val startX = command.normalizedX
            ?: command.screenX?.toFloat()?.div(targetSize.width.toFloat())
            ?: 0.5f
        val startY = command.normalizedY
            ?: command.screenY?.toFloat()?.div(targetSize.height.toFloat())
            ?: 0.5f
        val endX = command.endNormalizedX
            ?: command.endScreenX?.toFloat()?.div(targetSize.width.toFloat())
            ?: startX
        val endY = command.endNormalizedY
            ?: command.endScreenY?.toFloat()?.div(targetSize.height.toFloat())
            ?: startY
        return NormalizedBounds(
            startX = startX.coerceIn(0f, 1f),
            startY = startY.coerceIn(0f, 1f),
            endX = endX.coerceIn(0f, 1f),
            endY = endY.coerceIn(0f, 1f)
        )
    }

    private fun resolveTargetSize(
        targetWidth: Int,
        targetHeight: Int,
        actualWidth: Int,
        actualHeight: Int
    ): DeviceScreenMetrics {
        val safeTargetWidth = targetWidth.coerceAtLeast(1)
        val safeTargetHeight = targetHeight.coerceAtLeast(1)
        val targetLandscape = safeTargetWidth >= safeTargetHeight
        val actualLandscape = actualWidth >= actualHeight
        return if (targetLandscape == actualLandscape) {
            DeviceScreenMetrics(safeTargetWidth, safeTargetHeight)
        } else {
            DeviceScreenMetrics(safeTargetHeight, safeTargetWidth)
        }
    }

    private sealed interface GuideIndicator {
        data class Tap(val normalizedX: Float, val normalizedY: Float) : GuideIndicator
        data class Path(
            val startX: Float,
            val startY: Float,
            val endX: Float,
            val endY: Float,
            val longPress: Boolean
        ) : GuideIndicator
        data class Message(val text: String) : GuideIndicator
    }

    private class GuideOverlayView(context: Context) : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3)
            color = Color.argb(230, 255, 199, 0)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(96, 255, 199, 0)
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4)
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(220, 255, 199, 0)
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(230, 255, 87, 34)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = dp(14)
        }
        private var indicator: GuideIndicator? = null
        private var startedAt: Long = 0L
        private val screenLocation = IntArray(2)
        private val deviceMetrics by lazy { context.readDeviceScreenMetrics() }

        init {
            setBackgroundColor(Color.TRANSPARENT)
        }

        fun showIndicator(next: GuideIndicator) {
            indicator = next
            startedAt = System.currentTimeMillis()
            invalidate()
        }

        fun clearIndicator() {
            indicator = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = indicator ?: return
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            val fade = 1f - min(1f, elapsed / GUIDE_DURATION_MS.toFloat())
            getLocationOnScreen(screenLocation)
            val screenLeft = screenLocation[0].toFloat()
            val screenTop = screenLocation[1].toFloat()
            val screenWidth = deviceMetrics.width.toFloat().coerceAtLeast(1f)
            val screenHeight = deviceMetrics.height.toFloat().coerceAtLeast(1f)
            when (current) {
                is GuideIndicator.Tap -> drawTap(
                    canvas,
                    current.normalizedX * screenWidth - screenLeft,
                    current.normalizedY * screenHeight - screenTop,
                    fade
                )
                is GuideIndicator.Path -> drawPath(canvas, current, fade, screenWidth, screenHeight, screenLeft, screenTop)
                is GuideIndicator.Message -> drawMessage(canvas, current.text, fade)
            }
            if (fade > 0f) {
                postInvalidateOnAnimation()
            }
        }

        private fun drawTap(canvas: Canvas, x: Float, y: Float, fade: Float) {
            val pulse = ((System.currentTimeMillis() - startedAt) % 900L) / 900f
            val baseRadius = dp(18)
            val outerRadius = baseRadius + dp(18) * pulse
            ringPaint.alpha = (255 * fade).toInt()
            fillPaint.alpha = (110 * fade).toInt()
            accentPaint.alpha = (240 * fade).toInt()
            canvas.drawCircle(x, y, outerRadius, ringPaint)
            canvas.drawCircle(x, y, baseRadius, fillPaint)
            canvas.drawCircle(x, y, dp(6), accentPaint)
            drawLabel(canvas, "请点这里", x, y - outerRadius - dp(10), fade)
        }

        private fun drawPath(
            canvas: Canvas,
            indicator: GuideIndicator.Path,
            fade: Float,
            screenWidth: Float,
            screenHeight: Float,
            screenLeft: Float,
            screenTop: Float
        ) {
            val startX = indicator.startX * screenWidth - screenLeft
            val startY = indicator.startY * screenHeight - screenTop
            val endX = indicator.endX * screenWidth - screenLeft
            val endY = indicator.endY * screenHeight - screenTop
            ringPaint.alpha = (240 * fade).toInt()
            fillPaint.alpha = (90 * fade).toInt()
            linePaint.alpha = (220 * fade).toInt()
            accentPaint.alpha = (240 * fade).toInt()
            canvas.drawLine(startX, startY, endX, endY, linePaint)
            canvas.drawCircle(startX, startY, dp(if (indicator.longPress) 16 else 12), fillPaint)
            canvas.drawCircle(startX, startY, dp(if (indicator.longPress) 24 else 18), ringPaint)
            canvas.drawCircle(endX, endY, dp(10), accentPaint)
            drawArrowHead(canvas, startX, startY, endX, endY, fade)
            drawLabel(
                canvas,
                if (indicator.longPress) "请长按拖动" else "请向这里滑动",
                startX,
                min(startY, endY) - dp(12),
                fade
            )
        }

        private fun drawArrowHead(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float, fade: Float) {
            val angle = atan2(endY - startY, endX - startX)
            val headLength = dp(14)
            val wingAngle = Math.toRadians(26.0)
            val path = Path().apply {
                moveTo(endX, endY)
                lineTo(
                    endX - headLength * cos(angle - wingAngle).toFloat(),
                    endY - headLength * sin(angle - wingAngle).toFloat()
                )
                moveTo(endX, endY)
                lineTo(
                    endX - headLength * cos(angle + wingAngle).toFloat(),
                    endY - headLength * sin(angle + wingAngle).toFloat()
                )
            }
            linePaint.alpha = (220 * fade).toInt()
            canvas.drawPath(path, linePaint)
        }

        private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, fade: Float) {
            labelPaint.alpha = (255 * fade).toInt()
            val textWidth = labelPaint.measureText(text)
            canvas.drawText(text, x - textWidth / 2f, y.coerceAtLeast(dp(24)), labelPaint)
        }

        private fun drawMessage(canvas: Canvas, text: String, fade: Float) {
            val centerX = width / 2f
            val centerY = height * 0.22f
            val paddingH = dp(18)
            val paddingV = dp(12)
            val textWidth = labelPaint.measureText(text)
            val boxWidth = textWidth + paddingH * 2
            val boxHeight = dp(46)
            val left = centerX - boxWidth / 2f
            val top = centerY - boxHeight / 2f
            val right = centerX + boxWidth / 2f
            val bottom = centerY + boxHeight / 2f
            val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb((210 * fade).toInt(), 22, 32, 48)
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1)
                color = Color.argb((120 * fade).toInt(), 255, 255, 255)
            }
            canvas.drawRoundRect(left, top, right, bottom, dp(14), dp(14), bubblePaint)
            canvas.drawRoundRect(left, top, right, bottom, dp(14), dp(14), borderPaint)
            labelPaint.alpha = (255 * fade).toInt()
            canvas.drawText(text, centerX - textWidth / 2f, centerY + dp(5), labelPaint)
        }

        private fun dp(value: Int): Float = value * resources.displayMetrics.density
    }

    companion object {
        private const val GUIDE_DURATION_MS = 2200L
    }
}

private data class NormalizedBounds(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)
