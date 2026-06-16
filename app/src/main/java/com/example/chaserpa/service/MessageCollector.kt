package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.example.chaserpa.data.ConfigRepository

/**
 * 消息采集器。
 *
 * 这个类是企业微信消息采集的核心调度器：
 * 1. 根据无障碍事件判断企业微信是在前台还是后台；
 * 2. 根据配置选择“混合模式”或“纯轮询模式”；
 * 3. 在不同状态下启动/停止 NotificationEventCollector（通知监听）和 UIPollingCollector（UI 轮询）。
 *
 * Kotlin 语法提示：
 * - 构造函数中的 onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit 是一个函数类型参数，
 *   表示“接收一个 WeWorkMessage 参数，返回 Unit（void）”的回调函数。
 * - autoReplyOrchestrator: AutoReplyOrchestrator? = null 中的 ? 表示该参数可为 null，
 *   = null 表示调用时如果不传，默认就是 null。
 */
class MessageCollector(
    private val service: AccessibilityService,
    private val config: ConfigRepository,
    private val onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit,
    private val autoReplyOrchestrator: AutoReplyOrchestrator? = null
) {
    companion object {
        private const val TAG = "MessageCollector"
        // 状态转换过渡期，避免在前台/后台切换瞬间频繁启停采集器
        private const val TRANSITION_MS = 3000L
    }

    // 前台检测器
    private val foregroundDetector = ForegroundDetector()

    // 通知事件采集器：通过无障碍服务的通知事件抓取消息
    private val notificationCollector = NotificationEventCollector(config.targetGroups, onMessageCollected)

    // UI 轮询采集器：定时遍历企业微信 UI 树抓取消息
    private val uiPollingCollector = UIPollingCollector(service, config, onMessageCollected, autoReplyOrchestrator, myNickname = config.myNickname)

    // Handler 用于主线程调度状态转换定时器
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 过渡期结束后的 Runnable。
     *
     * Kotlin 语法提示：
     * - Runnable { ... } 是 SAM 转换，Kotlin 允许把 Lambda 直接传给 Java 单方法接口，
     *   等价于 Java：new Runnable() { @Override public void run() { ... } }。
     */
    private val transitionRunnable = Runnable {
        if (currentState == State.TRANSITION) {
            // 过渡期结束后，根据当前实际前台状态确定最终状态
            currentState = if (foregroundDetector.isForeground()) State.FOREGROUND else State.BACKGROUND
            MessageLog.add("[STATE] Transition resolved to: $currentState")
            applyCollectorsForCurrentState()
        }
    }

    /**
     * @Volatile 保证 currentState 在多线程间可见。
     * 无障碍服务回调和 Handler 可能在不同线程操作该变量。
     */
    @Volatile
    private var currentState: State = State.BACKGROUND

    @Volatile
    private var transitionEndTime: Long = 0

    /**
     * 状态枚举。
     */
    enum class State {
        BACKGROUND,   // 后台：企业微信不在前台
        FOREGROUND,   // 前台：企业微信在前台
        TRANSITION    // 过渡期：刚刚发生前后台切换，等待稳定
    }

    /**
     * 接收无障碍事件并做状态机和模式分发。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 先更新前台检测器状态
        foregroundDetector.onAccessibilityEvent(event)

        val wasForeground = currentState == State.FOREGROUND
        val isForeground = foregroundDetector.isForeground()

        // 状态转换检测
        when {
            // 当前处于过渡期
            currentState == State.TRANSITION -> {
                if (System.currentTimeMillis() >= transitionEndTime) {
                    // 过渡期已结束，解析最终状态
                    currentState = if (isForeground) State.FOREGROUND else State.BACKGROUND
                    MessageLog.add("[STATE] Transition resolved to: $currentState")
                    applyCollectorsForCurrentState()
                }
                // 过渡期内不再处理后续事件
                return
            }
            // 从前台切到后台，或从后台切到前台，进入过渡期
            wasForeground && !isForeground -> enterTransition()
            !wasForeground && isForeground -> enterTransition()
        }

        // 根据监控模式分发事件处理
        when (config.monitorMode) {
            ConfigRepository.MonitorMode.HYBRID -> handleHybrid(event)
            ConfigRepository.MonitorMode.POLLING_ONLY -> handlePollingOnly(event)
        }
    }

    /**
     * 混合模式处理：通知事件 + 根据状态启停 UI 轮询。
     */
    private fun handleHybrid(event: AccessibilityEvent) {
        notificationCollector.onAccessibilityEvent(event)
        handleHybridState()
    }

    /**
     * 混合模式下的状态机：
     * - 前台：启动 UI 轮询（因为通知监听在企业微信前台时可能失效）；
     * - 后台：停止 UI 轮询（靠通知监听即可）；
     * - 过渡期：启动 UI 轮询兜底。
     */
    private fun handleHybridState() {
        when (currentState) {
            State.FOREGROUND -> {
                if (!uiPollingCollector.isRunning()) uiPollingCollector.start()
            }
            State.BACKGROUND -> {
                if (uiPollingCollector.isRunning()) uiPollingCollector.stop()
            }
            State.TRANSITION -> {
                if (!uiPollingCollector.isRunning()) uiPollingCollector.start()
            }
        }
    }

    /**
     * 纯轮询模式处理：仍然监听通知事件用于去重/辅助，但 UI 轮询始终开启。
     */
    private fun handlePollingOnly(event: AccessibilityEvent) {
        notificationCollector.onAccessibilityEvent(event)
        handlePollingOnlyState()
    }

    private fun handlePollingOnlyState() {
        if (!uiPollingCollector.isRunning()) uiPollingCollector.start()
    }

    /**
     * 根据当前状态应用对应的采集器策略。
     */
    private fun applyCollectorsForCurrentState() {
        when (config.monitorMode) {
            ConfigRepository.MonitorMode.HYBRID -> handleHybridState()
            ConfigRepository.MonitorMode.POLLING_ONLY -> handlePollingOnlyState()
        }
    }

    /**
     * 进入过渡期。
     */
    private fun enterTransition() {
        currentState = State.TRANSITION
        transitionEndTime = System.currentTimeMillis() + TRANSITION_MS
        // 移除旧的过渡期任务，重新 post 一个新的
        handler.removeCallbacks(transitionRunnable)
        handler.postDelayed(transitionRunnable, TRANSITION_MS)
        MessageLog.add("[STATE] Entering TRANSITION state (${TRANSITION_MS}ms)")
    }

    /**
     * 销毁采集器，释放资源。
     */
    fun destroy() {
        handler.removeCallbacks(transitionRunnable)
        uiPollingCollector.stop()
    }
}
