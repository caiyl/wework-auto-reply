package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.chaserpa.LaunchBridgeActivity

/**
 * 企业微信启动器。
 *
 * 封装了绕过 Android 10+ 后台启动 Activity 限制的企业微信启动逻辑。
 * 核心思路：启动一个透明的 LaunchBridgeActivity，让它以真实前台上下文启动企业微信。
 *
 * 这个类被 KeepAliveWorker、UIPollingCollector 共享。
 */
object WeWorkLauncher {

    private const val PACKAGE_WEWORK = "com.tencent.wework"
    private const val TAG = "WeWorkLauncher"

    // 主线程 Handler，用于延迟释放启动锁
    private val handler = Handler(Looper.getMainLooper())

    // 全局启动锁，防止多个 Worker 同时触发启动造成混乱
    @Volatile
    private var isLaunching = false
    // 最近一次尝试启动的时间戳
    @Volatile
    private var lastLaunchAttemptTime = 0L
    // 两次启动尝试之间的最小间隔（毫秒）
    private const val LAUNCH_COOLDOWN_MS = 3000L
    // Bridge Activity 启动后多久释放锁
    private const val LOCK_RELEASE_DELAY_MS = 1500L

    /**
     * 启动企业微信。
     *
     * 实现方式：
     * 1. 启动透明的 LaunchBridgeActivity；
     * 2. LaunchBridgeActivity 会立即显示到前台；
     * 3. 它以真实前台 Activity 上下文启动企业微信；
     * 4. 然后 LaunchBridgeActivity 自动 finish。
     *
     * @param service AccessibilityService 实例
     * @param tag 日志标签前缀，方便区分是哪个组件触发的启动
     */
    fun launch(service: AccessibilityService, tag: String = "LAUNCHER") {
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[$tag] 监控已停止，不启动企业微信")
            return
        }

        // 全局冷却：3 秒内不重复执行完整启动流程
        val now = System.currentTimeMillis()
        if (now - lastLaunchAttemptTime < LAUNCH_COOLDOWN_MS) {
            MessageLog.add("[$tag] 全局启动冷却中，跳过")
            return
        }
        lastLaunchAttemptTime = now

        // 全局启动锁，防止并发执行
        if (isLaunching) {
            MessageLog.add("[$tag] 已有启动流程在执行，跳过")
            return
        }
        isLaunching = true

        try {
            val bridgeIntent = Intent(service, LaunchBridgeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(LaunchBridgeActivity.EXTRA_TARGET_PACKAGE, PACKAGE_WEWORK)
            }
            service.startActivity(bridgeIntent)
            MessageLog.add("[$tag] 已启动 LaunchBridgeActivity，准备从真实前台启动企业微信")

            // 延迟释放锁，给 Bridge Activity 完成前台显示和启动企业微信
            handler.postDelayed({
                isLaunching = false
            }, LOCK_RELEASE_DELAY_MS)
        } catch (e: Exception) {
            MessageLog.add("[$tag] 启动企业微信异常: ${e.javaClass.simpleName} ${e.message}")
            Log.e(TAG, "launch failed", e)
            isLaunching = false
        }
    }
}
