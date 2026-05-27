package com.example.chaserpa.service

import java.util.LinkedHashMap

class MessageDeduplicator(private val maxSize: Int = 100, private val windowMs: Long = 30000) {

    private val cache = LinkedHashMap<String, Long>(maxSize, 0.75f, true)

    /**
     * 检查该消息是否已存在（去重）。
     * 如果不存在或已过期，则加入缓存并返回 false（表示不重复）。
     * 如果存在且未过期，返回 true（表示重复）。
     */
    fun isDuplicate(groupName: String, sender: String, content: String): Boolean {
        val key = "$groupName|$sender|$content"
        val now = System.currentTimeMillis()

        synchronized(cache) {
            val existing = cache[key]
            if (existing != null && now - existing < windowMs) {
                return true
            }
            cache[key] = now
            val iterator = cache.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > windowMs) {
                    iterator.remove()
                } else {
                    break
                }
            }
            while (cache.size > maxSize) {
                cache.remove(cache.keys.first())
            }
            return false
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
    }
}
