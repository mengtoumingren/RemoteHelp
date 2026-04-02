package com.timemotion.remotehelp.core

import android.content.Context

class ConnectionSettingsStore(context: Context) {
    private val legacyPreferences = context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)
    private val preferences = SecurePreferencesFactory.create(context.applicationContext, PREF_NAME)

    init {
        migrateLegacyDataIfNeeded()
    }

    fun loadServerUrl(defaultValue: String): String {
        return preferences.getString(KEY_SERVER_URL, defaultValue) ?: defaultValue
    }

    fun loadHelperName(defaultValue: String): String {
        return preferences.getString(KEY_HELPER_NAME, defaultValue) ?: defaultValue
    }

    fun loadInviteApiKey(defaultValue: String): String {
        return preferences.getString(KEY_INVITE_API_KEY, defaultValue) ?: defaultValue
    }

    fun loadStunServer(defaultValue: String): String {
        return preferences.getString(KEY_STUN_SERVER, defaultValue) ?: defaultValue
    }

    fun loadTurnServer(defaultValue: String): String {
        return preferences.getString(KEY_TURN_SERVER, defaultValue) ?: defaultValue
    }

    fun loadTurnUsername(defaultValue: String): String {
        return preferences.getString(KEY_TURN_USERNAME, defaultValue) ?: defaultValue
    }

    fun loadTurnPassword(defaultValue: String): String {
        return preferences.getString(KEY_TURN_PASSWORD, defaultValue) ?: defaultValue
    }

    fun save(
        serverUrl: String, 
        inviteApiKey: String,
        helperName: String, 
        stunServer: String = loadStunServer("stun:stun.timemotion.top:3478"),
        turnServer: String = loadTurnServer(""),
        turnUsername: String = loadTurnUsername(""),
        turnPassword: String = loadTurnPassword("")
    ) {
        preferences.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_INVITE_API_KEY, inviteApiKey)
            .putString(KEY_HELPER_NAME, helperName)
            .putString(KEY_STUN_SERVER, stunServer)
            .putString(KEY_TURN_SERVER, turnServer)
            .putString(KEY_TURN_USERNAME, turnUsername)
            .putString(KEY_TURN_PASSWORD, turnPassword)
            .apply()
    }

    private fun migrateLegacyDataIfNeeded() {
        if (preferences.getBoolean(KEY_MIGRATED, false)) {
            return
        }
        preferences.edit().apply {
            copyLegacy(KEY_SERVER_URL, "")
            copyLegacy(KEY_INVITE_API_KEY, "")
            copyLegacy(KEY_HELPER_NAME, "")
            copyLegacy(KEY_STUN_SERVER, "")
            copyLegacy(KEY_TURN_SERVER, "")
            copyLegacy(KEY_TURN_USERNAME, "")
            copyLegacy(KEY_TURN_PASSWORD, "")
            putBoolean(KEY_MIGRATED, true)
        }.apply()
        legacyPreferences.edit().clear().apply()
    }

    private fun android.content.SharedPreferences.Editor.copyLegacy(key: String, defaultValue: String) {
        val value = legacyPreferences.getString(key, defaultValue)
        if (!value.isNullOrBlank()) {
            putString(key, value)
        }
    }

    companion object {
        private const val PREF_NAME = "remote_help_connection_settings_secure"
        private const val LEGACY_PREF_NAME = "remote_help_connection_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_INVITE_API_KEY = "invite_api_key"
        private const val KEY_HELPER_NAME = "helper_name"
        private const val KEY_STUN_SERVER = "stun_server"
        private const val KEY_TURN_SERVER = "turn_server"
        private const val KEY_TURN_USERNAME = "turn_username"
        private const val KEY_TURN_PASSWORD = "turn_password"
        private const val KEY_MIGRATED = "migrated_v1"
    }
}
