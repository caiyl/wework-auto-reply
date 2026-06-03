package com.example.chaserpa.service

import android.view.accessibility.AccessibilityEvent

class MessageCollector(
    private val targetGroups: Set<String>,
    private val onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit
) {
    private val notificationCollector = NotificationEventCollector(targetGroups, onMessageCollected)
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        notificationCollector.onAccessibilityEvent(event)
    }
}
