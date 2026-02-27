package com.soulon.app.onboarding

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.BuildConfig
import com.soulon.app.auth.BackendAuthManager
import com.soulon.app.i18n.LocaleManager
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * 初始化状态管理
 * 
 * 用途：
 * - 检查用户是否已完成初始化问卷
 * - 保存初始化完成状态
 * - 获取初始化进度
 * - 同步状态到后端（卸载重装后可恢复）
 */
object OnboardingState {
    
    private const val PREFS_NAME = "onboarding_prefs"
    private const val KEY_COMPLETED = "onboarding_completed"
    private const val KEY_COMPLETED_AT = "onboarding_completed_at"
    private const val KEY_CURRENT_QUESTION = "current_question_index"
    private const val KEY_UPLOAD_STARTED = "upload_started"
    private const val KEY_PERSONA_ANALYSIS_COMPLETE = "persona_analysis_complete"
    private val BACKEND_URL = BuildConfig.BACKEND_BASE_URL
    
    /**
     * 检查是否已完成初始化
     */
    fun isCompleted(context: Context, walletAddress: String? = WalletScope.currentWalletAddress(context)): Boolean {
        val prefs = getPrefs(context, walletAddress)
        val completed = prefs.getBoolean(KEY_COMPLETED, false)
        if (!completed && !walletAddress.isNullOrBlank()) {
            val unscoped = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val legacyCompleted = unscoped.getBoolean(KEY_COMPLETED, false)
            if (legacyCompleted) {
                val completedAt = unscoped.getLong(KEY_COMPLETED_AT, -1)
                val currentQuestion = unscoped.getInt(KEY_CURRENT_QUESTION, 0)
                prefs.edit()
                    .putBoolean(KEY_COMPLETED, true)
                    .putLong(KEY_COMPLETED_AT, completedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
                    .putInt(KEY_CURRENT_QUESTION, currentQuestion)
                    .apply()
                Timber.i("✅ 已将初始化完成状态迁移到当前钱包作用域")
                return true
            }
        }
        Timber.d("初始化状态: ${if (completed) "已完成" else "未完成"}")
        return completed
    }
    
    /**
     * 标记初始化已完成（本地）
     */
    fun markCompleted(context: Context, walletAddress: String? = WalletScope.currentWalletAddress(context)) {
        val prefs = getPrefs(context, walletAddress)
        prefs.edit()
            .putBoolean(KEY_COMPLETED, true)
            .putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
            .putInt(KEY_CURRENT_QUESTION, 0) // 重置进度
            .putBoolean(KEY_UPLOAD_STARTED, false)
            .putBoolean(KEY_PERSONA_ANALYSIS_COMPLETE, false)
            .apply()
        Timber.i("初始化已完成（本地）")
    }
    
    /**
     * 标记初始化已完成并同步到后端
     */
    suspend fun markCompletedAndSync(context: Context, walletAddress: String) {
        // 先标记本地
        markCompleted(context, walletAddress)
        
        // 同步到后端
        try {
            syncToBackend(context, walletAddress, true)
            Timber.i("✅ 初始化状态已同步到后端")
        } catch (e: Exception) {
            Timber.w("⚠️ 同步到后端失败，但本地已保存: ${e.message}")
        }
    }
    
    /**
     * 从后端检查并恢复初始化状态
     * @return true 如果从后端恢复了状态
     */
    suspend fun checkAndRestoreFromBackend(context: Context, walletAddress: String): Boolean {
        if (isCompleted(context, walletAddress)) {
            Timber.d("本地已完成初始化，跳过后端检查")
            return false
        }
        
        return try {
            val backendCompleted = checkBackendStatus(context, walletAddress)
            if (backendCompleted) {
                markCompleted(context, walletAddress)
                Timber.i("✅ 从后端恢复初始化状态")
                true
            } else {
                Timber.d("后端也没有完成记录")
                false
            }
        } catch (e: Exception) {
            Timber.w("检查后端状态失败: ${e.message}")
            false
        }
    }

    suspend fun restoreQuestionnaireFromBackend(context: Context, walletAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val api = com.soulon.app.data.BackendApiClient.getInstance(context)
            val persona = api.getPersona(walletAddress) ?: return@withContext false
            if (!persona.questionnaireCompleted) return@withContext false
            val answers = persona.questionnaireAnswers ?: return@withContext false
            if (answers.isEmpty()) return@withContext false

            markCompleted(context, walletAddress)
            val storage = com.soulon.app.onboarding.OnboardingEvaluationStorage(context)
            storage.clearAll()

            val digitRegex = Regex("(\\d+)")
            answers.forEach { (rawKey, rawValue) ->
                val id = digitRegex.find(rawKey)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val eval = OnboardingEvaluation(
                    questionId = id,
                    originalAnswer = rawValue
                )
                storage.saveEvaluation(eval)
            }

            Timber.i("✅ 已从后端恢复问卷答案: ${answers.size} 条")
            true
        } catch (e: Exception) {
            Timber.w("⚠️ 恢复问卷答案失败: ${e.message}")
            false
        }
    }
    
