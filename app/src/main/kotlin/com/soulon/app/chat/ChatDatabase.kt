package com.soulon.app.chat

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 聊天会话实体
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "title")
    val title: String,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    
    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0
)

/**
 * 聊天消息实体
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id"])]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    
    @ColumnInfo(name = "text")
    val text: String,
    
    @ColumnInfo(name = "is_user")
    val isUser: Boolean,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    
    @ColumnInfo(name = "retrieved_memories")
    val retrievedMemories: String = "", // JSON 序列化的记忆 ID 列表
    
    @ColumnInfo(name = "rewarded_memo")
    val rewardedMemo: Int = 0,
    
    @ColumnInfo(name = "is_error")
    val isError: Boolean = false,
    
    // ========== 人格相关性字段 ==========
    
    @ColumnInfo(name = "is_persona_relevant", defaultValue = "0")
    val isPersonaRelevant: Boolean = false, // 是否涉及人格
    
    @ColumnInfo(name = "relevance_score", defaultValue = "0.0")
    val relevanceScore: Float = 0f, // 人格相关度分数
    
    @ColumnInfo(name = "irys_transaction_id")
    val irysTransactionId: String? = null, // Irys 交易 ID（已上传时非空）
    
    @ColumnInfo(name = "detected_traits")
    val detectedTraits: String = "" // 检测到的特质（JSON 数组）
)

/**
 * 聊天 DAO
 */
@Dao
interface ChatDao {
    
    // ========== 会话操作 ==========
    
    /**
     * 获取所有会话（按更新时间倒序）
     */
    @Query("SELECT * FROM chat_sessions ORDER BY updated_at DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>
    
    /**
     * 获取所有会话（一次性查询，用于同步）
     */
    @Query("SELECT * FROM chat_sessions ORDER BY updated_at DESC")
    suspend fun getAllSessionsOnce(): List<ChatSessionEntity>
    
    /**
     * 获取会话
     */
    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): ChatSessionEntity?
    
    /**
     * 创建会话
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)
    
    /**
     * 更新会话
     */
    @Update
    suspend fun updateSession(session: ChatSessionEntity)
    
    /**
     * 删除会话
     */
    @Delete
    suspend fun deleteSession(session: ChatSessionEntity)
    
    /**
     * 删除会话（通过 ID）
     */
    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)
    
    // ========== 消息操作 ==========
    
    /**
     * 获取会话的所有消息
     */
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>
    
    /**
     * 获取会话的所有消息（一次性）
     */
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSessionOnce(sessionId: String): List<ChatMessageEntity>
    
    /**
     * 插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)
    
    /**
     * 批量插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)
    
    /**
     * 更新消息
     */
    @Update
    suspend fun updateMessage(message: ChatMessageEntity)
    
    /**
     * 删除消息
     */
    @Delete
    suspend fun deleteMessage(message: ChatMessageEntity)
    
    /**
     * 删除会话的所有消息
     */
    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)
    
    /**
     * 获取会话的消息数量
     */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE session_id = :sessionId")
    suspend fun getMessageCount(sessionId: String): Int
    
    /**
     * 获取会话的最后一条消息
     */
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(sessionId: String): ChatMessageEntity?
}
