package com.example.chaserpa.service

/**
 * 全局 UI 操作互斥锁，防止 UIPollingCollector 和 ReplyWorker 同时操作企业微信 UI。
 * 带 30 秒超时自动释放，防止因异常路径未释放而导致死锁。
 */
object UiController {
    @Volatile
    private var busySince: Long = 0
    @Volatile
    private var enabled: Boolean = true

    private const val LOCK_TIMEOUT_MS = 30_000L

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