    /**
     * 检查后端的初始化状态
     */
    private suspend fun checkBackendStatus(context: Context, walletAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BACKEND_URL/api/v1/user/$walletAddress/profile")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            val token = BackendAuthManager.getInstance(context).getAccessToken()
            if (!token.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val completed = json.optBoolean("onboardingCompleted", false)
                Timber.d("后端初始化状态: $completed")
                completed
            } else {
                Timber.w("后端请求失败: ${conn.responseCode}")
                false
            }
        } catch (e: Exception) {
            Timber.e("检查后端状态异常: ${e.message}")
            false
        }
    }
    
    /**
     * 同步初始化状态到后端
     */
    private suspend fun syncToBackend(context: Context, walletAddress: String, completed: Boolean) = withContext(Dispatchers.IO) {
        val url = URL("$BACKEND_URL/api/v1/user/$walletAddress/profile")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
        val token = BackendAuthManager.getInstance(context).getAccessToken()
        if (!token.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        
        val body = JSONObject().apply {
            put("onboardingCompleted", completed)
        }
        
        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
        
        if (conn.responseCode != 200) {
            throw Exception("同步失败: ${conn.responseCode}")
        }
    }
    
    /**
     * 重置初始化状态（用于测试或重新初始化）
     */
    fun reset(context: Context) {
        val prefs = getPrefs(context, WalletScope.currentWalletAddress(context))
        prefs.edit()
            .putBoolean(KEY_COMPLETED, false)
            .putInt(KEY_CURRENT_QUESTION, 0)
            .putBoolean(KEY_UPLOAD_STARTED, false)
            .putBoolean(KEY_PERSONA_ANALYSIS_COMPLETE, false)
            .apply()
        Timber.i("初始化状态已重置")
    }

    fun setUploadStarted(context: Context, walletAddress: String?, started: Boolean) {
        val prefs = getPrefs(context, walletAddress)
        prefs.edit().putBoolean(KEY_UPLOAD_STARTED, started).apply()
    }

    fun isUploadStarted(context: Context, walletAddress: String? = WalletScope.currentWalletAddress(context)): Boolean {
        val prefs = getPrefs(context, walletAddress)
        return prefs.getBoolean(KEY_UPLOAD_STARTED, false)
    }

    fun setPersonaAnalysisComplete(context: Context, walletAddress: String?, completed: Boolean) {
        val prefs = getPrefs(context, walletAddress)
        prefs.edit().putBoolean(KEY_PERSONA_ANALYSIS_COMPLETE, completed).apply()
    }

    fun isPersonaAnalysisComplete(context: Context, walletAddress: String? = WalletScope.currentWalletAddress(context)): Boolean {
        val prefs = getPrefs(context, walletAddress)
        return prefs.getBoolean(KEY_PERSONA_ANALYSIS_COMPLETE, false)
    }
    
    /**
     * 保存当前问题索引（用于中途退出后恢复）
     */
    fun saveProgress(context: Context, currentQuestionIndex: Int) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putInt(KEY_CURRENT_QUESTION, currentQuestionIndex)
            .apply()
    }
    
    /**
     * 获取当前问题索引
     */
    fun getCurrentQuestionIndex(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_CURRENT_QUESTION, 0)
    }
    
    /**
     * 获取完成时间
     */
    fun getCompletedAt(context: Context): Long? {
        val prefs = getPrefs(context)
        val timestamp = prefs.getLong(KEY_COMPLETED_AT, -1)
        return if (timestamp != -1L) timestamp else null
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return getPrefs(context, WalletScope.currentWalletAddress(context))
    }

    private fun getPrefs(context: Context, walletAddress: String?): SharedPreferences {
        return WalletScope.scopedPrefs(context, PREFS_NAME, walletAddress)
    }
}
