package com.soulon.app.payment

import android.content.Context
import com.soulon.app.BuildConfig
import com.soulon.app.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jupiter Ultra Swap API 服务
 * 
 * 基于 Jupiter Ultra API 实现代币交换和价格获取
 * 文档: https://dev.jup.ag/docs/ultra-api
 * 
 * 特点:
 * - 免费使用（只在实际交换时收 5-10 bps）
 * - RPC-less：无需维护 RPC 节点
 * - Gasless：自动支持无 Gas 交易
 * - 最佳执行价格：预测执行 + 滑点感知路由
 * - Sub-second 交易落地
 * 
 * API Key: 由后端通过 Workers Secrets 注入，客户端不持有 Key
 */
class JupiterUltraService(private val context: Context) {
    
    companion object {
        private const val TAG = "JupiterUltra"
        
        private val ULTRA_API_BASE = "${BuildConfig.BACKEND_BASE_URL}/api/v1/jupiter/ultra/v1"
        
        // 代币地址（Solana Mainnet）
        const val SOL_MINT = "So11111111111111111111111111111111111111112"
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
        
        // SKR Token（TODO: 替换为实际地址）
        const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        
        // 默认滑点（bps）
        private const val DEFAULT_SLIPPAGE_BPS = 50  // 0.5%
        
        // 价格缓存有效期
        private const val PRICE_CACHE_DURATION_MS = 30_000L  // 30 秒
        
        @Volatile
        private var instance: JupiterUltraService? = null
        
        fun getInstance(context: Context): JupiterUltraService {
            return instance ?: synchronized(this) {
                instance ?: JupiterUltraService(context.applicationContext).also { 
                    instance = it
                }
            }
        }
    }
    
    // 远程配置管理器
    private val remoteConfig = com.soulon.app.config.RemoteConfigManager.getInstance(context)
    
    /**
     * 获取收款钱包地址（从后台配置）
     */
    fun getRecipientWallet(): String {
        val wallet = remoteConfig.getRecipientWallet()
        return if (wallet.isNotBlank()) wallet else DEFAULT_RECIPIENT_WALLET
    }
    
    // 默认收款钱包（后台未配置时使用）
    private val DEFAULT_RECIPIENT_WALLET = "YOUR_PROJECT_WALLET_ADDRESS"
    
    // ==================== 数据类 ====================
    
    /**
     * 交换订单响应
     */
    data class SwapOrder(
        val requestId: String,
        val inputMint: String,
        val outputMint: String,
        val inAmount: Long,           // 输入数量（原始单位）
        val outAmount: Long,          // 输出数量（原始单位）
        val otherAmountThreshold: Long,
        val swapType: String,         // "aggregator" 或 "rfq"
        val slippageBps: Int,
        val priceImpactPct: Double?,
        val transaction: String?,     // Base64 编码的交易（需要 taker 参数）
        val priorityFee: Long?,
        val dynamicSlippageReport: DynamicSlippageReport?,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        /**
         * 计算实际价格（outputAmount / inputAmount）
         */
        fun getPrice(inputDecimals: Int, outputDecimals: Int): Double {
            val inAmountDecimal = inAmount.toDouble() / Math.pow(10.0, inputDecimals.toDouble())
            val outAmountDecimal = outAmount.toDouble() / Math.pow(10.0, outputDecimals.toDouble())
            return outAmountDecimal / inAmountDecimal
        }
    }
    
    /**
     * 动态滑点报告
     */
    data class DynamicSlippageReport(
        val slippageBps: Int,
        val otherAmount: Long?,
        val simulatedIncurredSlippageBps: Int?,
        val amplificationRatio: String?
    )
    
    /**
     * 交换执行结果
     */
    sealed class SwapResult {
        data class Success(
            val signature: String,
            val inputAmount: Long,
            val outputAmount: Long,
            val slot: Long?
        ) : SwapResult()
        
