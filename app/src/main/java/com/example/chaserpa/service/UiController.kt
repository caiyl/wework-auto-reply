package com.example.chaserpa.service

/**
 * 全局 UI 操作互斥锁。
 *
 * UIPollingCollector 和 ReplyWorker 都会操作企业微信 UI（点击、输入、发送等），
 * 必须互斥，否则两者同时操作会导致界面混乱。
 *
 * 这个单例使用 busySince 时间戳记录加锁时间，并带 30 秒超时自动释放，
 * 防止因异常路径未释放而导致死锁。
 *
 * Kotlin 语法提示：
 * - object UiController { ... } 是单例声明，全局只有一个实例。
 */
object UiController {
    /**
     * @Volatile 保证多线程可见性。
     * busySince = 0 表示未加锁；非 0 表示加锁时的系统时间戳。
     */
    @Volatile
    private var busySince: Long = 0

    @Volatile
    private var enabled: Boolean = true

    // 锁超时时间：30 秒
    private const val LOCK_TIMEOUT_MS = 30_000L

    /**
     * 是否处于忙碌状态（已加锁且未超时）。
     *
     * Kotlin 语法提示：
     * - val isBusy: Boolean 后面跟 get() { ... } 是“自定义 getter 的属性”，
     *   每次读取 isBusy 都会执行花括号里的代码。
     */
    val isBusy: Boolean
        get() {
            val since = busySince
            if (since == 0L) return false
            if (System.currentTimeMillis() - since > LOCK_TIMEOUT_MS) {
                busySince = 0
                MessageLog.add("[UI-LOCK] 锁超时自动释放")
                return false
            }
            return true
        }

    /**
     * 监控开关控制：停止监控后 UiController 直接拒绝加锁，
     * 避免已经 stop 的 Worker 继续执行 UI 操作。
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            busySince = 0
            MessageLog.add("[UI-LOCK] 监控已停止，锁强制释放")
        }
    }

    /**
     * 尝试获取锁。
     * @return true 获取成功，false 获取失败（已加锁或监控已停止）。
     */
    fun acquire(): Boolean {
        if (!enabled) {
            MessageLog.add("[UI-LOCK] 监控已停止，拒绝加锁")
            return false
        }
        if (isBusy) return false
        busySince = System.currentTimeMillis()
        MessageLog.add("[UI-LOCK] 已获取")
        return true
    }

    fun release() {
        if (busySince != 0L) {
            busySince = 0
            MessageLog.add("[UI-LOCK] 已释放")
        }
    }
}
