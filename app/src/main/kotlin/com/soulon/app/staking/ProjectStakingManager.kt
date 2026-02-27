package com.soulon.app.staking

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.wallet.SolanaRpcClient
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 项目质押管理器
 * 
 * 管理用户将代币质押到项目中的功能
 * 
 * 质押体系：
 * - 用户将 SOL/USDC/$SKR 质押到项目质押池
 * - 根据质押数量解锁不同等级的权益
 * - 质押时间越长，获得的加成越高
 * 
 * 质押等级：
 * - Tier 1: 10 SOL 或等值 (基础质押者)
 * - Tier 2: 50 SOL 或等值 (高级质押者)
 * - Tier 3: 200 SOL 或等值 (核心质押者)
 * - Tier 4: 1000 SOL 或等值 (创始人)
 * - Tier 5: 10000 SOL 或等值 (战略合作伙伴)
 * 
 * @property context Android 上下文
 * @property rpcClient Solana RPC 客户端
 */
class ProjectStakingManager(
    private val context: Context,
    private val rpcClient: SolanaRpcClient
) {
    companion object {
        private const val TAG = "ProjectStaking"
        
        // 项目质押池地址（需要替换为实际地址）
        const val STAKING_POOL_ADDRESS = "MemoryAIStakeXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
        
        // 支持的质押代币
        const val SOL_MINT = "So11111111111111111111111111111111111111112"
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        
        // 质押等级阈值（以 SOL 为基准）
        const val TIER_1_MIN = 10.0      // 10 SOL
        const val TIER_2_MIN = 50.0      // 50 SOL
        const val TIER_3_MIN = 200.0     // 200 SOL
        const val TIER_4_MIN = 1000.0    // 1000 SOL
        const val TIER_5_MIN = 10000.0   // 10000 SOL
        
        // 时间加成（质押时长）
        const val TIME_BONUS_30_DAYS = 0.05f   // 30 天 +5%
        const val TIME_BONUS_90_DAYS = 0.15f   // 90 天 +15%
        const val TIME_BONUS_180_DAYS = 0.30f  // 180 天 +30%
        const val TIME_BONUS_365_DAYS = 0.50f  // 365 天 +50%
        
        // SharedPreferences
        private const val PREFS_NAME = "project_staking_prefs"
        private const val KEY_STAKED_AMOUNT = "staked_amount"
        private const val KEY_STAKED_TOKEN = "staked_token"
        private const val KEY_STAKE_TIME = "stake_time"
        private const val KEY_LOCK_PERIOD = "lock_period"
        private const val KEY_LAST_UPDATE = "last_update"
        
        // 缓存有效期（10 分钟）
        private const val CACHE_VALIDITY_MS = 10 * 60 * 1000L
    }
    
    private val prefs: SharedPreferences
        get() = WalletScope.scopedPrefs(context, PREFS_NAME)
    
    private var cachedStakingInfo: ProjectStakingInfo? = null
    
    init {
        loadCache()
    }
    
    /**
     * 加载缓存
     */
    private fun loadCache() {
        try {
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
            if (System.currentTimeMillis() - lastUpdate < CACHE_VALIDITY_MS) {
                val stakedAmount = prefs.getFloat(KEY_STAKED_AMOUNT, 0f).toDouble()
                val stakedToken = prefs.getString(KEY_STAKED_TOKEN, null)
                val stakeTime = prefs.getLong(KEY_STAKE_TIME, 0L)
                val lockPeriod = prefs.getInt(KEY_LOCK_PERIOD, 0)
                
                if (stakedAmount > 0 && stakedToken != null) {
                    cachedStakingInfo = ProjectStakingInfo(
                        isStaking = true,
                        stakedAmount = stakedAmount,
                        stakedToken = StakingToken.fromMint(stakedToken),
                        stakeTimestamp = stakeTime,
                        lockPeriodDays = lockPeriod,
                        stakingTier = calculateTier(stakedAmount)
                    )
                    Timber.i("$TAG: 已加载缓存质押信息")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 加载缓存失败")
        }
    }
    
    /**
     * 保存缓存
     */
    private fun saveCache(info: ProjectStakingInfo) {
        try {
            prefs.edit().apply {
                putFloat(KEY_STAKED_AMOUNT, info.stakedAmount.toFloat())
                putString(KEY_STAKED_TOKEN, info.stakedToken.mint)
                putLong(KEY_STAKE_TIME, info.stakeTimestamp)
                putInt(KEY_LOCK_PERIOD, info.lockPeriodDays)
                putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                apply()
            }
            cachedStakingInfo = info
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 保存缓存失败")
        }
    }
    
    /**
     * 获取质押信息
     */
    suspend fun getStakingInfo(walletAddress: String): ProjectStakingInfo = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 查询质押信息: $walletAddress")
            
            // 查询链上质押账户
            val stakingData = queryStakingAccount(walletAddress)
            
            if (stakingData != null) {
                val info = ProjectStakingInfo(
                    isStaking = true,
                    stakedAmount = stakingData.amount,
                    stakedToken = stakingData.token,
                    stakeTimestamp = stakingData.timestamp,
                    lockPeriodDays = stakingData.lockDays,
                    stakingTier = calculateTier(stakingData.amount)
                )
                saveCache(info)
                Timber.i("$TAG: ✅ 质押: ${info.stakedAmount} ${info.stakedToken.symbol} -> ${info.stakingTier.displayName}")
                return@withContext info
            }
            
            // 无质押
            val noStake = ProjectStakingInfo(
                isStaking = false,
                stakedAmount = 0.0,
                stakedToken = StakingToken.SOL,
                stakeTimestamp = 0L,
                lockPeriodDays = 0,
                stakingTier = StakingTier.NONE
            )
            saveCache(noStake)
            Timber.w("$TAG: 未找到质押记录")
            noStake
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取质押信息失败")
            cachedStakingInfo ?: ProjectStakingInfo(
                isStaking = false,
                stakedAmount = 0.0,
                stakedToken = StakingToken.SOL,
                stakeTimestamp = 0L,
                lockPeriodDays = 0,
                stakingTier = StakingTier.NONE
            )
        }
    }
    
    /**
     * 计算质押权益
     */
    fun calculateBenefits(info: ProjectStakingInfo): StakingBenefits {
        if (!info.isStaking) {
            return StakingBenefits.NONE
        }
        
        val tier = info.stakingTier
        val timeBonus = calculateTimeBonus(info.stakeTimestamp, info.lockPeriodDays)
        
        return StakingBenefits(
            tier = tier,
            monthlyTokenLimit = tier.monthlyTokenLimit,
            memoMultiplier = tier.memoMultiplier * (1 + timeBonus),
            extraFeatures = tier.features.toList(),
            timeBonus = timeBonus,
            stakeDays = info.getStakedDays(),
            estimatedRewards = calculateEstimatedRewards(info)
        )
    }
    
    /**
     * 计算时间加成
     */
    private fun calculateTimeBonus(stakeTimestamp: Long, lockPeriodDays: Int): Float {
        if (stakeTimestamp == 0L) return 0f
        
        val stakedDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - stakeTimestamp).toInt()
        val effectiveDays = maxOf(stakedDays, lockPeriodDays)
        
        return when {
            effectiveDays >= 365 -> TIME_BONUS_365_DAYS
            effectiveDays >= 180 -> TIME_BONUS_180_DAYS
            effectiveDays >= 90 -> TIME_BONUS_90_DAYS
            effectiveDays >= 30 -> TIME_BONUS_30_DAYS
            else -> 0f
        }
    }
    
    /**
     * 计算质押等级
     */
    private fun calculateTier(amountInSol: Double): StakingTier {
        return when {
            amountInSol >= TIER_5_MIN -> StakingTier.STRATEGIC_PARTNER
            amountInSol >= TIER_4_MIN -> StakingTier.FOUNDER
            amountInSol >= TIER_3_MIN -> StakingTier.CORE
            amountInSol >= TIER_2_MIN -> StakingTier.ADVANCED
            amountInSol >= TIER_1_MIN -> StakingTier.BASIC
            else -> StakingTier.NONE
        }
    }
    
    /**
     * 计算预估奖励
     */
    private fun calculateEstimatedRewards(info: ProjectStakingInfo): EstimatedRewards {
        val tier = info.stakingTier
        val stakedDays = info.getStakedDays()
        
        // 基础年化收益
        val baseApy = tier.baseApy
        
        // 时间加成
        val timeBonus = calculateTimeBonus(info.stakeTimestamp, info.lockPeriodDays)
        val effectiveApy = baseApy * (1 + timeBonus)
        
        // 计算预估收益
        val dailyRate = effectiveApy / 365
        val earnedRewards = info.stakedAmount * dailyRate * stakedDays
        val pendingRewards = info.stakedAmount * dailyRate * 30 // 未来 30 天预估
        
        return EstimatedRewards(
            totalEarned = earnedRewards,
            pendingRewards = pendingRewards,
            effectiveApy = effectiveApy,
            nextMilestoneBonus = getNextMilestoneBonus(stakedDays)
        )
    }
    
    /**
     * 获取下一个里程碑加成
     */
    private fun getNextMilestoneBonus(stakedDays: Int): String {
        return when {
            stakedDays < 30 -> "再质押 ${30 - stakedDays} 天解锁 +5% 加成"
            stakedDays < 90 -> "再质押 ${90 - stakedDays} 天解锁 +15% 加成"
            stakedDays < 180 -> "再质押 ${180 - stakedDays} 天解锁 +30% 加成"
            stakedDays < 365 -> "再质押 ${365 - stakedDays} 天解锁 +50% 加成"
            else -> "已达最高加成等级"
        }
    }
    
    /**
     * 获取质押等级列表
     */
    fun getStakingTiers(): List<StakingTier> = StakingTier.values().filter { it != StakingTier.NONE }
    
    /**
     * 获取支持的质押代币
     */
    fun getSupportedTokens(): List<StakingToken> = StakingToken.values().toList()
    
    /**
     * 获取缓存的质押信息
     */
    fun getCachedStakingInfo(): ProjectStakingInfo? = cachedStakingInfo
    
    /**
     * 查询链上质押账户（模拟实现）
     */
    private suspend fun queryStakingAccount(walletAddress: String): StakingAccountData? {
        // TODO: 实现真正的链上查询
        // 这里需要调用项目的质押合约读取用户的质押信息
        
        // 开发阶段：返回模拟数据
        return null
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        cachedStakingInfo = null
    }
}

