package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 企业微信保活 Worker。
 *
 * 这是一个独立轮询检测器，定期检查企业微信是否在前台。
 * 如果连续多次检测不到企业微信窗口，就先按 Home 回桌面，再启动企业微信，
 * 从而绕过部分 ROM 对后台直接启动 Activity 的限制。
 */
class KeepAliveWorker(private val service: AccessibilityService) {

    companion object {
        // 企业微信包名
        private const val PACKAGE_WEWORK = "com.tencent.wework"
        // 检测间隔：1 分钟一次，更快发现企业微信不在前台的情况
        private const val CHECK_INTERVAL_MS = 60000L
        // 检测不到立即启动，不累积次数，更快恢复监控
        private const val MISSING_THRESHOLD = 1
    }

    // Handler 用于在主线程调度延迟任务
    private val handler = Handler(Looper.getMainLooper())
    // 是否正在运行
    private var isRunning = false
    // 连续未检测到的次数
    private var missingCount = 0

    /**
     * 启动保活检测。
     *
     * Kotlin 语法提示：
     * - if (isRunning) return 是 Kotlin 中的“守卫语句”，
     *   提前返回，避免重复启动。
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        missingCount = 0
        MessageLog.add("[KEEPALIVE] 保活机制已启动，间隔=${CHECK_INTERVAL_MS/1000}秒")
        scheduleCheck()
    }

    /**
     * 停止保活检测。
     */
    fun stop() {
        isRunning = false
        missingCount = 0
        handler.removeCallbacksAndMessages(null)
        MessageLog.add("[KEEPALIVE] 保活机制已停止")
    }

    /**
     * 安排下一次检测。
     */
    private fun scheduleCheck() {
        if (!isRunning) return
        // postDelayed(Runnable, delayMillis) 在指定毫秒后执行 Runnable
        handler.postDelayed({ doCheck() }, CHECK_INTERVAL_MS)
    }

    /**
     * 执行一次检测。
     */
    private fun doCheck() {
        if (!isRunning) return

        // 如果监控已停止，不再执行保活恢复
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[KEEPALIVE] 监控已停止，跳过本次检查")
            scheduleCheck()
            return
        }

        // 如果 UIPollingCollector 或 ReplyWorker 正在操作 UI，跳过本次检查，
        // 避免和自动化操作冲突
        if (UiController.isBusy) {
            scheduleCheck()
            return
        }

        // rootInActiveWindow 获取当前焦点窗口的 AccessibilityNodeInfo（UI 树根节点）
        val activeRoot = service.rootInActiveWindow
        // 读取当前焦点窗口所属包名
        val pkg = activeRoot?.packageName?.toString()
        // 用完必须 recycle，否则会造成内存泄漏
        activeRoot?.recycle()

        // 如果无法获取当前前台窗口，保守跳过，避免误判
        if (pkg == null) {
            scheduleCheck()
            return
        }

        // ChaserPA 自身在前台时，不要干扰用户操作配置页
        if (pkg == service.packageName) {
            missingCount = 0
            scheduleCheck()
            return
        }

        // 企业微信是否还有任何窗口存活（即使不在焦点窗口）
        // service.windows 返回当前所有可访问窗口列表
        val hasWeWorkWindow = service.windows.any {
            it.root?.packageName?.toString() == PACKAGE_WEWORK
        }

        if (pkg == PACKAGE_WEWORK || hasWeWorkWindow) {
            // 企业微信已在前台或仍有窗口存活，无需操作
            missingCount = 0
            scheduleCheck()
            return
        }

        missingCount++
        MessageLog.add("[KEEPALIVE] 企业微信不在前台(当前pkg=$pkg, 连续${missingCount}次)")

        // 还没达到阈值，继续等待
        if (missingCount < MISSING_THRESHOLD) {
            scheduleCheck()
            return
        }

        MessageLog.add("[KEEPALIVE] 连续${MISSING_THRESHOLD}次检测不到，执行恢复")
        missingCount = 0

        // 先按 Home 回到桌面，再启动企业微信（绕过部分 ROM 的后台启动限制）
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        // 500 毫秒后再启动企业微信，给按 Home 动作留出时间
        handler.postDelayed({
            launchWeWork()
            scheduleCheck()
        }, 500)
    }

    /**
     * 启动企业微信。
     */
    private fun launchWeWork() {
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[KEEPALIVE] 监控已停止，不启动企业微信")
            return
        }
        try {
            // 通过包管理器获取企业微信的启动 Intent
            val intent = service.packageManager.getLaunchIntentForPackage(PACKAGE_WEWORK)
            if (intent != null) {
                /**
                 * 设置 Intent 标志位：
                 * - FLAG_ACTIVITY_NEW_TASK：在新任务栈中启动 Activity。
                 * - FLAG_ACTIVITY_CLEAR_TOP：如果目标 Activity 已在栈顶上方有实例，清除上方实例。
                 * - FLAG_ACTIVITY_REORDER_TO_FRONT：如果 Activity 已在任务栈中，把它移到前台。
                 *
                 * Kotlin 语法提示：or 是按位或，等价于 Java 的 |。
                 */
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                service.startActivity(intent)
                MessageLog.add("[KEEPALIVE] 已启动企业微信")
            } else {
                MessageLog.add("[KEEPALIVE] 无法获取企业微信启动Intent")
            }
        } catch (e: Exception) {
            MessageLog.add("[KEEPALIVE] 启动企业微信异常: ${e.message}")
        }
    }
}
