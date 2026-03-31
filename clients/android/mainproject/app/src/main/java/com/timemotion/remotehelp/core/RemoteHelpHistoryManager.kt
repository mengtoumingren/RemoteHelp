package com.timemotion.remotehelp.core

import android.content.Context

class RemoteHelpHistoryManager(context: Context) {
    private val store = LocalHistoryStore(context.applicationContext)

    fun loadRestoredHelperSession(): ActiveHelpSession? {
        return store.loadPendingHelperSession()
            ?.takeUnless { it.isExpired() }
            ?.takeIf { it.channelToken.isNotBlank() }
            ?.copy(stage = HelpStage.REQUEST_CREATED, verificationAcceptedAt = null, endReason = null)
    }

    fun loadRecentContacts(): List<RecentContact> = store.loadRecentContacts()

    fun loadHistory(): List<SessionHistoryItem> = store.loadHistory()

    fun saveRecentContact(name: String, phone: String, now: Long = System.currentTimeMillis()) {
        store.saveRecentContact(name, phone, now)
    }

    fun saveHistory(item: SessionHistoryItem) {
        store.saveHistory(item)
    }

    fun savePendingHelperSession(session: ActiveHelpSession) {
        store.savePendingHelperSession(session)
    }

    fun clearPendingHelperSession() {
        store.clearPendingHelperSession()
    }
}
