package com.example.chaserpa

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.chaserpa.service.MessageLog

/**
 * 启动桥接 Activity。
 *
 * 用途：
 * - 当 AccessibilityService 无法直接从后台启动企业微信时，先启动这个 Activity；
 * - 这个 Activity 会立即显示到前台，然后以真实前台上下文启动企业微信；
 * - 启动完成后立刻 finish，用户不会看到界面。
 *
 * 为什么有效：
 * - Android 10+ 限制后台服务直接启动其他应用的 Activity；
 * - 但系统允许前台 Activity 启动其他 Activity；
 * - 所以这个透明 Activity 作为跳板，绕过后台启动限制。
 */
class LaunchBridgeActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_TARGET_ACTIVITY = "target_activity"
        private const val PACKAGE_WEWORK = "com.tencent.wework"
        private const val LAUNCH_DELAY_MS = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageLog.add("[BRIDGE] LaunchBridgeActivity 已启动到前台")

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: PACKAGE_WEWORK
        val targetActivity = intent.getStringExtra(EXTRA_TARGET_ACTIVITY)

        // 延迟 300ms 后启动目标应用，给本 Activity 完成前台显示
        Handler(Looper.getMainLooper()).postDelayed({
            launchTargetApp(targetPackage, targetActivity)
            // 启动后关闭并移除本任务，避免用户按返回键回到这里
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        }, LAUNCH_DELAY_MS)
    }

    /**
     * 从真实前台 Activity 上下文启动目标应用。
     */
    private fun launchTargetApp(packageName: String, activityName: String?) {
        try {
            val intent = if (activityName != null) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(packageName, activityName)
                }
            } else {
                packageManager.getLaunchIntentForPackage(packageName)
            }

            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(intent)
                MessageLog.add("[BRIDGE] 已从前台 Activity 启动 $packageName")
            } else {
                MessageLog.add("[BRIDGE] 无法获取 $packageName 启动 Intent")
            }
        } catch (e: Exception) {
            MessageLog.add("[BRIDGE] 启动异常: ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
