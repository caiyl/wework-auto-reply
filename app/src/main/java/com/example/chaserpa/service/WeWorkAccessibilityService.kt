package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.chaserpa.data.ConfigRepository

class WeWorkAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WeWorkAccessibilityService"
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var configRepository: ConfigRepository
    private lateinit var deduplicator: MessageDeduplicator
    private lateinit var messagePusher: MessagePusher
    private lateinit var messageCollector: MessageCollector
    private lateinit var uiAutomator: WeWorkUIAutomator

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        MessageLog.add("[SYS] 无障碍服务已连接")
        isRunning = true

        configRepository = ConfigRepository(this)
        val groups = configRepository.targetGroups
        MessageLog.add("[SYS] 配置加载完成，目标群: $groups")

        deduplicator = MessageDeduplicator()
        messagePusher = MessagePusher(
            backendUrl = configRepository.backendUrl,
            apiKey = configRepository.apiKey
        )
        uiAutomator = WeWorkUIAutomator(this)
        val myNickname = configRepository.myNickname
        messageCollector = MessageCollector(
            targetGroups = groups
        ) { message ->
            if (!deduplicator.isDuplicate(message.groupName, message.sender, message.content)) {
                messagePusher.push(message)
                if (configRepository.autoReply) {
                    val content = message.content
                    val replyText = "收到：$content"
                    MessageLog.add("[AUTO] 触发自动回复: $replyText")
                    uiAutomator.sendReply(message.groupName, replyText)
                }
            } else {
                Log.d(TAG, "Duplicate message ignored: ${message.content}")
                MessageLog.add("[SYS] 重复消息已忽略")
            }
        }
        MessageLog.add("[SYS] MessageCollector 初始化完成")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::messageCollector.isInitialized) {
            return
        }
        messageCollector.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
        isRunning = false
    }
}
