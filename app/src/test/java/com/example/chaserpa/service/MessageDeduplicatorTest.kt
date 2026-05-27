package com.example.chaserpa.service

import org.junit.Assert.*
import org.junit.Test

class MessageDeduplicatorTest {

    @Test
    fun `first message is not duplicate`() {
        val dedup = MessageDeduplicator()
        assertFalse(dedup.isDuplicate("群A", "用户1", "你好"))
    }

    @Test
    fun `same message within window is duplicate`() {
        val dedup = MessageDeduplicator()
        dedup.isDuplicate("群A", "用户1", "你好")
        assertTrue(dedup.isDuplicate("群A", "用户1", "你好"))
    }

    @Test
    fun `different content is not duplicate`() {
        val dedup = MessageDeduplicator()
        dedup.isDuplicate("群A", "用户1", "你好")
        assertFalse(dedup.isDuplicate("群A", "用户1", "在吗"))
    }

    @Test
    fun `different group is not duplicate`() {
        val dedup = MessageDeduplicator()
        dedup.isDuplicate("群A", "用户1", "你好")
        assertFalse(dedup.isDuplicate("群B", "用户1", "你好"))
    }

    @Test
    fun `clear removes all entries`() {
        val dedup = MessageDeduplicator()
        dedup.isDuplicate("群A", "用户1", "你好")
        dedup.clear()
        assertFalse(dedup.isDuplicate("群A", "用户1", "你好"))
    }
}
