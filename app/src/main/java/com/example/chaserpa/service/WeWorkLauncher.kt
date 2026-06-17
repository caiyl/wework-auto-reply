package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 企业微信启动器。
 *
 * 封装了绕过 Android 10+ 后台启动 Activity 限制的企业微信启动逻辑。
 * 核心思路：先把自己（ChaserPA）拉到前台，再用 ChaserPA 的上下文启动企业微信。
 *
 * 这个类被 KeepAliveWorker、UIPollingCollector、WeWorkUIAutomator 共享，
 * 避免到处重复实现同一套启动逻辑。
 */
object WeWorkLauncher {

    private const val PACKAGE_WEWORK = "com.tencent.wework"
    private const val TAG = "WeWorkLauncher"

    // 主线程 Handler，用于延迟启动企业微信
    private val handler = Handler(Looper.getMainLooper())

    // 全局启动锁，防止多个 Worker 同时执行“切回 ChaserPA + 启动企业微信”造成混乱
    @Volatile
    private var isLaunching = false
    // 最近一次尝试启动的时间戳
    @Volatile
    private var lastLaunchAttemptTime = 0L
    // 两次启动尝试之间的最小间隔（毫秒）
    private const val LAUNCH_COOLDOWN_MS = 3000L
    // 切回 ChaserPA 后等待多久再启动企业微信
    private const val LAUNCH_DELAY_MS = 1500L

    /**
     * 启动企业微信。
     *
     * @param service AccessibilityService 实例，用于获取 PackageManager 和启动 Intent
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
            // Step 1: 先把 ChaserPA 自己拉到前台。
            // Android 10+ 限制后台服务直接启动其他应用的 Activity，
            // 但允许前台应用启动 Activity。所以先用自己作为跳板。
            val selfIntent = service.packageManager.getLaunchIntentForPackage(service.packageName)
            if (selfIntent != null) {
                selfIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.startActivity(selfIntent)
                MessageLog.add("[$tag] 先切回 ChaserPA 前台，准备启动企业微信")
            } else {
                MessageLog.add("[$tag] 无法获取 ChaserPA 启动 Intent")
                isLaunching = false
                return
            }

            // Step 2: 延迟 1500ms 后启动企业微信，给系统完成 ChaserPA 前台切换
            handler.postDelayed({
                launchWeWorkInternal(service, tag)
                isLaunching = false
            }, LAUNCH_DELAY_MS)
        } catch (e: Exception) {
            MessageLog.add("[$tag] 启动企业微信异常: ${e.javaClass.simpleName} ${e.message}")
            Log.e(TAG, "launch failed", e)
            isLaunching = false
        }
    }

    /**
     * 实际发送启动企业微信的 Intent。
     */
    private fun launchWeWorkInternal(service: AccessibilityService, tag: String) {
        if (!WeWorkAccessibilityService.isMonitoringEnabled()) {
            MessageLog.add("[$tag] 监控已停止，取消启动企业微信")
            return
        }

        try {
            var intent = service.packageManager.getLaunchIntentForPackage(PACKAGE_WEWORK)

            // 兜底：如果 getLaunchIntentForPackage 为空，手动 resolve 启动 Activity
            if (intent == null) {
                MessageLog.add("[$tag] getLaunchIntent 为空，尝试 resolveActivity")
                val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = PACKAGE_WEWORK
                }
                val resolveInfo = service.packageManager.resolveActivity(queryIntent, 0)
                if (resolveInfo != null) {
                    val activityName = resolveInfo.activityInfo.name
                    intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName(PACKAGE_WEWORK, activityName)
                    }
                }
            }

            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                service.startActivity(intent)
                MessageLog.add("[$tag] 已发送启动企业微信的 Intent")
            } else {
                MessageLog.add("[$tag] 无法启动企业微信：resolveActivity 也失败")
            }
        } catch (e: Exception) {
            MessageLog.add("[$tag] 启动企业微信异常: ${e.javaClass.simpleName} ${e.message}")
            Log.e(TAG, "launchWeWorkInternal failed", e)
        }
    }
}
