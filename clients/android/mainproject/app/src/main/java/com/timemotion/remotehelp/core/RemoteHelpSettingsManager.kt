package com.timemotion.remotehelp.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

class RemoteHelpSettingsManager(
    context: Context,
    private val uiState: MutableStateFlow<RemoteHelpUiState>
) {
    private val settingsStore = ConnectionSettingsStore(context.applicationContext)

    fun loadServerUrl(defaultValue: String): String = settingsStore.loadServerUrl(defaultValue)

    fun loadHelperName(defaultValue: String): String = settingsStore.loadHelperName(defaultValue)

    fun updateServerUrl(value: String) {
        uiState.value = uiState.value.copy(serverUrl = value)
    }

    fun updateHelperName(value: String) {
        uiState.value = uiState.value.copy(helperName = value)
    }

    fun saveSettings() {
        settingsStore.save(
            serverUrl = uiState.value.serverUrl.trim(),
            helperName = uiState.value.helperName.trim()
        )
        uiState.value = uiState.value.copy(
            isSettingsVisible = false,
            bannerMessage = "设置已保存"
        )
    }
}
