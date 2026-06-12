package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.chaserpa.data.ConfigRepository

class ChatAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChatAccessibilityService"
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: ChatAccessibilityService? = null
            private set

        /**
         * vivo 系统 rootInActiveWindow / windows 经常返回 null 或错误窗口，
         * 改为通过无障碍事件缓存目标 App 最新的根节点，供轮询直接使用。
         */
        @Volatile
        var latestChatRoot: AccessibilityNodeInfo? = null

        /**
         * 外部调用（如 ConfigScreen 切换开关）来更新监控状态。
         */
        fun updateMonitoringState(enabled: Boolean) {
            val svc = instance
            if (svc != null && isRunning) {
                svc.applyMonitoringState(enabled)
            }
        }
    }

    private lateinit var configRepository: ConfigRepository
    private lateinit var deduplicator: MessageDeduplicator
    private lateinit var messagePusher: MessagePusher
    private lateinit var messageCollector: MessageCollector
    private lateinit var chatPlatform: ChatPlatform
    private lateinit var autoReplyOrchestrator: AutoReplyOrchestrator
    private var uiPollingCollector: UIPollingCollector? = null
    private var replyWorker: ReplyWorker? = null
    private var keepAliveWorker: KeepAliveWorker? = null

    @Volatile
    private var monitoringEnabled = true

    @Volatile
    private var isChaserpaForeground = false

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
        chatPlatform = createPlatform(configRepository.chatApp)
        autoReplyOrchestrator = AutoReplyOrchestrator(chatPlatform)

        messagePusher = MessagePusher(
            backendUrl = configRepository.backendUrl,
            apiKey = configRepository.apiKey,
            onReply = { groupName, replyText ->
                // 同步响应仅记录日志，真正的回复由 ReplyWorker 异步拉取后执行
                MessageLog.add("[AUTO] Backend sync reply (ignored, will pull async): $groupName -> $replyText")
            }
        )

        val myNickname = configRepository.myNickname
        val onMessageCollected: (ChatMessage) -> Unit = { message ->
            // 过滤自己发送的消息，防止死循环
            if (myNickname.isNotEmpty() && message.sender == myNickname) {
                MessageLog.add("[SYS] 过滤自己发送的消息: ${message.content}")
            } else {
                val timestamp = System.currentTimeMillis()
                if (!deduplicator.isDuplicate(message.groupName, message.sender, message.content, timestamp)) {
                    MessageLog.add("[CAPTURE] group=${message.groupName}, sender=${message.sender}, content=${message.content}")
                    // 转换为 MessagePusher 当前使用的 WeWorkMessage，后续可统一为 ChatMessage
                    val weWorkMessage = MessagePusher.WeWorkMessage(
                        groupName = message.groupName,
                        sender = message.sender,
                        content = message.content,
                        timestamp = timestamp
                    )
                    messagePusher.push(weWorkMessage)
                } else {
                    Log.d(TAG, "Duplicate message ignored: ${message.content}")
                    MessageLog.add("[SYS] 重复消息已忽略")
                }
            }
        }

        messageCollector = MessageCollector(
            service = this,
            config = configRepository,
            chatPlatform = chatPlatform,
            onMessageCollected = onMessageCollected
        )
        MessageLog.add("[SYS] MessageCollector 初始化完成")

        uiPollingCollector = UIPollingCollector(
            service = this,
            config = configRepository,
            chatPlatform = chatPlatform,
            onMessageCollected = onMessageCollected
        )

        replyWorker = ReplyWorker(
            service = this,
            config = configRepository,
            chatPlatform = chatPlatform
        )

        keepAliveWorker = KeepAliveWorker(this)

        // 初始化时检测一次当前前台状态
        val currentRoot = rootInActiveWindow
        isChaserpaForeground = currentRoot?.packageName?.toString() == packageName
        currentRoot?.recycle()

        // 根据当前监控开关状态启动/暂停
        applyMonitoringState(monitoringEnabled)
    }

    private fun createPlatform(chatApp: ChatApp): ChatPlatform {
        return when (chatApp) {
            ChatApp.WEWORK -> WeWorkPlatform(this)
            ChatApp.WECHAT -> WeChatPlatform(this)
        }
    }

    private fun applyMonitoringState(enabled: Boolean) {
        monitoringEnabled = enabled
        Log.i(TAG, "applyMonitoringState: enabled=$enabled, foreground=$isChaserpaForeground")
        if (enabled) {
            MessageLog.add("[SYS] 监控已开启")
            if (isChaserpaForeground) {
                MessageLog.add("[SYS] 当前在配置页，Worker 暂不启动")
                Log.i(TAG, "ChaserPA in foreground, workers not started")
            } else {
                Log.i(TAG, "Starting all workers")
                startAllWorkers()
            }
        } else {
            MessageLog.add("[SYS] 监控已暂停")
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()

        // 缓存目标 App 最新的根节点（vivo 系统 rootInActiveWindow 不可靠）
        if (pkg == chatPlatform.packageName) {
            val source = event.source
            if (source != null) {
                var root: AccessibilityNodeInfo? = source
                while (root != null) {
                    val parent = root.parent
                    if (parent != null) {
                        if (root !== source) root.recycle()
                        root = parent
                    } else {
                        break
                    }
                }
                latestChatRoot = root
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

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
        isRunning = false
        instance = null
        uiPollingCollector?.stop()
        replyWorker?.stop()
        keepAliveWorker?.stop()
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
