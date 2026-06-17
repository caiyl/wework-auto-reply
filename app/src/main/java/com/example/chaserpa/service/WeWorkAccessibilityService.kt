package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.chaserpa.data.ConfigRepository

/**
 * 企业微信无障碍服务。
 *
 * 这是整个 App 的核心后台服务，继承自 Android 的 AccessibilityService。
 * 当用户在系统设置中开启本服务的无障碍权限后，系统会把企业微信等应用的
 * 界面变化事件（通知、窗口切换、内容变化等）回调到这里。
 *
 * 主要职责：
 * 1. 监听无障碍事件，判断企业微信前后台状态；
 * 2. 缓存企业微信最新 UI 树根节点（部分 ROM 的 rootInActiveWindow 不可靠）；
 * 3. 管理 MessageCollector、UIPollingCollector、ReplyWorker、KeepAliveWorker 的启停；
 * 4. 提供全局单例访问和监控开关状态查询。
 *
 * Kotlin 语法提示：
 * - class WeWorkAccessibilityService : AccessibilityService() 表示继承，
 *   类似 Java 的 extends AccessibilityService。
 * - 类名后面没有主构造函数，因为 AccessibilityService 由系统通过无参构造创建。
 */
class WeWorkAccessibilityService : AccessibilityService() {

