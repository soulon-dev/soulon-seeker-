package com.soulon.app.sovereign

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.wallet.SolanaRpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Sovereign Score 链上管理器
 * 
 * 从 Solana 链上读取用户的 Sovereign 等级，并联动项目奖励计算
 * 
 * Sovereign Score 是 Solana Seeker 生态的核心概念：
 * - 用户通过质押 $SKR、持有 Genesis Token 等方式提升等级
 * - 不同等级获得不同的收益倍数
 * 
 * 等级定义：
 * - BRONZE: 1.0x 倍数
 * - SILVER: 1.1x 倍数
 * - GOLD: 1.2x 倍数
 * - PLATINUM: 1.35x 倍数
 * - DIAMOND: 1.5x 倍数
 * 
 * @property context Android 上下文
 * @property rpcClient Solana RPC 客户端
 */
class SovereignScoreManager(
    private val context: Context,
    private val rpcClient: SolanaRpcClient
) {
    companion object {
        private const val TAG = "SovereignScore"
        
        // Sovereign Program ID（需要替换为实际地址）
        const val SOVEREIGN_PROGRAM_ID = "SovrnXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
        
        // 缓存
        private const val PREFS_NAME = "sovereign_prefs"
        private const val KEY_LEVEL = "sovereign_level"
        private const val KEY_SCORE = "sovereign_score"
        private const val KEY_LAST_UPDATE = "last_update"
        
        // 缓存有效期（1 小时）
        private const val CACHE_VALIDITY_MS = 60 * 60 * 1000L
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // 缓存的 Sovereign 数据
    private var cachedLevel: SovereignLevel? = null
    private var cachedScore: Int = 0
    
    init {
        loadCache()
    }
    
    /**
     * Sovereign 等级枚举
     */
    enum class SovereignLevel(
        val displayName: String,
        val multiplier: Float,
        val minScore: Int,
        val colorHex: String
    ) {
        BRONZE("Bronze", 1.0f, 0, "#CD7F32"),
        SILVER("Silver", 1.1f, 100, "#C0C0C0"),
        GOLD("Gold", 1.2f, 500, "#FFD700"),
        PLATINUM("Platinum", 1.35f, 2000, "#E5E4E2"),
        DIAMOND("Diamond", 1.5f, 10000, "#B9F2FF");
        
        companion object {
            /**
             * 根据分数获取等级
             */
            fun fromScore(score: Int): SovereignLevel {
                return values()
                    .filter { score >= it.minScore }
                    .maxByOrNull { it.minScore }
                    ?: BRONZE
            }
        }
    }
    
    /**
     * 加载缓存
     */
    private fun loadCache() {
        try {
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
            if (System.currentTimeMillis() - lastUpdate < CACHE_VALIDITY_MS) {
                val levelName = prefs.getString(KEY_LEVEL, null)
                if (levelName != null) {
                    cachedLevel = try {
                        SovereignLevel.valueOf(levelName)
                    } catch (e: Exception) {
                        SovereignLevel.BRONZE
                    }
                    cachedScore = prefs.getInt(KEY_SCORE, 0)
                    Timber.i("$TAG: 已加载缓存 - Level: ${cachedLevel?.displayName}, Score: $cachedScore")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 加载缓存失败")
        }
    }
    
    /**
     * 保存缓存
     */
    private fun saveCache(level: SovereignLevel, score: Int) {
        try {
            prefs.edit().apply {
                putString(KEY_LEVEL, level.name)
                putInt(KEY_SCORE, score)
                putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                apply()
            }
            cachedLevel = level
            cachedScore = score
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 保存缓存失败")
        }
    }
    
    /**
     * 从链上获取 Sovereign 等级
     * 
     * @param walletAddress 钱包地址
     * @return Sovereign 等级
     */
    suspend fun getSovereignLevel(walletAddress: String): SovereignLevel = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 查询 Sovereign 等级: $walletAddress")
            
            // 1. 派生 Sovereign PDA
            val sovereignPda = deriveSovereignPda(walletAddress)
            
            // 2. 获取账户数据
            val accountData = rpcClient.getAccountInfoRaw(sovereignPda)
            
            if (accountData != null) {
                // 3. 解析 Sovereign 数据
                val score = parseSovereignScore(accountData)
                val level = SovereignLevel.fromScore(score)
                
                Timber.i("$TAG: ✅ Sovereign Score: $score, Level: ${level.displayName}")
                saveCache(level, score)
                
                return@withContext level
            }
            
            // 如果没有链上数据，返回缓存或默认值
            Timber.w("$TAG: 未找到链上 Sovereign 数据，使用缓存或默认值")
            cachedLevel ?: SovereignLevel.BRONZE
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取 Sovereign 等级失败")
            cachedLevel ?: SovereignLevel.BRONZE
        }
    }
    
    /**
     * 获取完整的 Sovereign 信息
     */
    suspend fun getSovereignInfo(walletAddress: String): SovereignInfo = withContext(Dispatchers.IO) {
        try {
            val level = getSovereignLevel(walletAddress)
            
            SovereignInfo(
                level = level,
                score = cachedScore,
                multiplier = level.multiplier,
                nextLevel = getNextLevel(level),
                pointsToNextLevel = getPointsToNextLevel(level, cachedScore)
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取 Sovereign 信息失败")
            SovereignInfo(
                level = SovereignLevel.BRONZE,
                score = 0,
                multiplier = 1.0f,
                nextLevel = SovereignLevel.SILVER,
                pointsToNextLevel = SovereignLevel.SILVER.minScore
            )
        }
    }
    
    /**
     * 获取当前收益倍数
     */
    fun getCurrentMultiplier(): Float {
        return cachedLevel?.multiplier ?: 1.0f
    }
    
    /**
     * 获取缓存的等级
     */
    fun getCachedLevel(): SovereignLevel {
        return cachedLevel ?: SovereignLevel.BRONZE
    }
    
    /**
     * 派生 Sovereign PDA
     */
    private fun deriveSovereignPda(walletAddress: String): String {
        // PDA seeds: ["sovereign", wallet_address]
        // 这里简化处理，实际需要正确计算 PDA
        // TODO: 实现正确的 PDA 派生
        return "SovereignPDA_$walletAddress"
    }
    
    /**
     * 解析 Sovereign Score
     */
    private fun parseSovereignScore(accountData: ByteArray): Int {
        return try {
            // Sovereign 账户数据格式（简化）：
            // offset 0-7: discriminator (8 bytes)
            // offset 8-11: score (u32, 4 bytes, little-endian)
            if (accountData.size >= 12) {
                val score = (accountData[8].toInt() and 0xFF) or
                           ((accountData[9].toInt() and 0xFF) shl 8) or
                           ((accountData[10].toInt() and 0xFF) shl 16) or
                           ((accountData[11].toInt() and 0xFF) shl 24)
                score
            } else {
                0
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 解析 Sovereign Score 失败")
            0
        }
    }
    
    /**
     * 获取下一个等级
     */
    private fun getNextLevel(currentLevel: SovereignLevel): SovereignLevel? {
        val levels = SovereignLevel.values()
        val currentIndex = levels.indexOf(currentLevel)
        return if (currentIndex < levels.size - 1) {
            levels[currentIndex + 1]
        } else {
            null
        }
    }
    
    /**
     * 获取升到下一级所需的分数
     */
    private fun getPointsToNextLevel(currentLevel: SovereignLevel, currentScore: Int): Int {
        val nextLevel = getNextLevel(currentLevel) ?: return 0
        return maxOf(0, nextLevel.minScore - currentScore)
    }
    
    /**
     * 清除缓存（用于测试）
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        cachedLevel = null
        cachedScore = 0
    }
}

/**
 * Sovereign 信息
 */
data class SovereignInfo(
    val level: SovereignScoreManager.SovereignLevel,
    val score: Int,
    val multiplier: Float,
    val nextLevel: SovereignScoreManager.SovereignLevel?,
    val pointsToNextLevel: Int
)
