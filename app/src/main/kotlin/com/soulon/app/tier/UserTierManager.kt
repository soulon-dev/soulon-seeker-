package com.soulon.app.tier

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.data.RealTimeBalanceResult
import com.soulon.app.i18n.AppStrings
import com.soulon.app.quota.TokenQuotaManager
import com.soulon.app.rewards.RewardsRepository
import com.soulon.app.rewards.UserProfile
import com.soulon.app.wallet.WalletScope
import timber.log.Timber

/**
 * 用户级别管理器
 * 
 * 管理五级用户权益系统：
 * 1. 普通用户（Free）：基础功能
 * 2. 订阅用户（Subscriber）：月付 SOL/USDC
 * 3. 质押用户（Staker）：锁定 SKR 代币
 * 4. 创始人用户（Founder）：日均质押超 10 万 USDC 价值
 * 5. 技术专家用户（Expert）：特殊贡献者
 * 
 * 注意：这是独立于会员等级（Member Tier）的系统
 * 用户级别影响：Token 限额、积分累积速度、功能解锁
 * 会员等级影响：空投、NFT、实物奖励
 */
class UserTierManager(
    private val context: Context,
    private val rewardsRepository: RewardsRepository
) {
    
    private val prefs: SharedPreferences
        get() = WalletScope.scopedPrefs(context, "user_tier")
    
    companion object {
        // 质押最低要求（lamports，约 0.1 SOL）
        const val MIN_STAKE_AMOUNT = 100_000_000L
        
        // 创始人用户最低日均质押（USDC 价值，单位：美分）
        const val FOUNDER_MIN_DAILY_STAKE = 100_000_00L  // 10万 USDC
        
        // SharedPreferences 键
        private const val KEY_USER_LEVEL = "user_level"
        private const val KEY_SUBSCRIPTION_EXPIRY = "subscription_expiry"
        private const val KEY_STAKED_AMOUNT = "staked_amount"
        private const val KEY_STAKING_START_TIME = "staking_start_time"
        private const val KEY_IS_FOUNDER = "is_founder"
        private const val KEY_IS_EXPERT = "is_expert"
        private const val KEY_FOUNDER_SINCE = "founder_since"
        private const val KEY_EXPERT_SINCE = "expert_since"
    }
    
    /**
     * 用户级别枚举
     */
    enum class UserLevel(
        val priority: Int,           // 优先级（数字越大越高）
        val displayName: String,     // 显示名称
        val monthlyTokenLimit: Long, // 每月 Token 限额
        val memoMultiplier: Float,   // 积分倍率
        val color: Long              // 主题色
    ) {
        FREE(
            priority = 1,
            displayName = "普通用户",
            monthlyTokenLimit = 1_000_000L,     // 100万/月
            memoMultiplier = 1.0f,
            color = 0xFF9E9E9E  // 灰色
        ),
        SUBSCRIBER(
            priority = 2,
            displayName = "订阅用户",
            monthlyTokenLimit = 5_000_000L,     // 500万/月
            memoMultiplier = 2.0f,
            color = 0xFF2196F3  // 蓝色
        ),
        STAKER(
            priority = 3,
            displayName = "质押用户",
            monthlyTokenLimit = 20_000_000L,    // 2000万/月
            memoMultiplier = 3.0f,
            color = 0xFF9C27B0  // 紫色
        ),
        FOUNDER(
            priority = 4,
            displayName = "创始人用户",
            monthlyTokenLimit = Long.MAX_VALUE, // 无限制
            memoMultiplier = 5.0f,
            color = 0xFFFFD700  // 金色
        ),
        EXPERT(
            priority = 5,
            displayName = "技术专家用户",
            monthlyTokenLimit = Long.MAX_VALUE, // 无限制
            memoMultiplier = 5.0f,
            color = 0xFFE91E63  // 粉色
        )
    }

    fun getLevelDisplayName(level: UserLevel): String {
        return when (level) {
            UserLevel.FREE -> AppStrings.tr("普通用户", "Free")
            UserLevel.SUBSCRIBER -> AppStrings.tr("订阅用户", "Subscriber")
            UserLevel.STAKER -> AppStrings.tr("质押用户", "Staker")
            UserLevel.FOUNDER -> AppStrings.tr("创始人用户", "Founder")
            UserLevel.EXPERT -> AppStrings.tr("技术专家用户", "Expert")
        }
    }
    
    /**
     * 用户权益数据类
     */
    data class TierBenefits(
        val monthlyTokenLimit: Long,         // 每月 Token 限额
        val memoMultiplier: Float,           // 积分倍率
        val priorityAccess: Boolean,         // 生态优先准入
        val advancedFeatures: Boolean,       // 高级功能解锁
        val tokenRewards: Boolean = false,   // 项目代币奖励
        val votingRights: Boolean = false,   // 投票权
        val proposalRights: Boolean = false, // 提案权
        val founderLottery: Boolean = false  // 基石投资抽签
    )
    
    /**
     * 用户级别详情
     */
    data class UserLevelInfo(
        val level: UserLevel,
        val benefits: TierBenefits,
        val subscriptionExpiry: Long?,      // 订阅到期时间
        val stakedAmount: Long,             // 质押数量
        val stakingDuration: Long,          // 质押时长（毫秒）
        val isFounderEligible: Boolean,     // 是否满足创始人条件
        val isExpert: Boolean               // 是否技术专家
    )
    
    /**
     * 计算用户当前有效级别
     * 
     * 优先使用后端实时数据 (backendResult)
     * 其次使用后端同步的用户档案 (profile)
     * 最后使用本地缓存
     */
    suspend fun calculateEffectiveLevel(
        backendResult: RealTimeBalanceResult?
    ): UserLevel {
        // 强制使用后端实时数据
        // 如果后端数据为空（例如网络错误），则默认为 FREE
        // 不再回退到本地缓存
        
        if (backendResult != null) {
            // 直接根据后端返回的 currentTier 判断（后端计算最权威）
            // 订阅判断
            val subscriptionType = backendResult.subscriptionType
            val subscriptionExpiry = backendResult.subscriptionExpiry ?: 0L
            
            if (subscriptionType != "FREE" || (subscriptionExpiry > System.currentTimeMillis())) {
                // 如果后端返回了订阅类型或未过期，至少是订阅用户
                return when (backendResult.currentTier) {
                    5 -> UserLevel.EXPERT
                    4 -> UserLevel.FOUNDER
                    3 -> UserLevel.STAKER
                    else -> UserLevel.SUBSCRIBER // 至少是订阅用户
                }
            }
            
            // 如果后端显示为高级别但不是订阅用户（例如纯质押用户），也直接返回后端级别
            if (backendResult.currentTier > 1) {
                return when (backendResult.currentTier) {
                    5 -> UserLevel.EXPERT
                    4 -> UserLevel.FOUNDER
                    3 -> UserLevel.STAKER
                    2 -> UserLevel.SUBSCRIBER
                    else -> UserLevel.FREE
                }
            }
        } else {
            Timber.e("无法获取后端数据，且强制不使用本地缓存，默认为普通用户")
        }
        
        return UserLevel.FREE
    }
    
    /**
     * 获取用户级别权益
     */
    fun getTierBenefits(level: UserLevel): TierBenefits {
        return when (level) {
            UserLevel.FREE -> TierBenefits(
                monthlyTokenLimit = level.monthlyTokenLimit,
                memoMultiplier = level.memoMultiplier,
                priorityAccess = false,
                advancedFeatures = false
            )
            UserLevel.SUBSCRIBER -> TierBenefits(
                monthlyTokenLimit = level.monthlyTokenLimit,
                memoMultiplier = level.memoMultiplier,
                priorityAccess = true,
                advancedFeatures = true
            )
            UserLevel.STAKER -> TierBenefits(
                monthlyTokenLimit = level.monthlyTokenLimit,
                memoMultiplier = level.memoMultiplier,
                priorityAccess = true,
                advancedFeatures = true,
                tokenRewards = true
            )
            UserLevel.FOUNDER -> TierBenefits(
                monthlyTokenLimit = level.monthlyTokenLimit,
                memoMultiplier = level.memoMultiplier,
                priorityAccess = true,
                advancedFeatures = true,
                tokenRewards = true,
                votingRights = true,
                proposalRights = true,
                founderLottery = true
            )
            UserLevel.EXPERT -> TierBenefits(
                monthlyTokenLimit = level.monthlyTokenLimit,
                memoMultiplier = level.memoMultiplier,
                priorityAccess = true,
                advancedFeatures = true,
                tokenRewards = true
            )
        }
    }
    
    /**
     * 获取完整的用户级别信息
     * 
     * 优先使用后端同步的数据
     */
    suspend fun getUserLevelInfo(): UserLevelInfo {
        // 1. 强制从后端获取最新实时数据
        val backendResult = try {
            rewardsRepository.refreshFromBackend().getOrNull()
        } catch (e: Exception) {
            Timber.e(e, "获取后端实时数据失败")
            null
        }
        
        // 2. 仅根据后端数据计算有效等级
        val level = calculateEffectiveLevel(backendResult)
        val benefits = getTierBenefits(level)
        
        // 3. 直接使用后端数据填充详情，如果后端数据为空则返回默认空值
        val subscriptionExpiry = backendResult?.subscriptionExpiry 
            ?: 0L
            
        // 目前后端 RealTimeBalanceResult 不包含 stakedAmount，暂定为 0
        // 如果后端后续补充了该字段，直接从 backendResult 获取
        val stakedAmount = 0L 
        
        // 暂无后端字段，设为 0
        val stakingDuration = 0L
        
        return UserLevelInfo(
            level = level,
            benefits = benefits,
            subscriptionExpiry = if (subscriptionExpiry > 0) subscriptionExpiry else null,
            stakedAmount = stakedAmount,
            stakingDuration = stakingDuration,
            // 仅当后端确认是创始人/专家时才为 true
            isFounderEligible = level == UserLevel.FOUNDER,
            isExpert = level == UserLevel.EXPERT
        )
    }
    
    /**
     * 转换为 TokenQuotaManager 的 UserLevelType
     */
    fun toTokenQuotaLevel(level: UserLevel): TokenQuotaManager.UserLevelType {
        return when (level) {
            UserLevel.FREE -> TokenQuotaManager.UserLevelType.FREE
            UserLevel.SUBSCRIBER -> TokenQuotaManager.UserLevelType.SUBSCRIBER
            UserLevel.STAKER -> TokenQuotaManager.UserLevelType.STAKER
            UserLevel.FOUNDER -> TokenQuotaManager.UserLevelType.FOUNDER
            UserLevel.EXPERT -> TokenQuotaManager.UserLevelType.EXPERT
        }
    }
    
    // ========== 订阅管理 ==========
    
    /**
     * 设置订阅到期时间
     */
    fun setSubscriptionExpiry(expiryTime: Long) {
        prefs.edit().putLong(KEY_SUBSCRIPTION_EXPIRY, expiryTime).apply()
        Timber.i("📅 订阅到期时间已设置: ${java.util.Date(expiryTime)}")
    }
    
    /**
     * 检查订阅是否有效
     */
    fun isSubscriptionActive(): Boolean {
        val expiry = prefs.getLong(KEY_SUBSCRIPTION_EXPIRY, 0)
        return expiry > System.currentTimeMillis()
    }
    
    /**
     * 获取订阅剩余天数
     */
    fun getSubscriptionDaysRemaining(): Int {
        val expiry = prefs.getLong(KEY_SUBSCRIPTION_EXPIRY, 0)
        if (expiry <= System.currentTimeMillis()) return 0
        
        val remainingMs = expiry - System.currentTimeMillis()
        return (remainingMs / (24 * 60 * 60 * 1000)).toInt()
    }
    
    // ========== 质押管理 ==========
    
    /**
     * 记录质押
     */
    fun recordStaking(amount: Long) {
        val currentAmount = prefs.getLong(KEY_STAKED_AMOUNT, 0)
        val newAmount = currentAmount + amount
        
        prefs.edit()
            .putLong(KEY_STAKED_AMOUNT, newAmount)
            .putLong(KEY_STAKING_START_TIME, System.currentTimeMillis())
            .apply()
        
        Timber.i("💎 质押记录: +$amount lamports, 总计: $newAmount lamports")
    }
    
    /**
     * 记录取消质押
     */
    fun recordUnstaking(amount: Long) {
        val currentAmount = prefs.getLong(KEY_STAKED_AMOUNT, 0)
        val newAmount = maxOf(0, currentAmount - amount)
        
        prefs.edit()
            .putLong(KEY_STAKED_AMOUNT, newAmount)
            .apply()
        
        if (newAmount == 0L) {
            prefs.edit().remove(KEY_STAKING_START_TIME).apply()
        }
        
        Timber.i("💎 取消质押: -$amount lamports, 剩余: $newAmount lamports")
    }
    
    /**
     * 获取质押数量
     */
    fun getStakedAmount(): Long {
        return prefs.getLong(KEY_STAKED_AMOUNT, 0)
    }
    
    // ========== 创始人管理 ==========
    
    /**
     * 检查是否满足创始人条件
     * 
     * 条件：日均质押超过 10 万 USDC 价值
     * 注意：实际实现需要连接链上数据
     */
    private fun isFounderEligible(): Boolean {
        return prefs.getBoolean(KEY_IS_FOUNDER, false)
    }
    
    /**
     * 设置创始人状态（由管理员或智能合约调用）
     */
    fun setFounderStatus(isFounder: Boolean) {
        prefs.edit()
            .putBoolean(KEY_IS_FOUNDER, isFounder)
            .putLong(KEY_FOUNDER_SINCE, if (isFounder) System.currentTimeMillis() else 0)
            .apply()
        
        Timber.i("👑 创始人状态已设置: $isFounder")
    }
    
    // ========== 技术专家管理 ==========
    
    /**
     * 设置技术专家状态（由管理员授予）
     */
    fun setExpertStatus(isExpert: Boolean) {
        prefs.edit()
            .putBoolean(KEY_IS_EXPERT, isExpert)
            .putLong(KEY_EXPERT_SINCE, if (isExpert) System.currentTimeMillis() else 0)
            .apply()
        
        Timber.i("🔧 技术专家状态已设置: $isExpert")
    }
    
    /**
     * 检查是否技术专家
     */
    fun isExpert(): Boolean {
        return prefs.getBoolean(KEY_IS_EXPERT, false)
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 清除所有状态（用于测试）
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
    
    /**
     * 获取用户级别颜色
     */
    fun getLevelColor(level: UserLevel): Long {
        return level.color
    }
    
    /**
     * 获取用户级别图标
     */
    fun getLevelIcon(level: UserLevel): String {
        return when (level) {
            UserLevel.FREE -> "👤"
            UserLevel.SUBSCRIBER -> "⭐"
            UserLevel.STAKER -> "💎"
            UserLevel.FOUNDER -> "👑"
            UserLevel.EXPERT -> "🔧"
        }
    }
}
