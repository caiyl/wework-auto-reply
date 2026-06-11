package com.example.chaserpa.data

import android.content.Context
import android.content.SharedPreferences

class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class MonitorMode { HYBRID, POLLING_ONLY }

    companion object {
        private const val PREFS_NAME = "wework_config"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TARGET_GROUPS = "target_groups"
        private const val KEY_AUTO_REPLY = "auto_reply"
        private const val KEY_MY_NICKNAME = "my_nickname"
        private const val KEY_MONITOR_MODE = "monitor_mode"
        private const val KEY_POLL_INTERVAL = "poll_interval"
        private const val KEY_ADAPTIVE_POLL = "adaptive_poll"
        private const val KEY_REPLY_POLL_INTERVAL = "reply_poll_interval"
        private const val KEY_REPLY_BACKEND_URL = "reply_backend_url"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    }

    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var targetGroups: Set<String>
        get() {
            val raw = prefs.getString(KEY_TARGET_GROUPS, "") ?: ""
            return if (raw.isEmpty()) emptySet() else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        set(value) = prefs.edit().putString(KEY_TARGET_GROUPS, value.joinToString(",")).apply()

    var autoReply: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY, value).apply()

    var myNickname: String
        get() = prefs.getString(KEY_MY_NICKNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MY_NICKNAME, value).apply()

    var monitorMode: MonitorMode
        get() {
            val name = prefs.getString(KEY_MONITOR_MODE, MonitorMode.HYBRID.name)
            return try {
                MonitorMode.valueOf(name ?: MonitorMode.HYBRID.name)
            } catch (_: IllegalArgumentException) {
                MonitorMode.HYBRID
            }
        }
        set(value) = prefs.edit().putString(KEY_MONITOR_MODE, value.name).apply()

    var pollInterval: Int
        get() = prefs.getInt(KEY_POLL_INTERVAL, 3000).coerceIn(1000, 10000)
        set(value) = prefs.edit().putInt(KEY_POLL_INTERVAL, value.coerceIn(1000, 10000)).apply()

    var adaptivePoll: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_POLL, true)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_POLL, value).apply()

    var replyPollInterval: Int
        get() = prefs.getInt(KEY_REPLY_POLL_INTERVAL, 5000).coerceIn(1000, 30000)
        set(value) = prefs.edit().putInt(KEY_REPLY_POLL_INTERVAL, value.coerceIn(1000, 30000)).apply()

    var replyBackendUrl: String
        get() = prefs.getString(KEY_REPLY_BACKEND_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REPLY_BACKEND_URL, value).apply()

    var monitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING_ENABLED, value).apply()

    fun isConfigured(): Boolean {
        return targetGroups.isNotEmpty()
    }
}
