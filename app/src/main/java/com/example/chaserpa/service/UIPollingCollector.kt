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
    private val chatPlatform: ChatPlatform,
    private val onMessageCollected: (ChatMessage) -> Unit,
    private val autoReplyOrchestrator: AutoReplyOrchestrator? = null,
    private val myNickname: String = ""
) {
    companion object {
        private const val TAG = "UIPollingCollector"

        // =================================================================
        // 以下为 vivo 手机企业微信版本的 view ID（通过 uiautomator dump 获取）
        // 注意：不同手机厂商（OPPO/vivo/小米等）、不同系统版本、不同企业微信版本，
        // 这些 resource-id 可能会发生变化。适配新设备时需要重新 dump 确认。
        // =================================================================

        // ---------------- 消息列表页（企业微信首页）----------------
        /** 消息列表 RecyclerView，bounds 约占屏幕 [0,240][1080,2219] */
        private const val ID_RECYCLER_VIEW = "com.tencent.wework:id/czp"
        /** 列表项中的群名 TextView，如 "智能客服测试2群" */
        private const val ID_GROUP_NAME = "com.tencent.wework:id/hrr"
        /** 列表项中的时间 TextView，如 "昨天"、"12分钟前" */
        private const val ID_TIME = "com.tencent.wework:id/g80"
        /** 列表项中的消息摘要 TextView，如 "chase: 7787" */
        private const val ID_MESSAGE_SUMMARY = "com.tencent.wework:id/mdj"

        // ---------------- 群聊详情页 ----------------
        /** 聊天消息列表 ListView（scrollable=true），bounds [0,240][1080,2085] */
        private const val ID_CHAT_LISTVIEW = "com.tencent.wework:id/iju"
        /** 消息气泡中的内容 TextView，如 "1440850817590，诊断一下" */
        private const val ID_CHAT_CONTENT = "com.tencent.wework:id/i9j"
        /** 时间分隔 TextView，如 "昨天  9:30"、"此群为外部群，了解更多" */
        private const val ID_CHAT_TIME = "com.tencent.wework:id/i_d"

        // 昵称节点（如 "chase"、"＠微信"）没有 resource-id（resource-id=""），
        // 需要通过 BFS 遍历气泡内无 id 的 TextView 来提取。

        // ---------------- 底部输入区 ----------------
        // 输入框 hint: "发消息或按住..."，resource-id: i_6，class: EditText
        // 发送按钮: text="发送"，resource-id: i_2，class: Button（输入文字后才出现）
        // 注：输入区 ID 定义在 WeWorkUIAutomator.kt 中

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
    // 使用专门的 Runnable 实例作为轮询 token，start() 只移除它，保留 readChatDetail 的 checkRunnable
    private val pollRunnable = Runnable { doPoll() }
    @Volatile
    private var isRunning = false
    private val listSnapshot = mutableMapOf<String, Pair<String, String>>()
    private val groupTimeMap = mutableMapOf<String, String>()
    private var currentInterval = config.pollInterval.toLong()
    private var consecutiveIdle = 0

    fun isRunning(): Boolean = isRunning

    fun start() {
        if (isRunning) return
        // 只移除轮询 callback，保留 readChatDetail 的 checkRunnable 让它完成
        handler.removeCallbacks(pollRunnable)
        isRunning = true
        currentInterval = config.pollInterval.toLong().coerceAtLeast(500L)
        consecutiveIdle = 0
        MessageLog.add("[POLL] UI polling started, interval=${currentInterval}ms")
        scheduleNext()
    }

    fun stop() {
        isRunning = false
        // 不清空 handler，让正在执行的 readChatDetail/checkRunnable 能正常完成
        MessageLog.add("[POLL] UI polling stopped")
    }

    private fun scheduleNext() {
        if (!isRunning) return
        handler.postDelayed(pollRunnable, currentInterval)
    }

    private fun doPoll() {
        if (!isRunning) return
        if (UiController.isBusy) {
            MessageLog.add("[POLL] UI busy, skip poll")
            scheduleNext()
            return
        }

        // 微信诊断分支：先 dump 当前窗口 UI 树，帮助企业适配
        if (chatPlatform.packageName == "com.tencent.mm") {
            try {
                val root = chatPlatform.findActiveChatRoot(service)
                if (root != null) {
                    MessageLog.add("[POLL] WeChat diagnostic root found, children=${root.childCount}")
                    chatPlatform.extractMessages(root)
                    root.recycle()
                } else {
                    MessageLog.add("[POLL] WeChat diagnostic root not found")
                }
            } catch (e: Exception) {
                MessageLog.add("[POLL] WeChat diagnostic exception: ${e.javaClass.simpleName}: ${e.message}")
            }
            scheduleNext()
            return
        }

        try {
            val root = findWeWorkRoot()
            if (root == null) {
                MessageLog.add("[POLL] WeWork window not found，尝试启动企业微信")
                launchWeWork()
                // vivo 启动较慢，临时延长轮询间隔，等应用真正出现
                currentInterval = 5000L
                scheduleNext()
                return
            }

            // 如果当前在群聊页（有输入框但无RecyclerView），先按Back回到消息列表
            val recyclerViewNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
            val hasRecycler = recyclerViewNodes.isNotEmpty()
            recyclerViewNodes.forEach { it.recycle() }

            if (!hasRecycler) {
                val hasInput = findInputField(root) != null
                root.recycle()
                if (hasInput) {
                    MessageLog.add("[POLL] 当前在群聊页，尝试点击左上角返回按钮")
                    val backRoot = findWeWorkRoot()
                    val returned = if (backRoot != null) {
                        val ok = clickBackButton(backRoot)
                        backRoot.recycle()
                        ok
                    } else false
                    if (!returned) {
                        MessageLog.add("[POLL] 返回按钮未找到，尝试按Back")
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                } else {
                    // 不在群聊页，也不在消息列表页（可能是通讯录/工作台/邮件等），切回消息列表
                    MessageLog.add("[POLL] 当前不在消息列表/群聊页，尝试点击'消息'Tab")
                    val navigated = navigateToMessageTabAcrossWindows()
                    if (!navigated) {
                        MessageLog.add("[POLL] 点击'消息'Tab失败")
                    }
                }
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
        } catch (e: Exception) {
            MessageLog.add("[POLL] doPoll exception: ${e.javaClass.simpleName}: ${e.message}")
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
        } catch (e: Exception) {
            MessageLog.add("[POLL] scanMessageList exception: ${e.javaClass.simpleName}: ${e.message}")
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
        // vivo 系统 Activity 启动慢，需要更长的等待时间和更多重试
        var retries = 12
        val checkRunnable = object : Runnable {
            override fun run() {
                MessageLog.add("[POLL] checkRunnable: retries left=$retries, finding WeWork root...")
                // 页面切换期间必须跳过缓存，否则可能拿到旧窗口（消息列表页而非聊天页）
                val chatRoot = findWeWorkRoot(skipCache = true)
                if (chatRoot == null) {
                    MessageLog.add("[POLL] checkRunnable: findWeWorkRoot returned null")
                    if (retries > 0) {
                        retries--
                        handler.postDelayed(this, 1200)
                        return
                    }
                    MessageLog.add("[POLL] readChatDetail: chat root not found after retries, abort")
                    ensureBackToMessageListThenRelease()
                    return
                }
                MessageLog.add("[POLL] checkRunnable: found chatRoot pkg=${chatRoot.packageName} children=${chatRoot.childCount}")

                val inChat = isInChatScreen(chatRoot, groupName)
                MessageLog.add("[POLL] checkRunnable: isInChatScreen=$inChat")
                if (!inChat) {
                    if (retries > 0) {
                        retries--
                        chatRoot.recycle()
                        MessageLog.add("[POLL] checkRunnable: not in chat yet, will retry")
                        handler.postDelayed(this, 1200)
                        return
                    }
                    MessageLog.add("[POLL] readChatDetail: not in chat screen after retries, abort")
                    chatRoot.recycle()
                    ensureBackToMessageListThenRelease()
                    return
                }

                // Step 4: read chat messages
                val messages = extractChatMessages(chatRoot, groupName)
                MessageLog.add("[POLL] readChatDetail: extracted ${messages.size} messages")
                messages.forEach { msg ->
                    onMessageCollected(msg)
                }
                chatRoot.recycle()

                // Step 5: return to message list
                // vivo 上按 Back 可能直接退出企业微信回到桌面。
                // 群聊页没有底部"消息"Tab，正确方式是点击左上角返回按钮。
                handler.postDelayed({
                    var returned = false
                    val backRoot = findWeWorkRoot()
                    if (backRoot != null) {
                        returned = clickBackButton(backRoot)
                        backRoot.recycle()
                    }
                    if (!returned) {
                        MessageLog.add("[POLL] readChatDetail: 左上角返回按钮未找到，尝试按Back")
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    UiController.release()
                }, 1200)
            }
        }
        // vivo 首次延迟更长，给系统 Instrumentation 完成时间
        handler.postDelayed(checkRunnable, 1500)
    }

    private fun isInChatScreen(root: AccessibilityNodeInfo, groupName: String): Boolean {
        val titleNodes = root.findAccessibilityNodeInfosByText(groupName)
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val titleMaxY = (rootRect.top + rootRect.height() * 0.15).toInt()

        val titleMatch = titleNodes.find {
            val t = it.text?.toString() ?: ""
            if (!t.contains(groupName)) return@find false
            val nodeRect = android.graphics.Rect()
            it.getBoundsInScreen(nodeRect)
            // 标题栏一定在屏幕顶部 15% 区域内；消息列表项在中部，会被过滤
            nodeRect.centerY() <= titleMaxY
        }
        val hasTitle = titleMatch != null
        val matchedTitle = titleMatch?.text?.toString() ?: ""
        titleNodes.forEach { it.recycle() }

        val inputNode = findInputField(root)
        val hasInput = inputNode != null
        inputNode?.recycle()

        MessageLog.add("[POLL] isInChatScreen: titleNodes=${titleNodes.size} hasTitle=$hasTitle matchedTitle='$matchedTitle' hasInput=$hasInput titleMaxY=$titleMaxY")
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

    /**
     * 查找群聊页输入框。
     * vivo 版本 hint 为 "发消息或按住..."（resource-id: i_6，class: EditText）。
     * 由于 findAccessibilityNodeInfosByText 是包含匹配，"发消息" 理论上可匹配到
     * "发消息或按住..."，但此处使用精确匹配（== hint），故需配合 EditText fallback。
     */
    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 旧版本 hint: "发消息" / "添加消息内容" / "输入消息" / "请输入消息"
        // vivo 版本 hint: "发消息或按住..."
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
    ): List<ChatMessage> {
        // Pair<消息, 该消息自己的时间气泡字符串>
        val extracted = mutableListOf<Pair<ChatMessage, String>>()
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
                        ChatMessage(
                            groupName = groupName,
                            sender = actualSender,
                            content = content,
                            time = bubbleTime
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
                            ChatMessage(
                                groupName = groupName,
                                sender = "UI采集",
                                content = text,
                                time = ""
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

    /**
     * 点击群聊页左上角的返回按钮（resource-id: nc9）。
     * vivo 群聊页没有底部"消息"Tab，返回消息列表的正确方式是点左上角返回。
     */
    private fun clickBackButton(root: AccessibilityNodeInfo): Boolean {
        val backNodes = root.findAccessibilityNodeInfosByViewId("com.tencent.wework:id/nc9")
        val backBtn = backNodes.firstOrNull()
        if (backBtn != null && backBtn.isClickable) {
            val result = backBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            MessageLog.add("[POLL] 点击左上角返回按钮: $result")
            backNodes.forEach { if (it !== backBtn) it.recycle() }
            backBtn.recycle()
            return result
        }
        backNodes.forEach { it.recycle() }
        return false
    }

    /**
     * 在所有企业微信窗口中搜索并点击底部"消息"Tab。
     * vivo 上聊天页窗口和消息列表页可能是不同窗口，需遍历 windows。
     */
    private fun navigateToMessageTabAcrossWindows(): Boolean {
        val windows = service.windows
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != chatPlatform.packageName) {
                root.recycle()
                continue
            }
            val clicked = navigateToMessageTab(root)
            root.recycle()
            if (clicked) return true
        }
        return false
    }

    private fun navigateToMessageTab(root: AccessibilityNodeInfo): Boolean {
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val minTop = (rootRect.top + rootRect.height() * 0.85).toInt()

        // 企业微信底部 Tab 栏：TextView 本身不是 clickable 的，父容器才是。
        // vivo 上底部 Tab 文字节点 bounds 可能为 [0,0][0,0]，需向上找 clickable 父节点
        // 再用父节点的 bounds 判断是否在底部区域。
        val msgNodes = root.findAccessibilityNodeInfosByText("消息")
        var clicked = false
        for (node in msgNodes) {
            // 先向上找 clickable 父节点
            var clickableNode: AccessibilityNodeInfo? = null
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < 5) {
                if (current.isClickable) {
                    clickableNode = current
                    break
                }
                current = current.parent
                depth++
            }

            if (clickableNode == null) {
                node.recycle()
                continue
            }

            // 用 clickable 父节点的 bounds 判断是否在底部 Tab 区域
            val clickRect = android.graphics.Rect()
            clickableNode.getBoundsInScreen(clickRect)
            if (clickRect.top < minTop) {
                if (clickableNode !== node) clickableNode.recycle()
                node.recycle()
                continue // 不在底部 Tab 区域，跳过
            }

            val result = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            MessageLog.add("[POLL] 点击'消息'Tab: $result")
            clicked = true
            if (clickableNode !== node) clickableNode.recycle()
            node.recycle()
            if (clicked) break
        }
        return clicked
    }

    /**
     * @param skipCache 页面切换期间（如 readChatDetail 的 checkRunnable）应设为 true，
     *                  避免 WeWorkAccessibilityService.latestWeWorkRoot 缓存未及时更新导致拿到旧窗口。
     */
    private fun findWeWorkRoot(skipCache: Boolean = false): AccessibilityNodeInfo? {
        // vivo 系统 rootInActiveWindow / windows 经常返回 null 或错误窗口。
        // 优先使用 WeWorkAccessibilityService 通过事件缓存的最新根节点，
        // 其次尝试 rootInActiveWindow，最后遍历所有窗口。
        // 关键：必须验证窗口包含主内容特征（RecyclerView 或输入框），
        // 避免选中 vivo 企业微信的侧边栏窗口（resource-id: ix_）。
        if (!skipCache) {
            val cached = ChatAccessibilityService.latestChatRoot
            if (cached != null && cached.packageName?.toString() == chatPlatform.packageName) {
                val hasMain = hasMainContent(cached)
                if (hasMain) {
                    MessageLog.add("[POLL] findWeWorkRoot: using cached root")
                    return AccessibilityNodeInfo.obtain(cached)
                }
            }
        }

        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null) {
            val pkg = activeRoot.packageName?.toString()
            if (pkg == chatPlatform.packageName && hasMainContent(activeRoot)) {
                MessageLog.add("[POLL] findWeWorkRoot: using rootInActiveWindow")
                return activeRoot
            } else {
                activeRoot.recycle()
            }
        }

        val windows = service.windows
        val fetchedRoots = mutableListOf<AccessibilityNodeInfo>()
        var bestRoot: AccessibilityNodeInfo? = null
        var bestChildCount = 0
        var weWorkWindowCount = 0
        try {
            for (window in windows) {
                val root = window.root ?: continue
                fetchedRoots.add(root)
                if (root.packageName?.toString() == chatPlatform.packageName) {
                    weWorkWindowCount++
                    val recyclerNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
                    val hasMainRecycler = recyclerNodes.isNotEmpty()
                    recyclerNodes.forEach { it.recycle() }
                    if (hasMainRecycler) {
                        bestRoot = root
                        bestChildCount = Int.MAX_VALUE
                    } else if (root.childCount > bestChildCount) {
                        bestRoot = root
                        bestChildCount = root.childCount
                    }
                }
            }
            fetchedRoots.remove(bestRoot)
            MessageLog.add("[POLL] findWeWorkRoot: windows=${windows.size} weWorkWindows=$weWorkWindowCount bestRoot=${bestRoot != null} childCount=$bestChildCount")
            return bestRoot
        } finally {
            fetchedRoots.forEach { it.recycle() }
        }
    }

    /**
     * 判断窗口是否包含企业微信主内容区特征（消息列表 RecyclerView 或聊天页输入框）。
     * vivo 版企业微信有左侧边栏窗口，需要过滤掉。
     */
    private fun hasMainContent(root: AccessibilityNodeInfo): Boolean {
        val recyclerNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
        val hasRecycler = recyclerNodes.isNotEmpty()
        recyclerNodes.forEach { it.recycle() }
        if (hasRecycler) return true

        val inputNode = findInputField(root)
        val hasInput = inputNode != null
        inputNode?.recycle()
        return hasInput
    }

    /**
     * readChatDetail 失败后的安全归位：确保回到消息列表再释放 UI-LOCK。
     */
    private fun ensureBackToMessageListThenRelease() {
        MessageLog.add("[POLL] ensureBackToMessageList: 开始归位")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        handler.postDelayed({
            val verifyRoot = findWeWorkRoot()
            if (verifyRoot != null) {
                val hasRecycler = verifyRoot.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW).isNotEmpty()
                verifyRoot.recycle()
                if (!hasRecycler) {
                    MessageLog.add("[POLL] ensureBackToMessageList: Back后未回到消息列表，再按一次")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    handler.postDelayed({
                        val finalRoot = findWeWorkRoot()
                        if (finalRoot != null) {
                            val stillNoRecycler = finalRoot.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW).isEmpty()
                            finalRoot.recycle()
                            if (stillNoRecycler) {
                                MessageLog.add("[POLL] ensureBackToMessageList: 两次Back无效，尝试点击'消息'Tab")
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
    }

    private fun launchWeWork() {
        try {
            val intent = service.packageManager.getLaunchIntentForPackage(chatPlatform.packageName)
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
