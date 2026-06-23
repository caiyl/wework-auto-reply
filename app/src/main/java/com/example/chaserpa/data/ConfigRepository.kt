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
        private const val KEY_ADAPTIVE_REPLY_POLL = "adaptive_reply_poll"
        private const val KEY_REPLY_BACKEND_URL = "reply_backend_url"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    }

    /**
     * 后台接口地址。
     *
     * 用途：
     * - 采集到的企业微信消息会以 JSON 格式 POST 到这个地址。
     * - 推送时机：当 UI 轮询或通知采集发现目标群有新消息，并经过去重过滤后。
     * - 推送内容示例：{"groupName":"客户群A","sender":"chase@微信","content":"我要咨询","timestamp":1234567890}
     *
     * 留空行为：
     * - 如果为空字符串，MessagePusher 不会发起网络请求，只在日志中打印消息内容。
     *
     * 鉴权：
     * - 如果 apiKey 也配置了，每次 POST 都会带请求头 X-API-Key。
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

    /**
     * API Key。
     *
     * 用途：
     * - 用于后端接口鉴权。
     * - 配置后，MessagePusher 在推送消息时会在 HTTP 请求头中加入：X-API-Key: <apiKey>。
     * - ReplyWorker 在拉取待回复消息时也会携带同样的请求头。
     *
     * 留空行为：
     * - 如果为空字符串，则不添加 X-API-Key 请求头。
     *
     * 安全提示：
     * - 该值以明文形式保存在 SharedPreferences 中，不要填入高敏感凭证。
     */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    /**
     * 目标群名称集合。
     *
     * 用途：
     * - 指定需要监控的企业微信群名称。
     * - UIPollingCollector 在扫描消息列表时，只有群名匹配到此集合中的项，才会点击进入群聊读取消息。
     *
     * 填写要求：
     * - 必须和企业微信消息列表中显示的群名完全一致（包括空格、表情、特殊符号）。
     * - 多个群名之间用英文逗号 ',' 或中文逗号 '，' 分隔。
     *
     * 存储格式：
     * - 内存中是 Set<String>，写入 SharedPreferences 时序列化为逗号分隔字符串。
     * - 读取时按中英文逗号拆分，并自动 trim 去空格、过滤空字符串、去重。
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

    /**
     * 自动回复开关。
     *
     * 用途：
     * - 控制是否启用自动回复功能。
     * - 开启后，ReplyWorker 会定时从后台拉取待回复消息，并通过 UI 自动化在企业微信中发送。
     *
     * 当前状态：
     * - 默认值为 true。
     * - 配置页已移除该开关（按用户要求默认自动回复），保存配置时固定写入 true。
     */
    var autoReply: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY, value).apply()

    /**
     * 我的昵称。
     *
     * 用途：
     * - 填写你在企业微信中的显示昵称。
     * - 用于过滤你自己发送的消息，防止自动回复死循环。
     *
     * 为什么必填：
     * - 自动回复发送消息后，企业微信聊天界面会显示这条新消息。
     * - 如果不过滤，App 可能把刚自动回复出去的消息又识别为新消息，再次触发回复。
     * - 当消息发送者 sender 等于此昵称时，该消息会被丢弃。
     *
     * 填写要求：
     * - 必须和企业微信中显示的个人昵称完全一致。
     * - 例如在外部群里显示为 "chase@微信"，就填写 "chase@微信"。
     */
    var myNickname: String
        get() = prefs.getString(KEY_MY_NICKNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MY_NICKNAME, value).apply()

    /**
     * 监控模式。
     *
     * 用途：
     * - 历史上曾支持通知模式、混合模式、纯轮询模式。
     * - 当前已移除混合模式，始终返回 POLLING_ONLY（纯轮询模式）。
     *
     * 保留原因：
     * - 兼容旧版本配置：如果用户之前保存过其他模式字符串，读取时自动降级为 POLLING_ONLY。
     *
     * Kotlin 语法提示：
     * - MonitorMode.valueOf(name) 把字符串转成枚举值，和 Java Enum.valueOf 一样。
     * - try { ... } catch (_: IllegalArgumentException) { ... } 中 catch 的参数用 _ 命名，
     *   表示“虽然要捕获异常，但代码里不打算使用这个异常对象”，类似 Java 中不引用 e。
     */
    var monitorMode: MonitorMode
        get() {
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
     * 用途：
     * - 控制 UIPollingCollector 扫描企业微信消息列表的频率。
     * - 也就是每隔多久检查一次消息列表是否有新消息。
     *
     * 取值范围：
     * - 最小 1000ms（1 秒），最大 10000ms（10 秒）。
     * - 写入和读取时都会用 coerceIn 强制限制在这个范围内。
     *
     * 默认值：3000ms（3 秒）。
     *
     * 调优建议：
     * - 消息频繁、需要快速响应：设为 1000-2000ms。
     * - 消息稀疏、注重省电：设为 5000-10000ms。
     * - 如果 adaptivePoll 开启，实际间隔会在该值基础上动态调整（忙时最快 2 秒，闲时最慢 5 秒）。
     *
     * Kotlin 语法提示：
     * - coerceIn(1000, 10000) 是 Kotlin 标准库的“限定范围”函数，
     *   确保值不会小于 1000 也不会大于 10000。
     *   等价于 Java：Math.max(1000, Math.min(value, 10000))
     */
    var pollInterval: Int
        get() = prefs.getInt(KEY_POLL_INTERVAL, 3000).coerceIn(1000, 10000)
        set(value) = prefs.edit().putInt(KEY_POLL_INTERVAL, value.coerceIn(1000, 10000)).apply()

    /**
     * 自适应频率开关。
     *
     * 用途：
     * - 控制 UIPollingCollector 是否根据消息活跃度动态调整轮询速度。
     *
     * 逻辑：
     * - 开启后，当某一轮扫描发现新消息，立即把轮询间隔加速到 2 秒。
     * - 连续 3 轮没有发现新消息，则把间隔放慢到 5 秒。
     * - 其他时间使用 pollInterval 的设置值。
     *
     * 默认值：true（建议开启，可在响应速度和功耗之间取得平衡）。
     */
    var adaptivePoll: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_POLL, true)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_POLL, value).apply()

    /**
     * 回复轮询间隔（毫秒）。
     *
     * 用途：
     * - 控制 ReplyWorker 从后台拉取待回复消息的频率。
     * - 也就是每隔多久问一次后台“有没有需要自动回复的消息”。
     *
     * 取值范围：
     * - 最小 1000ms（1 秒），最大 15000ms（15 秒）。
     *
     * 默认值：5000ms（5 秒）。
     *
     * 调优建议：
     * - 需要快速自动回复：设为 1000-2000ms。
     * - 回复不紧急、注重省电：设为 8000-15000ms。
     */
    var replyPollInterval: Int
        get() = prefs.getInt(KEY_REPLY_POLL_INTERVAL, 5000).coerceIn(1000, 15000)
        set(value) = prefs.edit().putInt(KEY_REPLY_POLL_INTERVAL, value.coerceIn(1000, 15000)).apply()

    /**
     * 回复自适应频率开关。
     *
     * 用途：
     * - 控制 ReplyWorker 是否根据后台回复活跃度动态调整拉取速度。
     *
     * 逻辑：
     * - 开启后，当后台返回待回复消息时，立即把拉取间隔加速到 2 秒。
     * - 连续 3 轮没有待回复消息，则把间隔放慢到 8 秒。
     * - 其他时间使用 replyPollInterval 的设置值。
     *
     * 默认值：true（建议开启，可在回复响应速度和功耗之间取得平衡）。
     */
    var adaptiveReplyPoll: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_REPLY_POLL, true)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_REPLY_POLL, value).apply()

    /**
     * 回复拉取地址。
     *
     * 用途：
     * - ReplyWorker 从这个 HTTP 地址 GET 待回复消息列表。
     * - 后台返回 JSON 格式，支持字段名：replies / messages / data。
     * - 单条消息支持字段名：groupName / group，replyText / text / content。
     *
     * 留空行为：
     * - 如果为空字符串，ReplyWorker 会复用 backendUrl 作为拉取地址。
     * - 如果 backendUrl 也为空，则只等待本地队列，不主动拉取。
     *
     * 典型响应示例：
     * {"replies":[{"groupName":"客户群A","replyText":"您好"}]}
     */
    var replyBackendUrl: String
        get() = prefs.getString(KEY_REPLY_BACKEND_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REPLY_BACKEND_URL, value).apply()

    /**
     * 监控总开关。
     *
     * 用途：
     * - 控制 App 是否对企业微信执行消息采集和自动回复。
     * - 开启时，WeWorkAccessibilityService 会启动 UIPollingCollector、ReplyWorker、KeepAliveWorker。
     * - 关闭时，所有 Worker 停止，App 不再操作企业微信 UI。
     *
     * 注意：
     * - 这只是 App 内部开关。如果系统设置里关闭了无障碍服务，此开关无效。
     *
     * 默认值：true。
     */
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
