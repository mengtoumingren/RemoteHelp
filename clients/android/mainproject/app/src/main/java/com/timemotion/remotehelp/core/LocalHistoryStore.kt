package com.timemotion.remotehelp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LocalHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadRecentContacts(): List<RecentContact> {
        val raw = preferences.getString(KEY_RECENT_CONTACTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        RecentContact(
                            name = item.optString("name"),
                            phone = item.optString("phone"),
                            lastHelpTime = item.optLong("lastHelpTime"),
                            helpCount = item.optInt("helpCount", 1)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun loadHistory(): List<SessionHistoryItem> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        SessionHistoryItem(
                            requestId = item.optString("requestId"),
                            helperName = item.optString("helperName"),
                            elderName = item.optString("elderName"),
                            elderPhone = item.optString("elderPhone"),
                            startedAt = item.optLong("startedAt"),
                            endedAt = item.optLong("endedAt"),
                            endReason = item.optString("endReason")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveRecentContact(name: String, phone: String, now: Long = System.currentTimeMillis()) {
        val updated = loadRecentContacts().toMutableList()
        val existingIndex = updated.indexOfFirst { it.phone == phone }
        if (existingIndex >= 0) {
            val existing = updated.removeAt(existingIndex)
            updated.add(
                0,
                existing.copy(
                    name = name,
                    lastHelpTime = now,
                    helpCount = existing.helpCount + 1
                )
            )
        } else {
            updated.add(
                0,
                RecentContact(
                    name = name,
                    phone = phone,
                    lastHelpTime = now,
                    helpCount = 1
                )
            )
        }
        persistRecentContacts(updated.take(MAX_ITEMS))
    }

    fun saveHistory(item: SessionHistoryItem) {
        val updated = (listOf(item) + loadHistory()).distinctBy { "${it.requestId}-${it.startedAt}" }
        val array = JSONArray()
        updated.take(MAX_ITEMS).forEach { record ->
            array.put(
                JSONObject()
                    .put("requestId", record.requestId)
                    .put("helperName", record.helperName)
                    .put("elderName", record.elderName)
                    .put("elderPhone", record.elderPhone)
                    .put("startedAt", record.startedAt)
                    .put("endedAt", record.endedAt)
                    .put("endReason", record.endReason)
            )
        }
        preferences.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun persistRecentContacts(items: List<RecentContact>) {
        val array = JSONArray()
        items.forEach { contact ->
            array.put(
                JSONObject()
                    .put("name", contact.name)
                    .put("phone", contact.phone)
                    .put("lastHelpTime", contact.lastHelpTime)
                    .put("helpCount", contact.helpCount)
            )
        }
        preferences.edit().putString(KEY_RECENT_CONTACTS, array.toString()).apply()
    }

    companion object {
        private const val PREF_NAME = "remote_help_local_history"
        private const val KEY_RECENT_CONTACTS = "recent_contacts"
        private const val KEY_HISTORY = "session_history"
        private const val MAX_ITEMS = 8
    }
}
