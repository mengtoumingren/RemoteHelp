package com.timemotion.remotehelp.core

import android.content.Context

class ConnectionSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadServerUrl(defaultValue: String): String {
        return preferences.getString(KEY_SERVER_URL, defaultValue) ?: defaultValue
    }

    fun loadHelperName(defaultValue: String): String {
        return preferences.getString(KEY_HELPER_NAME, defaultValue) ?: defaultValue
    }

    fun save(serverUrl: String, helperName: String) {
        preferences.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_HELPER_NAME, helperName)
            .apply()
    }

    companion object {
        private const val PREF_NAME = "remote_help_connection_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_HELPER_NAME = "helper_name"
    }
}
