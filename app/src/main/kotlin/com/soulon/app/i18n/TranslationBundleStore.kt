package com.soulon.app.i18n

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.data.BackendApiClient
import com.soulon.app.i18n.autogen.autoTranslationTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object TranslationBundleStore {
    private const val PREFS_NAME = "i18n_translation_bundles"
    private const val KEY_PREFIX = "bundle_"

    @Volatile
    private var appContext: Context? = null

    private val memoryCache = ConcurrentHashMap<String, Map<String, String>>()

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    suspend fun preload(languageCode: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext preloadWithProgress(languageCode, null)
    }

    suspend fun preloadWithProgress(
        languageCode: String,
        onProgress: ((done: Int, total: Int) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val baseLang = languageCode.substringBefore('-')
        if (baseLang == "en" || baseLang == "zh") return@withContext true

        val context = appContext ?: return@withContext false
        val prefs = prefs(context)
        val existing = loadFromPrefs(prefs, baseLang).toMutableMap()

        if (existing.size >= autoTranslationTemplates.size) {
            memoryCache[baseLang] = existing
            return@withContext true
        }

        val api = BackendApiClient.getInstance(context)
        val entries = autoTranslationTemplates.entries.toList()
        val batchSize = 50
        var idx = 0
        onProgress?.invoke(existing.size, autoTranslationTemplates.size)

        while (idx < entries.size) {
            val batch = entries.subList(idx, minOf(idx + batchSize, entries.size))
                .filter { (k, _) -> !existing.containsKey(k) }
                .map { (k, v) -> k to v }

            if (batch.isNotEmpty()) {
                val translated = api.translateUiStrings(baseLang, batch)
                existing.putAll(translated.filterValues { it.isNotBlank() })
            }

            idx += batchSize
            onProgress?.invoke(existing.size, autoTranslationTemplates.size)
        }

        saveToPrefs(prefs, baseLang, existing)
        memoryCache[baseLang] = existing
        existing.size >= autoTranslationTemplates.size
    }

    fun get(languageCode: String, key: String): String? {
        val baseLang = languageCode.substringBefore('-')
        if (baseLang == "en" || baseLang == "zh") return null

        val cached = memoryCache[baseLang]
        if (cached != null) return cached[key]

        val context = appContext ?: return null
        val prefs = prefs(context)
        val loaded = loadFromPrefs(prefs, baseLang)
        memoryCache[baseLang] = loaded
        return loaded[key]
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadFromPrefs(prefs: SharedPreferences, baseLang: String): Map<String, String> {
        val raw = prefs.getString(KEY_PREFIX + baseLang, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            json.keys().forEach { k ->
                result[k] = json.optString(k, "")
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveToPrefs(prefs: SharedPreferences, baseLang: String, map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_PREFIX + baseLang, json.toString()).apply()
    }
}
