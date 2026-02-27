package com.soulon.app.i18n

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OnDeviceTranslationManager {
    @Volatile
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val translators = ConcurrentHashMap<String, Translator>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    fun initialize(context: Context) {
        if (!initialized) {
            initialized = true
        }
    }

    fun preloadModelAsync(targetLang: String) {
        val baseLang = targetLang.substringBefore('-')
        if (baseLang == "en" || baseLang == "zh") return
        scope.launch {
            try {
                val translator = getTranslator(baseLang) ?: return@launch
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } catch (_: Exception) {}
        }
    }

    suspend fun ensureModel(targetLang: String): Boolean = withContext(Dispatchers.IO) {
        val baseLang = targetLang.substringBefore('-')
        if (baseLang == "en" || baseLang == "zh") return@withContext true
        return@withContext try {
            val translator = getTranslator(baseLang) ?: return@withContext false
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun isModelDownloaded(targetLang: String): Boolean = withContext(Dispatchers.IO) {
        val baseLang = targetLang.substringBefore('-')
        if (baseLang == "en" || baseLang == "zh") return@withContext true
        val target = toMlKitLanguage(baseLang) ?: return@withContext true
        return@withContext try {
            val model = TranslateRemoteModel.Builder(target).build()
            val manager = RemoteModelManager.getInstance()
            val downloaded = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            downloaded.contains(model)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun ensureModelWithProgress(targetLang: String, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val baseLang = targetLang.substringBefore('-')
        if (baseLang == "en" || baseLang == "zh") {
            onProgress(100)
            return@withContext true
        }
        if (isModelDownloaded(baseLang)) {
            onProgress(100)
            return@withContext true
        }
        val translator = getTranslator(baseLang) ?: return@withContext false
        return@withContext try {
            coroutineScope {
                var pct = 10
                val ticker = launch {
                    while (isActive) {
                        delay(350)
                        pct = (pct + 1).coerceAtMost(85)
                        onProgress(pct)
                    }
                }
                try {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    onProgress(100)
                    true
                } finally {
                    ticker.cancel()
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun translateAsync(sourceEnText: String, targetLang: String, cacheKey: String, onResult: (String) -> Unit) {
        val baseLang = targetLang.substringBefore('-')
        if (baseLang == "en" || baseLang == "zh") return

        val inflightKey = "$baseLang:$cacheKey"
        if (inFlight.putIfAbsent(inflightKey, true) != null) return

        scope.launch {
            try {
                val translator = getTranslator(baseLang) ?: return@launch
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                val protected = protect(sourceEnText)
                val translated = translator.translate(protected.text).await()
                onResult(unprotect(translated, protected))
            } catch (_: Exception) {
            } finally {
                inFlight.remove(inflightKey)
            }
        }
    }

    private fun getTranslator(baseLang: String): Translator? {
        val target = toMlKitLanguage(baseLang) ?: return null
        return translators.getOrPut(baseLang) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(target)
                .build()
            Translation.getClient(options)
        }
    }

    private fun toMlKitLanguage(baseLang: String): String? {
        return when (baseLang) {
            "en" -> TranslateLanguage.ENGLISH
            "zh" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "es" -> TranslateLanguage.SPANISH
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "pt" -> TranslateLanguage.PORTUGUESE
            "ru" -> TranslateLanguage.RUSSIAN
            "ar" -> TranslateLanguage.ARABIC
            else -> null
        }
    }

    private data class Protected(val text: String, val placeholders: List<String>)

    private fun protect(input: String): Protected {
        val placeholders = mutableListOf<String>()
        var s = input

        val fmtRegex = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")
        s = s.replace(fmtRegex) { m ->
            val token = "__FMT_${placeholders.size}__"
            placeholders.add(m.value)
            token
        }

        s = s.replace("\n", "__NL__")

        return Protected(text = s, placeholders = placeholders)
    }

    private fun unprotect(input: String, protected: Protected): String {
        var s = input
        s = s.replace("__NL__", "\n")
        protected.placeholders.forEachIndexed { idx, original ->
            // 使用不区分大小写的正则进行替换，因为翻译模型可能会改变占位符的大小写
            // 例如 __FMT_0__ 可能变成 __fmt_0__ 或 __Fmt_0__
            val regex = Regex("(?i)__FMT_${idx}__")
            s = s.replace(regex, original)
        }
        return s
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
