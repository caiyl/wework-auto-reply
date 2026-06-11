package com.example.chaserpa.service

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MessageLog {
    private val _logs = mutableStateListOf<String>()
    val logs: List<String> = _logs

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(log: String) {
        val timestamp = timeFormat.format(Date())
        synchronized(this) {
            _logs.add(0, "[$timestamp] $log")
            while (_logs.size > 50) {
                _logs.removeAt(_logs.lastIndex)
            }
        }
    }

    fun clear() {
        _logs.clear()
    }

    fun getAutoLogs(): List<String> {
        return _logs.filter { it.contains("[AUTO]") || it.contains("[CAPTURE]") || it.contains("[PUSH]") }
    }
}
