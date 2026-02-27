package com.soulon.app.storage

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Irys GraphQL 索引器
 * 
 * 通过 Arweave/Irys GraphQL API 查询已上传的记忆
 * 替代 cNFT 链上索引，零额外成本
 * 
 * 文档：https://docs.irys.xyz
 */
class IrysIndexer {
    
    companion object {
        // GraphQL 端点
        private const val ARWEAVE_GRAPHQL_URL = "https://arweave.net/graphql"
        private const val IRYS_GATEWAY_URL = "https://gateway.irys.xyz"
        
        // 应用标识
        const val APP_NAME = "Soulon"
        const val CONTENT_TYPE_MEMORY = "application/octet-stream"
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    /**
     * 查询用户的所有记忆
     * 
     * @param walletAddress 钱包地址
     * @param limit 返回数量限制
     * @return 记忆索引列表
     */
    suspend fun queryMemories(
        walletAddress: String,
        limit: Int = 100
    ): List<MemoryIndex> = withContext(Dispatchers.IO) {
        try {
            Timber.i("🔍 查询记忆索引: $walletAddress")
            
            val query = buildMemoryQuery(walletAddress, limit)
            val response = executeGraphQL(query)
            
            val memories = parseMemoryResponse(response)
            Timber.i("✅ 找到 ${memories.size} 条记忆")
            
            memories
        } catch (e: Exception) {
            Timber.e(e, "❌ 查询记忆索引失败")
            emptyList()
        }
    }
    
    /**
     * 查询特定类型的记忆
     * 
     * @param walletAddress 钱包地址
     * @param memoryType 记忆类型（如 "questionnaire", "chat", "manual"）
     * @param limit 返回数量限制
     * @return 记忆索引列表
     */
    suspend fun queryMemoriesByType(
        walletAddress: String,
        memoryType: String,
        limit: Int = 50
    ): List<MemoryIndex> = withContext(Dispatchers.IO) {
        try {
            Timber.i("🔍 查询 $memoryType 类型记忆: $walletAddress")
            
            val query = buildTypedMemoryQuery(walletAddress, memoryType, limit)
            val response = executeGraphQL(query)
            
            val memories = parseMemoryResponse(response)
            Timber.i("✅ 找到 ${memories.size} 条 $memoryType 记忆")
            
            memories
        } catch (e: Exception) {
            Timber.e(e, "❌ 查询记忆索引失败")
            emptyList()
        }
    }
    
    /**
     * 获取记忆内容
     * 
     * @param transactionId Irys/Arweave 交易 ID
     * @return 记忆内容（加密的）
     */
    suspend fun fetchMemoryContent(transactionId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            Timber.i("📥 获取记忆内容: $transactionId")
            
            val url = "$IRYS_GATEWAY_URL/$transactionId"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val content = response.body?.bytes()
                Timber.i("✅ 获取成功: ${content?.size ?: 0} bytes")
                content
            } else {
                Timber.e("❌ 获取失败: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ 获取记忆内容失败")
            null
        }
    }
    
