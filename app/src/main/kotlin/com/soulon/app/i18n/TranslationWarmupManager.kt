package com.soulon.app.i18n

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

object TranslationWarmupManager {
    enum class Stage {
        Idle,
        Checking,
        PreparingModel,
        PreparingBundle,
        Ready,
        Failed
    }

    data class State(
        val languageCode: String = "en",
        val stage: Stage = Stage.Idle,
        val progressPercent: Int? = null,
        val message: String? = null
    ) {
        val isActive: Boolean
            get() = stage == Stage.Checking || stage == Stage.PreparingModel || stage == Stage.PreparingBundle
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun start(context: Context, languageCode: String) {
        val baseLang = languageCode.substringBefore('-').ifBlank { "en" }

        scope.launch {
            try {
                TranslationBundleStore.initialize(context)
                OnDeviceTranslationManager.initialize(context)

                val localeManager = LocaleManager(context)
                val pendingBase = localeManager.getPendingLanguageCode()?.substringBefore('-')
                val shouldApply = pendingBase != null && pendingBase == baseLang

                if (baseLang == "en") {
                    mutableState.value = State(languageCode = baseLang, stage = Stage.Ready, progressPercent = 100)
                    if (shouldApply) {
                        localeManager.applyPendingLanguageOr(baseLang)
                    }
                    return@launch
                }

                mutableState.value = State(languageCode = baseLang, stage = Stage.Checking, progressPercent = 0)

                if (baseLang == "zh") {
                    mutableState.value = State(languageCode = baseLang, stage = Stage.Ready, progressPercent = 100)
                    if (shouldApply) {
                        localeManager.applyPendingLanguageOr(baseLang)
                    }
                    return@launch
                }

                val modelDownloaded = OnDeviceTranslationManager.isModelDownloaded(baseLang)
                if (!modelDownloaded) {
                    mutableState.value = State(languageCode = baseLang, stage = Stage.PreparingModel, progressPercent = 5)
                    OnDeviceTranslationManager.ensureModelWithProgress(baseLang) { pct ->
                        val safePct = pct.coerceIn(0, 90)
                        mutableState.value = State(languageCode = baseLang, stage = Stage.PreparingModel, progressPercent = safePct)
                    }
                }

                mutableState.value = State(languageCode = baseLang, stage = Stage.PreparingBundle, progressPercent = 90)
                TranslationBundleStore.preloadWithProgress(baseLang) { done, total ->
                    val pct = if (total <= 0) 90 else (90 + ((done.toDouble() / total.toDouble()) * 10.0).toInt()).coerceIn(90, 99)
                    mutableState.value = State(languageCode = baseLang, stage = Stage.PreparingBundle, progressPercent = pct)
                }

                if (shouldApply) {
                    localeManager.applyPendingLanguageOr(baseLang)
                }
                mutableState.value = State(languageCode = baseLang, stage = Stage.Ready, progressPercent = 100)
            } catch (e: Exception) {
                Timber.e(e, "Translation warmup failed")
                mutableState.value = State(languageCode = baseLang, stage = Stage.Failed, progressPercent = null, message = e.message)
            }
        }
    }

    fun reset() {
        mutableState.value = State()
    }

    fun shutdown() {
        scope.cancel()
    }
}
