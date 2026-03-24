package com.timemotion.remotehelp.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {
    private val softKeyboardListener = AccessibilityService.SoftKeyboardController.OnShowModeChangedListener { _, showMode ->
        softKeyboardStateListener?.invoke(showMode == SHOW_MODE_HIDDEN)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.addOnShowModeChangedListener(softKeyboardListener)
        }
        Log.i(TAG, "onServiceConnected")
        instance = this
        stateListener?.invoke(true)
        softKeyboardStateListener?.invoke(isSoftKeyboardHidden())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.w(TAG, "onUnbind")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.removeOnShowModeChangedListener(softKeyboardListener)
        }
        instance = null
        stateListener?.invoke(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.removeOnShowModeChangedListener(softKeyboardListener)
        }
        instance = null
        stateListener?.invoke(false)
        super.onDestroy()
    }

    fun execute(command: RemoteCommand): Boolean {
        return when (command.action) {
            RemoteAction.TAP -> {
                val metrics = applicationContext.readDeviceScreenMetrics()
                val x = (command.screenX?.toFloat()
                    ?: ((command.normalizedX ?: 0.5f) * metrics.width.toFloat()))
                    .coerceIn(0f, metrics.width.toFloat())
                val y = (command.screenY?.toFloat()
                    ?: ((command.normalizedY ?: 0.5f) * metrics.height.toFloat()))
                    .coerceIn(0f, metrics.height.toFloat())
                activateEditableNodeAt(x.toInt(), y.toInt()) || dispatchTapGesture(x, y)
            }

            RemoteAction.SWIPE -> {
                val metrics = applicationContext.readDeviceScreenMetrics()
                val startX = (command.screenX?.toFloat()
                    ?: ((command.normalizedX ?: 0.5f) * metrics.width.toFloat()))
                    .coerceIn(0f, metrics.width.toFloat())
                val startY = (command.screenY?.toFloat()
                    ?: ((command.normalizedY ?: 0.5f) * metrics.height.toFloat()))
                    .coerceIn(0f, metrics.height.toFloat())
                val endX = (command.endScreenX?.toFloat()
                    ?: ((command.endNormalizedX ?: command.normalizedX ?: 0.5f) * metrics.width.toFloat()))
                    .coerceIn(0f, metrics.width.toFloat())
                val endY = (command.endScreenY?.toFloat()
                    ?: ((command.endNormalizedY ?: command.normalizedY ?: 0.5f) * metrics.height.toFloat()))
                    .coerceIn(0f, metrics.height.toFloat())
                dispatchSwipeGesture(startX, startY, endX, endY)
            }

            RemoteAction.DRAG -> {
                val metrics = applicationContext.readDeviceScreenMetrics()
                val startX = (command.screenX?.toFloat()
                    ?: ((command.normalizedX ?: 0.5f) * metrics.width.toFloat()))
                    .coerceIn(0f, metrics.width.toFloat())
                val startY = (command.screenY?.toFloat()
                    ?: ((command.normalizedY ?: 0.5f) * metrics.height.toFloat()))
                    .coerceIn(0f, metrics.height.toFloat())
                val endX = (command.endScreenX?.toFloat()
                    ?: ((command.endNormalizedX ?: command.normalizedX ?: 0.5f) * metrics.width.toFloat()))
                    .coerceIn(0f, metrics.width.toFloat())
                val endY = (command.endScreenY?.toFloat()
                    ?: ((command.endNormalizedY ?: command.normalizedY ?: 0.5f) * metrics.height.toFloat()))
                    .coerceIn(0f, metrics.height.toFloat())
                dispatchLongPressDragGesture(startX, startY, endX, endY)
            }

            RemoteAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            RemoteAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            RemoteAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private fun dispatchTapGesture(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dispatchSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 220)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dispatchLongPressDragGesture(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val holdDuration = 280L
        val dragDuration = 420L
        val builder = GestureDescription.Builder()
        val holdPath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, startY)
        }
        val dragPath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        builder.addStroke(GestureDescription.StrokeDescription(holdPath, 0, holdDuration))
        builder.addStroke(GestureDescription.StrokeDescription(dragPath, holdDuration, dragDuration))
        return dispatchGesture(builder.build(), null, null)
    }

    fun isSoftKeyboardHidden(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        return softKeyboardController.showMode == SHOW_MODE_HIDDEN
    }

    private fun activateEditableNodeAt(x: Int, y: Int): Boolean {
        val node = findEditableNodeAt(rootInActiveWindow, x, y) ?: return false
        var success = false
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
            success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) || success
        }
        if (!node.isFocused) {
            success = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || success
        }
        if (!node.isAccessibilityFocused) {
            success = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) || success
        }
        val showOnScreenActionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
        if (node.actionList.any { it.id == showOnScreenActionId }) {
            success = node.performAction(showOnScreenActionId) || success
        }
        return success || node.isFocused || node.isAccessibilityFocused
    }

    private fun findEditableNodeAt(node: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) {
            return null
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) {
            return null
        }
        for (index in node.childCount - 1 downTo 0) {
            val match = findEditableNodeAt(node.getChild(index), x, y)
            if (match != null) {
                return match
            }
        }
        return node.takeIf { it.isEditable }
    }

    companion object {
        private const val TAG = "RemoteA11yService"

        @Volatile
        var instance: RemoteAccessibilityService? = null

        @Volatile
        var stateListener: ((Boolean) -> Unit)? = null

        @Volatile
        var softKeyboardStateListener: ((Boolean) -> Unit)? = null

        fun isEnabled(context: Context): Boolean {
            val serviceId = ComponentName(context, RemoteAccessibilityService::class.java).flattenToString()
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':').apply {
                setString(enabledServices)
            }
            while (splitter.hasNext()) {
                if (splitter.next().equals(serviceId, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }
}
