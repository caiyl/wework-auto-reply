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
        MessageLog.add("[POLL] readChatDetail start for '$groupName'")

        val root = findWeWorkRoot()
        if (root == null) {
            MessageLog.add("[POLL] readChatDetail: WeWork root not found")
            return
        }

        // Step 1: find RecyclerView and click the group item
        val recyclerViewNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
        val recyclerView = recyclerViewNodes.firstOrNull()
        if (recyclerView == null) {
            MessageLog.add("[POLL] readChatDetail: RecyclerView not found")
            recyclerViewNodes.forEach { it.recycle() }
            root.recycle()
            return
        }

        var groupItemNode: AccessibilityNodeInfo? = null
        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (i in 0 until minOf(recyclerView.childCount, MAX_SCAN_ITEMS)) {
                val item = recyclerView.getChild(i) ?: continue
                nodesToRecycle.add(item)
                val groupNameNodes = item.findAccessibilityNodeInfosByViewId(ID_GROUP_NAME)
                val nameNode = groupNameNodes.firstOrNull()
                nodesToRecycle.addAll(groupNameNodes)
                if (nameNode?.text?.toString() == groupName) {
                    // find clickable parent
                    var current: AccessibilityNodeInfo? = item
                    var depth = 0
                    while (current != null && depth < 10) {
                        if (current.isClickable) {
                            groupItemNode = current
                            break
                        }
                        val parent = current.parent
                        if (parent != null) {
                            nodesToRecycle.add(parent)
                        }
                        current = parent
                        depth++
                    }
                    break
                }
            }
        } finally {
            nodesToRecycle.forEach { it.recycle() }
            recyclerView.recycle()
            recyclerViewNodes.forEach { if (it !== recyclerView) it.recycle() }
        }

        if (groupItemNode == null) {
            MessageLog.add("[POLL] readChatDetail: clickable group item not found for '$groupName'")
            root.recycle()
            return
        }

        val clickSuccess = groupItemNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        MessageLog.add("[POLL] readChatDetail: click group item result=$clickSuccess")
        if (!clickSuccess) {
            MessageLog.add("[POLL] readChatDetail: click failed, abort")
            groupItemNode.recycle()
            root.recycle()
            return
        }
        groupItemNode.recycle()
        root.recycle()

        // Step 2: wait for chat detail page
        handler.postDelayed({
            // Step 3: verify chat detail page
            val chatRoot = findWeWorkRoot()
            if (chatRoot == null) {
                MessageLog.add("[POLL] readChatDetail: chat root not found after wait")
                return@postDelayed
            }

            val inChat = isInChatScreen(chatRoot, groupName)
            if (!inChat) {
                MessageLog.add("[POLL] readChatDetail: not in chat screen for '$groupName', abort")
                chatRoot.recycle()
                return@postDelayed
            }

            // Step 4: read chat messages
            val messages = extractChatMessages(chatRoot, groupName)
            MessageLog.add("[POLL] readChatDetail: extracted ${messages.size} messages")
            messages.forEach { msg ->
                onMessage(msg)
            }
            chatRoot.recycle()

            // Step 5: return to message list
            handler.postDelayed({
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                MessageLog.add("[POLL] readChatDetail: performed back action")
            }, 1500)
        }, 2000)
    }

    private fun isInChatScreen(root: AccessibilityNodeInfo, groupName: String): Boolean {
        val titleNodes = root.findAccessibilityNodeInfosByText(groupName)
        val hasTitle = titleNodes.any {
            val t = it.text?.toString() ?: ""
            t.contains(groupName) && !isNodeInRecyclerView(it)
        }
        titleNodes.forEach { it.recycle() }

        val hasInput = findInputField(root) != null
        return hasTitle && hasInput
    }

    private fun isNodeInRecyclerView(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 10) {
            val parent = current.parent
            if (parent?.className?.toString()?.contains("RecyclerView") == true ||
                parent?.className?.toString()?.contains("ListView") == true) {
                parent.recycle()
                return true
            }
            current = parent
            depth++
        }
        return false
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hints = listOf("发消息", "添加消息内容", "输入消息", "请输入消息")
        for (hint in hints) {
            val nodes = root.findAccessibilityNodeInfosByText(hint)
            val match = nodes.find { it.text?.toString() == hint }
            if (match != null) {
                nodes.forEach { if (it !== match) it.recycle() }
                return match
            }
            nodes.forEach { it.recycle() }
        }
        // Fallback: bottom EditText
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val minTop = (rootRect.top + rootRect.height() * 0.7).toInt()
        val nodeRect = android.graphics.Rect()
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            if (node.className?.toString() == "android.widget.EditText") {
                node.getBoundsInScreen(nodeRect)
                if (nodeRect.top >= minTop) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        return null
    }

    private fun extractChatMessages(
        root: AccessibilityNodeInfo,
        groupName: String
    ): List<MessagePusher.WeWorkMessage> {
        val messages = mutableListOf<MessagePusher.WeWorkMessage>()
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        val inputHints = setOf("发消息", "添加消息内容", "输入消息", "请输入消息")
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            val cls = node.className?.toString() ?: ""
            if (cls.contains("TextView")) {
                val text = node.text?.toString() ?: ""
                if (text.isNotBlank() &&
                    text != groupName &&
                    !text.contains(groupName) &&
                    text !in inputHints &&
                    isNodeInRecyclerView(node)
                ) {
                    messages.add(
                        MessagePusher.WeWorkMessage(
                            groupName = groupName,
                            sender = "UI采集",
                            content = text,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        return messages
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
