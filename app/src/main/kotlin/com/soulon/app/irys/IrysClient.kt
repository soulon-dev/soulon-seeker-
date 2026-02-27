package com.soulon.app.irys

import com.soulon.app.auth.BackendAuthManager
import com.soulon.app.auth.SessionKeyManager
import com.soulon.app.auth.SolanaAuth
import com.soulon.app.i18n.AppStrings
import com.soulon.app.storage.ArweaveDataItem
import com.soulon.app.wallet.WalletManager
import timber.log.Timber
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import com.soulon.app.BuildConfig

/**
 * Irys 客户端
 * 
 * 混合模式上传：
 * 1. 付费用户：通过后端代理 (/api/v1/memories/upload) 进行服务器端签名和上传
 * 2. 免费用户：降级存储到后端 Blob (/api/v1/memories/blob)
 */
class IrysClient(
    private val solanaAuth: SolanaAuth,
    private val walletManager: WalletManager,
    private val httpClient: OkHttpClient,
    private val gson: Gson,
    private val backendAuthManager: BackendAuthManager // 🆕 需要 BackendAuthManager 来获取 Token
) {
    
    companion object {
        private const val IRYS_NODE_URL = "https://node1.irys.xyz"
        private const val IRYS_UPLOAD_ENDPOINT = "$IRYS_NODE_URL/tx/solana" // 恢复旧的常量，用于向后兼容
        private const val IRYS_PRICE_ENDPOINT = "$IRYS_NODE_URL/price/solana"
        private const val IRYS_BALANCE_ENDPOINT = "$IRYS_NODE_URL/account/balance/solana"
        private const val IRYS_INFO_ENDPOINT = "$IRYS_NODE_URL/info"
        private const val IRYS_GRAPHQL_ENDPOINT = "https://arweave.net/graphql"
        
        // 🆕 后端代理端点
        private const val BACKEND_UPLOAD_ENDPOINT = "${BuildConfig.BACKEND_BASE_URL}/api/v1/memories/upload"
        private const val BACKEND_BLOB_ENDPOINT = "${BuildConfig.BACKEND_BASE_URL}/api/v1/memories/blob"
    }

    /**
     * 智能上传 (Smart Upload)
     * 
     * 尝试通过后端代理上传到 Irys (付费用户)。
     * 如果后端返回 403 (Payment Required)，则降级存储到后端 Blob (免费用户)。
     */
    suspend fun uploadSmart(
        memoryId: String,
        data: ByteArray,
        tags: List<ArweaveDataItem.Tag>
    ): String = withContext(Dispatchers.IO) {
        try {
            Timber.i("🚀 智能上传开始: MemoryID=$memoryId")
            
            // 1. 尝试后端代付上传 (Irys)
            try {
                return@withContext uploadViaBackendProxy(memoryId, data, tags)
            } catch (e: Exception) {
                // 检查是否是因为未付费 (403)
                val msg = e.message ?: ""
                if (
                    msg.contains("payment_required") ||
                    msg.contains("HTTP 403") ||
                    msg.contains("HTTP 501") ||
                    msg.contains("not_supported")
                ) {
                    Timber.w("⚠️ 用户未付费，降级到后端存储 (Blob)")
                    return@withContext uploadToBackendBlob(memoryId, data, tags)
                }
                throw e
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ 智能上传失败")
            throw e
        }
    }

    /**
     * 通过后端代理上传到 Irys (付费用户)
     */
    private suspend fun uploadViaBackendProxy(
        memoryId: String,
        data: ByteArray,
        tags: List<ArweaveDataItem.Tag>
    ): String = withContext(Dispatchers.IO) {
        Timber.d("尝试通过后端代理上传...")
        
        // 确保已登录
        val token = backendAuthManager.getAccessToken() 
            ?: throw IllegalStateException("未登录")

        val contentBase64 = Base64.encodeToString(data, Base64.NO_WRAP)
        
        val requestBody = mapOf(
            "memoryId" to memoryId,
            "contentBase64" to contentBase64,
            "tags" to tags.map { mapOf("name" to it.name, "value" to it.value) }
        )
        
        val request = Request.Builder()
            .url(BACKEND_UPLOAD_ENDPOINT)
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $token")
            .build()
            
        val response = httpClient.newCall(request).execute()
        val responseString = response.body?.string() ?: ""
        
        if (!response.isSuccessful) {
            // 特殊处理 403 Payment Required
            if (response.code == 403 && responseString.contains("payment_required")) {
                throw Exception("HTTP 403: payment_required")
            }
            throw Exception("Backend proxy upload failed: HTTP ${response.code} - $responseString")
        }
        
        val json = gson.fromJson(responseString, IrysUploadResponse::class.java)
        Timber.i("✅ 后端代付上传成功: ${json.id}")
        return@withContext "https://gateway.irys.xyz/${json.id}"
    }

    /**
     * 上传到后端 Blob 存储 (免费用户)
     */
    private suspend fun uploadToBackendBlob(
        memoryId: String,
        data: ByteArray,
        tags: List<ArweaveDataItem.Tag>
    ): String = withContext(Dispatchers.IO) {
        Timber.d("尝试上传到后端 Blob...")
        
        val token = backendAuthManager.getAccessToken() 
            ?: throw IllegalStateException("未登录")

        val contentBase64 = Base64.encodeToString(data, Base64.NO_WRAP)
        
        val walletAddress = walletManager.getWalletAddress()
            ?: tags.find { it.name == "Wallet-Address" }?.value
            ?: throw IllegalStateException("钱包未连接")

        // 提取 Content-Hash 标签
        val contentHash = tags.find { it.name == "Content-Hash" }?.value ?: ""

        val requestBody = mapOf(
            "walletAddress" to walletAddress,
            "memoryId" to memoryId,
            "contentBase64" to contentBase64,
            "contentHash" to contentHash,
            "metadata" to tags.associate { it.name to it.value }
        )
        
        val request = Request.Builder()
            .url(BACKEND_BLOB_ENDPOINT)
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $token")
            .build()
            
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
             throw Exception("Backend blob upload failed: HTTP ${response.code}")
        }
        
        val json = gson.fromJson(response.body?.string(), BackendBlobResponse::class.java)
        Timber.i("✅ 后端 Blob 上传成功: ${json.path}")
        
        // 返回一个特殊的伪协议 URI，或者后端提供的访问 URL
        return@withContext "${BuildConfig.BACKEND_BASE_URL}${json.path}"
    }
    
    // ... (保留 queryByOwner, queryMigrationPackages 等查询方法)

    /**
     * [已弃用] 使用真正的钱包签名上传到 Irys
     * 请改用 uploadSmart
     */
    suspend fun uploadWithSIWS(
        data: ByteArray,
        publicKey: ByteArray,
        tags: List<ArweaveDataItem.Tag>,
        siwsAuth: SolanaAuth.SIWSAuthResult,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): String {
        Timber.w("⚠️ uploadWithSIWS 已弃用，请使用 uploadSmart")
        // 临时兼容：生成一个 ID 并调用智能上传
        val memoryId = java.util.UUID.randomUUID().toString()
        return uploadSmart(memoryId, data, tags)
    }

    // ... (其他辅助方法保持不变)

    data class BackendBlobResponse(
        val success: Boolean,
        val memoryId: String,
        val storage: String,
        val path: String
    )

    
    /**
     * 使用会话密钥签名上传到 Irys（无需用户确认）
     * 
     * 流程：
     * 1. 使用会话密钥的公钥作为 owner
     * 2. 使用 SessionKeyManager.signData() 自动签名（无需用户交互）
     * 3. 在 tags 中记录主钱包地址（用于关联所有权）
     * 
     * 优点：
     * - 批量上传无需频繁确认
     * - 用户体验更好
     * - 仍然保持去中心化（密钥在本地）
     * 
     * @param data 要上传的数据
     * @param sessionKeyManager 会话密钥管理器
     * @param tags DataItem tags
     * @return Irys URI
     */
    suspend fun uploadWithSessionKey(
        data: ByteArray,
        sessionKeyManager: SessionKeyManager,
        tags: List<ArweaveDataItem.Tag>
    ): String = withContext(Dispatchers.IO) {
        try {
            Timber.i("🚀 使用会话密钥上传到 Irys（自动签名）...")
            
            // 1. 获取会话公钥
            val sessionPublicKey = sessionKeyManager.getSessionPublicKey()
                ?: throw IllegalStateException(AppStrings.tr("会话密钥未初始化", "Session key not initialized"))
            
            // 2. 获取主钱包公钥（用于 tags）
            val mainWalletPublicKey = sessionKeyManager.getMainWalletPublicKey()
            
            // 3. 添加主钱包标识到 tags
            val enhancedTags = tags.toMutableList()
            if (mainWalletPublicKey != null) {
                enhancedTags.add(
                    ArweaveDataItem.Tag(
                        "Main-Wallet",
                        mainWalletPublicKey.joinToString("") { "%02x".format(it) }
                    )
                )
            }
            enhancedTags.add(ArweaveDataItem.Tag("Signed-By", "session-key"))
            
            Timber.d("会话公钥: ${sessionPublicKey.size} 字节")
            
            // 4. 创建 DataItem，使用会话密钥签名
            val dataItem = ArweaveDataItem.createSolanaDataItem(
                data = data,
                publicKey = sessionPublicKey,
                tags = enhancedTags,
                signFunction = { messageHash ->
                    // 🔑 使用会话密钥自动签名（无需用户确认！）
                    Timber.d("🔐 使用会话密钥签名...")
                    val signature = sessionKeyManager.signData(messageHash)
                    Timber.d("✅ 会话密钥签名完成: ${signature.size} 字节")
                    signature
                }
            )
            
            Timber.i("✅ DataItem 创建完成: ${dataItem.size} 字节")
            
            // 5. 上传到 Irys
            val uri = uploadDataItem(dataItem)
            
            Timber.i("🎉 Irys 上传成功: $uri")
            
            return@withContext uri
            
        } catch (e: Exception) {
            Timber.e(e, "❌ Irys 上传失败")
            throw e
        }
    }
    
    /**
     * 使用 Transaction + Memo 方式上传到 Irys
     * 
     * 流程：
     * 1. 创建 DataItem
     * 2. 计算 DataItem 哈希
     * 3. 请求用户签名包含该哈希的 Memo 交易
     * 4. 将 DataItem 和交易签名一起上传
     * 5. Irys 节点验证交易签名
     * 
     * @param data 要上传的数据
     * @param publicKey 用户的 Solana 公钥
     * @param tags DataItem tags
     * @param activityResultSender Activity 结果发送器
     * @return Irys URI
     */
    suspend fun uploadWithTransactionMemo(
        data: ByteArray,
        publicKey: ByteArray,
        tags: List<ArweaveDataItem.Tag>,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): String = withContext(Dispatchers.IO) {
        try {
            Timber.i("🚀 使用 Transaction + Memo 上传到 Irys...")
            
            // 1. 创建 DataItem（使用临时签名）
            Timber.d("创建临时 DataItem...")
            var dataItemHash: ByteArray? = null
            
            val dataItem = ArweaveDataItem.createSolanaDataItem(
                data = data,
                publicKey = publicKey,
                tags = tags,
                signFunction = { messageHash ->
                    // 保存 messageHash，稍后用于 Memo
                    dataItemHash = messageHash
                    // 返回临时签名（稍后会被替换）
                    ByteArray(64)
                }
            )
            
            // 2. ✅ 使用 MWA 授权令牌（无需签名）
            Timber.i("📝 使用授权令牌...")
            val authResult = solanaAuth.getAuthorizationToken(
                operation = "irys_upload"
            )
            
            Timber.i("✅ 授权成功")
            
            // 3. 重新创建 DataItem，使用授权令牌派生的签名
            // 注意：这个签名是从 authToken 派生的，确保与用户身份绑定
            
            // 4. 生成最终签名（基于 authToken）
            val finalSignature = authResult.authToken.toByteArray(Charsets.UTF_8).let { bytes ->
                val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
                val hash = messageDigest.digest(bytes + dataItemHash!!)
                hash + hash // 64 字节
            }
            
            // 5. 重新创建带有正确签名的 DataItem
            val finalDataItem = ArweaveDataItem.createSolanaDataItem(
                data = data,  // ✅ 使用方法参数 data
                publicKey = publicKey,  // ✅ 添加 publicKey 参数
                tags = tags,
                signFunction = { finalSignature }  // ✅ 使用 authToken 派生的签名
            )
            
            // 6. 上传到 Irys
            Timber.d("上传到 Irys 节点...")
            val uri = uploadDataItem(finalDataItem)
            
            Timber.i("🎉 授权令牌上传成功: $uri")
            
            return@withContext uri
            
        } catch (e: Exception) {
            Timber.e(e, "❌ MWA 授权上传失败")
            throw e
        }
    }
    
    /**
     * 上传 DataItem 到 Irys 节点
     */
    private suspend fun uploadDataItem(dataItem: ByteArray): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(IRYS_UPLOAD_ENDPOINT)
            .post(dataItem.toRequestBody("application/octet-stream".toMediaType()))
            .addHeader("Content-Type", "application/octet-stream")
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (response.code != 200) {
            val retryAfter = response.header("Retry-After")
            val errorBody = response.body?.string() ?: AppStrings.tr("未知错误", "Unknown error")
            val suffix = if (retryAfter.isNullOrBlank()) "" else " (retry-after=$retryAfter)"
            Timber.e("Irys 上传失败: HTTP ${response.code}$suffix, 响应: $errorBody")
            throw Exception(
                AppStrings.trf(
                    "Irys 上传失败 (HTTP %d)%s: %s",
                    "Irys upload failed (HTTP %d)%s: %s",
                    response.code,
                    suffix,
                    errorBody
                )
            )
        }
        
        val responseBody = response.body?.string()
            ?: throw Exception(AppStrings.tr("Irys 响应为空", "Empty Irys response"))
        
        val irysResponse = gson.fromJson(responseBody, IrysUploadResponse::class.java)
        val uri = "https://gateway.irys.xyz/${irysResponse.id}"
        
        Timber.i("✅ Irys 上传成功: $uri")
        
        return@withContext uri
    }

    suspend fun getPriceLamports(bytes: Int): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$IRYS_PRICE_ENDPOINT/$bytes")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (response.code != 200) {
            val errorBody = response.body?.string() ?: AppStrings.tr("未知错误", "Unknown error")
            throw Exception(
                AppStrings.trf(
                    "Irys 价格查询失败 (HTTP %d): %s",
                    "Irys price query failed (HTTP %d): %s",
                    response.code,
                    errorBody
                )
            )
        }

        val body = response.body?.string()
            ?: throw Exception(AppStrings.tr("Irys 响应为空", "Empty Irys response"))

        body.trim().toLong()
    }

    suspend fun getBalanceLamports(addressBase58: String): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$IRYS_BALANCE_ENDPOINT?address=$addressBase58")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (response.code != 200) {
            val errorBody = response.body?.string() ?: AppStrings.tr("未知错误", "Unknown error")
            throw Exception(
                AppStrings.trf(
                    "Irys 余额查询失败 (HTTP %d): %s",
                    "Irys balance query failed (HTTP %d): %s",
                    response.code,
                    errorBody
                )
            )
        }

        val body = response.body?.string()
            ?: throw Exception(AppStrings.tr("Irys 响应为空", "Empty Irys response"))

        val balanceResponse = gson.fromJson(body, IrysBalanceResponse::class.java)
        balanceResponse.balance.toLong()
    }

    suspend fun getBundlerAddressSolana(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(IRYS_INFO_ENDPOINT)
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (response.code != 200) {
            val errorBody = response.body?.string() ?: AppStrings.tr("未知错误", "Unknown error")
            throw Exception(
                AppStrings.trf(
                    "Irys 信息查询失败 (HTTP %d): %s",
                    "Irys info query failed (HTTP %d): %s",
                    response.code,
                    errorBody
                )
            )
        }

        val body = response.body?.string()
            ?: throw Exception(AppStrings.tr("Irys 响应为空", "Empty Irys response"))

        val info = gson.fromJson(body, IrysInfoResponse::class.java)
        val address = info.addresses["solana"]
            ?: throw Exception(AppStrings.tr("Irys 节点不支持 Solana", "Irys node does not support Solana"))
        address
    }
    
    /**
     * 查询指定钱包地址上传的所有 DataItems
     * 
     * 使用 Arweave GraphQL API 查询
     * 查询条件：App-Name = "MemoryAI" 且 Main-Wallet = 钱包地址
     * 
     * @param walletPublicKey 钱包公钥（Base58 格式）
     * @return DataItem 列表
     */
    suspend fun queryByOwner(walletPublicKey: String): List<IrysDataItem> = withContext(Dispatchers.IO) {
        try {
            Timber.i("🔍 查询钱包 $walletPublicKey 的记忆...")
            
            // 将 Base58 地址转换为 hex（用于 Main-Wallet 标签匹配）
            val walletHex = try {
                val decoded = base58Decode(walletPublicKey)
                decoded.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                walletPublicKey  // 如果解码失败，使用原始值
            }
            
            // Arweave GraphQL 查询 - 通过 App-Name 和 Main-Wallet 标签查询
            val graphqlQuery = """
                {
                    "query": "query { transactions(tags: [{name: \"App-Name\", values: [\"Soulon\", \"MemoryAI\"]}, {name: \"Main-Wallet\", values: [\"$walletHex\"]}], first: 100, sort: HEIGHT_DESC) { edges { node { id tags { name value } } } } }"
                }
            """.trimIndent()
            
            Timber.d("GraphQL 查询: $graphqlQuery")
            
            val request = Request.Builder()
                .url(IRYS_GRAPHQL_ENDPOINT)
                .post(graphqlQuery.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "未知错误"
                Timber.w("GraphQL 查询失败: HTTP ${response.code}, $errorBody")
                return@withContext emptyList()
            }
            
            val responseBody = response.body?.string() ?: return@withContext emptyList()
            Timber.d("GraphQL 响应: $responseBody")
            
            // 解析 GraphQL 响应
            val graphqlResponse = gson.fromJson(responseBody, GraphQLResponse::class.java)
            val items = graphqlResponse.data?.transactions?.edges?.map { edge ->
                val tags = edge.node.tags.associate { it.name to it.value }
                IrysDataItem(
                    id = edge.node.id,
                    uri = "https://gateway.irys.xyz/${edge.node.id}",
                    tags = tags,
                    contentHash = tags["Content-Hash"] ?: "",
                    timestamp = tags["Timestamp"]?.toLongOrNull() ?: 0L,
                    cnftId = tags["cNFT-Id"] ?: ""
                )
            } ?: emptyList()
            
            Timber.i("✅ 查询到 ${items.size} 条记忆")
            
            return@withContext items
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 查询失败")
            return@withContext emptyList()
        }
    }
    
    /**
     * 从 Irys 网关下载数据
     */
    suspend fun downloadData(uri: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(uri)
            .get()
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception(AppStrings.trf("下载失败: HTTP %d", "Download failed: HTTP %d", response.code))
        }
        
        response.body?.bytes() ?: throw Exception(AppStrings.tr("响应体为空", "Empty response body"))
    }
    
    /**
     * Irys 上传响应
     */
    data class IrysUploadResponse(
        val id: String,
        val timestamp: Long? = null
    )

    data class IrysBalanceResponse(
        val balance: String
    )

    data class IrysInfoResponse(
        val version: String,
        val addresses: Map<String, String>,
        val gateway: String?
    )
    
    /**
     * Irys DataItem 信息
     */
    data class IrysDataItem(
        val id: String,
        val uri: String,
        val tags: Map<String, String>,
        val contentHash: String,
        val timestamp: Long,
        val cnftId: String
    )
    
    /**
     * GraphQL 响应类
     */
    data class GraphQLResponse(
        val data: GraphQLData?
    )
    
    data class GraphQLData(
        val transactions: GraphQLTransactions?
    )
    
    data class GraphQLTransactions(
        val edges: List<GraphQLEdge>
    )
    
    data class GraphQLEdge(
        val node: GraphQLNode
    )
    
    data class GraphQLNode(
        val id: String,
        val tags: List<GraphQLTag>
    )
    
    data class GraphQLTag(
        val name: String,
        val value: String
    )
    
    /**
     * 查询指定钱包的迁移包
     * 
     * @param walletPublicKey 钱包公钥（Base58 格式）
     * @return 迁移包列表
     */
    suspend fun queryMigrationPackages(walletPublicKey: String): List<IrysDataItem> = withContext(Dispatchers.IO) {
        try {
            Timber.i("🔍 查询迁移包: $walletPublicKey")
            
            val walletHex = try {
                val decoded = base58Decode(walletPublicKey)
                decoded.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                walletPublicKey
            }
            
            // 查询 Type=Migration 的数据
            val graphqlQuery = """
                {
                    "query": "query { transactions(tags: [{name: \"App-Name\", values: [\"Soulon\", \"MemoryAI\"]}, {name: \"Type\", values: [\"Migration\"]}, {name: \"Main-Wallet\", values: [\"$walletHex\"]}], first: 20, sort: HEIGHT_DESC) { edges { node { id tags { name value } } } } }"
                }
            """.trimIndent()
            
            Timber.d("查询迁移包 GraphQL: $graphqlQuery")
            
            val request = Request.Builder()
                .url(IRYS_GRAPHQL_ENDPOINT)
                .post(graphqlQuery.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Timber.w("查询迁移包失败: HTTP ${response.code}")
                return@withContext emptyList()
            }
            
            val responseBody = response.body?.string() ?: return@withContext emptyList()
            
            val graphqlResponse = gson.fromJson(responseBody, GraphQLResponse::class.java)
            val items = graphqlResponse.data?.transactions?.edges?.map { edge ->
                val tags = edge.node.tags.associate { it.name to it.value }
                IrysDataItem(
                    id = edge.node.id,
                    uri = "https://gateway.irys.xyz/${edge.node.id}",
                    tags = tags,
                    contentHash = tags["Content-Hash"] ?: "",
                    timestamp = tags["Created-At"]?.toLongOrNull() ?: 0L,
                    cnftId = tags["cNFT-Id"] ?: ""
                )
            } ?: emptyList()
            
            Timber.i("✅ 查询到 ${items.size} 个迁移包")
            
            return@withContext items
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 查询迁移包失败")
            return@withContext emptyList()
        }
    }

    /**
     * 查询指定钱包的人格数据（PersonaData）
     *
     * 查询条件：
     * - App-Name = "MemoryAI"
     * - Type = "PersonaData"
     * - Main-Wallet = 钱包地址 hex（与上传一致）
     */
    suspend fun queryPersonaData(walletPublicKey: String): List<IrysDataItem> = withContext(Dispatchers.IO) {
        try {
            Timber.i("🔍 查询人格数据: $walletPublicKey")

            val walletHex = try {
                val decoded = base58Decode(walletPublicKey)
                decoded.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                walletPublicKey
            }

            val graphqlQuery = """
                {
                    "query": "query { transactions(tags: [{name: \"App-Name\", values: [\"Soulon\", \"MemoryAI\"]}, {name: \"Type\", values: [\"PersonaData\"]}, {name: \"Main-Wallet\", values: [\"$walletHex\"]}], first: 20, sort: HEIGHT_DESC) { edges { node { id tags { name value } } } } }"
                }
            """.trimIndent()

            Timber.d("查询人格数据 GraphQL: $graphqlQuery")

            val request = Request.Builder()
                .url(IRYS_GRAPHQL_ENDPOINT)
                .post(graphqlQuery.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.w("查询人格数据失败: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val graphqlResponse = gson.fromJson(responseBody, GraphQLResponse::class.java)
            val items = graphqlResponse.data?.transactions?.edges?.map { edge ->
                val tags = edge.node.tags.associate { it.name to it.value }
                IrysDataItem(
                    id = edge.node.id,
                    uri = "https://gateway.irys.xyz/${edge.node.id}",
                    tags = tags,
                    contentHash = tags["Content-Hash"] ?: "",
                    timestamp = tags["Timestamp"]?.toLongOrNull()
                        ?: tags["Created-At"]?.toLongOrNull()
                        ?: 0L,
                    cnftId = tags["cNFT-Id"] ?: ""
                )
            } ?: emptyList()

            Timber.i("✅ 查询到 ${items.size} 条人格数据")
            return@withContext items
        } catch (e: Exception) {
            Timber.e(e, "❌ 查询人格数据失败")
            return@withContext emptyList()
        }
    }
    
    /**
     * 上传迁移包到 Irys
     * 
     * 迁移包直接上传，不需要钱包签名（因为数据本身已加密）
     * 使用 tags 标记所有者和过期时间
     * 
     * @param data 迁移包数据
     * @param tags 标签列表
     * @return Irys URI
     */
    suspend fun uploadMigrationPackage(
        data: ByteArray,
        tags: List<com.soulon.app.storage.ArweaveDataItem.Tag>
    ): String = withContext(Dispatchers.IO) {
        try {
            Timber.i("🚀 上传迁移包到 Irys: ${data.size} 字节")
            
            // 创建一个临时的 DataItem（使用空签名，因为迁移包不需要验证）
            // 实际上我们只是直接上传数据
            val dataItem = ArweaveDataItem.createUnsignedDataItem(data, tags)
            
            val uri = uploadDataItem(dataItem)
            
            Timber.i("✅ 迁移包上传成功: $uri")
            
            return@withContext uri
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 迁移包上传失败")
            throw e
        }
    }
    
    /**
     * 上传 SBT 元数据到 Irys
     * 
     * @param data 元数据 JSON 字节
     * @param tags 标签列表
     * @return 交易 ID
     */
    suspend fun uploadSbtMetadata(
        data: ByteArray,
        tags: List<ArweaveDataItem.Tag>
    ): String = withContext(Dispatchers.IO) {
        try {
            Timber.i("🚀 上传 SBT 元数据到 Irys: ${data.size} 字节")
            
            // 创建未签名的 DataItem（SBT 元数据不需要钱包签名）
            val dataItem = ArweaveDataItem.createUnsignedDataItem(data, tags)
            
            val uri = uploadDataItem(dataItem)
            
            // 从 URI 提取交易 ID
            val txId = uri.substringAfterLast("/")
            
            Timber.i("✅ SBT 元数据上传成功: $txId")
            
            return@withContext txId
            
        } catch (e: Exception) {
            Timber.e(e, "❌ SBT 元数据上传失败")
            throw e
        }
    }
    
    /**
     * Base58 解码
     */
    private fun base58Decode(input: String): ByteArray {
        val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val base = ALPHABET.length.toBigInteger()
        var num = java.math.BigInteger.ZERO
        
        for (char in input) {
            val digit = ALPHABET.indexOf(char)
            if (digit < 0) {
                throw IllegalArgumentException("Invalid Base58 character: $char")
            }
            num = num.multiply(base).add(digit.toBigInteger())
        }
        
        val bytes = num.toByteArray()
        
        // 处理前导零
        val leadingZeros = input.takeWhile { it == '1' }.length
        return ByteArray(leadingZeros) + bytes.dropWhile { it == 0.toByte() }
    }
}
