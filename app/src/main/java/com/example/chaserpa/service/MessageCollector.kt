package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.chaserpa.data.ConfigRepository

/**
 * 消息采集器（纯轮询模式）。
 *
 * 这个类是企业微信消息采集的核心调度器：
 * 1. 通过无障碍服务的通知事件抓取消息（作为辅助/去重）；
 * 2. 始终启动 UIPollingCollector 定时遍历企业微信 UI 树抓取消息。
 *
 * 已移除混合模式：不再根据前台/后台状态切换采集策略，
 * UI 轮询始终运行，通知事件也始终监听。
 *
 * Kotlin 语法提示：
 * - 构造函数中的 onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit 是一个函数类型参数，
 *   表示“接收一个 WeWorkMessage 参数，返回 Unit（void）”的回调函数。
 * - autoReplyOrchestrator: AutoReplyOrchestrator? = null 中的 ? 表示该参数可为 null，
 *   = null 表示调用时如果不传，默认就是 null。
 */
class MessageCollector(
    private val service: AccessibilityService,
    private val config: ConfigRepository,
    private val onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit,
    private val autoReplyOrchestrator: AutoReplyOrchestrator? = null
) {
    companion object {
        private const val TAG = "MessageCollector"
    }

    // 通知事件采集器：通过无障碍服务的通知事件抓取消息
    private val notificationCollector = NotificationEventCollector(config.targetGroups, onMessageCollected)

    // UI 轮询采集器：定时遍历企业微信 UI 树抓取消息
    private val uiPollingCollector = UIPollingCollector(service, config, onMessageCollected, autoReplyOrchestrator, myNickname = config.myNickname)

    /**
     * 接收无障碍事件。
     *
     * 纯轮询模式下：
     * 1. 始终尝试从通知事件中提取消息；
     * 2. 确保 UI 轮询采集器处于运行状态。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        notificationCollector.onAccessibilityEvent(event)
        ensurePollingRunning()
    }

    /**
     * 确保 UI 轮询已启动。
     */
    private fun ensurePollingRunning() {
        if (!uiPollingCollector.isRunning()) {
            uiPollingCollector.start()
        }
    }

    /**
     * 销毁采集器，释放资源。
     */
    fun destroy() {
        uiPollingCollector.stop()
    }
}
