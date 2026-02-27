package com.soulon.app.chat

import android.content.Context
import com.soulon.app.data.ChatMessageData
import com.soulon.app.data.ChatSessionData
import com.soulon.app.data.CloudDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * 聊天会话（UI 模型）
 */
data class ChatSession(
    val id: String,
    val title: String,
    val timestamp: Long,
    val messageCount: Int = 0,
    val totalTokens: Int = 0,
    val totalMemoEarned: Int = 0
)

/**
 * 聊天消息（UI 模型）
 */
data class ChatMessageModel(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val retrievedMemories: List<String> = emptyList(),
    val rewardedMemo: Int = 0,
    val isError: Boolean = false,
    val pendingDecryption: Boolean = false,
    val encryptedMemoryIds: List<String> = emptyList(),
    val tokensUsed: Int = 0,
    val isPersonaRelevant: Boolean = false,
    val relevanceScore: Float = 0f
)

/**
 * 聊天仓库
 * 
 * 管理聊天会话和消息的持久化
 * 
 * 数据流向（云端优先）：
 * - 读取：后端 -> 本地缓存 -> UI
 * - 写入：本地缓存 + 后端（异步）
 */
class ChatRepository(private val context: Context) {
    
    private val database = com.soulon.app.rewards.RewardsDatabase.getInstance(context)
    private val chatDao = database.chatDao()
    private val cloudRepo by lazy { CloudDataRepository.getInstance(context) }
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // 当前钱包地址（用于后端同步）
    private var currentWallet: String? = null
    
    // 会话列表状态（支持后端数据）
    private val _cloudSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val cloudSessions = _cloudSessions.asStateFlow()
    
    /**
     * 设置当前钱包地址
     */
    fun setWalletAddress(walletAddress: String?) {
        currentWallet = walletAddress
        if (walletAddress != null) {
            // 从后端加载会话
            scope.launch {
                loadSessionsFromCloud()
            }
        }
    }
    
    /**
     * 从后端加载会话列表
     */
    private suspend fun loadSessionsFromCloud() {
        val wallet = currentWallet ?: return
        try {
            val sessions = cloudRepo.getChatSessions(forceRefresh = true)
            _cloudSessions.value = sessions.map { it.toModel() }
            Timber.i("从后端加载了 ${sessions.size} 个会话")
        } catch (e: Exception) {
            Timber.e(e, "从后端加载会话失败")
        }
    }
    
    // ========== 会话操作 ==========
    
    /**
     * 获取所有会话（本地数据库 Flow）
     */
    fun getAllSessions(): Flow<List<ChatSession>> {
        return chatDao.getAllSessions().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    /**
     * 获取所有会话（云端优先）
     */
    suspend fun getAllSessionsCloud(): List<ChatSession> {
        // 优先从后端获取
        val cloudSessions = cloudRepo.getChatSessions()
        if (cloudSessions.isNotEmpty()) {
            _cloudSessions.value = cloudSessions.map { it.toModel() }
            return _cloudSessions.value
        }
        // 后备使用本地数据
        return chatDao.getAllSessionsOnce().map { it.toModel() }
    }
    
    /**
     * 创建新会话
     */
    suspend fun createSession(title: String = "新对话"): ChatSession {
        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        
        // 1. 先保存到本地（保证离线可用）
        val localSession = ChatSessionEntity(
            id = sessionId,
            title = title,
            createdAt = now,
            updatedAt = now,
            messageCount = 0
        )
        chatDao.insertSession(localSession)
        
        // 2. 异步同步到后端
        if (currentWallet != null) {
            scope.launch {
                try {
                    val cloudSession = cloudRepo.createChatSession(title)
                    if (cloudSession != null) {
                        // 更新本地 ID 为后端 ID（如果不同）
                        if (cloudSession.id != sessionId) {
                            // 可以选择更新本地记录或保持不变
                            Timber.d("后端会话创建成功: ${cloudSession.id}")
                        }
                        // 刷新会话列表
                        loadSessionsFromCloud()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "后端会话创建失败，仅保留本地")
                }
            }
        }
        
        Timber.d("创建新会话: $sessionId")
        return localSession.toModel()
    }
    
    /**
     * 更新会话标题
     */
    suspend fun updateSessionTitle(sessionId: String, title: String) {
        val session = chatDao.getSession(sessionId) ?: return
        chatDao.updateSession(session.copy(
            title = title,
            updatedAt = System.currentTimeMillis()
        ))
        // TODO: 同步到后端
    }
    
    /**
     * 删除会话
     */
    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteSessionById(sessionId)
        // TODO: 同步到后端
        Timber.d("删除会话: $sessionId")
    }
    
    // ========== 消息操作 ==========
    
