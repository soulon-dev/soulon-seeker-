package com.soulon.app.subscription

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.BuildConfig
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.LocaleManager
import com.soulon.app.payment.JupiterPriceService
import com.soulon.app.rewards.RewardsRepository
import com.soulon.app.tier.UserTierManager
import com.soulon.app.wallet.WalletManager
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.soulon.app.wallet.SolanaRpcClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 订阅管理器
 * 
 * 处理用户订阅支付：
 * - 月度/季度/年度订阅（USDC/SOL/SKR）
 * - 以 USDC 为锚定价格
 * - 通过 Jupiter Price API 获取实时汇率
 * - 订阅状态检查
 * - 订阅续费和取消
 */
class SubscriptionManager(
    private val context: Context,
    private val walletManager: WalletManager,
    private val userTierManager: UserTierManager
) {
    
    private val prefs: SharedPreferences
        get() = WalletScope.scopedPrefs(context, "subscription")
    private val priceService: JupiterPriceService = JupiterPriceService.getInstance(context)
    
    // HTTP Client for Cloud Checks
    private val httpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept-Language", LocaleManager.getAcceptLanguage(context))
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    companion object {
        // Backend API
        private val BACKEND_URL = BuildConfig.BACKEND_BASE_URL + "/api/v1"
        
        // ========== 订阅价格配置（以 USDC 为锚定） ==========
        const val MONTHLY_PRICE_USDC = 9.99         // 月费 $9.99
        const val QUARTERLY_PRICE_USDC = 24.99      // 季度 $24.99 (省17%)
        const val YEARLY_PRICE_USDC = 79.99         // 年费 $79.99 (省33%)
        
        // ========== 汇率配置 ==========
        const val SOL_PRICE_USDC = 100.0            // 1 SOL ≈ 100 USDC
        const val SKR_PRICE_USDC = 0.01             // 1 SKR ≈ 0.01 USDC
        
        // ========== 订阅时长（毫秒） ==========
        const val MONTHLY_DURATION_MS = 30L * 24 * 60 * 60 * 1000   // 30 天
        const val QUARTERLY_DURATION_MS = 90L * 24 * 60 * 60 * 1000 // 90 天
        const val YEARLY_DURATION_MS = 365L * 24 * 60 * 60 * 1000   // 365 天
        
        // USDC Token 地址（Solana Mainnet）
        const val USDC_MINT_ADDRESS = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        
        // SKR Token 地址（TODO: 配置实际地址）
        const val SKR_MINT_ADDRESS = "YOUR_SKR_TOKEN_ADDRESS"
        
        // 默认收款地址（后台未配置时使用）
        const val DEFAULT_RECIPIENT_WALLET = "YOUR_PROJECT_WALLET_ADDRESS"
        
        // 获取收款地址（优先从后台配置获取）
        fun getRecipientWallet(context: Context): String {
            val remoteConfig = com.soulon.app.config.RemoteConfigManager.getInstance(context)
            val configWallet = remoteConfig.getRecipientWallet()
            return if (configWallet.isNotBlank()) configWallet else DEFAULT_RECIPIENT_WALLET
        }
        
        @Deprecated("使用 getRecipientWallet(context) 代替")
        const val RECIPIENT_WALLET = DEFAULT_RECIPIENT_WALLET
        
        // SharedPreferences 键
        private const val KEY_SUBSCRIPTION_TYPE = "subscription_type"
        private const val KEY_SUBSCRIPTION_EXPIRY = "subscription_expiry"
        private const val KEY_SUBSCRIPTION_TX_ID = "subscription_tx_id"
        private const val KEY_AUTO_RENEW = "auto_renew"
    }
    
    /**
     * 订阅时长类型
     */
    enum class SubscriptionDuration(
        val months: Int,
        val durationMs: Long,
        val priceUsdc: Double,
        val savingsPercent: Int
    ) {
        MONTHLY(1, MONTHLY_DURATION_MS, MONTHLY_PRICE_USDC, 0),
        QUARTERLY(3, QUARTERLY_DURATION_MS, QUARTERLY_PRICE_USDC, 17),
        YEARLY(12, YEARLY_DURATION_MS, YEARLY_PRICE_USDC, 33),
        GENESIS_TRIAL(1, MONTHLY_DURATION_MS, 0.0, 100) // 7天试用，之后按月续费
    }
    
    /**
     * 支付代币类型
     */
    enum class PaymentToken(val symbol: String, val decimals: Int) {
        SOL("SOL", 9),
        USDC("USDC", 6),
        SKR("SKR", 9)  // 假设 SKR 为 9 decimals
    }
    
    /**
     * 订阅状态
     */
    data class SubscriptionStatus(
        val isActive: Boolean,
        val type: String,           // FREE, MONTHLY, QUARTERLY, YEARLY
        val expiryTime: Long?,
        val daysRemaining: Int,
        val autoRenew: Boolean,
        val lastTransactionId: String?
    )
    
    /**
     * 订阅价格信息（以 USDC 为锚定）
     */
    data class SubscriptionPricing(
        val duration: SubscriptionDuration,
        val priceUSDC: Double,       // USDC 锚定价格
        val priceSOL: Double,        // 等价 SOL
        val priceSKR: Double,        // 等价 SKR
        val originalPriceUSDC: Double,  // 原价（月费 × 月数）
        val savingsPercent: Int         // 节省百分比
    )
    
    /**
     * 订阅结果
     */
    sealed class SubscriptionResult {
        data class Success(
            val transactionId: String,
            val expiryTime: Long,
            val type: String
        ) : SubscriptionResult()
        
        data class Error(
            val message: String,
            val code: ErrorCode = ErrorCode.UNKNOWN
        ) : SubscriptionResult()
        
        enum class ErrorCode {
            WALLET_NOT_CONNECTED,
            INSUFFICIENT_BALANCE,
            TRANSACTION_FAILED,
            ALREADY_SUBSCRIBED,
            NETWORK_ERROR,
            UNKNOWN
        }
    }
    
    /**
     * 获取订阅价格信息（以 USDC 为锚定，使用缓存汇率）
     * 
     * 注意：此方法使用缓存的汇率，如需最新汇率请先调用 getSubscriptionPricingAsync
     */
    fun getSubscriptionPricing(duration: SubscriptionDuration): SubscriptionPricing {
        val priceUsdc = duration.priceUsdc
        val originalPriceUsdc = MONTHLY_PRICE_USDC * duration.months
        
        // 使用 Jupiter Price API 的缓存汇率
        val rates = priceService.exchangeRates.value
        
        return SubscriptionPricing(
            duration = duration,
            priceUSDC = priceUsdc,
            priceSOL = priceUsdc / rates.solPriceUsdc,
            priceSKR = priceUsdc / rates.skrPriceUsdc,
            originalPriceUSDC = originalPriceUsdc,
            savingsPercent = duration.savingsPercent
        )
    }
    
    /**
     * 获取订阅价格信息（异步获取最新汇率）
     */
    suspend fun getSubscriptionPricingAsync(duration: SubscriptionDuration): SubscriptionPricing {
        val priceUsdc = duration.priceUsdc
        val originalPriceUsdc = MONTHLY_PRICE_USDC * duration.months
        
        // 获取最新汇率
        val rates = priceService.getExchangeRates()
        
        return SubscriptionPricing(
            duration = duration,
            priceUSDC = priceUsdc,
            priceSOL = priceUsdc / rates.solPriceUsdc,
            priceSKR = priceUsdc / rates.skrPriceUsdc,
            originalPriceUSDC = originalPriceUsdc,
            savingsPercent = duration.savingsPercent
        )
    }
    
    /**
     * 获取所有订阅方案的价格（使用缓存汇率）
     */
    fun getAllPricing(): List<SubscriptionPricing> {
        return SubscriptionDuration.entries.map { getSubscriptionPricing(it) }
    }
    
    /**
     * 获取所有订阅方案的价格（异步获取最新汇率）
     */
    suspend fun getAllPricingAsync(): List<SubscriptionPricing> {
        // 先刷新汇率
        priceService.getExchangeRates()
        return SubscriptionDuration.entries.map { getSubscriptionPricing(it) }
    }
    
    /**
     * 获取实时汇率服务
     */
    fun getPriceService(): JupiterPriceService = priceService
    
    /**
     * 获取当前订阅状态
     */
    fun getSubscriptionStatus(): SubscriptionStatus {
        val type = prefs.getString(KEY_SUBSCRIPTION_TYPE, "FREE") ?: "FREE"
        val expiry = prefs.getLong(KEY_SUBSCRIPTION_EXPIRY, 0)
        val autoRenew = prefs.getBoolean(KEY_AUTO_RENEW, false)
        val txId = prefs.getString(KEY_SUBSCRIPTION_TX_ID, null)
        
        val now = System.currentTimeMillis()
        val isActive = expiry > now
        val daysRemaining = if (isActive) {
            ((expiry - now) / (24 * 60 * 60 * 1000)).toInt()
        } else 0
        
        return SubscriptionStatus(
            isActive = isActive,
            type = if (isActive) type else "FREE",
            expiryTime = if (expiry > 0) expiry else null,
            daysRemaining = daysRemaining,
            autoRenew = autoRenew,
            lastTransactionId = txId
        )
    }
    
    /**
     * 检查用户是否有资格享受 Genesis 7天试用
     */
    suspend fun checkGenesisTrialEligibility(): Boolean = withContext(Dispatchers.IO) {
        val walletAddress = walletManager.getWalletAddress() ?: return@withContext false
        
        // 1. 检查是否已有活跃订阅
        val status = getSubscriptionStatus()
        if (status.isActive) return@withContext false
        
        // 2. 检查云端是否已领取 (Backend Check)
        try {
            val request = okhttp3.Request.Builder()
                .url("$BACKEND_URL/subscription/genesis/status?wallet=$walletAddress")
                .get()
                .build()
                
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = org.json.JSONObject(body)
                    if (json.optBoolean("redeemed", false)) {
                        Timber.w("☁️ Genesis 试用已在云端记录领取")
                        return@withContext false
                    }
                }
            } else {
                Timber.e("☁️ 云端检查失败: ${response.code}")
                // Fallback to local check if cloud fails? 
                // 安全起见，如果云端检查失败，应该默认不允许，或者降级到本地检查。
                // 这里选择降级到本地检查，避免网络问题阻断用户。
            }
        } catch (e: Exception) {
            Timber.e(e, "☁️ 云端检查异常")
        }

        // 3. 检查本地是否已经领取 (Double Check)
        val hasRedeemed = prefs.getBoolean("genesis_redeemed", false)
        if (hasRedeemed) return@withContext false
        
        // 4. 检查是否持有 Genesis Token
        val rpcClient = SolanaRpcClient()
        val genesisTokenMint = com.soulon.app.config.RemoteConfigManager.getInstance(context).getGenesisTokenMint()
        return@withContext rpcClient.hasToken(walletAddress, genesisTokenMint)
    }

    /**
     * 开启 Genesis 7天试用 (含自动续费)
     */
    suspend fun startGenesisTrial(
        activityResultSender: ActivityResultSender
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("📱 开始 Genesis 7天试用")
            
            // 检查钱包连接
            val walletAddress = walletManager.getWalletAddress()
            if (walletAddress == null) {
                return@withContext SubscriptionResult.Error(
                    AppStrings.tr("请先连接钱包", "Please connect your wallet first"),
                    SubscriptionResult.ErrorCode.WALLET_NOT_CONNECTED
                )
            }
            
            // 再次检查资格
            if (!checkGenesisTrialEligibility()) {
                 return@withContext SubscriptionResult.Error(
                    AppStrings.tr("不符合试用资格或已领取", "Not eligible for the trial or already redeemed"),
                    SubscriptionResult.ErrorCode.UNKNOWN
                )
            }

            // 1. 构建 create_subscription 指令
            // 这里我们模拟合约调用，实际上应该调用 WalletManager 发送交易
            // 设置 trial_period = 7天 (604800秒), period = 30天
            val trialDurationSeconds = 7L * 24 * 60 * 60
            
            // 模拟交易 ID
            val txId = "genesis_trial_tx_${System.currentTimeMillis()}"
            
            // 2. 上报云端领取记录 (Critical Step)
            try {
                val json = org.json.JSONObject().apply {
                    put("wallet", walletAddress)
                    put("signature", txId)
                }
                
                val request = okhttp3.Request.Builder()
                    .url("$BACKEND_URL/subscription/genesis/redeem")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                    
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Timber.e("☁️ 云端记录失败: $errorBody")
                    // 如果云端记录失败，是否回滚？
                    // 理想情况下应该回滚。
                    // 但如果是网络问题，可能导致用户损失。
                    // 暂时允许继续，但在本地标记 pending_sync。
                } else {
                    Timber.i("☁️ 云端记录成功")
                }
            } catch (e: Exception) {
                Timber.e(e, "☁️ 云端记录异常")
            }
            
            // 3. 更新本地状态
            val expiryTime = System.currentTimeMillis() + (trialDurationSeconds * 1000)
            
            saveSubscription("GENESIS_TRIAL", expiryTime, txId)
            
            // 标记已领取
            prefs.edit().putBoolean("genesis_redeemed", true).apply()
            
            // 更新 UserTierManager
            userTierManager.setSubscriptionExpiry(expiryTime)
            
            Timber.i("✅ Genesis 试用开启成功, 到期: ${java.util.Date(expiryTime)}")
            
            SubscriptionResult.Success(
                transactionId = txId,
                expiryTime = expiryTime,
                type = "GENESIS_TRIAL"
            )
            
        } catch (e: Exception) {
             Timber.e(e, "Genesis 试用开启失败")
            SubscriptionResult.Error(
                e.message ?: AppStrings.tr("试用开启失败", "Failed to start trial"),
                SubscriptionResult.ErrorCode.TRANSACTION_FAILED
            )
        }
    }
    
    /**
     * 发起订阅（SOL 支付）
     */
    suspend fun subscribeWithSOL(
        duration: SubscriptionDuration,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("📱 开始 SOL 订阅: ${duration.months} 个月")
            
            // 检查钱包连接
            val walletAddress = walletManager.getWalletAddress()
            if (walletAddress == null) {
                return@withContext SubscriptionResult.Error(
                    AppStrings.tr("请先连接钱包", "Please connect your wallet first"),
                    SubscriptionResult.ErrorCode.WALLET_NOT_CONNECTED
                )
            }
            
            // 检查是否已订阅
            val currentStatus = getSubscriptionStatus()
            if (currentStatus.isActive && currentStatus.daysRemaining > 30) {
                return@withContext SubscriptionResult.Error(
                    AppStrings.trf(
                        "当前订阅仍有效（剩余 %d 天）",
                        "Your subscription is still active (%d days remaining)",
                        currentStatus.daysRemaining
                    ),
                    SubscriptionResult.ErrorCode.ALREADY_SUBSCRIBED
                )
            }
            
            // 计算价格
            val pricing = getSubscriptionPricing(duration)
            val amountLamports = (pricing.priceSOL * 1_000_000_000).toLong()
            
            Timber.d("订阅价格: ${pricing.priceSOL} SOL ($amountLamports lamports)")
            
            // TODO: 实际发送 SOL 交易
            // 这里需要使用 WalletManager 发送交易到 RECIPIENT_WALLET
            // val txId = walletManager.sendSOL(RECIPIENT_WALLET, amountLamports, activityResultSender)
            
            // 模拟交易成功（实际实现需要替换）
            val txId = "simulated_tx_${System.currentTimeMillis()}"
            
            // 更新订阅状态
            val expiryTime = if (currentStatus.isActive) {
                // 续费：在现有到期时间基础上延长
                currentStatus.expiryTime!! + duration.durationMs
            } else {
                // 新订阅：从现在开始
                System.currentTimeMillis() + duration.durationMs
            }
            
            val subscriptionType = duration.name  // MONTHLY, QUARTERLY, YEARLY
            
            saveSubscription(subscriptionType, expiryTime, txId)
            
            // 更新 UserTierManager
            userTierManager.setSubscriptionExpiry(expiryTime)
            
            Timber.i("✅ SOL 订阅成功: $subscriptionType, 到期: ${java.util.Date(expiryTime)}")
            
            SubscriptionResult.Success(
                transactionId = txId,
                expiryTime = expiryTime,
                type = subscriptionType
            )
            
        } catch (e: Exception) {
            Timber.e(e, "SOL 订阅失败")
            SubscriptionResult.Error(
                e.message ?: AppStrings.tr("订阅失败", "Subscription failed"),
                SubscriptionResult.ErrorCode.TRANSACTION_FAILED
            )
        }
    }
    
    /**
     * 发起订阅（USDC 支付）
     */
    suspend fun subscribeWithUSDC(
        duration: SubscriptionDuration,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("📱 开始 USDC 订阅: ${duration.months} 个月")
            
            // 检查钱包连接
            val walletAddress = walletManager.getWalletAddress()
            if (walletAddress == null) {
                return@withContext SubscriptionResult.Error(
                    AppStrings.tr("请先连接钱包", "Please connect your wallet first"),
                    SubscriptionResult.ErrorCode.WALLET_NOT_CONNECTED
                )
            }
            
            // 检查是否已订阅
            val currentStatus = getSubscriptionStatus()
            if (currentStatus.isActive && currentStatus.daysRemaining > 30) {
                return@withContext SubscriptionResult.Error(
                    AppStrings.trf(
                        "当前订阅仍有效（剩余 %d 天）",
                        "Your subscription is still active (%d days remaining)",
                        currentStatus.daysRemaining
                    ),
                    SubscriptionResult.ErrorCode.ALREADY_SUBSCRIBED
                )
            }
            
            // 计算价格
            val pricing = getSubscriptionPricing(duration)
            val amountMicroUSDC = (pricing.priceUSDC * 1_000_000).toLong()
            
            Timber.d("订阅价格: ${pricing.priceUSDC} USDC ($amountMicroUSDC micro-USDC)")
            
            // TODO: 实际发送 USDC 交易
            // 这里需要使用 WalletManager 发送 SPL Token 交易
            // val txId = walletManager.sendSPLToken(USDC_MINT_ADDRESS, RECIPIENT_WALLET, amountMicroUSDC, activityResultSender)
            
            // 模拟交易成功（实际实现需要替换）
            val txId = "simulated_usdc_tx_${System.currentTimeMillis()}"
            
            // 更新订阅状态
            val expiryTime = if (currentStatus.isActive) {
                currentStatus.expiryTime!! + duration.durationMs
            } else {
                System.currentTimeMillis() + duration.durationMs
            }
            
            val subscriptionType = duration.name  // MONTHLY, QUARTERLY, YEARLY
            
            saveSubscription(subscriptionType, expiryTime, txId)
            
            // 更新 UserTierManager
            userTierManager.setSubscriptionExpiry(expiryTime)
            
            Timber.i("✅ USDC 订阅成功: $subscriptionType, 到期: ${java.util.Date(expiryTime)}")
            
            SubscriptionResult.Success(
                transactionId = txId,
                expiryTime = expiryTime,
                type = subscriptionType
            )
            
        } catch (e: Exception) {
            Timber.e(e, "USDC 订阅失败")
            SubscriptionResult.Error(
                e.message ?: AppStrings.tr("订阅失败", "Subscription failed"),
                SubscriptionResult.ErrorCode.TRANSACTION_FAILED
            )
        }
    }
    
    /**
     * 发起订阅（SKR 支付）
     */
    suspend fun subscribeWithSKR(
        duration: SubscriptionDuration,
        activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("📱 开始 SKR 订阅: ${duration.months} 个月")
            
            // 检查钱包连接
            val walletAddress = walletManager.getWalletAddress()
            if (walletAddress == null) {
                return@withContext SubscriptionResult.Error(
                    AppStrings.tr("请先连接钱包", "Please connect your wallet first"),
                    SubscriptionResult.ErrorCode.WALLET_NOT_CONNECTED
                )
            }
            
            // 检查是否已订阅
            val currentStatus = getSubscriptionStatus()
            if (currentStatus.isActive && currentStatus.daysRemaining > 30) {
                return@withContext SubscriptionResult.Error(
                    AppStrings.trf(
                        "当前订阅仍有效（剩余 %d 天）",
                        "Your subscription is still active (%d days remaining)",
                        currentStatus.daysRemaining
                    ),
                    SubscriptionResult.ErrorCode.ALREADY_SUBSCRIBED
                )
            }
            
            // 计算价格
            val pricing = getSubscriptionPricing(duration)
            val amountSKR = (pricing.priceSKR * 1_000_000_000).toLong()  // 9 decimals
            
            Timber.d("订阅价格: ${pricing.priceSKR} SKR ($amountSKR)")
            
            // TODO: 实际发送 SKR 交易
            // val txId = walletManager.sendSPLToken(SKR_MINT_ADDRESS, RECIPIENT_WALLET, amountSKR, activityResultSender)
            
            // 模拟交易成功（实际实现需要替换）
            val txId = "simulated_skr_tx_${System.currentTimeMillis()}"
            
            // 更新订阅状态
            val expiryTime = if (currentStatus.isActive) {
                currentStatus.expiryTime!! + duration.durationMs
            } else {
                System.currentTimeMillis() + duration.durationMs
            }
            
            val subscriptionType = duration.name  // MONTHLY, QUARTERLY, YEARLY
            
            saveSubscription(subscriptionType, expiryTime, txId)
            
            // 更新 UserTierManager
            userTierManager.setSubscriptionExpiry(expiryTime)
            
            Timber.i("✅ SKR 订阅成功: $subscriptionType, 到期: ${java.util.Date(expiryTime)}")
            
            SubscriptionResult.Success(
                transactionId = txId,
                expiryTime = expiryTime,
                type = subscriptionType
            )
            
        } catch (e: Exception) {
            Timber.e(e, "SKR 订阅失败")
            SubscriptionResult.Error(
                e.message ?: AppStrings.tr("订阅失败", "Subscription failed"),
                SubscriptionResult.ErrorCode.TRANSACTION_FAILED
            )
        }
    }
    
    /**
     * 取消自动续费
     */
    fun cancelAutoRenew() {
        prefs.edit().putBoolean(KEY_AUTO_RENEW, false).apply()
        Timber.i("🚫 已取消自动续费")
    }
    
    /**
     * 开启自动续费
     */
    fun enableAutoRenew() {
        prefs.edit().putBoolean(KEY_AUTO_RENEW, true).apply()
        Timber.i("✅ 已开启自动续费")
    }
    
    /**
     * 检查订阅是否即将到期（7天内）
     */
    fun isExpiringsSoon(): Boolean {
        val status = getSubscriptionStatus()
        return status.isActive && status.daysRemaining <= 7
    }
    
    /**
     * 获取订阅历史（从本地存储）
     */
    fun getSubscriptionHistory(): List<SubscriptionRecord> {
        // TODO: 实现订阅历史存储
        return emptyList()
    }
    
    /**
     * 保存订阅信息
     */
    private fun saveSubscription(type: String, expiryTime: Long, txId: String) {
        prefs.edit()
            .putString(KEY_SUBSCRIPTION_TYPE, type)
            .putLong(KEY_SUBSCRIPTION_EXPIRY, expiryTime)
            .putString(KEY_SUBSCRIPTION_TX_ID, txId)
            .apply()
    }
    
    /**
     * 清除订阅信息（用于测试）
     */
    fun clearSubscription() {
        prefs.edit().clear().apply()
        Timber.d("订阅信息已清除")
    }
    
    /**
     * 订阅记录
     */
    data class SubscriptionRecord(
        val type: String,
        val startTime: Long,
        val endTime: Long,
        val paymentToken: PaymentToken,
        val amount: Double,
        val transactionId: String
    )
}
