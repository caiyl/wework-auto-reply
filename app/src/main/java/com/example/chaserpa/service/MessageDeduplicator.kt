package com.example.chaserpa.service

import java.util.LinkedHashMap

class MessageDeduplicator(private val maxSize: Int = 100, private val windowMs: Long = MS_PER_MINUTE) {

    companion object {
        private const val MS_PER_MINUTE = 60_000L
    }

    init {
        require(maxSize > 0) { "maxSize must be > 0" }
    }

    private val cache = LinkedHashMap<String, Long>(maxSize, 0.75f, true)

    /**
     * Checks whether this message already exists (deduplication).
     * If it does not exist or has expired, it is added to the cache and false is returned (not a duplicate).
     * If it exists and has not expired, true is returned (duplicate).
     */
    fun isDuplicate(groupName: String, sender: String, content: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val minute = timestamp / MS_PER_MINUTE
        val key = "$groupName|$sender|$content|$minute"

        synchronized(cache) {
            val existing = cache[key]
            if (existing != null && timestamp - existing < windowMs) {
                return true
            }
            cache[key] = timestamp
            val iterator = cache.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (timestamp - entry.value > windowMs) {
                    iterator.remove()
                }
            }
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
