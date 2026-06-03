package com.example.chaserpa.service

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
import java.util.concurrent.TimeUnit

class MessagePusher(
    private val backendUrl: String,
    private val apiKey: String,
    private val onReply: ((String, String) -> Unit)? = null  // (groupName, replyText)
) {
    companion object {
        private const val TAG = "MessagePusher"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class WeWorkMessage(
        val groupName: String,
        val sender: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun push(message: WeWorkMessage) {
        val logLine = "group=${message.groupName}, sender=${message.sender}, content=${message.content}"
        if (backendUrl.isEmpty()) {
            Log.i(TAG, "[PRINT MODE] $logLine")
            MessageLog.add("[PRINT] $logLine")
            return
        }
        if (apiKey.isEmpty()) {
            Log.w(TAG, "API Key not configured, skipping push")
            MessageLog.add("[SKIP] API Key missing")
            return
        }
        MessageLog.add("[PUSH] $logLine")

        val json = JSONObject().apply {
            put("groupName", message.groupName)
            put("sender", message.sender)
            put("content", message.content)
            put("timestamp", message.timestamp)
        }

        val request = Request.Builder()
            .url(backendUrl)
            .header("X-API-Key", apiKey)
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to push message: ${e.message}")
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
                                    onReply(message.groupName, reply)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse reply JSON", e)
                            }
                        }
                        Log.i(TAG, "Message pushed successfully: ${message.groupName} / ${message.sender}")
                    } else {
                        Log.e(TAG, "Push failed with code: ${response.code}")
                    }
                } finally {
                    response.close()
                }
            }
        })
    }
}
