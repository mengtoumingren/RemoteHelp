package com.timemotion.remotecontroldemo.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
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
                if (focusEditableNodeAt(x.toInt(), y.toInt())) {
                    return true
                }
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 80)
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
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
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 220)
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            }

            RemoteAction.INPUT_TEXT -> setFocusedText(command.text, command.dismissKeyboard)
            RemoteAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            RemoteAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun setFocusedText(text: String?, dismissKeyboard: Boolean): Boolean {
        val content = text?.takeIf { it.isNotBlank() } ?: return false
        val focusedNode = findEditableNode(rootInActiveWindow) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content)
        }
        val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (success && dismissKeyboard) {
            Handler(Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
            }, 120)
        }
        return success
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }
        if (node.isEditable && (node.isFocused || node.isAccessibilityFocused || node.isVisibleToUser)) {
            return node
        }
        for (index in 0 until node.childCount) {
            val match = findEditableNode(node.getChild(index))
            if (match != null) {
                return match
            }
        }
        return null
    }

    private fun focusEditableNodeAt(x: Int, y: Int): Boolean {
        val node = findEditableNodeAt(rootInActiveWindow, x, y) ?: return false
        var success = false
        if (!node.isFocused) {
            success = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || success
        }
        if (!node.isAccessibilityFocused) {
            success = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) || success
        }
        return success || node.isFocused || node.isAccessibilityFocused
    }

    private fun findEditableNodeAt(
        node: AccessibilityNodeInfo?,
        x: Int,
        y: Int
    ): AccessibilityNodeInfo? {
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
        if (node.isEditable) {
            return node
        }
        return null
    }

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
    }
}
