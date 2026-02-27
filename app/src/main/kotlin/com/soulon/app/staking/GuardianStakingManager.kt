package com.soulon.app.staking

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.wallet.SolanaRpcClient
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Guardian 质押状态管理器
 * 
 * 读取用户对 Solana 验证者（Guardian）的 $SKR 质押状态
 * 并计算相应的加成收益
 * 
 * Guardian（守护者）是 Seeker 生态中的验证节点：
 * - 用户可以将 $SKR 质押给 Guardian
 * - 质押给官方认证的 Guardian（如 Helius、Jito）可获得额外加成
 * 
 * 加成类型：
 * - 额外积分：+20%
 * - 手续费减免：10%
 * - 解锁特殊功能：高级皮肤、优先队列
 * 
 * @property context Android 上下文
 * @property rpcClient Solana RPC 客户端
 */
class GuardianStakingManager(
    private val context: Context,
    private val rpcClient: SolanaRpcClient
) {
    companion object {
        private const val TAG = "GuardianStaking"
        
        // $SKR Token Mint（需要替换为实际地址）
        const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        
        // 官方认证的 Guardian 节点
        val CERTIFIED_GUARDIANS = listOf(
            CertifiedGuardian(
                name = "Helius",
                address = "HeL1usGt7gpjy2uFgwvwGzFxBPn3RhKMSjzHf3SqGKZE",
                description = "高性能 RPC 提供商",
                logoUrl = "https://helius.xyz/logo.png",
                apy = 8.5f
            ),
            CertifiedGuardian(
                name = "Jito",
                address = "JitoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxV1",
                description = "MEV 优化验证者",
                logoUrl = "https://jito.network/logo.png",
                apy = 9.2f
            ),
            CertifiedGuardian(
                name = "Solana Mobile",
                address = "SMobxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                description = "官方 Seeker 节点",
                logoUrl = "https://solanamobile.com/logo.png",
                apy = 7.8f
            )
        )
        
        // 缓存
        private const val PREFS_NAME = "guardian_staking_prefs"
        private const val KEY_STAKED_AMOUNT = "staked_amount"
        private const val KEY_STAKED_GUARDIAN = "staked_guardian"
        private const val KEY_IS_CERTIFIED = "is_certified"
        private const val KEY_LAST_UPDATE = "last_update"
        
        // 缓存有效期（30 分钟）
        private const val CACHE_VALIDITY_MS = 30 * 60 * 1000L
    }
    
    private val prefs: SharedPreferences
        get() = WalletScope.scopedPrefs(context, PREFS_NAME)
    
    // 缓存的质押状态
    private var cachedStatus: GuardianStakingStatus? = null
    
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
                val stakedAmount = prefs.getLong(KEY_STAKED_AMOUNT, 0L)
                val guardianAddress = prefs.getString(KEY_STAKED_GUARDIAN, null)
                val isCertified = prefs.getBoolean(KEY_IS_CERTIFIED, false)
                
                if (guardianAddress != null) {
                    cachedStatus = GuardianStakingStatus(
                        isStaking = stakedAmount > 0,
                        stakedAmount = stakedAmount,
                        guardianAddress = guardianAddress,
                        guardianName = findGuardianName(guardianAddress),
                        isCertifiedGuardian = isCertified
                    )
                    Timber.i("$TAG: 已加载缓存质押状态")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 加载缓存失败")
        }
    }
    
    /**
     * 保存缓存
     */
    private fun saveCache(status: GuardianStakingStatus) {
        try {
            prefs.edit().apply {
                putLong(KEY_STAKED_AMOUNT, status.stakedAmount)
                putString(KEY_STAKED_GUARDIAN, status.guardianAddress)
                putBoolean(KEY_IS_CERTIFIED, status.isCertifiedGuardian)
                putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                apply()
            }
            cachedStatus = status
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 保存缓存失败")
        }
    }
    
    /**
     * 获取质押状态
     * 
     * @param walletAddress 钱包地址
     * @return 质押状态
     */
    suspend fun getStakingStatus(walletAddress: String): GuardianStakingStatus = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 查询质押状态: $walletAddress")
            
            // 1. 查询 Stake 账户
            val stakeAccounts = queryStakeAccounts(walletAddress)
            
            if (stakeAccounts.isNotEmpty()) {
                // 找到最大的质押
                val primaryStake = stakeAccounts.maxByOrNull { it.amount }!!
                val isCertified = isCertifiedGuardian(primaryStake.validatorAddress)
                
                val status = GuardianStakingStatus(
                    isStaking = true,
                    stakedAmount = primaryStake.amount,
                    guardianAddress = primaryStake.validatorAddress,
                    guardianName = findGuardianName(primaryStake.validatorAddress),
                    isCertifiedGuardian = isCertified
                )
                
                saveCache(status)
                Timber.i("$TAG: ✅ 质押状态: ${primaryStake.amount} SKR -> ${status.guardianName}")
                
                return@withContext status
            }
            
            // 没有找到质押
            val noStakeStatus = GuardianStakingStatus(
                isStaking = false,
                stakedAmount = 0L,
                guardianAddress = null,
                guardianName = null,
                isCertifiedGuardian = false
            )
            saveCache(noStakeStatus)
            
            Timber.w("$TAG: 未找到质押记录")
            noStakeStatus
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取质押状态失败")
            cachedStatus ?: GuardianStakingStatus(
                isStaking = false,
                stakedAmount = 0L,
                guardianAddress = null,
                guardianName = null,
                isCertifiedGuardian = false
            )
        }
    }
    
    /**
     * 计算质押加成
     */
    fun calculateStakingBonus(status: GuardianStakingStatus): StakingBonus {
        if (!status.isStaking) {
            return StakingBonus.NONE
        }
        
        return if (status.isCertifiedGuardian) {
            StakingBonus(
                extraMemoPercent = 20,      // 额外 20% 积分
                feeDiscount = 0.1f,         // 10% 手续费减免
                unlockFeatures = listOf(
                    "premium_skin",          // 高级皮肤
                    "priority_queue",        // 优先队列
                    "exclusive_airdrops"     // 专属空投
                ),
                description = "质押给认证 Guardian，享受完整加成"
            )
        } else {
            StakingBonus(
                extraMemoPercent = 5,       // 普通质押只有 5% 加成
                feeDiscount = 0.02f,        // 2% 手续费减免
                unlockFeatures = emptyList(),
                description = "质押给普通 Guardian"
            )
        }
    }
    
    /**
     * 获取缓存的质押状态
     */
    fun getCachedStatus(): GuardianStakingStatus? = cachedStatus
    
    /**
     * 获取认证 Guardian 列表
     */
    fun getCertifiedGuardians(): List<CertifiedGuardian> = CERTIFIED_GUARDIANS
    
    /**
     * 检查是否是认证 Guardian
     */
    fun isCertifiedGuardian(validatorAddress: String?): Boolean {
        if (validatorAddress == null) return false
        return CERTIFIED_GUARDIANS.any { 
            it.address.equals(validatorAddress, ignoreCase = true) 
        }
    }
    
    /**
     * 查找 Guardian 名称
     */
    private fun findGuardianName(address: String?): String? {
        if (address == null) return null
        return CERTIFIED_GUARDIANS.find { 
            it.address.equals(address, ignoreCase = true) 
        }?.name
    }
    
    /**
     * 查询 Stake 账户
     */
    private suspend fun queryStakeAccounts(walletAddress: String): List<StakeAccountInfo> {
        return try {
            // TODO: 实现真正的 Stake 账户查询
            // 这里需要调用 getStakeAccountsByWithdrawer 或类似 RPC 方法
            
            // 模拟数据（开发用）
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 查询 Stake 账户失败")
            emptyList()
        }
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        cachedStatus = null
    }
}

