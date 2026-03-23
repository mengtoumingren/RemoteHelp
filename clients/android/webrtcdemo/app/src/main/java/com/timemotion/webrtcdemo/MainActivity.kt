package com.timemotion.webrtcdemo

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timemotion.webrtcdemo.ui.CallScreen
import com.timemotion.webrtcdemo.ui.theme.WebrtcdemoTheme
import com.timemotion.webrtcdemo.webrtc.CallController

class MainActivity : ComponentActivity() {
    private lateinit var callController: CallController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callController = CallController(applicationContext)
        enableEdgeToEdge()
        setContent {
            WebrtcdemoTheme {
                val context = LocalContext.current
                val uiState by callController.uiState.collectAsStateWithLifecycle()
                var permissionsGranted by remember { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    permissionsGranted = permissions.values.all { it }
                    if (!permissionsGranted) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.permission_required),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        callController.startLocalMedia()
                    }
                }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                }

                LaunchedEffect(permissionsGranted) {
                    if (permissionsGranted) {
                        callController.startLocalMedia()
                    }
                }

                CallScreen(
                    uiState = uiState,
                    onServerUrlChanged = callController::updateServerUrl,
                    onRoomIdChanged = callController::updateRoomId,
                    onDisplayNameChanged = callController::updateDisplayName,
                    onJoinClick = {
                        if (permissionsGranted) {
                            callController.joinRoom()
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.RECORD_AUDIO
                                )
                            )
                        }
                    },
                    onLeaveClick = callController::leaveRoom,
                    onToggleMic = callController::toggleMic,
                    onToggleCamera = callController::toggleCamera
                )
            }
        }
    }

    override fun onDestroy() {
        callController.release()
        super.onDestroy()
    }
}
