package com.soulon.app.tier

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.i18n.AppStrings
import com.soulon.app.rewards.RewardsDatabase
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 会员等级管理器
 * 
 * 独立于用户级别 (UserLevel) 的会员等级系统
 * 
 * 会员等级 (Member Tier) - 5级系统：
 * - 影响项目奖励：空投、NFT、实物奖励
 * - 通过累积会员积分升级
 * - 用户级别影响积分累积速度
 * 
 * 用户级别 (User Level) - 5级系统：
 * - 普通用户、订阅用户、质押用户、创始人用户、技术专家用户
 * - 影响 Token 限额、$MEMO 积分倍率
 * 
 * 两个系统相互独立，但 UserLevel 影响 MemberTier 的积分累积速度
 */
class MemberTierManager(private val context: Context) {
    
    private val prefs: SharedPreferences
        get() = WalletScope.scopedPrefs(context, "member_tier")
    private val database = RewardsDatabase.getInstance(context)
    private val rewardsRepository = com.soulon.app.rewards.RewardsRepository(context)
    
    companion object {
        private const val TAG = "MemberTier"
        
        // SharedPreferences 键
        private const val KEY_MEMBER_POINTS = "member_points"
        private const val KEY_MEMBER_TIER = "member_tier"
        private const val KEY_LAST_POINTS_UPDATE = "last_points_update"
        private const val KEY_TOTAL_AIRDROPS = "total_airdrops"
        private const val KEY_TOTAL_NFTS = "total_nfts"
        private const val KEY_TIER_SINCE = "tier_since"
        
        // 日活跃积分上限
        private const val DAILY_ACTIVITY_POINTS_CAP = 100
        
        // 连续登录加成天数上限
        private const val MAX_STREAK_BONUS_DAYS = 30
    }
    
    /**
     * 会员等级枚举
     * 
     * 每个等级需要的累计积分和对应奖励
     */
    enum class MemberTier(
        val level: Int,
        val displayName: String,
        val displayNameCn: String,
        val pointsRequired: Long,
        val airdropMultiplier: Float,  // 空投倍数
        val nftDropRate: Float,        // NFT 掉落率加成
        val physicalRewardEligible: Boolean,  // 实物奖励资格
        val exclusiveEvents: Boolean,  // 专属活动准入
        val colorHex: Long,
        val iconEmoji: String
    ) {
        /**
         * V1 白皮书等级门槛：
         * - Bronze: 0 积分
         * - Silver: 2,500 积分
         * - Gold: 12,000 积分
         * - Platinum: 50,000 积分
         * - Diamond: 200,000 积分
         */
        BRONZE(
            level = 1,
            displayName = "Bronze",
            displayNameCn = "青铜会员",
            pointsRequired = 0,
            airdropMultiplier = 1.0f,
            nftDropRate = 0.01f,
            physicalRewardEligible = false,
            exclusiveEvents = false,
            colorHex = 0xFFCD7F32,  // 铜色
            iconEmoji = "🥉"
        ),
        SILVER(
            level = 2,
            displayName = "Silver",
            displayNameCn = "白银会员",
            pointsRequired = 2500,
            airdropMultiplier = 1.5f,
            nftDropRate = 0.02f,
            physicalRewardEligible = false,
            exclusiveEvents = false,
            colorHex = 0xFFC0C0C0,  // 银色
            iconEmoji = "🥈"
        ),
        GOLD(
            level = 3,
            displayName = "Gold",
            displayNameCn = "黄金会员",
            pointsRequired = 12000,
            airdropMultiplier = 2.0f,
            nftDropRate = 0.05f,
            physicalRewardEligible = true,
            exclusiveEvents = false,
            colorHex = 0xFFFFD700,  // 金色
            iconEmoji = "🥇"
        ),
        PLATINUM(
            level = 4,
            displayName = "Platinum",
            displayNameCn = "铂金会员",
            pointsRequired = 50000,
            airdropMultiplier = 3.0f,
            nftDropRate = 0.10f,
            physicalRewardEligible = true,
            exclusiveEvents = true,
            colorHex = 0xFFE5E4E2,  // 铂金色
            iconEmoji = "💫"
        ),
        DIAMOND(
            level = 5,
            displayName = "Diamond",
            displayNameCn = "钻石会员",
            pointsRequired = 200000,
            airdropMultiplier = 5.0f,
            nftDropRate = 0.20f,
            physicalRewardEligible = true,
            exclusiveEvents = true,
            colorHex = 0xFFB9F2FF,  // 钻石蓝
            iconEmoji = "💎"
        );

        fun localizedName(): String {
            return AppStrings.tr(displayNameCn, "$displayName member")
        }
        
        companion object {
            /**
             * 根据积分获取会员等级
             */
            fun fromPoints(points: Long): MemberTier {
                return values()
                    .filter { points >= it.pointsRequired }
                    .maxByOrNull { it.pointsRequired }
                    ?: BRONZE
            }
            
            /**
             * 获取下一等级
             */
            fun getNextTier(current: MemberTier): MemberTier? {
                val currentIndex = values().indexOf(current)
                return if (currentIndex < values().size - 1) {
                    values()[currentIndex + 1]
                } else null
            }
        }
    }
    
