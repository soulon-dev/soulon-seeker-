package com.soulon.app.rewards

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.soulon.app.i18n.AppStrings

/**
 * 用户档案
 * 
 * 存储用户的积分、等级、人格数据等信息
 * 
 * Phase 3 Week 2: Task_Tier_System & Task_Persona_Analysis
 */
@Entity(tableName = "user_profile")
@TypeConverters(PersonaDataConverter::class, PersonaProfileV2Converter::class)
data class UserProfile(
    @PrimaryKey
    val userId: String = "default_user",
    
    /** 当前 $MEMO 积分余额 */
    val memoBalance: Int = 0,
    
    /** 累计获得的总积分 */
    val totalMemoEarned: Int = 0,
    
    /** 当前 Tier 等级 (1-5) */
    val currentTier: Int = 1,
    
    /** 上次等级更新时间 */
    val lastTierUpdate: Long = System.currentTimeMillis(),
    
    /** 累计生成的 Token 数量（算力证明） */
    val totalTokensGenerated: Int = 0,
    
    /** 累计推理次数 */
    val totalInferences: Int = 0,
    
    /** Sovereign Ratio（硬件授权比例，0.0-1.0） */
    val sovereignRatio: Float = 0.0f,
    
    /** OCEAN 人格数据（JSON 格式） */
    val personaData: PersonaData? = null,

    /** 人格画像 V2（带置信度/证据链） */
    val personaProfileV2: PersonaProfileV2? = null,
    
    /** 上次人格分析时间 */
    val lastPersonaAnalysis: Long? = null,
    
    /** 人格同步率（0.0-1.0） */
    val personaSyncRate: Float? = null,
    
    /** 账号创建时间 */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** 最后活跃时间 */
    val lastActiveAt: Long = System.currentTimeMillis(),
    
    // ========== 用户级别相关字段 ==========
    
    /** 订阅类型：FREE, MONTHLY, YEARLY */
    val subscriptionType: String = "FREE",
    
    /** 订阅到期时间 */
    val subscriptionExpiry: Long? = null,
    
    /** 质押数量（lamports） */
    val stakedAmount: Long = 0L,
    
    /** 质押开始时间 */
    val stakingStartTime: Long? = null,
    
    /** 是否创始人用户 */
    val isFounder: Boolean = false,
    
    /** 创始人资格获取时间 */
    val founderSince: Long? = null,
    
    /** 是否技术专家用户 */
    val isExpert: Boolean = false,
    
    /** 技术专家资格获取时间 */
    val expertSince: Long? = null,
    
    // ========== Token 使用统计 ==========
    
    /** 今日 Token 使用量 */
    val dailyTokensUsed: Long = 0L,
    
    /** 上次 Token 重置日期 (YYYY-MM-DD) */
    val lastTokenResetDate: String = "",
    
    /** 累计使用 Token */
    val totalLifetimeTokens: Long = 0L,
    
    // ========== V1 积分系统新增字段 ==========
    
    /** 今日对话次数（用于限制每日50条全额积分） */
    val dailyDialogueCount: Int = 0,
    
    /** 上次对话计数重置日期 (YYYY-MM-DD) */
    val lastDialogueResetDate: String = "",
    
    /** 今日是否已首聊（每日首聊奖励） */
    val hasFirstChatToday: Boolean = false,
    
    /** 连续签到天数 */
    val consecutiveCheckInDays: Int = 0,
    
    /** 上次签到日期 (YYYY-MM-DD) */
    val lastCheckInDate: String = "",
    
    /** 本周签到进度 (1-7) */
    val weeklyCheckInProgress: Int = 0,
    
    /** 累计签到天数 */
    val totalCheckInDays: Int = 0,

    /** 是否已兑换 Genesis Token 试用 */
    val genesisTokenRedeemed: Boolean = false
) {
    /**
     * 获取 Tier 加成倍数
     */
    fun getTierMultiplier(): Float = when (currentTier) {
        1 -> 1.0f
        2 -> 1.5f
        3 -> 2.0f
        4 -> 3.0f
        5 -> 5.0f
        else -> 1.0f
    }
    
    /**
     * 获取 Tier 名称
     */
    fun getTierName(): String = when (currentTier) {
        1 -> AppStrings.tr("青铜", "Bronze")
        2 -> AppStrings.tr("白银", "Silver")
        3 -> AppStrings.tr("黄金", "Gold")
        4 -> AppStrings.tr("铂金", "Platinum")
        5 -> AppStrings.tr("钻石", "Diamond")
        else -> AppStrings.tr("未知", "Unknown")
    }
    
    /**
     * 是否已订阅会员
     */
    val isSubscribed: Boolean
        get() = subscriptionType != "FREE" && 
                (subscriptionExpiry == null || subscriptionExpiry > System.currentTimeMillis())
    
    /**
     * 是否需要更新人格分析（超过 7 天）
     */
    fun needsPersonaUpdate(): Boolean {
        if (lastPersonaAnalysis == null) return true
        val daysSinceLastAnalysis = (System.currentTimeMillis() - lastPersonaAnalysis) / (1000 * 60 * 60 * 24)
        return daysSinceLastAnalysis >= 7
    }
    
    /**
     * 计算升级进度（基于积分和 Sovereign Ratio）
     */
    fun calculateTierProgress(): Float {
        val nextTierRequirement = getNextTierRequirement()
        if (nextTierRequirement == null) return 1.0f // 已达最高等级
        
        // 综合考虑积分和 Sovereign Ratio
        val scoreProgress = totalMemoEarned.toFloat() / nextTierRequirement.memoRequired
        val sovereignProgress = sovereignRatio / nextTierRequirement.sovereignRequired
        
        return ((scoreProgress + sovereignProgress) / 2).coerceIn(0f, 1f)
    }
    
    /**
     * 获取下一等级要求（V1 白皮书标准）
     */
    fun getNextTierRequirement(): TierRequirement? = when (currentTier) {
        1 -> TierRequirement(tier = 2, memoRequired = 2500, sovereignRequired = 0.2f)    // Silver
        2 -> TierRequirement(tier = 3, memoRequired = 12000, sovereignRequired = 0.4f)   // Gold
        3 -> TierRequirement(tier = 4, memoRequired = 50000, sovereignRequired = 0.6f)   // Platinum
        4 -> TierRequirement(tier = 5, memoRequired = 200000, sovereignRequired = 0.8f)  // Diamond
        else -> null // 已达最高等级
    }
    
    /**
     * 检查是否因 Sovereign Ratio 不足而锁定等级
     */
    fun isLevelLockedBySovereign(): Boolean {
        val nextReq = getNextTierRequirement() ?: return false
        return totalMemoEarned >= nextReq.memoRequired && sovereignRatio < nextReq.sovereignRequired
    }
}

