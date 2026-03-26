package com.timemotion.remotehelp.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.timemotion.remotehelp.core.AppLog

class RemoteAccessibilityService : AccessibilityService() {
    private val softKeyboardListener = AccessibilityService.SoftKeyboardController.OnShowModeChangedListener { _, showMode ->
        softKeyboardStateListener?.invoke(showMode == SHOW_MODE_HIDDEN)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
    }

    override fun onServiceConnected() {
        runCatching {
            super.onServiceConnected()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                softKeyboardController.addOnShowModeChangedListener(softKeyboardListener)
            }
            Log.i(TAG, "onServiceConnected")
            instance = this
            stateListener?.invoke(true)
            softKeyboardStateListener?.invoke(isSoftKeyboardHidden())
        }.onFailure {
            AppLog.logThrowable(TAG, it, "无障碍服务连接失败")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        return runCatching {
            Log.w(TAG, "onUnbind")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                softKeyboardController.removeOnShowModeChangedListener(softKeyboardListener)
            }
            instance = null
            stateListener?.invoke(false)
            super.onUnbind(intent)
        }.getOrElse {
            AppLog.logThrowable(TAG, it, "无障碍服务解绑失败")
            false
        }
    }

    override fun onDestroy() {
        runCatching {
            Log.w(TAG, "onDestroy")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                softKeyboardController.removeOnShowModeChangedListener(softKeyboardListener)
            }
            instance = null
            stateListener?.invoke(false)
            super.onDestroy()
        }.onFailure {
            AppLog.logThrowable(TAG, it, "无障碍服务销毁失败")
        }
    }

    fun execute(command: RemoteCommand): Boolean {
        return runCatching {
            when (command.action) {
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

                RemoteAction.TEXT_INPUT -> {
                    val text = command.text.orEmpty()
                    if (text.isEmpty()) {
                        false
                    } else {
                        inputTextIntoBestNode(text)
                    }
                }

                RemoteAction.BACKSPACE -> deletePreviousCharacter()
                RemoteAction.CURSOR_LEFT -> moveCursorByCharacter(previous = true)
                RemoteAction.CURSOR_RIGHT -> moveCursorByCharacter(previous = false)
                RemoteAction.ENTER -> insertNewLine()

                RemoteAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                RemoteAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                RemoteAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }.getOrElse {
            AppLog.logThrowable(TAG, it, "执行远控指令失败: ${command.action}")
            false
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

    private fun pasteTextIntoFocusedNode(text: String): Boolean {
        return inputTextIntoBestNode(text)
    }

    private fun deletePreviousCharacter(): Boolean {
        val node = prepareEditableNode() ?: return false
        val selectionActionArgs = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
            )
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
        }
        val selected = node.performAction(
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
            selectionActionArgs
        )
        if (selected && node.performAction(AccessibilityNodeInfo.ACTION_CUT)) {
            return true
        }
        return deleteByTextMutation(node)
    }

    private fun moveCursorByCharacter(previous: Boolean): Boolean {
        val node = prepareEditableNode() ?: return false
        val action = if (previous) {
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        } else {
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        }
        val args = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
            )
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
        }
        return node.performAction(action, args)
    }

    private fun insertNewLine(): Boolean {
        return pasteTextIntoFocusedNode("\n")
    }

    private fun deleteByTextMutation(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString().orEmpty()
        if (text.isEmpty()) {
            return false
        }
        val selectionStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            node.textSelectionStart
        } else {
            -1
        }
        val selectionEnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            node.textSelectionEnd
        } else {
            -1
        }
        val nextText = when {
            selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd -> {
                val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
                val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
                text.removeRange(start, end)
            }
            selectionStart > 0 -> {
                val index = selectionStart.coerceAtMost(text.length)
                text.removeRange(index - 1, index)
            }
            else -> text.dropLast(1)
        }
        if (nextText == text) {
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, nextText)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun inputTextIntoBestNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = buildList {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { supportsTextInput(it) }
                ?.let { add(it) }
            findBestTextInputNode(root)
                ?.let { add(it) }
        }.distinctBy { System.identityHashCode(it) }

        for (candidate in candidates) {
            if (!prepareNodeForInput(candidate)) {
                continue
            }
            if (injectTextIntoNode(candidate, text)) {
                return true
            }
        }
        AppLog.d(
            TAG,
            "文本注入失败 textLength=${text.length} candidateCount=${candidates.size}"
        )
        return false
    }

    private fun injectTextIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val currentText = node.text?.toString().orEmpty()
        if (currentText.isEmpty()) {
            val setTextArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
                return true
            }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("remote_help_text", text))
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return true
        }
        if (currentText.isEmpty()) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        AppLog.d(
            TAG,
            "文本粘贴失败 node=${node.className} textLength=${text.length}"
        )
        return false
    }

    private fun prepareEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { supportsTextInput(it) }
            ?.let { return it }
        val editable = findBestTextInputNode(root) ?: return null
        prepareNodeForInput(editable)
        return editable
    }

    private fun prepareNodeForInput(node: AccessibilityNodeInfo): Boolean {
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
        return success || node.isFocused || node.isAccessibilityFocused || supportsTextInput(node)
    }

    private fun supportsTextInput(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) {
            return true
        }
        return node.actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_SET_TEXT ||
                action.id == AccessibilityNodeInfo.ACTION_PASTE
        }
    }

    private fun findBestTextInputNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) {
            return null
        }
        if (supportsTextInput(node)) {
            return node
        }
        for (index in node.childCount - 1 downTo 0) {
            val match = findBestTextInputNode(node.getChild(index))
            if (match != null) {
                return match
            }
        }
        return null
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