/**
 * Guardian 质押状态
 */
data class GuardianStakingStatus(
    val isStaking: Boolean,
    val stakedAmount: Long,                    // SKR 数量（原子单位）
    val guardianAddress: String?,
    val guardianName: String?,
    val isCertifiedGuardian: Boolean
) {
    /**
     * 获取格式化的质押数量
     */
    fun getFormattedAmount(): String {
        val skr = stakedAmount / 1_000_000_000.0  // 假设 9 位小数
        return String.format("%.2f SKR", skr)
    }
}

/**
 * 认证 Guardian 信息
 */
data class CertifiedGuardian(
    val name: String,
    val address: String,
    val description: String,
    val logoUrl: String,
    val apy: Float                             // 预估年化收益率
)

/**
 * 质押加成
 */
data class StakingBonus(
    val extraMemoPercent: Int,                 // 额外积分百分比
    val feeDiscount: Float,                    // 手续费折扣（0-1）
    val unlockFeatures: List<String>,          // 解锁的功能
    val description: String
) {
    companion object {
        val NONE = StakingBonus(
            extraMemoPercent = 0,
            feeDiscount = 0f,
            unlockFeatures = emptyList(),
            description = "未质押"
        )
    }
    
    /**
     * 计算积分倍数
     */
    fun getMemoMultiplier(): Float {
        return 1.0f + (extraMemoPercent / 100f)
    }
}

/**
 * Stake 账户信息（内部使用）
 */
internal data class StakeAccountInfo(
    val address: String,
    val amount: Long,
    val validatorAddress: String
)
