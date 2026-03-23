package com.timemotion.remotehelp.remote

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

data class DeviceScreenMetrics(
    val width: Int,
    val height: Int
)

fun Context.readDeviceScreenMetrics(): DeviceScreenMetrics {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowManager = getSystemService(WindowManager::class.java)
        val bounds = windowManager.maximumWindowMetrics.bounds
        DeviceScreenMetrics(bounds.width(), bounds.height())
    } else {
        @Suppress("DEPRECATION")
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        DeviceScreenMetrics(metrics.widthPixels, metrics.heightPixels)
    }
}
