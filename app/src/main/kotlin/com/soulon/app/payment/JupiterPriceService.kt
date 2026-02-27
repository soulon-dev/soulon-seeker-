package com.soulon.app.payment

import android.content.Context
import com.soulon.app.BuildConfig
import com.soulon.app.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jupiter 价格服务
 * 
 * 支持两种模式获取 Solana 生态代币的实时价格：
 * 
 * 1. Jupiter Ultra API（推荐）
 *    - 免费使用，需要 API Key（由后端保存，客户端不持有）
 *    - 通过交换报价获取实时价格
 *    - 文档: https://dev.jup.ag/docs/ultra-api
 * 
 * 2. Jupiter Price API V3（备选）
 *    - 需要 API Key（由后端保存，客户端不持有）
 *    - 直接获取代币价格
 *    - 文档: https://dev.jup.ag/docs/price/v3
 * 
 * API Key: 在 https://portal.jup.ag 获取后配置到后端 Workers Secrets
 */
class JupiterPriceService(private val context: Context) {
    
    companion object {
        private const val TAG = "JupiterPrice"
        
        private val JUPITER_PRICE_API_V3 = "${BuildConfig.BACKEND_BASE_URL}/api/v1/jupiter/price/v3"
        
        // 代币地址（Solana Mainnet）
        const val SOL_MINT = "So11111111111111111111111111111111111111112"  // Wrapped SOL
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
        const val JUP_MINT = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"   // Jupiter Token
        
        // SKR Token（TODO: 替换为实际部署后的地址）
        const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        
        // 缓存有效期（5分钟）
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L
        
        // 默认价格（API 失败时的回退值）
        private const val DEFAULT_SOL_PRICE_USD = 150.0
        private const val DEFAULT_SKR_PRICE_USD = 0.01
        
        // 查询限制：每次最多 50 个代币
        private const val MAX_IDS_PER_QUERY = 50
        
        @Volatile
        private var instance: JupiterPriceService? = null
        
        fun getInstance(context: Context): JupiterPriceService {
            return instance ?: synchronized(this) {
                instance ?: JupiterPriceService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * 代币价格数据（Jupiter Price API V3 响应格式）
     * 
     * @param mint 代币地址
     * @param symbol 代币符号
     * @param usdPrice USD 价格
     * @param decimals 小数位数
     * @param blockId 区块 ID（用于验证价格新鲜度）
     * @param priceChange24h 24小时价格变化百分比
     * @param timestamp 获取时间戳
     */
    data class TokenPrice(
        val mint: String,
        val symbol: String,
        val usdPrice: Double,
        val decimals: Int = 9,
        val blockId: Long? = null,
        val priceChange24h: Double? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() - timestamp > CACHE_DURATION_MS
        
        // 兼容旧代码
        val priceUsd: Double get() = usdPrice
    }
    
    /**
     * 汇率信息（以 USDC 为锚定）
     */
    data class ExchangeRates(
        val solPriceUsdc: Double,      // 1 SOL = ? USDC
        val skrPriceUsdc: Double,      // 1 SKR = ? USDC
        val solPriceUsd: Double,       // 1 SOL = ? USD
        val skrPriceUsd: Double,       // 1 SKR = ? USD
        val lastUpdated: Long = System.currentTimeMillis(),
        val isFromCache: Boolean = false
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() - lastUpdated > CACHE_DURATION_MS
        
        /**
         * 将 USDC 金额转换为 SOL
         */
        fun usdcToSol(usdc: Double): Double = usdc / solPriceUsdc
        
        /**
         * 将 USDC 金额转换为 SKR
         */
        fun usdcToSkr(usdc: Double): Double = usdc / skrPriceUsdc
        
        /**
         * 将 SOL 金额转换为 USDC
         */
        fun solToUsdc(sol: Double): Double = sol * solPriceUsdc
        
        /**
         * 将 SKR 金额转换为 USDC
         */
        fun skrToUsdc(skr: Double): Double = skr * skrPriceUsdc
    }
    
    // 价格缓存
    private val priceCache = mutableMapOf<String, TokenPrice>()
    private val cacheMutex = Mutex()
    
    // 汇率状态流
    private val _exchangeRates = MutableStateFlow(
        ExchangeRates(
            solPriceUsdc = DEFAULT_SOL_PRICE_USD,
            skrPriceUsdc = DEFAULT_SKR_PRICE_USD,
            solPriceUsd = DEFAULT_SOL_PRICE_USD,
            skrPriceUsd = DEFAULT_SKR_PRICE_USD,
            isFromCache = true
        )
    )
    val exchangeRates: StateFlow<ExchangeRates> = _exchangeRates.asStateFlow()
    
    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // 错误状态
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    /**
     * 获取最新汇率
     * 
     * @param forceRefresh 强制刷新（忽略缓存）
     * @return 汇率信息
     */
    suspend fun getExchangeRates(forceRefresh: Boolean = false): ExchangeRates {
        // 检查缓存
        if (!forceRefresh && !_exchangeRates.value.isExpired) {
            Timber.d("$TAG: 使用缓存汇率")
            return _exchangeRates.value
        }
        
        _isLoading.value = true
        _error.value = null
        
        return try {
            // 获取 SOL 和 SKR 的 USD 价格
            val solPrice = fetchTokenPrice(SOL_MINT, "SOL")
            val skrPrice = fetchTokenPrice(SKR_MINT, "SKR")
            
            // 计算以 USDC 为锚定的汇率（假设 1 USDC ≈ 1 USD）
            val rates = ExchangeRates(
                solPriceUsdc = solPrice?.priceUsd ?: DEFAULT_SOL_PRICE_USD,
                skrPriceUsdc = skrPrice?.priceUsd ?: DEFAULT_SKR_PRICE_USD,
                solPriceUsd = solPrice?.priceUsd ?: DEFAULT_SOL_PRICE_USD,
                skrPriceUsd = skrPrice?.priceUsd ?: DEFAULT_SKR_PRICE_USD,
                isFromCache = false
            )
            
            _exchangeRates.value = rates
            
            Timber.i("$TAG: 汇率已更新 - SOL: \$${rates.solPriceUsdc}, SKR: \$${rates.skrPriceUsdc}")
            
            rates
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取汇率失败，使用默认值")
            _error.value = e.message
            
            // 返回缓存或默认值
            _exchangeRates.value.copy(isFromCache = true)
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * 获取单个代币价格
     * 
     * Jupiter Price API V3 响应格式:
     * {
     *   "So11111111111111111111111111111111111111112": {
     *     "usdPrice": 147.4789340738336,
     *     "blockId": 348004023,
     *     "decimals": 9,
     *     "priceChange24h": 1.2907622140620008
     *   }
     * }
     */
    suspend fun fetchTokenPrice(mint: String, symbol: String): TokenPrice? = withContext(Dispatchers.IO) {
        // 检查缓存
        cacheMutex.withLock {
            priceCache[mint]?.takeIf { !it.isExpired }?.let {
                Timber.d("$TAG: 使用缓存价格 $symbol: \$${it.usdPrice}")
                return@withContext it
            }
        }
        
        try {
            val url = URL("$JUPITER_PRICE_API_V3?ids=$mint")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            }
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Timber.w("$TAG: API 响应错误: $responseCode")
                return@withContext null
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            
            // Jupiter Price API V3 格式: { "mint": { "usdPrice": ..., "decimals": ..., "blockId": ..., "priceChange24h": ... } }
            val tokenData = json.optJSONObject(mint)
            
            if (tokenData == null) {
                // 代币价格不可用（可能是未交易或被标记为可疑）
                Timber.w("$TAG: $symbol 价格不可用 - 可能未在近7天内交易或被标记")
                return@withContext null
            }
            
            val usdPrice = tokenData.optDouble("usdPrice")
            if (usdPrice.isNaN() || usdPrice <= 0) {
                Timber.w("$TAG: 无法解析 $symbol 价格")
                return@withContext null
            }
            
            val tokenPrice = TokenPrice(
                mint = mint,
                symbol = symbol,
                usdPrice = usdPrice,
                decimals = tokenData.optInt("decimals", 9),
                blockId = if (tokenData.has("blockId")) tokenData.optLong("blockId") else null,
                priceChange24h = if (tokenData.has("priceChange24h")) tokenData.optDouble("priceChange24h") else null
            )
            
            // 更新缓存
            cacheMutex.withLock {
                priceCache[mint] = tokenPrice
            }
            
            Timber.d("$TAG: $symbol 价格: \$${usdPrice} (24h: ${tokenPrice.priceChange24h?.let { "${if (it >= 0) "+" else ""}${String.format("%.2f", it)}%" } ?: "N/A"})")
            tokenPrice
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取 $symbol 价格失败")
            null
        }
    }
    
    /**
     * 批量获取代币价格
     * 
     * 注意: 每次最多查询 50 个代币
     */
    suspend fun fetchMultipleTokenPrices(mints: List<String>): Map<String, TokenPrice> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, TokenPrice>()
        
        // 分批处理（每批最多 50 个）
        val batches = mints.chunked(MAX_IDS_PER_QUERY)
        
        for (batch in batches) {
            try {
                val ids = batch.joinToString(",")
                val url = URL("$JUPITER_PRICE_API_V3?ids=$ids")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
                }
                
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Timber.w("$TAG: 批量价格 API 响应错误: $responseCode")
                    continue
                }
                
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                batch.forEach { mint ->
                    val tokenData = json.optJSONObject(mint)
                    if (tokenData != null) {
                        val usdPrice = tokenData.optDouble("usdPrice")
                        if (!usdPrice.isNaN() && usdPrice > 0) {
                            val tokenPrice = TokenPrice(
                                mint = mint,
                                symbol = mint.take(4), // 简化符号
                                usdPrice = usdPrice,
                                decimals = tokenData.optInt("decimals", 9),
                                blockId = if (tokenData.has("blockId")) tokenData.optLong("blockId") else null,
                                priceChange24h = if (tokenData.has("priceChange24h")) tokenData.optDouble("priceChange24h") else null
                            )
                            result[mint] = tokenPrice
                            
                            // 更新缓存
                            cacheMutex.withLock {
                                priceCache[mint] = tokenPrice
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: 批量获取价格失败")
            }
        }
        
        Timber.d("$TAG: 批量获取 ${result.size}/${mints.size} 个代币价格")
        result
    }
    
    /**
     * 获取代币 24 小时价格变化
     */
    suspend fun getPriceChange24h(mint: String): Double? {
        val price = fetchTokenPrice(mint, mint.take(4))
        return price?.priceChange24h
    }
    
    /**
     * 清除价格缓存
     */
    suspend fun clearCache() {
        cacheMutex.withLock {
            priceCache.clear()
        }
        _exchangeRates.value = ExchangeRates(
            solPriceUsdc = DEFAULT_SOL_PRICE_USD,
            skrPriceUsdc = DEFAULT_SKR_PRICE_USD,
            solPriceUsd = DEFAULT_SOL_PRICE_USD,
            skrPriceUsd = DEFAULT_SKR_PRICE_USD,
            isFromCache = true
        )
        Timber.d("$TAG: 价格缓存已清除")
    }
    
    /**
     * 格式化价格显示
     */
    fun formatPrice(amount: Double, decimals: Int = 2): String {
        return if (amount >= 1000) {
            String.format("%,.${decimals}f", amount)
        } else if (amount >= 1) {
            String.format("%.${decimals}f", amount)
        } else {
            // 小数点后保留更多位数
            String.format("%.${decimals + 2}f", amount)
        }
    }
    
    // ==================== 交易辅助方法 ====================
    
    /**
     * 计算支付所需的代币数量
     * 
     * @param usdAmount USD 金额
     * @param tokenMint 代币地址
     * @return 所需代币数量（考虑 decimals）
     */
    suspend fun calculateTokenAmount(usdAmount: Double, tokenMint: String): TokenAmount? {
        val price = fetchTokenPrice(tokenMint, tokenMint.take(4)) ?: return null
        
        val tokenAmount = usdAmount / price.usdPrice
        val rawAmount = (tokenAmount * Math.pow(10.0, price.decimals.toDouble())).toLong()
        
        return TokenAmount(
            mint = tokenMint,
            amount = tokenAmount,
            rawAmount = rawAmount,
            decimals = price.decimals,
            usdValue = usdAmount
        )
    }
    
    /**
     * 计算代币的 USD 价值
     * 
     * @param tokenAmount 代币数量
     * @param tokenMint 代币地址
     * @return USD 价值
     */
    suspend fun calculateUsdValue(tokenAmount: Double, tokenMint: String): Double? {
        val price = fetchTokenPrice(tokenMint, tokenMint.take(4)) ?: return null
        return tokenAmount * price.usdPrice
    }
    
    /**
     * 获取代币信息（用于显示）
     */
    suspend fun getTokenInfo(mint: String): TokenInfo? {
        val price = fetchTokenPrice(mint, getSymbolForMint(mint)) ?: return null
        
        return TokenInfo(
            mint = mint,
            symbol = getSymbolForMint(mint),
            usdPrice = price.usdPrice,
            decimals = price.decimals,
            priceChange24h = price.priceChange24h
        )
    }
    
    /**
     * 根据 mint 地址获取代币符号
     */
    private fun getSymbolForMint(mint: String): String {
        return when (mint) {
            SOL_MINT -> "SOL"
            USDC_MINT -> "USDC"
            USDT_MINT -> "USDT"
            JUP_MINT -> "JUP"
            SKR_MINT -> "SKR"
            else -> mint.take(4) + "..."
        }
    }
    
    /**
     * 验证价格是否在可接受范围内（防止价格操纵）
     * 
     * @param currentPrice 当前价格
     * @param expectedPrice 预期价格
     * @param slippageBps 允许的滑点（基点，100 = 1%）
     */
    fun isPriceAcceptable(currentPrice: Double, expectedPrice: Double, slippageBps: Int = 100): Boolean {
        if (expectedPrice <= 0) return false
        
        val slippagePercent = slippageBps / 10000.0
        val minPrice = expectedPrice * (1 - slippagePercent)
        val maxPrice = expectedPrice * (1 + slippagePercent)
        
        return currentPrice in minPrice..maxPrice
    }
    
    /**
     * 代币数量（用于交易）
     */
    data class TokenAmount(
        val mint: String,
        val amount: Double,      // 人类可读数量
        val rawAmount: Long,     // 链上数量（考虑 decimals）
        val decimals: Int,
        val usdValue: Double
    )
    
    /**
     * 代币信息
     */
    data class TokenInfo(
        val mint: String,
        val symbol: String,
        val usdPrice: Double,
        val decimals: Int,
        val priceChange24h: Double?
    ) {
        val priceChangeFormatted: String
            get() = priceChange24h?.let {
                "${if (it >= 0) "+" else ""}${String.format("%.2f", it)}%"
            } ?: "N/A"
        
        val isPriceUp: Boolean
            get() = (priceChange24h ?: 0.0) >= 0
    }
}