    /**
     * 检查交易是否已确认
     * 
     * @param transactionId 交易 ID
     * @return 是否已确认
     */
    suspend fun isTransactionConfirmed(transactionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                    transaction(id: "$transactionId") {
                        id
                        block {
                            height
                        }
                    }
                }
            """.trimIndent()
            
            val response = executeGraphQL(query)
            val jsonResponse = gson.fromJson(response, GraphQLResponse::class.java)
            
            // 如果有 block 信息，说明已确认
            jsonResponse.data?.transaction?.block != null
        } catch (e: Exception) {
            Timber.e(e, "检查交易状态失败")
            false
        }
    }
    
    /**
     * 构建记忆查询 GraphQL
     */
    private fun buildMemoryQuery(walletAddress: String, limit: Int): String {
        return """
            query {
                transactions(
                    first: $limit,
                    tags: [
                        { name: "App-Name", values: ["$APP_NAME", "MemoryAI"] },
                        { name: "Wallet-Address", values: ["$walletAddress"] },
                        { name: "Content-Type", values: ["$CONTENT_TYPE_MEMORY", "application/json"] }
                    ],
                    sort: HEIGHT_DESC
                ) {
                    edges {
                        node {
                            id
                            tags {
                                name
                                value
                            }
                            block {
                                timestamp
                                height
                            }
                            data {
                                size
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }
    
    /**
     * 构建带类型的记忆查询 GraphQL
     */
    private fun buildTypedMemoryQuery(walletAddress: String, memoryType: String, limit: Int): String {
        return """
            query {
                transactions(
                    first: $limit,
                    tags: [
                        { name: "App-Name", values: ["$APP_NAME", "MemoryAI"] },
                        { name: "Wallet-Address", values: ["$walletAddress"] },
                        { name: "Memory-Type", values: ["$memoryType"] },
                        { name: "Content-Type", values: ["$CONTENT_TYPE_MEMORY", "application/json"] }
                    ],
                    sort: HEIGHT_DESC
                ) {
                    edges {
                        node {
                            id
                            tags {
                                name
                                value
                            }
                            block {
                                timestamp
                                height
                            }
                            data {
                                size
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }
    
    /**
     * 执行 GraphQL 查询
     */
    private fun executeGraphQL(query: String): String {
        val requestBody = gson.toJson(mapOf("query" to query))
            .toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(ARWEAVE_GRAPHQL_URL)
            .post(requestBody)
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("GraphQL 查询失败: ${response.code}")
        }
        
        return response.body?.string() ?: throw Exception("空响应")
    }
    
    /**
     * 解析记忆响应
     */
    private fun parseMemoryResponse(response: String): List<MemoryIndex> {
        val graphQLResponse = gson.fromJson(response, GraphQLResponse::class.java)
        
        return graphQLResponse.data?.transactions?.edges?.mapNotNull { edge ->
            val node = edge.node ?: return@mapNotNull null
            val tags = node.tags?.associate { it.name to it.value } ?: emptyMap()
            
            MemoryIndex(
                transactionId = node.id,
                walletAddress = tags["Wallet-Address"] ?: "",
                memoryType = tags["Memory-Type"] ?: "unknown",
                timestamp = tags["Timestamp"]?.toLongOrNull() 
                    ?: (node.block?.timestamp?.times(1000L) ?: System.currentTimeMillis()),
                blockHeight = node.block?.height,
                dataSize = node.data?.size ?: 0,
                tags = tags,
                gatewayUrl = "$IRYS_GATEWAY_URL/${node.id}"
            )
        } ?: emptyList()
    }
}

/**
 * 记忆索引数据
 */
data class MemoryIndex(
    val transactionId: String,          // Irys/Arweave 交易 ID
    val walletAddress: String,          // 所有者钱包地址
    val memoryType: String,             // 记忆类型
    val timestamp: Long,                // 创建时间戳
    val blockHeight: Long?,             // 区块高度（已确认时）
    val dataSize: Long,                 // 数据大小
    val tags: Map<String, String>,      // 所有标签
    val gatewayUrl: String              // 网关访问 URL
) {
    /**
     * 是否已在链上确认
     */
    val isConfirmed: Boolean get() = blockHeight != null
    
    /**
     * 获取 Arweave 浏览器链接
     */
    fun getExplorerUrl(): String = "https://viewblock.io/arweave/tx/$transactionId"
}

// ========== GraphQL 响应数据类 ==========

private data class GraphQLResponse(
    val data: GraphQLData?
)

private data class GraphQLData(
    val transactions: TransactionsData?,
    val transaction: TransactionNode?
)

private data class TransactionsData(
    val edges: List<TransactionEdge>?
)

private data class TransactionEdge(
    val node: TransactionNode?
)

private data class TransactionNode(
    val id: String,
    val tags: List<TagData>?,
    val block: BlockData?,
    val data: DataInfo?
)

private data class TagData(
    val name: String,
    val value: String
)

private data class BlockData(
    val timestamp: Long?,
    val height: Long?
)

private data class DataInfo(
    val size: Long?
)
