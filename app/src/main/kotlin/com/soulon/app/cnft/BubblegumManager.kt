package com.soulon.app.cnft

import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Metaplex Bubblegum cNFT 管理器
 * 
 * ⚠️ 已弃用：使用 Irys GraphQL 索引替代
 * 
 * 原设计功能：
 * - Mint 压缩 NFT 作为记忆索引
 * - 链上可验证的记录
 * 
 * 弃用原因：
 * - Irys 上传时的 Tags 已提供足够的索引能力
 * - 通过 Irys GraphQL API 可以免费查询
 * - 无需额外链上交易成本
 * 
 * 当前使用：
 * - 仅用于迁移备份凭证（可选）
 * - 新记忆存储不再铸造 cNFT
 * 
 * 替代方案：com.soulon.app.storage.IrysIndexer
 * 
 * @property walletManager 钱包管理器
 * @property rpcUrl Solana RPC 端点
 */
@Deprecated("使用 IrysIndexer 替代 cNFT 索引", ReplaceWith("IrysIndexer"))
class BubblegumManager(
    private val walletManager: com.soulon.app.wallet.WalletManager,
    private val rpcUrl: String = "https://api.mainnet-beta.solana.com" // ✅ 使用 Mainnet
) {
    
    companion object {
        // Metaplex Bubblegum 程序 ID
        private const val BUBBLEGUM_PROGRAM_ID = "BGUMAp9Gq7iTEuizy4pqaxsTyUCBK68MDfK752saRPUY"
        
        // Solana 系统程序
        private const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"
        private const val SPL_ACCOUNT_COMPRESSION_PROGRAM_ID = "cmtDvXumGCrqC1Age74AVPhSRVXJMd8PJS91L8KbNCK"
        private const val SPL_NOOP_PROGRAM_ID = "noopb9bkMVfRPU8AsbpTUg8AQkHtKwMYZiFUjNRtMmV"
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    /**
     * Mint cNFT
     * 
     * 当前实现：本地索引模式
     * - 生成本地 cNFT ID 用于索引
     * - 记忆已通过 Irys 存储在 Arweave 网络（真实去中心化存储）
     * - cNFT 链上索引待后续实现
     * 
     * 完整实现需要：
     * 1. 创建 Merkle Tree（一次性，约 0.01 SOL）
     * 2. 构建 Bubblegum mintV1 指令
     * 3. 通过 MWA 签名并发送交易
     * 4. 等待交易确认
     * 
     * @param metadata cNFT 元数据
     * @return cNFT ID 和 Mint 地址
     */
    suspend fun mintCNFT(metadata: CNFTMetadata): CNFTMintResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("🎨 创建 cNFT 索引: ${metadata.name}")
            
            // 检查钱包连接
            val session = walletManager.getSession()
                ?: throw IllegalStateException("未连接钱包，无法创建 cNFT 索引")
            
            val walletAddress = session.getPublicKeyBase58()
            
            // 生成本地索引 ID（用于本地存储和查询）
            // 格式：cNFT_<钱包地址前8位>_<时间戳>_<UUID>
            val localIndexId = "cNFT_${walletAddress.take(8)}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
            
            Timber.i("📝 本地索引 ID: $localIndexId")
            Timber.i("📦 元数据 URI: ${metadata.uri}")
            Timber.i("👛 所有者: $walletAddress")
            Timber.d("ℹ️ 注意：记忆数据已存储在 Irys/Arweave 网络，cNFT 链上索引待后续版本实现")
            
            CNFTMintResult(
                mintId = localIndexId,
                signature = "local_index_${System.currentTimeMillis()}",
                explorerUrl = "https://explorer.solana.com/address/$walletAddress", // 链接到钱包地址
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ cNFT 索引创建失败")
            throw CNFTException("cNFT 索引创建失败: ${e.message}", e)
        }
    }
    
    /**
     * 查询 cNFT
     * 
     * @param mintId cNFT Mint ID
     * @return cNFT 数据
     */
    suspend fun getCNFT(mintId: String): CNFTData? = withContext(Dispatchers.IO) {
        try {
            Timber.i("查询 cNFT: $mintId")
            
            // Phase 2.1: 模拟查询（后续通过 DAS API 实现）
            Timber.w("使用模拟 cNFT 查询")
            
            CNFTData(
                mintId = mintId,
                name = "Memory #${mintId.take(8)}",
                uri = "https://gateway.irys.xyz/mock",
                owner = walletManager.getSession()?.getPublicKeyBase58() ?: "unknown"
            )
        } catch (e: Exception) {
            Timber.e(e, "cNFT 查询失败")
            null
        }
    }
    
    /**
     * 获取用户所有的 cNFT
     * 
     * @return cNFT 列表
     */
    suspend fun getUserCNFTs(): List<CNFTData> = withContext(Dispatchers.IO) {
        try {
            val session = walletManager.getSession()
                ?: throw IllegalStateException("未连接钱包")
            
            Timber.i("获取用户所有 cNFT")
            
            // Phase 2.1: 模拟查询（后续通过 DAS API 实现）
            Timber.w("使用模拟 cNFT 列表查询")
            
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "获取 cNFT 列表失败")
            emptyList()
        }
    }
    
    /**
     * 创建迁移备份的 cNFT 元数据
     * 
     * @param walletAddress 钱包地址
     * @param irysUri Irys 上迁移包的 URI
     * @param timestamp 创建时间戳
     * @return cNFT 元数据
     */
    fun createMigrationMetadata(
        walletAddress: String,
        irysUri: String,
        timestamp: Long
    ): CNFTMetadata {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        
        return CNFTMetadata(
            name = "迁移备份 - ${walletAddress.take(8)} - $dateStr",
            symbol = "MEM-MIG",
            description = "MemoryAI 跨设备迁移备份凭证。此 NFT 记录了记忆的迁移备份信息。",
            uri = irysUri,
            sellerFeeBasisPoints = 0,
            creators = emptyList()
        )
    }
}

/**
 * cNFT 元数据
 */
data class CNFTMetadata(
    val name: String,
    val symbol: String = "MEM",
    val description: String,
    val uri: String, // Irys Transaction ID
    val sellerFeeBasisPoints: Int = 0,
    val creators: List<Creator> = emptyList()
) {
    data class Creator(
        val address: String,
        val share: Int,
        val verified: Boolean = false
    )
}

/**
 * cNFT Mint 结果
 */
data class CNFTMintResult(
    val mintId: String,
    val signature: String,
    val explorerUrl: String,
    val timestamp: Long
)

/**
 * cNFT 数据
 */
data class CNFTData(
    val mintId: String,
    val name: String,
    val uri: String,
    val owner: String
)

/**
 * cNFT 异常
 */
class CNFTException(message: String, cause: Throwable? = null) : Exception(message, cause)