    /**
     * 会员积分来源类型
     */
    enum class PointSource(
        val basePoints: Int,
        val displayNameZh: String,
        val displayNameEn: String
    ) {
        DAILY_LOGIN(10, "每日登录", "Daily login"),
        AI_CONVERSATION(5, "AI 对话", "AI conversation"),
        MEMORY_UPLOAD(20, "记忆上传", "Memory upload"),
        PERSONA_UPDATE(30, "人格更新", "Persona update"),
        REFERRAL(100, "邀请好友", "Referral"),
        QUESTIONNAIRE(50, "完成问卷", "Questionnaire"),
        FEEDBACK(25, "提供反馈", "Feedback"),
        STREAK_BONUS(5, "连续登录加成", "Streak bonus"),
        SPECIAL_EVENT(0, "特殊活动", "Special event"),
        ACHIEVEMENT(0, "成就解锁", "Achievement")
        ;

        fun localizedName(): String {
            return AppStrings.tr(displayNameZh, displayNameEn)
        }
    }
    
    /**
     * 会员信息数据类
     */
    data class MemberInfo(
        val tier: MemberTier,
        val totalPoints: Long,           // 用于显示的当前余额（与积分记录页面一致）
        val totalEarnedPoints: Long,     // 累计获取积分（用于等级计算）
        val pointsToNextTier: Long,
        val progressPercent: Float,
        val nextTier: MemberTier?,
        val tierSince: Long,
        val benefits: MemberBenefits,
        val stats: MemberStats
    )
    
    /**
     * 会员权益
     */
    data class MemberBenefits(
        val airdropMultiplier: Float,
        val nftDropRate: Float,
        val physicalRewardEligible: Boolean,
        val exclusiveEvents: Boolean,
        val exclusiveBadge: String,
        val prioritySupport: Boolean
    )
    
    /**
     * 会员统计
     */
    data class MemberStats(
        val totalAirdropsReceived: Int,
        val totalNftsReceived: Int,
        val daysAsMember: Int,
        val currentStreak: Int
    )
    
    /**
     * 积分记录
     */
    data class PointsRecord(
        val source: PointSource,
        val points: Int,
        val multiplier: Float,
        val finalPoints: Int,
        val timestamp: Long,
        val description: String
    )
    
    // ========== 核心方法 ==========
    
    /**
     * 获取当前会员信息
     * 
     * 优先从后端同步的数据中读取，然后使用本地 SharedPreferences 作为备份
     * 
     * 重要：等级始终根据积分实时计算，确保不会出现"积分够了但等级没升"的问题
     */
    suspend fun getMemberInfo(): MemberInfo = withContext(Dispatchers.IO) {
        // 从 RewardsRepository 读取后端同步的数据
        val userProfile = rewardsRepository.getUserProfile()
        
        // 当前余额（用于显示，与积分记录页面一致）
        val currentBalance = userProfile.memoBalance.toLong()
        
        // 累计获取（用于等级计算）- 后端已修复同步问题，两者应该一致
        val totalEarned = when {
            userProfile.totalMemoEarned > 0 -> userProfile.totalMemoEarned.toLong()
            currentBalance > 0 -> currentBalance
            else -> prefs.getLong(KEY_MEMBER_POINTS, 0)
        }
        
        Timber.d("$TAG: 积分数据 - memoBalance=$currentBalance, totalMemoEarned=${userProfile.totalMemoEarned}")
        
        // 使用累计获取计算等级
        val currentTier = MemberTier.fromPoints(totalEarned)
        
        // 如果数据库中的等级低于计算出的等级，同步更新数据库
        if (userProfile.currentTier < currentTier.level) {
            try {
                val database = com.soulon.app.rewards.RewardsDatabase.getInstance(context)
                database.rewardsDao().updateTier("default_user", currentTier.level)
                Timber.i("🔄 同步等级到数据库: ${userProfile.currentTier} -> ${currentTier.level}")
            } catch (e: Exception) {
                Timber.w(e, "同步等级到数据库失败")
            }
        }
        
        val nextTier = MemberTier.getNextTier(currentTier)
        val tierSince = prefs.getLong(KEY_TIER_SINCE, System.currentTimeMillis())
        
        // 距离下一级需要的积分（基于累计获取）
        val pointsToNextTier = if (nextTier != null) {
            maxOf(0L, nextTier.pointsRequired - totalEarned)
        } else 0L
        
        // 进度百分比（基于累计获取）
        val progressPercent = if (nextTier != null) {
            val currentTierMin = currentTier.pointsRequired
            val nextTierMin = nextTier.pointsRequired
            ((totalEarned - currentTierMin).toFloat() / (nextTierMin - currentTierMin)).coerceIn(0f, 1f)
        } else 1.0f
        
        MemberInfo(
            tier = currentTier,
            totalPoints = currentBalance,           // 显示当前余额（与积分记录页面一致）
            totalEarnedPoints = totalEarned,        // 累计获取（用于等级计算）
            pointsToNextTier = pointsToNextTier,
            progressPercent = progressPercent,
            nextTier = nextTier,
            tierSince = tierSince,
            benefits = getMemberBenefits(currentTier),
            stats = getMemberStats()
        )
    }
    
