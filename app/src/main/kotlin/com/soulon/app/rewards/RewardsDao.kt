package com.soulon.app.rewards

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Rewards DAO (Data Access Object)
 * 
 * 管理 $MEMO 积分和用户档案的数据库访问
 * 
 * Phase 3 Week 2: Task_Tier_System
 */
@Dao
interface RewardsDao {
    
    // ======================== UserProfile 操作 ========================
    
    /**
     * 获取用户档案（Flow，实时更新）
     */
    @Query("SELECT * FROM user_profile WHERE userId = :userId LIMIT 1")
    fun getUserProfileFlow(userId: String = "default_user"): Flow<UserProfile?>
    
    /**
     * 获取用户档案（单次查询）
     */
    @Query("SELECT * FROM user_profile WHERE userId = :userId LIMIT 1")
    suspend fun getUserProfile(userId: String = "default_user"): UserProfile?
    
    /**
     * 插入或更新用户档案
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserProfile(profile: UserProfile)
    
    /**
     * 插入或更新用户档案（别名，用于数据同步）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateUserProfile(profile: UserProfile)
    
    /**
     * 更新 $MEMO 余额
     */
    @Query("UPDATE user_profile SET memoBalance = memoBalance + :amount, lastActiveAt = :timestamp WHERE userId = :userId")
    suspend fun updateMemoBalance(userId: String = "default_user", amount: Int, timestamp: Long = System.currentTimeMillis())
    
    /**
     * 更新累计积分
     */
    @Query("UPDATE user_profile SET totalMemoEarned = totalMemoEarned + :amount WHERE userId = :userId")
    suspend fun updateTotalMemoEarned(userId: String = "default_user", amount: Int)
    
    /**
     * 更新 Tier 等级
     */
    @Query("UPDATE user_profile SET currentTier = :tier, lastTierUpdate = :timestamp WHERE userId = :userId")
    suspend fun updateTier(userId: String = "default_user", tier: Int, timestamp: Long = System.currentTimeMillis())
    
    /**
     * 更新推理统计
     */
    @Query("UPDATE user_profile SET totalTokensGenerated = totalTokensGenerated + :tokens, totalInferences = totalInferences + 1 WHERE userId = :userId")
    suspend fun updateInferenceStats(userId: String = "default_user", tokens: Int)
    
    /**
     * 更新人格数据（使用完整的 UserProfile 更新）
     */
    @Query("UPDATE user_profile SET lastPersonaAnalysis = :timestamp, personaSyncRate = :syncRate WHERE userId = :userId")
    suspend fun updatePersonaAnalysisTime(userId: String = "default_user", timestamp: Long = System.currentTimeMillis(), syncRate: Float)
    
    /**
     * 更新完整的用户档案（包含人格数据）
     */
    @Update
    suspend fun updateUserProfile(profile: UserProfile)
    
    /**
     * 更新 Sovereign Ratio
     */
    @Query("UPDATE user_profile SET sovereignRatio = :ratio WHERE userId = :userId")
    suspend fun updateSovereignRatio(userId: String = "default_user", ratio: Float)
    
    // ======================== MemoTransaction 操作 ========================
    
    /**
     * 插入交易记录
     */
    @Insert
    suspend fun insertTransaction(transaction: MemoTransaction): Long
    
    /**
     * 获取所有交易记录（Flow，实时更新）
     */
    @Query("SELECT * FROM memo_transactions ORDER BY timestamp DESC")
    fun getAllTransactionsFlow(): Flow<List<MemoTransaction>>
    
    /**
     * 获取最近的交易记录
     */
    @Query("SELECT * FROM memo_transactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTransactions(limit: Int = 20): List<MemoTransaction>
    
    /**
     * 根据类型获取交易记录
     */
    @Query("SELECT * FROM memo_transactions WHERE type = :type ORDER BY timestamp DESC")
    fun getTransactionsByType(type: TransactionType): Flow<List<MemoTransaction>>
    
    /**
     * 根据记忆 ID 获取交易记录
     */
    @Query("SELECT * FROM memo_transactions WHERE memoryId = :memoryId ORDER BY timestamp DESC")
    suspend fun getTransactionsByMemory(memoryId: String): List<MemoTransaction>
    
    /**
     * 获取时间范围内的交易
     */
    @Query("SELECT * FROM memo_transactions WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    suspend fun getTransactionsBetween(startTime: Long, endTime: Long): List<MemoTransaction>
    
    /**
     * 计算时间范围内的总收入
     */
    @Query("SELECT SUM(amount) FROM memo_transactions WHERE amount > 0 AND timestamp BETWEEN :startTime AND :endTime")
    suspend fun getTotalIncomeBetween(startTime: Long, endTime: Long): Int?
    
    /**
     * 计算时间范围内的总支出
     */
    @Query("SELECT SUM(ABS(amount)) FROM memo_transactions WHERE amount < 0 AND timestamp BETWEEN :startTime AND :endTime")
    suspend fun getTotalSpendBetween(startTime: Long, endTime: Long): Int?
    
    /**
     * 获取交易总数
     */
    @Query("SELECT COUNT(*) FROM memo_transactions")
    suspend fun getTransactionCount(): Int
    
    /**
     * 删除旧交易记录（保留最近 N 条）
     */
    @Query("DELETE FROM memo_transactions WHERE id NOT IN (SELECT id FROM memo_transactions ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun deleteOldTransactions(keepCount: Int = 1000)
    
    /**
     * 清空所有交易记录
     */
    @Query("DELETE FROM memo_transactions")
    suspend fun deleteAllTransactions()

    // ======================== MemoTransactionLog 操作（后端同步日志） ========================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactionLogs(logs: List<MemoTransactionLog>)

    @Query("SELECT * FROM memo_transaction_logs ORDER BY createdAt DESC")
    fun getAllTransactionLogsFlow(): Flow<List<MemoTransactionLog>>

    @Query("SELECT COUNT(*) FROM memo_transaction_logs")
    suspend fun getTransactionLogCount(): Int

    @Query("DELETE FROM memo_transaction_logs")
    suspend fun deleteAllTransactionLogs()
    
    // ======================== V1 积分系统新增操作 ========================
    
    /**
     * 重置每日对话计数
     */
    @Query("UPDATE user_profile SET dailyDialogueCount = 0, lastDialogueResetDate = :today, hasFirstChatToday = 0 WHERE userId = :userId")
    suspend fun resetDailyDialogueCount(userId: String = "default_user", today: String)
    
    /**
     * 增加每日对话计数
     */
    @Query("UPDATE user_profile SET dailyDialogueCount = dailyDialogueCount + 1 WHERE userId = :userId")
    suspend fun incrementDailyDialogueCount(userId: String = "default_user")
    
    /**
     * 设置今日首聊状态
     */
    @Query("UPDATE user_profile SET hasFirstChatToday = :hasFirstChat WHERE userId = :userId")
    suspend fun setFirstChatToday(userId: String = "default_user", hasFirstChat: Boolean)
    
    /**
     * 更新签到数据
     */
    @Query("""
        UPDATE user_profile SET 
            lastCheckInDate = :lastCheckInDate,
            consecutiveCheckInDays = :consecutiveDays,
            weeklyCheckInProgress = :weeklyProgress,
            totalCheckInDays = :totalCheckInDays
        WHERE userId = :userId
    """)
    suspend fun updateCheckInData(
        userId: String = "default_user",
        lastCheckInDate: String,
        consecutiveDays: Int,
        weeklyProgress: Int,
        totalCheckInDays: Int
    )
}
