package com.example.chaserpa.service

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 本地待回复消息队列。
 *
 * 作为 ReplyWorker 从后台拉取结果的缓冲，
 * 其他模块可以在这里 enqueue，WeWorkUIAutomator 再 dequeue 执行。
 *
 * Kotlin 语法提示：
 * - object ReplyQueue { ... } 是单例声明，整个应用只有这一个队列实例。
 */
object ReplyQueue {
    /**
     * 待回复消息数据类。
     */
    data class PendingReply(
        val groupName: String,
        val replyText: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // 线程安全队列
    private val queue = ConcurrentLinkedQueue<PendingReply>()

    /**
     * 入队一条待回复消息。
     *
     * Kotlin 语法提示：
     * - replyText.take(30) 取字符串前 30 个字符，用于日志Truncation，避免日志过长。
     */
    fun enqueue(groupName: String, replyText: String) {
        queue.add(PendingReply(groupName, replyText))
        MessageLog.add("[REPLY-QUEUE] Enqueued: $groupName -> ${replyText.take(30)}")
    }

    /**
     * 批量入队。
     */
    fun enqueueBatch(replies: List<PendingReply>) {
        queue.addAll(replies)
        MessageLog.add("[REPLY-QUEUE] Enqueued batch: ${replies.size} replies")
    }

    /**
     * 出队一条消息。
     *
     * Kotlin 语法提示：
     * - fun dequeue(): PendingReply? = queue.poll() 是单表达式函数。
     * - PendingReply? 中的 ? 表示返回值可能为 null（队列空时返回 null）。
     */
    fun dequeue(): PendingReply? = queue.poll()

    fun peek(): PendingReply? = queue.peek()

    fun isEmpty(): Boolean = queue.isEmpty()

    fun size(): Int = queue.size

    fun clear() {
        queue.clear()
    }
}
