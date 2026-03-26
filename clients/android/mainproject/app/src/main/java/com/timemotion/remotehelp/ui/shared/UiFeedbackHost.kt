package com.timemotion.remotehelp.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collect

@Composable
fun UiFeedbackHost() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        UiFeedbackBus.topToasts.collect { message ->
            showTopToast(context, message)
        }
    }
}
