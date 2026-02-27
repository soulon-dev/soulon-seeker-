package com.soulon.app.data

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 云端数据仓库
 * 
 * 统一数据访问层，实现：
 * - 后端优先的数据存储策略
 * - 本地缓存支持离线使用
 * - 自动同步机制
 * 
 * 数据流向：
 * 读取：后端 -> 本地缓存 -> UI
 * 写入：UI -> 后端 + 本地缓存
 */
class CloudDataRepository private constructor(private val context: Context) {
    
    private val api = BackendApiClient(context)
    private val prefs: SharedPreferences
        get() = WalletScope.scopedPrefs(context, "cloud_data_cache")
    private val syncMutex = Mutex()
    
    // 当前钱包地址
    private var currentWallet: String? = null
    
    // 用户数据状态
    private val _userProfile = MutableStateFlow<FullUserProfile?>(null)
    val userProfile = _userProfile.asStateFlow()
    
    // 聊天会话状态
    private val _chatSessions = MutableStateFlow<List<ChatSessionData>>(emptyList())
    val chatSessions = _chatSessions.asStateFlow()
    
    // 人格数据状态
    private val _persona = MutableStateFlow<PersonaData?>(null)
    val persona = _persona.asStateFlow()
    
    // 待回答问题状态
    private val _pendingQuestions = MutableStateFlow<List<ProactiveQuestionData>>(emptyList())
    val pendingQuestions = _pendingQuestions.asStateFlow()
    
    // AI 服务配置（从后台获取）
    private val _aiConfig = MutableStateFlow<AiServiceConfig?>(null)
    val aiConfig = _aiConfig.asStateFlow()
    
    // 同步状态
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()
    
    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime = _lastSyncTime.asStateFlow()
    
