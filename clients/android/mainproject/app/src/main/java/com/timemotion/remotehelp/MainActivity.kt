package com.timemotion.remotehelp

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.RemoteHelpCoordinator
import com.timemotion.remotehelp.remote.RemoteHelpForegroundService
import com.timemotion.remotehelp.ui.RemoteHelpRoute
import com.timemotion.remotehelp.ui.theme.RemotehelpTheme

class MainActivity : ComponentActivity() {
    private var boundService by mutableStateOf<RemoteHelpForegroundService?>(null)
    private var projectionManager: MediaProjectionManager? = null
    private var startupErrorMessage: String? = null
    private val pendingDeepLink = mutableStateOf<String?>(null)
    private var isServiceBound = false
    private var hadActiveSessionOnLastUnbind = false

    private val coordinator: RemoteHelpCoordinator?
        get() = boundService?.coordinator

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            runCatching {
                val binder = service as RemoteHelpForegroundService.LocalBinder
                boundService = binder.service
                isServiceBound = true
            }.onFailure {
                AppLog.logThrowable("MainActivity", it, "绑定后台服务失败")
                startupErrorMessage = "绑定后台服务失败：${it.message ?: "unknown"}"
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            boundService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            enableEdgeToEdge()
            AppLog.install(applicationContext)
            projectionManager = getSystemService(MediaProjectionManager::class.java)
            queueDeepLink(intent)
            RemoteHelpForegroundService.start(this)
        }.onFailure {
            startupErrorMessage = "启动失败：${it.message ?: "unknown"}"
            AppLog.logThrowable("MainActivity", it, "应用启动失败")
        }
        setContent {
            RemotehelpTheme {
                val activeCoordinator = coordinator
                val activeProjectionManager = projectionManager
                if (startupErrorMessage != null) {
                    StartupErrorScreen(
                        message = startupErrorMessage ?: "启动失败",
                        onRetry = { recreate() }
                    )
                } else if (activeCoordinator == null || activeProjectionManager == null) {
                    StartupLoadingScreen(
                        message = if (isServiceBound) {
                            "正在初始化远程协助服务"
                        } else {
                            "正在连接远程协助服务"
                        }
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

                    RemoteHelpRoute(
                        coordinator = activeCoordinator,
                        uiState = uiState.value,
                        callState = callState.value,
                        remoteState = remoteState.value,
                        projectionIntent = activeProjectionManager.createScreenCaptureIntent(),
                        onRequestHelperOverlayShow = { boundService?.requestHelperOverlayShow() }
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

    override fun onStart() {
        super.onStart()
        runCatching {
            if (!isServiceBound) {
                bindService(
                    Intent(this, RemoteHelpForegroundService::class.java),
                    serviceConnection,
                    BIND_AUTO_CREATE
                )
            }
        }.onFailure {
            startupErrorMessage = "绑定服务失败：${it.message ?: "unknown"}"
            AppLog.logThrowable("MainActivity", it, "绑定后台服务失败")
        }
    }

    override fun onStop() {
        runCatching {
            if (isServiceBound) {
                hadActiveSessionOnLastUnbind = coordinator?.uiState?.value?.activeSession != null
                unbindService(serviceConnection)
                isServiceBound = false
                boundService = null
            }
        }.onFailure {
            AppLog.logThrowable("MainActivity", it, "停止时解绑后台服务失败")
        }
        super.onStop()
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
            if (isFinishing && !hadActiveSessionOnLastUnbind) {
                RemoteHelpForegroundService.stop(this)
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
private fun StartupLoadingScreen(message: String) {
    Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color(0xFFF6EFE3)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "正在启动",
                style = MaterialTheme.typography.headlineMedium,
                color = androidx.compose.ui.graphics.Color(0xFF183153)
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 12.dp),
                color = androidx.compose.ui.graphics.Color(0xFF526277)
            )
        }
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
