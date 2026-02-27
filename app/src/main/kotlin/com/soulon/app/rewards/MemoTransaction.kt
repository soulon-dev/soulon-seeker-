package com.soulon.app.rewards

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.util.Date

/**
 * $MEMO 积分交易记录
 * 
 * 用于记录所有的积分变动，包括：
 * - AI 推理奖励（算力证明）
 * - 等级加成奖励
 * - 其他活动奖励
 * 
 * Phase 3 Week 2: Task_Tier_System
 */
@Entity(tableName = "memo_transactions")
@TypeConverters(TransactionTypeConverter::class)
data class MemoTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** 交易类型 */
    val type: TransactionType,
    
    /** 积分变动数量（正数为获得，负数为消耗） */
    val amount: Int,
    
    /** 交易时间 */
    val timestamp: Long = System.currentTimeMillis(),
    
    /** 交易描述 */
    val description: String,
    
    /** 关联的记忆 ID（如果适用） */
    val memoryId: String? = null,
    
    /** 推理生成的 Token 数量（用于算力证明） */
    val tokensGenerated: Int? = null,
    
    /** 用户当时的 Tier 等级 */
    val userTier: Int? = null,
    
    /** Tier 加成倍数 */
    val tierMultiplier: Float? = null
) {
    /**
     * 获取格式化的时间
     */
    fun getFormattedDate(): Date = Date(timestamp)
    
    /**
     * 是否为收入
     */
    fun isIncome(): Boolean = amount > 0
    
    /**
     * 获取带符号的积分字符串
     */
    fun getSignedAmount(): String = if (amount >= 0) "+$amount" else "$amount"
}

/**
 * 交易类型
 */
enum class TransactionType {
    /** AI 推理奖励（算力证明） */
    AI_INFERENCE_REWARD,
    
    /** Tier 等级加成 */
    TIER_BONUS,
    
    /** 每日登录奖励 */
    DAILY_LOGIN,
    
    /** 每日签到奖励（7天循环） */
    CHECK_IN,
    
    /** 每日首聊/每日奖励 */
    DAILY_BONUS,
    
    /** 完成任务奖励 */
    TASK_COMPLETION,
    
    /** 创建记忆奖励 */
    MEMORY_CREATION,
    
    /** 分享记忆奖励 */
    MEMORY_SHARE,
    
    /** 人格共鸣奖励 (S级特效) */
    SOUL_RESONANCE,
    
    /** 积分消耗（如：解锁功能） */
    SPEND,
    
    /** 系统调整 */
    SYSTEM_ADJUSTMENT,
    
    /** 其他 */
    OTHER
}

/**
 * Room TypeConverter for TransactionType
 */
class TransactionTypeConverter {
    @TypeConverter
    fun fromTransactionType(type: TransactionType): String = type.name
    
    @TypeConverter
    fun toTransactionType(value: String): TransactionType = 
        TransactionType.valueOf(value)
}
