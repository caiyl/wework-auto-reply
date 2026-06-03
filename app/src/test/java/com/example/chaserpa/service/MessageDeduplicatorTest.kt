package com.example.chaserpa.service

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
    fun sameMessageInDifferentMinute_isNotDuplicate() {
        val minute1 = 1000L * 60 * 1000 // 1000 minutes in ms
        val minute2 = 1001L * 60 * 1000 // 1001 minutes in ms
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", minute1))
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", minute2))
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
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", now))
        assertFalse(deduplicator.isDuplicate("group1", "sender1", "hello", now + 61000))
    }
}
