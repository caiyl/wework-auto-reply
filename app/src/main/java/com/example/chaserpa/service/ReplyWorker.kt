package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.chaserpa.data.ConfigRepository
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 回复轮询 Worker。
 *
 * 这是一个独立轮询器，定时从后台 HTTP 接口拉取待回复消息，
 * 然后通过 WeWorkUIAutomator 在企业微信中执行自动回复。
 *
 * 与 MessagePusher 的同步回调不同：
 * - MessagePusher 在推送消息时如果后台立即返回 reply，这里只记录日志不执行；
 * - 真正的回复由 ReplyWorker 异步拉取后串行执行，避免多个回复并发导致 UI 混乱。
 */
class ReplyWorker(
    private val service: AccessibilityService,
    private val config: ConfigRepository,
    private val uiAutomator: WeWorkUIAutomator
) {
    companion object {
        private const val TAG = "ReplyWorker"
        // 默认轮询间隔 5 秒
        private const val DEFAULT_INTERVAL_MS = 5000L
        // 两次回复之间的冷却时间，防止界面动画冲突
        private const val REPLY_COOLDOWN_MS = 6000L
        // 消息最大存活时间：10 分钟，过期丢弃
        private const val MSG_MAX_AGE_MS = 600_000L
    }

    // Handler 用于主线程调度轮询
    private val handler = Handler(Looper.getMainLooper())

    // OkHttp 客户端，用于拉取后台待回复消息
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var isRunning = false
    @Volatile
    private var isProcessing = false

    fun start() {
        if (isRunning) return
        isRunning = true
        // coerceAtLeast(1000L) 保证最小 1 秒间隔
        val interval = config.replyPollInterval.toLong().coerceAtLeast(1000L)
        MessageLog.add("[REPLY-WORKER] 已启动，轮询间隔=${interval}ms")
        scheduleNext(interval)
    }

    fun stop() {
        isRunning = false
        isProcessing = false
        handler.removeCallbacksAndMessages(null)
        MessageLog.add("[REPLY-WORKER] 已停止")
    }

    /**
     * 是否正在忙（处理中或队列非空）。
     */
    fun isBusy(): Boolean = isProcessing || !ReplyQueue.isEmpty()

    /**
     * 安排下一次轮询。
     *
     * Kotlin 语法提示：
     * - scheduleNext(delayMs: Long = ...) 是带默认参数的私有方法。
     * - handler.postDelayed({ doPoll() }, delayMs) 用 Lambda 创建 Runnable。
     */
    private fun scheduleNext(delayMs: Long = config.replyPollInterval.toLong().coerceAtLeast(1000L)) {
        if (!isRunning) return
        handler.postDelayed({ doPoll() }, delayMs)
    }

    /**
     * 执行一次轮询。
     */
    private fun doPoll() {
        if (!isRunning) return

        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[REPLY-WORKER] 监控已停止，跳过本次轮询")
            scheduleNext()
            return
        }

        if (isProcessing) {
            MessageLog.add("[REPLY-WORKER] 正在处理上一条，跳过")
            scheduleNext()
            return
        }

        if (UiController.isBusy) {
            MessageLog.add("[REPLY-WORKER] UI 被占用，跳过本次轮询")
            scheduleNext()
            return
        }

        // 1. 先尝试消费本地队列中已有的消息
        val queuedReply = ReplyQueue.dequeue()
        if (queuedReply != null) {
            executeReply(queuedReply)
            return
        }

        // 2. 本地队列为空，尝试从后台 HTTP 拉取
        val url = config.replyBackendUrl
        if (url.isEmpty()) {
            MessageLog.add("[REPLY-WORKER] 拉取地址未配置，仅等待本地队列")
            scheduleNext()
            return
        }

        fetchFromBackend(url)
    }

    /**
     * 从后台拉取待回复消息。
     */
    private fun fetchFromBackend(url: String) {
        val requestBuilder = Request.Builder().url(url).get()
        if (config.apiKey.isNotEmpty()) {
            requestBuilder.header("X-API-Key", config.apiKey)
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Fetch replies failed: ${e.message}")
                MessageLog.add("[REPLY-WORKER] 拉取失败: ${e.message}")
                // 回调在 OkHttp 后台线程，切回主线程再 scheduleNext
                handler.post { scheduleNext() }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            MessageLog.add("[REPLY-WORKER] 收到响应: ${body.take(200)}")
                            val replies = parseReplies(body)
                            if (replies.isNotEmpty()) {
                                ReplyQueue.enqueueBatch(replies)
                                MessageLog.add("[REPLY-WORKER] 从后台拉取 ${replies.size} 条待回复")
                            } else {
                                MessageLog.add("[REPLY-WORKER] 后台无待回复消息 (replies=0)")
                            }
                        } else {
                            MessageLog.add("[REPLY-WORKER] 后台返回空 body")
                        }
                    } else {
                        MessageLog.add("[REPLY-WORKER] 拉取失败，HTTP ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Parse replies failed", e)
                    MessageLog.add("[REPLY-WORKER] 解析响应失败: ${e.message}")
                } finally {
                    response.close()
                    handler.post { scheduleNext() }
                }
            }
        })
    }

    /**
     * 解析后台返回的 JSON，支持多种常见字段名。
     */
    private fun parseReplies(body: String): List<ReplyQueue.PendingReply> {
        val json = JSONObject(body)
        val array: JSONArray = when {
            json.has("replies") -> json.getJSONArray("replies")
            json.has("messages") -> json.getJSONArray("messages")
            json.has("data") -> json.getJSONArray("data")
            else -> JSONArray()
        }

        val result = mutableListOf<ReplyQueue.PendingReply>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            // 兼容多种字段名：groupName / group
            val groupName = obj.optString("groupName")
                .ifEmpty { obj.optString("group") }
            // 兼容多种字段名：replyText / text / content
            val replyText = obj.optString("replyText")
                .ifEmpty { obj.optString("text") }
                .ifEmpty { obj.optString("content") }
            if (groupName.isNotEmpty() && replyText.isNotEmpty()) {
                result.add(ReplyQueue.PendingReply(groupName, replyText))
            }
        }
        return result
    }

    /**
     * 执行一条回复。
     */
    private fun executeReply(reply: ReplyQueue.PendingReply) {
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[REPLY-WORKER] 监控已停止，放弃执行回复")
            isProcessing = false
            scheduleNext(500)
            return
        }

        // 检查消息是否过期
        val age = System.currentTimeMillis() - reply.timestamp
        if (age > MSG_MAX_AGE_MS) {
            MessageLog.add("[REPLY-WORKER] 消息已过期(${age / 1000}s)，丢弃: ${reply.groupName}")
            scheduleNext(500)
            return
        }

        MessageLog.add("[REPLY-WORKER] 开始回复: ${reply.groupName} -> ${reply.replyText.take(30)}")
        isProcessing = true
        // 获取 UI 操作锁
        UiController.acquire()

        /**
         * 调用 UI 自动化器发送回复，传入完成回调。
         *
         * Kotlin 语法提示：
         * - uiAutomator.sendReply(...) { ... } 是尾随 Lambda，
         *   最后一个参数是函数时可以写在圆括号外面。
         */
        uiAutomator.sendReply(reply.groupName, reply.replyText) {
            isProcessing = false
            UiController.release()
            MessageLog.add("[REPLY-WORKER] 回复完成: ${reply.groupName}")
            scheduleNext()
        }
    }
}