/**
 * Tier 等级要求
 */
data class TierRequirement(
    val tier: Int,
    val memoRequired: Int,
    val sovereignRequired: Float
)

/**
 * OCEAN 人格数据
 * 
 * 五大人格维度（Big Five Personality Traits）
 */
data class PersonaData(
    /** Openness (开放性) - 0.0 到 1.0 */
    val openness: Float,
    
    /** Conscientiousness (尽责性) - 0.0 到 1.0 */
    val conscientiousness: Float,
    
    /** Extraversion (外向性) - 0.0 到 1.0 */
    val extraversion: Float,
    
    /** Agreeableness (宜人性) - 0.0 到 1.0 */
    val agreeableness: Float,
    
    /** Neuroticism (神经质) - 0.0 到 1.0 */
    val neuroticism: Float,
    
    /** 分析时间 */
    val analyzedAt: Long = System.currentTimeMillis(),
    
    /** 分析样本数量（基于多少条记忆） */
    val sampleSize: Int = 0
) {
    /**
     * 计算人格向量的模长（用于相似度计算）
     */
    fun magnitude(): Float {
        return kotlin.math.sqrt(
            openness * openness +
            conscientiousness * conscientiousness +
            extraversion * extraversion +
            agreeableness * agreeableness +
            neuroticism * neuroticism
        )
    }
    
    /**
     * 计算与另一个人格的相似度（余弦相似度）
     */
    fun similarity(other: PersonaData): Float {
        val dotProduct = openness * other.openness +
                        conscientiousness * other.conscientiousness +
                        extraversion * other.extraversion +
                        agreeableness * other.agreeableness +
                        neuroticism * other.neuroticism
        
        val magnitude1 = magnitude()
        val magnitude2 = other.magnitude()
        
        return if (magnitude1 > 0 && magnitude2 > 0) {
            dotProduct / (magnitude1 * magnitude2)
        } else {
            0f
        }
    }
    
    /**
     * 获取主导人格特质
     * 
     * 返回标准化的英文字符串，UI 层负责本地化显示
     */
    fun getDominantTrait(): Pair<String, Float> {
        val traits = mapOf(
            "Openness" to openness,
            "Conscientiousness" to conscientiousness,
            "Extraversion" to extraversion,
            "Agreeableness" to agreeableness,
            "Neuroticism" to neuroticism
        )
        return traits.maxByOrNull { it.value }?.toPair() ?: ("Unknown" to 0f)
    }
    
    /**
     * 与另一个人格数据合并
     * 
     * 使用样本量加权平均算法
     * 
     * @param other 另一个人格数据
     * @return 合并后的人格数据
     */
    fun mergeWith(other: PersonaData): PersonaData {
        val totalSamples = (this.sampleSize + other.sampleSize).coerceAtLeast(1)
        val weight1 = this.sampleSize.toFloat() / totalSamples
        val weight2 = other.sampleSize.toFloat() / totalSamples
        
        return PersonaData(
            openness = this.openness * weight1 + other.openness * weight2,
            conscientiousness = this.conscientiousness * weight1 + other.conscientiousness * weight2,
            extraversion = this.extraversion * weight1 + other.extraversion * weight2,
            agreeableness = this.agreeableness * weight1 + other.agreeableness * weight2,
            neuroticism = this.neuroticism * weight1 + other.neuroticism * weight2,
            analyzedAt = System.currentTimeMillis(),
            sampleSize = totalSamples
        )
    }
    
    /**
     * 计算与另一个人格的差异度
     * 
     * @param other 另一个人格数据
     * @return 差异度 (0-1)，越小越相似
     */
    fun differenceFrom(other: PersonaData): Float {
        val diff = kotlin.math.abs(openness - other.openness) +
                   kotlin.math.abs(conscientiousness - other.conscientiousness) +
                   kotlin.math.abs(extraversion - other.extraversion) +
                   kotlin.math.abs(agreeableness - other.agreeableness) +
                   kotlin.math.abs(neuroticism - other.neuroticism)
        return diff / 5f  // 归一化到 0-1
    }
}

