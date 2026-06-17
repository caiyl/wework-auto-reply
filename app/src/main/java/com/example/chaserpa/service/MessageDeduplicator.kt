package com.example.chaserpa.service

import java.util.LinkedHashMap

/**
 * 消息去重器。
 *
 * 由于通知监听和 UI 轮询可能同时抓到同一条消息，
 * 需要用这个类判断某条消息是否已经在最近处理过。
 *
 * 去重 key = "群名|发送者|内容"，不加入分钟信息，
 * 避免同一条消息在分钟边界处（如 10:59 和 11:00 各抓一次）被重复推送。
 *
 * Kotlin 语法提示：
 * - class MessageDeduplicator(private val maxSize: Int = 100, private val windowMs: Long = MS_FIVE_MINUTES)
 *   是主构造函数带默认参数的写法。调用时可以只传一个、两个或不传。
 *   例如 MessageDeduplicator() 会使用默认值 maxSize=100, windowMs=5分钟。
 */
class MessageDeduplicator(private val maxSize: Int = 100, private val windowMs: Long = MS_DEDUPLICATE_WINDOW) {

    companion object {
        // 默认去重窗口：3 分钟。
        // 选择 3 分钟的原因：
        // - 消息有效时间 MSG_MAX_AGE_MS 为 2 分钟，2 分钟内可能多次进群读取同一条消息；
        // - 3 分钟比 2 分钟略长，可以覆盖多次重复读取的时间范围；
        // - 又不会过长，避免把客户的正常追问（如 2-3 分钟后重复询问）过滤掉。
        private const val MS_DEDUPLICATE_WINDOW = 180_000L
    }

    /**
     * init 块。
     *
     * Kotlin 语法提示：
     * - init { ... } 是“初始化块”，在构造函数执行时运行，类似 Java 的实例初始化块 { ... }。
     * - require(maxSize > 0) { ... } 是前置条件检查，不满足会抛 IllegalArgumentException。
     */
    init {
        require(maxSize > 0) { "maxSize must be > 0" }
    }

    /**
     * 去重缓存。
     *
     * Kotlin 语法提示：
     * - LinkedHashMap<String, Long>(maxSize, 0.75f, true) 的第三个参数 true 表示“按访问顺序排序”。
     *   最近访问的条目会排在后面，最久未访问的排在前面，方便后面按 maxSize 淘汰。
     */
    private val cache = LinkedHashMap<String, Long>(maxSize, 0.75f, true)

    /**
     * 判断是否为重复消息。
     *
     * 如果该 key 已存在且未过期，返回 true（是重复）。
     * 否则加入缓存并返回 false（不是重复）。
     *
     * Kotlin 语法提示：
     * - timestamp: Long = System.currentTimeMillis() 表示参数有默认值，调用时可不传。
     * - "$groupName|$sender|$content" 是字符串模板，直接把变量嵌入字符串。
     * - synchronized(cache) { ... } 保证线程安全。
     */
    fun isDuplicate(groupName: String, sender: String, content: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val key = "$groupName|$sender|$content"

        synchronized(cache) {
            val existing = cache[key]
            if (existing != null && timestamp - existing < windowMs) {
                return true
            }
            cache[key] = timestamp

            // 清理已过期的条目
            val iterator = cache.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (timestamp - entry.value > windowMs) {
                    iterator.remove()
                }
            }

            // 如果缓存超过最大容量，淘汰最旧的条目
            while (cache.size > maxSize) {
                val it = cache.entries.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            return false
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
    }

    fun size(): Int {
        synchronized(cache) {
            return cache.size
        }
    }
}
