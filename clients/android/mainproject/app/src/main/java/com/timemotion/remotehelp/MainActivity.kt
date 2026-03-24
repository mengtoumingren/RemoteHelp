package com.timemotion.remotehelp

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.ui.RemoteHelpApp
import com.timemotion.remotehelp.ui.theme.RemotehelpTheme

class MainActivity : ComponentActivity() {
    private lateinit var coordinator: RemoteHelpCoordinator
    private lateinit var projectionManager: MediaProjectionManager
    private val pendingDeepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.install(applicationContext)
        coordinator = RemoteHelpCoordinator(applicationContext)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        queueDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            val uiState = coordinator.uiState.collectAsStateWithLifecycle()
            val callState = coordinator.callController.uiState.collectAsStateWithLifecycle()
            val remoteState = coordinator.remoteController.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(pendingDeepLink.value) {
                pendingDeepLink.value?.let { deepLink ->
                    coordinator.consumeInvite(deepLink)
                    pendingDeepLink.value = null
                }
            }

            RemotehelpTheme {
                RemoteHelpApp(
                    coordinator = coordinator,
                    uiState = uiState.value,
                    callState = callState.value,
                    remoteState = remoteState.value,
                    projectionIntent = projectionManager.createScreenCaptureIntent()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        queueDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        coordinator.callController.resumeAfterForeground()
        coordinator.remoteController.refreshLocalCapabilities()
    }

    override fun onDestroy() {
        coordinator.release()
        super.onDestroy()
    }

    private fun queueDeepLink(intent: Intent?) {
        pendingDeepLink.value = intent?.dataString?.takeIf { it.isNotBlank() }
    }
}
