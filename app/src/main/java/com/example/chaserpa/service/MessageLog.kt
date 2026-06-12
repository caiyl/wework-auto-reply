package com.example.chaserpa.service

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MessageLog {
    private const val TAG = "MessageLog"
    private val _logs = mutableStateListOf<String>()
    val logs: List<String> = _logs

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fileTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 本地文件日志，避免 vivo logcat buffer 被冲掉
    // 使用应用私有外部存储目录，无需额外权限
    private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        logFile = File(dir, "app.log")
    }

    fun add(log: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $log"
        Log.i(TAG, log)
        synchronized(this) {
            _logs.add(0, entry)
            while (_logs.size > 50) {
                _logs.removeAt(_logs.lastIndex)
            }
        }
        // 同时写入本地文件，确保异常和轮询中断也能保留日志
        try {
            val file = logFile ?: return
            val fileEntry = "[${fileTimeFormat.format(Date())}] $log\n"
            file.appendText(fileEntry)
        } catch (_: Exception) {
            // 文件写入失败静默处理，避免影响主流程
        }
    }

    fun clear() {
        _logs.clear()
    }

    fun getAutoLogs(): List<String> {
        return _logs.filter { it.contains("[AUTO]") || it.contains("[CAPTURE]") || it.contains("[PUSH]") }
    }

    fun getLogFilePath(): String = logFile?.absolutePath ?: "未初始化"
}