    companion object {
        private const val TAG = "CloudDataRepository"
        private const val PREF_LAST_SYNC = "last_sync_time"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5分钟
        
        @Volatile
        private var INSTANCE: CloudDataRepository? = null
        
        fun getInstance(context: Context): CloudDataRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CloudDataRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    // ============================================
    // 初始化和同步
    // ============================================
    
    /**
     * 设置当前钱包并初始化数据
     */
    suspend fun initialize(walletAddress: String) {
        if (currentWallet == walletAddress && _userProfile.value != null) {
            Timber.d("已初始化相同钱包，跳过")
            return
        }
        
        currentWallet = walletAddress
        _lastSyncTime.value = prefs.getLong(PREF_LAST_SYNC, 0)
        
        // 首先尝试从后端加载完整数据
        syncFullProfile()
    }
    
    /**
     * 从后端同步完整用户数据
     */
    suspend fun syncFullProfile(): Boolean = syncMutex.withLock {
        val wallet = currentWallet ?: return false
        
        _isSyncing.value = true
        Timber.i("🔄 开始同步完整用户数据: $wallet")
        
        return try {
            val profile = api.getFullUserProfile(wallet)
            
            if (profile != null) {
                _userProfile.value = profile
                _chatSessions.value = profile.chatSessions
                _persona.value = profile.persona

                withContext(Dispatchers.IO) {
                    runCatching {
                        val db = com.soulon.app.rewards.RewardsDatabase.getInstance(context)
                        val dao = db.rewardsDao()
                        val existingCount = dao.getTransactionLogCount()

                        val pageSize = 200
                        val all = mutableListOf<MemoTransactionLogData>()

                        val firstPage = api.getTransactionHistory(wallet, limit = pageSize, offset = 0)
                        if (firstPage != null) {
                            all.addAll(firstPage.transactions)
                            if (existingCount == 0) {
                                var nextOffset = pageSize
                                while (all.size < firstPage.total && nextOffset < 2000) {
                                    val page = api.getTransactionHistory(wallet, limit = pageSize, offset = nextOffset) ?: break
                                    if (page.transactions.isEmpty()) break
                                    all.addAll(page.transactions)
                                    nextOffset += pageSize
                                }
                            }
                        } else if (profile.memoTransactions.isNotEmpty()) {
                            all.addAll(profile.memoTransactions)
                        }

                        if (all.isNotEmpty()) {
                            val entities = all
                                .distinctBy { it.id }
                                .map {
                                    com.soulon.app.rewards.MemoTransactionLog(
                                        remoteId = it.id,
                                        walletAddress = it.walletAddress.ifBlank { wallet },
                                        transactionType = it.transactionType,
                                        amount = it.amount,
                                        description = it.description,
                                        createdAt = it.createdAt,
                                        metadataJson = it.metadataJson
                                    )
                                }
                            dao.upsertTransactionLogs(entities)
                        }
                    }.onFailure { e ->
                        Timber.e(e, "同步积分交易日志失败")
                    }
                }
                
                // 更新 AI 服务配置
                _aiConfig.value = profile.aiConfig
                
                // 强制更新 RemoteConfigManager 中的配置
                com.soulon.app.config.RemoteConfigManager.getInstance(context).syncFromBackend()

                if (profile.aiConfig != null) {
                    Timber.i("🔑 AI 配置已更新：")
                    Timber.i("  - Qwen Endpoint: ${profile.aiConfig.qwenEndpoint ?: "默认"}")
                    Timber.i("  - Embedding Endpoint: ${profile.aiConfig.embeddingEndpoint ?: "默认"}")
                }
                
                _lastSyncTime.value = profile.syncedAt
                prefs.edit().putLong(PREF_LAST_SYNC, profile.syncedAt).apply()
                
                Timber.i("✅ 完整数据同步成功: balance=${profile.memoBalance}, tier=${profile.currentTier}, sessions=${profile.chatSessions.size}")
                true
            } else {
                Timber.w("后端无用户数据，可能是新用户")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ 同步完整数据失败")
            false
        } finally {
            _isSyncing.value = false
        }
    }
    
    /**
     * 检查是否需要同步
     */
    fun needsSync(): Boolean {
        val lastSync = _lastSyncTime.value
        val now = System.currentTimeMillis() / 1000
        return (now - lastSync) > (SYNC_INTERVAL_MS / 1000)
    }
    
    /**
     * 如果需要则同步
     */
    suspend fun syncIfNeeded(): Boolean {
        return if (needsSync()) {
            syncFullProfile()
        } else {
            false
        }
    }
    
    // ============================================
    // 用户数据操作
    // ============================================
    
    /**
     * 获取当前用户档案
     */
    fun getCurrentProfile(): FullUserProfile? = _userProfile.value
    
    /**
     * 获取当前积分余额
     */
    fun getMemoBalance(): Int = _userProfile.value?.memoBalance ?: 0
    
    /**
     * 获取当前 Tier 等级
     */
    fun getCurrentTier(): Int = _userProfile.value?.currentTier ?: 1
    
    /**
     * 获取订阅类型
     */
    fun getSubscriptionType(): String = _userProfile.value?.subscriptionType ?: "FREE"
    
    /**
     * 是否已订阅
     */
    fun isSubscribed(): Boolean {
        val profile = _userProfile.value ?: return false
        val expiry = profile.subscriptionExpiry ?: return profile.subscriptionType != "FREE"
        return profile.subscriptionType != "FREE" && expiry > System.currentTimeMillis() / 1000
    }
    
    // ============================================
    // AI 服务配置
    // ============================================
    
    /**
     * 获取 Qwen API 密钥（仅从后端获取，不使用硬编码）
     * 
     * @return API 密钥，如果未配置则返回 null
     */
    fun getQwenApiKey(): String? {
        val backendKey = _aiConfig.value?.qwenApiKey
        return backendKey?.takeIf { it.isNotBlank() }
    }
    
    /**
     * 获取 Qwen API 端点
     * 确保返回正确的 Chat 端点
     */
    fun getQwenEndpoint(): String {
        val defaultEndpoint = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
        val backendEndpoint = _aiConfig.value?.qwenEndpoint
        
        if (backendEndpoint.isNullOrBlank()) {
            return defaultEndpoint
        }
        
        // 如果后端配置了 Embedding 端点作为 Chat 端点，这是错误的
        if (backendEndpoint.contains("embeddings")) {
            Timber.w("⚠️ 后端配置的 Chat 端点似乎是 Embedding 端点，使用默认 Chat 端点")
            return defaultEndpoint
        }
        
        return backendEndpoint
    }
    
    /**
     * 获取 Embedding API 密钥（仅从后端获取）
     * 
     * @return API 密钥，如果未配置则返回 null
     */
    fun getEmbeddingApiKey(): String? {
        val backendKey = _aiConfig.value?.embeddingApiKey
        return backendKey?.takeIf { it.isNotBlank() } ?: getQwenApiKey()
    }
    
    /**
     * 获取 Embedding API 端点
     * 确保返回正确的 Embedding 端点，不会误用 Chat 端点
     */
    fun getEmbeddingEndpoint(): String {
        val defaultEndpoint = "https://dashscope-intl.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding"
        val backendEndpoint = _aiConfig.value?.embeddingEndpoint
        
        // 验证端点是否正确（不是 Chat 端点）
        if (backendEndpoint.isNullOrBlank()) {
            return defaultEndpoint
        }
        
        // 如果后端配置了 Chat 端点作为 Embedding 端点，这是错误的
        if (backendEndpoint.contains("chat/completions")) {
            Timber.w("⚠️ 后端配置的 Embedding 端点似乎是 Chat 端点，使用默认 Embedding 端点")
            return defaultEndpoint
        }
        
        return backendEndpoint
    }
    
    /**
     * 检查是否已配置 AI 服务
     */
    fun isAiServiceConfigured(): Boolean = true
    
    /**
     * 获取 AI 配置状态描述
     */
    fun getAiConfigStatus(): String {
        return "AI 服务通过后端代理提供"
    }
    
    /**
     * 同步用户基本数据到后端
     */
    suspend fun syncUserDataToBackend(
        memoBalance: Int,
        currentTier: Int,
        subscriptionType: String,
        subscriptionExpiry: Long?,
        stakedAmount: Long,
        totalTokensUsed: Int,
        memoriesCount: Int
    ): Boolean {
        val wallet = currentWallet ?: return false
        return api.syncUserData(
            wallet, memoBalance, currentTier, subscriptionType,
            subscriptionExpiry, stakedAmount, totalTokensUsed, memoriesCount
        )
    }
    
    // ============================================
    // 聊天数据操作
    // ============================================
    
    /**
     * 获取聊天会话列表
     */
    suspend fun getChatSessions(forceRefresh: Boolean = false): List<ChatSessionData> {
        val wallet = currentWallet ?: return emptyList()
        
        if (!forceRefresh && _chatSessions.value.isNotEmpty()) {
            return _chatSessions.value
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val sessions = api.getChatSessions(wallet) ?: emptyList()
                _chatSessions.value = sessions
                sessions
            } catch (e: Exception) {
                Timber.e(e, "获取聊天会话失败")
                _chatSessions.value
            }
        }
    }
    
    /**
     * 创建新聊天会话
     */
    suspend fun createChatSession(title: String = "新对话"): ChatSessionData? {
        val wallet = currentWallet ?: return null
        
        return withContext(Dispatchers.IO) {
            try {
                val session = api.createChatSession(wallet, title)
                if (session != null) {
                    _chatSessions.value = listOf(session) + _chatSessions.value
                }
                session
            } catch (e: Exception) {
                Timber.e(e, "创建聊天会话失败")
                null
            }
        }
    }
    
    /**
     * 获取会话消息
     */
    suspend fun getChatMessages(sessionId: String, afterTimestamp: Long? = null): List<ChatMessageData> {
        return withContext(Dispatchers.IO) {
            try {
                api.getChatMessages(sessionId, afterTimestamp = afterTimestamp) ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "获取聊天消息失败")
                emptyList()
            }
        }
    }
    
