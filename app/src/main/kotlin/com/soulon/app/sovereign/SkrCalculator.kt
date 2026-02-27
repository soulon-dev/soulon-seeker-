package com.soulon.app.sovereign

import com.soulon.app.rewards.UserProfile
import com.soulon.app.i18n.AppStrings
import kotlin.math.min

/**
 * SKR (Solana Seeker Rewards) 空投计算器
 * 
 * 计算用户预估的 SKR 空投数量
 * 
 * Phase 3 Week 4: Sovereign Certification
 */
object SkrCalculator {
    
    // ======================== 基础配置 ========================
    
    /**
     * 基础 SKR 空投量
     * 
     * 注意：这是估算值，实际空投量由 Solana 官方决定
     */
    private const val BASE_SKR_AMOUNT = 100.0
    
    /**
     * Tier 等级倍数
     */
    private val TIER_MULTIPLIERS = mapOf(
        1 to 1.0,   // Bronze
        2 to 1.5,   // Silver
        3 to 2.5,   // Gold
        4 to 4.0,   // Platinum
        5 to 6.0    // Diamond
    )
    
    // ======================== 核心计算 ========================
    
    /**
     * 计算预估 SKR 数量
     * 
     * 公式：
     * SKR = Base × Tier_Multiplier × Sovereign_Ratio × Activity_Score
     */
    fun calculateEstimatedSkr(userProfile: UserProfile): SkrEstimation {
        // 1. Tier 倍数
        val tierMultiplier = TIER_MULTIPLIERS[userProfile.currentTier] ?: 1.0
        
        // 2. Sovereign Ratio (0.0 - 1.0)
        val sovereignRatio = userProfile.sovereignRatio.toDouble().coerceIn(0.0, 1.0)
        
        // 3. 活跃度评分 (0.0 - 1.0)
        val activityScore = calculateActivityScore(userProfile)
        
        // 4. 计算最终 SKR
        val estimatedSkr = BASE_SKR_AMOUNT * tierMultiplier * sovereignRatio * activityScore
        
        return SkrEstimation(
            estimatedAmount = estimatedSkr,
            tierMultiplier = tierMultiplier,
            sovereignRatio = sovereignRatio,
            activityScore = activityScore,
            breakdown = SkrBreakdown(
                baseSk = BASE_SKR_AMOUNT,
                tierBonus = BASE_SKR_AMOUNT * (tierMultiplier - 1.0),
                sovereignBonus = BASE_SKR_AMOUNT * tierMultiplier * (sovereignRatio - 0.5).coerceAtLeast(0.0),
                activityBonus = BASE_SKR_AMOUNT * tierMultiplier * sovereignRatio * (activityScore - 0.5).coerceAtLeast(0.0)
            )
        )
    }
    
    /**
     * 计算活跃度评分
     * 
     * 基于：
     * - AI 推理次数 (40%)
     * - $MEMO 积分 (30%)
     * - 使用天数 (20%)
     * - 人格同步率 (10%)
     */
    private fun calculateActivityScore(userProfile: UserProfile): Double {
        // 1. AI 推理次数评分 (0-1)
        val inferenceScore = calculateInferenceScore(userProfile.totalInferences)
        
        // 2. $MEMO 积分评分 (0-1)
        val memoScore = calculateMemoScore(userProfile.totalMemoEarned)
        
        // 3. 使用天数评分 (0-1)
        val daysScore = calculateDaysScore(userProfile.createdAt)
        
        // 4. 人格同步率 (0-1)
        val personaScore = (userProfile.personaSyncRate ?: 0f).toDouble()
        
        // 加权平均
        val activityScore = (
            inferenceScore * 0.4 +
            memoScore * 0.3 +
            daysScore * 0.2 +
            personaScore * 0.1
        ).coerceIn(0.0, 1.0)
        
        return activityScore
    }
    
    /**
     * 推理次数评分
     */
    private fun calculateInferenceScore(totalInferences: Int): Double {
        return when {
            totalInferences >= 1000 -> 1.0
            totalInferences >= 500 -> 0.9
            totalInferences >= 200 -> 0.8
            totalInferences >= 100 -> 0.7
            totalInferences >= 50 -> 0.6
            totalInferences >= 20 -> 0.5
            totalInferences >= 10 -> 0.4
            totalInferences >= 5 -> 0.3
            else -> 0.2
        }
    }
    
    /**
     * $MEMO 积分评分
     */
    private fun calculateMemoScore(totalMemo: Int): Double {
        return when {
            totalMemo >= 100000 -> 1.0
            totalMemo >= 50000 -> 0.9
            totalMemo >= 20000 -> 0.8
            totalMemo >= 10000 -> 0.7
            totalMemo >= 5000 -> 0.6
            totalMemo >= 2000 -> 0.5
            totalMemo >= 1000 -> 0.4
            totalMemo >= 500 -> 0.3
            else -> 0.2
        }
    }
    
    /**
     * 使用天数评分
     */
    private fun calculateDaysScore(createdAt: Long): Double {
        val daysUsed = (System.currentTimeMillis() - createdAt) / (1000 * 60 * 60 * 24)
        return when {
            daysUsed >= 90 -> 1.0
            daysUsed >= 60 -> 0.9
            daysUsed >= 30 -> 0.8
            daysUsed >= 14 -> 0.7
            daysUsed >= 7 -> 0.6
            daysUsed >= 3 -> 0.5
            daysUsed >= 1 -> 0.4
            else -> 0.3
        }
    }
    
