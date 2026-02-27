package com.soulon.app.game

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class VoyageEventLogStore(
    context: Context,
    walletAddress: String
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("voyage_event_log", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "log_${walletAddress.lowercase()}"

    fun load(): List<String> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun append(line: String, maxEntries: Int = 200) {
        val current = load()
        val next = (current + line).takeLast(maxEntries)
        prefs.edit().putString(key, gson.toJson(next)).apply()
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }
}

