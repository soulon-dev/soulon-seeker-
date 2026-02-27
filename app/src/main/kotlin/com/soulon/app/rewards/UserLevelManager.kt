package com.soulon.app.rewards

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 用户等级管理器
 * 
 * 功能：
 * - 自动计算和更新用户 Tier 等级
 * - 基于积分和 Sovereign Ratio
 * - Tier 1-5 升级逻辑
 * - 等级加成管理
 * 
 * Phase 3 Week 2: Task_Tier_System
 */
class UserLevelManager(private val context: Context) {
    
    private val database = RewardsDatabase.getInstance(context)
    private val dao = database.rewardsDao()
    
    companion object {
        // Tier 等级定义
        data class TierLevel(
            val tier: Int,
            val name: String,
            val memoRequired: Int,
            val sovereignRequired: Float,
            val multiplier: Float,
            val color: Long // ARGB 颜色
        )
        
        /**
         * V1 白皮书等级定义
         * 
         * | 等级     | 积分要求  | Sovereign Ratio | 综合倍数 | 预期时间      |
         * |----------|----------|-----------------|----------|---------------|
         * | Bronze   | 0        | 0%              | 1.0x     | 注册即领       |
         * | Silver   | 2,500    | 20%             | 1.5x     | 2-3 周        |
         * | Gold     | 12,000   | 40%             | 2.0x     | 1.5-2 个月    |
         * | Platinum | 50,000   | 60%             | 3.0x     | 3 个月(瓶颈期) |
         * | Diamond  | 200,000  | 80%             | 5.0x     | 长期/极少数    |
         */
        val TIER_LEVELS = listOf(
            TierLevel(1, "Bronze", 0, 0.0f, 1.0f, 0xFFCD7F32),
            TierLevel(2, "Silver", 2500, 0.2f, 1.5f, 0xFFC0C0C0),
            TierLevel(3, "Gold", 12000, 0.4f, 2.0f, 0xFFFFD700),
            TierLevel(4, "Platinum", 50000, 0.6f, 3.0f, 0xFFE5E4E2),
            TierLevel(5, "Diamond", 200000, 0.8f, 5.0f, 0xFFB9F2FF)
        )
        
        private const val DEFAULT_USER_ID = "default_user"
    }
    
    /**
     * 检查并更新用户等级
     * 
     * @return 是否升级了
     */
    suspend fun checkAndUpdateTier(): TierUpdateResult = withContext(Dispatchers.IO) {
        val profile = dao.getUserProfile(DEFAULT_USER_ID) ?: run {
            Timber.w("用户档案不存在，无法更新等级")
            return@withContext TierUpdateResult(false, 1, 1, null)
        }
        
        // 获取可信度系数
        val reliabilityMultiplier = try {
            val evaluationManager = com.soulon.app.onboarding.OnboardingEvaluationManager(context)
            evaluationManager.getReliabilityMultiplier()
        } catch (e: Exception) {
            Timber.w(e, "获取可信度系数失败，使用默认值 1.0")
            1.0f
        }
        
        val currentTier = profile.currentTier
        val newTier = calculateTier(profile.totalMemoEarned, profile.sovereignRatio, reliabilityMultiplier)
        
        if (newTier > currentTier) {
            // 升级！
            dao.updateTier(DEFAULT_USER_ID, newTier)
            
            val oldLevel = TIER_LEVELS.find { it.tier == currentTier }
            val newLevel = TIER_LEVELS.find { it.tier == newTier }
            
            Timber.i("🎉 用户升级！ ${oldLevel?.name} (Tier $currentTier) → ${newLevel?.name} (Tier $newTier)")
            Timber.i("  • 新的积分倍数: ${newLevel?.multiplier}x")
            
            return@withContext TierUpdateResult(
                upgraded = true,
                oldTier = currentTier,
                newTier = newTier,
                newLevel = newLevel
            )
        } else if (newTier < currentTier) {
            // 降级（理论上不应该发生，除非数据异常）
            dao.updateTier(DEFAULT_USER_ID, newTier)
            
            Timber.w("⚠️ 用户降级：Tier $currentTier → Tier $newTier")
            
            return@withContext TierUpdateResult(
                upgraded = false,
                oldTier = currentTier,
                newTier = newTier,
                newLevel = TIER_LEVELS.find { it.tier == newTier }
            )
        } else {
            // 无变化
            return@withContext TierUpdateResult(false, currentTier, currentTier, null)
        }
    }
    
