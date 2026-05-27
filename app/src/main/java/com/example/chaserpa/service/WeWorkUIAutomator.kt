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

    private fun findWeWorkWindow(): AccessibilityNodeInfo? {
        val windows = service.windows
        MessageLog.add("[AUTO] findWeWorkWindow: windows=${windows.size}")
        for (window in windows) {
            val root = window.root
            val pkg = root?.packageName?.toString()
            MessageLog.add("[AUTO]   window pkg=$pkg children=${root?.childCount}")
            if (pkg == PACKAGE_WEWORK && root != null && root.childCount >= 3) {
                return root
            }
        }
        for (window in windows) {
            val root = window.root
            if (root?.packageName?.toString() == PACKAGE_WEWORK) {
                return root
            }
        }
        val active = service.rootInActiveWindow
        MessageLog.add("[AUTO]   rootInActiveWindow pkg=${active?.packageName} children=${active?.childCount}")
        return active
    }

    fun sendReply(groupName: String, replyText: String) {
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

    private fun waitForWeWork(groupName: String, replyText: String, retries: Int) {
        if (retries <= 0) {
            MessageLog.add("[AUTO] 等待企业微信出现超时")
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
        val rootNode = findWeWorkWindow()
        if (rootNode == null) {
            MessageLog.add("[AUTO] 无法获取窗口")
            return
        }

        val pkg = rootNode.packageName?.toString()
        val childCount = rootNode.childCount
        MessageLog.add("[AUTO] 当前窗口 pkg=$pkg children=$childCount")

        if (pkg != PACKAGE_WEWORK) {
            MessageLog.add("[AUTO] 窗口包名不对，放弃")
            return
        }

        if (childCount == 0) {
            MessageLog.add("[AUTO] 窗口节点为空，可能企业微信还没加载完")
            dumpTree(rootNode)
            return
        }

        if (childCount < 3 && !isInChatScreen(rootNode, groupName)) {
            if (waitRetries > 0) {
                MessageLog.add("[AUTO] 窗口节点过少($childCount)，等待加载... 剩余$waitRetries")
                handler.postDelayed({ trySend(groupName, replyText, waitRetries - 1) }, 1000)
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
                return
            }

            // 优先用搜索功能定位群聊
            findGroupBySearch(groupName, replyText)
            return
        }

        MessageLog.add("[AUTO] 已在目标群聊，准备输入发送")
        tryTypeAndSend(replyText)
    }

    private fun waitForChatScreen(replyText: String, groupName: String, retries: Int) {
        if (retries <= 0) {
            MessageLog.add("[AUTO] 等待群聊界面超时")
            val root = findWeWorkWindow()
            if (root != null) dumpTree(root)
            return
        }
        val root = findWeWorkWindow()
        if (root != null && isInChatScreen(root, groupName)) {
            MessageLog.add("[AUTO] 已进入群聊界面")
            tryTypeAndSend(replyText)
        } else {
            MessageLog.add("[AUTO] 等待群聊界面...")
            handler.postDelayed({ waitForChatScreen(replyText, groupName, retries - 1) }, 1200)
        }
    }

    private fun tryTypeAndSend(text: String) {
        val rootNode = findWeWorkWindow() ?: return
        val pkg = rootNode.packageName?.toString()
        if (pkg != PACKAGE_WEWORK) {
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
                val freshRoot = findWeWorkWindow()
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

    private fun tryClickSend() {
        handler.postDelayed({
            val freshRoot = findWeWorkWindow()
            if (freshRoot == null) {
                MessageLog.add("[AUTO] 点击发送前窗口丢失")
                return@postDelayed
            }
            val sendBtn = findSendButton(freshRoot)
            if (sendBtn != null) {
                val clickSuccess = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                MessageLog.add("[AUTO] 点击发送结果: $clickSuccess")
                if (clickSuccess) {
                    handler.postDelayed({
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        MessageLog.add("[AUTO] 已按Home键，企业微信切到后台")
                    }, 2000)
                }
            } else {
                MessageLog.add("[AUTO] 找不到发送按钮")
                dumpTree(freshRoot)
            }
        }, 1000)
    }

    private fun isInChatScreen(root: AccessibilityNodeInfo, groupName: String): Boolean {
        val titleNodes = root.findAccessibilityNodeInfosByText(groupName)
        // 企业微信标题可能显示 "群名(人数)"，用 contains 匹配
        // 但必须排除 RecyclerView 中的节点（消息列表项），只匹配顶部标题栏
        val hasTitleByText = titleNodes.any {
            val t = it.text?.toString() ?: ""
            t.contains(groupName) && !isNodeInRecyclerView(it)
        }
        val hasTitleByDesc = titleNodes.any {
            val d = it.contentDescription?.toString() ?: ""
            d.contains(groupName) && !isNodeInRecyclerView(it)
        }
        val hasTitle = hasTitleByText || hasTitleByDesc
        val hasInput = findInputField(root) != null
        MessageLog.add("[AUTO] isInChatScreen: hasTitle=$hasTitle hasInput=$hasInput")
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

    private fun findGroupBySearch(groupName: String, replyText: String) {
        val root = findWeWorkWindow()
        if (root == null || root.packageName?.toString() != PACKAGE_WEWORK) {
            MessageLog.add("[AUTO] 搜索前窗口不对")
            return
        }

        val searchBtn = findSearchButton(root)
        if (searchBtn == null) {
            MessageLog.add("[AUTO] 找不到搜索按钮，fallback 到列表滚动")
            findGroupWithScroll(root, groupName, 5) { groupItem ->
                if (groupItem != null) {
                    val success = groupItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    MessageLog.add("[AUTO] 点击群聊结果: $success")
                    handler.postDelayed({ waitForChatScreen(replyText, groupName, 6) }, 2000)
                } else {
                    MessageLog.add("[AUTO] 找不到群聊 '$groupName'，打印当前UI树")
                    val freshRoot = findWeWorkWindow()
                    if (freshRoot != null) dumpTree(freshRoot)
                }
            }
            return
        }

        val clickSuccess = searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        MessageLog.add("[AUTO] 点击搜索按钮: $clickSuccess")

        handler.postDelayed({
            val searchRoot = findWeWorkWindow()
            if (searchRoot == null || searchRoot.packageName?.toString() != PACKAGE_WEWORK) {
                MessageLog.add("[AUTO] 搜索界面未出现")
                return@postDelayed
            }
            MessageLog.add("[AUTO] 搜索界面已出现，开始找输入框")

            // 1. 先尝试标准 EditText
            var searchInput = findNodeByClass(searchRoot, "android.widget.EditText")
            // 2. Fallback: 找任何可编辑节点
            if (searchInput == null) {
                searchInput = findEditableNode(searchRoot)
                if (searchInput != null) {
                    MessageLog.add("[AUTO] 通过isEditable找到搜索输入框")
                }
            } else {
                MessageLog.add("[AUTO] 通过EditText类找到搜索输入框")
            }
            // 3. Fallback: 如果搜索界面没有输入框，说明点击的可能不是搜索按钮，回退到列表滚动
            if (searchInput == null) {
                MessageLog.add("[AUTO] 搜索界面无输入框，fallback到列表滚动找群聊")
                val listRoot = findWeWorkWindow()
                if (listRoot != null) {
                    findGroupWithScroll(listRoot, groupName, 5) { groupItem ->
                        if (groupItem != null) {
                            val success = groupItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            MessageLog.add("[AUTO] 点击群聊结果: $success")
                            handler.postDelayed({ waitForChatScreen(replyText, groupName, 6) }, 2000)
                        } else {
                            MessageLog.add("[AUTO] 列表滚动也找不到群聊 '$groupName'")
                        }
                    }
                }
                return@postDelayed
            }

            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, groupName)
            val setTextSuccess = searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            MessageLog.add("[AUTO] 输入搜索文字: $setTextSuccess")

            handler.postDelayed({
                val resultRoot = findWeWorkWindow()
                if (resultRoot == null || resultRoot.packageName?.toString() != PACKAGE_WEWORK) {
                    MessageLog.add("[AUTO] 搜索结果窗口丢失")
                    return@postDelayed
                }

                val resultNodes = resultRoot.findAccessibilityNodeInfosByText(groupName)
                MessageLog.add("[AUTO] 搜索结果节点数: ${resultNodes.size}")
                // 企业微信搜索结果可能显示 "群名 (人数) 外部"，用 contains 匹配
                // 必须排除搜索输入框本身（EditText），否则点的是输入框不是结果
                val result = resultNodes.find {
                    val cls = it.className?.toString() ?: ""
                    if (cls.contains("EditText")) return@find false
                    val t = it.text?.toString() ?: ""
                    val d = it.contentDescription?.toString() ?: ""
                    t.contains(groupName) || d.contains(groupName)
                }

                if (result == null) {
                    MessageLog.add("[AUTO] 搜索结果中没有群名，打印UI树")
                    dumpTree(resultRoot)
                    return@postDelayed
                }
                MessageLog.add("[AUTO] 找到搜索结果节点: class=${result.className} text=${result.text}")

                // 向上找可点击父节点，最多10层
                var current: AccessibilityNodeInfo? = result
                var depth = 0
                var clickableNode: AccessibilityNodeInfo? = null
                while (current != null && depth < 10) {
                    if (current.isClickable) {
                        clickableNode = current
                        break
                    }
                    current = current.parent
                    depth++
                }

                // 如果向上找不到 clickable，尝试直接用包含群名的节点点击（部分系统支持）
                if (clickableNode == null) {
                    MessageLog.add("[AUTO] 向上找不到 clickable 父节点，尝试直接点击文本节点")
                    val directClick = result.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    MessageLog.add("[AUTO] 直接点击结果: $directClick")
                    if (directClick) {
                        handler.postDelayed({ waitForChatScreen(replyText, groupName, 6) }, 2000)
                        return@postDelayed
                    }
                }

                if (clickableNode != null) {
                    val clickResult = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    MessageLog.add("[AUTO] 点击搜索结果: $clickResult")
                    handler.postDelayed({ waitForChatScreen(replyText, groupName, 6) }, 2000)
                } else {
                    MessageLog.add("[AUTO] 搜索结果不可点击，fallback 到打印UI树")
                    dumpTree(resultRoot)
                }
            }, 1500)
        }, 1500)
    }

    private fun findGroupWithScroll(root: AccessibilityNodeInfo, groupName: String, maxScrolls: Int, onFound: (AccessibilityNodeInfo?) -> Unit) {
        val groupItem = findGroupInCurrentScreen(root, groupName)
        if (groupItem != null) {
            onFound(groupItem)
            return
        }

        if (maxScrolls <= 0) {
            onFound(null)
            return
        }

        val scrollable = findScrollableContainer(root)
        if (scrollable == null) {
            onFound(null)
            return
        }

        val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        MessageLog.add("[AUTO] 滚动列表结果: $scrolled，剩余次数: $maxScrolls")

        if (scrolled) {
            handler.postDelayed({
                val freshRoot = findWeWorkWindow()
                if (freshRoot != null) {
                    findGroupWithScroll(freshRoot, groupName, maxScrolls - 1, onFound)
                } else {
                    onFound(null)
                }
            }, 1200)
        } else {
            onFound(null)
        }
    }

    private fun findGroupInCurrentScreen(root: AccessibilityNodeInfo, groupName: String): AccessibilityNodeInfo? {
        // 精确匹配文字
        val nodes = root.findAccessibilityNodeInfosByText(groupName)
        val exactNode = nodes.find { it.text?.toString() == groupName }

        if (exactNode == null) {
            return null
        }

        MessageLog.add("[AUTO] 找到群名节点: class=${exactNode.className} text=${exactNode.text} clickable=${exactNode.isClickable}")

        // 向上找列表项父节点
        var current: AccessibilityNodeInfo? = exactNode
        var depth = 0
        var listItem: AccessibilityNodeInfo? = null

        while (current != null && depth < 10) {
            val parent = current.parent

            // 如果父节点是 RecyclerView 或 ListView，current 就是列表项
            if (parent != null && (
                        parent.className?.toString()?.contains("RecyclerView") == true ||
                        parent.className?.toString()?.contains("ListView") == true
                    )) {
                listItem = current
                MessageLog.add("[AUTO] 定位到列表项: class=${current.className} clickable=${current.isClickable}")
                break
            }

            current = parent
            depth++
        }

        // 如果没找到 RecyclerView/ListView 父节点，就退而求其次找可点击父节点
        if (listItem == null) {
            current = exactNode
            depth = 0
            while (current != null && depth < 5) {
                if (current.isClickable) {
                    listItem = current
                    MessageLog.add("[AUTO] 定位到可点击父节点: class=${current.className}")
                    break
                }
                current = current.parent
                depth++
            }
        }

        return listItem
    }

    private fun findScrollableContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            if (node.isScrollable && (
                        node.className?.toString()?.contains("RecyclerView") == true ||
                        node.className?.toString()?.contains("ListView") == true ||
                        node.className?.toString()?.contains("ScrollView") == true
                    )) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        return null
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hints = listOf("发消息", "添加消息内容", "输入消息", "请输入消息")
        for (hint in hints) {
            val nodes = root.findAccessibilityNodeInfosByText(hint)
            val match = nodes.find { it.text?.toString() == hint }
            if (match != null) {
                MessageLog.add("[AUTO] 通过hint找到输入框: $hint")
                return match
            }
        }
        // Fallback 1: 找屏幕底部 30% 区域的 EditText
        val byClass = findNodeByClassInLowerScreen(root, "android.widget.EditText", 0.3f)
        if (byClass != null) {
            MessageLog.add("[AUTO] 通过EditText类找到输入框")
            return byClass
        }
        // Fallback 2: 企业微信可能是自定义输入框，找底部可编辑节点
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val minTop = (rootRect.top + rootRect.height() * 0.7).toInt()
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

    private fun findSearchButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hints = listOf("搜索", "查找", "Search")
        for (hint in hints) {
            val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
            deque.add(root)
            while (deque.isNotEmpty()) {
                val node = deque.poll() ?: continue
                if (node.contentDescription?.toString() == hint) {
                    return node
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { deque.add(it) }
                }
            }
        }
        // Fallback: 找屏幕顶部标题栏的可点击节点，按水平位置排序后返回中间那个
        return findCenterClickableInTopBar(root)
    }

    private fun findCenterClickableInTopBar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        // 扩大搜索区域到顶部 25%，水平方向只排除最边缘 5%
        val minTop = rootRect.top
        val maxBottom = (rootRect.top + rootRect.height() * 0.25).toInt()
        val minLeft = (rootRect.left + rootRect.width() * 0.05).toInt()
        val maxRight = (rootRect.left + rootRect.width() * 0.95).toInt()

        val nodeRect = android.graphics.Rect()
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            if (node.isClickable) {
                node.getBoundsInScreen(nodeRect)
                if (nodeRect.top >= minTop && nodeRect.bottom <= maxBottom &&
                    nodeRect.left >= minLeft && nodeRect.right <= maxRight) {
                    candidates.add(node to android.graphics.Rect(nodeRect))
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }

        candidates.sortBy { it.second.centerX() }
        MessageLog.add("[AUTO] 顶部可点击按钮数: ${candidates.size}")
        candidates.forEachIndexed { idx, pair ->
            val txt = pair.first.text?.toString()?.take(10) ?: ""
            val desc = pair.first.contentDescription?.toString()?.take(10) ?: ""
            MessageLog.add("[AUTO]   按钮$idx: class=${pair.first.className} id=${pair.first.viewIdResourceName} text='$txt' desc='$desc' centerX=${pair.second.centerX()}")
        }

        // 企业微信右上角通常是：...[放大镜][+号]
        // 最右边是 + 号，倒数第二个才是放大镜搜索按钮
        val result = when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates[0].first
            else -> {
                // 找屏幕最右侧的两个按钮（差距 < 150px 视为一组）
                val rightmost = candidates.last()
                val secondRightmost = candidates[candidates.size - 2]
                val gap = rightmost.second.centerX() - secondRightmost.second.centerX()
                if (gap < 150) {
                    MessageLog.add("[AUTO] 右上角有两个紧邻按钮，选择左边的（放大镜）")
                    secondRightmost.first
                } else {
                    candidates.last().first
                }
            }
        }
        if (result != null) {
            MessageLog.add("[AUTO] 选择搜索按钮: class=${result.className} id=${result.viewIdResourceName}")
        }
        return result
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. 通过文字匹配
        val byText = root.findAccessibilityNodeInfosByText("发送")
        val match = byText.find { it.text?.toString() == "发送" }
        if (match != null) {
            MessageLog.add("[AUTO] 通过文字找到发送按钮")
            return match
        }

        // 2. 通过 contentDescription 匹配
        val hints = listOf("发送", "Send")
        for (hint in hints) {
            val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
            deque.add(root)
            while (deque.isNotEmpty()) {
                val node = deque.poll() ?: continue
                if (node.contentDescription?.toString() == hint) {
                    MessageLog.add("[AUTO] 通过contentDescription找到发送按钮: $hint")
                    return node
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { deque.add(it) }
                }
            }
        }

        // 3. 通过 class 匹配 Button
        val byClass = findNodeByClass(root, "android.widget.Button")
        if (byClass != null) {
            MessageLog.add("[AUTO] 通过Button类找到发送按钮")
            return byClass
        }

        // 4. Fallback: 找屏幕右下角的可点击节点（发送按钮通常在右下角）
        val rootRect = android.graphics.Rect()
        root.getBoundsInScreen(rootRect)
        val minLeft = (rootRect.left + rootRect.width() * 0.7).toInt()
        val minTop = (rootRect.top + rootRect.height() * 0.8).toInt()

        val nodeRect = android.graphics.Rect()
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
        val deque = java.util.ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.poll() ?: continue
            if (node.isClickable) {
                node.getBoundsInScreen(nodeRect)
                if (nodeRect.left >= minLeft && nodeRect.top >= minTop) {
                    candidates.add(node to android.graphics.Rect(nodeRect))
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }

        // 选最靠右下的
        candidates.sortWith(compareBy({ it.second.top }, { it.second.left }))
        val result = candidates.lastOrNull()?.first
        if (result != null) {
            MessageLog.add("[AUTO] 通过位置找到发送按钮: class=${result.className} id=${result.viewIdResourceName}")
        }
        return result
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
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
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
