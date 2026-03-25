package com.timemotion.remotehelp

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.ui.RemoteHelpApp
import com.timemotion.remotehelp.ui.theme.RemotehelpTheme

class MainActivity : ComponentActivity() {
    companion object {
        private var retainedCoordinator: RemoteHelpCoordinator? = null
    }

    private var coordinator: RemoteHelpCoordinator? = null
    private var projectionManager: MediaProjectionManager? = null
    private var startupErrorMessage: String? = null
    private val pendingDeepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            enableEdgeToEdge()
            AppLog.install(applicationContext)
            coordinator = retainedCoordinator ?: RemoteHelpCoordinator(applicationContext).also {
                retainedCoordinator = it
            }
            projectionManager = getSystemService(MediaProjectionManager::class.java)
            queueDeepLink(intent)
        }.onFailure {
            startupErrorMessage = "启动失败：${it.message ?: "unknown"}"
            AppLog.logThrowable("MainActivity", it, "应用启动失败")
        }
        setContent {
            RemotehelpTheme {
                val activeCoordinator = coordinator
                val activeProjectionManager = projectionManager
                if (startupErrorMessage != null || activeCoordinator == null || activeProjectionManager == null) {
                    StartupErrorScreen(
                        message = startupErrorMessage ?: "启动失败",
                        onRetry = { recreate() }
                    )
                } else {
                    val uiState = activeCoordinator.uiState.collectAsStateWithLifecycle()
                    val callState = activeCoordinator.callController.uiState.collectAsStateWithLifecycle()
                    val remoteState = activeCoordinator.remoteController.uiState.collectAsStateWithLifecycle()

                    LaunchedEffect(pendingDeepLink.value) {
                        pendingDeepLink.value?.let { deepLink ->
                            runCatching {
                                activeCoordinator.consumeInvite(deepLink)
                            }.onFailure {
                                AppLog.logThrowable("MainActivity", it, "处理深链失败")
                            }
                            pendingDeepLink.value = null
                        }
                    }

                    RemoteHelpApp(
                        coordinator = activeCoordinator,
                        uiState = uiState.value,
                        callState = callState.value,
                        remoteState = remoteState.value,
                        projectionIntent = activeProjectionManager.createScreenCaptureIntent()
                    )
                }
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
        runCatching {
            coordinator?.callController?.resumeAfterForeground()
            coordinator?.remoteController?.refreshLocalCapabilities()
        }.onFailure {
            AppLog.logThrowable("MainActivity", it, "恢复前台状态失败")
        }
    }

    override fun onDestroy() {
        runCatching {
            if (isFinishing) {
                coordinator?.release()
                retainedCoordinator = null
            } else {
                AppLog.i("MainActivity", "activity destroyed but session retained")
            }
        }.onFailure {
            AppLog.logThrowable("MainActivity", it, "销毁 Activity 失败")
        }
        super.onDestroy()
    }

    private fun queueDeepLink(intent: Intent?) {
        pendingDeepLink.value = intent?.dataString?.takeIf { it.isNotBlank() }
    }
}

@androidx.compose.runtime.Composable
private fun StartupErrorScreen(
    message: String,
    onRetry: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color(0xFFF6EFE3)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "应用启动异常",
                style = MaterialTheme.typography.headlineMedium,
                color = androidx.compose.ui.graphics.Color(0xFF183153)
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                color = androidx.compose.ui.graphics.Color(0xFF526277)
            )
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}
