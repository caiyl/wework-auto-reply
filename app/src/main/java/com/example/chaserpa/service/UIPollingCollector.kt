package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.chaserpa.data.ConfigRepository

class UIPollingCollector(
    private val service: AccessibilityService,
    private val config: ConfigRepository,
    private val onMessage: (MessagePusher.WeWorkMessage) -> Unit
) {
    companion object {
        private const val TAG = "UIPollingCollector"
        private const val PACKAGE_WEWORK = "com.tencent.wework"
        private const val ID_RECYCLER_VIEW = "com.tencent.wework:id/cxl"
        private const val ID_GROUP_NAME = "com.tencent.wework:id/hrm"
        private const val ID_TIME = "com.tencent.wework:id/g86"
        private const val ID_MESSAGE_SUMMARY = "com.tencent.wework:id/mar"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val listSnapshot = mutableMapOf<String, Pair<String, String>>()
    private var currentInterval = config.pollInterval.toLong()
    private var consecutiveIdle = 0

    fun isRunning(): Boolean = isRunning

    fun start() {
        if (isRunning) return
        isRunning = true
        currentInterval = config.pollInterval.toLong()
        consecutiveIdle = 0
        MessageLog.add("[POLL] UI polling started, interval=${currentInterval}ms")
        scheduleNext()
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        MessageLog.add("[POLL] UI polling stopped")
    }

    private fun scheduleNext() {
        if (!isRunning) return
        handler.postDelayed({ doPoll() }, currentInterval)
    }

    private fun doPoll() {
        if (!isRunning) return
        val root = findWeWorkRoot()
        if (root == null) {
            MessageLog.add("[POLL] WeWork window not found")
            scheduleNext()
            return
        }
        val hasNewMessage = scanMessageList(root)
        if (config.adaptivePoll) {
            if (hasNewMessage) {
                currentInterval = 2000L
                consecutiveIdle = 0
            } else {
                consecutiveIdle++
                if (consecutiveIdle >= 3) {
                    currentInterval = 5000L
                } else {
                    currentInterval = config.pollInterval.toLong()
                }
            }
        }
        scheduleNext()
    }

    private fun scanMessageList(root: AccessibilityNodeInfo): Boolean {
        val recyclerView = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW).firstOrNull() ?: return false
        var hasNewMessage = false
        val currentSnapshot = mutableMapOf<String, Pair<String, String>>()
        for (i in 0 until minOf(recyclerView.childCount, 10)) {
            val item = recyclerView.getChild(i) ?: continue
            val groupNameNode = item.findAccessibilityNodeInfosByViewId(ID_GROUP_NAME).firstOrNull()
            val timeNode = item.findAccessibilityNodeInfosByViewId(ID_TIME).firstOrNull()
            val summaryNode = item.findAccessibilityNodeInfosByViewId(ID_MESSAGE_SUMMARY).firstOrNull()
            val groupName = groupNameNode?.text?.toString() ?: continue
            val time = timeNode?.text?.toString() ?: ""
            val summary = summaryNode?.text?.toString() ?: ""
            currentSnapshot[groupName] = Pair(summary, time)
            if (!config.targetGroups.contains(groupName)) continue
            val last = listSnapshot[groupName]
            if (last == null || last.first != summary || last.second != time) {
                MessageLog.add("[POLL] New message detected in '$groupName': $summary")
                hasNewMessage = true
                readChatDetail(groupName)
            }
        }
        listSnapshot.clear()
        listSnapshot.putAll(currentSnapshot)
        return hasNewMessage
    }

    private fun readChatDetail(groupName: String) {
        // Placeholder for now: log intent
        // Full implementation in Task 9
        MessageLog.add("[POLL] Will read chat detail for '$groupName' (Task 9)")
    }

    private fun findWeWorkRoot(): AccessibilityNodeInfo? {
        val windows = service.windows
        for (window in windows) {
            val root = window.root
            if (root?.packageName?.toString() == PACKAGE_WEWORK && root.childCount >= 3) {
                return root
            }
        }
        for (window in windows) {
            val root = window.root
            if (root?.packageName?.toString() == PACKAGE_WEWORK) {
                return root
            }
        }
        return service.rootInActiveWindow
    }
}