/**
 * 质押等级
 */
enum class StakingTier(
    val displayName: String,
    val minAmount: Double,
    val monthlyTokenLimit: Int,   // 每月 Token 限额
    val memoMultiplier: Float,
    val baseApy: Double,
    val features: Set<String>
) {
    NONE(
        displayName = "未质押",
        minAmount = 0.0,
        monthlyTokenLimit = 1_000_000,       // 100万/月
        memoMultiplier = 1.0f,
        baseApy = 0.0,
        features = emptySet()
    ),
    
    BASIC(
        displayName = "基础质押者",
        minAmount = 10.0,
        monthlyTokenLimit = 5_000_000,       // 500万/月
        memoMultiplier = 1.2f,
        baseApy = 0.08,  // 8%
        features = setOf("extended_history", "priority_support")
    ),
    
    ADVANCED(
        displayName = "高级质押者",
        minAmount = 50.0,
        monthlyTokenLimit = 10_000_000,      // 1000万/月
        memoMultiplier = 1.5f,
        baseApy = 0.12,  // 12%
        features = setOf(
            "extended_history",
            "priority_support",
            "advanced_analytics",
            "early_access"
        )
    ),
    
    CORE(
        displayName = "核心质押者",
        minAmount = 200.0,
        monthlyTokenLimit = 30_000_000,      // 3000万/月
        memoMultiplier = 2.0f,
        baseApy = 0.18,  // 18%
        features = setOf(
            "extended_history",
            "priority_support",
            "advanced_analytics",
            "early_access",
            "exclusive_airdrops",
            "governance_voting"
        )
    ),
    
    FOUNDER(
        displayName = "创始人",
        minAmount = 1000.0,
        monthlyTokenLimit = Int.MAX_VALUE,   // 无限制
        memoMultiplier = 3.0f,
        baseApy = 0.25,  // 25%
        features = setOf(
            "extended_history",
            "priority_support",
            "advanced_analytics",
            "early_access",
            "exclusive_airdrops",
            "governance_voting",
            "founder_badge",
            "investment_opportunities",
            "direct_team_access"
        )
    ),
    
    STRATEGIC_PARTNER(
        displayName = "战略合作伙伴",
        minAmount = 10000.0,
        monthlyTokenLimit = Int.MAX_VALUE,   // 无限制
        memoMultiplier = 5.0f,
        baseApy = 0.35,  // 35%
        features = setOf(
            "extended_history",
            "priority_support",
            "advanced_analytics",
            "early_access",
            "exclusive_airdrops",
            "governance_voting",
            "founder_badge",
            "investment_opportunities",
            "direct_team_access",
            "strategic_partnership",
            "custom_integration",
            "board_advisory"
        )
    );
    
    /**
     * 获取特性描述
     */
    fun getFeatureDescriptions(): Map<String, String> {
        return mapOf(
            "extended_history" to "扩展历史记录存储",
            "priority_support" to "优先客户支持",
            "advanced_analytics" to "高级数据分析",
            "early_access" to "新功能优先体验",
            "exclusive_airdrops" to "专属空投资格",
            "governance_voting" to "治理投票权",
            "founder_badge" to "创始人徽章",
            "investment_opportunities" to "投资机会优先权",
            "direct_team_access" to "团队直接沟通渠道",
            "strategic_partnership" to "战略合作伙伴身份",
            "custom_integration" to "定制集成服务",
            "board_advisory" to "顾问委员会席位"
        )
    }
}

