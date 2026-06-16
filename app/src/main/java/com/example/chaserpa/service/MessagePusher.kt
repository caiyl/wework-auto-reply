package com.example.chaserpa.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * 消息推送器。
 *
 * 负责把采集到的企业微信消息推送到用户后台服务器。
 * 如果后台返回 {"reply":"回复内容"}，还会触发自动回复。
 *
 * Kotlin 语法提示：
 * - onReply: ((String, String) -> Unit)? = null 是“可为空的函数类型参数”。
 *   表示一个接收两个 String、返回 Unit 的回调，调用者可以不传。
 *   这里两个 String 分别是 groupName（群名）和 replyText（回复内容）。
 */
class MessagePusher(
    private val backendUrl: String,
    private val apiKey: String,
    private val onReply: ((String, String) -> Unit)? = null  // (groupName, replyText)
) {
    companion object {
        private const val TAG = "MessagePusher"
        private const val MAX_QUEUE_SIZE = 100
        // OkHttp 需要的 MediaType，Companion.toMediaType() 是 Kotlin 的扩展函数用法
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * OkHttp 客户端。
     *
     * Kotlin 语法提示：
     * - OkHttpClient.Builder()...build() 是经典的 Builder 模式链式调用。
     * - connectTimeout(10, TimeUnit.SECONDS) 设置连接超时 10 秒。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 待推送消息队列。
     *
     * Kotlin 语法提示：
     * - ConcurrentLinkedQueue 是线程安全的无界队列，适合多线程生产/消费场景。
     */
    private val pendingQueue = ConcurrentLinkedQueue<WeWorkMessage>()

    // Handler 用于把重试任务调度回主线程
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 企业微信消息数据类。
     *
     * Kotlin 语法提示：
     * - data class 会自动生成 toString、equals、hashCode、copy 等。
     * - val 表示构造后不可变，类似 Java 的 final 字段。
     * - timestamp: Long = System.currentTimeMillis() 带默认值。
     */
    data class WeWorkMessage(
        val groupName: String,
        val sender: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 推送一条消息。
     *
     * 如果后台地址为空，则只打印日志（打印模式）。
     */
    fun push(message: WeWorkMessage) {
        val logLine = "group=${message.groupName}, sender=${message.sender}, content=${message.content}"
        if (backendUrl.isEmpty()) {
            Log.i(TAG, "[PRINT MODE] $logLine")
            MessageLog.add("[PRINT] $logLine")
            return
        }
        MessageLog.add("[PUSH] $logLine")
        // 先尝试推送之前失败缓存的消息
        flushPending()
        doPush(message)
    }

    /**
     * 真正执行 HTTP 推送。
     *
     * @param retryCount 当前重试次数，默认 0。
     */
    private fun doPush(message: WeWorkMessage, retryCount: Int = 0) {
        // 后台 API 格式: {"groupName":"测试群2","sender":"李四","content":"早上好"}

        /**
         * JSONObject().apply { ... }。
         *
         * Kotlin 语法提示：
         * - apply 是标准库函数，会在对象上执行 Lambda 并返回该对象本身。
         * - 在 Lambda 内部可以用 this 调用对象方法，这里省略了 this，直接写 put(...)。
         *   等价于：
         *     val json = JSONObject();
         *     json.put("groupName", ...);
         *     json.put(...);
         */
        val json = JSONObject().apply {
            put("groupName", message.groupName)
            put("sender", message.sender)
            put("content", message.content)
        }

        val requestBuilder = Request.Builder()
            .url(backendUrl)
            .header("Content-Type", "application/json")
            // json.toString().toRequestBody(JSON) 把 JSON 字符串转成请求体
            .post(json.toString().toRequestBody(JSON))

        // 如果有 API Key，加到请求头
        if (apiKey.isNotEmpty()) {
            requestBuilder.header("X-API-Key", apiKey)
        }

        val request = requestBuilder.build()

        /**
         * 异步发送请求。
         *
         * Kotlin 语法提示：
         * - object : Callback { ... } 是匿名内部类实现 OkHttp 的 Callback 接口。
         * - 因为 Callback 有两个抽象方法，所以不能用 SAM 转换，必须显式写 object。
         */
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Push failed: ${e.message}, retry=$retryCount")
                MessageLog.add("[PUSH_FAIL] ${message.groupName}, retry=$retryCount")
                if (retryCount < 2) {
                    // 指数退避重试：第 1 次 3 秒，第 2 次 6 秒
                    handler.postDelayed({ doPush(message, retryCount + 1) }, 3000L * (retryCount + 1))
                } else {
                    // 重试 2 次仍然失败，加入待重试队列
                    enqueuePending(message)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null && onReply != null) {
                            try {
                                val json = org.json.JSONObject(body)
                                val reply = json.optString("reply", "")
                                if (reply.isNotEmpty()) {
                                    // 触发自动回复回调
                                    onReply(message.groupName, reply)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse reply JSON", e)
                            }
                        }
                        Log.i(TAG, "Message pushed successfully: ${message.groupName} / ${message.sender}")
                    } else {
                        Log.e(TAG, "Push failed with code: ${response.code}")
                        enqueuePending(message)
                    }
                } finally {
                    // 必须关闭响应体，防止连接泄漏
                    response.close()
                }
            }
        })
    }

    /**
     * 把失败消息加入待推送队列。
     */
    private fun enqueuePending(message: WeWorkMessage) {
        if (pendingQueue.size >= MAX_QUEUE_SIZE) {
            pendingQueue.poll() // 移除队头最旧消息
        }
        pendingQueue.offer(message)
        MessageLog.add("[PUSH_QUEUE] Message queued for retry (${pendingQueue.size})")
    }

    /**
     * 清空并重新推送待推送队列中的消息。
     */
    private fun flushPending() {
        val copy = mutableListOf<WeWorkMessage>()
        while (pendingQueue.isNotEmpty()) {
            // poll() 取出并移除队头，?.let 表示不为 null 才执行
            pendingQueue.poll()?.let { copy.add(it) }
        }
        copy.forEach { doPush(it) }
    }
}
