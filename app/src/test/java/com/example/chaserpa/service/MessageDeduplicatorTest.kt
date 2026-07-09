package com.example.chaserpa.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageDeduplicatorTest {

    private lateinit var deduplicator: MessageDeduplicator

    @Before
    fun setUp() {
        deduplicator = MessageDeduplicator()
    }

    @Test
    fun sameMessageWithinWindow_isDuplicate() {
        val now = System.currentTimeMillis()
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", now))
        assertTrue(deduplicator.isDuplicate("group1", "sender1", "hello", now + 1000))
    }

    @Test
    fun sameMessageOutsideWindow_isNotDuplicate() {
        val windowMs = 11L * 60 * 1000
        val t1 = 1000L * 60 * 60 * 1000 // 1000 hours in ms
        val t2 = t1 + windowMs + 1
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", t1))
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", t2))
    }

    @Test
    fun differentContent_isNotDuplicate() {
        val now = System.currentTimeMillis()
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", now))
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "world", now))
    }

    @Test
    fun differentGroup_isNotDuplicate() {
        val now = System.currentTimeMillis()
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", now))
        assertFalse(deduplicator.isDuplicate("group2", "sender1", "hello", now))
    }

    @Test
    fun expiredMessage_isNotDuplicate() {
        val now = System.currentTimeMillis()
        val windowMs = 11L * 60 * 1000
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", now))
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", now + windowMs + 1))
    }

    @Test
    fun maxSizeEviction_removesOldestEntries() {
        val smallDeduplicator = MessageDeduplicator(maxSize = 2)
        val now = System.currentTimeMillis()
        smallDeduplicator.isDuplicate("group1", "sender1", "msg1", now)
        smallDeduplicator.isDuplicate("group1", "sender1", "msg2", now + 1)
        smallDeduplicator.isDuplicate("group1", "sender1", "msg3", now + 2)
        assertEquals(2, smallDeduplicator.size())
        assertFalse(smallDeduplicator.isDuplicate("group1", "sender1", "msg1", now + 3))
    }

    @Test
    fun clear_emptiesTheCache() {
        val now = System.currentTimeMillis()
        deduplicator.isDuplicate("group1", "sender1", "hello", now)
        assertEquals(1, deduplicator.size())
        deduplicator.clear()
        assertEquals(0, deduplicator.size())
    }

    @Test
    fun size_returnsCorrectCount() {
        val now = System.currentTimeMillis()
        assertEquals(0, deduplicator.size())
        deduplicator.isDuplicate("group1", "sender1", "hello", now)
        assertEquals(1, deduplicator.size())
        deduplicator.isDuplicate("group1", "sender1", "world", now)
        assertEquals(2, deduplicator.size())
    }
}
