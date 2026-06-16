package com.example.chaserpa.service

import android.os.Handler
import android.os.Looper

/**
 * 自动回复任务编排器。
 *
 * 当收到后台返回的回复内容时，不能立即执行 UI 自动化（避免和企业微信界面动画冲突），
 * 所以先把回复任务放进队列，再逐个串行处理。
 *
 * Kotlin 语法提示：
 * - class AutoReplyOrchestrator(private val uiAutomator: WeWorkUIAutomator) 是主构造函数写法，
 *   private val 表示该参数同时是类的私有只读属性。
 */
class AutoReplyOrchestrator(
    private val uiAutomator: WeWorkUIAutomator
) {
    companion object {
        // 队列最大长度，防止内存无限增长
        private const val MAX_QUEUE_SIZE = 10
    }

    /**
     * Handler 用于把任务提交到主线程（UI 线程）执行。
     *
     * Android 中所有 UI 操作都必须在主线程执行。
     * Looper.getMainLooper() 获取主线程的消息循环器，
     * Handler 则像 Java 的调度器，可以 post 延迟任务。
     */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 回复任务队列。
     *
     * Kotlin 语法提示：
     * - ArrayDeque 是 Kotlin/JVM 提供的双端队列，可作为 FIFO 队列使用。
     * - <ReplyTask> 是泛型，和 Java 的 ArrayDeque<ReplyTask> 一样。
     */
    private val queue = ArrayDeque<ReplyTask>()

    // 是否正在处理某个任务，用于串行化
    private var isProcessing = false

    /**
     * 回复任务数据类。
     *
     * Kotlin 语法提示：
     * - data class 是 Kotlin 为“纯数据载体”提供的便捷声明。
     *   编译器会自动生成 equals、hashCode、toString、copy 等方法。
     *   等价于 Java 中写一堆 getter/setter/equals/hashCode。
     */
    data class ReplyTask(val groupName: String, val replyText: String)

    /**
     * 入队一个回复任务。
     *
     * Kotlin 语法提示：
     * - synchronized(this) { ... } 是 Kotlin 对 Java synchronized 关键字的函数式封装，
     *   等价于 Java 的 synchronized(this) { ... }。
     * - "[REPLY] Enqueued: $groupName -> $replyText (queue=${queue.size})" 是字符串模板：
     *   $变量名 会直接替换为变量值，${表达式} 可以插入任意表达式。
     */
    fun enqueue(groupName: String, replyText: String) {
        synchronized(this) {
            // 队列满了就丢弃最老的任务
            if (queue.size >= MAX_QUEUE_SIZE) {
                val dropped = queue.removeFirst()
                MessageLog.add("[REPLY] Queue full, dropped: ${dropped.groupName}")
            }
            queue.addLast(ReplyTask(groupName, replyText))
            MessageLog.add("[REPLY] Enqueued: $groupName -> $replyText (queue=${queue.size})")
        }
        processNext()
    }

    /**
     * 处理队列中的下一个任务。
     *
     * 使用双重检查模式（Double-Check）避免多个任务并发执行。
     */
    private fun processNext() {
        val shouldStart: Boolean
        synchronized(this) {
            // 如果已经在处理，或者队列为空，直接返回
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
        // 调用 UI 自动化器真正发送回复
        uiAutomator.sendReply(task.groupName, task.replyText)

        // 6 秒后认为本次回复完成（包括界面切换、输入、发送动画），再处理下一个
        handler.postDelayed({
            synchronized(this) {
                isProcessing = false
            }
            MessageLog.add("[REPLY] Done: ${task.groupName}")
            processNext()
        }, 6000)
    }

    /**
     * 清空队列并取消所有延迟任务。
     */
    fun clear() {
        synchronized(this) {
            queue.clear()
            isProcessing = false
        }
        // 移除所有通过本 Handler post 的任务和消息，避免内存泄漏
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 判断当前是否还有未完成的任务。
     *
     * Kotlin 语法提示：
     * - 单表达式函数：fun isBusy(): Boolean = synchronized(this) { ... }
     *   花括号内的表达式值就是返回值。
     */
    fun isBusy(): Boolean = synchronized(this) { isProcessing || queue.isNotEmpty() }
}