        data class Failed(
            val error: String,
            val code: String?
        ) : SwapResult()
        
        data class Pending(
            val requestId: String
        ) : SwapResult()
    }
    
    /**
     * 代币余额
     */
    data class TokenBalance(
        val mint: String,
        val symbol: String?,
        val balance: Long,
        val decimals: Int,
        val usdValue: Double?
    ) {
        val balanceFormatted: Double
            get() = balance.toDouble() / Math.pow(10.0, decimals.toDouble())
    }
    
    /**
     * 代币信息（Shield API）
     */
    data class TokenShield(
        val mint: String,
        val symbol: String?,
        val name: String?,
        val decimals: Int,
        val logoUri: String?,
        val isSuspicious: Boolean,
        val warnings: List<String>
    )
    
    // ==================== 价格缓存 ====================
    
    private data class PriceCache(
        val price: Double,
        val timestamp: Long
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() - timestamp > PRICE_CACHE_DURATION_MS
    }
    
    private val priceCache = mutableMapOf<String, PriceCache>()
    private val decimalsCache = mutableMapOf<String, Int>()
    
    // ==================== 状态流 ====================
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    
    // ==================== 核心方法 ====================
    
    /**
     * 获取交换订单（报价）
     * 
     * @param inputMint 输入代币地址
     * @param outputMint 输出代币地址
     * @param amount 输入数量（原始单位，如 lamports）
     * @param taker 用户钱包地址（可选，提供后返回可签名的交易）
     * @param slippageBps 滑点（基点）
     */
    suspend fun getOrder(
        inputMint: String,
        outputMint: String,
        amount: Long,
        taker: String? = null,
        slippageBps: Int = DEFAULT_SLIPPAGE_BPS
    ): SwapOrder? = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _lastError.value = null
        
        try {
            val urlBuilder = StringBuilder("$ULTRA_API_BASE/order")
                .append("?inputMint=$inputMint")
                .append("&outputMint=$outputMint")
                .append("&amount=$amount")
                .append("&slippageBps=$slippageBps")
            
            taker?.let { urlBuilder.append("&taker=$it") }
            
            val url = URL(urlBuilder.toString())
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
            }
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Timber.e("$TAG: Order API 错误: $responseCode - $errorStream")
                _lastError.value = "获取报价失败: $responseCode"
                return@withContext null
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            
            // 解析响应
            val order = parseOrderResponse(json)
            
            Timber.i("$TAG: 获取订单成功 - ${inputMint.take(8)}... → ${outputMint.take(8)}... | 数量: $amount → ${order?.outAmount}")
            
            order
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取订单失败")
            _lastError.value = e.message
            null
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * 执行交换订单
     * 
     * @param requestId 订单请求 ID
     * @param signedTransaction Base64 编码的已签名交易
     */
    suspend fun executeOrder(
        requestId: String,
        signedTransaction: String
    ): SwapResult = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _lastError.value = null
        
        try {
            val url = URL("$ULTRA_API_BASE/execute")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            }
            
