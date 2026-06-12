package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class WeChatPlatform(private val service: AccessibilityService) : ChatPlatform {

    override val packageName: String = "com.tencent.mm"
    override val displayName: String = "微信"

    companion object {
        private const val TAG = "WeChatPlatform"
    }

    override fun findActiveChatRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        // 优先返回 rootInActiveWindow（当包名匹配时）
        val active = service.rootInActiveWindow
        if (active?.packageName?.toString() == packageName) {
            MessageLog.add("[WECHAT] findActiveChatRoot: found active window, children=${active.childCount}")
            return active
        }
        // fallback：遍历所有窗口找微信主窗口
        val windows = service.windows
        var bestRoot: AccessibilityNodeInfo? = null
        var bestChildCount = 0
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == packageName && root.childCount > bestChildCount) {
                bestRoot = root
                bestChildCount = root.childCount
            }
        }
        MessageLog.add("[WECHAT] findActiveChatRoot: fallback bestRoot children=$bestChildCount")
        return bestRoot
    }

    override fun extractMessages(root: AccessibilityNodeInfo): List<ChatMessage> {
        // 诊断阶段：打印整棵 UI 树到日志，帮助反推微信 resource-id 和结构
        MessageLog.add("[WECHAT] extractMessages: dumping UI tree (children=${root.childCount})")
        dumpTree(root)
        return emptyList()
    }

    override fun sendReply(groupName: String, replyText: String) {
        MessageLog.add("[WECHAT] sendReply not yet implemented: $groupName -> $replyText")
    }

    private fun dumpTree(root: AccessibilityNodeInfo) {
        val sb = StringBuilder()
        sb.appendLine("[WECHAT-TREE] ===== UI Tree Dump =====")
        sb.appendLine("[WECHAT-TREE] pkg=${root.packageName} class=${root.className} children=${root.childCount}")
        dumpNode(root, sb, 0)
        sb.appendLine("[WECHAT-TREE] ===== End Dump =====")
        MessageLog.add(sb.toString())
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString()?.take(40) ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: "null"
        val clickable = if (node.isClickable) "[C]" else ""
        val editable = if (node.isEditable) "[E]" else ""
        val scrollable = if (node.isScrollable) "[S]" else ""
        val desc = node.contentDescription?.toString()?.take(30) ?: ""
        val id = node.viewIdResourceName?.toString()?.take(50) ?: ""
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        sb.appendLine(
            "[WECHAT-TREE] $indent$className id='$id' text='$text' desc='$desc' " +
                    "$clickable$editable$scrollable bounds=$bounds"
        )
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, sb, depth + 1) }
        }
    }
}