    /**
     * 获取会员权益
     */
    private fun getMemberBenefits(tier: MemberTier): MemberBenefits {
        return MemberBenefits(
            airdropMultiplier = tier.airdropMultiplier,
            nftDropRate = tier.nftDropRate,
            physicalRewardEligible = tier.physicalRewardEligible,
            exclusiveEvents = tier.exclusiveEvents,
            exclusiveBadge = tier.iconEmoji,
            prioritySupport = tier.level >= 3
        )
    }
    
    /**
     * 获取会员统计
     */
    private fun getMemberStats(): MemberStats {
        val tierSince = prefs.getLong(KEY_TIER_SINCE, System.currentTimeMillis())
        val daysAsMember = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - tierSince).toInt()
        
        return MemberStats(
            totalAirdropsReceived = prefs.getInt(KEY_TOTAL_AIRDROPS, 0),
            totalNftsReceived = prefs.getInt(KEY_TOTAL_NFTS, 0),
            daysAsMember = daysAsMember,
            currentStreak = calculateCurrentStreak()
        )
    }
    
    /**
     * 添加会员积分
     * 
     * @param source 积分来源
     * @param userLevelMultiplier 用户级别影响的积分倍率
     * @param customPoints 自定义积分（仅用于 SPECIAL_EVENT 和 ACHIEVEMENT）
     * @param description 描述
     * @return 实际获得的积分
     */
    suspend fun addPoints(
        source: PointSource,
        userLevelMultiplier: Float = 1.0f,
        customPoints: Int? = null,
        description: String = ""
    ): PointsRecord = withContext(Dispatchers.IO) {
        val basePoints = customPoints ?: source.basePoints
        val finalPoints = (basePoints * userLevelMultiplier).toInt()
        
        val currentPoints = prefs.getLong(KEY_MEMBER_POINTS, 0)
        val oldTier = MemberTier.fromPoints(currentPoints)
        
        val newPoints = currentPoints + finalPoints
        prefs.edit()
            .putLong(KEY_MEMBER_POINTS, newPoints)
            .putLong(KEY_LAST_POINTS_UPDATE, System.currentTimeMillis())
            .apply()
        
        val newTier = MemberTier.fromPoints(newPoints)
        
        // 检查是否升级
        if (newTier.level > oldTier.level) {
            prefs.edit()
                .putInt(KEY_MEMBER_TIER, newTier.level)
                .putLong(KEY_TIER_SINCE, System.currentTimeMillis())
                .apply()
            
            Timber.i("🎉 $TAG: 会员升级！${oldTier.displayNameCn} → ${newTier.displayNameCn}")
        }
        
        val record = PointsRecord(
            source = source,
            points = basePoints,
            multiplier = userLevelMultiplier,
            finalPoints = finalPoints,
            timestamp = System.currentTimeMillis(),
            description = description.ifEmpty { source.localizedName() }
        )
        
        Timber.d("$TAG: +$finalPoints 积分 (${source.localizedName()}), 总计: $newPoints")
        
        record
    }
    
    /**
     * 记录每日登录
     */
    suspend fun recordDailyLogin(userLevelMultiplier: Float = 1.0f): PointsRecord {
        val streak = calculateCurrentStreak()
        val streakBonus = minOf(streak, MAX_STREAK_BONUS_DAYS)
        
        // 基础登录积分
        val loginRecord = addPoints(
            source = PointSource.DAILY_LOGIN,
            userLevelMultiplier = userLevelMultiplier
        )
        
        // 连续登录加成
        if (streakBonus > 0) {
            addPoints(
                source = PointSource.STREAK_BONUS,
                userLevelMultiplier = userLevelMultiplier,
                customPoints = streakBonus * PointSource.STREAK_BONUS.basePoints,
                description = "连续登录 $streak 天"
            )
        }
        
        updateLoginStreak()
        
        return loginRecord
    }
    
    /**
     * 记录 AI 对话
     */
    suspend fun recordAIConversation(userLevelMultiplier: Float = 1.0f): PointsRecord {
        return addPoints(
            source = PointSource.AI_CONVERSATION,
            userLevelMultiplier = userLevelMultiplier
        )
    }
    
    /**
     * 记录记忆上传
     */
    suspend fun recordMemoryUpload(userLevelMultiplier: Float = 1.0f): PointsRecord {
        return addPoints(
            source = PointSource.MEMORY_UPLOAD,
            userLevelMultiplier = userLevelMultiplier
        )
    }
    
    /**
     * 记录人格更新
     */
    suspend fun recordPersonaUpdate(userLevelMultiplier: Float = 1.0f): PointsRecord {
        return addPoints(
            source = PointSource.PERSONA_UPDATE,
            userLevelMultiplier = userLevelMultiplier
        )
    }
    
    /**
     * 记录邀请好友
     */
    suspend fun recordReferral(userLevelMultiplier: Float = 1.0f): PointsRecord {
        return addPoints(
            source = PointSource.REFERRAL,
            userLevelMultiplier = userLevelMultiplier
        )
    }
    
    /**
     * 记录完成问卷
     */
    suspend fun recordQuestionnaire(userLevelMultiplier: Float = 1.0f): PointsRecord {
        return addPoints(
            source = PointSource.QUESTIONNAIRE,
            userLevelMultiplier = userLevelMultiplier
        )
    }
    
    /**
     * 记录空投领取
     */
    fun recordAirdrop() {
        val current = prefs.getInt(KEY_TOTAL_AIRDROPS, 0)
        prefs.edit().putInt(KEY_TOTAL_AIRDROPS, current + 1).apply()
        Timber.i("$TAG: 📦 空投领取记录 +1")
    }
    
    /**
     * 记录 NFT 领取
     */
    fun recordNftReceived() {
        val current = prefs.getInt(KEY_TOTAL_NFTS, 0)
        prefs.edit().putInt(KEY_TOTAL_NFTS, current + 1).apply()
        Timber.i("$TAG: 🎨 NFT 领取记录 +1")
    }
    
    // ========== 连续登录逻辑 ==========
    
    private fun calculateCurrentStreak(): Int {
        val lastLogin = prefs.getLong("last_login_date", 0)
        val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        val lastDay = lastLogin / (24 * 60 * 60 * 1000)
        
        return when {
            lastDay == today -> prefs.getInt("login_streak", 1)
            lastDay == today - 1 -> prefs.getInt("login_streak", 0) + 1
            else -> 1
        }
    }
    
    private fun updateLoginStreak() {
        val lastLogin = prefs.getLong("last_login_date", 0)
        val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        val lastDay = lastLogin / (24 * 60 * 60 * 1000)
        
        val newStreak = when {
            lastDay == today -> prefs.getInt("login_streak", 1)
            lastDay == today - 1 -> prefs.getInt("login_streak", 0) + 1
            else -> 1
        }
        
        prefs.edit()
            .putLong("last_login_date", System.currentTimeMillis())
            .putInt("login_streak", newStreak)
            .apply()
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 获取所有会员等级
     */
    fun getAllTiers(): List<MemberTier> = MemberTier.values().toList()
    
    /**
     * 获取当前会员等级
     */
    fun getCurrentTier(): MemberTier {
        val points = prefs.getLong(KEY_MEMBER_POINTS, 0)
        return MemberTier.fromPoints(points)
    }
    
    /**
     * 获取当前积分
     */
    fun getCurrentPoints(): Long {
        return prefs.getLong(KEY_MEMBER_POINTS, 0)
    }
    
    /**
     * 清除数据（用于测试）
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

/**
 * 会员等级升级结果
 */
data class MemberTierUpgradeResult(
    val upgraded: Boolean,
    val oldTier: MemberTierManager.MemberTier,
    val newTier: MemberTierManager.MemberTier,
    val newBenefits: List<String>
)
