package com.soulon.app.persona

import android.content.Context

object PersonaTelemetry {
    private const val PREFS = "persona_telemetry"

    const val KEY_PERSONA_UPDATE_SUCCESS = "persona_update_success"
    const val KEY_PERSONA_UPDATE_FAILURE = "persona_update_failure"
    const val KEY_PERSONA_IRYS_UPLOAD_SUCCESS = "persona_irys_upload_success"
    const val KEY_PERSONA_IRYS_UPLOAD_FAILURE = "persona_irys_upload_failure"
    const val KEY_PERSONA_IRYS_RESTORE_SUCCESS = "persona_irys_restore_success"
    const val KEY_PERSONA_IRYS_RESTORE_FAILURE = "persona_irys_restore_failure"

    fun increment(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getLong(key, 0L)
        prefs.edit().putLong(key, current + 1L).apply()
    }

    fun snapshot(context: Context): Map<String, Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapValues { (_, v) ->
            when (v) {
                is Int -> v.toLong()
                is Long -> v
                is String -> v.toLongOrNull() ?: 0L
                else -> 0L
            }
        }
    }
}

