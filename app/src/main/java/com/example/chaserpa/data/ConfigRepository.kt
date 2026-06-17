package com.example.chaserpa.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置存储仓库。
 *
 * 这个类负责把用户配置（后台地址、目标群、开关状态等）保存到 Android 的 SharedPreferences 中。
 * SharedPreferences 是 Android 提供的轻量级键值对存储，类似 Java 的 Properties 或小型本地 KV 数据库。
 *
 * Kotlin 语法提示：
 * - class ConfigRepository(context: Context) 是“主构造函数”写在类头里的写法，
 *   等价于 Java 的 public ConfigRepository(Context context) { ... }。
 * - 构造函数的参数如果带 val/var 会自动成为类的属性；这里 context 只是临时使用，所以没有加 val/var。
 */
class ConfigRepository(context: Context) {

    /**
     * SharedPreferences 实例。
     *
     * Kotlin 语法提示：
     * - private val prefs: SharedPreferences = ... 是“声明并立即初始化”的写法。
     * - 类型可以省略（编译器会推断），但这里显式写出方便阅读。
     * - Context.MODE_PRIVATE 表示只有本应用能读写这个配置文件。
     */
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 监控模式枚举。
     *
     * Kotlin 语法提示：
     * - enum class 等价于 Java 的 enum。
     * - 当前只保留 POLLING_ONLY（纯轮询模式）。
     */
    enum class MonitorMode { POLLING_ONLY }

    /**
     * 伴生对象。
     *
     * Kotlin 中没有 static 关键字。companion object { ... } 相当于 Java 的 static 成员区域，
     * 里面的变量/方法可以通过类名直接访问，例如 ConfigRepository.PREFS_NAME。
     * 这里 private const val 表示“私有编译期常量”，类似 Java 的 private static final String。
     */
    companion object {
        private const val PREFS_NAME = "wework_config"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TARGET_GROUPS = "target_groups"
        private const val KEY_AUTO_REPLY = "auto_reply"
        private const val KEY_MY_NICKNAME = "my_nickname"
        private const val KEY_MONITOR_MODE = "monitor_mode"
        private const val KEY_POLL_INTERVAL = "poll_interval"
        private const val KEY_ADAPTIVE_POLL = "adaptive_poll"
        private const val KEY_REPLY_POLL_INTERVAL = "reply_poll_interval"
        private const val KEY_REPLY_BACKEND_URL = "reply_backend_url"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    }

    /**
     * 后台接口地址。
     *
     * Kotlin 语法提示：
     * - var backendUrl: String 下面是自定义 getter/setter，不是普通字段。
     * - get() = ... 表示“读取时执行这段代码”。
     * - prefs.getString(...) ?: "" 中的 ?: 是 Kotlin 的 Elvis 运算符：
     *   如果左边为 null，就返回右边的默认值。这里防止 getString 返回 null。
     * - set(value) = ... 表示“写入时执行这段代码”。
     * - prefs.edit().putString(...).apply() 是 SharedPreferences 的异步提交写法，
     *   不会阻塞主线程；如果需要同步提交可用 commit()。
     */
    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    /**
     * 目标群名称集合。
     *
     * Kotlin 语法提示：
     * - Set<String> 是不可变集合接口（只读）。
     * - get() 里把存储的逗号分隔字符串拆分成 Set：
     *   raw.split(",") → List
     *   .map { it.trim() } → 去空格
     *   .filter { it.isNotEmpty() } → 去空
     *   .toSet() → 转成 Set 去重
     * - set(value) 里把 Set 用 joinToString(",") 拼回字符串保存。
     */
    var targetGroups: Set<String>
        get() {
            val raw = prefs.getString(KEY_TARGET_GROUPS, "") ?: ""
            return if (raw.isEmpty()) emptySet() else raw.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        set(value) = prefs.edit().putString(KEY_TARGET_GROUPS, value.joinToString(",")).apply()

    var autoReply: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY, value).apply()

    var myNickname: String
        get() = prefs.getString(KEY_MY_NICKNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MY_NICKNAME, value).apply()

    /**
     * 监控模式。
     *
     * Kotlin 语法提示：
     * - MonitorMode.valueOf(name) 把字符串转成枚举值，和 Java Enum.valueOf 一样。
     * - try { ... } catch (_: IllegalArgumentException) { ... } 中 catch 的参数用 _ 命名，
     *   表示“虽然要捕获异常，但代码里不打算使用这个异常对象”，类似 Java 中不引用 e。
     */
    var monitorMode: MonitorMode
        get() {
            // 已移除混合模式，始终返回纯轮询模式
            val name = prefs.getString(KEY_MONITOR_MODE, MonitorMode.POLLING_ONLY.name)
            return try {
                MonitorMode.valueOf(name ?: MonitorMode.POLLING_ONLY.name)
            } catch (_: IllegalArgumentException) {
                MonitorMode.POLLING_ONLY
            }
        }
        set(value) = prefs.edit().putString(KEY_MONITOR_MODE, value.name).apply()

    /**
     * 轮询间隔（毫秒）。
     *
     * Kotlin 语法提示：
     * - coerceIn(1000, 10000) 是 Kotlin 标准库的“限定范围”函数，
     *   确保值不会小于 1000 也不会大于 10000。
     *   等价于 Java：Math.max(1000, Math.min(value, 10000))
     */
    var pollInterval: Int
        get() = prefs.getInt(KEY_POLL_INTERVAL, 3000).coerceIn(1000, 10000)
        set(value) = prefs.edit().putInt(KEY_POLL_INTERVAL, value.coerceIn(1000, 10000)).apply()

    var adaptivePoll: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_POLL, true)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_POLL, value).apply()

    var replyPollInterval: Int
        get() = prefs.getInt(KEY_REPLY_POLL_INTERVAL, 5000).coerceIn(1000, 15000)
        set(value) = prefs.edit().putInt(KEY_REPLY_POLL_INTERVAL, value.coerceIn(1000, 15000)).apply()

    var replyBackendUrl: String
        get() = prefs.getString(KEY_REPLY_BACKEND_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REPLY_BACKEND_URL, value).apply()

    var monitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING_ENABLED, value).apply()

    /**
     * 判断用户是否已完成必要配置（至少填写了一个目标群）。
     *
     * Kotlin 语法提示：
     * - 单表达式函数可以写成 fun isConfigured(): Boolean = ...，省略花括号和 return。
     */
    fun isConfigured(): Boolean {
        return targetGroups.isNotEmpty()
    }
}
