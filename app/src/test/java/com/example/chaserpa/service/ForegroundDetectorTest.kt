package com.example.chaserpa.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ForegroundDetectorTest {

    @Test
    fun initialStateIsBackground() {
        val detector = ForegroundDetector()
        assertTrue(detector.isBackground())
        assertFalse(detector.isForeground())
    }

    @Test
    fun weworkWindowStateChangedSetsForeground() {
        val detector = ForegroundDetector()
        val event = mock(AccessibilityEvent::class.java)
        `when`(event.eventType).thenReturn(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        `when`(event.packageName).thenReturn("com.tencent.wework")

        detector.onAccessibilityEvent(event)

        assertTrue(detector.isForeground())
        assertFalse(detector.isBackground())
    }

    @Test
    fun otherAppWindowStateChangedSetsBackground() {
        val detector = ForegroundDetector()
        // First set foreground via WeWork
        val foregroundEvent = mock(AccessibilityEvent::class.java)
        `when`(foregroundEvent.eventType).thenReturn(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        `when`(foregroundEvent.packageName).thenReturn("com.tencent.wework")
        detector.onAccessibilityEvent(foregroundEvent)
        assertTrue(detector.isForeground())

        // Then switch to another app
        val backgroundEvent = mock(AccessibilityEvent::class.java)
        `when`(backgroundEvent.eventType).thenReturn(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        `when`(backgroundEvent.packageName).thenReturn("com.example.otherapp")
        detector.onAccessibilityEvent(backgroundEvent)

        assertFalse(detector.isForeground())
        assertTrue(detector.isBackground())
    }

    @Test
    fun windowContentChangedFromWeworkConfirmsForeground() {
        val detector = ForegroundDetector()
        val event = mock(AccessibilityEvent::class.java)
        `when`(event.eventType).thenReturn(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        `when`(event.packageName).thenReturn("com.tencent.wework")

        detector.onAccessibilityEvent(event)

        assertTrue(detector.isForeground())
        assertFalse(detector.isBackground())
    }
}
