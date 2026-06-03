package com.example.chaserpa.service

import android.view.accessibility.AccessibilityEvent

class ForegroundDetector {
    companion object {
        private const val PACKAGE_WEWORK = "com.tencent.wework"
    }

    @Volatile
    private var isForeground: Boolean = false

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                isForeground = (pkg == PACKAGE_WEWORK)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg == PACKAGE_WEWORK) {
                    isForeground = true
                }
            }
        }
    }

    fun isForeground(): Boolean = isForeground
    fun isBackground(): Boolean = !isForeground
}
