package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.chaserpa.data.ConfigRepository

/**
 * 消息采集器（纯轮询模式）。
 *
 * 这个类是企业微信消息采集的核心调度器：
 * 1. 通过无障碍服务的通知事件抓取消息（作为辅助/去重）；
 * 2. 通知 WeWorkAccessibilityService 确保 UI 轮询采集器处于运行状态。
 *
 * 已移除混合模式：不再根据前台/后台状态切换采集策略。
 * UI 轮询由 WeWorkAccessibilityService 直接管理的单一 UIPollingCollector 实例负责，
 * 避免 MessageCollector 内部再维护一个轮询器造成重复轮询。
 *
 * Kotlin 语法提示：
 * - 构造函数中的 onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit 是一个函数类型参数，
 *   表示“接收一个 WeWorkMessage 参数，返回 Unit（void）”的回调函数。
 * - autoReplyOrchestrator: AutoReplyOrchestrator? = null 中的 ? 表示该参数可为 null，
 *   = null 表示调用时如果不传，默认就是 null。
 */
class MessageCollector(
    private val config: ConfigRepository,
    private val onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit
) {
    companion object {
        private const val TAG = "MessageCollector"
    }

    // 通知事件采集器：通过无障碍服务的通知事件抓取消息
    private val notificationCollector = NotificationEventCollector(config.targetGroups, onMessageCollected)

    /**
     * 接收无障碍事件。
     *
     * 纯轮询模式下：
     * 1. 始终尝试从通知事件中提取消息；
     * 2. 通知外部确保 UI 轮询采集器处于运行状态（实际由 WeWorkAccessibilityService 管理）。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        notificationCollector.onAccessibilityEvent(event)
    }

    /**
     * 销毁采集器，释放资源。
     */
    fun destroy() {
        // 通知采集器如有资源需要释放可在这里处理
    }
}
