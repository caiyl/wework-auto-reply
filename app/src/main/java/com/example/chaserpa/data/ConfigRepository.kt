package com.example.chaserpa.data

import android.content.Context
import android.content.SharedPreferences

class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "wework_config"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TARGET_GROUPS = "target_groups"
        private const val KEY_AUTO_REPLY = "auto_reply"
        private const val KEY_MY_NICKNAME = "my_nickname"
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

    fun isConfigured(): Boolean {
        return backendUrl.isNotEmpty() && apiKey.isNotEmpty() && targetGroups.isNotEmpty()
    }
}
