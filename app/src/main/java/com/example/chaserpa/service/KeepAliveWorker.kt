package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 企业微信保活 Worker，独立轮询检测企业微信是否在前台。
 * 如果不在前台，先 HOME 回桌面再启动企业微信，绕过部分 ROM 的后台启动限制。
 */
class KeepAliveWorker(private val service: AccessibilityService) {

    companion object {
        private const val PACKAGE_WEWORK = "com.tencent.wework"
        private const val CHECK_INTERVAL_MS = 180000L // 3分钟检测一次，减少对用户操作的干扰
        private const val MISSING_THRESHOLD = 2 // 连续2次检测不到才恢复，减少误判
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var missingCount = 0

    fun start() {
        if (isRunning) return
        isRunning = true
        missingCount = 0
        MessageLog.add("[KEEPALIVE] 保活机制已启动，间隔=${CHECK_INTERVAL_MS/60000}分钟")
        scheduleCheck()
    }

    fun stop() {
        isRunning = false
        missingCount = 0
        handler.removeCallbacksAndMessages(null)
        MessageLog.add("[KEEPALIVE] 保活机制已停止")
    }

    private fun scheduleCheck() {
        if (!isRunning) return
        handler.postDelayed({ doCheck() }, CHECK_INTERVAL_MS)
    }

    private fun doCheck() {
        if (!isRunning) return

        // 如果监控已停止，不再执行保活恢复
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[KEEPALIVE] 监控已停止，跳过本次检查")
            scheduleCheck()
            return
        }

        // 如果 UIPollingCollector 或 ReplyWorker 正在操作 UI，跳过本次检查
        if (UiController.isBusy) {
            scheduleCheck()
            return
        }

        val activeRoot = service.rootInActiveWindow
        val pkg = activeRoot?.packageName?.toString()
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

        if (missingCount < MISSING_THRESHOLD) {
            scheduleCheck()
            return
        }

        MessageLog.add("[KEEPALIVE] 连续${MISSING_THRESHOLD}次检测不到，执行恢复")
        missingCount = 0

        // 先按 Home 回到桌面，再启动企业微信（绕过部分 ROM 的后台启动限制）
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        handler.postDelayed({
            launchWeWork()
            scheduleCheck()
        }, 500)
    }

    private fun launchWeWork() {
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[KEEPALIVE] 监控已停止，不启动企业微信")
            return
        }
        try {
            val intent = service.packageManager.getLaunchIntentForPackage(PACKAGE_WEWORK)
            if (intent != null) {
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