    /**
     * 伴生对象，提供全局访问点。
     *
     * Kotlin 语法提示：
     * - companion object 中的属性/方法类似 Java 的 static。
     * - @Volatile 保证多线程可见性。
     * - var isRunning: Boolean = false
     *       private set
     *   表示该属性对外只读，只能在类内部修改。
     */
    companion object {
        private const val TAG = "WeWorkAccessibilityService"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: WeWorkAccessibilityService? = null
            private set

        /**
         * vivo 系统 rootInActiveWindow / windows 经常返回 null 或错误窗口，
         * 改为通过无障碍事件缓存企业微信最新的根节点，供轮询直接使用。
         */
        @Volatile
        var latestWeWorkRoot: AccessibilityNodeInfo? = null

        /**
         * 外部调用（如 ConfigScreen 切换开关）来更新监控状态。
         */
        fun updateMonitoringState(enabled: Boolean) {
            val svc = instance
            if (svc != null && isRunning) {
                svc.applyMonitoringState(enabled)
            }
        }

        /**
         * 查询当前监控开关状态，Worker/Automator 在操作前调用，
         * 避免停止监控后仍触发轮询、保活、UI 自动化等行为。
         */
        fun isMonitoringEnabled(): Boolean = instance?.monitoringEnabled ?: false

        /**
         * 实时查询用户是否已在系统设置中启用本无障碍服务。
         *
         * 说明：
         * - isRunning 只能反映服务实例是否还活着，如果服务被系统临时回收可能不准确；
         * - 这个方法通过 AccessibilityManager 直接读取系统设置中已启用的服务列表，
         *   只要用户打开过开关就会返回 true，更适合判断“要不要显示去开启按钮”。
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val accessibilityManager =
                context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                    ?: return false
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ) ?: return false
            val componentName = android.content.ComponentName(context.packageName, WeWorkAccessibilityService::class.java.name)
            return enabledServices.any { it.resolveInfo?.serviceInfo?.let { serviceInfo ->
                componentName.packageName == serviceInfo.packageName &&
                        componentName.className == serviceInfo.name
            } == true }
        }
    }

    /**
     * lateinit 延迟初始化属性。
     *
     * Kotlin 语法提示：
     * - lateinit var 表示“延迟初始化”：声明时不需要赋值，但使用前必须初始化，否则会抛异常。
     * - 适合在 onServiceConnected 这类生命周期回调中初始化的场景。
     * - 判断是否已经初始化：::property.isInitialized
     */
    private lateinit var configRepository: ConfigRepository
    private lateinit var deduplicator: MessageDeduplicator
    private lateinit var messagePusher: MessagePusher
    private lateinit var messageCollector: MessageCollector
    private lateinit var uiAutomator: WeWorkUIAutomator
    private lateinit var autoReplyOrchestrator: AutoReplyOrchestrator

    // 可空类型，用 ? 表示，不需要在构造函数中初始化
    private var uiPollingCollector: UIPollingCollector? = null
    private var replyWorker: ReplyWorker? = null
    private var keepAliveWorker: KeepAliveWorker? = null

    @Volatile
    private var monitoringEnabled = true

    @Volatile
    private var isChaserpaForeground = false

    /**
     * 无障碍服务连接成功时回调。
     *
     * 这是服务的入口，相当于 Activity 的 onCreate。
     * 在这里初始化所有组件，并根据当前监控开关状态启动/暂停 Worker。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        MessageLog.add("[SYS] 无障碍服务已连接")
        isRunning = true
        instance = this

        configRepository = ConfigRepository(this)
        monitoringEnabled = configRepository.monitoringEnabled
        val groups = configRepository.targetGroups
        MessageLog.add("[SYS] 配置加载完成，目标群: $groups, 监控状态: $monitoringEnabled")

        deduplicator = MessageDeduplicator()
        uiAutomator = WeWorkUIAutomator(this)
        autoReplyOrchestrator = AutoReplyOrchestrator(uiAutomator)

        messagePusher = MessagePusher(
            backendUrl = configRepository.backendUrl,
            apiKey = configRepository.apiKey,
            onReply = { groupName, replyText ->
                // 同步响应仅记录日志，真正的回复由 ReplyWorker 异步拉取后执行
                MessageLog.add("[AUTO] Backend sync reply (ignored, will pull async): $groupName -> $replyText")
            }
        )

        val myNickname = configRepository.myNickname
        // 消息被采集后的统一处理回调
        val onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit = { message ->
            // 过滤自己发送的消息，防止死循环
            if (myNickname.isNotEmpty() && message.sender == myNickname) {
                MessageLog.add("[SYS] 过滤自己发送的消息: ${message.content}")
            } else {
                val timestamp = System.currentTimeMillis()
                if (!deduplicator.isDuplicate(message.groupName, message.sender, message.content, timestamp)) {
                    MessageLog.add("[CAPTURE] group=${message.groupName}, sender=${message.sender}, content=${message.content}")
                    messagePusher.push(message)
                } else {
                    Log.d(TAG, "Duplicate message ignored: ${message.content}")
                    MessageLog.add("[SYS] 重复消息已忽略")
                }
            }
        }

        messageCollector = MessageCollector(
            config = configRepository,
            onMessageCollected = onMessageCollected
        )
        MessageLog.add("[SYS] MessageCollector 初始化完成")

        uiPollingCollector = UIPollingCollector(
            service = this,
            config = configRepository,
            onMessage = onMessageCollected
        )

        replyWorker = ReplyWorker(
            service = this,
            config = configRepository,
            uiAutomator = uiAutomator
        )

        keepAliveWorker = KeepAliveWorker(this)

        // 初始化时检测一次当前前台状态
        val currentRoot = rootInActiveWindow
        isChaserpaForeground = currentRoot?.packageName?.toString() == packageName
        currentRoot?.recycle()

        // 根据当前监控开关状态启动/暂停
        applyMonitoringState(monitoringEnabled)
    }

    /**
     * 应用监控开关状态。
     */
    private fun applyMonitoringState(enabled: Boolean) {
        monitoringEnabled = enabled
        Log.i(TAG, "applyMonitoringState: enabled=$enabled, foreground=$isChaserpaForeground")
        if (enabled) {
            MessageLog.add("[SYS] 监控已开启")
            UiController.setEnabled(true)
            if (isChaserpaForeground) {
                MessageLog.add("[SYS] 当前在配置页，Worker 暂不启动")
                Log.i(TAG, "ChaserPA in foreground, workers not started")
            } else {
                Log.i(TAG, "Starting all workers")
                startAllWorkers()
            }
        } else {
            MessageLog.add("[SYS] 监控已暂停")
            UiController.setEnabled(false)
            stopAllWorkers()
        }
    }

    private fun startAllWorkers() {
        Log.i(TAG, "startAllWorkers: uiPolling=${uiPollingCollector != null}, reply=${replyWorker != null}, keepAlive=${keepAliveWorker != null}")
        uiPollingCollector?.start()
        replyWorker?.start()
        keepAliveWorker?.start()
    }

    private fun stopAllWorkers() {
        uiPollingCollector?.stop()
        replyWorker?.stop()
        keepAliveWorker?.stop()
        UiController.release()
        autoReplyOrchestrator.clear()
    }

    /**
     * 收到无障碍事件时回调。
     *
     * 这是服务最核心的回调，所有企业微信的界面变化都会进入这里。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()

        // 缓存企业微信最新的根节点（vivo 系统 rootInActiveWindow 不可靠）
        if (pkg == "com.tencent.wework") {
            val source = event.source
            if (source != null) {
                var root: AccessibilityNodeInfo? = source
                // 沿父节点上溯到根节点
                while (root != null) {
                    val parent = root.parent
                    if (parent != null) {
                        if (root !== source) root.recycle()
                        root = parent
                    } else {
                        break
                    }
                }
                if (root != null) {
                    // 复制一份再缓存，避免系统回收后使用失效节点
                    val oldRoot = latestWeWorkRoot
                    latestWeWorkRoot = AccessibilityNodeInfo.obtain(root)
                    oldRoot?.recycle()
                }
            }
        }

        // 监听 ChaserPA 自身前台状态，进入前台时暂停所有 Worker，离开时恢复
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val wasForeground = isChaserpaForeground
            isChaserpaForeground = (pkg == packageName)
            if (wasForeground && !isChaserpaForeground) {
                // 离开 ChaserPA，恢复所有 Worker
                if (monitoringEnabled) {
                    MessageLog.add("[SYS] 离开配置页，恢复 Worker")
                    startAllWorkers()
                }
            } else if (!wasForeground && isChaserpaForeground) {
                // 进入 ChaserPA，暂停所有 Worker
                MessageLog.add("[SYS] 进入配置页，暂停 Worker")
                stopAllWorkers()
            }
        }

        if (!monitoringEnabled) return
        if (!::messageCollector.isInitialized) {
            return
        }
        messageCollector.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    /**
     * 服务销毁时回调。
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
        isRunning = false
        instance = null
        latestWeWorkRoot?.recycle()
        latestWeWorkRoot = null
        uiPollingCollector?.stop()
        replyWorker?.stop()
        keepAliveWorker?.stop()
        // 使用 ::property.isInitialized 判断 lateinit 属性是否已初始化，避免未初始化就访问
        if (::messageCollector.isInitialized) {
            messageCollector.destroy()
        }
        if (::autoReplyOrchestrator.isInitialized) {
            autoReplyOrchestrator.clear()
        }
        if (::deduplicator.isInitialized) {
            deduplicator.clear()
        }
    }
}
