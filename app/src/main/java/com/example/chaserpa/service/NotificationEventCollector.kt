package com.example.chaserpa.service

import android.app.Notification
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 通知事件采集器。
 *
 * 通过无障碍服务的 TYPE_NOTIFICATION_STATE_CHANGED 事件，
 * 从系统通知栏提取企业微信消息。
 *
 * 注意：当企业微信自身在前台运行时，系统通常不会弹出通知，
 * 所以这种采集方式主要覆盖后台/锁屏场景。
 */
class NotificationEventCollector(
    private val targetGroups: Set<String>,
    private val onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit
) {
    companion object {
        private const val TAG = "NotificationEventCollector"
        private const val PACKAGE_WEWORK = "com.tencent.wework"
    }

    /**
     * 接收无障碍事件。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 只处理通知状态变化事件
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return
        }

        // 读取事件来源包名，如果为 null 则返回 "unknown"
        val pkg = event.packageName?.toString() ?: "unknown"

        // 过滤掉本应用自己产生的通知，避免死循环或干扰
        if (pkg == "com.example.chaserpa") {
            return
        }
        MessageLog.add("[PKG] $pkg")

        // 只处理企业微信的通知
        if (pkg != PACKAGE_WEWORK) {
            return
        }

        handleNotification(event)
    }

    /**
     * 从 AccessibilityEvent 中解析 Notification 对象并提取消息。
     */
    private fun handleNotification(event: AccessibilityEvent) {
        // parcelableData 是事件携带的 Parcelable 数据，对于通知事件通常是 Notification 对象
        val parcelableData = event.parcelableData

        /**
         * Kotlin 语法提示：
         * - is 是 Kotlin 的类型检查关键字，等价于 Java 的 instanceof。
         * - if (parcelableData !is Notification) 表示“如果不是 Notification 类型就返回”。
         */
        if (parcelableData !is Notification) {
            MessageLog.add("[MSG] trace=unknown status=过滤_非通知 reason=数据不是通知")
            return
        }

        // Notification.extras 包含标题、内容等扩展信息
        val extras = parcelableData.extras
        // 标题通常是群名
        val title = extras.getString(Notification.EXTRA_TITLE) ?: extras.getString("android.title")
        // 正文通常是“发送者: 消息内容”
        val text = extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence("android.text")

        // 标题或正文缺失则跳过
        if (title == null || text == null) {
            MessageLog.add("[MSG] trace=${title ?: "unknown"}-unknown status=过滤_信息缺失 reason=标题或内容为空")
            return
        }

        // 如果用户配置了目标群，只采集这些群的消息
        if (targetGroups.isNotEmpty() && !targetGroups.contains(title)) {
            MessageLog.add("[MSG] trace=${title}-unknown status=过滤_非目标群 reason=群不在目标列表 content=${text.toString().take(20)}")
            return
        }

        val senderAndContent = text.toString()
        /**
         * 把“发送者: 内容”拆分开。
         *
         * Kotlin 语法提示：
         * - split(": ", limit = 2) 按 ": " 拆分，limit=2 表示最多分成两段，
         *   这样即使内容里也有 ": " 也不会被继续拆分。
         */
        val parts = senderAndContent.split(": ", limit = 2)
        val sender = if (parts.size >= 2) parts[0] else "未知"
        val content = if (parts.size >= 2) parts[1] else senderAndContent

        MessageLog.add("[NOTIFY] group=$title, sender=$sender, content=$content")
        MessageLog.add("[MSG] trace=${title}-${sender}-${content.take(20)} status=已采集 reason=通知采集")
        onMessageCollected(
            MessagePusher.WeWorkMessage(
                groupName = title,
                sender = sender,
                content = content
            )
        )
    }
}