    /**
     * 同步消息到后端
     */
    suspend fun syncMessages(sessionId: String, messages: List<ChatMessageData>): Boolean {
        val wallet = currentWallet ?: return false
        
        return withContext(Dispatchers.IO) {
            try {
                api.syncChatMessages(wallet, sessionId, messages)
            } catch (e: Exception) {
                Timber.e(e, "同步消息失败")
                false
            }
        }
    }
    
    // ============================================
    // 人格数据操作
    // ============================================
    
    /**
     * 获取人格数据
     */
    suspend fun getPersona(forceRefresh: Boolean = false): PersonaData? {
        val wallet = currentWallet ?: return null
        
        if (!forceRefresh && _persona.value != null) {
            return _persona.value
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val persona = api.getPersona(wallet)
                _persona.value = persona
                persona
            } catch (e: Exception) {
                Timber.e(e, "获取人格数据失败")
                _persona.value
            }
        }
    }
    
    /**
     * 更新人格数据
     */
    suspend fun updatePersona(persona: PersonaData): Boolean {
        val wallet = currentWallet ?: return false
        
        return withContext(Dispatchers.IO) {
            try {
                val success = api.updatePersona(wallet, persona)
                if (success) {
                    _persona.value = persona
                }
                success
            } catch (e: Exception) {
                Timber.e(e, "更新人格数据失败")
                false
            }
        }
    }
    
    /**
     * 提交问卷答案
     */
    suspend fun submitQuestionnaire(answers: Map<String, Any>, personaScores: PersonaData): Boolean {
        val wallet = currentWallet ?: return false
        
        return withContext(Dispatchers.IO) {
            try {
                val success = api.submitQuestionnaire(wallet, answers, personaScores)
                if (success) {
                    _persona.value = personaScores.copy(questionnaireCompleted = true)
                }
                success
            } catch (e: Exception) {
                Timber.e(e, "提交问卷失败")
                false
            }
        }
    }
    
    // ============================================
    // 向量数据操作
    // ============================================
    
    /**
     * 语义搜索
     */
    suspend fun searchVectors(
        queryVector: FloatArray,
        topK: Int = 5,
        threshold: Float = 0.7f
    ): List<VectorSearchResult> {
        val wallet = currentWallet ?: return emptyList()
        
        return withContext(Dispatchers.IO) {
            try {
                api.searchVectors(wallet, queryVector, topK, threshold) ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "向量搜索失败")
                emptyList()
            }
        }
    }
    
    /**
     * 上传向量
     */
    suspend fun uploadVectors(vectors: List<VectorUploadData>): Boolean {
        val wallet = currentWallet ?: return false
        
        return withContext(Dispatchers.IO) {
            try {
                api.uploadVectors(wallet, vectors)
            } catch (e: Exception) {
                Timber.e(e, "上传向量失败")
                false
            }
        }
    }
    
    // ============================================
    // 奇遇问题操作
    // ============================================
    
    /**
     * 获取待回答问题
     */
    suspend fun getPendingQuestions(forceRefresh: Boolean = false): List<ProactiveQuestionData> {
        val wallet = currentWallet ?: return emptyList()
        
        if (!forceRefresh && _pendingQuestions.value.isNotEmpty()) {
            return _pendingQuestions.value
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val questions = api.getPendingQuestions(wallet) ?: emptyList()
                _pendingQuestions.value = questions
                questions
            } catch (e: Exception) {
                Timber.e(e, "获取待回答问题失败")
                _pendingQuestions.value
            }
        }
    }
    
    /**
     * 回答问题
     */
    suspend fun answerQuestion(
        questionId: String,
        answerText: String,
        personaImpact: Map<String, Float>?,
        rewardedMemo: Int
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val success = api.answerQuestion(questionId, answerText, personaImpact, rewardedMemo)
                if (success) {
                    // 从待回答列表中移除
                    _pendingQuestions.value = _pendingQuestions.value.filter { it.id != questionId }
                }
                success
            } catch (e: Exception) {
                Timber.e(e, "回答问题失败")
                false
            }
        }
    }
    
    /**
     * 创建问题
     */
    suspend fun createQuestion(questionText: String, category: String, priority: Int = 0): String? {
        val wallet = currentWallet ?: return null
        
        return withContext(Dispatchers.IO) {
            try {
                api.createQuestion(wallet, questionText, category, priority)
            } catch (e: Exception) {
                Timber.e(e, "创建问题失败")
                null
            }
        }
    }
    
    // ============================================
    // 清理
    // ============================================
    
    /**
     * 清除缓存数据
     */
    fun clearCache() {
        _userProfile.value = null
        _chatSessions.value = emptyList()
        _persona.value = null
        _pendingQuestions.value = emptyList()
        _lastSyncTime.value = 0
        currentWallet = null
        prefs.edit().clear().apply()
    }
    
    /**
     * 断开连接（登出时调用）
     */
    fun disconnect() {
        clearCache()
    }
}