    /**
     * 获取会话的所有消息（本地 Flow）
     */
    fun getMessages(sessionId: String): Flow<List<ChatMessageModel>> {
        return chatDao.getMessagesForSession(sessionId).map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    /**
     * 获取会话的所有消息（一次性，本地）
     */
    suspend fun getMessagesOnce(sessionId: String): List<ChatMessageModel> {
        return chatDao.getMessagesForSessionOnce(sessionId).map { it.toModel() }
    }
    
    /**
     * 获取会话的所有消息（云端优先）
     */
    suspend fun getMessagesCloud(sessionId: String): List<ChatMessageModel> {
        // 优先从后端获取
        try {
            val cloudMessages = cloudRepo.getChatMessages(sessionId)
            if (cloudMessages.isNotEmpty()) {
                return cloudMessages.map { it.toModel() }
            }
        } catch (e: Exception) {
            Timber.w(e, "从后端获取消息失败，使用本地数据")
        }
        // 后备使用本地数据
        return getMessagesOnce(sessionId)
    }
    
    /**
     * 添加消息
     */
    suspend fun addMessage(
        sessionId: String,
        text: String,
        isUser: Boolean,
        retrievedMemories: List<String> = emptyList(),
        rewardedMemo: Int = 0,
        isError: Boolean = false,
        tokensUsed: Int = 0,
        isPersonaRelevant: Boolean = false,
        relevanceScore: Float = 0f
    ): ChatMessageModel {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        
        // 1. 先保存到本地
        val message = ChatMessageEntity(
            id = messageId,
            sessionId = sessionId,
            text = text,
            isUser = isUser,
            timestamp = timestamp,
            retrievedMemories = retrievedMemories.joinToString(","),
            rewardedMemo = rewardedMemo,
            isError = isError,
            isPersonaRelevant = isPersonaRelevant,
            relevanceScore = relevanceScore
        )
        
        chatDao.insertMessage(message)
        
        // 更新会话
        val session = chatDao.getSession(sessionId)
        if (session != null) {
            val messageCount = chatDao.getMessageCount(sessionId)
            chatDao.updateSession(session.copy(
                updatedAt = System.currentTimeMillis(),
                messageCount = messageCount
            ))
            
            // 如果是第一条用户消息，自动更新标题
            if (isUser && messageCount == 1) {
                val title = text.take(30) + if (text.length > 30) "..." else ""
                chatDao.updateSession(session.copy(title = title))
            }
        }
        
        // 2. 异步同步到后端
        if (currentWallet != null) {
            scope.launch {
                try {
                    val cloudMessage = ChatMessageData(
                        id = messageId,
                        sessionId = sessionId,
                        text = text,
                        isUser = isUser,
                        timestamp = timestamp,
                        tokensUsed = tokensUsed,
                        rewardedMemo = rewardedMemo,
                        isPersonaRelevant = isPersonaRelevant,
                        relevanceScore = relevanceScore,
                        isError = isError
                    )
                    cloudRepo.syncMessages(sessionId, listOf(cloudMessage))
                    Timber.d("消息已同步到后端: $messageId")
                } catch (e: Exception) {
                    Timber.w(e, "消息同步到后端失败")
                }
            }
        }
        
        Timber.d("添加消息到会话 $sessionId: ${text.take(50)}...")
        return message.toModel()
    }
    
    /**
     * 更新消息
     */
    suspend fun updateMessage(
        messageId: String,
        sessionId: String,
        text: String,
        retrievedMemories: List<String> = emptyList(),
        rewardedMemo: Int = 0,
        isError: Boolean = false
    ) {
        val messages = chatDao.getMessagesForSessionOnce(sessionId)
        val message = messages.find { it.id == messageId } ?: return
        
        chatDao.updateMessage(message.copy(
            text = text,
            retrievedMemories = retrievedMemories.joinToString(","),
            rewardedMemo = rewardedMemo,
            isError = isError
        ))
        // TODO: 同步到后端
    }
    
    /**
     * 批量同步本地消息到后端（用于数据迁移）
     */
    suspend fun syncLocalMessagesToCloud(sessionId: String): Boolean {
        val wallet = currentWallet ?: return false
        
        try {
            val localMessages = chatDao.getMessagesForSessionOnce(sessionId)
            if (localMessages.isEmpty()) return true
            
            val cloudMessages = localMessages.map { msg ->
                ChatMessageData(
                    id = msg.id,
                    sessionId = sessionId,
                    text = msg.text,
                    isUser = msg.isUser,
                    timestamp = msg.timestamp,
                    tokensUsed = 0,
                    rewardedMemo = msg.rewardedMemo,
                    isPersonaRelevant = msg.isPersonaRelevant,
                    relevanceScore = msg.relevanceScore,
                    isError = msg.isError
                )
            }
            
            return cloudRepo.syncMessages(sessionId, cloudMessages)
        } catch (e: Exception) {
            Timber.e(e, "批量同步消息到后端失败")
            return false
        }
    }
    
    // ========== 转换函数 ==========
    
    private fun ChatSessionEntity.toModel(): ChatSession {
        return ChatSession(
            id = id,
            title = title,
            timestamp = updatedAt,
            messageCount = messageCount
        )
    }
    
    private fun ChatSessionData.toModel(): ChatSession {
        return ChatSession(
            id = id,
            title = title,
            timestamp = updatedAt,
            messageCount = messageCount,
            totalTokens = totalTokens,
            totalMemoEarned = totalMemoEarned
        )
    }
    
    private fun ChatMessageEntity.toModel(): ChatMessageModel {
        return ChatMessageModel(
            id = id,
            text = text,
            isUser = isUser,
            timestamp = timestamp,
            retrievedMemories = if (retrievedMemories.isBlank()) emptyList() else retrievedMemories.split(","),
            rewardedMemo = rewardedMemo,
            isError = isError,
            isPersonaRelevant = isPersonaRelevant,
            relevanceScore = relevanceScore
        )
    }
    
    private fun ChatMessageData.toModel(): ChatMessageModel {
        return ChatMessageModel(
            id = id,
            text = text,
            isUser = isUser,
            timestamp = timestamp,
            rewardedMemo = rewardedMemo,
            isError = isError,
            tokensUsed = tokensUsed,
            isPersonaRelevant = isPersonaRelevant,
            relevanceScore = relevanceScore
        )
    }
}
