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
 * 独立回复轮询线程，定时从后台 HTTP 拉取待回复消息，并通过 WeWorkUIAutomator 执行 UI 回复。
 */
class ReplyWorker(
    private val service: AccessibilityService,
    private val config: ConfigRepository,
    private val uiAutomator: WeWorkUIAutomator
) {
    companion object {
        private const val TAG = "ReplyWorker"
        private const val DEFAULT_INTERVAL_MS = 5000L
        private const val REPLY_COOLDOWN_MS = 6000L
        private const val MSG_MAX_AGE_MS = 600_000L // 10分钟
    }

    private val handler = Handler(Looper.getMainLooper())
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
        val interval = config.replyPollInterval.toLong().coerceAtLeast(2000L)
        MessageLog.add("[REPLY-WORKER] 已启动，轮询间隔=${interval}ms")
        scheduleNext(interval)
    }

    fun stop() {
        isRunning = false
        isProcessing = false
        handler.removeCallbacksAndMessages(null)
        MessageLog.add("[REPLY-WORKER] 已停止")
    }

    fun isBusy(): Boolean = isProcessing || !ReplyQueue.isEmpty()

    private fun scheduleNext(delayMs: Long = config.replyPollInterval.toLong().coerceAtLeast(2000L)) {
        if (!isRunning) return
        handler.postDelayed({ doPoll() }, delayMs)
    }

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

    private fun fetchFromBackend(url: String) {
        val requestBuilder = Request.Builder().url(url).get()
        if (config.apiKey.isNotEmpty()) {
            requestBuilder.header("X-API-Key", config.apiKey)
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Fetch replies failed: ${e.message}")
                MessageLog.add("[REPLY-WORKER] 拉取失败: ${e.message}")
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
            val groupName = obj.optString("groupName")
                .ifEmpty { obj.optString("group") }
            val replyText = obj.optString("replyText")
                .ifEmpty { obj.optString("text") }
                .ifEmpty { obj.optString("content") }
            if (groupName.isNotEmpty() && replyText.isNotEmpty()) {
                result.add(ReplyQueue.PendingReply(groupName, replyText))
            }
        }
        return result
    }

    private fun executeReply(reply: ReplyQueue.PendingReply) {
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[REPLY-WORKER] 监控已停止，放弃执行回复")
            isProcessing = false
            scheduleNext(500)
            return
        }

        val age = System.currentTimeMillis() - reply.timestamp
        if (age > MSG_MAX_AGE_MS) {
            MessageLog.add("[REPLY-WORKER] 消息已过期(${age / 1000}s)，丢弃: ${reply.groupName}")
            scheduleNext(500)
            return
        }

        MessageLog.add("[REPLY-WORKER] 开始回复: ${reply.groupName} -> ${reply.replyText.take(30)}")
        isProcessing = true
        UiController.acquire()

        uiAutomator.sendReply(reply.groupName, reply.replyText) {
            isProcessing = false
            UiController.release()
            MessageLog.add("[REPLY-WORKER] 回复完成: ${reply.groupName}")
            scheduleNext()
        }
    }
}
