package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.chaserpa.data.ConfigRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * UI 轮询采集器。
 *
 * 这个类通过无障碍服务定时遍历企业微信的 UI 树，从消息列表和群聊详情页中提取消息。
 * 主要用于弥补 NotificationEventCollector 的不足：
 * 1. 企业微信在前台运行时通常不会弹出通知；
 * 2. 部分 ROM（如 vivo ColorOS）在息屏或后台时通知事件可能丢失。
 *
 * 工作流程：
 * 1. 定时查找企业微信窗口；
 * 2. 扫描消息列表 RecyclerView，发现目标群有新消息摘要时点击进入；
 * 3. 在群聊详情页提取最新消息；
 * 4. 返回消息列表，继续下一轮。
 *
 * Kotlin 语法提示：
 * - 构造函数中的 onMessage: (MessagePusher.WeWorkMessage) -> Unit 是一个函数类型参数，
 *   作为消息提取后的回调。
 * - autoReplyOrchestrator: AutoReplyOrchestrator? = null 表示可选参数，默认 null。
 */
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
        /** 列表项中的时间 TextView，如 "昨天”、“12分钟前" */
        private const val ID_TIME = "com.tencent.wework:id/g80"
        /** 列表项中的消息摘要 TextView，如 "chase: 7787" */
        private const val ID_MESSAGE_SUMMARY = "com.tencent.wework:id/mdj"

        // ---------------- 群聊详情页 ----------------
        /** 聊天消息列表 ListView（scrollable=true），bounds [0,240][1080,2085] */
        private const val ID_CHAT_LISTVIEW = "com.tencent.wework:id/iju"
        /** 消息气泡中的内容 TextView，如 "1440850817590，诊断一下" */
        private const val ID_CHAT_CONTENT = "com.tencent.wework:id/i9j"
        /** 时间分隔 TextView，如 "昨天  9:30”、“此群为外部群，了解更多" */
        private const val ID_CHAT_TIME = "com.tencent.wework:id/i_d"

        // 昵称节点（如 "chase"、"＠微信"）没有 resource-id（resource-id=""），
        // 需要通过 BFS 遍历气泡内无 id 的 TextView 来提取。

        // ---------------- 底部输入区 ----------------
        // 输入框 hint: "发消息或按住..."，resource-id: i_6，class: EditText
        // 发送按钮: text="发送"，resource-id: i_2，class: Button（输入文字后才出现）
        // 注：输入区 ID 定义在 WeWorkUIAutomator.kt 中

        private const val MAX_SCAN_ITEMS = 10
        private const val MSG_MAX_AGE_MS = 600_000L // 10分钟，超过此时间的消息视为旧消息，不再触发读取
        private const val MAX_CHAT_READ_MS = 20_000L // 进群读取整体超时 20 秒

        // 跨 UIPollingCollector 实例保留消息列表快照，避免 Worker 重启后重复触发同一消息
        private var persistedListSnapshot: Map<String, Pair<String, String>> = emptyMap()

        /**
         * 解析企业微信UI时间字符串为时间戳（毫秒）
         * 支持格式: "刚刚", "X分钟前", "HH:mm", "昨天", "yyyy/MM/dd"
         *
         * Kotlin 语法提示：
         * - when 是多分支表达式，类似 Java 的 switch，但更强大。
         * - timeStr.endsWith("分钟前") 是 Kotlin 字符串扩展函数。
         * - toIntOrNull() 尝试把字符串转整数，失败返回 null，配合 ?: return null 安全退出。
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

        /**
         * 判断消息是否太旧。
         * 无法解析的时间（如"昨天"、未知格式）一律视为旧消息，保守过滤。
         */
        fun isMessageTooOld(msgTime: Long?): Boolean {
            if (msgTime == null) return true
            return System.currentTimeMillis() - msgTime > MSG_MAX_AGE_MS
        }
    }

    // Handler 用于主线程调度轮询
    private val handler = Handler(Looper.getMainLooper())
    // 使用专门的 Runnable 实例作为轮询 token，start() 只移除它，保留 readChatDetail 的 checkRunnable
    private val pollRunnable = Runnable { doPoll() }
    @Volatile
    private var isRunning = false
    // 消息列表快照：群名 -> (消息摘要, 时间)
    private val listSnapshot = mutableMapOf<String, Pair<String, String>>()
    private val groupTimeMap = mutableMapOf<String, String>()
    private var currentInterval = config.pollInterval.toLong()
    // 连续空闲轮询次数，用于自适应频率
    private var consecutiveIdle = 0
    // 最近一次尝试启动企业微信的时间，用于防止反复启动
    private var lastLaunchTime = 0L
    // 启动冷却期：8 秒内不重复启动，给企业微信留出启动时间
    private val LAUNCH_COOLDOWN_MS = 8000L

    fun isRunning(): Boolean = isRunning

    /**
     * 启动轮询。
     */
    fun start() {
        if (isRunning) return
        // 只移除轮询 callback，保留 readChatDetail 的 checkRunnable 让它完成
        handler.removeCallbacks(pollRunnable)
        isRunning = true
        currentInterval = config.pollInterval.toLong().coerceAtLeast(500L)
        consecutiveIdle = 0
        // 恢复上次快照，避免 Worker 重启后重复触发同一消息
        synchronized(listSnapshot) {
            listSnapshot.clear()
            listSnapshot.putAll(persistedListSnapshot)
        }
        MessageLog.add("[POLL] UI polling started, interval=${currentInterval}ms, snapshotSize=${persistedListSnapshot.size}")
        scheduleNext()
    }

    /**
     * 停止轮询。
     */
    fun stop() {
        isRunning = false
        // 不清空 handler，让正在执行的 readChatDetail/checkRunnable 能正常完成
        // 保存当前快照，供下次 start() 恢复，避免重复触发
        synchronized(listSnapshot) {
            persistedListSnapshot = listSnapshot.toMap()
        }
        MessageLog.add("[POLL] UI polling stopped, snapshotSize=${persistedListSnapshot.size}")
    }

    private fun scheduleNext() {
        if (!isRunning) return
        handler.postDelayed(pollRunnable, currentInterval)
    }

    /**
     * 单次轮询主逻辑。
     */
    private fun doPoll() {
        if (!isRunning) return
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[POLL] 监控已停止，跳过本次轮询")
            scheduleNext()
            return
        }
        if (UiController.isBusy) {
            MessageLog.add("[POLL] UI busy, skip poll")
            scheduleNext()
            return
        }
        try {
            val root = findWeWorkRoot()
            if (root == null) {
                val now = System.currentTimeMillis()
                if (now - lastLaunchTime > LAUNCH_COOLDOWN_MS) {
                    MessageLog.add("[POLL] WeWork window not found，尝试启动企业微信")
                    launchWeWork()
                    lastLaunchTime = now
                } else {
                    MessageLog.add("[POLL] WeWork window not found，启动冷却中，跳过")
                }
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

    /**
     * 扫描消息列表，发现目标群新消息后进入群聊详情读取。
     */
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
        val failedGroups = mutableSetOf<String>()
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
                if (!config.targetGroups.contains(groupName)) {
                    MessageLog.add("[MSG] trace=${groupName}-unknown-${summary.take(20)} status=过滤_非目标群 reason=群不在目标列表")
                    continue
                }


                synchronized(listSnapshot) {
                    val last = listSnapshot[groupName]
                    val isDraft = summary.startsWith("[草稿]") || summary.contains("后台已收到")
                    val changed = last == null || last.first != summary || last.second != time

                    if (isDraft) {
                        // 草稿摘要会掩盖群里的新消息，目标群需要主动进群检查
                        if (changed) {
                            MessageLog.add("[POLL] 目标群出现草稿摘要，主动进群检查: '$groupName': $summary")
                            MessageLog.add("[MSG] trace=${groupName}-unknown-${summary.take(20)} status=草稿_进入检查 reason=草稿摘要变化")
                            hasNewMessage = true
                            val acquired = readChatDetail(groupName, clearDraft = true)
                            if (!acquired) {
                                failedGroups.add(groupName)
                                MessageLog.add("[MSG] trace=${groupName}-unknown-${summary.take(20)} status=读取失败 reason=草稿群详情读取失败")
                            }
                        } else {
                            MessageLog.add("[POLL] 草稿摘要未变化，跳过: '$groupName': $summary")
                            MessageLog.add("[MSG] trace=${groupName}-unknown-${summary.take(20)} status=过滤_草稿未变化 reason=草稿摘要未变化")
                        }
                        continue
                    }

                    if (changed) {
                        // 判断群列表项时间是否超过10分钟
                        val listTime = parseUiTime(time)
                        if (isMessageTooOld(listTime)) {
                            MessageLog.add("[POLL] 群列表时间超过${MSG_MAX_AGE_MS/60000}分钟，不触发: '$groupName' time=$time")
                            MessageLog.add("[MSG] trace=${groupName}-unknown-${summary.take(20)} status=过滤_时间过期 reason=列表时间超过${MSG_MAX_AGE_MS/60000}分钟 time=$time")
                            continue
                        }
                        MessageLog.add("[POLL] New message detected in '$groupName': $summary")
                        MessageLog.add("[MSG] trace=${groupName}-unknown-${summary.take(20)} status=列表变化 reason=列表摘要变化")
                        hasNewMessage = true
                        val acquired = readChatDetail(groupName)
                        if (!acquired) {
                            failedGroups.add(groupName)
                            MessageLog.add("[MSG] trace=${groupName}-unknown-${summary.take(20)} status=读取失败 reason=群详情读取失败")
                        }
                    }
                }
            }
            synchronized(listSnapshot) {
                listSnapshot.clear()
                listSnapshot.putAll(currentSnapshot.filterKeys { it !in failedGroups })
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

    /**
     * 进入指定群聊详情页并提取最新消息。
     */
    private fun readChatDetail(groupName: String, clearDraft: Boolean = false): Boolean {
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[POLL] 监控已停止，不读取群详情")
            return false
        }
        if (!UiController.acquire()) {
            MessageLog.add("[POLL] readChatDetail skipped: UI is busy")
            return false
        }
        val readStartTime = System.currentTimeMillis()
        MessageLog.add("[POLL] readChatDetail start for '$groupName'")

        val root = findWeWorkRoot()
        if (root == null) {
            MessageLog.add("[POLL] readChatDetail: WeWork root not found")
            UiController.release()
            return true
        }

        val recyclerViewNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
        if (recyclerViewNodes.isEmpty()) {
            MessageLog.add("[POLL] readChatDetail: 当前不在消息列表页，尝试切回")
            recyclerViewNodes.forEach { it.recycle() }
            navigateToMessageTab(root)
            root.recycle()
            UiController.release()
            return true
        }
        val recyclerView = recyclerViewNodes.firstOrNull()
        if (recyclerView == null) {
            MessageLog.add("[POLL] readChatDetail: RecyclerView not found")
            recyclerViewNodes.forEach { it.recycle() }
            root.recycle()
            UiController.release()
            return true
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
            return true
        }

        val clickSuccess = groupItemNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        MessageLog.add("[POLL] readChatDetail: click group item result=$clickSuccess")
        if (!clickSuccess) {
            MessageLog.add("[POLL] readChatDetail: click failed, abort")
            groupItemNode.recycle()
            root.recycle()
            UiController.release()
            return true
        }
        groupItemNode.recycle()
        root.recycle()

        // Step 2: wait for chat detail page with retry
        // 调试期间减少重试次数，快速失败以便观察问题
        var retries = 3
        var skipCache = false
        val checkRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - readStartTime
                if (elapsed > MAX_CHAT_READ_MS) {
                    MessageLog.add("[POLL] readChatDetail: 整体超时 ${elapsed}ms，放弃并释放锁")
                    ensureBackToMessageListThenRelease()
                    return
                }
                MessageLog.add("[POLL] checkRunnable: retries left=$retries, elapsed=${elapsed}ms, finding WeWork root...")
                // 先尝试缓存（vivo rootInActiveWindow 不可靠），如果缓存不是聊天页再用 skipCache
                var chatRoot = findWeWorkRoot(skipCache = skipCache)
                if (chatRoot == null) {
                    MessageLog.add("[POLL] checkRunnable: findWeWorkRoot returned null")
                    if (retries > 0) {
                        retries--
                        skipCache = true
                        handler.postDelayed(this, 500)
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
                    chatRoot.recycle()
                    if (retries > 0) {
                        retries--
                        skipCache = true
                        MessageLog.add("[POLL] checkRunnable: not in chat yet, will retry")
                        handler.postDelayed(this, 500)
                        return
                    }
                    MessageLog.add("[POLL] readChatDetail: not in chat screen after retries, abort")
                    ensureBackToMessageListThenRelease()
                    return
                }

                // Step 4: read chat messages
                val messages = extractChatMessages(chatRoot, groupName)
                MessageLog.add("[POLL] readChatDetail: extracted ${messages.size} messages")
                messages.forEach { msg ->
                    onMessage(msg)
                }

                // Step 4.5: clear reply draft to prevent message list summary stuck
                if (clearDraft) {
                    clearInputDraftIfNeeded(chatRoot)
                }

                chatRoot.recycle()

                // Step 5: return to message列表
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
                }, 300)
            }
        }
        // vivo 首次延迟更长，给系统 Instrumentation 完成时间
        handler.postDelayed(checkRunnable, 1500)
        return true
    }

    /**
     * 判断当前是否在指定群聊页。
     */
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

    /**
     * 判断节点是否位于 RecyclerView/ListView 内部。
     */
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

    /**
     * 清理群聊页输入框中残留的回复草稿。
     * 回复失败后未发送的文字会让企业微信消息列表摘要一直显示"[草稿]..."，
     * 导致后续新消息无法被检测到，因此需要在返回列表页前清理。
     */
    private fun clearInputDraftIfNeeded(root: AccessibilityNodeInfo) {
        val inputNode = findInputField(root) ?: run {
            MessageLog.add("[POLL] clearInputDraft: input not found, skip")
            return
        }
        val currentText = inputNode.text?.toString() ?: ""
        val hint = inputNode.hintText?.toString() ?: ""
        if (currentText.isEmpty() || currentText == hint) {
            MessageLog.add("[POLL] clearInputDraft: no draft text, skip")
            inputNode.recycle()
            return
        }

        MessageLog.add("[POLL] clearInputDraft: found draft text='$currentText', trying to clear")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val setTextResult = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        MessageLog.add("[POLL] clearInputDraft: ACTION_SET_TEXT result=$setTextResult")
        if (!setTextResult) {
            val clickResult = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            MessageLog.add("[POLL] clearInputDraft: ACTION_CLICK result=$clickResult")
            if (clickResult) {
                val selectArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
                }
                val selectResult = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
                MessageLog.add("[POLL] clearInputDraft: ACTION_SET_SELECTION result=$selectResult")
                if (selectResult) {
                    val cutResult = inputNode.performAction(AccessibilityNodeInfo.ACTION_CUT)
                    MessageLog.add("[POLL] clearInputDraft: ACTION_CUT result=$cutResult")
                }
            }
        }
        inputNode.recycle()
    }

    /**
     * 从群聊详情页提取消息。
     *
     * 时间上下文传递：气泡有时间则更新 lastKnownTime，无时间则继承上一个。
     * 无时间气泡 = 时间气泡已滚出屏幕 = 旧消息，直接丢弃。
     * 全部气泡都没有时间时，只取最后一条作为兜底。
     */
    private fun extractChatMessages(
        root: AccessibilityNodeInfo,
        groupName: String
    ): List<MessagePusher.WeWorkMessage> {
        val result = mutableListOf<MessagePusher.WeWorkMessage>()
        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()
        var lastKnownTime: Long? = null
        var hasAnyTime = false
        var lastPendingMsg: MessagePusher.WeWorkMessage? = null
        var lastValidMsg: MessagePusher.WeWorkMessage? = null
        var lastValidMsgTime: Long? = null

        val chatListNodes = root.findAccessibilityNodeInfosByViewId(ID_CHAT_LISTVIEW)
        val chatList = chatListNodes.firstOrNull()
        if (chatList != null) {
            nodesToRecycle.add(chatList)
            for (i in 0 until chatList.childCount) {
                val bubble = chatList.getChild(i) ?: continue
                nodesToRecycle.add(bubble)

                val contentNodes = bubble.findAccessibilityNodeInfosByViewId(ID_CHAT_CONTENT)
                val content = contentNodes.firstOrNull()?.text?.toString()
                nodesToRecycle.addAll(contentNodes)

                val timeNodes = bubble.findAccessibilityNodeInfosByViewId(ID_CHAT_TIME)
                val bubbleTime = timeNodes.firstOrNull()?.text?.toString() ?: ""
                nodesToRecycle.addAll(timeNodes)
                if (bubbleTime.isNotEmpty()) {
                    parseUiTime(bubbleTime)?.let {
                        lastPendingMsg?.let { old ->
                            MessageLog.add("[POLL] 发现时间气泡，丢弃无时间气泡缓存: ${old.sender} -> ${old.content.take(40)}")
                            MessageLog.add("[MSG] trace=${messageTraceKey(old)} status=丢弃 reason=发现时间气泡丢弃暂存")
                        }
                        lastKnownTime = it
                        if (lastValidMsgTime == null) {
                            lastValidMsgTime = it
                        }
                        hasAnyTime = true
                        lastPendingMsg = null
                    }
                }

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
                    if (nicknameParts.isEmpty()) {
                        MessageLog.add("[POLL] 过滤自己消息(无昵称): ${content.take(30)}")
                        MessageLog.add("[MSG] trace=${groupName}-unknown-${content.take(20)} status=过滤_自己发送 reason=气泡无昵称")
                        continue
                    }

                    val isExternalWeChat = nicknameParts.any { it.contains("微信") }
                    if (!isExternalWeChat) {
                        val actualSender = nicknameParts.firstOrNull() ?: "未知"
                        MessageLog.add("[POLL] 过滤非外部客户: sender=$actualSender content=${content.take(30)}")
                        MessageLog.add("[MSG] trace=${groupName}-${actualSender}-${content.take(20)} status=过滤_非外部客户 reason=昵称不含微信")
                        continue
                    }

                    val actualSender = nicknameParts.joinToString("")

                    val msg = MessagePusher.WeWorkMessage(
                        groupName = groupName,
                        sender = actualSender,
                        content = content,
                        timestamp = System.currentTimeMillis()
                    )
                    lastValidMsg = msg
                    lastValidMsgTime = lastKnownTime

                    if (!hasAnyTime) {
                        lastPendingMsg?.let { old ->
                            MessageLog.add("[POLL] 无时间气泡消息被覆盖: ${old.sender} -> ${old.content.take(40)}")
                            MessageLog.add("[MSG] trace=${messageTraceKey(old)} status=丢弃 reason=被新无时间气泡消息覆盖")
                        }
                        MessageLog.add("[POLL] 无时间气泡，暂存消息: ${msg.sender} -> ${msg.content.take(40)}")
                        MessageLog.add("[MSG] trace=${messageTraceKey(msg)} status=暂存_无时间气泡 reason=等待时间气泡")
                        lastPendingMsg = msg
                    } else {
                        if (isMessageTooOld(lastKnownTime)) {
                            MessageLog.add("[POLL] 消息过旧，跳过: ${content.take(30)}")
                            MessageLog.add("[MSG] trace=${messageTraceKey(msg)} status=过滤_时间过期 reason=气泡时间超过${MSG_MAX_AGE_MS/60000}分钟")
                            continue
                        }
                        result.add(msg)
                    }
                }
            }
        } else {
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
                       if (!text.contains("微信")) {
                           MessageLog.add("[POLL] 过滤非外部客户(FB): ${text.take(30)}")
                            MessageLog.add("[MSG] trace=${groupName}-UI采集-${text.take(20)} status=过滤_非外部客户 reason=兜底昵称不含微信")
                           continue
                       }
                       result.add(
                            MessagePusher.WeWorkMessage(
                                groupName = groupName,
                                sender = "UI采集",
                                content = text,
                                timestamp = System.currentTimeMillis()
                            )
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

       if (!hasAnyTime && lastPendingMsg != null) {
            MessageLog.add("[POLL] 全部无时间气泡，只取最后一条: ${lastPendingMsg.sender} -> ${lastPendingMsg.content.take(40)}")
            MessageLog.add("[MSG] trace=${messageTraceKey(lastPendingMsg)} status=已提取 reason=全部无时间气泡兜底")
            result.add(lastPendingMsg)
        }
        if (hasAnyTime && result.isEmpty() && lastValidMsg != null) {
            if (isMessageTooOld(lastValidMsgTime)) {
                MessageLog.add("[POLL] 兜底消息时间过期，不推送: ${lastValidMsg.sender} -> ${lastValidMsg.content.take(40)}")
                MessageLog.add("[MSG] trace=${messageTraceKey(lastValidMsg)} status=丢弃 reason=兜底消息时间过期")
            } else {
                MessageLog.add("[POLL] 时间超时兜底，由于群摘要变化推送最后一条: ${lastValidMsg.sender} -> ${lastValidMsg.content.take(40)}")
                MessageLog.add("[MSG] trace=${messageTraceKey(lastValidMsg)} status=已提取 reason=摘要变化超时兜底")
                result.add(lastValidMsg)
            }
        }
        if (result.isNotEmpty()) {
            MessageLog.add("[POLL] 提取到 ${result.size} 条消息: ${result.joinToString { "${it.sender}->${it.content.take(20)}" }}")
            result.forEach {
                MessageLog.add("[MSG] trace=${messageTraceKey(it)} status=已提取 reason=UI采集")
            }
        }
        return result
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
            if (root.packageName?.toString() != PACKAGE_WEWORK) {
                root.recycle()
                continue
            }
            val clicked = navigateToMessageTab(root)
            root.recycle()
            if (clicked) return true
        }
        return false
    }

    /**
     * 在指定窗口中查找并点击底部"消息"Tab。
     */
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
     * 查找企业微信主窗口。
     *
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
            val cached = WeWorkAccessibilityService.latestWeWorkRoot
            if (cached != null && cached.packageName?.toString() == PACKAGE_WEWORK) {
                val hasMain = hasMainContent(cached)
                if (hasMain) {
                    MessageLog.add("[POLL] findWeWorkRoot: using cached root")
                    // AccessibilityNodeInfo.obtain(cached) 创建一份拷贝，避免多线程竞争回收
                    return AccessibilityNodeInfo.obtain(cached)
                }
            }
        }

        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null) {
            val pkg = activeRoot.packageName?.toString()
            if (pkg == PACKAGE_WEWORK && hasMainContent(activeRoot)) {
                MessageLog.add("[POLL] findWeWorkRoot: using rootInActiveWindow")
                return activeRoot
            } else {
                activeRoot.recycle()
            }
        }

        val windows = service.windows
        val fetchedRoots = mutableListOf<AccessibilityNodeInfo>()
        var chatRoot: AccessibilityNodeInfo? = null
        var listRoot: AccessibilityNodeInfo? = null
        var fallbackRoot: AccessibilityNodeInfo? = null
        var fallbackChildCount = 0
        var weWorkWindowCount = 0
        try {
            for (window in windows) {
                val root = window.root ?: continue
                fetchedRoots.add(root)
                if (root.packageName?.toString() == PACKAGE_WEWORK) {
                    weWorkWindowCount++
                    val recyclerNodes = root.findAccessibilityNodeInfosByViewId(ID_RECYCLER_VIEW)
                    val hasMainRecycler = recyclerNodes.isNotEmpty()
                    recyclerNodes.forEach { it.recycle() }
                    val inputNode = findInputField(root)
                    val hasInput = inputNode != null
                    inputNode?.recycle()

                    if (hasInput && chatRoot == null) {
                        // 聊天页优先（有输入框无 RecyclerView 或两者都有）
                        chatRoot = root
                    } else if (hasMainRecycler && listRoot == null) {
                        // 消息列表页
                        listRoot = root
                    } else if (root.childCount > fallbackChildCount) {
                        // 兜底：选子节点最多的窗口
                        fallbackRoot = root
                        fallbackChildCount = root.childCount
                    }
                }
            }
            val bestRoot = chatRoot ?: listRoot ?: fallbackRoot
            fetchedRoots.remove(bestRoot)
            MessageLog.add("[POLL] findWeWorkRoot: windows=${windows.size} weWorkWindows=$weWorkWindowCount bestRoot=${bestRoot != null} chatRoot=${chatRoot != null} listRoot=${listRoot != null} fallback=$fallbackChildCount")
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

    /**
     * 启动企业微信。
     *
     * 使用 WeWorkLauncher 的健壮启动逻辑，绕过 Android 10+ 后台启动限制。
     */
    private fun launchWeWork() {
        WeWorkLauncher.launch(service, "POLL")
    }
}
