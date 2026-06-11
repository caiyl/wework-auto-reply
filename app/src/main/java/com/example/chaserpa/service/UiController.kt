package com.example.chaserpa.service

/**
 * 全局 UI 操作互斥锁，防止 UIPollingCollector 和 ReplyWorker 同时操作企业微信 UI。
 * 带 30 秒超时自动释放，防止因异常路径未释放而导致死锁。
 */
object UiController {
    @Volatile
    private var busySince: Long = 0

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

    fun acquire(): Boolean {
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
