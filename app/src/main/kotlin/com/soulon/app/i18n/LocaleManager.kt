package com.soulon.app.i18n

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import timber.log.Timber
import java.util.Locale

/**
 * 语言管理器
 * 
 * 管理应用的语言设置，支持：
 * - 首次启动语言选择
 * - 运行时切换语言
 * - 持久化语言偏好
 */
class LocaleManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "locale_prefs"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"
        private const val KEY_LANGUAGE_SELECTED = "language_selected"
        private const val KEY_PENDING_LANGUAGE = "pending_language"
        
        /**
         * 支持的语言列表
         * 
         * 为了加快上线速度，目前仅支持中文和英文
         */
        val SUPPORTED_LANGUAGES = listOf(
            Language("zh", "简体中文", "Chinese (Simplified)", "🇨🇳"),
            Language("en", "English", "English", "🇺🇸")
        )
        
        /**
         * 获取默认语言代码（基于系统语言）
         */
        fun getDefaultLanguageCode(context: Context): String {
            return "en"
        }
        
        /**
         * 为 Context 应用语言设置
         */
        fun applyLocaleToContext(context: Context, languageCode: String): Context {
            val locale = Locale.forLanguageTag(languageCode).takeIf { it.language.isNotBlank() }
                ?: Locale(languageCode)
            Locale.setDefault(locale)
            
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            
            return context.createConfigurationContext(config)
        }

        fun getSavedLanguageCode(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_LANGUAGE, null)
        }

        fun getAcceptLanguage(context: Context): String {
            val code = getSavedLanguageCode(context) ?: getDefaultLanguageCode(context)
            return Locale.forLanguageTag(code).takeIf { it.language.isNotBlank() }?.toLanguageTag()
                ?: code
        }
    }
    
    /**
     * 检查用户是否已选择语言
     */
    fun isLanguageSelected(): Boolean {
        return prefs.getBoolean(KEY_LANGUAGE_SELECTED, false)
    }
    
    /**
     * 获取当前选择的语言代码
     */
    fun getSelectedLanguageCode(): String {
        return prefs.getString(KEY_SELECTED_LANGUAGE, null)
            ?: getDefaultLanguageCode(context)
    }

    fun getPendingLanguageCode(): String? {
        return prefs.getString(KEY_PENDING_LANGUAGE, null)
    }
    
    /**
     * 获取当前选择的语言对象
     */
    fun getSelectedLanguage(): Language {
        val code = getSelectedLanguageCode()
        return SUPPORTED_LANGUAGES.find { it.code == code }
            ?: SUPPORTED_LANGUAGES.first { it.code == "en" }
    }
    
    /**
     * 设置语言
     * 
     * @param languageCode 语言代码
     * @param isFirstSelection 是否是首次选择
     */
    fun setLanguage(languageCode: String, isFirstSelection: Boolean = false) {
        Timber.i("🌍 设置语言: $languageCode")

        setPendingLanguage(languageCode, isFirstSelection = isFirstSelection)
        applyPendingLanguageOr(languageCode)
    }

    fun setPendingLanguage(languageCode: String, isFirstSelection: Boolean = false) {
        prefs.edit()
            .putString(KEY_PENDING_LANGUAGE, languageCode)
            .putBoolean(KEY_LANGUAGE_SELECTED, true)
            .apply()
    }

    fun applyPendingLanguageOr(languageCode: String) {
        val pending = getPendingLanguageCode() ?: languageCode
        prefs.edit()
            .putString(KEY_SELECTED_LANGUAGE, pending)
            .remove(KEY_PENDING_LANGUAGE)
            .putBoolean(KEY_LANGUAGE_SELECTED, true)
            .apply()

        AppStrings.setLanguage(pending)
        applyLocale(pending)
    }

    fun applyLanguageImmediately(languageCode: String) {
        prefs.edit()
            .putString(KEY_SELECTED_LANGUAGE, languageCode)
            .remove(KEY_PENDING_LANGUAGE)
            .putBoolean(KEY_LANGUAGE_SELECTED, true)
            .apply()

        AppStrings.setLanguage(languageCode)
        applyLocale(languageCode)
    }
    
    /**
     * 应用语言设置
     */
    fun applyLocale(languageCode: String) {
        val locale = Locale.forLanguageTag(languageCode).takeIf { it.language.isNotBlank() }
            ?: Locale(languageCode)
        Locale.setDefault(locale)

        Timber.i("✅ 语言已应用: $languageCode")
    }
    
    /**
     * 初始化语言设置（在 Application 或 Activity 中调用）
     */
    fun initializeLocale() {
        val languageCode = getSelectedLanguageCode()
        
        // 同步更新 AppStrings
        AppStrings.setLanguage(languageCode)
        
        applyLocale(languageCode)
    }
    
    /**
     * 重置语言选择（用于测试）
     */
    fun resetLanguageSelection() {
        prefs.edit()
            .remove(KEY_SELECTED_LANGUAGE)
            .remove(KEY_PENDING_LANGUAGE)
            .remove(KEY_LANGUAGE_SELECTED)
            .apply()
    }
}

/**
 * 语言数据类
 */
data class Language(
    val code: String,        // ISO 639-1 语言代码
    val nativeName: String,  // 语言的本地名称
    val englishName: String, // 语言的英文名称
    val flag: String         // 国旗 emoji
) {
    /**
     * 获取显示名称（本地名称 + 英文名称）
     */
    fun getDisplayName(): String {
        return if (nativeName == englishName) {
            nativeName
        } else {
            "$nativeName ($englishName)"
        }
    }
}
