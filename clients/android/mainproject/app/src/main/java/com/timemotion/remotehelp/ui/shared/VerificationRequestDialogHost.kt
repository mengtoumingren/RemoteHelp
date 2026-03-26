package com.timemotion.remotehelp.ui.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.timemotion.remotehelp.core.AppLog
import com.timemotion.remotehelp.core.DeviceSide
import com.timemotion.remotehelp.core.RemoteHelpUiState
import com.timemotion.remotehelp.core.shouldShowVerificationRequestDialog

@Composable
fun VerificationRequestDialogHost(
    uiState: RemoteHelpUiState,
    onAcceptRequest: () -> Unit,
    onRejectRequest: () -> Unit
) {
    val context = LocalContext.current
    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingVerificationAccept by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { permissions: Map<String, Boolean> ->
        locationPermissionGranted =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (pendingVerificationAccept) {
            pendingVerificationAccept = false
            if (locationPermissionGranted) {
                runCatching { onAcceptRequest() }
                    .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "接受验证请求失败") }
            } else {
                runCatching { onRejectRequest() }
                    .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "拒绝验证请求失败") }
                Toast.makeText(context, "未授权定位，已视为拒绝本次视频认证", Toast.LENGTH_LONG).show()
            }
        } else if (!locationPermissionGranted) {
            Toast.makeText(context, "未授予定位权限，留痕记录将不包含位置信息", Toast.LENGTH_LONG).show()
        }
    }

    if (
        uiState.side == DeviceSide.HELPER &&
        uiState.isVerificationRequestVisible &&
        uiState.activeSession != null &&
        uiState.shouldShowVerificationRequestDialog()
    ) {
        VerificationRequestDialog(
            session = uiState.activeSession,
            locationPermissionGranted = locationPermissionGranted,
            onRequestLocationPermission = {
                runCatching {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }.onFailure {
                    AppLog.logThrowable("RemoteHelpApp", it, "申请定位权限失败")
                }
            },
            onAccept = {
                if (locationPermissionGranted) {
                    runCatching { onAcceptRequest() }
                        .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "接受验证请求失败") }
                } else {
                    pendingVerificationAccept = true
                    runCatching {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }.onFailure {
                        pendingVerificationAccept = false
                        AppLog.logThrowable("RemoteHelpApp", it, "申请定位权限失败")
                    }
                }
            },
            onReject = {
                runCatching { onRejectRequest() }
                    .onFailure { AppLog.logThrowable("RemoteHelpApp", it, "拒绝验证请求失败") }
            }
        )
    }
}
