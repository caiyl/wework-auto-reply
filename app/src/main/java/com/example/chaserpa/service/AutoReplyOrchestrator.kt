package com.example.chaserpa.service

import android.os.Handler
import android.os.Looper

class AutoReplyOrchestrator(
    private val uiAutomator: WeWorkUIAutomator
) {
    companion object {
        private const val MAX_QUEUE_SIZE = 10
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<ReplyTask>()
    private var isProcessing = false

    data class ReplyTask(val groupName: String, val replyText: String)

    fun enqueue(groupName: String, replyText: String) {
        synchronized(this) {
            if (queue.size >= MAX_QUEUE_SIZE) {
                val dropped = queue.removeFirst()
                MessageLog.add("[REPLY] Queue full, dropped: ${dropped.groupName}")
            }
            queue.addLast(ReplyTask(groupName, replyText))
            MessageLog.add("[REPLY] Enqueued: $groupName -> $replyText (queue=${queue.size})")
        }
        processNext()
    }

    private fun processNext() {
        val shouldStart: Boolean
        synchronized(this) {
            if (isProcessing || queue.isEmpty()) return
            isProcessing = true
            shouldStart = true
        }
        if (!shouldStart) return

        val task: ReplyTask
        synchronized(this) {
            task = queue.removeFirst()
        }
        MessageLog.add("[REPLY] Executing: ${task.groupName}")
        uiAutomator.sendReply(task.groupName, task.replyText)
        handler.postDelayed({
            synchronized(this) {
                isProcessing = false
            }
            MessageLog.add("[REPLY] Done: ${task.groupName}")
            processNext()
        }, 6000)
    }

    fun clear() {
        synchronized(this) {
            queue.clear()
            isProcessing = false
        }
        handler.removeCallbacksAndMessages(null)
    }

    fun isBusy(): Boolean = synchronized(this) { isProcessing || queue.isNotEmpty() }
}