/**
 * 支持的质押代币
 */
enum class StakingToken(
    val mint: String,
    val symbol: String,
    val decimals: Int,
    val conversionRate: Double  // 转换为 SOL 的比率
) {
    SOL(
        mint = "So11111111111111111111111111111111111111112",
        symbol = "SOL",
        decimals = 9,
        conversionRate = 1.0
    ),
    USDC(
        mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        symbol = "USDC",
        decimals = 6,
        conversionRate = 0.005  // 1 USDC ≈ 0.005 SOL（示例，需要实时价格）
    ),
    SKR(
        mint = "SKRxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        symbol = "SKR",
        decimals = 9,
        conversionRate = 0.001  // 项目代币（示例汇率）
    );
    
    companion object {
        fun fromMint(mint: String): StakingToken {
            return values().find { it.mint == mint } ?: SOL
        }
    }
}

/**
 * 项目质押信息
 */
data class ProjectStakingInfo(
    val isStaking: Boolean,
    val stakedAmount: Double,              // 质押数量
    val stakedToken: StakingToken,         // 质押代币
    val stakeTimestamp: Long,              // 质押开始时间
    val lockPeriodDays: Int,               // 锁定期（天）
    val stakingTier: StakingTier           // 质押等级
) {
    /**
     * 获取已质押天数
     */
    fun getStakedDays(): Int {
        if (stakeTimestamp == 0L) return 0
        return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - stakeTimestamp).toInt()
    }
    
    /**
     * 获取格式化的质押数量
     */
    fun getFormattedAmount(): String {
        return String.format("%.2f %s", stakedAmount, stakedToken.symbol)
    }
    
    /**
     * 获取等值 SOL 数量
     */
    fun getEquivalentSol(): Double {
        return stakedAmount * stakedToken.conversionRate
    }
    
    /**
     * 获取锁定结束时间
     */
    fun getLockEndTimestamp(): Long {
        return stakeTimestamp + TimeUnit.DAYS.toMillis(lockPeriodDays.toLong())
    }
    
    /**
     * 检查是否可以解锁
     */
    fun canUnlock(): Boolean {
        return System.currentTimeMillis() >= getLockEndTimestamp()
    }
}

