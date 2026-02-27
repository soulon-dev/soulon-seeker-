package com.soulon.app.data

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.chat.ChatRepository
import com.soulon.app.proactive.ProactiveQuestionManager
import com.soulon.app.rag.VectorRepository
import com.soulon.app.rewards.RewardsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 数据迁移管理器
 * 
 * 负责将本地数据迁移到后端，实现数据云化
 * 
 * 迁移策略：
 * 1. 首次连接钱包时检查是否需要迁移
 * 2. 按模块分批迁移，记录进度
 * 3. 迁移失败可重试，不影响正常使用
 */
class DataMigrationManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("data_migration", Context.MODE_PRIVATE)
    private val cloudRepo by lazy { CloudDataRepository.getInstance(context) }
    
    companion object {
        private const val TAG = "DataMigrationManager"
        
        // 迁移状态键
        private const val KEY_MIGRATION_VERSION = "migration_version"
        private const val KEY_USER_PROFILE_MIGRATED = "user_profile_migrated"
        private const val KEY_CHAT_SESSIONS_MIGRATED = "chat_sessions_migrated"
        private const val KEY_PERSONA_MIGRATED = "persona_migrated"
        private const val KEY_VECTORS_MIGRATED = "vectors_migrated"
        private const val KEY_QUESTIONS_MIGRATED = "questions_migrated"
        
        // 当前迁移版本
        private const val CURRENT_MIGRATION_VERSION = 1
    }
    
    /**
     * 迁移状态
     */
    data class MigrationStatus(
        val version: Int,
        val userProfileMigrated: Boolean,
        val chatSessionsMigrated: Boolean,
        val personaMigrated: Boolean,
        val vectorsMigrated: Boolean,
        val questionsMigrated: Boolean
    ) {
        val isComplete: Boolean
            get() = userProfileMigrated && chatSessionsMigrated && 
                    personaMigrated && vectorsMigrated && questionsMigrated
        
        val progress: Float
            get() {
                var count = 0
                if (userProfileMigrated) count++
                if (chatSessionsMigrated) count++
                if (personaMigrated) count++
                if (vectorsMigrated) count++
                if (questionsMigrated) count++
                return count / 5f
            }
    }
    
    /**
     * 获取迁移状态
     */
    fun getMigrationStatus(): MigrationStatus {
        return MigrationStatus(
            version = prefs.getInt(KEY_MIGRATION_VERSION, 0),
            userProfileMigrated = prefs.getBoolean(KEY_USER_PROFILE_MIGRATED, false),
            chatSessionsMigrated = prefs.getBoolean(KEY_CHAT_SESSIONS_MIGRATED, false),
            personaMigrated = prefs.getBoolean(KEY_PERSONA_MIGRATED, false),
            vectorsMigrated = prefs.getBoolean(KEY_VECTORS_MIGRATED, false),
            questionsMigrated = prefs.getBoolean(KEY_QUESTIONS_MIGRATED, false)
        )
    }
    
    /**
     * 检查是否需要迁移
     */
    fun needsMigration(): Boolean {
        val status = getMigrationStatus()
        return status.version < CURRENT_MIGRATION_VERSION || !status.isComplete
    }
    
    /**
     * 执行完整迁移
     * 
     * @param walletAddress 用户钱包地址
     * @param onProgress 进度回调 (0.0 - 1.0)
     * @return 是否全部迁移成功
     */
    suspend fun migrateAll(
        walletAddress: String,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Timber.i("$TAG: 开始数据迁移...")
        
        var allSuccess = true
        var progress = 0f
        
        // 1. 迁移用户资料
        if (!prefs.getBoolean(KEY_USER_PROFILE_MIGRATED, false)) {
            val success = migrateUserProfile(walletAddress)
            if (success) {
                prefs.edit().putBoolean(KEY_USER_PROFILE_MIGRATED, true).apply()
            } else {
                allSuccess = false
            }
            progress = 0.2f
            onProgress?.invoke(progress)
        }
        
        // 2. 迁移聊天记录
        if (!prefs.getBoolean(KEY_CHAT_SESSIONS_MIGRATED, false)) {
            val success = migrateChatSessions(walletAddress)
            if (success) {
                prefs.edit().putBoolean(KEY_CHAT_SESSIONS_MIGRATED, true).apply()
            } else {
                allSuccess = false
            }
            progress = 0.4f
            onProgress?.invoke(progress)
        }
        
        // 3. 迁移人格数据
        if (!prefs.getBoolean(KEY_PERSONA_MIGRATED, false)) {
            val success = migratePersona(walletAddress)
            if (success) {
                prefs.edit().putBoolean(KEY_PERSONA_MIGRATED, true).apply()
            } else {
                allSuccess = false
            }
            progress = 0.6f
            onProgress?.invoke(progress)
        }
        
        // 4. 迁移向量数据
        if (!prefs.getBoolean(KEY_VECTORS_MIGRATED, false)) {
            val success = migrateVectors(walletAddress)
            if (success) {
                prefs.edit().putBoolean(KEY_VECTORS_MIGRATED, true).apply()
            } else {
                allSuccess = false
            }
            progress = 0.8f
            onProgress?.invoke(progress)
        }
        
        // 5. 迁移奇遇问题
        if (!prefs.getBoolean(KEY_QUESTIONS_MIGRATED, false)) {
            val success = migrateQuestions(walletAddress)
            if (success) {
                prefs.edit().putBoolean(KEY_QUESTIONS_MIGRATED, true).apply()
            } else {
                allSuccess = false
            }
            progress = 1.0f
            onProgress?.invoke(progress)
        }
        
        // 更新迁移版本
        if (allSuccess) {
            prefs.edit().putInt(KEY_MIGRATION_VERSION, CURRENT_MIGRATION_VERSION).apply()
            Timber.i("$TAG: ✅ 数据迁移完成")
        } else {
            Timber.w("$TAG: ⚠️ 部分数据迁移失败")
        }
        
        return@withContext allSuccess
    }
    
    /**
     * 迁移用户资料
     */
    private suspend fun migrateUserProfile(walletAddress: String): Boolean {
        return try {
            val rewardsRepo = RewardsRepository(context)
            val success = rewardsRepo.syncToBackend(walletAddress)
            Timber.i("$TAG: 用户资料迁移 ${if (success) "成功" else "失败"}")
            success
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 用户资料迁移异常")
            false
        }
    }
    
    /**
     * 迁移聊天记录
     */
    private suspend fun migrateChatSessions(walletAddress: String): Boolean {
        return try {
            val chatRepo = ChatRepository(context)
            chatRepo.setWalletAddress(walletAddress)
            
            // 获取所有本地会话
            val localSessions = chatRepo.getAllSessionsCloud()
            
            var allSuccess = true
            for (session in localSessions) {
                // 同步每个会话的消息到后端
                val success = chatRepo.syncLocalMessagesToCloud(session.id)
                if (!success) {
                    allSuccess = false
                    Timber.w("$TAG: 会话 ${session.id} 消息迁移失败")
                }
            }
            
            Timber.i("$TAG: 聊天记录迁移 ${if (allSuccess) "成功" else "部分失败"}")
            allSuccess
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 聊天记录迁移异常")
            false
        }
    }
    
    /**
     * 迁移人格数据
     */
    private suspend fun migratePersona(walletAddress: String): Boolean {
        return try {
            val rewardsRepo = RewardsRepository(context)
            val profile = rewardsRepo.getUserProfile()
            val personaData = profile.personaData
            
            if (personaData != null && personaData.sampleSize > 0) {
                val cloudPersona = PersonaData(
                    openness = personaData.openness,
                    conscientiousness = personaData.conscientiousness,
                    extraversion = personaData.extraversion,
                    agreeableness = personaData.agreeableness,
                    neuroticism = personaData.neuroticism,
                    analyzedAt = personaData.analyzedAt,
                    sampleSize = personaData.sampleSize,
                    syncRate = profile.personaSyncRate ?: 0f
                )
                
                val success = cloudRepo.updatePersona(cloudPersona)
                Timber.i("$TAG: 人格数据迁移 ${if (success) "成功" else "失败"}")
                success
            } else {
                Timber.d("$TAG: 无人格数据需要迁移")
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 人格数据迁移异常")
            false
        }
    }
    
    /**
     * 迁移向量数据
     */
    private suspend fun migrateVectors(walletAddress: String): Boolean {
        return try {
            val vectorRepo = VectorRepository(context)
            vectorRepo.setWalletAddress(walletAddress)
            
            // 获取本地向量数量
            val stats = vectorRepo.getStats()
            if (stats.totalVectors == 0) {
                Timber.d("$TAG: 无向量数据需要迁移")
                return true
            }
            
            // 获取所有本地向量
            val localVectors = vectorRepo.getAllVectors()
            
            // 批量上传到后端（注意：没有原文，只能上传向量）
            val vectorDataList = localVectors.map { (memoryId, vector) ->
                VectorUploadData(
                    memoryId = memoryId,
                    vector = vector.toList(),
                    originalText = "", // 本地没有存原文
                    category = "migrated"
                )
            }
            
            val success = cloudRepo.uploadVectors(vectorDataList)
            Timber.i("$TAG: 向量数据迁移 ${if (success) "成功" else "失败"} (${vectorDataList.size} 个)")
            success
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 向量数据迁移异常")
            false
        }
    }
    
    /**
     * 迁移奇遇问题
     */
    private suspend fun migrateQuestions(walletAddress: String): Boolean {
        return try {
            val questionManager = ProactiveQuestionManager(context)
            questionManager.setWalletAddress(walletAddress)
            
            val success = questionManager.syncLocalQuestionsToCloud()
            Timber.i("$TAG: 奇遇问题迁移 ${if (success) "成功" else "失败"}")
            success
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 奇遇问题迁移异常")
            false
        }
    }
    
    /**
     * 重置迁移状态（用于测试或强制重新迁移）
     */
    fun resetMigrationStatus() {
        prefs.edit().clear().apply()
        Timber.d("$TAG: 迁移状态已重置")
    }
}
