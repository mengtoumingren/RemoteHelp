package com.timemotion.remotecontroldemo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timemotion.remotecontroldemo.remote.RemoteControlController
import com.timemotion.remotecontroldemo.ui.RemoteControlScreen
import com.timemotion.remotecontroldemo.ui.theme.RemotecontroldemoTheme

class MainActivity : ComponentActivity() {
    private lateinit var controller: RemoteControlController
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = RemoteControlController(applicationContext)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        enableEdgeToEdge()
        setContent {
            RemotecontroldemoTheme {
                RemoteControlRoute(
                    controller = controller,
                    projectionIntent = projectionManager.createScreenCaptureIntent()
                )
            }
        }
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        controller.refreshLocalCapabilities()
    }
}

@Composable
private fun RemoteControlRoute(
    controller: RemoteControlController,
    projectionIntent: Intent
) {
    val context = LocalContext.current
    val uiState = controller.uiState.collectAsStateWithLifecycle()
    val currentController = remember(controller) { controller }
    val projectionLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            currentController.startTargetCapture(result.resultCode, result.data!!)
        } else {
            currentController.onCapturePermissionDenied()
        }
    }

    RemoteControlScreen(
        uiState = uiState.value,
        onServerUrlChanged = currentController::updateServerUrl,
        onRoomIdChanged = currentController::updateRoomId,
        onDisplayNameChanged = currentController::updateDisplayName,
        onRoleSelected = currentController::selectRole,
        onConnectClick = currentController::connect,
        onDisconnectClick = currentController::disconnect,
        onRequestCapture = { projectionLauncher.launch(projectionIntent) },
        onOpenAccessibilitySettings = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onCommand = currentController::sendCommand,
        onQuickCommand = currentController::sendCommand,
        onFrameTap = currentController::sendTapCommand,
        onFrameSwipe = currentController::sendSwipeCommand
    )
}