            // 构建请求体
            val requestBody = JSONObject().apply {
                put("requestId", requestId)
                put("signedTransaction", signedTransaction)
            }
            
            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray())
            }
            
            val responseCode = connection.responseCode
            val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            
            val json = JSONObject(response)
            
            when (json.optString("status")) {
                "Success" -> {
                    val signature = json.optString("signature")
                    val inputAmount = json.optLong("inputAmount", 0)
                    val outputAmount = json.optLong("outputAmount", 0)
                    val slot = if (json.has("slot")) json.optLong("slot") else null
                    
                    Timber.i("$TAG: 交换成功 - 签名: $signature")
                    SwapResult.Success(signature, inputAmount, outputAmount, slot)
                }
                "Failed" -> {
                    val error = json.optString("error", "Unknown error")
                    val code = json.optString("code")
                    Timber.e("$TAG: 交换失败 - $error")
                    _lastError.value = error
                    SwapResult.Failed(error, code)
                }
                else -> {
                    // 可能还在处理中
                    SwapResult.Pending(requestId)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 执行交换失败")
            _lastError.value = e.message
            SwapResult.Failed(e.message ?: "Unknown error", null)
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * 获取代币价格（通过获取 1 USDC 的报价来计算）
     * 
     * @param tokenMint 代币地址
     * @return USD 价格
     */
    suspend fun getTokenPriceUsd(tokenMint: String): Double? {
        // 检查缓存
        priceCache[tokenMint]?.takeIf { !it.isExpired }?.let {
            return it.price
        }
        
        // USDC 价格固定为 1
        if (tokenMint == USDC_MINT) {
            return 1.0
        }
        
        // 通过获取 1 USDC 换多少目标代币来计算价格
        val order = getOrder(
            inputMint = USDC_MINT,
            outputMint = tokenMint,
            amount = 1_000_000  // 1 USDC (6 decimals)
        ) ?: return null
        
        // 价格 = 1 / (输出数量 / 10^decimals)
        val decimals = getTokenDecimals(tokenMint)
        val outputAmount = order.outAmount.toDouble() / Math.pow(10.0, decimals.toDouble())
        if (!outputAmount.isFinite() || outputAmount <= 0.0) {
            Timber.w("$TAG: 获取价格失败（输出数量异常） mint=${tokenMint.take(8)}... outAmount=${order.outAmount} decimals=$decimals")
            return null
        }
        val price = 1.0 / outputAmount
        if (!price.isFinite() || price <= 0.0) {
            Timber.w("$TAG: 获取价格失败（价格异常） mint=${tokenMint.take(8)}... price=$price")
            return null
        }
        
        // 缓存价格
        priceCache[tokenMint] = PriceCache(price, System.currentTimeMillis())
        
        Timber.d("$TAG: ${tokenMint.take(8)}... 价格: \$$price")
        return price
    }
    
    /**
     * 获取 SOL/USDC 汇率
     */
    suspend fun getSolUsdcRate(): Double? {
        return getTokenPriceUsd(SOL_MINT)
    }
    
    /**
     * 获取 SKR/USDC 汇率
     */
    suspend fun getSkrUsdcRate(): Double? {
        return getTokenPriceUsd(SKR_MINT)
    }
    
    /**
     * 计算支付订阅所需的代币数量
     * 
     * @param usdcAmount USDC 金额
     * @param paymentMint 支付代币地址
     * @return 所需代币数量（原始单位）
     */
    suspend fun calculatePaymentAmount(usdcAmount: Double, paymentMint: String): Long? {
        if (paymentMint == USDC_MINT) {
            return (usdcAmount * 1_000_000).toLong()  // 6 decimals
        }
        
        val usdcRaw = (usdcAmount * 1_000_000).toLong()
        
        // 获取报价
        val order = getOrder(
            inputMint = paymentMint,
            outputMint = USDC_MINT,
            amount = when (paymentMint) {
                SOL_MINT -> 1_000_000_000L  // 1 SOL
                else -> {
                    val decimals = getTokenDecimals(paymentMint)
                    Math.pow(10.0, decimals.toDouble()).toLong()
                }
            }
        ) ?: return null
        
        // 计算比率
        val rate = order.outAmount.toDouble() / order.inAmount.toDouble()
        if (!rate.isFinite() || rate <= 0.0) {
            Timber.w("$TAG: 汇率异常，无法计算支付数量 paymentMint=${paymentMint.take(8)}... rate=$rate")
            return null
        }
        
        // 需要的输入数量
        val requiredInput = usdcRaw / rate
        return requiredInput.toLong()
    }
    
    // ==================== 自动换币支付 ====================
    
    /**
     * 订阅支付订单
     * 
     * 包含自动换币信息（如果需要）
     */
    data class SubscriptionPaymentOrder(
        val paymentMint: String,           // 支付代币
        val paymentAmount: Long,           // 支付数量（原始单位）
        val paymentAmountFormatted: Double,// 支付数量（人类可读）
        val usdcAmount: Double,            // USDC 等值金额
        val needsSwap: Boolean,            // 是否需要换币
        val swapOrder: SwapOrder?,         // 换币订单（如果需要）
        val recipientAddress: String,      // 收款地址
        val memo: String                   // 交易备注
    )
    
    /**
     * 支付结果
     */
    sealed class PaymentResult {
        data class Success(
            val signature: String,
            val paidAmount: Double,
            val paidToken: String,
            val usdcReceived: Double?
        ) : PaymentResult()
        
        data class Failed(
            val error: String,
            val code: String? = null
        ) : PaymentResult()
    }
    
    /**
     * 创建订阅支付订单
     * 
     * 自动处理换币逻辑：
     * - 如果用户选择 USDC 支付，直接转账
     * - 如果用户选择 SOL/SKR 支付，先换成 USDC 再转账
     * 
     * @param usdcAmount 订阅价格（USDC）
     * @param paymentMint 用户选择的支付代币
     * @param takerAddress 用户钱包地址
     * @param recipientAddress 收款钱包地址
     * @param memo 交易备注（如 "Soulon 月费会员"）
     */
    suspend fun createSubscriptionPaymentOrder(
        usdcAmount: Double,
        paymentMint: String,
        takerAddress: String,
        recipientAddress: String,
        memo: String
    ): SubscriptionPaymentOrder? {
        Timber.i("$TAG: 创建订阅支付订单 - \$${usdcAmount} USDC, 支付代币: ${paymentMint.take(8)}...")
        
        // USDC 直接支付
        if (paymentMint == USDC_MINT) {
            val usdcRaw = (usdcAmount * 1_000_000).toLong()
            return SubscriptionPaymentOrder(
                paymentMint = USDC_MINT,
                paymentAmount = usdcRaw,
                paymentAmountFormatted = usdcAmount,
                usdcAmount = usdcAmount,
                needsSwap = false,
                swapOrder = null,
                recipientAddress = recipientAddress,
                memo = memo
            )
        }
        
        // 需要换币：获取 SOL/SKR → USDC 的报价
        // 多加 1% 作为缓冲（考虑滑点）
        val targetUsdcRaw = (usdcAmount * 1_000_000 * 1.01).toLong()
        
        // 先估算需要多少输入代币
        val estimatedInputAmount = calculatePaymentAmount(usdcAmount * 1.02, paymentMint) 
            ?: return null
        
        // 获取精确报价
        val swapOrder = getOrder(
            inputMint = paymentMint,
            outputMint = USDC_MINT,
            amount = estimatedInputAmount,
            taker = takerAddress
        ) ?: return null
        
        // 验证输出是否足够
        if (swapOrder.outAmount < targetUsdcRaw) {
            // 输出不够，需要更多输入
            val ratio = targetUsdcRaw.toDouble() / swapOrder.outAmount.toDouble()
            val adjustedInput = (estimatedInputAmount * ratio * 1.02).toLong()
            
            // 重新获取报价
            val adjustedOrder = getOrder(
                inputMint = paymentMint,
                outputMint = USDC_MINT,
                amount = adjustedInput,
                taker = takerAddress
            ) ?: return null
            
            val inputDecimals = getTokenDecimals(paymentMint)
            val inputFormatted = adjustedOrder.inAmount.toDouble() / Math.pow(10.0, inputDecimals.toDouble())
            
            Timber.i("$TAG: 换币订单 - 输入: $inputFormatted ${getSymbolForMint(paymentMint)}, 输出: ${adjustedOrder.outAmount / 1_000_000.0} USDC")
            
            return SubscriptionPaymentOrder(
                paymentMint = paymentMint,
                paymentAmount = adjustedOrder.inAmount,
                paymentAmountFormatted = inputFormatted,
                usdcAmount = usdcAmount,
                needsSwap = true,
                swapOrder = adjustedOrder,
                recipientAddress = recipientAddress,
                memo = memo
            )
        }
        
        val inputDecimals = getTokenDecimals(paymentMint)
        val inputFormatted = swapOrder.inAmount.toDouble() / Math.pow(10.0, inputDecimals.toDouble())
        
        Timber.i("$TAG: 换币订单 - 输入: $inputFormatted ${getSymbolForMint(paymentMint)}, 输出: ${swapOrder.outAmount / 1_000_000.0} USDC")
        
        return SubscriptionPaymentOrder(
            paymentMint = paymentMint,
            paymentAmount = swapOrder.inAmount,
            paymentAmountFormatted = inputFormatted,
            usdcAmount = usdcAmount,
            needsSwap = true,
            swapOrder = swapOrder,
            recipientAddress = recipientAddress,
            memo = memo
        )
    }
    
    /**
     * 执行订阅支付
     * 
     * 自动处理：
     * 1. 如果需要换币，先执行 swap 交易
     * 2. 将 USDC 转账到收款地址
     * 
     * @param order 支付订单
     * @param signTransaction 签名函数（由钱包提供）
     */
    suspend fun executeSubscriptionPayment(
        order: SubscriptionPaymentOrder,
        signTransaction: suspend (String) -> String?  // Base64 交易 → 签名后的 Base64 交易
    ): PaymentResult {
        Timber.i("$TAG: 执行订阅支付 - 需要换币: ${order.needsSwap}")
        
        _isLoading.value = true
        _lastError.value = null
        
        try {
            if (order.needsSwap && order.swapOrder != null) {
                // 需要换币
                val transaction = order.swapOrder.transaction
                if (transaction.isNullOrBlank()) {
                    return PaymentResult.Failed("交易数据为空", "NO_TRANSACTION")
                }
                
                // 签名交易
                val signedTransaction = signTransaction(transaction)
                if (signedTransaction.isNullOrBlank()) {
                    return PaymentResult.Failed("用户取消签名", "USER_CANCELLED")
                }
                
                // 执行换币
                val swapResult = executeOrder(order.swapOrder.requestId, signedTransaction)
                
                return when (swapResult) {
                    is SwapResult.Success -> {
                        val outputUsdc = swapResult.outputAmount / 1_000_000.0
                        Timber.i("$TAG: 换币成功 - 获得 $outputUsdc USDC, 签名: ${swapResult.signature}")
                        
                        // TODO: 第二步 - 将 USDC 转账到收款地址
                        // 目前 Ultra API 的 swap 会直接将输出发送到 taker 地址
                        // 如果需要转账到其他地址，需要额外的转账交易
                        
                        PaymentResult.Success(
                            signature = swapResult.signature,
                            paidAmount = order.paymentAmountFormatted,
                            paidToken = getSymbolForMint(order.paymentMint),
                            usdcReceived = outputUsdc
                        )
                    }
                    is SwapResult.Failed -> {
                        Timber.e("$TAG: 换币失败 - ${swapResult.error}")
                        PaymentResult.Failed(swapResult.error, swapResult.code)
                    }
                    is SwapResult.Pending -> {
                        // 交易还在处理中
                        PaymentResult.Failed("交易处理中，请稍后查询", "PENDING")
                    }
                }
            } else {
                // 直接 USDC 转账
                // TODO: 实现 USDC 直接转账逻辑
                // 目前返回需要外部处理
                return PaymentResult.Failed("USDC 直接转账请使用钱包", "DIRECT_TRANSFER")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 执行支付失败")
            _lastError.value = e.message
            return PaymentResult.Failed(e.message ?: "未知错误")
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * 一键支付（推荐使用）
     * 
     * 完整的支付流程：
     * 1. 创建支付订单
     * 2. 签名交易
     * 3. 执行支付
     * 
     * @param usdcAmount 订阅价格（USDC）
     * @param paymentMint 支付代币
     * @param takerAddress 用户钱包地址
     * @param recipientAddress 收款地址
     * @param memo 交易备注
     * @param signTransaction 签名函数
     */
    suspend fun oneClickPayment(
        usdcAmount: Double,
        paymentMint: String,
        takerAddress: String,
        recipientAddress: String,
        memo: String,
        signTransaction: suspend (String) -> String?
    ): PaymentResult {
        Timber.i("$TAG: 一键支付 - \$${usdcAmount} USDC, 使用 ${getSymbolForMint(paymentMint)}")
        
        // 1. 创建支付订单
        val order = createSubscriptionPaymentOrder(
            usdcAmount = usdcAmount,
            paymentMint = paymentMint,
            takerAddress = takerAddress,
            recipientAddress = recipientAddress,
            memo = memo
        ) ?: return PaymentResult.Failed("创建支付订单失败")
        
        // 2. 执行支付
        return executeSubscriptionPayment(order, signTransaction)
    }
    
    /**
     * 获取支付预览信息
     * 
     * 用于在支付前显示给用户确认
     */
    suspend fun getPaymentPreview(
        usdcAmount: Double,
        paymentMint: String,
        takerAddress: String
    ): PaymentPreview? {
        if (paymentMint == USDC_MINT) {
            return PaymentPreview(
                paymentToken = "USDC",
                paymentAmount = usdcAmount,
                usdcEquivalent = usdcAmount,
                priceImpact = null,
                slippageBps = 0,
                needsSwap = false,
                estimatedFee = 0.0
            )
        }
        
        // 获取换币报价
        val order = createSubscriptionPaymentOrder(
            usdcAmount = usdcAmount,
            paymentMint = paymentMint,
            takerAddress = takerAddress,
            recipientAddress = "", // 预览不需要实际地址
            memo = ""
        ) ?: return null
        
        return PaymentPreview(
            paymentToken = getSymbolForMint(paymentMint),
            paymentAmount = order.paymentAmountFormatted,
            usdcEquivalent = usdcAmount,
            priceImpact = order.swapOrder?.priceImpactPct,
            slippageBps = order.swapOrder?.slippageBps ?: 0,
            needsSwap = true,
            estimatedFee = (order.paymentAmountFormatted * 0.001) // 估算 0.1% 手续费
        )
    }
    
    /**
     * 支付预览信息
     */
    data class PaymentPreview(
        val paymentToken: String,      // 支付代币符号
        val paymentAmount: Double,     // 支付数量
        val usdcEquivalent: Double,    // USDC 等值
        val priceImpact: Double?,      // 价格影响
        val slippageBps: Int,          // 滑点
        val needsSwap: Boolean,        // 是否需要换币
        val estimatedFee: Double       // 预估手续费
    ) {
        val priceImpactFormatted: String
            get() = priceImpact?.let { String.format("%.2f%%", it) } ?: "N/A"
        
        val slippageFormatted: String
            get() = String.format("%.2f%%", slippageBps / 100.0)
    }
    
    private fun getSymbolForMint(mint: String): String {
        return when (mint) {
            SOL_MINT -> "SOL"
            USDC_MINT -> "USDC"
            USDT_MINT -> "USDT"
            SKR_MINT -> "SKR"
            else -> mint.take(4) + "..."
        }
    }
    
    /**
     * 获取用户代币余额
     * 
     * @param walletAddress 钱包地址
     * @param mints 要查询的代币地址列表（可选）
     */
    suspend fun getHoldings(
        walletAddress: String,
        mints: List<String>? = null
    ): List<TokenBalance> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = StringBuilder("$ULTRA_API_BASE/balances")
                .append("?wallet=$walletAddress")
            
            mints?.let { urlBuilder.append("&mints=${it.joinToString(",")}") }
            
            val url = URL(urlBuilder.toString())
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            }
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext emptyList()
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            
            // 解析余额
            val balances = mutableListOf<TokenBalance>()
            val tokens = json.optJSONArray("tokens") ?: return@withContext emptyList()
            
            for (i in 0 until tokens.length()) {
                val token = tokens.getJSONObject(i)
                balances.add(TokenBalance(
                    mint = token.getString("mint"),
                    symbol = token.optString("symbol"),
                    balance = token.getLong("balance"),
                    decimals = token.getInt("decimals"),
                    usdValue = if (token.has("usdValue")) token.getDouble("usdValue") else null
                ))
            }
            
            balances
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取余额失败")
            emptyList()
        }
    }
    
    /**
     * 获取代币安全信息
     */
    suspend fun getTokenShield(mint: String): TokenShield? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$ULTRA_API_BASE/shield?mints=$mint")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            }
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val tokenData = json.optJSONObject(mint) ?: return@withContext null
            
            val warnings = mutableListOf<String>()
            tokenData.optJSONArray("warnings")?.let { arr ->
                for (i in 0 until arr.length()) {
                    warnings.add(arr.getString(i))
                }
            }
            
            TokenShield(
                mint = mint,
                symbol = tokenData.optString("symbol"),
                name = tokenData.optString("name"),
                decimals = tokenData.optInt("decimals", 9),
                logoUri = tokenData.optString("logoUri"),
                isSuspicious = tokenData.optBoolean("isSuspicious", false),
                warnings = warnings
            )
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取代币信息失败")
            null
        }
    }
    
    // ==================== 辅助方法 ====================
    
    private fun parseOrderResponse(json: JSONObject): SwapOrder? {
        return try {
            val dynamicSlippageJson = json.optJSONObject("dynamicSlippageReport")
            val dynamicSlippage = dynamicSlippageJson?.let {
                DynamicSlippageReport(
                    slippageBps = it.optInt("slippageBps"),
                    otherAmount = if (it.has("otherAmount")) it.optLong("otherAmount") else null,
                    simulatedIncurredSlippageBps = if (it.has("simulatedIncurredSlippageBps")) 
                        it.optInt("simulatedIncurredSlippageBps") else null,
                    amplificationRatio = it.optString("amplificationRatio")
                )
            }
            
            SwapOrder(
                requestId = json.getString("requestId"),
                inputMint = json.getString("inputMint"),
                outputMint = json.getString("outputMint"),
                inAmount = json.getLong("inAmount"),
                outAmount = json.getLong("outAmount"),
                otherAmountThreshold = json.optLong("otherAmountThreshold", 0),
                swapType = json.optString("swapType", "aggregator"),
                slippageBps = json.optInt("slippageBps", DEFAULT_SLIPPAGE_BPS),
                priceImpactPct = if (json.has("priceImpactPct")) json.optDouble("priceImpactPct") else null,
                transaction = json.optString("transaction").takeIf { it.isNotBlank() },
                priorityFee = if (json.has("priorityFee")) json.optLong("priorityFee") else null,
                dynamicSlippageReport = dynamicSlippage
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 解析订单响应失败")
            null
        }
    }
    
    private suspend fun getTokenDecimals(mint: String): Int {
        return when (mint) {
            SOL_MINT -> 9
            USDC_MINT -> 6
            USDT_MINT -> 6
            SKR_MINT -> 6
            else -> {
                decimalsCache[mint]?.let { return it }
                val decimals = getTokenShield(mint)?.decimals?.takeIf { it in 0..18 } ?: 9
                decimalsCache[mint] = decimals
                decimals
            }
        }
    }
    
    /**
     * 清除价格缓存
     */
    fun clearPriceCache() {
        priceCache.clear()
        decimalsCache.clear()
        _lastError.value = null
        Timber.d("$TAG: 价格缓存已清除")
    }

    fun clearLastError() {
        _lastError.value = null
    }
}
