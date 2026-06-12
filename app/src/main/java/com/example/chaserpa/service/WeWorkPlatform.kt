package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class WeWorkPlatform(private val service: AccessibilityService) : ChatPlatform {

    override val packageName: String = "com.tencent.wework"
    override val displayName: String = "企业微信"

    companion object {
        private const val TAG = "WeWorkPlatform"
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 获取企业微信的主窗口。
     * vivo 版企业微信有左侧边栏窗口（ix_），会作为独立窗口出现。
     * 优先选择包含消息列表 RecyclerView（czp）或输入框特征的主窗口，
     * 避免选中侧边栏导致 UI 操作失败。
     */
    override fun findActiveChatRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val windows = service.windows
        MessageLog.add("[AUTO] findWeWorkWindow: windows=${windows.size}")
        var chatRoot: AccessibilityNodeInfo? = null
        var listRoot: AccessibilityNodeInfo? = null
        for (window in windows) {
            val root = window.root
            val pkg = root?.packageName?.toString()
            MessageLog.add("[AUTO]   window pkg=$pkg children=${root?.childCount}")
            if (pkg == packageName && root != null) {
                // 群聊页窗口优先（有输入框），其次消息列表页窗口（有 RecyclerView）。
                // 不能只看 childCount，否则容易选中侧栏或错误窗口。
                val hasInput = findInputField(root) != null
                val hasRecycler = root.findAccessibilityNodeInfosByViewId("$packageName:id/czp").isNotEmpty()
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

    override fun extractMessages(root: AccessibilityNodeInfo): List<ChatMessage> {
        // 初始 stub，消息提取逻辑在 Task 5 中从 UIPollingCollector 迁移过来
        MessageLog.add("[WEWORK] extractMessages not yet migrated")
        return emptyList()
    }

    override fun sendReply(groupName: String, replyText: String) {
        MessageLog.add("[AUTO] 准备回复群 '$groupName': $replyText")
        Log.d(TAG, "sendReply: group=$groupName, text=$replyText")

        val rootNode = findActiveChatRoot(service)
        val pkg = rootNode?.packageName?.toString()
        Log.d(TAG, "current pkg=$pkg, root=${rootNode != null}")

        if (rootNode == null || pkg != packageName) {
            MessageLog.add("[AUTO] 当前不在企业微信(pkg=$pkg)，尝试启动")
            launchWeWork()
            handler.postDelayed({ waitForWeWork(groupName, replyText, 8) }, 1500)
            return
        }

        trySend(groupName, replyText)
    }

    private fun waitForWeWork(groupName: String, replyText: String, retries: Int) {
        if (retries <= 0) {
            MessageLog.add("[AUTO] 等待企业微信出现超时")
            return
        }
        val root = findActiveChatRoot(service)
        val pkg = root?.packageName?.toString()
        Log.d(TAG, "waitForWeWork: retries=$retries, pkg=$pkg")

        if (pkg == packageName && root != null) {
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
            UiController.release()
            return
        }

        val rootNode = findActiveChatRoot(service)
        if (rootNode == null) {
            MessageLog.add("[AUTO] 无法获取窗口")
            emergencyRecover()
            UiController.release()
            return
        }

        val pkg = rootNode.packageName?.toString()
        val childCount = rootNode.childCount
        MessageLog.add("[AUTO] 当前窗口 pkg=$pkg children=$childCount")

        if (pkg != packageName) {
            MessageLog.add("[AUTO] 窗口包名不对，放弃")
            rootNode.recycle()
            emergencyRecover()
            UiController.release()
            return
        }

        if (childCount == 0) {
            MessageLog.add("[AUTO] 窗口节点为空，可能企业微信还没加载完")
            dumpTree(rootNode)
            rootNode.recycle()
            emergencyRecover()
            UiController.release()
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
            val recyclerNodes = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/czp")
            if (recyclerNodes.isNotEmpty()) {
                MessageLog.add("[AUTO] 当前在消息列表页，尝试直接点击群聊项")
                val recycler = recyclerNodes.firstOrNull()
                var clicked = false
                if (recycler != null) {
                    for (i in 0 until minOf(recycler.childCount, 10)) {
                        val item = recycler.getChild(i) ?: continue
                        val nameNodes = item.findAccessibilityNodeInfosByViewId("$packageName:id/hrr")
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
                // 不在消息列表页，也不在聊天页（可能是通讯录/工作台等），切回消息列表
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
            UiController.release()
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

        val recyclerNodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/czp")
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
            val root = findActiveChatRoot(service)
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
                    val root2 = findActiveChatRoot(service)
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
            val root = findActiveChatRoot(service)
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
                    val root2 = findActiveChatRoot(service)
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
            val root = findActiveChatRoot(service)
            if (root != null) dumpTree(root)
            return
        }
        val root = findActiveChatRoot(service)
        if (root != null && isInChatScreen(root, groupName)) {
            MessageLog.add("[AUTO] 已进入群聊界面")
            tryTypeAndSend(replyText)
        } else {
            MessageLog.add("[AUTO] 等待群聊界面...")
            handler.postDelayed({ waitForChatScreen(replyText, groupName, retries - 1) }, 1200)
        }
    }

    private fun tryTypeAndSend(text: String) {
        val rootNode = findActiveChatRoot(service) ?: return
        val pkg = rootNode.packageName?.toString()
        if (pkg != packageName) {
            MessageLog.add("[AUTO] 打字前窗口变了 pkg=$pkg")
            return
        }

        val inputNode = findInputField(rootNode)
        if (inputNode == null) {
            MessageLog.add("[AUTO] 找不到输入框")
            dumpTree(rootNode)
            return
        }

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        var setTextSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        MessageLog.add("[AUTO] 输入文字结果: $setTextSuccess")

        // 如果直接输入失败（企业微信输入框可能是 View），先点击聚焦，等真正输入框出现
        if (!setTextSuccess) {
            MessageLog.add("[AUTO] 直接输入失败，尝试点击输入框聚焦")
            val focusSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val clickSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            MessageLog.add("[AUTO] 聚焦=$focusSuccess 点击=$clickSuccess")

            handler.postDelayed({
                val freshRoot = findActiveChatRoot(service)
                if (freshRoot == null) {
                    MessageLog.add("[AUTO] 聚焦后窗口丢失")
                    return@postDelayed
                }
                val newInput = findInputField(freshRoot)
                if (newInput != null && newInput != inputNode) {
                    MessageLog.add("[AUTO] 聚焦后出现新输入框: class=${newInput.className}")
                    val newArgs = Bundle()
                    newArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    setTextSuccess = newInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, newArgs)
                    MessageLog.add("[AUTO] 新输入框输入结果: $setTextSuccess")
                } else {
                    MessageLog.add("[AUTO] 聚焦后输入框未变化，尝试在旧节点再次输入")
                    val retryArgs = Bundle()
                    retryArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    setTextSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, retryArgs)
                    MessageLog.add("[AUTO] 重试输入结果: $setTextSuccess")
                }
                if (setTextSuccess) {
                    tryClickSend()
                } else {
                    MessageLog.add("[AUTO] 多次输入均失败，放弃")
                }
            }, 800)
            return
        }

        tryClickSend()
    }

    private fun tryClickSend(retryCount: Int = 0) {
        handler.postDelayed({
            val freshRoot = findActiveChatRoot(service)
            if (freshRoot == null) {
                MessageLog.add("[AUTO] 点击发送前窗口丢失")
                ensureBackToMessageList(attempt = 1)
                return@postDelayed
            }
            val sendBtn = findSendButton(freshRoot)
            if (sendBtn != null) {
                val btnRect = android.graphics.Rect()
                sendBtn.getBoundsInScreen(btnRect)
                val centerX = (btnRect.left + btnRect.right) / 2f
                val centerY = (btnRect.top + btnRect.bottom) / 2f

                val clickSuccess = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                MessageLog.add("[AUTO] 点击发送结果: $clickSuccess, bounds=$btnRect")
                sendBtn.recycle()
                freshRoot.recycle()
                if (clickSuccess) {
                    handler.postDelayed({
                        verifySendAndBack(retryCount)
                    }, 1500)
                } else if (retryCount < 1) {
                    // ACTION_CLICK 对企业微信某些 Button 不生效，改用坐标手势点击
                    MessageLog.add("[AUTO] ACTION_CLICK 失败，尝试坐标点击 ($centerX, $centerY)")
                    clickAt(centerX, centerY)
                    handler.postDelayed({
                        verifySendAndBack(retryCount + 1)
                    }, 1500)
                } else {
                    MessageLog.add("[AUTO] 发送最终失败，直接Back退出")
                    ensureBackToMessageList(attempt = 1)
                }
            } else {
                MessageLog.add("[AUTO] 找不到发送按钮")
                dumpTree(freshRoot)
                freshRoot.recycle()
                ensureBackToMessageList(attempt = 1)
            }
        }, 1000)
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
        val root = findActiveChatRoot(service)
        if (root == null) {
            MessageLog.add("[AUTO] 发送验证时窗口丢失")
            ensureBackToMessageList(attempt = 1)
            return
        }
        val inputNode = findInputField(root)
        val inputText = inputNode?.text?.toString() ?: ""
        inputNode?.recycle()
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
        }
    }

    /**
     * 备选发送方案：直接再次点击发送按钮（有时第一次点击未生效）。
     */
    private fun tryEnterSend(retryCount: Int) {
        val root = findActiveChatRoot(service)
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
        val root = findActiveChatRoot(service)
        if (root != null) {
            val clicked = clickBackButton(root)
            root.recycle()
            if (clicked) {
                MessageLog.add("[AUTO] 已点击左上角返回按钮")
                handler.postDelayed({
                    val verifyRoot = findActiveChatRoot(service)
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
            val verifyRoot = findActiveChatRoot(service)
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
        val backNodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/nc9")
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
            if (root.packageName?.toString() != packageName) {
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
        // Fallback 3: 企业微信外部群输入框可能是TextView/View，找底部最宽的节点
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
        val deque2 = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque2.add(root)
        while (deque2.isNotEmpty()) {
            val node = deque2.poll() ?: continue
            val cls = node.className?.toString() ?: ""
            if (cls.contains("EditText") || cls.contains("TextView") || cls.contains("View")) {
                node.getBoundsInScreen(nodeRect)
                if (nodeRect.top >= minTop && nodeRect.width() > rootRect.width() * 0.3) {
                    candidates.add(node to android.graphics.Rect(nodeRect))
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque2.add(it) }
            }
        }
        candidates.sortByDescending { it.second.width() }
        val widest = candidates.firstOrNull()?.first
        if (widest != null) {
            MessageLog.add("[AUTO] 通过底部最宽节点找到输入框: class=${widest.className}")
            return widest
        }
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
        try {
            // 先让 ChaserPA 自己回到前台，绕过 Android 10+ 后台启动 Activity 限制
            val selfIntent = service.packageManager.getLaunchIntentForPackage(service.packageName)
            if (selfIntent != null) {
                selfIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.startActivity(selfIntent)
                MessageLog.add("[AUTO] 先切回 ChaserPA 前台")
            }

            handler.postDelayed({
                var intent = service.packageManager.getLaunchIntentForPackage(packageName)
                if (intent == null) {
                    MessageLog.add("[AUTO] getLaunchIntent 为空，尝试 resolveActivity")
                    val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        `package` = packageName
                    }
                    val resolveInfo = service.packageManager.resolveActivity(queryIntent, 0)
                    if (resolveInfo != null) {
                        val activityName = resolveInfo.activityInfo.name
                        intent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            component = ComponentName(packageName, activityName)
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
        val byId = root.findAccessibilityNodeInfosByViewId("$packageName:id/i_2")
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

        // 4. Fallback: 找屏幕右下角的可点击节点
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
        val deque2 = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque2.add(root)
        while (deque2.isNotEmpty()) {
            val node = deque2.poll() ?: continue
            if (node.isClickable) {
                node.getBoundsInScreen(nodeRect)
                if (nodeRect.left >= minLeft && nodeRect.top >= minTop) {
                    candidates.add(node to android.graphics.Rect(nodeRect))
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque2.add(it) }
            }
        }

        candidates.sortWith(compareBy({ it.second.top }, { it.second.left }))
        val fallback = candidates.lastOrNull()?.first
        if (fallback != null) {
            MessageLog.add("[AUTO] 通过位置找到发送按钮: class=${fallback.className} id=${fallback.viewIdResourceName}")
        }
        return fallback
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
