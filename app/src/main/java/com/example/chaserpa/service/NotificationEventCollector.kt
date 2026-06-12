package com.example.chaserpa.service

import android.app.Notification
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class NotificationEventCollector(
    private val targetGroups: Set<String>,
    private val chatApp: ChatApp,
    private val onMessageCollected: (ChatMessage) -> Unit
) {
    companion object {
        private const val TAG = "NotificationEventCollector"
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return
        }

        val pkg = event.packageName?.toString() ?: "unknown"
        if (pkg == "com.example.chaserpa") {
            return
        }
        MessageLog.add("[PKG] $pkg")

        if (pkg != chatApp.packageName) {
            return
        }

        handleNotification(event)
    }

    private fun handleNotification(event: AccessibilityEvent) {
        val parcelableData = event.parcelableData
        if (parcelableData !is Notification) {
            return
        }

        val extras = parcelableData.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: extras.getString("android.title")
        val text = extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence("android.text")

        if (title == null || text == null) {
            return
        }

        if (targetGroups.isNotEmpty() && !targetGroups.contains(title)) {
            return
        }

        val senderAndContent = text.toString()
        val parts = senderAndContent.split(": ", limit = 2)
        val sender = if (parts.size >= 2) parts[0] else "未知"
        val content = if (parts.size >= 2) parts[1] else senderAndContent

        MessageLog.add("[NOTIFY] group=$title, sender=$sender, content=$content")
        onMessageCollected(
            ChatMessage(
                groupName = title,
                sender = sender,
                content = content
            )
        )
    }
}
