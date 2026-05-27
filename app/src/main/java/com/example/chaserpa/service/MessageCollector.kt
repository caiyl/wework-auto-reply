package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MessageCollector(
    private val targetGroups: Set<String>,
    private val onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit
) {
    companion object {
        private const val TAG = "MessageCollector"
        private const val PACKAGE_Wework = "com.tencent.wework"
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

        if (pkg != PACKAGE_Wework) {
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

        MessageLog.add("[CAPTURE] group=$title, sender=$sender, content=$content")
        onMessageCollected(
            MessagePusher.WeWorkMessage(
                groupName = title,
                sender = sender,
                content = content
            )
        )
    }
}
