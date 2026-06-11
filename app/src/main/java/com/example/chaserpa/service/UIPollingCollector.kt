package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.chaserpa.data.ConfigRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class UIPollingCollector(
    private val service: AccessibilityService,
    private val config: ConfigRepository,
    private val onMessage: (MessagePusher.WeWorkMessage) -> Unit,
    private val autoReplyOrchestrator: AutoReplyOrchestrator? = null,
    private val myNickname: String = ""
) {
    companion object {
        private const val TAG = "UIPollingCollector"
        private const val PACKAGE_WEWORK = "com.tencent.wework"
        private const val ID_RECYCLER_VIEW = "com.tencent.wework:id/cxl"
        private const val ID_GROUP_NAME = "com.tencent.wework:id/hrm"
        private const val ID_TIME = "com.tencent.wework:id/g86"
        private const val ID_MESSAGE_SUMMARY = "com.tencent.wework:id/mar"
        private const val ID_CHAT_LISTVIEW = "com.tencent.wework:id/iis"
        private const val ID_CHAT_CONTENT = "com.tencent.wework:id/i8u"
        private const val ID_CHAT_TIME = "com.tencent.wework:id/i9p"
        private const val MAX_SCAN_ITEMS = 10
        private const val MSG_MAX_AGE_MS = 300_000L // 5分钟

        /**
         * 解析企业微信UI时间字符串为时间戳（毫秒）
         * 支持格式: "刚刚", "X分钟前", "HH:mm", "昨天", "yyyy/MM/dd"
         */
        fun parseUiTime(timeStr: String): Long? {
            val now = System.currentTimeMillis()
            return when {
                timeStr == "刚刚" -> now
                timeStr.endsWith("分钟前") -> {
                    val minutes = timeStr.removeSuffix("分钟前").toIntOrNull() ?: return null
                    now - minutes * 60_000L
                }
                timeStr == "昨天" -> {
                    // 返回昨天同一时刻（用于旧消息过滤即可）
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_MONTH, -1)
                    cal.timeInMillis
                }
                timeStr.matches(Regex("\\d{2}:\\d{2}")) -> {
                    val parts = timeStr.split(":")
                    val hour = parts[0].toInt()
                    val minute = parts[1].toInt()
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    // 如果计算出的时间晚于当前时间，说明是昨天的（跨天情况）
                    if (cal.timeInMillis > now) {
                        cal.add(Calendar.DAY_OF_MONTH, -1)
                    } else if (now - cal.timeInMillis > 12 * 60 * 60 * 1000L) {
                        // 如果解析出的时间距离现在超过12小时，说明是昨天的
                        // 例如现在16:00，昨天15:20的消息被解析成今天15:20，相差24小时不到但>12小时
                        cal.add(Calendar.DAY_OF_MONTH, -1)
                    }
                    cal.timeInMillis
                }
                timeStr.matches(Regex("\\d{4}/\\d{2}/\\d{2}")) -> {
                    val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                    sdf.parse(timeStr)?.time
                }
                else -> null
            }
        }

        fun isMessageTooOld(msgTime: Long?): Boolean {
            // 无法解析的时间（如"昨天"、未知格式）一律视为旧消息，保守过滤
            if (msgTime == null) return true
            return System.currentTimeMillis() - msgTime > MSG_MAX_AGE_MS
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var isRunning = false
    private val listSnapshot = mutableMapOf<String, Pair<String, String>>()
    private val groupTimeMap = mutableMapOf<String, String>()
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
        if (UiController.isBusy) {
            MessageLog.add("[POLL] UI busy, skip poll")
            scheduleNext()
            return
        }
        val root = findWeWorkRoot()
        if (root == null) {
            MessageLog.add("[POLL] WeWork window not found，尝试启动企业微信")
            launchWeWork()
            scheduleNext()
            return
        }

        // 如果当前在群聊页（有输入框但无RecyclerView），先按Back回到消息列表
        val recyclerViewNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
        val hasRecycler = recyclerViewNodes.isNotEmpty()
        recyclerViewNodes.forEach { it.recycle() }

        if (!hasRecycler) {
            val hasInput = findInputField(root) != null
            if (hasInput) {
                MessageLog.add("[POLL] 当前在群聊页，按Back回到消息列表")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                root.recycle()
                scheduleNext()
                return
            } else {
                // 不在群聊页，也不在消息列表页（可能是通讯录/工作台/邮件等），切回消息列表
                MessageLog.add("[POLL] 当前不在消息列表/群聊页，尝试点击'消息'Tab")
                navigateToMessageTab(root)
                root.recycle()
                scheduleNext()
                return
            }
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
                groupTimeMap[groupName] = time
                if (!config.targetGroups.contains(groupName)) continue

                // 过滤草稿和自己的自动回复摘要，避免循环触发
                if (summary.startsWith("[草稿]") || summary.contains("后台已收到")) {
                    MessageLog.add("[POLL] 跳过残留/草稿摘要: '$groupName': $summary")
                    continue
                }

                synchronized(listSnapshot) {
                    val last = listSnapshot[groupName]
                    if (last == null || last.first != summary || last.second != time) {
                        // 判断群列表项时间是否超过10分钟
                        val listTime = parseUiTime(time)
                        if (isMessageTooOld(listTime)) {
                            MessageLog.add("[POLL] 群列表时间超过${MSG_MAX_AGE_MS/60000}分钟，不触发: '$groupName' time=$time")
                            continue
                        }
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
        if (!UiController.acquire()) {
            MessageLog.add("[POLL] readChatDetail skipped: UI is busy")
            return
        }
        MessageLog.add("[POLL] readChatDetail start for '$groupName'")

        val root = findWeWorkRoot()
        if (root == null) {
            MessageLog.add("[POLL] readChatDetail: WeWork root not found")
            UiController.release()
            return
        }

        // 安全页面检查：如果当前不在消息列表页（无RecyclerView），尝试切回
        val recyclerViewNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
        if (recyclerViewNodes.isEmpty()) {
            MessageLog.add("[POLL] readChatDetail: 当前不在消息列表页，尝试切回")
            recyclerViewNodes.forEach { it.recycle() }
            navigateToMessageTab(root)
            root.recycle()
            UiController.release()
            return
        }
        val recyclerView = recyclerViewNodes.firstOrNull()
        if (recyclerView == null) {
            MessageLog.add("[POLL] readChatDetail: RecyclerView not found")
            recyclerViewNodes.forEach { it.recycle() }
            root.recycle()
            UiController.release()
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
                    // Remove groupItemNode from nodesToRecycle so it survives past finally
                    groupItemNode?.let { nodesToRecycle.remove(it) }
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
            UiController.release()
            return
        }

        val clickSuccess = groupItemNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        MessageLog.add("[POLL] readChatDetail: click group item result=$clickSuccess")
        if (!clickSuccess) {
            MessageLog.add("[POLL] readChatDetail: click failed, abort")
            groupItemNode.recycle()
            root.recycle()
            UiController.release()
            return
        }
        groupItemNode.recycle()
        root.recycle()

        // Step 2: wait for chat detail page with retry
        var retries = 6
        val checkRunnable = object : Runnable {
            override fun run() {
                val chatRoot = findWeWorkRoot()
                if (chatRoot == null) {
                    if (retries > 0) {
                        retries--
                        handler.postDelayed(this, 600)
                        return
                    }
                    MessageLog.add("[POLL] readChatDetail: chat root not found after retries")
                    UiController.release()
                    return
                }

                val inChat = isInChatScreen(chatRoot, groupName)
                if (!inChat) {
                    if (retries > 0) {
                        retries--
                        chatRoot.recycle()
                        handler.postDelayed(this, 600)
                        return
                    }
                    MessageLog.add("[POLL] readChatDetail: not in chat screen after retries, abort")
                    chatRoot.recycle()
                    UiController.release()
                    return
                }

                // Step 4: read chat messages
                val messages = extractChatMessages(chatRoot, groupName)
                MessageLog.add("[POLL] readChatDetail: extracted ${messages.size} messages")
                messages.forEach { msg ->
                    onMessage(msg)
                }
                chatRoot.recycle()

                // Step 5: return to message list with verification
                handler.postDelayed({
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    MessageLog.add("[POLL] readChatDetail: performed back action")

                    // 检测是否真正回到了消息列表，如果没有则再按一次或点消息Tab
                    handler.postDelayed({
                        val verifyRoot = findWeWorkRoot()
                        if (verifyRoot != null) {
                            val hasRecycler = verifyRoot.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW).isNotEmpty()
                            verifyRoot.recycle()
                            if (!hasRecycler) {
                                MessageLog.add("[POLL] Back后未回到消息列表，再按一次Back")
                                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                                handler.postDelayed({
                                    val finalRoot = findWeWorkRoot()
                                    if (finalRoot != null) {
                                        val stillNoRecycler = finalRoot.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW).isEmpty()
                                        finalRoot.recycle()
                                        if (stillNoRecycler) {
                                            MessageLog.add("[POLL] 两次Back无效，尝试点击'消息'Tab")
                                            val tabRoot = findWeWorkRoot()
                                            if (tabRoot != null) {
                                                navigateToMessageTab(tabRoot)
                                                tabRoot.recycle()
                                            }
                                        }
                                    }
                                    UiController.release()
                                }, 800)
                                return@postDelayed
                            }
                        }
                        UiController.release()
                    }, 800)
                }, 1500)
            }
        }
        handler.postDelayed(checkRunnable, 600)
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
        val parentsToRecycle = mutableListOf<AccessibilityNodeInfo>()
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        var found = false
        while (current != null && depth < 10) {
            val parent = current.parent ?: break
            parentsToRecycle.add(parent)
            val parentClass = parent.className?.toString() ?: ""
            if (parentClass.contains("RecyclerView") || parentClass.contains("ListView")) {
                found = true
                break
            }
            current = parent
            depth++
        }
        parentsToRecycle.forEach { it.recycle() }
        return found
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
        // Fallback: bottom EditText（企业微信输入区约在屏幕 55%-60%，不能设 70%）
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val minTop = (rootRect.top + rootRect.height() * 0.45).toInt()
        val nodeRect = android.graphics.Rect()
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            if (node.className?.toString() == "android.widget.EditText") {
                node.getBoundsInScreen(nodeRect)
                if (nodeRect.top >= minTop) {
                    nodesToRecycle.forEach { it.recycle() }
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    nodesToRecycle.add(child)
                    deque.add(child)
                }
            }
        }
        nodesToRecycle.forEach { it.recycle() }
        return null
    }

    private fun extractChatMessages(
        root: AccessibilityNodeInfo,
        groupName: String
    ): List<MessagePusher.WeWorkMessage> {
        // Pair<消息, 该消息自己的时间气泡字符串>
        val extracted = mutableListOf<Pair<MessagePusher.WeWorkMessage, String>>()
        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()

        // 策略1: 尝试按消息气泡结构精确解析（ListView id=iis + 子项 RelativeLayout）
        val chatListNodes = root.findAccessibilityNodeInfosByViewId(ID_CHAT_LISTVIEW)
        val chatList = chatListNodes.firstOrNull()
        if (chatList != null) {
            nodesToRecycle.add(chatList)
            for (i in 0 until chatList.childCount) {
                val bubble = chatList.getChild(i) ?: continue
                nodesToRecycle.add(bubble)

                // 1) 用 viewId 递归查找消息内容（i8u 嵌套在第6层）
                val contentNodes = bubble.findAccessibilityNodeInfosByViewId(ID_CHAT_CONTENT)
                val content = contentNodes.firstOrNull()?.text?.toString()
                nodesToRecycle.addAll(contentNodes)

                // 2) 用 viewId 查找时间戳（记录每条消息自己的时间气泡）
                val timeNodes = bubble.findAccessibilityNodeInfosByViewId(ID_CHAT_TIME)
                val bubbleTime = timeNodes.firstOrNull()?.text?.toString() ?: ""
                nodesToRecycle.addAll(timeNodes)

                // 3) 在气泡内部 BFS 查找无 id 的 TextView（昵称 / @微信）
                val nicknameParts = mutableListOf<String>()
                val bfsDeque = java.util.ArrayDeque<AccessibilityNodeInfo>()
                bfsDeque.add(bubble)
                while (bfsDeque.isNotEmpty()) {
                    val node = bfsDeque.poll() ?: continue
                    val cls = node.className?.toString() ?: ""
                    if (cls.contains("TextView")) {
                        val text = node.text?.toString() ?: ""
                        val id = node.viewIdResourceName?.toString() ?: ""
                        if (text.isNotBlank() && id.isEmpty()) {
                            nicknameParts.add(text)
                        }
                    }
                    for (k in 0 until node.childCount) {
                        val child = node.getChild(k)
                        if (child != null) {
                            nodesToRecycle.add(child)
                            bfsDeque.add(child)
                        }
                    }
                }

                if (content != null && content.isNotBlank()) {
                    // 无昵称 = 自己发的消息，跳过
                    if (nicknameParts.isEmpty()) {
                        MessageLog.add("[POLL] 过滤自己消息(无昵称): ${content.take(30)}")
                        continue
                    }

                    // 检查是否是外部微信客户（包含 @微信）
                    val isExternalWeChat = nicknameParts.any { it.contains("微信") }
                    if (!isExternalWeChat) {
                        val actualSender = nicknameParts.firstOrNull() ?: "未知"
                        MessageLog.add("[POLL] 过滤非外部客户: sender=$actualSender content=${content.take(30)}")
                        continue
                    }

                    // 拼接完整发送者名称，如 "chase@微信"
                    val actualSender = nicknameParts.joinToString("")

                    extracted.add(
                        MessagePusher.WeWorkMessage(
                            groupName = groupName,
                            sender = actualSender,
                            content = content,
                            timestamp = System.currentTimeMillis()
                        ) to bubbleTime
                    )
                }
            }
        } else {
            // 策略2: fallback 到旧的 BFS 方式（兼容不同版本企业微信）
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
                        // Fallback 策略：只保留包含 @微信 的外部客户消息
                        if (!text.contains("微信")) {
                            MessageLog.add("[POLL] 过滤非外部客户(FB): ${text.take(30)}")
                            continue
                        }
                        extracted.add(
                            MessagePusher.WeWorkMessage(
                                groupName = groupName,
                                sender = "UI采集",
                                content = text,
                                timestamp = System.currentTimeMillis()
                            ) to ""
                        )
                    }
                }
                for (k in 0 until node.childCount) {
                    val child = node.getChild(k)
                    if (child != null) {
                        nodesToRecycle.add(child)
                        deque.add(child)
                    }
                }
            }
        }

        nodesToRecycle.forEach { it.recycle() }
        chatList?.recycle()
        chatListNodes.forEach { if (it !== chatList) it.recycle() }

        // 只取最后一条（最新消息），并做时间判断
        val last = extracted.lastOrNull()
        if (last != null) {
            val (message, bubbleTime) = last
            if (bubbleTime.isNotEmpty()) {
                val t = parseUiTime(bubbleTime)
                if (t == null || System.currentTimeMillis() - t > MSG_MAX_AGE_MS) {
                    MessageLog.add("[POLL] 最新消息有时间但超${MSG_MAX_AGE_MS/60000}分钟，过滤: ${message.content.take(30)}")
                    return emptyList()
                }
            }
            MessageLog.add("[POLL] 提取到最新消息: ${message.sender} -> ${message.content.take(40)}")
            return listOf(message)
        }
        return emptyList()
    }

    private fun navigateToMessageTab(root: AccessibilityNodeInfo): Boolean {
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val minTop = (rootRect.top + rootRect.height() * 0.85).toInt()

        // 企业微信底部 Tab 栏：TextView 本身不是 clickable 的，父容器才是。
        // 先通过文字找到底部区域的"消息"节点，再向上找 clickable 父节点点击。
        val msgNodes = root.findAccessibilityNodeInfosByText("消息")
        var clicked = false
        for (node in msgNodes) {
            val nodeRect = android.graphics.Rect()
            node.getBoundsInScreen(nodeRect)
            if (nodeRect.top < minTop) {
                node.recycle()
                continue // 不在底部 Tab 区域，跳过
            }

            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < 5) {
                if (current.isClickable) {
                    val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    MessageLog.add("[POLL] 点击'消息'Tab: $result")
                    clicked = true
                    break
                }
                val parent = current.parent
                if (parent != null && current !== node) {
                    current.recycle()
                }
                current = parent
                depth++
            }
            if (current != null && current !== node && !clicked) {
                current.recycle()
            }
            node.recycle()
            if (clicked) break
        }
        return clicked
    }

    private fun findWeWorkRoot(): AccessibilityNodeInfo? {
        val windows = service.windows
        val fetchedRoots = mutableListOf<AccessibilityNodeInfo>()
        var bestRoot: AccessibilityNodeInfo? = null
        var bestChildCount = 0
        try {
            // 选择 childCount 最大的企业微信窗口（通常是主窗口）
            for (window in windows) {
                val root = window.root ?: continue
                fetchedRoots.add(root)
                if (root.packageName?.toString() == PACKAGE_WEWORK && root.childCount > bestChildCount) {
                    bestRoot = root
                    bestChildCount = root.childCount
                }
            }
            if (bestRoot == null) {
                bestRoot = service.rootInActiveWindow
                if (bestRoot != null) fetchedRoots.add(bestRoot)
            }
            fetchedRoots.remove(bestRoot)
            return bestRoot
        } finally {
            fetchedRoots.forEach { it.recycle() }
        }
    }

    private fun launchWeWork() {
        try {
            val intent = service.packageManager.getLaunchIntentForPackage(PACKAGE_WEWORK)
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                service.startActivity(intent)
                MessageLog.add("[POLL] 已启动企业微信")
            } else {
                MessageLog.add("[POLL] 无法获取企业微信启动Intent")
            }
        } catch (e: Exception) {
            MessageLog.add("[POLL] 启动企业微信异常: ${e.message}")
        }
    }
}
