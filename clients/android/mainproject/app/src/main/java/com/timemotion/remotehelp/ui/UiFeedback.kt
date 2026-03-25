package com.timemotion.remotehelp.ui

import android.content.Context
import android.view.Gravity
import android.widget.Toast
import com.timemotion.remotehelp.core.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object UiFeedbackBus {
    private val topToastFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val topToasts = topToastFlow.asSharedFlow()

    fun emitTopToast(message: String) {
        topToastFlow.tryEmit(message)
    }
}

fun showTopToast(
    context: Context,
    message: String,
    duration: Int = Toast.LENGTH_LONG
) {
    val toast = Toast.makeText(context.applicationContext, message, duration)
    val yOffset = (context.resources.displayMetrics.density * 96f).toInt()
    toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, yOffset)
    toast.show()
}

inline fun guardUiAction(
    tag: String,
    failureMessage: String,
    crossinline block: () -> Unit
) {
    runCatching { block() }.onFailure { throwable ->
        AppLog.logThrowable(tag, throwable, failureMessage)
        UiFeedbackBus.emitTopToast(failureMessage)
    }
}
