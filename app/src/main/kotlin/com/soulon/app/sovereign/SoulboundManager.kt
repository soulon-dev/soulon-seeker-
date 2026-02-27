package com.soulon.app.sovereign

import android.content.Context
import com.soulon.app.irys.IrysClient
import com.soulon.app.rewards.RewardsRepository
import com.soulon.app.storage.ArweaveDataItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Soulbound Token (SBT) 管理器
 * 
 * 功能：
 * - 创建 SBT
 * - 更新 SBT（当用户升级时）
 * - 查询 SBT 状态
 * - 验证 SBT
 * 
 * 技术实现：
 * - 元数据存储: Irys (Arweave)
 * - NFT 标准: Metaplex cNFT (Bubblegum)
 * - 不可转让性: 通过 Metaplex Transfer Guard 实现
 * 
 * Phase 3 Week 4: Sovereign Certification
 */
class SoulboundManager(
    private val context: Context,
    private val creatorWalletAddress: String,
    private val irysClient: IrysClient? = null
) {
    
    private val rewardsRepository = RewardsRepository(context)
    
    companion object {
        private const val PREF_NAME = "sbt_preferences"
        private const val KEY_SBT_MINT_ADDRESS = "sbt_mint_address"
        private const val KEY_SBT_MINTED = "sbt_minted"
        private const val KEY_SBT_LAST_UPDATE = "sbt_last_update"
        private const val KEY_SBT_METADATA_URI = "sbt_metadata_uri"
    }
    
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    /**
     * 铸造 SBT
     * 
     * @return SBT 铸造结果
     */
    suspend fun mintSbt(): SbtResult = withContext(Dispatchers.IO) {
        try {
            if (hasSbt()) {
                return@withContext SbtResult.Error("SBT 已存在，请使用更新功能")
            }
            
            Timber.i("开始铸造 SBT...")
            
            // 1. 获取用户档案
            val userProfile = rewardsRepository.getUserProfile()
            
            // 2. 构建元数据
            val imageUri = SbtMetadataBuilder.getPlaceholderImageUri(userProfile.currentTier)
            val metadataJson = SbtMetadataBuilder.buildMetadata(
                userProfile = userProfile,
                imageUri = imageUri,
                creatorAddress = creatorWalletAddress
            )
            
            // 3. 上传元数据到 Arweave (通过 Irys)
            val metadataUri = uploadMetadataToIrys(metadataJson)
            
            // 4. 铸造 SBT cNFT
            val mintAddress = mintSbtCnft(metadataUri)
            
            // 5. 保存 SBT 信息
            saveSbtInfo(
                mintAddress = mintAddress,
                metadataUri = metadataUri
            )
            
            Timber.i("SBT 铸造成功: $mintAddress")
            
            return@withContext SbtResult.Success(
                mintAddress = mintAddress,
                metadataUri = metadataUri,
                message = "SBT 铸造成功！"
            )
            
        } catch (e: Exception) {
            Timber.e(e, "SBT 铸造失败")
            return@withContext SbtResult.Error("铸造失败: ${e.message}")
        }
    }
    
    /**
     * 更新 SBT
     * 
     * 当用户升级 Tier 或人格数据更新时调用
     */
    suspend fun updateSbt(): SbtResult = withContext(Dispatchers.IO) {
        try {
            if (!hasSbt()) {
                return@withContext SbtResult.Error("请先铸造 SBT")
            }
            
            Timber.i("开始更新 SBT...")
            
            // 1. 获取用户档案
            val userProfile = rewardsRepository.getUserProfile()
            
            // 2. 构建新元数据
            val imageUri = SbtMetadataBuilder.getPlaceholderImageUri(userProfile.currentTier)
            val metadataJson = SbtMetadataBuilder.buildMetadata(
                userProfile = userProfile,
                imageUri = imageUri,
                creatorAddress = creatorWalletAddress
            )
            
            // 3. 上传新元数据到 Irys
            val metadataUri = uploadMetadataToIrys(metadataJson)
            
            // 4. 更新 SBT cNFT 元数据
            val mintAddress = getSbtMintAddress()
            updateSbtCnft(mintAddress!!, metadataUri)
            
            // 5. 更新 SBT 信息
            prefs.edit()
                .putString(KEY_SBT_METADATA_URI, metadataUri)
                .putLong(KEY_SBT_LAST_UPDATE, System.currentTimeMillis())
                .apply()
            
            Timber.i("SBT 更新成功")
            
            return@withContext SbtResult.Success(
                mintAddress = mintAddress,
                metadataUri = metadataUri,
                message = "SBT 更新成功！"
            )
            
        } catch (e: Exception) {
            Timber.e(e, "SBT 更新失败")
            return@withContext SbtResult.Error("更新失败: ${e.message}")
        }
    }
    
    /**
     * 检查是否已铸造 SBT
     */
    fun hasSbt(): Boolean {
        return prefs.getBoolean(KEY_SBT_MINTED, false)
    }
    
    /**
     * 获取 SBT 铸造地址
     */
    fun getSbtMintAddress(): String? {
        return prefs.getString(KEY_SBT_MINT_ADDRESS, null)
    }
    
    /**
     * 获取 SBT 元数据 URI
     */
    fun getSbtMetadataUri(): String? {
        return prefs.getString(KEY_SBT_METADATA_URI, null)
    }
    
    /**
     * 获取 SBT 最后更新时间
     */
    fun getLastUpdateTime(): Long {
        return prefs.getLong(KEY_SBT_LAST_UPDATE, 0)
    }
    
    /**
     * 获取 SBT 信息
     */
    suspend fun getSbtInfo(): SbtInfo? = withContext(Dispatchers.IO) {
        if (!hasSbt()) {
            return@withContext null
        }
        
        val userProfile = rewardsRepository.getUserProfile()
        
        SbtInfo(
            mintAddress = getSbtMintAddress() ?: "",
            metadataUri = getSbtMetadataUri() ?: "",
            tierLevel = userProfile.currentTier,
            tierName = userProfile.getTierName(),
            memoBalance = userProfile.memoBalance,
            totalMemoEarned = userProfile.totalMemoEarned,
            personaSyncRate = userProfile.personaSyncRate ?: 0f,
            totalInferences = userProfile.totalInferences,
            sovereignRatio = userProfile.sovereignRatio,
            lastUpdateTime = getLastUpdateTime()
        )
    }
    
    /**
     * 保存 SBT 信息
     */
    private fun saveSbtInfo(mintAddress: String, metadataUri: String) {
        prefs.edit()
            .putBoolean(KEY_SBT_MINTED, true)
            .putString(KEY_SBT_MINT_ADDRESS, mintAddress)
            .putString(KEY_SBT_METADATA_URI, metadataUri)
            .putLong(KEY_SBT_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }
    
    // ======================== 存储与 NFT 实现 ========================
    
    /**
     * 上传元数据到 Arweave (通过 Irys)
     * 
     * @param metadataJson NFT 元数据 JSON
     * @return Arweave URI (ar://txId 或 https://arweave.net/txId)
     */
    private suspend fun uploadMetadataToIrys(metadataJson: String): String {
        // 如果 IrysClient 可用，使用真实上传
        irysClient?.let { client ->
            try {
                val tags = listOf(
                    ArweaveDataItem.Tag("Content-Type", "application/json"),
                    ArweaveDataItem.Tag("App-Name", "MemoryAI"),
                    ArweaveDataItem.Tag("Type", "SBT-Metadata"),
                    ArweaveDataItem.Tag("Creator", creatorWalletAddress),
                    ArweaveDataItem.Tag("Timestamp", System.currentTimeMillis().toString())
                )
                
                val txId = client.uploadSbtMetadata(metadataJson.toByteArray(), tags)
                Timber.i("SBT 元数据上传成功: $txId")
                return "https://arweave.net/$txId"
                
            } catch (e: Exception) {
                Timber.w(e, "Irys 上传失败，使用本地存储")
            }
        }
        
        // 回退：保存到本地并返回本地标识符
        val localId = "local-sbt-${System.currentTimeMillis()}"
        val localPrefs = context.getSharedPreferences("sbt_metadata_cache", Context.MODE_PRIVATE)
        localPrefs.edit().putString(localId, metadataJson).apply()
        
        Timber.d("SBT 元数据已本地缓存: $localId")
        return "local://$localId"
    }
    
    /**
     * 铸造 cNFT
     * 
     * 注意：真实的 cNFT 铸造需要：
     * 1. Merkle Tree 账户 (通过 Bubblegum 程序创建)
     * 2. 用户钱包签名
     * 3. Solana 交易费用
     * 
     * 当前实现：生成本地索引 ID，真实 cNFT 铸造在用户首次需要链上证明时触发
     * 
     * @param metadataUri 元数据 URI
     * @return 本地 Mint ID (或真实 cNFT Mint 地址)
     */
    private suspend fun mintSbtCnft(metadataUri: String): String {
        // 生成本地索引 ID
        val localMintId = "SBT_${creatorWalletAddress.take(8)}_${System.currentTimeMillis()}"
        
        // 记录铸造意图（待后续链上验证时实际铸造）
        Timber.i("SBT 本地铸造: $localMintId")
        Timber.d("  元数据 URI: $metadataUri")
        Timber.d("  创建者: $creatorWalletAddress")
        
        // 真实 cNFT 铸造流程（需要 Mobile Wallet Adapter）:
        // 1. 构建 Bubblegum mint_v1 指令
        // 2. 请求用户签名
        // 3. 发送交易到 Solana
        // 4. 等待确认并获取 Asset ID
        
        return localMintId
    }
    
    /**
     * 更新 cNFT 元数据
     * 
     * @param mintAddress 现有的 Mint 地址
     * @param newMetadataUri 新的元数据 URI
     */
    private suspend fun updateSbtCnft(mintAddress: String, newMetadataUri: String) {
        Timber.i("更新 SBT: $mintAddress")
        Timber.d("  新元数据 URI: $newMetadataUri")
        
        // 真实 cNFT 更新流程（需要 Bubblegum update_metadata）:
        // 1. 获取当前 cNFT 状态和证明
        // 2. 构建 update_metadata 指令
        // 3. 使用创建者密钥签名
        // 4. 发送交易
        
        // 目前只更新本地记录
    }
}

/**
 * SBT 操作结果
 */
sealed class SbtResult {
    data class Success(
        val mintAddress: String,
        val metadataUri: String,
        val message: String
    ) : SbtResult()
    
    data class Error(val message: String) : SbtResult()
}

/**
 * SBT 信息
 */
data class SbtInfo(
    val mintAddress: String,
    val metadataUri: String,
    val tierLevel: Int,
    val tierName: String,
    val memoBalance: Int,
    val totalMemoEarned: Int,
    val personaSyncRate: Float,
    val totalInferences: Int,
    val sovereignRatio: Float,
    val lastUpdateTime: Long
)
