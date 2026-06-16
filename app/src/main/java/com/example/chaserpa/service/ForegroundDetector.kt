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
        // 只用 WINDOW_STATE_CHANGED 判断前台切换，避免内容刷新导致状态抖动
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            isForeground = (pkg == PACKAGE_WEWORK)
        }
    }

    fun isForeground(): Boolean = isForeground
    fun isBackground(): Boolean = !isForeground
}
