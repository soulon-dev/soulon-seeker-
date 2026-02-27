package com.soulon.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.soulon.app.irys.IrysClient
import com.soulon.app.i18n.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * StorageManager - 去中心化存储管理器
 * 
 * 功能特性：
 * - Irys 去中心化存储用于加密数据 blob
 * - Irys GraphQL 用于索引查询（零额外成本）
 * - AES-GCM-256 加密，密钥由 Android KeyStore 保护
 * - 无传统云服务依赖
 * 
 * 架构：
 * 1. 用户数据 -> 加密 (SeedVaultKeyManager)
 * 2. 加密数据 + Tags -> 上传到 Irys -> 获得永久交易 ID
 * 3. 通过 Irys GraphQL API 按 Tags 查询用户记忆
 * 4. 根据交易 ID 从 Irys Gateway 获取加密内容并解密
 * 
 * @property context Android 应用上下文
 * @property keyManager 密钥管理器实例
 */
class StorageManager(
    private val context: Context,
    private val keyManager: SeedVaultKeyManager,
    private val walletManager: com.soulon.app.wallet.WalletManager
) {
    
    // Solana 认证（使用钱包签名）
    private val solanaAuth by lazy {
        com.soulon.app.auth.SolanaAuth(context, walletManager)
    }
    
    // 会话密钥管理器（用于批量签名，避免频繁钱包确认）
    val sessionKeyManager by lazy {
        com.soulon.app.auth.SessionKeyManager(context)
    }
    
    // Irys 客户端
    private val irysClient by lazy {
        com.soulon.app.irys.IrysClient(
            solanaAuth, 
            walletManager, 
            httpClient, 
            gson,
            com.soulon.app.auth.BackendAuthManager.getInstance(context) // 🆕 注入 BackendAuthManager
        )
    }
    
    // 向量仓库（用于语义搜索）
    private val vectorRepository by lazy {
        com.soulon.app.rag.VectorRepository(context)
    }
    
    // Irys GraphQL 索引器（替代 cNFT 链上索引）
    private val irysIndexer by lazy {
        com.soulon.app.storage.IrysIndexer()
    }
    
    // SIWS 认证结果（缓存，避免重复登录）
    private var siwsAuth: com.soulon.app.auth.SolanaAuth.SIWSAuthResult? = null
    
    // 是否使用会话密钥（默认开启，可通过 setUseSessionKey 切换）
    private var useSessionKey: Boolean = false
    
    companion object {
        // Irys 网络配置 (Mainnet)
        private const val IRYS_NODE_URL = "https://node1.irys.xyz"
        private const val IRYS_UPLOAD_ENDPOINT = "$IRYS_NODE_URL/tx"
        
        // Solana 网络配置
        private const val SOLANA_RPC_URL = "https://api.mainnet-beta.solana.com"
        
        // 本地缓存
        private const val CACHE_DIR = "memory_cache"
        private const val INDEX_FILE = "memory_index.json"
        
        // 成本限制 (lamports)
        private const val MAX_CNFT_COST = 10000L // ~0.00001 SOL
        
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    // ==================== 会话密钥管理 ====================
    
    /**
     * 初始化并授权会话密钥（只需调用一次）
     * 
     * 流程：
     * 1. 生成会话密钥对
     * 2. 用户用主钱包签名授权消息
     * 3. 保存授权信息
     * 
     * 之后所有上传自动使用会话密钥，无需用户确认！
     * 
     * @param activityResultSender Activity 结果发送器
     * @return 是否授权成功
     */
    suspend fun initializeSessionKey(
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): Unit = withContext(Dispatchers.IO) {
        Timber.i("🔑 初始化会话密钥...")
        
        // 1. 生成会话密钥
        sessionKeyManager.generateSessionKey()
        
        // 2. 获取授权消息
        val authMessage = sessionKeyManager.buildAuthorizationMessage()
        Timber.d("授权消息长度: ${authMessage.size} 字节")
        
        // 3. 请求用户用主钱包签名（失败会抛出异常）
        Timber.i("📝 请求钱包签名授权会话密钥...")
        val signature = walletManager.signMessage(
            message = authMessage,
            activityResultSender = activityResultSender
        )
        
        // 4. 获取主钱包公钥
        val mainWalletPublicKey = walletManager.getPublicKey()
            ?: throw IllegalStateException(AppStrings.tr("无法获取钱包公钥", "Unable to get wallet public key"))
        
        // 5. 保存授权
        sessionKeyManager.authorizeSession(
            mainWalletPublicKey = mainWalletPublicKey,
            authorizationSignature = signature
        )
        
        Timber.i("🎉 会话密钥初始化成功！")
        Timber.i("   ✨ 后续上传将自动签名，无需钱包确认")
        Timber.i("   ⏰ 有效期: 7 天")
    }
    
    /**
     * 生成会话密钥授权消息（用于一站式连接）
     * 
     * @return 授权消息字节数组
     */
    fun prepareSessionKeyAuthMessage(): ByteArray {
        // 生成会话密钥
        sessionKeyManager.generateSessionKey()
        // 返回授权消息
        return sessionKeyManager.buildAuthorizationMessage()
    }
    
    /**
     * 使用已签名的授权完成会话密钥初始化（用于一站式连接）
     * 
     * @param mainWalletPublicKey 主钱包公钥
     * @param signature 授权签名
     */
    fun completeSessionKeyWithSignature(
        mainWalletPublicKey: ByteArray,
        signature: ByteArray
    ) {
        sessionKeyManager.authorizeSession(
            mainWalletPublicKey = mainWalletPublicKey,
            authorizationSignature = signature
        )
        
        Timber.i("🎉 会话密钥初始化成功！")
        Timber.i("   ✨ 后续上传将自动签名，无需钱包确认")
        Timber.i("   ⏰ 有效期: 7 天")
    }
    
    /**
     * 检查是否有有效的会话密钥
     */
    fun hasValidSessionKey(): Boolean {
        return sessionKeyManager.hasValidSession()
    }
    
    /**
     * 获取会话信息
     */
    fun getSessionInfo(): com.soulon.app.auth.SessionKeyManager.SessionInfo? {
        return sessionKeyManager.getSessionInfo()
    }
    
    /**
     * 撤销会话密钥
     */
    fun revokeSessionKey() {
        sessionKeyManager.revokeSession()
        Timber.i("✅ 会话密钥已撤销，后续上传将需要钱包确认")
    }
    
    /**
     * 设置是否使用会话密钥
     * 
     * @param enabled true=优先使用会话密钥, false=始终使用钱包签名
     */
    fun setUseSessionKey(enabled: Boolean) {
        useSessionKey = enabled
        Timber.i("会话密钥模式: ${if (enabled) "启用" else "禁用"}")
    }
    
    // ==================== 上传进度管理 ====================
    
    // 🔑 上传进度管理器
    val uploadProgressManager = com.soulon.app.storage.UploadProgressManager(context)
    
    // 🔑 支付管理器
    val paymentManager = com.soulon.app.payment.PaymentManager(context, walletManager)
    
    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    private fun getIndexFile(): File {
        val wallet = walletManager.getWalletAddress()
        val fileName = if (wallet.isNullOrBlank()) {
            INDEX_FILE
        } else {
            "memory_index_${com.soulon.app.wallet.WalletScope.scopeId(wallet)}.json"
        }
        return File(cacheDir, fileName)
    }
    
    /**
     * 存储记忆（带支付验证）
     * 
     * @param content 记忆内容（明文）
     * @param metadata 可选元数据
     * @param onPaymentRequired 需要支付时的回调，返回 true 表示用户确认支付
     * @return 存储结果，包含 cNFT ID 和成本
     */
    /**
     * 存储记忆（带支付确认和 Solana 钱包签名授权）
     * 
     * @param content 记忆内容
     * @param metadata 元数据
     * @param onPaymentRequired 支付确认回调
     * @param activityResultSender Activity 结果发送器（用于请求钱包签名）
     * @return 存储结果
     */
    suspend fun storeMemoryWithPayment(
        content: String,
        metadata: Map<String, String> = emptyMap(),
        onPaymentRequired: suspend (com.soulon.app.payment.PaymentManager.CostEstimate) -> Boolean,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): StorageResult = withContext(Dispatchers.IO) {
        val memoryId = java.util.UUID.randomUUID().toString()
        
        try {
            val plaintext = content.toByteArray()
            
            // Step 1: 估算费用
            Timber.d("Step 1: 估算费用...")
            val costEstimate = paymentManager.estimateStorageCost(plaintext.size)
            Timber.i("存储费用估算: ${costEstimate.formatSol(costEstimate.totalCost)}")
            
            // Step 2: 请求用户确认支付
            Timber.d("Step 2: 请求用户确认支付...")
            val userConfirmed = onPaymentRequired(costEstimate)
            
            if (!userConfirmed) {
                Timber.i("用户取消支付")
                return@withContext StorageResult(
                    success = false,
                    memoryId = null,
                    cnftId = null,
                    irysUri = null,
                    costLamports = 0,
                    message = "用户取消支付"
                )
            }
            
            // Step 3: 执行支付
            Timber.d("Step 3: 执行支付...")
            val paymentResult = paymentManager.executePayment(
                operation = "存储记忆",
                cost = costEstimate
            )
            
            when (paymentResult) {
                is com.soulon.app.payment.PaymentManager.PaymentResult.Success -> {
                    Timber.i("✅ 支付成功: ${paymentResult.transactionId}")
                    // 继续存储流程
                }
                is com.soulon.app.payment.PaymentManager.PaymentResult.Failed -> {
                    Timber.e("❌ 支付失败: ${paymentResult.reason}")
                    return@withContext StorageResult(
                        success = false,
                        memoryId = null,
                        cnftId = null,
                        irysUri = null,
                        costLamports = 0,
                        message = "支付失败: ${paymentResult.reason}"
                    )
                }
                is com.soulon.app.payment.PaymentManager.PaymentResult.InsufficientBalance -> {
                    val shortfallSol = paymentResult.shortfall.toDouble() / 1_000_000_000
                    Timber.e("❌ 余额不足: 缺少 $shortfallSol SOL")
                    return@withContext StorageResult(
                        success = false,
                        memoryId = null,
                        cnftId = null,
                        irysUri = null,
                        costLamports = 0,
                        message = "余额不足，请充值后重试"
                    )
                }
                is com.soulon.app.payment.PaymentManager.PaymentResult.Cancelled -> {
                    Timber.i("用户取消支付")
                    return@withContext StorageResult(
                        success = false,
                        memoryId = null,
                        cnftId = null,
                        irysUri = null,
                        costLamports = 0,
                        message = "用户取消支付"
                    )
                }
            }
            
            // Step 4: 继续存储流程（加密、上传、铸造）
            storeMemoryInternal(memoryId, content, metadata, costEstimate, activityResultSender)
            
        } catch (e: Exception) {
            Timber.e(e, "存储记忆失败")
            uploadProgressManager.markFailed(memoryId, e.message ?: "未知错误")
            StorageResult(
                success = false,
                memoryId = null,
                cnftId = null,
                irysUri = null,
                costLamports = 0,
                message = "存储失败: ${e.message}"
            )
        }
    }
    
    /**
     * 存储记忆（内部实现，不带支付验证）
     * 
     * @param content 记忆内容（明文）
     * @param metadata 可选元数据
     * @return 存储结果，包含 cNFT ID 和成本
     */
    /**
     * 存储记忆（带 Solana 钱包签名授权）
     * 
     * @param content 记忆内容
     * @param metadata 元数据
     * @param activityResultSender Activity 结果发送器（用于请求钱包签名）
     * @return 存储结果
     */
    suspend fun storeMemory(
        content: String,
        metadata: Map<String, String> = emptyMap(),
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): StorageResult = withContext(Dispatchers.IO) {
        val memoryId = java.util.UUID.randomUUID().toString()
        val plaintext = content.toByteArray()
        val costEstimate = paymentManager.estimateStorageCost(plaintext.size)
        
        storeMemoryInternal(memoryId, content, metadata, costEstimate, activityResultSender)
    }

    suspend fun storeMemoryWithId(
        memoryId: String,
        content: String,
        metadata: Map<String, String> = emptyMap(),
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): StorageResult = withContext(Dispatchers.IO) {
        val plaintext = content.toByteArray()
        val costEstimate = paymentManager.estimateStorageCost(plaintext.size)

        storeMemoryInternal(memoryId, content, metadata, costEstimate, activityResultSender)
    }
    
    /**
     * 内部存储实现
     */
    private suspend fun storeMemoryInternal(
        memoryId: String,
        content: String,
        metadata: Map<String, String>,
        costEstimate: com.soulon.app.payment.PaymentManager.CostEstimate,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): StorageResult = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val plaintext = content.toByteArray()
            
            // 初始化上传进度
            uploadProgressManager.startUpload(memoryId, plaintext.size.toLong())
            
            // Step 1: 使用钱包派生密钥加密数据（支持跨设备恢复）
            Timber.d("Step 1: 使用钱包派生密钥加密数据...")
            uploadProgressManager.updateEncrypting(memoryId)
            
            // 获取钱包地址用于派生加密密钥
            val walletAddress = walletManager.getWalletAddress()
                ?: throw IllegalStateException(AppStrings.tr("未连接钱包，无法加密数据", "Wallet not connected. Unable to encrypt data"))
            
            // 使用钱包派生密钥加密（相同钱包 = 相同密钥，支持跨设备解密）
            val encryptedData = keyManager.encryptWithWalletKey(plaintext, walletAddress)
            val dataHash = keyManager.generateHash(plaintext)
            
            // Step 2: 上传加密数据到 Irys（使用 Solana 钱包签名）
            Timber.d("Step 2: 上传到 Irys...")
            val encryptedBytes = encryptedData.toByteArray()
            val contentHash = dataHash.toHexString()
            uploadProgressManager.updateProgress(memoryId, 0)
            val irysUri = uploadToIrys(encryptedBytes, memoryId, contentHash, metadata, activityResultSender)
            
            // 上传完成，更新进度
            uploadProgressManager.updateProgress(memoryId, encryptedBytes.size.toLong())

            val transactionId = irysUri.substringAfterLast("/")
            
            // Step 3: 更新本地索引
            updateLocalIndex(
                MemoryIndex(
                    id = memoryId,
                    cnftId = transactionId,
                    irysUri = irysUri,
                    contentHash = dataHash.toHexString(),
                    timestamp = encryptedData.timestamp,
                    metadata = metadata,
                    storage = if (isBackendBlobUri(irysUri)) "BACKEND" else "IRYS"
                )
            )
            
            val elapsedTime = System.currentTimeMillis() - startTime
            Timber.i("✅ 记忆存储成功: ID=$memoryId, IrysTx=$transactionId, 耗时=${elapsedTime}ms")
            Timber.i("   • Irys URI: $irysUri")
            Timber.i("   • 成本: ${costEstimate.totalCost} lamports")
            
            // 标记上传完成
            uploadProgressManager.markCompleted(memoryId)
            
            // Step 6: 自动向量化（用于语义搜索）
            try {
                Timber.d("Step 6: 自动向量化...")
                val vectorized = vectorRepository.vectorizeAndSave(memoryId, content)
                if (vectorized) {
                    Timber.i("   • 向量化成功")
                } else {
                    Timber.w("   • 向量化失败（不影响存储）")
                }
            } catch (e: Exception) {
                Timber.w(e, "向量化失败（不影响存储）")
            }
            
            StorageResult(
                success = true,
                memoryId = memoryId,
                cnftId = transactionId,
                irysUri = irysUri,
                costLamports = costEstimate.totalCost,
                message = if (com.soulon.app.BuildConfig.BACKEND_BASE_URL.isNotBlank() &&
                    irysUri.startsWith(com.soulon.app.BuildConfig.BACKEND_BASE_URL) &&
                    irysUri.contains("/api/v1/memories/blob/")
                ) {
                    AppStrings.tr("记忆已安全存储到后端", "Memory stored on backend")
                } else {
                    AppStrings.tr("记忆已安全存储到 Arweave（Irys）", "Memory stored on Arweave (Irys)")
                }
            )
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            uploadProgressManager.markRetrying(memoryId)
            throw e
        } catch (e: Exception) {
            Timber.e(e, "存储记忆失败")
            
            // 标记上传失败
            uploadProgressManager.markFailed(memoryId, e.message ?: "未知错误")
            
            StorageResult(
                success = false,
                memoryId = null,
                cnftId = null,
                irysUri = null,
                costLamports = 0,
                message = "存储失败: ${e.message}"
            )
        }
    }
    
    /**
     * 检索记忆（使用钱包派生密钥解密，支持跨设备恢复）
     * 
     * @param memoryId 记忆 ID
     * @param activity 用于身份验证的 Activity（保留参数以兼容旧代码）
     * @return 解密后的记忆内容
     */
    suspend fun retrieveMemory(
        memoryId: String,
        activity: androidx.fragment.app.FragmentActivity
    ): String? = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            
            // Step 1: 从本地索引查找
            val index = loadLocalIndex().find { it.id == memoryId }
                ?: throw IllegalArgumentException(AppStrings.trf("未找到记忆: %s", "Memory not found: %s", memoryId))
            
            Timber.d("Step 1: 找到记忆索引: ${index.cnftId}")
            
            // Step 2: 从 Irys 下载加密数据
            Timber.d("Step 2: 从 Irys 下载数据...")
            val encryptedBytes = downloadFromIrys(index.irysUri)
            val encryptedData = EncryptedData.fromByteArray(encryptedBytes)
            
            // Step 3: 使用钱包派生密钥解密（跨设备可用）
            Timber.d("Step 3: 使用钱包派生密钥解密...")
            val walletAddress = walletManager.getWalletAddress()
                ?: throw IllegalStateException(AppStrings.tr("未连接钱包，无法解密数据", "Wallet not connected. Unable to decrypt data"))
            val plaintext = keyManager.decryptWithWalletKey(encryptedData, walletAddress)
            
            // Step 4: 验证完整性
            val hash = keyManager.generateHash(plaintext)
            require(hash.toHexString() == index.contentHash) {
                "数据完整性验证失败"
            }
            
            val elapsedTime = System.currentTimeMillis() - startTime
            Timber.i("记忆检索成功: ID=$memoryId, 耗时=${elapsedTime}ms")
            
            String(plaintext)
            
        } catch (e: Exception) {
            Timber.e(e, "检索记忆失败: $memoryId")
            null
        }
    }
    
    /**
     * 获取所有记忆索引
     */
    suspend fun getAllMemories(): List<MemoryIndex> = withContext(Dispatchers.IO) {
        loadLocalIndex()
    }
    
    /**
     * 🔢 批量向量化现有记忆
     * 
     * 遍历所有已存储的记忆，对未向量化的记忆进行向量化
     * 需要先解密记忆内容才能向量化
     * 
     * @param activity 用于显示生物识别提示的 Activity
     * @param onProgress 进度回调 (已处理数, 总数, 成功数)
     * @return 成功向量化的记忆数量
     */
    suspend fun vectorizeExistingMemories(
        activity: androidx.fragment.app.FragmentActivity,
        onProgress: ((Int, Int, Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        
        try {
            // 1. 获取所有记忆索引
            val allMemories = loadLocalIndex()
            val total = allMemories.size
            
            if (total == 0) {
                Timber.i("📭 没有记忆需要向量化")
                return@withContext 0
            }
            
            Timber.i("🔢 开始批量向量化 $total 条记忆...")
            
            // 2. 检查哪些记忆需要向量化
            val memoriesToVectorize = mutableListOf<MemoryIndex>()
            for (memory in allMemories) {
                val exists = vectorRepository.exists(memory.id)
                if (!exists) {
                    memoriesToVectorize.add(memory)
                }
            }
            
            if (memoriesToVectorize.isEmpty()) {
                Timber.i("✅ 所有记忆都已向量化")
                return@withContext 0
            }
            
            Timber.i("📋 需要向量化的记忆: ${memoriesToVectorize.size}/${total}")
            
            // 3. 解密并向量化
            val memoryIds = memoriesToVectorize.map { it.id }
            val decryptedContents = retrieveMemoriesBatch(memoryIds, activity)
            
            if (decryptedContents.isEmpty()) {
                Timber.w("❌ 没有成功解密任何记忆")
                return@withContext 0
            }
            
            Timber.i("🔓 成功解密 ${decryptedContents.size} 条记忆，开始向量化...")
            
            // 4. 批量向量化
            successCount = vectorRepository.vectorizeAndSaveBatch(decryptedContents)
            
            Timber.i("✅ 向量化完成: 成功=$successCount, 总数=${decryptedContents.size}")
            onProgress?.invoke(decryptedContents.size, total, successCount)
            
        } catch (e: Exception) {
            Timber.e(e, "批量向量化失败")
        }
        
        return@withContext successCount
    }
    
    /**
     * 获取向量化统计信息
     */
    suspend fun getVectorStats(): VectorStatsInfo = withContext(Dispatchers.IO) {
        try {
            val allMemories = loadLocalIndex()
            val vectorStats = vectorRepository.getStats()
            
            VectorStatsInfo(
                totalMemories = allMemories.size,
                vectorizedMemories = vectorStats.totalVectors,
                unvectorizedMemories = allMemories.size - vectorStats.totalVectors,
                vectorDimension = vectorStats.vectorDimension
            )
        } catch (e: Exception) {
            Timber.e(e, "获取向量统计失败")
            VectorStatsInfo(0, 0, 0, 0)
        }
    }
    
    /**
     * 🔐 批量解密记忆（使用钱包派生密钥，支持跨设备恢复）
     * 
     * 使用钱包派生密钥批量解密所有指定的记忆
     * 无需每条记忆都进行生物识别验证，因为钱包连接已验证身份
     * 
     * @param memoryIds 需要解密的记忆 ID 列表
     * @param activity 用于显示提示的 Activity（保留参数以兼容旧代码）
     * @return 解密结果映射：memoryId -> 解密内容（解密失败的记忆不包含在结果中）
     */
    suspend fun retrieveMemoriesBatch(
        memoryIds: List<String>,
        activity: androidx.fragment.app.FragmentActivity
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()
        
        if (memoryIds.isEmpty()) {
            Timber.w("批量解密：记忆 ID 列表为空")
            return@withContext results
        }
        
        try {
            val startTime = System.currentTimeMillis()
            Timber.i("🔐 开始批量解密 ${memoryIds.size} 条记忆...")
            
            // Step 1: 获取钱包地址用于派生解密密钥
            val walletAddress = walletManager.getWalletAddress()
                ?: throw IllegalStateException(AppStrings.tr("未连接钱包，无法解密数据", "Wallet not connected. Unable to decrypt data"))
            
            Timber.i("✅ 钱包已连接，使用钱包派生密钥解密...")
            
            // Step 2: 加载索引
            val allIndices = loadLocalIndex()
            val indexMap = allIndices.associateBy { it.id }
            
            // Step 3: 批量解密
            var successCount = 0
            var failCount = 0
            
            for (memoryId in memoryIds) {
                try {
                    val index = indexMap[memoryId]
                    if (index == null) {
                        Timber.w("未找到记忆索引: $memoryId")
                        failCount++
                        continue
                    }
                    
                    // 从 Irys 下载加密数据
                    val encryptedBytes = downloadFromIrys(index.irysUri)
                    val encryptedData = EncryptedData.fromByteArray(encryptedBytes)
                    
                    // 使用钱包派生密钥解密（跨设备可用）
                    val plaintext = keyManager.decryptWithWalletKey(encryptedData, walletAddress)
                    
                    // 验证完整性
                    val hash = keyManager.generateHash(plaintext)
                    if (hash.toHexString() != index.contentHash) {
                        Timber.e("记忆完整性验证失败: $memoryId")
                        failCount++
                        continue
                    }
                    
                    results[memoryId] = String(plaintext)
                    successCount++
                    Timber.d("✅ 解密成功: $memoryId")
                    
                } catch (e: Exception) {
                    Timber.e(e, "解密记忆失败: $memoryId")
                    failCount++
                }
            }
            
            val elapsedTime = System.currentTimeMillis() - startTime
            Timber.i("🔓 批量解密完成: 成功=$successCount, 失败=$failCount, 耗时=${elapsedTime}ms")
            
            results
            
        } catch (e: IllegalStateException) {
            Timber.e(e, "批量解密失败：钱包未连接")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "批量解密失败")
            results
        }
    }
    
    /**
     * 从 Irys 网络同步记忆
     * 
     * 在钱包连接成功后调用，恢复该钱包上传的所有记忆
     * 
     * @param walletPublicKey 钱包公钥（Base58 格式）
     * @return 同步的记忆数量
     */
    suspend fun syncMemoriesFromNetwork(walletPublicKey: String): Int = withContext(Dispatchers.IO) {
        try {
            Timber.i("🔄 开始从 Irys 同步记忆...")
            Timber.d("钱包地址: $walletPublicKey")
            
            // 1. 查询该钱包的所有 DataItems
            val remoteItems = irysClient.queryByOwner(walletPublicKey)
            
            if (remoteItems.isEmpty()) {
                Timber.i("📭 该钱包没有已存储的记忆")
                return@withContext 0
            }
            
            Timber.i("📥 发现 ${remoteItems.size} 条远程记忆")
            
            // 2. 检查是否有 onboarding 类型的记忆，如果有则恢复 onboarding 完成状态
            val onboardingItems = remoteItems.filter { item -> 
                item.tags["type"] == "onboarding" 
            }
            
            if (onboardingItems.isNotEmpty()) {
                Timber.i("📋 发现 ${onboardingItems.size} 条 onboarding 记忆，恢复完成状态")
                
                // 检查本地 onboarding 状态
                val isLocalCompleted = com.soulon.app.onboarding.OnboardingState.isCompleted(context)
                
                if (!isLocalCompleted) {
                    // 恢复 onboarding 完成状态
                    com.soulon.app.onboarding.OnboardingState.markCompleted(context)
                    Timber.i("✅ Onboarding 状态已从网络恢复")
                    
                    // 尝试恢复评估数据
                    try {
                        restoreOnboardingEvaluations(onboardingItems)
                    } catch (e: Exception) {
                        Timber.w(e, "恢复评估数据失败，但 onboarding 状态已恢复")
                    }
                }
            }
            
            // 3. 获取本地已有记忆的 ID
            val localMemories = loadLocalIndex()
            val localIds = localMemories.map { it.irysUri }.toSet()
            
            // 4. 找出需要同步的记忆（本地没有的）
            val newItems = remoteItems.filter { it.uri !in localIds }
            
            if (newItems.isEmpty()) {
                Timber.i("✅ 本地记忆已是最新")
                return@withContext 0
            }
            
            Timber.i("📥 需要同步 ${newItems.size} 条新记忆")
            
            // 5. 将远程记忆添加到本地索引
            val newMemories = newItems.mapNotNull { item ->
                try {
                    MemoryIndex(
                        id = item.id,
                        cnftId = item.cnftId,
                        irysUri = item.uri,
                        contentHash = item.contentHash,
                        timestamp = item.timestamp,
                        metadata = item.tags
                    )
                } catch (e: Exception) {
                    Timber.w(e, "解析记忆失败: ${item.id}")
                    null
                }
            }
            
            // 6. 合并并保存索引
            val mergedIndex = (localMemories + newMemories)
                .distinctBy { it.id }
                .sortedByDescending { it.timestamp }
            
            saveLocalIndex(mergedIndex)
            
            Timber.i("🎉 同步完成！共 ${newMemories.size} 条新记忆")
            
            return@withContext newMemories.size
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 同步记忆失败")
            return@withContext 0
        }
    }
    
    /**
     * 恢复 onboarding 评估数据
     * 
     * 从网络同步的 onboarding 记忆中提取信息，恢复评估记录
     */
    private fun restoreOnboardingEvaluations(onboardingItems: List<com.soulon.app.irys.IrysClient.IrysDataItem>) {
        try {
            val evaluationStorage = com.soulon.app.onboarding.OnboardingEvaluationStorage(context)
            
            for (item in onboardingItems) {
                val questionIdStr = item.tags["question_id"] ?: continue
                val questionId = questionIdStr.toIntOrNull() ?: continue
                
                // 检查本地是否已有该评估
                val existingEval = evaluationStorage.getEvaluation(questionId)
                if (existingEval != null) {
                    // 更新关联的记忆 ID
                    val updatedEval = existingEval.addRelatedMemory(item.id)
                    evaluationStorage.saveEvaluation(updatedEval)
                    Timber.d("更新评估: 问题 $questionId, 添加记忆 ${item.id}")
                } else {
                    // 创建新的评估记录（使用默认值，因为原始答案需要解密才能获取）
                    val evaluation = com.soulon.app.onboarding.OnboardingEvaluation(
                        questionId = questionId,
                        sincerityScore = 0.5f,  // 默认中等
                        confidenceScore = 0.5f,
                        originalAnswer = "[从网络恢复]",  // 原始答案需要解密记忆才能获取
                        relatedMemoryIds = item.id,
                        verificationCount = 0,
                        contradictionCount = 0,
                        lastUpdated = item.timestamp,
                        evaluationNotes = "从网络同步恢复的评估记录"
                    )
                    evaluationStorage.saveEvaluation(evaluation)
                    Timber.d("恢复评估: 问题 $questionId")
                }
            }
            
            Timber.i("✅ 评估数据恢复完成，共 ${onboardingItems.size} 条")
            
        } catch (e: Exception) {
            Timber.e(e, "恢复评估数据失败")
            throw e
        }
    }
    
    /**
     * 保存本地索引
     */
    private fun saveLocalIndex(memories: List<MemoryIndex>) {
        try {
            getIndexFile().writeText(gson.toJson(memories))
            Timber.d("本地索引已保存: ${memories.size} 条记忆")
        } catch (e: Exception) {
            Timber.e(e, "保存本地索引失败")
        }
    }
    
    /**
     * 上传数据到 Irys
     */
    /**
     * 确保 SIWS 认证（一次性登录）
     * 
     * @param activityResultSender Activity 结果发送器
     */
    private suspend fun ensureSIWSAuth(
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): com.soulon.app.auth.SolanaAuth.SIWSAuthResult {
        // 如果已经认证且未过期，直接返回
        siwsAuth?.let { auth ->
            // 检查认证是否过期（24 小时有效期）
            val issuedAtMillis = try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .parse(auth.issuedAt)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
            
            val authAge = System.currentTimeMillis() - issuedAtMillis
            val maxAuthAge = 24 * 60 * 60 * 1000L // 24 小时（毫秒）
            
            if (authAge < maxAuthAge && issuedAtMillis > 0) {
                Timber.d("使用缓存的 SIWS 认证 (剩余有效期: ${(maxAuthAge - authAge) / 3600000}小时)")
                return auth
            } else {
                Timber.i("SIWS 认证已过期或无效，需要重新认证")
                siwsAuth = null
            }
        }
        
        // 执行 SIWS 登录
        Timber.i("🔐 执行 SIWS (Sign In With Solana) 登录...")
        val auth = solanaAuth.signInWithSolana(
            domain = "soulon.top",
            statement = "授权 Soulon 加密并存储你的记忆数据到 Arweave",
            activityResultSender = activityResultSender
        )
        
        // 缓存认证结果
        siwsAuth = auth
        Timber.i("✅ SIWS 登录成功")
        
        return auth
    }
    
    /**
     * 上传数据到 Irys（去中心化存储）
     * 
     * 🚀 使用 Solana 钱包签名 + SIWS 认证
     * 
     * 流程：
     * 1. 确保用户已通过 SIWS 登录
     * 2. 创建 DataItem
     * 3. 请求用户授权该 DataItem（签名授权消息）
     * 4. 上传 DataItem 和授权签名到 Irys
     * 5. Irys 验证签名和授权
     * 
     * @param data 要上传的数据
     * @param memoryId 记忆 ID（用于进度追踪）
     * @param activityResultSender Activity 结果发送器（用于请求签名）
     * @return Irys URI
     * @throws Exception 上传失败时抛出异常
     */
    suspend fun uploadToIrys(
        data: ByteArray,
        memoryId: String,
        contentHash: String,
        metadata: Map<String, String> = emptyMap(),
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
        allowBackendFallback: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        try {
            Timber.i("🚀 开始 Irys 上传: 数据大小=${data.size} 字节, Memory ID=$memoryId")
            
            // 检查是否可以使用会话密钥（无需用户确认！）
            if (useSessionKey && sessionKeyManager.hasValidSession()) {
                Timber.i("✨ 使用会话密钥上传（自动签名，无需确认）")
                return@withContext uploadWithSessionKey(data, memoryId, contentHash, metadata)
            }
            
            // 回退到钱包签名
            Timber.i("🔐 使用钱包签名上传（需要用户确认）")
            return@withContext uploadWithWalletSignature(data, memoryId, contentHash, metadata, activityResultSender)
            
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (allowBackendFallback && isIrysInsufficientBalanceError(msg)) {
                val walletAddress = walletManager.getWalletAddress()
                if (!walletAddress.isNullOrBlank()) {
                    val backendUri = com.soulon.app.data.BackendApiClient.getInstance(context).storeMemoryBlob(
                        walletAddress = walletAddress,
                        memoryId = memoryId,
                        encryptedBytes = data,
                        contentHash = contentHash,
                        metadata = metadata
                    )
                    if (!backendUri.isNullOrBlank()) {
                        Timber.i("✅ 已回退到后端存储记忆: $backendUri")
                        return@withContext backendUri
                    }
                }
            }
            Timber.e(e, "❌ Irys 上传失败")
            throw Exception(
                AppStrings.trf("Irys 上传失败: %s", "Irys upload failed: %s", e.message ?: ""),
                e
            )
        }
    }

    suspend fun migrateBackendStoredMemories(
        maxCount: Int = 20
    ): Int = withContext(Dispatchers.IO) {
        if (!sessionKeyManager.hasValidSession()) return@withContext 0

        val all = loadLocalIndex()
        val pending = all.filter { it.storage == "BACKEND" || isBackendBlobUri(it.irysUri) }
            .sortedBy { it.timestamp }
            .take(maxCount)

        if (pending.isEmpty()) return@withContext 0

        var migrated = 0
        for (index in pending) {
            try {
                val encryptedBytes = downloadFromIrys(index.irysUri)
                val newUri = uploadWithSessionKey(encryptedBytes, index.id, index.contentHash, index.metadata)
                val txId = newUri.substringAfterLast("/")

                val updated = index.copy(
                    cnftId = txId,
                    irysUri = newUri,
                    storage = "IRYS",
                    migratedFrom = index.irysUri,
                    migratedAt = System.currentTimeMillis()
                )
                upsertLocalIndex(updated)
                com.soulon.app.data.BackendApiClient.getInstance(context).markMemoryBlobMigrated(
                    memoryId = index.id,
                    irysId = txId,
                    deleteBlob = true
                )
                migrated++
            } catch (e: Exception) {
                Timber.w(e, "后端暂存记忆迁移失败: ${index.id}")
                val msg = e.message.orEmpty()
                if (isIrysInsufficientBalanceError(msg)) {
                    break
                }
            }
        }

        migrated
    }
    
    /**
     * 使用会话密钥上传（自动签名，无需用户确认）
     */
    private suspend fun uploadWithSessionKey(
        data: ByteArray,
        memoryId: String,
        contentHash: String,
        metadata: Map<String, String>
    ): String {
        val walletAddress = walletManager.getWalletAddress()
        val memoryType = metadata["type"] ?: metadata["Type"]

        // 创建 tags（Main-Wallet 由 IrysClient 自动添加）
        val baseTags = mutableListOf(
            com.soulon.app.storage.ArweaveDataItem.Tag("Content-Type", "application/octet-stream"),
            com.soulon.app.storage.ArweaveDataItem.Tag("App-Name", "Soulon"),
            com.soulon.app.storage.ArweaveDataItem.Tag("App-Version", "2.1.0"),
            com.soulon.app.storage.ArweaveDataItem.Tag("Memory-ID", memoryId),
            com.soulon.app.storage.ArweaveDataItem.Tag("Content-Hash", contentHash),
            com.soulon.app.storage.ArweaveDataItem.Tag("Encrypted", "true"),
            com.soulon.app.storage.ArweaveDataItem.Tag("Timestamp", System.currentTimeMillis().toString())
        )

        if (!walletAddress.isNullOrBlank()) {
            baseTags.add(com.soulon.app.storage.ArweaveDataItem.Tag("Wallet-Address", walletAddress))
        }
        if (!memoryType.isNullOrBlank()) {
            baseTags.add(com.soulon.app.storage.ArweaveDataItem.Tag("Type", memoryType))
            baseTags.add(com.soulon.app.storage.ArweaveDataItem.Tag("Memory-Type", memoryType))
        }
        
        // 使用会话密钥上传
        val uri = irysClient.uploadWithSessionKey(
            data = data,
            sessionKeyManager = sessionKeyManager,
            tags = baseTags
        )
        
        Timber.i("🎉 Irys 上传成功！（会话密钥）")
        Timber.i("   📍 URI: $uri")
        Timber.i("   📦 大小: ${data.size} 字节")
        Timber.i("   🔑 认证: 会话密钥（自动签名）")
        
        return uri
    }
    
    /**
     * 使用钱包签名上传（需要用户确认）
     */
    private suspend fun uploadWithWalletSignature(
        data: ByteArray,
        memoryId: String,
        contentHash: String,
        metadata: Map<String, String>,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): String {
        val walletAddress = walletManager.getWalletAddress()
            ?: throw IllegalStateException(AppStrings.tr("未连接钱包，无法上传", "Wallet not connected. Unable to upload"))

        val priceLamports = irysClient.getPriceLamports(data.size)
        val bundlerBalanceLamports = irysClient.getBalanceLamports(walletAddress)
        if (bundlerBalanceLamports < priceLamports) {
            val bundlerAddress = irysClient.getBundlerAddressSolana()
            throw IllegalStateException(
                AppStrings.trf(
                    "Irys 余额不足：需要 %d lamports，当前 %d lamports。请向 Irys Bundler 地址充值后重试：%s",
                    "Insufficient Irys balance: need %d lamports, have %d lamports. Fund the Irys bundler address then retry: %s",
                    priceLamports,
                    bundlerBalanceLamports,
                    bundlerAddress
                )
            )
        }

        // 步骤 1: 确保 SIWS 认证
        val auth = ensureSIWSAuth(activityResultSender)
        
        // 步骤 2: 获取 Solana 钱包公钥
        val publicKey = walletManager.getPublicKey()
            ?: throw IllegalStateException(AppStrings.tr("无法获取钱包公钥", "Unable to get wallet public key"))
        
        if (publicKey.size != 32) {
            throw IllegalStateException(
                AppStrings.trf(
                    "Solana 公钥必须是 32 字节，实际: %d",
                    "Solana public key must be 32 bytes, got: %d",
                    publicKey.size
                )
            )
        }
        Timber.d("✅ Solana 钱包公钥: ${publicKey.size} 字节")
        
        // 钱包公钥的 hex 表示（用于 Main-Wallet 标签）
        val walletHex = publicKey.joinToString("") { "%02x".format(it) }
        val memoryType = metadata["type"] ?: metadata["Type"]
        
        // 步骤 3: 创建 tags
        val tags = mutableListOf(
            com.soulon.app.storage.ArweaveDataItem.Tag("Content-Type", "application/octet-stream"),
            com.soulon.app.storage.ArweaveDataItem.Tag("App-Name", "Soulon"),
            com.soulon.app.storage.ArweaveDataItem.Tag("App-Version", "2.1.0"),
            com.soulon.app.storage.ArweaveDataItem.Tag("Memory-ID", memoryId),
            com.soulon.app.storage.ArweaveDataItem.Tag("Content-Hash", contentHash),
            com.soulon.app.storage.ArweaveDataItem.Tag("Main-Wallet", walletHex),
            com.soulon.app.storage.ArweaveDataItem.Tag("Encrypted", "true"),
            com.soulon.app.storage.ArweaveDataItem.Tag("Timestamp", System.currentTimeMillis().toString()),
            com.soulon.app.storage.ArweaveDataItem.Tag("SIWS-Nonce", auth.nonce)
        )

        if (!walletAddress.isNullOrBlank()) {
            tags.add(com.soulon.app.storage.ArweaveDataItem.Tag("Wallet-Address", walletAddress))
        }
        if (!memoryType.isNullOrBlank()) {
            tags.add(com.soulon.app.storage.ArweaveDataItem.Tag("Type", memoryType))
            tags.add(com.soulon.app.storage.ArweaveDataItem.Tag("Memory-Type", memoryType))
        }
        
        // 步骤 4: 使用智能上传 (Smart Upload)
        Timber.i("📤 开始智能上传...")
        val uri = irysClient.uploadSmart(
            memoryId = memoryId,
            data = data,
            tags = tags
        )
        
        Timber.i("🎉 上传成功！")
        Timber.i("   📍 URI: $uri")
        Timber.i("   📦 大小: ${data.size} 字节")
        
        return uri
    }
    
    /**
     * 私有方法：上传到 Irys（用于不需要 Activity 的场景）
     * 
     * ⚠️ 这个方法会抛出异常，因为需要 Activity 来请求钱包签名
     */
    private suspend fun uploadToIrys(data: ByteArray, memoryId: String): String {
        throw IllegalStateException(
            "Irys 上传需要 Activity 来请求用户签名。" +
            "请使用 uploadToIrys(data, memoryId, activityResultSender) 方法。"
        )
    }
    
    /**
     * 从 Irys 下载数据
     */
    private suspend fun downloadFromIrys(uri: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            // 处理本地文件 URI（测试模式）
            if (uri.startsWith("file://")) {
                val file = File(uri.removePrefix("file://"))
                return@withContext file.readBytes()
            }
            
            val requestBuilder = Request.Builder()
                .url(uri)
                .get()

            val backendBaseUrl = com.soulon.app.BuildConfig.BACKEND_BASE_URL
            if (backendBaseUrl.isNotBlank() && uri.startsWith(backendBaseUrl) && uri.contains("/api/v1/memories/blob/")) {
                val token = com.soulon.app.auth.BackendAuthManager.getInstance(context).getAccessToken()
                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
            }

            val request = requestBuilder.build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw Exception(AppStrings.trf("Irys 下载失败: %d", "Irys download failed: %d", response.code))
            }
            
            response.body?.bytes() ?: throw Exception(AppStrings.tr("Irys 响应为空", "Empty Irys response"))
            
        } catch (e: Exception) {
            Timber.e(e, "Irys 下载失败: $uri")
            throw e
        }
    }
    
    /**
     * 估算 cNFT Mint 成本
     */
    private fun estimateCNFTCost(): Long {
        // State Compression cNFT 的实际成本非常低
        // 典型成本: ~0.000005 SOL (5000 lamports)
        return 5000L
    }
    
    /**
     * Mint cNFT 到 Solana
     * 
     * 注意：这是简化实现。实际生产环境需要：
     * 1. 集成 Mobile Wallet Adapter 获取签名
     * 2. 调用 Solana State Compression 程序
     * 3. 处理交易确认和重试
     */
    /**
     * 生成本地索引 ID
     * 
     * 注意：原 cNFT 铸造功能已被 Irys GraphQL 索引替代
     * 
     * 原因：
     * - Irys 标签系统提供免费、去中心化的索引功能
     * - 无需支付 Solana 交易费用
     * - 查询更快，支持复杂过滤
     * 
     * 如需链上不可变证明，可使用 SoulboundManager 铸造 SBT
     * 
     * @see com.soulon.app.storage.IrysIndexer
     * @see com.soulon.app.sovereign.SoulboundManager
     */
    @Deprecated(
        message = "使用 IrysIndexer 进行索引查询，或 SoulboundManager 进行链上证明",
        replaceWith = ReplaceWith("irysIndexer.queryMemories(walletAddress)")
    )
    private suspend fun mintCNFT(metadata: CNFTMetadata): String = withContext(Dispatchers.IO) {
        // 生成本地索引 ID（用于向后兼容）
        // 使用元数据名称和时间戳生成唯一 ID
        val nameHash = metadata.name.hashCode().toString(16).takeLast(8)
        val localIndexId = "idx_${nameHash}_${System.currentTimeMillis()}"
        
        Timber.d("生成本地索引 ID: $localIndexId")
        Timber.d("  名称: ${metadata.name}")
        Timber.d("  描述: ${metadata.description.take(50)}...")
        
        // 注意：实际索引通过 Irys GraphQL 完成
        // 使用 irysIndexer.queryMemories(walletAddress) 查询记忆
        
        localIndexId
    }
    
    /**
     * 更新本地索引
     */
    private fun updateLocalIndex(newIndex: MemoryIndex) {
        upsertLocalIndex(newIndex)
    }

    private fun upsertLocalIndex(newIndex: MemoryIndex) {
        val currentIndex = loadLocalIndex().toMutableList()
        val idx = currentIndex.indexOfFirst { it.id == newIndex.id }
        if (idx >= 0) {
            currentIndex[idx] = newIndex
        } else {
            currentIndex.add(newIndex)
        }
        getIndexFile().writeText(gson.toJson(currentIndex))
        Timber.d("本地索引已更新: ${currentIndex.size} 条记忆")
    }

    private fun isBackendBlobUri(uri: String): Boolean {
        val base = com.soulon.app.BuildConfig.BACKEND_BASE_URL
        if (base.isBlank()) return false
        return uri.startsWith(base) && uri.contains("/api/v1/memories/blob/")
    }

    private fun isIrysInsufficientBalanceError(msg: String): Boolean {
        val s = msg.lowercase()
        if (s.contains("irys 余额不足") || s.contains("insufficient irys balance")) return true
        if (s.contains("http 402")) return true
        if (s.contains("insufficient") && s.contains("balance")) return true
        if (s.contains("not enough") && s.contains("balance")) return true
        return false
    }
    
    /**
     * 加载本地索引
     */
    private fun loadLocalIndex(): List<MemoryIndex> {
        val file = getIndexFile()
        return if (file.exists()) {
            try {
                val json = file.readText()
                gson.fromJson(json, Array<MemoryIndex>::class.java).toList()
            } catch (e: Exception) {
                Timber.e(e, "加载本地索引失败")
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    
    /**
     * 清理缓存（用于测试）
     */
    fun clearCache() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        Timber.i("缓存已清理")
    }
    
    // ==================== Irys GraphQL 索引查询 ====================
    
    /**
     * 从 Irys 查询用户的所有记忆索引
     * 
     * 通过 GraphQL API 按钱包地址和 App 标签查询
     * 
     * @param walletAddress 钱包地址
     * @param limit 返回数量限制
     * @return 记忆索引列表
     */
    suspend fun queryMemoriesFromIrys(
        walletAddress: String,
        limit: Int = 100
    ): List<com.soulon.app.storage.MemoryIndex> = withContext(Dispatchers.IO) {
        Timber.i("📡 从 Irys 查询记忆索引: $walletAddress")
        irysIndexer.queryMemories(walletAddress, limit)
    }
    
    /**
     * 从 Irys 查询特定类型的记忆
     * 
     * @param walletAddress 钱包地址
     * @param memoryType 记忆类型（questionnaire, chat, manual）
     * @param limit 返回数量限制
     * @return 记忆索引列表
     */
    suspend fun queryMemoriesByType(
        walletAddress: String,
        memoryType: String,
        limit: Int = 50
    ): List<com.soulon.app.storage.MemoryIndex> = withContext(Dispatchers.IO) {
        Timber.i("📡 从 Irys 查询 $memoryType 类型记忆")
        irysIndexer.queryMemoriesByType(walletAddress, memoryType, limit)
    }
    
    /**
     * 根据交易 ID 获取并解密记忆内容（使用钱包派生密钥）
     * 
     * @param transactionId Irys 交易 ID
     * @param activity 用于显示提示的 Activity（保留参数以兼容旧代码）
     * @return 解密后的记忆内容
     */
    suspend fun retrieveMemoryFromIrys(
        transactionId: String,
        activity: androidx.fragment.app.FragmentActivity
    ): String? = withContext(Dispatchers.IO) {
        try {
            Timber.i("📥 从 Irys 获取记忆: $transactionId")
            
            // 1. 获取加密内容
            val encryptedBytes = irysIndexer.fetchMemoryContent(transactionId)
                ?: throw Exception(AppStrings.tr("无法获取记忆内容", "Unable to load memory content"))
            
            // 2. 使用钱包派生密钥解密（跨设备可用）
            val walletAddress = walletManager.getWalletAddress()
                ?: throw IllegalStateException(AppStrings.tr("未连接钱包，无法解密数据", "Wallet not connected. Unable to decrypt data"))
            val encryptedData = EncryptedData.fromByteArray(encryptedBytes)
            val plaintext = keyManager.decryptWithWalletKey(encryptedData, walletAddress)
            
            Timber.i("✅ 记忆解密成功")
            String(plaintext)
        } catch (e: Exception) {
            Timber.e(e, "❌ 获取记忆失败")
            null
        }
    }
    
    /**
     * 查询钱包的所有数据（用于完整数据恢复）
     * 
     * @param walletAddress 钱包地址
     * @return 数据项列表
     */
    suspend fun queryMemoriesByWallet(walletAddress: String): List<IrysDataItem> = withContext(Dispatchers.IO) {
        try {
            Timber.i("📡 查询钱包的所有数据: $walletAddress")
            
            // 从 Irys 查询所有记忆
            val remoteMemories = irysIndexer.queryMemories(walletAddress, limit = 500)
            
            remoteMemories.map { memory ->
                IrysDataItem(
                    transactionId = memory.transactionId,
                    irysUri = "https://gateway.irys.xyz/${memory.transactionId}",
                    timestamp = memory.timestamp,
                    metadata = memory.tags
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ 查询失败")
            emptyList()
        }
    }

    /**
     * 通过 GraphQL（App-Name + Main-Wallet）查询该钱包的所有 DataItems
     *
     * 用于跨设备恢复（与 uploadWithWalletSignature 的 tags 体系一致）
     */
    suspend fun queryAllDataItemsByOwner(walletAddress: String): List<IrysClient.IrysDataItem> =
        withContext(Dispatchers.IO) {
            irysClient.queryByOwner(walletAddress)
        }

    /**
     * 查询该钱包的人格数据 DataItems（Type=PersonaData）
     */
    suspend fun queryPersonaDataItems(walletAddress: String): List<IrysClient.IrysDataItem> =
        withContext(Dispatchers.IO) {
            irysClient.queryPersonaData(walletAddress)
        }
    
    /**
     * 下载 Irys 数据（不解密，返回加密数据）
     * 
     * 用于获取加密内容，解密需要单独调用 retrieveMemory 并经过硬件授权
     * 
     * @param irysUri Irys 数据 URI
     * @return 加密的数据字节，失败返回 null
     */
    suspend fun downloadEncrypted(irysUri: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            Timber.d("📥 下载加密数据: $irysUri")
            val encryptedBytes = downloadFromIrys(irysUri)
            Timber.d("✅ 下载成功: ${encryptedBytes.size} 字节")
            encryptedBytes
        } catch (e: Exception) {
            Timber.w(e, "下载失败: $irysUri")
            null
        }
    }
    
    /**
     * 使用钱包派生密钥解密数据（支持跨设备恢复）
     * 
     * @param encryptedBytes 加密的数据字节
     * @param activity 用于显示提示的 Activity（保留参数以兼容旧代码）
     * @return 解密后的内容，失败返回 null
     */
    suspend fun decryptWithHardwareAuth(
        encryptedBytes: ByteArray,
        activity: android.app.Activity
    ): String? = withContext(Dispatchers.IO) {
        try {
            Timber.d("🔐 使用钱包派生密钥解密...")
            
            // 获取钱包地址
            val walletAddress = walletManager.getWalletAddress()
                ?: throw IllegalStateException(AppStrings.tr("未连接钱包，无法解密数据", "Wallet not connected. Unable to decrypt data"))
            
            // 解析加密数据结构
            val encryptedData = EncryptedData.fromByteArray(encryptedBytes)
            
            // 使用钱包派生密钥解密（跨设备可用）
            val plaintext = keyManager.decryptWithWalletKey(encryptedData, walletAddress)
            
            Timber.d("✅ 钱包密钥解密成功: ${plaintext.size} 字节")
            String(plaintext)
        } catch (e: Exception) {
            Timber.w(e, "钱包密钥解密失败")
            null
        }
    }
    
    /**
     * 同步本地索引与 Irys 链上数据
     * 
     * 用于跨设备同步或恢复本地数据
     * 
     * @param walletAddress 钱包地址
     * @return 同步的记忆数量
     */
    suspend fun syncWithIrys(walletAddress: String): Int = withContext(Dispatchers.IO) {
        try {
            Timber.i("🔄 开始同步 Irys 索引...")
            
            // 从 Irys 查询所有记忆
            val remoteMemories = irysIndexer.queryMemories(walletAddress)
            
            // 加载本地索引
            val localIndex = loadLocalIndex().toMutableList()
            val localIds = localIndex.map { it.irysUri }.toSet()
            
            // 找出需要同步的记忆
            var syncCount = 0
            for (memory in remoteMemories) {
                val irysUri = "https://gateway.irys.xyz/${memory.transactionId}"
                if (irysUri !in localIds) {
                    // 添加到本地索引
                    localIndex.add(
                        MemoryIndex(
                            id = memory.transactionId,
                            cnftId = memory.transactionId, // 使用交易 ID 作为标识
                            irysUri = irysUri,
                            contentHash = "", // 需要下载后验证
                            timestamp = memory.timestamp,
                            metadata = memory.tags
                        )
                    )
                    syncCount++
                }
            }
            
            // 保存更新后的索引
            if (syncCount > 0) {
                saveLocalIndex(localIndex)
                Timber.i("✅ 同步完成，新增 $syncCount 条记忆")
            } else {
                Timber.i("✅ 本地索引已是最新")
            }
            
            syncCount
        } catch (e: Exception) {
            Timber.e(e, "❌ 同步失败")
            0
        }
    }
}

/**
 * 向量统计信息
 */
data class VectorStatsInfo(
    val totalMemories: Int,
    val vectorizedMemories: Int,
    val unvectorizedMemories: Int,
    val vectorDimension: Int
)

/**
 * 存储结果
 */
data class StorageResult(
    val success: Boolean,
    val memoryId: String?,
    val cnftId: String?,
    val irysUri: String?,
    val costLamports: Long,
    val message: String
)

/**
 * 记忆索引
 */
data class MemoryIndex(
    val id: String,
    val cnftId: String,
    val irysUri: String,
    val contentHash: String,
    val timestamp: Long,
    val metadata: Map<String, String>,
    val storage: String = "IRYS",
    val migratedFrom: String? = null,
    val migratedAt: Long? = null
)

/**
 * cNFT 元数据（符合 Metaplex 标准）
 */
data class CNFTMetadata(
    val name: String,
    val description: String,
    val image: String,
    val attributes: List<CNFTAttribute>
)

data class CNFTAttribute(
    @SerializedName("trait_type") val traitType: String,
    val value: String
)

/**
 * Irys 上传响应
 */
data class IrysUploadResponse(
    val id: String,
    val timestamp: Long
)

/**
 * Irys 数据项（用于数据同步）
 */
data class IrysDataItem(
    val transactionId: String,
    val irysUri: String,
    val timestamp: Long,
    val metadata: Map<String, String>
)

/**
 * 字节数组转十六进制字符串
 */
fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}
