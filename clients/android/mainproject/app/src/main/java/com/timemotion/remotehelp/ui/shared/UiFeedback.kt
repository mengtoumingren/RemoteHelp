package com.timemotion.remotehelp.ui.shared

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
