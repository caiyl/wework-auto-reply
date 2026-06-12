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
        // 第一阶段：优先返回 rootInActiveWindow（当包名匹配时）
        val active = service.rootInActiveWindow
        return if (active?.packageName?.toString() == packageName) active else null
    }

    override fun extractMessages(root: AccessibilityNodeInfo): List<ChatMessage> {
        // 第一阶段：仅返回空列表，避免崩溃；后续通过运行时诊断日志反推实现
        MessageLog.add("[WECHAT] extractMessages not yet implemented")
        return emptyList()
    }

    override fun sendReply(groupName: String, replyText: String) {
        // 第一阶段：仅记录日志；后续逐步实现
        MessageLog.add("[WECHAT] sendReply not yet implemented: $groupName -> $replyText")
    }
}
