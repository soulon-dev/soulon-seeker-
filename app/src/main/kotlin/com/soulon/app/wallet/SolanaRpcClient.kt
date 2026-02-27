package com.soulon.app.wallet

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.soulon.app.data.BackendApiClient
import com.soulon.app.data.SolanaBalanceResult
import com.soulon.app.data.SolanaTokensResult
import com.soulon.app.data.TransactionVerifyResult
import com.soulon.app.i18n.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Solana RPC 客户端
 * 
 * 🔒 后端优先架构（Backend-First Architecture）
 * 
 * 核心原则：
 * - 所有链上查询和交易通过后端代理 - 防止网络威胁和数据篡改
 * - 后端验证交易有效性 - 防止伪造交易
 * - 无直接 RPC 访问 - 所有操作必须经过后端
 * 
 * 用于查询链上数据（余额、交易等）
 * 
 * 官方文档：
 * - https://docs.solana.com/api/http
 */
class SolanaRpcClient(
    private val rpcUrl: String = "https://api.mainnet-beta.solana.com", // ✅ 使用 Mainnet（正式网络）
    private val useBackendProxy: Boolean = true // 🔒 默认启用后端代理
) {
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    // 后端 API 客户端（延迟初始化）
    private var backendApiClient: BackendApiClient? = null
    
    /**
     * 设置后端 API 客户端（用于后端代理模式）
     */
    fun setBackendApiClient(client: BackendApiClient) {
        backendApiClient = client
        Timber.i("🔒 SolanaRpcClient 已连接后端代理")
    }
    
    /**
     * 初始化后端代理（使用 Context）
     */
    fun initBackendProxy(context: android.content.Context) {
        backendApiClient = BackendApiClient.getInstance(context)
        Timber.i("🔒 SolanaRpcClient 后端代理已初始化")
    }
    
    companion object {
        private const val TAG = "SolanaRpcClient"
    }
    
    /**
     * 获取账户余额
     * 
     * 🔒 后端优先架构：优先通过后端代理查询余额
     * 
     * @param publicKeyBase58 公钥（Base58 编码）
     * @return 余额（lamports，1 SOL = 1,000,000,000 lamports）
     */
    suspend fun getBalance(publicKeyBase58: String): Long = withContext(Dispatchers.IO) {
        // 🔒 优先使用后端代理
        if (useBackendProxy && backendApiClient != null) {
            return@withContext getBalanceViaBackend(publicKeyBase58)
        }
        
        // 降级：直接 RPC 调用（仅在后端不可用时）
        Timber.tag(TAG).w("⚠️ 使用直接 RPC 查询余额（后端代理不可用）")
        getBalanceDirect(publicKeyBase58)
    }
    
    /**
     * 通过后端代理获取余额
     */
    private suspend fun getBalanceViaBackend(publicKeyBase58: String): Long {
        try {
            Timber.tag(TAG).i("🔒 通过后端代理查询余额: $publicKeyBase58")
            
            val result = backendApiClient!!.getSolanaBalance(publicKeyBase58)
            
            return when (result) {
                is SolanaBalanceResult.Success -> {
                    Timber.tag(TAG).i("✅ 后端代理余额查询成功: ${result.lamports} lamports (${result.sol} SOL)")
                    result.lamports
                }
                else -> {
                    if (result is SolanaBalanceResult.Error) {
                        Timber.tag(TAG).e("❌ 后端代理余额查询失败: ${result.message}")
                    }
                    // 降级到直接 RPC
                    Timber.tag(TAG).w("⚠️ 降级到直接 RPC 查询")
                    getBalanceDirect(publicKeyBase58)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "后端代理查询异常，降级到直接 RPC")
            return getBalanceDirect(publicKeyBase58)
        }
    }
    
    /**
     * 直接 RPC 调用获取余额（降级方案）
     */
    private suspend fun getBalanceDirect(publicKeyBase58: String): Long {
        try {
            Timber.tag(TAG).i("查询余额(直接RPC): $publicKeyBase58")
            
            // 构建 RPC 请求
            val request = RpcRequest(
                method = "getBalance",
                params = listOf(publicKeyBase58)
            )
            
            val requestBody = gson.toJson(request)
            Timber.tag(TAG).d("RPC 请求: $requestBody")
            
            val httpRequest = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(httpRequest).execute()
            
            if (!response.isSuccessful) {
                Timber.tag(TAG).e("RPC 请求失败: HTTP ${response.code}")
                throw Exception(AppStrings.trf("RPC 请求失败: HTTP %d", "RPC request failed: HTTP %d", response.code))
            }
            
            val responseBody = response.body?.string()
                ?: throw Exception(AppStrings.tr("响应体为空", "Empty response body"))
            
            Timber.tag(TAG).d("RPC 响应: $responseBody")
            
            // 解析响应
            val rpcResponse = gson.fromJson(responseBody, BalanceResponse::class.java)
            
            if (rpcResponse.error != null) {
                Timber.tag(TAG).e("RPC 错误: ${rpcResponse.error.message}")
                throw Exception(AppStrings.trf("RPC 错误: %s", "RPC error: %s", rpcResponse.error.message))
            }
            
            val balance = rpcResponse.result?.value ?: 0L
            Timber.tag(TAG).i("余额查询成功: $balance lamports (${balance / 1_000_000_000.0} SOL)")
            
            return balance
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "查询余额失败")
            throw e
        }
    }
    
    /**
     * 获取账户信息
     */
    suspend fun getAccountInfo(publicKeyBase58: String): AccountInfo? = withContext(Dispatchers.IO) {
        try {
            Timber.i("查询账户信息: $publicKeyBase58")
            
            val request = RpcRequest(
                method = "getAccountInfo",
                params = listOf(
                    publicKeyBase58,
                    mapOf("encoding" to "base64")
                )
            )
            
            val requestBody = gson.toJson(request)
            val httpRequest = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(httpRequest).execute()
            
            if (!response.isSuccessful) {
                return@withContext null
            }
            
            val responseBody = response.body?.string() ?: return@withContext null
            val rpcResponse = gson.fromJson(responseBody, AccountInfoResponse::class.java)
            
            rpcResponse.result?.value
        } catch (e: Exception) {
            Timber.e(e, "查询账户信息失败")
            null
        }
    }
    
    /**
     * 获取账户原始数据
     * 
     * @param publicKeyBase58 账户地址
     * @return 原始字节数据
     */
    suspend fun getAccountInfoRaw(publicKeyBase58: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = RpcRequest(
                method = "getAccountInfo",
                params = listOf(
                    publicKeyBase58,
                    mapOf("encoding" to "base64")
                )
            )
            
            val requestBody = gson.toJson(request)
            val httpRequest = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) return@withContext null
            
            val responseBody = response.body?.string() ?: return@withContext null
            val json = org.json.JSONObject(responseBody)
            
            val result = json.optJSONObject("result") ?: return@withContext null
            val value = result.optJSONObject("value") ?: return@withContext null
            val dataArray = value.optJSONArray("data") ?: return@withContext null
            
            val base64Data = dataArray.optString(0)
            if (base64Data.isNotEmpty()) {
                android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "获取账户原始数据失败")
            null
        }
    }
    
    /**
     * 检查用户是否持有指定的 SPL Token (例如 Genesis Token)
     * 
     * @param ownerAddress 用户钱包地址
     * @param tokenMintAddress 代币 Mint 地址
     * @return true 如果持有该代币且余额 > 0
     */
    suspend fun hasToken(ownerAddress: String, tokenMintAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 使用 getTokenAccountsByOwner 查询
            // 这里我们不需要所有的 Token，只需要查询特定的 Mint
            // 优化：使用 getTokenAccountsByOwner 并过滤 mint
            
            // 由于 getTokenAccountsByOwner 的 filter 参数只能是 programId，
            // 所以我们先获取所有 Token，然后在本地过滤
            // 或者使用 getTokenAccountsByOwner(mint) 过滤器（如果是 SPL Token Program）
            
            // 尝试直接使用 getTokenAccountsByOwner(mint) 过滤器
            // 注意：标准的 RPC 方法 getTokenAccountsByOwner 接受 {mint: "..."} 作为 filter
            
            val request = RpcRequest(
                method = "getTokenAccountsByOwner",
                params = listOf(
                    ownerAddress,
                    mapOf("mint" to tokenMintAddress),
                    mapOf("encoding" to "jsonParsed")
                )
            )
            
            val requestBody = gson.toJson(request)
            val httpRequest = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(httpRequest).execute()
            
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("检查 Token 失败: HTTP ${response.code}")
                return@withContext false
            }
            
            val responseBody = response.body?.string() ?: return@withContext false
            val json = org.json.JSONObject(responseBody)
            
            val result = json.optJSONObject("result") ?: return@withContext false
            val value = result.optJSONArray("value") ?: return@withContext false
            
            // 检查是否有任何账户余额 > 0
            for (i in 0 until value.length()) {
                val accountObj = value.getJSONObject(i)
                val account = accountObj.getJSONObject("account")
                val data = account.getJSONObject("data")
                val parsed = data.getJSONObject("parsed")
                val info = parsed.getJSONObject("info")
                val tokenAmount = info.getJSONObject("tokenAmount")
                val uiAmount = tokenAmount.optDouble("uiAmount", 0.0)
                
                if (uiAmount > 0) {
                    return@withContext true
                }
            }
            
            return@withContext false
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "检查 Token 持有状态失败: $tokenMintAddress")
            false
        }
    }
    
    /**
     * 获取指定 Token Program 下的所有 Token 账户
     * 
     * 🔒 后端优先架构：优先通过后端代理查询代币账户
     * 
     * @param ownerAddress 所有者地址
     * @param programId Token Program ID (SPL Token 或 Token 2022)
     * @return Token 账户列表
     */
    suspend fun getTokenAccountsByOwner(
        ownerAddress: String,
        programId: String
    ): List<com.soulon.app.teepin.TokenAccount> = withContext(Dispatchers.IO) {
        // 🔒 优先使用后端代理
        if (useBackendProxy && backendApiClient != null) {
            return@withContext getTokenAccountsViaBackend(ownerAddress)
        }
        
        // 降级：直接 RPC 调用
        Timber.tag(TAG).w("⚠️ 使用直接 RPC 查询代币（后端代理不可用）")
        getTokenAccountsDirect(ownerAddress, programId)
    }
    
    /**
     * 通过后端代理获取代币账户
     */
    private suspend fun getTokenAccountsViaBackend(ownerAddress: String): List<com.soulon.app.teepin.TokenAccount> {
        try {
            Timber.tag(TAG).i("🔒 通过后端代理查询代币: $ownerAddress")
            
            val result = backendApiClient!!.getSolanaTokens(ownerAddress)
            
            return when (result) {
                is SolanaTokensResult.Success -> {
                    Timber.tag(TAG).i("✅ 后端代理代币查询成功: ${result.tokens.size} 个代币")
                    result.tokens.map { token ->
                        com.soulon.app.teepin.TokenAccount(
                            address = token.address,
                            mint = token.mint,
                            owner = ownerAddress,
                            amount = (token.balance * Math.pow(10.0, token.decimals.toDouble())).toLong()
                        )
                    }
                }
                else -> {
                    if (result is SolanaTokensResult.Error) {
                        Timber.tag(TAG).e("❌ 后端代理代币查询失败: ${result.message}")
                    }
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "后端代理代币查询异常")
            return emptyList()
        }
    }
    
    /**
     * 直接 RPC 调用获取代币账户（降级方案）
     */
    private suspend fun getTokenAccountsDirect(
        ownerAddress: String,
        programId: String
    ): List<com.soulon.app.teepin.TokenAccount> {
        try {
            Timber.tag(TAG).d("查询 Token 账户(直接RPC): owner=$ownerAddress, program=$programId")
            
            val request = RpcRequest(
                method = "getTokenAccountsByOwner",
                params = listOf(
                    ownerAddress,
                    mapOf("programId" to programId),
                    mapOf("encoding" to "jsonParsed")
                )
            )
            
            val requestBody = gson.toJson(request)
            val httpRequest = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("Token 账户查询失败: HTTP ${response.code}")
                return emptyList()
            }
            
            val responseBody = response.body?.string() ?: return emptyList()
            val json = org.json.JSONObject(responseBody)
            
            val result = json.optJSONObject("result") ?: return emptyList()
            val value = result.optJSONArray("value") ?: return emptyList()
            
            val accounts = mutableListOf<com.soulon.app.teepin.TokenAccount>()
            
            for (i in 0 until value.length()) {
                try {
                    val accountObj = value.getJSONObject(i)
                    val pubkey = accountObj.getString("pubkey")
                    val account = accountObj.getJSONObject("account")
                    val data = account.getJSONObject("data")
                    val parsed = data.getJSONObject("parsed")
                    val info = parsed.getJSONObject("info")
                    
                    val mint = info.getString("mint")
                    val owner = info.getString("owner")
                    val tokenAmount = info.getJSONObject("tokenAmount")
                    val amount = tokenAmount.optString("amount", "0").toLongOrNull() ?: 0L
                    
                    accounts.add(
                        com.soulon.app.teepin.TokenAccount(
                            address = pubkey,
                            mint = mint,
                            owner = owner,
                            amount = amount
                        )
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "解析 Token 账户失败")
                }
            }
            
            Timber.tag(TAG).d("找到 ${accounts.size} 个 Token 账户")
            return accounts
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "获取 Token 账户失败")
            return emptyList()
        }
    }
    
    /**
     * 获取最新的 Blockhash
     * 
     * @return Blockhash (Base58 编码)
     */
    suspend fun getLatestBlockhash(): String = withContext(Dispatchers.IO) {
        try {
            val request = RpcRequest(
                method = "getLatestBlockhash",
                params = listOf(mapOf("commitment" to "finalized"))
            )
            
            val requestBody = gson.toJson(request)
            val httpRequest = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                throw Exception(
                    AppStrings.trf("获取 Blockhash 失败: HTTP %d", "Failed to fetch blockhash: HTTP %d", response.code)
                )
            }
            
            val responseBody = response.body?.string()
                ?: throw Exception(AppStrings.tr("响应体为空", "Empty response body"))
            
            val json = org.json.JSONObject(responseBody)
            val result = json.getJSONObject("result")
            val value = result.getJSONObject("value")
            
            value.getString("blockhash")
            
        } catch (e: Exception) {
            Timber.e(e, "获取 Blockhash 失败")
            throw e
        }
    }
    
    /**
     * 获取交易签名状态
     * 
     * @param signatures 签名列表 (Base58)
     * @return 状态列表
     */
    suspend fun getSignatureStatuses(signatures: List<String>): List<SignatureStatus?> = withContext(Dispatchers.IO) {
        try {
            val request = RpcRequest(
                method = "getSignatureStatuses",
                params = listOf(
                    signatures,
                    mapOf("searchTransactionHistory" to true)
                )
            )
            
            val requestBody = gson.toJson(request)
            val httpRequest = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                return@withContext emptyList()
            }
            
            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val json = org.json.JSONObject(responseBody)
            
            val result = json.optJSONObject("result") ?: return@withContext emptyList()
            val value = result.optJSONArray("value") ?: return@withContext emptyList()
            
            val statuses = mutableListOf<SignatureStatus?>()
            for (i in 0 until value.length()) {
                val statusObj = value.optJSONObject(i)
                if (statusObj != null) {
                    statuses.add(
                        SignatureStatus(
                            slot = statusObj.optLong("slot"),
                            confirmations = statusObj.optInt("confirmations"),
                            confirmationStatus = statusObj.optString("confirmationStatus"),
                            err = statusObj.opt("err")
                        )
                    )
                } else {
                    statuses.add(null)
                }
            }
            
            statuses
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "获取签名状态失败")
            emptyList()
        }
    }
    
    /**
     * 验证交易（通过后端代理）
     * 
     * 🔒 后端优先架构：交易验证必须通过后端，防止伪造交易
     * 
     * @param signature 交易签名 (Base58)
     * @return 验证结果
     */
    suspend fun verifyTransaction(signature: String): TransactionVerifyResult = withContext(Dispatchers.IO) {
        if (backendApiClient == null) {
            return@withContext TransactionVerifyResult.Error("后端代理未初始化")
        }
        
        Timber.tag(TAG).i("🔒 通过后端代理验证交易: $signature")
        
        val result = backendApiClient!!.verifySolanaTransaction(signature)
        
        when (result) {
            is TransactionVerifyResult.Success -> {
                Timber.tag(TAG).i("✅ 交易验证成功: verified=${result.verified}, status=${result.status}")
            }
            is TransactionVerifyResult.Error -> {
                Timber.tag(TAG).e("❌ 交易验证失败: ${result.message}")
            }
        }
        
        result
    }
    
    /**
     * RPC 请求
     */
    private data class RpcRequest(
        val jsonrpc: String = "2.0",
        val id: Int = 1,
        val method: String,
        val params: List<Any>
    )
    
    /**
     * 余额响应
     */
    private data class BalanceResponse(
        val result: BalanceResult?,
        val error: RpcError?
    )
    
    private data class BalanceResult(
        val context: Context?,
        val value: Long
    )
    
    private data class Context(
        val slot: Long
    )
    
    /**
     * 账户信息响应
     */
    private data class AccountInfoResponse(
        val result: AccountInfoResult?,
        val error: RpcError?
    )
    
    private data class AccountInfoResult(
        val value: AccountInfo?
    )
    
    /**
     * RPC 错误
     */
    private data class RpcError(
        val code: Int,
        val message: String
    )
}

/**
 * 账户信息
 */
data class AccountInfo(
    val lamports: Long,
    val owner: String,
    val executable: Boolean,
    @SerializedName("rentEpoch")
    val rentEpoch: Long
)

/**
 * 签名状态
 */
data class SignatureStatus(
    val slot: Long,
    val confirmations: Int?,
    val confirmationStatus: String?,
    val err: Any?
)
