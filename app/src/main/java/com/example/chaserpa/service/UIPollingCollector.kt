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
        private const val MAX_SCAN_ITEMS = 10
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var isRunning = false
    private val listSnapshot = mutableMapOf<String, Pair<String, String>>()
    private var currentInterval = config.pollInterval.toLong()
    private var consecutiveIdle = 0

    fun isRunning(): Boolean = isRunning

    fun start() {
        if (isRunning) return
        isRunning = true
        currentInterval = config.pollInterval.toLong().coerceAtLeast(500L)
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
        try {
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
                        currentInterval = config.pollInterval.toLong().coerceAtLeast(500L)
                    }
                }
            }
        } finally {
            root.recycle()
        }
        scheduleNext()
    }

    private fun scanMessageList(root: AccessibilityNodeInfo): Boolean {
        val recyclerViewNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
        val recyclerView = recyclerViewNodes.firstOrNull() ?: run {
            recyclerViewNodes.forEach { it.recycle() }
            return false
        }
        // Keep recyclerView alive for scanning; recycle the rest of the list
        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()
        var hasNewMessage = false
        val currentSnapshot = mutableMapOf<String, Pair<String, String>>()
        try {
            for (i in 0 until minOf(recyclerView.childCount, MAX_SCAN_ITEMS)) {
                val item = recyclerView.getChild(i) ?: continue
                nodesToRecycle.add(item)
                val groupNameNodes = item.findAccessibilityNodeInfosByViewId(ID_GROUP_NAME)
                val timeNodes = item.findAccessibilityNodeInfosByViewId(ID_TIME)
                val summaryNodes = item.findAccessibilityNodeInfosByViewId(ID_MESSAGE_SUMMARY)
                val groupNameNode = groupNameNodes.firstOrNull()
                val timeNode = timeNodes.firstOrNull()
                val summaryNode = summaryNodes.firstOrNull()
                nodesToRecycle.addAll(groupNameNodes)
                nodesToRecycle.addAll(timeNodes)
                nodesToRecycle.addAll(summaryNodes)
                val groupName = groupNameNode?.text?.toString() ?: continue
                val time = timeNode?.text?.toString() ?: ""
                val summary = summaryNode?.text?.toString() ?: ""
                currentSnapshot[groupName] = Pair(summary, time)
                if (!config.targetGroups.contains(groupName)) continue
                synchronized(listSnapshot) {
                    val last = listSnapshot[groupName]
                    if (last == null || last.first != summary || last.second != time) {
                        MessageLog.add("[POLL] New message detected in '$groupName': $summary")
                        hasNewMessage = true
                        readChatDetail(groupName)
                    }
                }
            }
            synchronized(listSnapshot) {
                listSnapshot.clear()
                listSnapshot.putAll(currentSnapshot)
            }
        } finally {
            nodesToRecycle.forEach { it.recycle() }
            recyclerView.recycle()
            recyclerViewNodes.forEach { if (it !== recyclerView) it.recycle() }
        }
        return hasNewMessage
    }

    private fun readChatDetail(groupName: String) {
        // Placeholder for now: log intent
        // Full implementation in Task 9
        MessageLog.add("[POLL] Will read chat detail for '$groupName' (Task 9)")
    }

    private fun findWeWorkRoot(): AccessibilityNodeInfo? {
        val windows = service.windows
        val fetchedRoots = mutableListOf<AccessibilityNodeInfo>()
        var result: AccessibilityNodeInfo? = null
        try {
            for (window in windows) {
                val root = window.root ?: continue
                fetchedRoots.add(root)
                if (root.packageName?.toString() == PACKAGE_WEWORK && root.childCount >= 3) {
                    result = root
                    break
                }
            }
            if (result == null) {
                for (window in windows) {
                    val root = window.root ?: continue
                    if (!fetchedRoots.contains(root)) {
                        fetchedRoots.add(root)
                    }
                    if (root.packageName?.toString() == PACKAGE_WEWORK) {
                        result = root
                        break
                    }
                }
            }
            if (result == null) {
                result = service.rootInActiveWindow
                if (result != null) fetchedRoots.add(result)
            }
            fetchedRoots.remove(result)
            return result
        } finally {
            fetchedRoots.forEach { it.recycle() }
        }
    }
}
