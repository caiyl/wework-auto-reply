package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

interface ChatPlatform {
    val packageName: String
    val displayName: String

    /**
     * 从当前无障碍窗口中找到目标 App 的主窗口根节点。
     * 调用方负责回收返回的 AccessibilityNodeInfo。
     */
    fun findActiveChatRoot(service: AccessibilityService): AccessibilityNodeInfo?

    /**
     * 从窗口根节点提取消息列表。
     * 实现类应返回新列表，调用方负责回收 root。
     */
    fun extractMessages(root: AccessibilityNodeInfo): List<ChatMessage>

    /**
     * 执行自动回复。实现类内部负责切到指定群并发送消息。
     */
    fun sendReply(groupName: String, replyText: String)
}
