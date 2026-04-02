package com.timemotion.remotehelp.core

import android.content.Context
import com.timemotion.remotehelp.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow

class RemoteHelpSettingsManager(
    context: Context,
    private val uiState: MutableStateFlow<RemoteHelpUiState>
) {
    private val settingsStore = ConnectionSettingsStore(context.applicationContext)

    fun loadServerUrl(defaultValue: String): String = settingsStore.loadServerUrl(defaultValue)

    fun loadHelperName(defaultValue: String): String = settingsStore.loadHelperName(defaultValue)

    fun loadStunServer(defaultValue: String): String = settingsStore.loadStunServer(defaultValue)

    fun loadTurnServer(defaultValue: String): String = settingsStore.loadTurnServer(defaultValue)

    fun loadTurnUsername(defaultValue: String): String = settingsStore.loadTurnUsername(defaultValue)

    fun loadTurnPassword(defaultValue: String): String = settingsStore.loadTurnPassword(defaultValue)

    fun updateServerUrl(value: String) {
        uiState.value = uiState.value.copy(serverUrl = value)
    }

    fun updateHelperName(value: String) {
        uiState.value = uiState.value.copy(helperName = value)
    }

    fun updateStunServer(value: String) {
        uiState.value = uiState.value.copy(stunServer = value)
    }

    fun updateTurnServer(value: String) {
        uiState.value = uiState.value.copy(turnServer = value)
    }

    fun updateTurnUsername(value: String) {
        uiState.value = uiState.value.copy(turnUsername = value)
    }

    fun updateTurnPassword(value: String) {
        uiState.value = uiState.value.copy(turnPassword = value)
    }

    fun saveSettings() {
        val serverUrl = uiState.value.serverUrl.trim()
        if (!isAllowedServerUrl(serverUrl)) {
            val banner = if (BuildConfig.ALLOW_INSECURE_TRANSPORT) {
                "服务地址必须使用 ws:// 或 wss://"
            } else {
                "生产环境仅允许 wss:// 地址"
            }
            uiState.value = uiState.value.copy(bannerMessage = banner)
            return
        }
        settingsStore.save(
            serverUrl = serverUrl,
            helperName = uiState.value.helperName.trim(),
            stunServer = uiState.value.stunServer.trim(),
            turnServer = uiState.value.turnServer.trim(),
            turnUsername = uiState.value.turnUsername.trim(),
            turnPassword = uiState.value.turnPassword.trim()
        )
        uiState.value = uiState.value.copy(
            bannerMessage = "设置已保存"
        )
    }

    private fun isAllowedServerUrl(serverUrl: String): Boolean {
        val normalized = serverUrl.lowercase()
        return if (BuildConfig.ALLOW_INSECURE_TRANSPORT) {
            normalized.startsWith("ws://") || normalized.startsWith("wss://")
        } else {
            normalized.startsWith("wss://")
        }
    }
}
