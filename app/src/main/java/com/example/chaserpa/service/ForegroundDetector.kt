package com.example.chaserpa.service

import android.view.accessibility.AccessibilityEvent

/**
 * 前台检测器。
 *
 * 通过无障碍服务事件判断企业微信当前是否在前台。
 * 只在 WINDOW_STATE_CHANGED（窗口状态变化）事件时更新状态，
 * 避免频繁的内容刷新事件导致状态抖动。
 */
class ForegroundDetector {
    companion object {
        // 企业微信包名
        private const val PACKAGE_WEWORK = "com.tencent.wework"
    }

    /**
     * @Volatile 注解。
     *
     * 在 Java/Kotlin 中，普通变量可能被 CPU 缓存，导致多个线程看到的值不一致。
     * @Volatile 保证变量的读写直接操作主内存，类似 Java 的 volatile 关键字。
     * 这里无障碍服务回调可能在后台线程，而 isForeground() 可能在主线程被读取，所以需要可见性保证。
     */
    @Volatile
    private var isForeground: Boolean = false

    /**
     * 接收无障碍事件并更新前台状态。
     *
     * Kotlin 语法提示：
     * - event.packageName?.toString() ?: return 是“安全调用 + Elvis 运算符”组合：
     *   1. event.packageName 是 CharSequence?（可为 null）；
     *   2. ?.toString() 表示“如果不为 null 就调用 toString()，否则整个表达式为 null”；
     *   3. ?: return 表示“如果整个表达式为 null，就直接 return”。
     *   等价于 Java：
     *     CharSequence pkgObj = event.getPackageName();
     *     if (pkgObj == null) return;
     *     String pkg = pkgObj.toString();
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // 只用 WINDOW_STATE_CHANGED 判断前台切换，避免内容刷新导致状态抖动
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            isForeground = (pkg == PACKAGE_WEWORK)
        }
    }

    /**
     * 判断企业微信是否在前台。
     *
     * Kotlin 语法提示：
     * - 单表达式函数 fun isForeground(): Boolean = isForeground
     *   省略了 { return ... }，直接返回表达式结果。
     */
    fun isForeground(): Boolean = isForeground

    fun isBackground(): Boolean = !isForeground
}