/**
 * 质押权益
 */
data class StakingBenefits(
    val tier: StakingTier,
    val monthlyTokenLimit: Int,   // 每月 Token 限额
    val memoMultiplier: Float,
    val extraFeatures: List<String>,
    val timeBonus: Float,
    val stakeDays: Int,
    val estimatedRewards: EstimatedRewards
) {
    companion object {
        val NONE = StakingBenefits(
            tier = StakingTier.NONE,
            monthlyTokenLimit = 1_000_000,  // 100万/月
            memoMultiplier = 1.0f,
            extraFeatures = emptyList(),
            timeBonus = 0f,
            stakeDays = 0,
            estimatedRewards = EstimatedRewards(0.0, 0.0, 0.0, "")
        )
    }
    
    /**
     * 获取格式化的 Token 限额
     */
    fun getFormattedTokenLimit(): String {
        return if (monthlyTokenLimit == Int.MAX_VALUE) {
            "无限制"
        } else {
            String.format("%,d", monthlyTokenLimit)
        }
    }
}

/**
 * 预估奖励
 */
data class EstimatedRewards(
    val totalEarned: Double,          // 已获得的奖励
    val pendingRewards: Double,       // 待发放奖励
    val effectiveApy: Double,         // 有效年化收益率
    val nextMilestoneBonus: String    // 下一里程碑提示
)

/**
 * 链上质押账户数据（内部使用）
 */
internal data class StakingAccountData(
    val amount: Double,
    val token: StakingToken,
    val timestamp: Long,
    val lockDays: Int
)
