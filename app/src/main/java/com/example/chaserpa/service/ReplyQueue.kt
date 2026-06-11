package com.example.chaserpa.service

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 本地待回复消息队列，作为 ReplyWorker 从后台拉取结果的缓冲。
 */
object ReplyQueue {
    data class PendingReply(
        val groupName: String,
        val replyText: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val queue = ConcurrentLinkedQueue<PendingReply>()

    fun enqueue(groupName: String, replyText: String) {
        queue.add(PendingReply(groupName, replyText))
        MessageLog.add("[REPLY-QUEUE] Enqueued: $groupName -> ${replyText.take(30)}")
    }

    fun enqueueBatch(replies: List<PendingReply>) {
        queue.addAll(replies)
        MessageLog.add("[REPLY-QUEUE] Enqueued batch: ${replies.size} replies")
    }

    fun dequeue(): PendingReply? = queue.poll()

    fun peek(): PendingReply? = queue.peek()

    fun isEmpty(): Boolean = queue.isEmpty()

    fun size(): Int = queue.size

    fun clear() {
        queue.clear()
    }
}