    // ======================== 升级建议 ========================
    
    /**
     * 计算升级后的 SKR
     */
    fun calculateUpgradeSkr(
        currentProfile: UserProfile,
        targetTier: Int
    ): SkrUpgradeComparison {
        val currentEstimation = calculateEstimatedSkr(currentProfile)
        
        // 模拟升级后的档案
        val upgradedProfile = currentProfile.copy(currentTier = targetTier)
        val upgradedEstimation = calculateEstimatedSkr(upgradedProfile)
        
        val increase = upgradedEstimation.estimatedAmount - currentEstimation.estimatedAmount
        val increasePercent = if (currentEstimation.estimatedAmount > 0) {
            (increase / currentEstimation.estimatedAmount) * 100
        } else {
            0.0
        }
        
        return SkrUpgradeComparison(
            currentTier = currentProfile.currentTier,
            targetTier = targetTier,
            currentSkr = currentEstimation.estimatedAmount,
            upgradedSkr = upgradedEstimation.estimatedAmount,
            increase = increase,
            increasePercent = increasePercent
        )
    }
    
    /**
     * 获取升级建议
     */
    fun getUpgradeSuggestions(userProfile: UserProfile): List<UpgradeSuggestion> {
        val suggestions = mutableListOf<UpgradeSuggestion>()
        
        val nextTierRequirement = userProfile.getNextTierRequirement()
        
        if (nextTierRequirement != null) {
            val memoNeeded = (nextTierRequirement.memoRequired - userProfile.totalMemoEarned).coerceAtLeast(0)
            val sovereignNeeded = (nextTierRequirement.sovereignRequired - userProfile.sovereignRatio).coerceAtLeast(0f)
            
            if (memoNeeded > 0) {
                suggestions.add(
                    UpgradeSuggestion(
                        type = SuggestionType.EARN_MEMO,
                        title = AppStrings.tr("赚取更多 \$MEMO", "Earn more \$MEMO"),
                        description = AppStrings.trf(
                            "还需要 %d \$MEMO 才能升级到 Tier %d",
                            "Need %d \$MEMO to reach Tier %d",
                            memoNeeded,
                            nextTierRequirement.tier
                        ),
                        progress = userProfile.totalMemoEarned.toFloat() / nextTierRequirement.memoRequired,
                        target = nextTierRequirement.memoRequired,
                        current = userProfile.totalMemoEarned
                    )
                )
            }
            
            if (sovereignNeeded > 0) {
                suggestions.add(
                    UpgradeSuggestion(
                        type = SuggestionType.INCREASE_SOVEREIGN,
                        title = AppStrings.tr("提升 Sovereign 比例", "Increase Sovereign ratio"),
                        description = AppStrings.trf(
                            "还需要 %d%% 才能升级到 Tier %d",
                            "Need %d%% more to reach Tier %d",
                            (sovereignNeeded * 100).toInt(),
                            nextTierRequirement.tier
                        ),
                        progress = userProfile.sovereignRatio / nextTierRequirement.sovereignRequired,
                        target = nextTierRequirement.sovereignRequired.toInt(),
                        current = (userProfile.sovereignRatio * 100).toInt()
                    )
                )
            }
        }
        
        // 活跃度建议
        if (userProfile.totalInferences < 100) {
            suggestions.add(
                UpgradeSuggestion(
                    type = SuggestionType.INCREASE_ACTIVITY,
                    title = AppStrings.tr("增加 AI 推理次数", "Increase AI inferences"),
                    description = AppStrings.tr(
                        "多使用 AI 对话功能可以提升活跃度评分",
                        "Use AI chat more to improve your activity score"
                    ),
                    progress = userProfile.totalInferences / 100f,
                    target = 100,
                    current = userProfile.totalInferences
                )
            )
        }
        
        return suggestions
    }
}

/**
 * SKR 估算结果
 */
data class SkrEstimation(
    val estimatedAmount: Double,
    val tierMultiplier: Double,
    val sovereignRatio: Double,
    val activityScore: Double,
    val breakdown: SkrBreakdown
) {
    /**
     * 获取四舍五入的整数 SKR
     */
    fun getRoundedAmount(): Int = estimatedAmount.toInt()
    
    /**
     * 获取格式化的 SKR 字符串
     */
    fun getFormattedAmount(): String = String.format("%.1f SKR", estimatedAmount)
}

/**
 * SKR 明细
 */
data class SkrBreakdown(
    val baseSk: Double,
    val tierBonus: Double,
    val sovereignBonus: Double,
    val activityBonus: Double
)

/**
 * SKR 升级对比
 */
data class SkrUpgradeComparison(
    val currentTier: Int,
    val targetTier: Int,
    val currentSkr: Double,
    val upgradedSkr: Double,
    val increase: Double,
    val increasePercent: Double
) {
    fun getIncreaseText(): String = "+${increase.toInt()} SKR (+${increasePercent.toInt()}%)"
}

/**
 * 升级建议
 */
data class UpgradeSuggestion(
    val type: SuggestionType,
    val title: String,
    val description: String,
    val progress: Float,
    val target: Int,
    val current: Int
)

/**
 * 建议类型
 */
enum class SuggestionType {
    EARN_MEMO,
    INCREASE_SOVEREIGN,
    INCREASE_ACTIVITY
}
