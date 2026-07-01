package com.example.chaserpa.service

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息日志单例。
 *
 * 这个对象同时做三件事：
 * 1. 输出到 Android Logcat（开发调试）；
 * 2. 保存到 Compose 的 mutableStateListOf，让配置页可以实时显示最近 50 条日志；
 * 3. 写入本地文件，避免部分机型 Logcat 缓存被冲掉后无法排查问题。
 *
 * Kotlin 语法提示：
 * - object MessageLog { ... } 是 Kotlin 的“单例声明”，编译器会自动生成单例类，
 *   等价于 Java 的 public class MessageLog { private MessageLog(){} public static final MessageLog INSTANCE = ... }。
 *   使用时直接 MessageLog.add(...)，不需要 new。
 */
object MessageLog {
    private const val TAG = "MessageLog"

    /**
     * 内部使用的可变状态列表。
     *
     * Kotlin/Compose 语法提示：
     * - mutableStateListOf<String>() 创建“可观察列表”。
     *   向列表添加/删除元素时，Compose 会自动刷新引用该列表的 UI（如 ConfigScreen 里的 LazyColumn）。
     * - 以下划线 _logs 开头是 Kotlin 常见约定，表示“私有内部实现”。
     */
    private val _logs = mutableStateListOf<String>()

    /**
     * 对外暴露的只读列表。
     *
     * Kotlin 语法提示：
     * - List<String> 是只读接口；外部只能读取，不能修改。
     * - _logs 和 logs 引用的是同一个对象，但通过接口类型限制外部行为。
     */
    val logs: List<String> = _logs

    // 界面显示用的时间格式：HH:mm:ss
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    // 文件日志用的时间格式：MM-dd HH:mm:ss.SSS
    private val fileTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 本地文件日志，避免 vivo logcat buffer 被冲掉
    // 使用应用私有外部存储目录，无需额外权限
    private var logFile: File? = null

    /**
     * 初始化日志文件路径。
     *
     * Kotlin 语法提示：
     * - context.getExternalFilesDir(null) 获取应用私有外部存储目录，
     *   对应 Android/data/包名/files/，不需要存储权限。
     * - dir.mkdirs() 创建多级目录（如果不存在）。
     */
    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        logFile = File(dir, "app.log")
    }

    /**
     * 添加一条日志。
     *
     * Kotlin 语法提示：
     * - Date() 创建当前时间对象。
     * - "[$timestamp] $log" 是字符串模板。
     * - synchronized(this) 保证多线程下操作 _logs 安全。
     * - _logs.add(0, entry) 在列表头部插入，最新的显示在最上面。
     * - _logs.lastIndex 是列表最后一个有效下标。
     */
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

    /**
     * 获取与自动回复/采集相关的日志，用于复制关键日志。
     *
     * Kotlin 语法提示：
     * - _logs.filter { ... } 对列表按条件过滤，返回新列表。
     * - it 是 Lambda 单参数默认名，代表当前元素。
     * - || 是逻辑或。
     */
    fun getAutoLogs(): List<String> {
        return _logs.filter { it.contains("[AUTO]") || it.contains("[CAPTURE]") || it.contains("[PUSH]") || it.contains("[MSG]") }
    }

    /**
     * 获取日志文件路径，用于排查问题时定位。
     */
    fun getLogFilePath(): String = logFile?.absolutePath ?: "未初始化"
}
