package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.example.chaserpa.data.ConfigRepository

class MessageCollector(
    private val service: AccessibilityService,
    private val config: ConfigRepository,
    private val onMessageCollected: (MessagePusher.WeWorkMessage) -> Unit
) {
    companion object {
        private const val TAG = "MessageCollector"
        private const val TRANSITION_MS = 3000L
    }

    private val foregroundDetector = ForegroundDetector()
    private val notificationCollector = NotificationEventCollector(config.targetGroups, onMessageCollected)
    private val uiPollingCollector = UIPollingCollector(service, config, onMessageCollected)

    private val handler = Handler(Looper.getMainLooper())
    private val transitionRunnable = Runnable {
        if (currentState == State.TRANSITION) {
            currentState = if (foregroundDetector.isForeground()) State.FOREGROUND else State.BACKGROUND
            MessageLog.add("[STATE] Transition resolved to: $currentState")
            applyCollectorsForCurrentState()
        }
    }

    @Volatile
    private var currentState: State = State.BACKGROUND

    @Volatile
    private var transitionEndTime: Long = 0

    enum class State {
        BACKGROUND,
        FOREGROUND,
        TRANSITION
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        foregroundDetector.onAccessibilityEvent(event)
        val wasForeground = currentState == State.FOREGROUND
        val isForeground = foregroundDetector.isForeground()

        // State transition detection
        when {
            currentState == State.TRANSITION -> {
                if (System.currentTimeMillis() >= transitionEndTime) {
                    currentState = if (isForeground) State.FOREGROUND else State.BACKGROUND
                    MessageLog.add("[STATE] Transition resolved to: $currentState")
                    applyCollectorsForCurrentState()
                }
                return // Skip further event handling during transition
            }
            wasForeground && !isForeground -> enterTransition()
            !wasForeground && isForeground -> enterTransition()
        }

        // Mode-specific event handling
        when (config.monitorMode) {
            ConfigRepository.MonitorMode.HYBRID -> handleHybrid(event)
            ConfigRepository.MonitorMode.POLLING_ONLY -> handlePollingOnly(event)
        }
    }

    private fun handleHybrid(event: AccessibilityEvent) {
        notificationCollector.onAccessibilityEvent(event)
        handleHybridState()
    }

    private fun handleHybridState() {
        when (currentState) {
            State.FOREGROUND -> {
                if (!uiPollingCollector.isRunning()) uiPollingCollector.start()
            }
            State.BACKGROUND -> {
                if (uiPollingCollector.isRunning()) uiPollingCollector.stop()
            }
            State.TRANSITION -> {
                if (!uiPollingCollector.isRunning()) uiPollingCollector.start()
            }
        }
    }

    private fun handlePollingOnly(event: AccessibilityEvent) {
        notificationCollector.onAccessibilityEvent(event)
        handlePollingOnlyState()
    }

    private fun handlePollingOnlyState() {
        if (!uiPollingCollector.isRunning()) uiPollingCollector.start()
    }

    private fun applyCollectorsForCurrentState() {
        when (config.monitorMode) {
            ConfigRepository.MonitorMode.HYBRID -> handleHybridState()
            ConfigRepository.MonitorMode.POLLING_ONLY -> handlePollingOnlyState()
        }
    }

    private fun enterTransition() {
        currentState = State.TRANSITION
        transitionEndTime = System.currentTimeMillis() + TRANSITION_MS
        handler.removeCallbacks(transitionRunnable)
        handler.postDelayed(transitionRunnable, TRANSITION_MS)
        MessageLog.add("[STATE] Entering TRANSITION state (${TRANSITION_MS}ms)")
    }

    fun destroy() {
        handler.removeCallbacks(transitionRunnable)
        uiPollingCollector.stop()
    }
}