    /**
     * 计算应有的 Tier 等级
     * 
     * 规则：
     * - 必须同时满足积分和 Sovereign Ratio 要求
     * - 可信度系数影响积分要求
     * - 取两者较小的等级
     */
    private fun calculateTier(totalMemo: Int, sovereignRatio: Float, reliabilityMultiplier: Float = 1.0f): Int {
        var tierByMemo = 1
        var tierBySovereign = 1
        
        // 根据积分计算 Tier（应用可信度系数）
        for (level in TIER_LEVELS.sortedByDescending { it.tier }) {
            // 调整后的要求 = 基础要求 / 可信度系数
            val adjustedRequirement = level.memoRequired / reliabilityMultiplier
            
            if (totalMemo >= adjustedRequirement) {
                tierByMemo = level.tier
                Timber.d("  积分 Tier: $totalMemo >= ${adjustedRequirement.toInt()} (原始: ${level.memoRequired}, 系数: $reliabilityMultiplier)")
                break
            }
        }
        
        // 根据 Sovereign Ratio 计算 Tier
        for (level in TIER_LEVELS.sortedByDescending { it.tier }) {
            if (sovereignRatio >= level.sovereignRequired) {
                tierBySovereign = level.tier
                break
            }
        }
        
        // 取较小值（必须都达标）
        val finalTier = minOf(tierByMemo, tierBySovereign)
        
        Timber.d("计算 Tier: 积分=$tierByMemo, Sovereign=$tierBySovereign, 可信度系数=$reliabilityMultiplier, 最终=$finalTier")
        
        return finalTier
    }
    
    /**
     * 获取升级进度
     */
    suspend fun getTierProgress(): TierProgress = withContext(Dispatchers.IO) {
        val profile = dao.getUserProfile(DEFAULT_USER_ID) ?: return@withContext TierProgress(
            currentTier = 1,
            nextTier = 2,
            progressPercent = 0f,
            memoProgress = 0f,
            sovereignProgress = 0f,
            memoNeeded = 2500,  // V1 Silver 要求
            sovereignNeeded = 0.2f,
            isLockedBySovereign = false
        )
        
        val currentTier = profile.currentTier
        val nextTierLevel = TIER_LEVELS.find { it.tier == currentTier + 1 }
        
        if (nextTierLevel == null) {
            // 已达最高等级
            return@withContext TierProgress(
                currentTier = currentTier,
                nextTier = currentTier,
                progressPercent = 1.0f,
                memoProgress = 1.0f,
                sovereignProgress = 1.0f,
                memoNeeded = 0,
                sovereignNeeded = 0f,
                isLockedBySovereign = false
            )
        }
        
        // 计算进度
        val memoProgress = (profile.totalMemoEarned.toFloat() / nextTierLevel.memoRequired).coerceAtMost(1.0f)
        val sovereignProgress = (profile.sovereignRatio / nextTierLevel.sovereignRequired).coerceAtMost(1.0f)
        
        // 总进度取两者较小值
        val overallProgress = minOf(memoProgress, sovereignProgress)
        
        // 检查是否因 Sovereign Ratio 不足而锁定
        val isLockedBySovereign = profile.totalMemoEarned >= nextTierLevel.memoRequired && 
            profile.sovereignRatio < nextTierLevel.sovereignRequired
        
        return@withContext TierProgress(
            currentTier = currentTier,
            nextTier = nextTierLevel.tier,
            progressPercent = overallProgress,
            memoProgress = memoProgress,
            sovereignProgress = sovereignProgress,
            memoNeeded = nextTierLevel.memoRequired - profile.totalMemoEarned,
            sovereignNeeded = nextTierLevel.sovereignRequired - profile.sovereignRatio,
            isLockedBySovereign = isLockedBySovereign
        )
    }
    
    /**
     * 获取等级信息
     */
    fun getTierInfo(tier: Int): TierLevel? {
        return TIER_LEVELS.find { it.tier == tier }
    }
    
    /**
     * 获取所有等级列表
     */
    fun getAllTierLevels(): List<TierLevel> = TIER_LEVELS
}

/**
 * Tier 更新结果
 */
data class TierUpdateResult(
    val upgraded: Boolean,
    val oldTier: Int,
    val newTier: Int,
    val newLevel: UserLevelManager.Companion.TierLevel?
)

/**
 * Tier 进度
 */
data class TierProgress(
    val currentTier: Int,
    val nextTier: Int,
    val progressPercent: Float,
    val memoProgress: Float,
    val sovereignProgress: Float,
    val memoNeeded: Int,
    val sovereignNeeded: Float,
    /** 是否因 Sovereign Ratio 不足而锁定等级 */
    val isLockedBySovereign: Boolean = false
)
