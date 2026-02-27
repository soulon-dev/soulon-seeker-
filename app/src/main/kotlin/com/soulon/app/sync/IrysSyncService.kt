package com.soulon.app.sync

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.soulon.app.StorageManager
import com.soulon.app.chat.ChatDao
import com.soulon.app.chat.ChatMessageEntity
import com.soulon.app.chat.ChatSessionEntity
import com.soulon.app.config.RemoteConfigManager
import com.soulon.app.persona.PersonaTelemetry
import com.soulon.app.rewards.PersonaData
import com.soulon.app.rewards.RewardsDao
import com.soulon.app.rewards.RewardsDatabase
import com.soulon.app.rewards.UserProfile
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Irys 数据同步服务
 * 
 * 负责将所有用户数据同步到 Irys 区块链：
 * - 聊天会话和消息
 * - 用户档案（积分、等级）
 * - 人格数据
 * - 记忆数据
 * 
 * 以及从 Irys 恢复数据到本地
 */
class IrysSyncService(
    private val context: Context,
    private val storageManager: StorageManager
) {
    
    private val gson = Gson()
    private val prefs: SharedPreferences
        get() = WalletScope.scopedPrefs(context, "irys_sync")
    
    // 数据库访问
    private val database by lazy { RewardsDatabase.getInstance(context) }
    private val chatDao: ChatDao by lazy { database.chatDao() }
    private val rewardsDao: RewardsDao by lazy { database.rewardsDao() }
    
    // 同步状态
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState
    
    companion object {
        // 数据类型标签
        const val TYPE_CHAT_SESSION = "ChatSession"
        const val TYPE_CHAT_MESSAGE = "ChatMessage"
        const val TYPE_USER_PROFILE = "UserProfile"
        const val TYPE_PERSONA_DATA = "PersonaData"
        const val TYPE_MEMORY = "Memory"
        const val TYPE_ONBOARDING = "onboarding"
        const val TYPE_PERSONA_CONVERSATION = "PersonaConversation" // 人格相关对话
        
        // 同步间隔
        const val SYNC_INTERVAL_MS = 60 * 60 * 1000L // 1 小时
        
        // SharedPreferences 键
        const val KEY_LAST_SYNC_TIME = "last_sync_time"
        const val KEY_LAST_CHAT_SYNC = "last_chat_sync"
        const val KEY_LAST_PROFILE_SYNC = "last_profile_sync"
        const val KEY_LAST_PERSONA_SYNC = "last_persona_sync"
        const val KEY_WALLET_ADDRESS = "synced_wallet_address"
    }
    
    /**
     * 同步状态数据类
     */
    data class SyncState(
        val isSyncing: Boolean = false,
        val currentOperation: String = "",
        val progress: Float = 0f,
        val lastSyncTime: Long = 0,
        val error: String? = null
    )
    
    // ========== 上传功能 ==========
    
    /**
     * 上传聊天会话到 Irys
     */
    suspend fun uploadChatSession(
        session: ChatSessionEntity,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.d("📤 上传聊天会话: ${session.id}")
            
            val sessionJson = gson.toJson(session)
            val result = storageManager.storeMemory(
                content = sessionJson,
                metadata = mapOf(
                    "type" to TYPE_CHAT_SESSION,
                    "session_id" to session.id,
                    "title" to session.title,
                    "created_at" to session.createdAt.toString(),
                    "updated_at" to session.updatedAt.toString()
                ),
                activityResultSender = activityResultSender
            )
            
            if (result.success) {
                Timber.i("✅ 聊天会话已上传: ${session.id}")
            } else {
                Timber.w("聊天会话上传失败: ${result.message}")
            }
            
            result.success
        } catch (e: Exception) {
            Timber.e(e, "上传聊天会话失败")
            false
        }
    }
    
    /**
     * 批量上传聊天消息到 Irys
     */
    suspend fun uploadChatMessages(
        messages: List<ChatMessageEntity>,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        
        messages.forEach { message ->
            try {
                val messageJson = gson.toJson(message)
                val result = storageManager.storeMemory(
                    content = messageJson,
                    metadata = mapOf(
                        "type" to TYPE_CHAT_MESSAGE,
                        "message_id" to message.id,
                        "session_id" to message.sessionId,
                        "is_user" to message.isUser.toString(),
                        "timestamp" to message.timestamp.toString()
                    ),
                    activityResultSender = activityResultSender
                )
                
                if (result.success) {
                    successCount++
                    Timber.d("✅ 消息已上传: ${message.id}")
                }
            } catch (e: Exception) {
                Timber.e(e, "上传消息失败: ${message.id}")
            }
        }
        
        Timber.i("📤 批量上传完成: $successCount/${messages.size} 条消息")
        successCount
    }
    
    /**
     * 上传人格相关对话到 Irys
     * 
     * 当对话被判定为涉及人格特征时，将对话加密上传到 Irys
     * 以便在重新安装应用后可以恢复
     * 
     * @param sessionId 会话 ID
     * @param userMessage 用户消息
     * @param aiResponse AI 回复
     * @param relevanceScore 人格相关度分数
     * @param detectedTraits 检测到的特质
     * @param activityResultSender Activity 结果发送器
     * @return 上传结果，包含 Irys 交易 ID
     */
    suspend fun uploadPersonaConversation(
        sessionId: String,
        userMessage: String,
        aiResponse: String,
        relevanceScore: Float,
        detectedTraits: List<String>,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): PersonaConversationUploadResult = withContext(Dispatchers.IO) {
        try {
            Timber.d("📤 上传人格相关对话: session=$sessionId")
            
            // 构建对话数据
            val conversationData = PersonaConversationData(
                sessionId = sessionId,
                userMessage = userMessage,
                aiResponse = aiResponse,
                relevanceScore = relevanceScore,
                detectedTraits = detectedTraits,
                timestamp = System.currentTimeMillis()
            )
            
            val conversationJson = gson.toJson(conversationData)
            
            val result = storageManager.storeMemory(
                content = conversationJson,
                metadata = mapOf(
                    "type" to TYPE_PERSONA_CONVERSATION,
                    "session_id" to sessionId,
                    "relevance_score" to relevanceScore.toString(),
                    "traits" to detectedTraits.joinToString(","),
                    "timestamp" to System.currentTimeMillis().toString()
                ),
                activityResultSender = activityResultSender
            )
            
            if (result.success && result.memoryId != null) {
                Timber.i("✅ 人格对话已上传: ${result.memoryId}")
                PersonaConversationUploadResult.Success(
                    transactionId = result.memoryId,
                    irysUri = result.irysUri ?: ""
                )
            } else {
                Timber.w("人格对话上传失败: ${result.message}")
                PersonaConversationUploadResult.Error(result.message ?: "上传失败")
            }
        } catch (e: Exception) {
            Timber.e(e, "上传人格对话失败")
            PersonaConversationUploadResult.Error(e.message ?: "未知错误")
        }
    }
    
    /**
     * 人格对话数据
     */
    data class PersonaConversationData(
        val sessionId: String,
        val userMessage: String,
        val aiResponse: String,
        val relevanceScore: Float,
        val detectedTraits: List<String>,
        val timestamp: Long
    )
    
    /**
     * 人格对话上传结果
     */
    sealed class PersonaConversationUploadResult {
        data class Success(
            val transactionId: String,
            val irysUri: String
        ) : PersonaConversationUploadResult()
        
        data class Error(val message: String) : PersonaConversationUploadResult()
    }
    
    /**
     * 上传用户档案到 Irys
     */
    suspend fun uploadUserProfile(
        profile: UserProfile,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.d("📤 上传用户档案")
            
            // 排除 personaData（单独上传）
            val profileForUpload = ProfileSnapshot(
                userId = profile.userId,
                memoBalance = profile.memoBalance,
                totalMemoEarned = profile.totalMemoEarned,
                currentTier = profile.currentTier,
                lastTierUpdate = profile.lastTierUpdate,
                totalTokensGenerated = profile.totalTokensGenerated,
                totalInferences = profile.totalInferences,
                sovereignRatio = profile.sovereignRatio,
                lastPersonaAnalysis = profile.lastPersonaAnalysis,
                personaSyncRate = profile.personaSyncRate,
                createdAt = profile.createdAt,
                lastActiveAt = profile.lastActiveAt,
                snapshotTime = System.currentTimeMillis()
            )
            
            val profileJson = gson.toJson(profileForUpload)
            val result = storageManager.storeMemory(
                content = profileJson,
                metadata = mapOf(
                    "type" to TYPE_USER_PROFILE,
                    "user_id" to profile.userId,
                    "memo_balance" to profile.memoBalance.toString(),
                    "current_tier" to profile.currentTier.toString(),
                    "snapshot_time" to System.currentTimeMillis().toString()
                ),
                activityResultSender = activityResultSender
            )
            
            if (result.success) {
                Timber.i("✅ 用户档案已上传")
                prefs.edit().putLong(KEY_LAST_PROFILE_SYNC, System.currentTimeMillis()).apply()
            } else {
                val msg = result.message.orEmpty()
                if (msg.contains("Irys 余额不足") || msg.contains("Insufficient Irys balance")) {
                    prefs.edit().putLong(KEY_LAST_PROFILE_SYNC, System.currentTimeMillis()).apply()
                    Timber.w("用户档案上传已跳过（Irys 余额不足）")
                    return@withContext true
                }
                Timber.w("用户档案上传失败: ${result.message}")
            }
            
            result.success
        } catch (e: Exception) {
            Timber.e(e, "上传用户档案失败")
            false
        }
    }

    suspend fun uploadPersonaSnapshot(
        profile: UserProfile,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val remoteConfig = RemoteConfigManager.getInstance(context)
            if (!remoteConfig.getBoolean("persona.irys.snapshot.enabled", true)) {
                return@withContext true
            }

            val personaUpdatedAt = profile.lastPersonaAnalysis ?: return@withContext false
            val lastPersonaSync = prefs.getLong(KEY_LAST_PERSONA_SYNC, 0)
            if (personaUpdatedAt <= lastPersonaSync) {
                return@withContext true
            }

            val payload = profile.personaProfileV2?.let { gson.toJson(it) } ?: profile.personaData?.let { gson.toJson(it) }
            if (payload.isNullOrBlank()) {
                return@withContext false
            }

            val result = storageManager.storeMemory(
                content = payload,
                metadata = mapOf(
                    "type" to TYPE_PERSONA_DATA,
                    "version" to (profile.personaProfileV2?.version?.toString() ?: "1"),
                    "persona_updated_at" to personaUpdatedAt.toString()
                ),
                activityResultSender = activityResultSender
            )

            if (result.success) {
                prefs.edit().putLong(KEY_LAST_PERSONA_SYNC, personaUpdatedAt).apply()
                Timber.i("✅ 人格画像已上传: updatedAt=$personaUpdatedAt")
                PersonaTelemetry.increment(context, PersonaTelemetry.KEY_PERSONA_IRYS_UPLOAD_SUCCESS)
                return@withContext true
            }

            val msg = result.message.orEmpty()
            if (msg.contains("Irys 余额不足") || msg.contains("Insufficient Irys balance")) {
                prefs.edit().putLong(KEY_LAST_PERSONA_SYNC, personaUpdatedAt).apply()
                Timber.w("人格画像上传已跳过（Irys 余额不足）")
                return@withContext true
            }

            Timber.w("人格画像上传失败: ${result.message}")
            PersonaTelemetry.increment(context, PersonaTelemetry.KEY_PERSONA_IRYS_UPLOAD_FAILURE)
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "上传人格画像失败")
            PersonaTelemetry.increment(context, PersonaTelemetry.KEY_PERSONA_IRYS_UPLOAD_FAILURE)
            return@withContext false
        }
    }
    
    /**
     * 同步所有新的聊天数据到 Irys
     */
    suspend fun syncChatDataToIrys(
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            updateState { copy(isSyncing = true, currentOperation = "同步聊天数据...") }
            
            val lastChatSync = prefs.getLong(KEY_LAST_CHAT_SYNC, 0)
            var uploadedSessions = 0
            var uploadedMessages = 0
            
            // 获取所有会话
            val sessions = chatDao.getAllSessionsOnce()
            
            sessions.forEach { session ->
                // 只上传更新时间晚于上次同步的会话
                if (session.updatedAt > lastChatSync) {
                    // 上传会话
                    if (uploadChatSession(session, activityResultSender)) {
                        uploadedSessions++
                    }
                    
                    // 获取该会话的所有消息
                    val messages = chatDao.getMessagesForSessionOnce(session.id)
                    val newMessages = messages.filter { it.timestamp > lastChatSync }
                    
                    if (newMessages.isNotEmpty()) {
                        uploadedMessages += uploadChatMessages(newMessages, activityResultSender)
                    }
                }
                
                // 更新进度
                updateState { copy(progress = (sessions.indexOf(session) + 1f) / sessions.size) }
            }
            
            // 更新同步时间
            prefs.edit().putLong(KEY_LAST_CHAT_SYNC, System.currentTimeMillis()).apply()
            
            updateState { copy(isSyncing = false, currentOperation = "", progress = 1f) }
            
            Timber.i("✅ 聊天数据同步完成: $uploadedSessions 会话, $uploadedMessages 消息")
            
            SyncResult.Success(
                sessionsUploaded = uploadedSessions,
                messagesUploaded = uploadedMessages
            )
        } catch (e: Exception) {
            Timber.e(e, "聊天数据同步失败")
            updateState { copy(isSyncing = false, error = e.message) }
            SyncResult.Error(e.message ?: "未知错误")
        }
    }
    
    // ========== 下载/恢复功能 ==========

    /**
     * 从 Irys 恢复 PersonaData（下载 + 钱包派生密钥解密）
     *
     * 只要恢复成功并写入 user_profile.personaData，雷达图会自动解锁。
     */
    suspend fun restorePersonaDataFromIrys(
        walletAddress: String,
        activity: android.app.Activity
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val remoteConfig = RemoteConfigManager.getInstance(context)
            if (!remoteConfig.getBoolean("persona.irys.restore.enabled", true)) {
                updateState { copy(isSyncing = false, currentOperation = "", progress = 1f) }
                return@withContext RestoreResult.Success(
                    sessionsRestored = 0,
                    messagesRestored = 0,
                    profileRestored = false,
                    personaRestored = false,
                    encryptedItemsFound = 0,
                    note = "远程配置已关闭人格恢复"
                )
            }

            updateState { copy(isSyncing = true, currentOperation = "恢复人格数据...") }

            val existingProfile = rewardsDao.getUserProfile()
            if (existingProfile?.personaData != null) {
                updateState { copy(isSyncing = false, currentOperation = "", progress = 1f) }
                return@withContext RestoreResult.Success(
                    sessionsRestored = 0,
                    messagesRestored = 0,
                    profileRestored = false,
                    personaRestored = true,
                    encryptedItemsFound = 0,
                    note = "本地已存在人格数据"
                )
            }

            val typedItems = storageManager.queryPersonaDataItems(walletAddress)
            val allItems = storageManager.queryAllDataItemsByOwner(walletAddress)
            val candidates = when {
                typedItems.isNotEmpty() -> typedItems
                allItems.any { it.tags["Type"] == TYPE_PERSONA_DATA } -> allItems.filter { it.tags["Type"] == TYPE_PERSONA_DATA }
                else -> allItems
            }

            if (candidates.isEmpty()) {
                updateState { copy(isSyncing = false, currentOperation = "", progress = 1f) }
                return@withContext RestoreResult.Success(
                    sessionsRestored = 0,
                    messagesRestored = 0,
                    profileRestored = false,
                    personaRestored = false,
                    encryptedItemsFound = 0,
                    note = "链上未找到任何 Irys DataItem"
                )
            }

            val sorted = candidates.sortedByDescending { it.timestamp }
            val maxAttempts = 30
            var restored = false
            var restoredTimestamp: Long? = null

            for ((index, item) in sorted.take(maxAttempts).withIndex()) {
                updateState { copy(progress = (index + 1f) / sorted.take(maxAttempts).size) }

                val encryptedBytes = storageManager.downloadEncrypted(item.uri) ?: continue
                val plaintext = storageManager.decryptWithHardwareAuth(encryptedBytes, activity) ?: continue

                val profileV2 = runCatching { gson.fromJson(plaintext, com.soulon.app.rewards.PersonaProfileV2::class.java) }.getOrNull()
                val persona = profileV2?.toLegacyPersonaData()
                    ?: runCatching { gson.fromJson(plaintext, PersonaData::class.java) }.getOrNull()
                    ?: continue

                if (!isValidPersona(persona)) continue

                val profile = rewardsDao.getUserProfile() ?: UserProfile()
                val updated = profile.copy(
                    personaData = persona,
                    personaProfileV2 = profileV2,
                    lastPersonaAnalysis = item.timestamp,
                    lastActiveAt = System.currentTimeMillis()
                )
                rewardsDao.insertOrUpdateUserProfile(updated)

                restored = true
                restoredTimestamp = item.timestamp
                Timber.i("✅ PersonaData 已从 Irys 恢复并写入本地: ts=$restoredTimestamp")
                PersonaTelemetry.increment(context, PersonaTelemetry.KEY_PERSONA_IRYS_RESTORE_SUCCESS)
                break
            }

            updateState { copy(isSyncing = false, currentOperation = "", progress = 1f) }

            return@withContext RestoreResult.Success(
                sessionsRestored = 0,
                messagesRestored = 0,
                profileRestored = false,
                personaRestored = restored,
                encryptedItemsFound = candidates.size,
                note = if (restored) {
                    "人格数据恢复成功"
                } else if (typedItems.isEmpty() && allItems.isNotEmpty()) {
                    "未找到 Type=PersonaData，已尝试从最近数据中识别但失败（可能旧版本未上传 PersonaData 或格式不同）"
                } else {
                    "尝试解密/解析失败（可能是旧格式或无权限）"
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "人格数据恢复失败")
            PersonaTelemetry.increment(context, PersonaTelemetry.KEY_PERSONA_IRYS_RESTORE_FAILURE)
            updateState { copy(isSyncing = false, error = e.message) }
            return@withContext RestoreResult.Error(e.message ?: "未知错误")
        }
    }
    
    /**
     * 从 Irys 同步数据索引（不解密内容）
     * 
     * 注意：由于使用 TEE 硬件密钥，卸载应用后密钥丢失，
     * 加密内容无法恢复。此方法仅同步数据索引。
     * 
     * 真正的记忆解密需要在使用时通过硬件授权进行。
     */
    suspend fun syncDataIndexFromIrys(
        walletAddress: String
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            updateState { copy(isSyncing = true, currentOperation = "同步区块链数据索引...") }
            
            // 查询该钱包的所有数据
            val allItems = storageManager.queryMemoriesByWallet(walletAddress)
            
            Timber.i("📥 找到 ${allItems.size} 条加密数据")
            Timber.i("⚠️ 注意：加密内容需要硬件授权才能解密")
            
            // 记录找到的数据类型统计
            val typeCounts = mutableMapOf<String, Int>()
            allItems.forEach { item ->
                val type = item.metadata["type"] ?: "unknown"
                typeCounts[type] = (typeCounts[type] ?: 0) + 1
            }
            
            Timber.i("📊 数据类型统计:")
            typeCounts.forEach { (type, count) ->
                Timber.i("   - $type: $count 条")
            }
            
            // 更新同步时间
            prefs.edit()
                .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
                .putString(KEY_WALLET_ADDRESS, walletAddress)
                .apply()
            
            updateState { 
                copy(
                    isSyncing = false, 
                    currentOperation = "", 
                    progress = 1f,
                    lastSyncTime = System.currentTimeMillis()
                ) 
            }
            
            // 返回索引同步结果
            RestoreResult.Success(
                sessionsRestored = 0, // 索引同步不恢复会话
                messagesRestored = 0, // 索引同步不恢复消息
                profileRestored = false,
                personaRestored = false,
                encryptedItemsFound = allItems.size,
                note = "加密数据已同步索引，解密需要硬件授权"
            )
        } catch (e: Exception) {
            Timber.e(e, "数据索引同步失败")
            updateState { copy(isSyncing = false, error = e.message) }
            RestoreResult.Error(e.message ?: "未知错误")
        }
    }

    private fun isValidPersona(persona: PersonaData): Boolean {
        fun ok(v: Float) = v.isFinite() && v >= 0f && v <= 1f
        return ok(persona.openness) &&
            ok(persona.conscientiousness) &&
            ok(persona.extraversion) &&
            ok(persona.agreeableness) &&
            ok(persona.neuroticism) &&
            persona.sampleSize > 0
    }
    
    /**
     * 检查是否需要同步
     */
    fun needsSync(): Boolean {
        val lastSync = prefs.getLong(KEY_LAST_SYNC_TIME, 0)
        return System.currentTimeMillis() - lastSync > SYNC_INTERVAL_MS
    }
    
    /**
     * 获取上次同步时间
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0)
    }
    
    /**
     * 更新状态
     */
    private fun updateState(update: SyncState.() -> SyncState) {
        _syncState.value = _syncState.value.update()
    }
    
    /**
     * 同步结果
     */
    sealed class SyncResult {
        data class Success(
            val sessionsUploaded: Int,
            val messagesUploaded: Int
        ) : SyncResult()
        
        data class Error(val message: String) : SyncResult()
    }
    
    /**
     * 恢复结果
     */
    sealed class RestoreResult {
        data class Success(
            val sessionsRestored: Int,
            val messagesRestored: Int,
            val profileRestored: Boolean,
            val personaRestored: Boolean,
            val encryptedItemsFound: Int = 0,
            val note: String? = null
        ) : RestoreResult()
        
        data class Error(val message: String) : RestoreResult()
    }
    
    /**
     * 用户档案快照（用于上传）
     */
    data class ProfileSnapshot(
        val userId: String,
        val memoBalance: Int,
        val totalMemoEarned: Int,
        val currentTier: Int,
        val lastTierUpdate: Long,
        val totalTokensGenerated: Int,
        val totalInferences: Int,
        val sovereignRatio: Float,
        val lastPersonaAnalysis: Long?,
        val personaSyncRate: Float?,
        val createdAt: Long,
        val lastActiveAt: Long,
        val snapshotTime: Long
    )
}
