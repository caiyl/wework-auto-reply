package com.example.chaserpa.service

import java.util.LinkedHashMap

class MessageDeduplicator(private val maxSize: Int = 100, private val windowMs: Long = MS_FIVE_MINUTES) {

    companion object {
        private const val MS_FIVE_MINUTES = 300_000L
    }

    init {
        require(maxSize > 0) { "maxSize must be > 0" }
    }

    private val cache = LinkedHashMap<String, Long>(maxSize, 0.75f, true)

    /**
     * Checks whether this message already exists (deduplication).
     * If it does not exist or has expired, it is added to the cache and false is returned (not a duplicate).
     * If it exists and has not expired, true is returned (duplicate).
     *
     * NOTE: key does NOT include minute to avoid duplicate pushes when the same message
     * spans across a minute boundary (e.g. polled at 10:59 and again at 11:00).
     */
    fun isDuplicate(groupName: String, sender: String, content: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val key = "$groupName|$sender|$content"

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