/**
 * Room TypeConverter for PersonaData
 */
class PersonaDataConverter {
    @TypeConverter
    fun fromPersonaData(data: PersonaData?): String? {
        if (data == null) return null
        return "${data.openness},${data.conscientiousness},${data.extraversion}," +
               "${data.agreeableness},${data.neuroticism},${data.analyzedAt},${data.sampleSize}"
    }
    
    @TypeConverter
    fun toPersonaData(value: String?): PersonaData? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(",")
        if (parts.size < 7) return null
        
        return try {
            PersonaData(
                openness = parts[0].toFloat(),
                conscientiousness = parts[1].toFloat(),
                extraversion = parts[2].toFloat(),
                agreeableness = parts[3].toFloat(),
                neuroticism = parts[4].toFloat(),
                analyzedAt = parts[5].toLong(),
                sampleSize = parts[6].toInt()
            )
        } catch (e: Exception) {
            null
        }
    }
}

class PersonaProfileV2Converter {
    @TypeConverter
    fun fromPersonaProfileV2(data: PersonaProfileV2?): String? {
        if (data == null) return null
        return PersonaProfileV2Json.toJson(data)
    }

    @TypeConverter
    fun toPersonaProfileV2(value: String?): PersonaProfileV2? {
        if (value.isNullOrBlank()) return null
        return PersonaProfileV2Json.fromJson(value)
    }
}
