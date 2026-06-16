package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class WeWorkUIAutomator(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "WeWorkUIAutomator"
        private const val PACKAGE_WEWORK = "com.tencent.wework"
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 获取企业微信的主窗口。
     * vivo 版企业微信有左侧边栏窗口（ix_），会作为独立窗口出现。
     * 优先选择包含消息列表 RecyclerView（czp）或输入框特征的主窗口，
     * 避免选中侧边栏导致 UI 操作失败。
     */
    private fun findWeWorkWindow(): AccessibilityNodeInfo? {
        val windows = service.windows
        MessageLog.add("[AUTO] findWeWorkWindow: windows=${windows.size}")
        var chatRoot: AccessibilityNodeInfo? = null
        var listRoot: AccessibilityNodeInfo? = null
        for (window in windows) {
            val root = window.root
            val pkg = root?.packageName?.toString()
            val childCount = root?.childCount ?: 0
            MessageLog.add("[AUTO]   window pkg=$pkg children=$childCount")
            if (pkg == PACKAGE_WEWORK && root != null) {
                // 群聊页窗口优先（有输入框），其次消息列表页窗口（有 RecyclerView）。
                // vivo 上主窗口可能被包在一个 FrameLayout 里（children=1），
                // 所以不能只看 childCount，要通过实际内容判断。
                val hasInput = findInputField(root) != null
                val hasRecycler = root.findAccessibilityNodeInfosByViewId("com.tencent.wework:id/czp").isNotEmpty()
                if (hasInput && chatRoot == null) {
                    chatRoot = root
                } else if (hasRecycler && listRoot == null) {
                    listRoot = root
                }
            }
        }
        if (chatRoot != null) {
            MessageLog.add("[AUTO]   select chat root (has input)")
            return chatRoot
        }
        if (listRoot != null) {
            MessageLog.add("[AUTO]   select message list root (has recycler)")
            return listRoot
        }
        val active = service.rootInActiveWindow
        MessageLog.add("[AUTO]   rootInActiveWindow pkg=${active?.packageName} children=${active?.childCount}")
        return active
    }

    private var onReplyComplete: (() -> Unit)? = null

    fun sendReply(groupName: String, replyText: String, onComplete: (() -> Unit)? = null) {
        this.onReplyComplete = onComplete

        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[AUTO] 监控已停止，不执行回复")
            finishReply()
            return
        }
        MessageLog.add("[AUTO] 准备回复群 '$groupName': $replyText")
        Log.d(TAG, "sendReply: group=$groupName, text=$replyText")

        val rootNode = findWeWorkWindow()
        val pkg = rootNode?.packageName?.toString()
        Log.d(TAG, "current pkg=$pkg, root=${rootNode != null}")

        if (rootNode == null || pkg != PACKAGE_WEWORK) {
            MessageLog.add("[AUTO] 当前不在企业微信(pkg=$pkg)，尝试启动")
            launchWeWork()
            handler.postDelayed({ waitForWeWork(groupName, replyText, 8) }, 1500)
            return
        }

        trySend(groupName, replyText)
    }

    private fun finishReply() {
        val callback = onReplyComplete
        onReplyComplete = null
        callback?.invoke()
    }

    private fun waitForWeWork(groupName: String, replyText: String, retries: Int) {
        if (retries <= 0) {
            MessageLog.add("[AUTO] 等待企业微信出现超时")
            finishReply()
            return
        }
        val root = findWeWorkWindow()
        val pkg = root?.packageName?.toString()
        Log.d(TAG, "waitForWeWork: retries=$retries, pkg=$pkg")

        if (pkg == PACKAGE_WEWORK && root != null) {
            MessageLog.add("[AUTO] 企业微信已出现，继续操作")
            trySend(groupName, replyText)
        } else {
            MessageLog.add("[AUTO] 等待中... 当前pkg=$pkg")
            handler.postDelayed({ waitForWeWork(groupName, replyText, retries - 1) }, 1200)
        }
    }

    private fun trySend(groupName: String, replyText: String, waitRetries: Int = 3) {
        // 1. 安全页面检查：确保不在邮件/文档/工作台等未知页面
        if (!ensureSafePage()) {
            MessageLog.add("[AUTO] trySend: 无法进入安全页面，放弃本次回复")
            emergencyRecover()
            finishReply()
            return
        }

        val rootNode = findWeWorkWindow()
        if (rootNode == null) {
            MessageLog.add("[AUTO] 无法获取窗口")
            emergencyRecover()
            finishReply()
            return
        }

        val pkg = rootNode.packageName?.toString()
        val childCount = rootNode.childCount
        MessageLog.add("[AUTO] 当前窗口 pkg=$pkg children=$childCount")

        if (pkg != PACKAGE_WEWORK) {
            MessageLog.add("[AUTO] 窗口包名不对，放弃")
            rootNode.recycle()
            emergencyRecover()
            finishReply()
            return
        }

        if (childCount == 0) {
            MessageLog.add("[AUTO] 窗口节点为空，可能企业微信还没加载完")
            dumpTree(rootNode)
            rootNode.recycle()
            emergencyRecover()
            finishReply()
            return
        }

        if (childCount < 3 && !isInChatScreen(rootNode, groupName)) {
            if (waitRetries > 0) {
                MessageLog.add("[AUTO] 窗口节点过少($childCount)，等待加载... 剩余$waitRetries")
                handler.postDelayed({ trySend(groupName, replyText, waitRetries - 1) }, 1000)
                rootNode.recycle()
                return
            } else {
                MessageLog.add("[AUTO] 窗口节点仍过少($childCount)，继续尝试...")
            }
        }

        val inTargetChat = isInChatScreen(rootNode, groupName)
        MessageLog.add("[AUTO] 是否已在目标群聊: $inTargetChat")

        if (!inTargetChat) {
            // 先检查是不是在其他聊天里（有输入框但标题不对）
            val hasInput = findInputField(rootNode) != null
            val hasTitle = rootNode.findAccessibilityNodeInfosByText(groupName).any {
                it.text?.toString() == groupName
            }

            if (hasInput && !hasTitle) {
                MessageLog.add("[AUTO] 当前在其他聊天，按返回键")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                handler.postDelayed({ trySend(groupName, replyText) }, 1500)
                rootNode.recycle()
                return
            }

            // 检查是否在消息列表页——如果是，直接遍历点击群聊项
            val recyclerNodes = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.wework:id/czp")
            if (recyclerNodes.isNotEmpty()) {
                MessageLog.add("[AUTO] 当前在消息列表页，尝试直接点击群聊项")
                val recycler = recyclerNodes.firstOrNull()
                var clicked = false
                if (recycler != null) {
                    for (i in 0 until minOf(recycler.childCount, 10)) {
                        val item = recycler.getChild(i) ?: continue
                        val nameNodes = item.findAccessibilityNodeInfosByViewId("com.tencent.wework:id/hrr")
                        val name = nameNodes.firstOrNull()?.text?.toString()
                        if (name == groupName) {
                            var current: AccessibilityNodeInfo? = item
                            var depth = 0
                            while (current != null && depth < 10) {
                                if (current.isClickable) {
                                    val clickOk = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    MessageLog.add("[AUTO] 消息列表页点击群聊项: $clickOk")
                                    clicked = true
                                    handler.postDelayed({ waitForChatScreen(replyText, groupName, 6) }, 2000)
                                    break
                                }
                                current = current.parent
                                depth++
                            }
                        }
                        nameNodes.forEach { it.recycle() }
                        item.recycle()
                        if (clicked) break
                    }
                    recycler.recycle()
                }
                recyclerNodes.forEach { if (it !== recycler) it.recycle() }
                if (clicked) {
                    rootNode.recycle()
                    return
                }
                MessageLog.add("[AUTO] 消息列表页未找到群聊，fallback到搜索")
            } else if (!hasInput) {
                // 不在消息列表页，也不在聊天页（如通讯录、工作台等），尝试点击底部"消息"Tab
                val navigated = navigateToMessageTab(rootNode)
                if (navigated) {
                    MessageLog.add("[AUTO] 点击'消息'Tab，等待页面切换")
                    handler.postDelayed({ trySend(groupName, replyText, 3) }, 1500)
                    rootNode.recycle()
                    return
                }
            }

            // 搜索定位已禁用（置顶群直接通过消息列表点击）
            MessageLog.add("[AUTO] 消息列表页未找到群聊，搜索已禁用")
            rootNode.recycle()
            emergencyRecover()
            finishReply()
            return
        }

        MessageLog.add("[AUTO] 已在目标群聊，准备输入发送")
        tryTypeAndSend(replyText)
        rootNode.recycle()
    }

    // ==================== 页面安全识别与恢复 ====================

    enum class PageType {
        MESSAGE_LIST, CHAT, MAIL, DOC, WORKBENCH, CONTACT, CONTACT_DETAIL, UNKNOWN
    }

    /**
     * 识别当前企业微信页面类型，避免在未知页面乱操作
     */
    private fun detectCurrentPage(root: AccessibilityNodeInfo): PageType {
        val titleNodes = root.findAccessibilityNodeInfosByText("消息")
        val hasMsgTitle = titleNodes.any {
            val t = it.text?.toString() ?: ""
            (t == "消息" || t.contains("消息")) && !isNodeInRecyclerView(it)
        }
        titleNodes.forEach { it.recycle() }

        val recyclerNodes = root.findAccessibilityNodeInfosByViewId("com.tencent.wework:id/czp")
        val hasRecycler = recyclerNodes.isNotEmpty()
        recyclerNodes.forEach { it.recycle() }

        val hasInput = findInputField(root) != null

        // 消息列表页特征：有 RecyclerView 且顶部标题包含"消息"
        if (hasRecycler && hasMsgTitle) return PageType.MESSAGE_LIST
        // 群聊页特征：有输入框 + 不在消息列表
        if (hasInput && !hasRecycler) return PageType.CHAT
        // 其他 Tab 页面特征（无 RecyclerView 无输入框）
        if (!hasRecycler && !hasInput) {
            val mailNodes = root.findAccessibilityNodeInfosByText("邮件")
            val hasMail = mailNodes.any { it.text?.toString() == "邮件" && !isNodeInRecyclerView(it) }
            mailNodes.forEach { it.recycle() }
            if (hasMail) return PageType.MAIL

            val docNodes = root.findAccessibilityNodeInfosByText("文档")
            val hasDoc = docNodes.any { it.text?.toString() == "文档" && !isNodeInRecyclerView(it) }
            docNodes.forEach { it.recycle() }
            if (hasDoc) return PageType.DOC

            val workNodes = root.findAccessibilityNodeInfosByText("工作台")
            val hasWork = workNodes.any { it.text?.toString() == "工作台" && !isNodeInRecyclerView(it) }
            workNodes.forEach { it.recycle() }
            if (hasWork) return PageType.WORKBENCH

            val contactNodes = root.findAccessibilityNodeInfosByText("通讯录")
            val hasContact = contactNodes.any { it.text?.toString() == "通讯录" && !isNodeInRecyclerView(it) }
            contactNodes.forEach { it.recycle() }
            if (hasContact) return PageType.CONTACT
        }

        return PageType.UNKNOWN
    }

    /**
     * 确保当前在安全页面（消息列表页或群聊页），否则导航回消息列表
     */
    fun ensureSafePage(): Boolean {
        for (attempt in 1..3) {
            val root = findWeWorkWindow()
            if (root == null) {
                MessageLog.add("[AUTO] ensureSafePage: 无法获取窗口，尝试启动企业微信")
                launchWeWork()
                Thread.sleep(1200)
                continue
            }
            val page = detectCurrentPage(root)
            MessageLog.add("[AUTO] ensureSafePage: 当前页面=$page")
            root.recycle()

            when (page) {
                PageType.MESSAGE_LIST, PageType.CHAT -> return true
                else -> {
                    MessageLog.add("[AUTO] ensureSafePage: 当前在$page，尝试切回消息列表 (attempt=$attempt)")
                    val root2 = findWeWorkWindow()
                    if (root2 != null) {
                        navigateToMessageTab(root2)
                        root2.recycle()
                    }
                    Thread.sleep(1000)
                }
            }
        }
        MessageLog.add("[AUTO] ensureSafePage: 3次尝试后仍无法回到安全页面，放弃")
        return false
    }

    /**
     * 智能紧急恢复：每次操作后检测当前页面，按需决定下一步，避免过度回退
     */
    fun emergencyRecover() {
        MessageLog.add("[AUTO] emergencyRecover: 开始智能恢复")
        for (attempt in 1..5) {
            val root = findWeWorkWindow()
            if (root == null) {
                MessageLog.add("[AUTO] emergencyRecover: 企业微信不在前台，尝试启动 (attempt=$attempt)")
                launchWeWork()
                Thread.sleep(1200)
                continue
            }

            val page = detectCurrentPage(root)
            MessageLog.add("[AUTO] emergencyRecover: 当前页面=$page (attempt=$attempt)")
            root.recycle()

            when (page) {
                PageType.MESSAGE_LIST -> {
                    MessageLog.add("[AUTO] emergencyRecover: 已回到消息列表，恢复完成")
                    return
                }
                PageType.CHAT -> {
                    // 在群聊页，按Back即可回到消息列表
                    MessageLog.add("[AUTO] emergencyRecover: 在群聊页，按Back")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    Thread.sleep(500)
                }
                else -> {
                    // 在邮件/文档/工作台/通讯录等页面，直接点消息Tab最快
                    MessageLog.add("[AUTO] emergencyRecover: 在$page，点击'消息'Tab")
                    val root2 = findWeWorkWindow()
                    if (root2 != null) {
                        navigateToMessageTab(root2)
                        root2.recycle()
                    }
                    Thread.sleep(800)
                }
            }
        }
        MessageLog.add("[AUTO] emergencyRecover: 5次尝试后仍未恢复，放弃")
    }

    private fun navigateToMessageTab(root: AccessibilityNodeInfo): Boolean {
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val minTop = (rootRect.top + rootRect.height() * 0.85).toInt()

        // vivo 上底部 Tab 文字节点 bounds 可能为 [0,0][0,0]，需向上找 clickable 父节点
        // 再用父节点的 bounds 判断是否在底部区域。
        val msgNodes = root.findAccessibilityNodeInfosByText("消息")
        var clicked = false
        for (node in msgNodes) {
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

            val clickRect = android.graphics.Rect()
            clickableNode.getBoundsInScreen(clickRect)
            if (clickRect.top < minTop) {
                if (clickableNode !== node) clickableNode.recycle()
                node.recycle()
                continue
            }

            val result = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            MessageLog.add("[AUTO] 点击'消息'Tab: $result")
            clicked = true
            if (clickableNode !== node) clickableNode.recycle()
            node.recycle()
            if (clicked) break
        }
        return clicked
    }

    private fun waitForChatScreen(replyText: String, groupName: String, retries: Int) {
        if (retries <= 0) {
            MessageLog.add("[AUTO] 等待群聊界面超时")
            dumpAllWeWorkWindows()
            finishReply()
            return
        }

        // vivo 上聊天页可能是新窗口，findWeWorkWindow 可能优先返回消息列表窗口。
        // 改为遍历所有企业微信窗口，直接检测聊天页特征。
        val chatRoot = findChatWindowAcrossWindows(groupName)
        if (chatRoot != null) {
            MessageLog.add("[AUTO] 已进入群聊界面")
            tryTypeAndSend(replyText)
            chatRoot.recycle()
        } else {
            MessageLog.add("[AUTO] 等待群聊界面...")
            handler.postDelayed({ waitForChatScreen(replyText, groupName, retries - 1) }, 1500)
        }
    }

    /**
     * 遍历所有窗口，查找标题和输入框都匹配的群聊页。
     */
    private fun findChatWindowAcrossWindows(groupName: String): AccessibilityNodeInfo? {
        val windows = service.windows
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != PACKAGE_WEWORK) {
                root.recycle()
                continue
            }
            if (isInChatScreen(root, groupName)) {
                return root
            }
            root.recycle()
        }
        return null
    }

    /**
     * dump 所有企业微信窗口，用于超时后诊断。
     */
    private fun dumpAllWeWorkWindows() {
        val windows = service.windows
        var dumped = 0
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == PACKAGE_WEWORK) {
                MessageLog.add("[AUTO] dump 第 ${dumped + 1} 个企业微信窗口")
                dumpTree(root)
                dumped++
            }
            root.recycle()
        }
        if (dumped == 0) {
            MessageLog.add("[AUTO] 超时后未找到任何企业微信窗口")
        }
    }

    private fun tryTypeAndSend(text: String) {
        val rootNode = findWeWorkWindow() ?: run {
            MessageLog.add("[AUTO] 打字前窗口丢失")
            finishReply()
            return
        }
        val pkg = rootNode.packageName?.toString()
        if (pkg != PACKAGE_WEWORK) {
            MessageLog.add("[AUTO] 打字前窗口变了 pkg=$pkg")
            rootNode.recycle()
            finishReply()
            return
        }

        val inputNode = findInputField(rootNode)
        if (inputNode == null) {
            MessageLog.add("[AUTO] 找不到输入框")
            dumpTree(rootNode)
            rootNode.recycle()
            finishReply()
            return
        }

        // 关键修复：先确保输入框获得焦点，再设置文字。
        // vivo/企业微信在输入框未聚焦时设置文字，发送按钮不会从右侧工具栏切换出来，
        // 导致后续 findSendButton 可能误点“+”或语音按钮，消息未发送就退出，最终存为草稿。
        val focusSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clickSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        MessageLog.add("[AUTO] 输入框聚焦=$focusSuccess 点击=$clickSuccess")

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val setTextSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        var focusedAfter = inputNode.isFocused
        MessageLog.add("[AUTO] 输入文字结果: $setTextSuccess, 输入后聚焦=$focusedAfter")

        // 关键：vivo/企业微信设置文字后输入框可能失焦，导致发送按钮不出现。
        // 必须重新聚焦并点击输入框，才能唤出发送按钮。
        if (setTextSuccess && !focusedAfter) {
            MessageLog.add("[AUTO] 输入后失焦，重新聚焦输入框")
            val reFocus = inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val reClick = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            focusedAfter = inputNode.isFocused
            MessageLog.add("[AUTO] 重新聚焦结果: focus=$reFocus click=$reClick focused=$focusedAfter")
        }

        inputNode.recycle()
        rootNode.recycle()

        if (!setTextSuccess) {
            MessageLog.add("[AUTO] 直接输入失败，稍后重试")
            handler.postDelayed({ retryTypeAndSend(text) }, 800)
            return
        }

        if (!focusedAfter) {
            MessageLog.add("[AUTO] 警告：输入框仍未聚焦，发送按钮可能未出现")
        }

        // 增加等待时间，确保文字设置并重新聚焦后发送按钮（resource-id i_2）已经出现
        handler.postDelayed({ tryClickSend() }, 1500)
    }

    /**
     * 直接输入失败后重试：重新查找输入框并设置文字。
     */
    private fun retryTypeAndSend(text: String) {
        val freshRoot = findWeWorkWindow()
        if (freshRoot == null) {
            MessageLog.add("[AUTO] 重试输入时窗口丢失")
            finishReply()
            return
        }
        val newInput = findInputField(freshRoot)
        if (newInput == null) {
            MessageLog.add("[AUTO] 重试时找不到输入框")
            freshRoot.recycle()
            finishReply()
            return
        }
        val focusSuccess = newInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clickSuccess = newInput.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        MessageLog.add("[AUTO] 重试输入框聚焦=$focusSuccess 点击=$clickSuccess")

        val newArgs = Bundle()
        newArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val retrySuccess = newInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, newArgs)
        val focusedAfter = newInput.isFocused
        MessageLog.add("[AUTO] 重试输入结果: $retrySuccess, 输入后聚焦=$focusedAfter")

        newInput.recycle()
        freshRoot.recycle()

        if (retrySuccess) {
            handler.postDelayed({ tryClickSend() }, 1500)
        } else {
            MessageLog.add("[AUTO] 多次输入均失败，放弃")
            finishReply()
        }
    }

    private fun tryClickSend(retryCount: Int = 0) {
        val freshRoot = findWeWorkWindow()
        if (freshRoot == null) {
            MessageLog.add("[AUTO] 点击发送前窗口丢失")
            ensureBackToMessageList(attempt = 1)
            finishReply()
            return
        }
        val sendBtn = findSendButton(freshRoot)
        if (sendBtn != null) {
            val btnRect = android.graphics.Rect()
            sendBtn.getBoundsInScreen(btnRect)
            val centerX = (btnRect.left + btnRect.right) / 2f
            val centerY = (btnRect.top + btnRect.bottom) / 2f

            val btnText = sendBtn.text?.toString() ?: ""
            val btnId = sendBtn.viewIdResourceName?.toString() ?: ""
            val btnClass = sendBtn.className?.toString() ?: ""
            MessageLog.add("[AUTO] 找到发送按钮: text='$btnText' id=$btnId class=$btnClass bounds=$btnRect")

            // vivo/企业微信的 Button 对 ACTION_CLICK 常常返回 true 但不实际触发发送。
            // 因此优先使用坐标手势点击；重试时再换 ACTION_CLICK。
            if (retryCount == 0) {
                MessageLog.add("[AUTO] 使用坐标点击发送: ($centerX, $centerY)")
                clickAt(centerX, centerY)
                sendBtn.recycle()
                freshRoot.recycle()
                handler.postDelayed({
                    verifySendAndBack(retryCount)
                }, 2000)
            } else {
                val clickSuccess = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                MessageLog.add("[AUTO] 重试 ACTION_CLICK: $clickSuccess")
                sendBtn.recycle()
                freshRoot.recycle()
                handler.postDelayed({
                    verifySendAndBack(retryCount)
                }, 2000)
            }
        } else {
            MessageLog.add("[AUTO] 找不到发送按钮")
            if (retryCount < 1) {
                // 可能是输入框未聚焦导致发送按钮没出现，尝试重新聚焦输入框后再找一次
                val inputNode = findInputField(freshRoot)
                if (inputNode != null) {
                    MessageLog.add("[AUTO] 尝试重新聚焦输入框以显示发送按钮")
                    inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    inputNode.recycle()
                }
                freshRoot.recycle()
                handler.postDelayed({ tryClickSend(retryCount + 1) }, 1200)
            } else {
                dumpTree(freshRoot)
                freshRoot.recycle()
                ensureBackToMessageList(attempt = 1)
                finishReply()
            }
        }
    }

    /**
     * 通过无障碍手势在指定坐标执行点击，兼容不响应 ACTION_CLICK 的自定义 View。
     */
    private fun clickAt(x: Float, y: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        val path = android.graphics.Path()
        path.moveTo(x, y)
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                MessageLog.add("[AUTO] 坐标点击完成: ($x, $y)")
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                MessageLog.add("[AUTO] 坐标点击被取消: ($x, $y)")
            }
        }, null)
    }

    /**
     * 发送后验证输入框是否已清空，如果没清空说明发送失败，重试一次。
     * 验证完成后确保回到消息列表。
     */
    private fun verifySendAndBack(retryCount: Int) {
        val root = findWeWorkWindow()
        if (root == null) {
            MessageLog.add("[AUTO] 发送验证时窗口丢失")
            ensureBackToMessageList(attempt = 1)
            return
        }
        val inputNode = findInputField(root)
        if (inputNode == null) {
            MessageLog.add("[AUTO] 发送验证时找不到输入框，可能已不在聊天页")
            root.recycle()
            ensureBackToMessageList(attempt = 1)
            finishReply()
            return
        }
        val inputText = inputNode.text?.toString() ?: ""
        inputNode.recycle()
        root.recycle()

        if (inputText.isNotBlank() && retryCount < 1) {
            // 输入框还有文字，说明没发出去，重试一次
            MessageLog.add("[AUTO] 发送验证失败，输入框仍有文字: ${inputText.take(30)}")
            tryClickSend(retryCount = retryCount + 1)
        } else {
            if (inputText.isBlank()) {
                MessageLog.add("[AUTO] 发送验证通过，输入框已清空")
            } else {
                MessageLog.add("[AUTO] 输入框仍有文字但已重试过，放弃")
            }
            ensureBackToMessageList(attempt = 1)
            finishReply()
        }
    }

    /**
     * 备选发送方案：直接再次点击发送按钮（有时第一次点击未生效）。
     */
    private fun tryEnterSend(retryCount: Int) {
        val root = findWeWorkWindow()
        if (root == null) {
            MessageLog.add("[AUTO] 重试发送时窗口丢失")
            ensureBackToMessageList(attempt = 1)
            return
        }
        val sendBtn = findSendButton(root)
        if (sendBtn != null) {
            val clickSuccess = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            MessageLog.add("[AUTO] 重试点击发送: $clickSuccess")
            sendBtn.recycle()
        } else {
            MessageLog.add("[AUTO] 重试时找不到发送按钮")
        }
        root.recycle()

        handler.postDelayed({
            verifySendAndBack(retryCount)
        }, 1500)
    }

    private fun isInChatScreen(root: AccessibilityNodeInfo, groupName: String): Boolean {
        val titleNodes = root.findAccessibilityNodeInfosByText(groupName)
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val titleMaxY = (rootRect.top + rootRect.height() * 0.15).toInt()

        // vivo 版本标题栏节点可能嵌套在 RecyclerView 中，不能用 isNodeInRecyclerView 过滤。
        // 改用屏幕位置判断：标题栏一定在屏幕顶部 15% 区域内。
        val hasTitleByText = titleNodes.any {
            val t = it.text?.toString() ?: ""
            if (!t.contains(groupName)) return@any false
            val nodeRect = android.graphics.Rect()
            it.getBoundsInScreen(nodeRect)
            nodeRect.centerY() <= titleMaxY
        }
        val hasTitleByDesc = titleNodes.any {
            val d = it.contentDescription?.toString() ?: ""
            if (!d.contains(groupName)) return@any false
            val nodeRect = android.graphics.Rect()
            it.getBoundsInScreen(nodeRect)
            nodeRect.centerY() <= titleMaxY
        }
        val hasTitle = hasTitleByText || hasTitleByDesc
        val inputNode = findInputField(root)
        val hasInput = inputNode != null
        inputNode?.recycle()
        MessageLog.add("[AUTO] isInChatScreen: hasTitle=$hasTitle hasInput=$hasInput titleMaxY=$titleMaxY")
        titleNodes.forEach { it.recycle() }
        return hasTitle && hasInput
    }

    private fun isNodeInRecyclerView(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 10) {
            val parent = current.parent
            if (parent?.className?.toString()?.contains("RecyclerView") == true ||
                parent?.className?.toString()?.contains("ListView") == true) {
                return true
            }
            current = parent
            depth++
        }
        return false
    }

    /**
     * 发送消息后，确保回到消息列表页。
     * vivo 群聊页没有底部"消息"Tab，正确方式是点击左上角返回按钮。
     */
    private fun ensureBackToMessageList(attempt: Int) {
        if (attempt > 3) {
            MessageLog.add("[AUTO] 3次尝试后仍未回到消息列表，触发紧急恢复")
            emergencyRecover()
            return
        }

        // 先尝试点击左上角返回按钮
        val root = findWeWorkWindow()
        if (root != null) {
            val clicked = clickBackButton(root)
            root.recycle()
            if (clicked) {
                MessageLog.add("[AUTO] 已点击左上角返回按钮")
                handler.postDelayed({
                    val verifyRoot = findWeWorkWindow()
                    if (verifyRoot != null) {
                        val page = detectCurrentPage(verifyRoot)
                        verifyRoot.recycle()
                        if (page == PageType.MESSAGE_LIST) {
                            MessageLog.add("[AUTO] 已回到消息列表页")
                        } else if (page == PageType.CHAT) {
                            ensureBackToMessageList(attempt + 1)
                        } else {
                            navigateToMessageTabAcrossWindows()
                        }
                    }
                }, 1200)
                return
            }
        }

        // fallback 到按 Back
        MessageLog.add("[AUTO] 返回按钮未找到，第${attempt}次按Back")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        handler.postDelayed({
            val verifyRoot = findWeWorkWindow()
            if (verifyRoot == null) {
                MessageLog.add("[AUTO] Back后窗口丢失，触发紧急恢复")
                emergencyRecover()
                return@postDelayed
            }
            val page = detectCurrentPage(verifyRoot)
            verifyRoot.recycle()
            when (page) {
                PageType.MESSAGE_LIST -> {
                    MessageLog.add("[AUTO] 已回到消息列表页")
                }
                PageType.CHAT -> {
                    ensureBackToMessageList(attempt + 1)
                }
                else -> {
                    MessageLog.add("[AUTO] Back后进入$page，尝试点击'消息'Tab切回")
                    navigateToMessageTabAcrossWindows()
                }
            }
        }, 800)
    }

    /**
     * 点击群聊页左上角的返回按钮（resource-id: nc9）。
     */
    private fun clickBackButton(root: AccessibilityNodeInfo): Boolean {
        val backNodes = root.findAccessibilityNodeInfosByViewId("com.tencent.wework:id/nc9")
        val backBtn = backNodes.firstOrNull()
        if (backBtn != null && backBtn.isClickable) {
            val result = backBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            MessageLog.add("[AUTO] 点击左上角返回按钮: $result")
            backNodes.forEach { if (it !== backBtn) it.recycle() }
            backBtn.recycle()
            return result
        }
        backNodes.forEach { it.recycle() }
        return false
    }

    /**
     * 在所有企业微信窗口中搜索并点击底部"消息"Tab。
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

//     private fun findGroupBySearch(groupName: String, replyText: String) {
//         val root = findWeWorkWindow()
//         if (root == null || root.packageName?.toString() != PACKAGE_WEWORK) {
//             MessageLog.add("[AUTO] 搜索前窗口不对")
//             return
//         }
// 
//         val searchBtn = findSearchButton(root)
//         if (searchBtn == null) {
//             MessageLog.add("[AUTO] 找不到搜索按钮，fallback 到列表滚动")
//             findGroupWithScroll(root, groupName, 5) { groupItem ->
//                 if (groupItem != null) {
//                     val success = groupItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                     MessageLog.add("[AUTO] 点击群聊结果: $success")
//                     handler.postDelayed({ waitForChatScreen(replyText, groupName, 6) }, 2000)
//                 } else {
//                     MessageLog.add("[AUTO] 找不到群聊 '$groupName'，打印当前UI树")
//                     val freshRoot = findWeWorkWindow()
//                     if (freshRoot != null) dumpTree(freshRoot)
//                 }
//             }
//             return
//         }
// 
//         val clickSuccess = searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//         MessageLog.add("[AUTO] 点击搜索按钮: $clickSuccess")
// 
//         handler.postDelayed({
//             val searchRoot = findWeWorkWindow()
//             if (searchRoot == null || searchRoot.packageName?.toString() != PACKAGE_WEWORK) {
//                 MessageLog.add("[AUTO] 搜索界面未出现")
//                 return@postDelayed
//             }
//             MessageLog.add("[AUTO] 搜索界面已出现，开始找输入框")
// 
//             // 1. 先尝试标准 EditText
//             var searchInput = findNodeByClass(searchRoot, "android.widget.EditText")
//             // 2. Fallback: 找任何可编辑节点
//             if (searchInput == null) {
//                 searchInput = findEditableNode(searchRoot)
//                 if (searchInput != null) {
//                     MessageLog.add("[AUTO] 通过isEditable找到搜索输入框")
//                 }
//             } else {
//                 MessageLog.add("[AUTO] 通过EditText类找到搜索输入框")
//             }
//             // 3. Fallback: 如果搜索界面没有输入框，说明点击的可能不是搜索按钮，回退到列表滚动
//             if (searchInput == null) {
//                 MessageLog.add("[AUTO] 搜索界面无输入框，fallback到列表滚动找群聊")
//                 val listRoot = findWeWorkWindow()
//                 if (listRoot != null) {
//                     findGroupWithScroll(listRoot, groupName, 5) { groupItem ->
//                         if (groupItem != null) {
//                             val success = groupItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                             MessageLog.add("[AUTO] 点击群聊结果: $success")
//                             handler.postDelayed({ waitForChatScreen(replyText, groupName, 6) }, 2000)
//                         } else {
//                             MessageLog.add("[AUTO] 列表滚动也找不到群聊 '$groupName'")
//                         }
//                     }
//                 }
//                 return@postDelayed
//             }
// 
//             val args = Bundle()
//             args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, groupName)
//             val setTextSuccess = searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
//             MessageLog.add("[AUTO] 输入搜索文字: $setTextSuccess")
// 
//             handler.postDelayed({
//                 val resultRoot = findWeWorkWindow()
//                 if (resultRoot == null || resultRoot.packageName?.toString() != PACKAGE_WEWORK) {
//                     MessageLog.add("[AUTO] 搜索结果窗口丢失")
//                     return@postDelayed
//                 }
// 
//                 val resultNodes = resultRoot.findAccessibilityNodeInfosByText(groupName)
//                 MessageLog.add("[AUTO] 搜索结果节点数: ${resultNodes.size}")
//                 // 企业微信搜索结果可能显示 "群名 (人数) 外部"，用 contains 匹配
//                 // 必须排除搜索输入框本身（EditText），否则点的是输入框不是结果
//                 val result = resultNodes.find {
//                     val cls = it.className?.toString() ?: ""
//                     if (cls.contains("EditText")) return@find false
//                     val t = it.text?.toString() ?: ""
//                     val d = it.contentDescription?.toString() ?: ""
//                     t.contains(groupName) || d.contains(groupName)
//                 }
// 
//                 if (result == null) {
//                     MessageLog.add("[AUTO] 搜索结果中没有群名，打印UI树")
//                     dumpTree(resultRoot)
//                     return@postDelayed
//                 }
//                 MessageLog.add("[AUTO] 找到搜索结果节点: class=${result.className} text=${result.text}")
// 
//                 // 向上找可点击父节点，最多10层
//                 var current: AccessibilityNodeInfo? = result
//                 var depth = 0
//                 var clickableNode: AccessibilityNodeInfo? = null
//                 while (current != null && depth < 10) {
//                     if (current.isClickable) {
//                         clickableNode = current
//                         break
//                     }
//                     current = current.parent
//                     depth++
//                 }
// 
//                 // 如果向上找不到 clickable，尝试直接用包含群名的节点点击（部分系统支持）
//                 if (clickableNode == null) {
//                     MessageLog.add("[AUTO] 向上找不到 clickable 父节点，尝试直接点击文本节点")
//                     val directClick = result.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                     MessageLog.add("[AUTO] 直接点击结果: $directClick")
//                     if (directClick) {
//                         handler.postDelayed({ waitForChatScreen(replyText, groupName, 6) }, 2000)
//                         return@postDelayed
//                     }
//                 }
// 
//                 if (clickableNode != null) {
//                     val clickResult = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                     MessageLog.add("[AUTO] 点击搜索结果: $clickResult")
//                     handler.postDelayed({ waitForChatScreen(replyText, groupName, 6) }, 2000)
//                 } else {
//                     MessageLog.add("[AUTO] 搜索结果不可点击，fallback 到打印UI树")
//                     dumpTree(resultRoot)
//                 }
//             }, 1500)
//         }, 1500)
//     }
// 
//     private fun findGroupWithScroll(root: AccessibilityNodeInfo, groupName: String, maxScrolls: Int, onFound: (AccessibilityNodeInfo?) -> Unit) {
//         val groupItem = findGroupInCurrentScreen(root, groupName)
//         if (groupItem != null) {
//             onFound(groupItem)
//             return
//         }
// 
//         if (maxScrolls <= 0) {
//             onFound(null)
//             return
//         }
// 
//         val scrollable = findScrollableContainer(root)
//         if (scrollable == null) {
//             onFound(null)
//             return
//         }
// 
//         val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
//         MessageLog.add("[AUTO] 滚动列表结果: $scrolled，剩余次数: $maxScrolls")
// 
//         if (scrolled) {
//             handler.postDelayed({
//                 val freshRoot = findWeWorkWindow()
//                 if (freshRoot != null) {
//                     findGroupWithScroll(freshRoot, groupName, maxScrolls - 1, onFound)
//                 } else {
//                     onFound(null)
//                 }
//             }, 1200)
//         } else {
//             onFound(null)
//         }
//     }
// 
//     private fun findGroupInCurrentScreen(root: AccessibilityNodeInfo, groupName: String): AccessibilityNodeInfo? {
//         // 精确匹配文字
//         val nodes = root.findAccessibilityNodeInfosByText(groupName)
//         val exactNode = nodes.find { it.text?.toString() == groupName }
// 
//         if (exactNode == null) {
//             return null
//         }
// 
//         MessageLog.add("[AUTO] 找到群名节点: class=${exactNode.className} text=${exactNode.text} clickable=${exactNode.isClickable}")
// 
//         // 向上找列表项父节点
//         var current: AccessibilityNodeInfo? = exactNode
//         var depth = 0
//         var listItem: AccessibilityNodeInfo? = null
// 
//         while (current != null && depth < 10) {
//             val parent = current.parent
// 
//             // 如果父节点是 RecyclerView 或 ListView，current 就是列表项
//             if (parent != null && (
//                         parent.className?.toString()?.contains("RecyclerView") == true ||
//                         parent.className?.toString()?.contains("ListView") == true
//                     )) {
//                 listItem = current
//                 MessageLog.add("[AUTO] 定位到列表项: class=${current.className} clickable=${current.isClickable}")
//                 break
//             }
// 
//             current = parent
//             depth++
//         }
// 
//         // 如果没找到 RecyclerView/ListView 父节点，就退而求其次找可点击父节点
//         if (listItem == null) {
//             current = exactNode
//             depth = 0
//             while (current != null && depth < 5) {
//                 if (current.isClickable) {
//                     listItem = current
//                     MessageLog.add("[AUTO] 定位到可点击父节点: class=${current.className}")
//                     break
//                 }
//                 current = current.parent
//                 depth++
//             }
//         }
// 
//         return listItem
//     }
// 
//     private fun findScrollableContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
//         val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
//         deque.add(root)
//         while (deque.isNotEmpty()) {
//             val node = deque.poll() ?: continue
//             if (node.isScrollable && (
//                         node.className?.toString()?.contains("RecyclerView") == true ||
//                         node.className?.toString()?.contains("ListView") == true ||
//                         node.className?.toString()?.contains("ScrollView") == true
//                     )) {
//                 return node
//             }
//             for (i in 0 until node.childCount) {
//                 node.getChild(i)?.let { deque.add(it) }
//             }
//         }
//         return null
//     }
// 
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
                MessageLog.add("[AUTO] 通过hint找到输入框: $hint")
                return match
            }
        }
        // Fallback 1: 找屏幕底部 55% 区域的 EditText（企业微信输入区约在 55%-60%）
        val byClass = findNodeByClassInLowerScreen(root, "android.widget.EditText", 0.55f)
        if (byClass != null) {
            MessageLog.add("[AUTO] 通过EditText类找到输入框")
            return byClass
        }
        // Fallback 2: 企业微信可能是自定义输入框，找底部可编辑节点
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val minTop = (rootRect.top + rootRect.height() * 0.45).toInt()
        val nodeRect = android.graphics.Rect()
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            if (node.isEditable) {
                node.getBoundsInScreen(nodeRect)
                if (nodeRect.top >= minTop) {
                    MessageLog.add("[AUTO] 通过isEditable找到输入框: class=${node.className}")
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        // Fallback 3 已移除：通过底部最宽 TextView 匹配容易把消息列表底部 Tab（"消息"）
        // 或桌面 Dock 误判为输入框，导致 auto-reply 操作跑偏。
        MessageLog.add("[AUTO] 所有方式均未找到输入框")
        return null
    }

    private fun findNodeByClassInLowerScreen(
        root: AccessibilityNodeInfo,
        className: String,
        screenRatio: Float
    ): AccessibilityNodeInfo? {
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val minTop = (rootRect.top + rootRect.height() * (1 - screenRatio)).toInt()

        val nodeRect = android.graphics.Rect()
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            if (node.className?.toString() == className) {
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

//     private fun findSearchButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
//         val hints = listOf("搜索", "查找", "Search")
//         for (hint in hints) {
//             val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
//             deque.add(root)
//             while (deque.isNotEmpty()) {
//                 val node = deque.poll() ?: continue
//                 if (node.contentDescription?.toString() == hint) {
//                     return node
//                 }
//                 for (i in 0 until node.childCount) {
//                     node.getChild(i)?.let { deque.add(it) }
//                 }
//             }
//         }
//         // Fallback: 找屏幕顶部标题栏的可点击节点，按水平位置排序后返回中间那个
//         return findCenterClickableInTopBar(root)
//     }
// 
//     private fun findCenterClickableInTopBar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
//         val rootRect = android.graphics.Rect()
//         root.getBoundsInScreen(rootRect)
//         // 扩大搜索区域到顶部 25%，水平方向只排除最边缘 5%
//         val minTop = rootRect.top
//         val maxBottom = (rootRect.top + rootRect.height() * 0.25).toInt()
//         val minLeft = (rootRect.left + rootRect.width() * 0.05).toInt()
//         val maxRight = (rootRect.left + rootRect.width() * 0.95).toInt()
// 
//         val nodeRect = android.graphics.Rect()
//         val candidates = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
//         val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
//         deque.add(root)
//         while (deque.isNotEmpty()) {
//             val node = deque.poll() ?: continue
//             if (node.isClickable) {
//                 node.getBoundsInScreen(nodeRect)
//                 if (nodeRect.top >= minTop && nodeRect.bottom <= maxBottom &&
//                     nodeRect.left >= minLeft && nodeRect.right <= maxRight) {
//                     candidates.add(node to android.graphics.Rect(nodeRect))
//                 }
//             }
//             for (i in 0 until node.childCount) {
//                 node.getChild(i)?.let { deque.add(it) }
//             }
//         }
// 
//         candidates.sortBy { it.second.centerX() }
//         MessageLog.add("[AUTO] 顶部可点击按钮数: ${candidates.size}")
//         candidates.forEachIndexed { idx, pair ->
//             val txt = pair.first.text?.toString()?.take(10) ?: ""
//             val desc = pair.first.contentDescription?.toString()?.take(10) ?: ""
//             MessageLog.add("[AUTO]   按钮$idx: class=${pair.first.className} id=${pair.first.viewIdResourceName} text='$txt' desc='$desc' centerX=${pair.second.centerX()}")
//         }
// 
//         // 企业微信右上角通常是：...[放大镜][+号]
//         // 最右边是 + 号，倒数第二个才是放大镜搜索按钮
//         val result = when {
//             candidates.isEmpty() -> null
//             candidates.size == 1 -> candidates[0].first
//             else -> {
//                 // 找屏幕最右侧的两个按钮（差距 < 150px 视为一组）
//                 val rightmost = candidates.last()
//                 val secondRightmost = candidates[candidates.size - 2]
//                 val gap = rightmost.second.centerX() - secondRightmost.second.centerX()
//                 if (gap < 150) {
//                     MessageLog.add("[AUTO] 右上角有两个紧邻按钮，选择左边的（放大镜）")
//                     secondRightmost.first
//                 } else {
//                     candidates.last().first
//                 }
//             }
//         }
//         if (result != null) {
//             MessageLog.add("[AUTO] 选择搜索按钮: class=${result.className} id=${result.viewIdResourceName}")
//         }
//         return result
//     }
// 
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        // 发送按钮通常在右下角（输入框右侧），企业微信输入区约在屏幕 55%-60%
        val minLeft = (rootRect.left + rootRect.width() * 0.6).toInt()
        val minTop = (rootRect.top + rootRect.height() * 0.45).toInt()
        val nodeRect = android.graphics.Rect()

        // 0. 通过 resource-id 匹配（最稳定，优先）
        // vivo 版本企业微信发送按钮 ID: i_2（class=Button, text="发送", clickable=true）
        // 注：仅在输入框有文字时才显示，空输入框时该节点不存在
        val byId = root.findAccessibilityNodeInfosByViewId("com.tencent.wework:id/i_2")
        val idMatch = byId.firstOrNull()
        if (idMatch != null && idMatch.isClickable) {
            idMatch.getBoundsInScreen(nodeRect)
            MessageLog.add("[AUTO] 通过resource-id找到发送按钮: bounds=${nodeRect}")
            byId.forEach { if (it !== idMatch) it.recycle() }
            return idMatch
        }
        byId.forEach { it.recycle() }

        // 1. 通过文字匹配（限定右下角区域 + 必须 clickable）
        val byText = root.findAccessibilityNodeInfosByText("发送")
        var result: AccessibilityNodeInfo? = null
        for (node in byText) {
            if (!node.isClickable) continue
            node.getBoundsInScreen(nodeRect)
            if (nodeRect.left >= minLeft && nodeRect.top >= minTop) {
                result = node
                break
            }
        }
        byText.forEach { if (it !== result) it.recycle() }
        if (result != null) {
            MessageLog.add("[AUTO] 通过文字找到发送按钮: bounds=${nodeRect}")
            return result
        }

        // 2. 通过 contentDescription 匹配（同样限定区域）
        val hints = listOf("发送", "Send")
        for (hint in hints) {
            val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
            deque.add(root)
            while (deque.isNotEmpty()) {
                val node = deque.poll() ?: continue
                if (node.isClickable && node.contentDescription?.toString() == hint) {
                    node.getBoundsInScreen(nodeRect)
                    if (nodeRect.left >= minLeft && nodeRect.top >= minTop) {
                        MessageLog.add("[AUTO] 通过contentDescription找到发送按钮: $hint bounds=${nodeRect}")
                        return node
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { deque.add(it) }
                }
            }
        }

        // 3. 通过 class 匹配 Button（限定右下角）
        val byClass = findNodeByClassInLowerScreen(root, "android.widget.Button", 0.25f)
        if (byClass != null) {
            MessageLog.add("[AUTO] 通过Button类找到发送按钮")
            return byClass
        }

        // 4. 旧的兜底逻辑（右下角任意可点击节点）已移除：
        //    在 vivo 上很容易点到底部导航/工具栏，导致发送失败或退出到桌面。
        //    如果上面 1-3 都没找到，说明发送按钮未出现（通常是输入框未聚焦），
        //    应由调用方重新聚焦输入框后重试，而不是乱点。
        MessageLog.add("[AUTO] 未找到符合特征的发送按钮")
        return null
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            if (node.isEditable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        return null
    }

    private fun findNodeByClass(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            if (node.className?.toString() == className) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        return null
    }

    private fun launchWeWork() {
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[AUTO] 监控已停止，不启动企业微信")
            return
        }
        try {
            // 先让 ChaserPA 自己回到前台，绕过 Android 10+ 后台启动 Activity 限制
            val selfIntent = service.packageManager.getLaunchIntentForPackage(service.packageName)
            if (selfIntent != null) {
                selfIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.startActivity(selfIntent)
                MessageLog.add("[AUTO] 先切回 ChaserPA 前台")
            }

            handler.postDelayed({
                var intent = service.packageManager.getLaunchIntentForPackage(PACKAGE_WEWORK)
                if (intent == null) {
                    MessageLog.add("[AUTO] getLaunchIntent 为空，尝试 resolveActivity")
                    val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        `package` = PACKAGE_WEWORK
                    }
                    val resolveInfo = service.packageManager.resolveActivity(queryIntent, 0)
                    if (resolveInfo != null) {
                        val activityName = resolveInfo.activityInfo.name
                        intent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            component = ComponentName(PACKAGE_WEWORK, activityName)
                        }
                    }
                }
                if (intent != null) {
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    service.startActivity(intent)
                    MessageLog.add("[AUTO] 已发送启动企业微信的 Intent")
                } else {
                    MessageLog.add("[AUTO] 无法启动企业微信：resolveActivity 也失败")
                }
            }, 800)
        } catch (e: Exception) {
            MessageLog.add("[AUTO] 启动异常: ${e.javaClass.simpleName} ${e.message}")
            Log.e(TAG, "launchWeWork failed", e)
        }
    }

    private fun dumpTree(root: AccessibilityNodeInfo) {
        val sb = StringBuilder()
        sb.appendLine("[TREE] ===== UI Tree Dump =====")
        sb.appendLine("[TREE] pkg=${root.packageName} class=${root.className} children=${root.childCount}")
        dumpNode(root, sb, 0)
        sb.appendLine("[TREE] ===== End Dump =====")
        MessageLog.add(sb.toString())
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString()?.take(40) ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: "null"
        val clickable = if (node.isClickable) "[C]" else ""
        val desc = node.contentDescription?.toString()?.take(30) ?: ""
        val id = node.viewIdResourceName?.toString()?.take(30) ?: ""
        sb.appendLine("[TREE] $indent$className id='$id' text='$text' desc='$desc' $clickable")
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, sb, depth + 1) }
        }
    }
}
