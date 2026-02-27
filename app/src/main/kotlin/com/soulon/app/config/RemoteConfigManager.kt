package com.soulon.app.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.soulon.app.BuildConfig
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.LocaleManager
import com.soulon.app.security.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程配置管理器
 * 从后台管理系统拉取配置，实现后台修改实时同步到客户端
 */
class RemoteConfigManager(private val context: Context) {
    
    companion object {
        private const val TAG = "RemoteConfigManager"
        private val BASE_URL = BuildConfig.BACKEND_BASE_URL
        private const val PREFS_NAME = "remote_config_prefs"
        private const val KEY_LAST_SYNC = "last_sync_time"
        private const val KEY_CONFIG_CACHE = "config_cache"
        
        // 同步间隔（1分钟 - 实时同步）
        private const val SYNC_INTERVAL_MS = 60 * 1000L
        
        // 单例实例（用于全局访问）
        @Volatile
        private var INSTANCE: RemoteConfigManager? = null
        
        fun getInstance(context: Context): RemoteConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RemoteConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 配置更新监听器
    private val configListeners = mutableListOf<OnConfigUpdateListener>()
    
    interface OnConfigUpdateListener {
        fun onConfigUpdated(updatedKeys: Set<String>)
    }
    
    fun addConfigListener(listener: OnConfigUpdateListener) {
        configListeners.add(listener)
    }
    
    fun removeConfigListener(listener: OnConfigUpdateListener) {
        configListeners.remove(listener)
    }
    
    private fun notifyConfigUpdate(updatedKeys: Set<String>) {
        configListeners.forEach { it.onConfigUpdated(updatedKeys) }
    }
    
    private val prefs: SharedPreferences = SecurePrefs.create(context, PREFS_NAME)
    
    // 缓存的配置
    private var configCache: Map<String, Any> = loadCacheFromPrefs()
    
    /**
     * 从 SharedPreferences 加载缓存的配置
     */
    private fun loadCacheFromPrefs(): Map<String, Any> {
        val cached = prefs.getString(KEY_CONFIG_CACHE, null) ?: return emptyMap()
        return try {
            val json = JSONObject(cached)
            val map = mutableMapOf<String, Any>()
            json.keys().forEach { key ->
                map[key] = json.get(key)
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "加载缓存配置失败", e)
            emptyMap()
        }
    }
    
    /**
     * 保存配置到 SharedPreferences
     */
    private fun saveCacheToPrefs(config: Map<String, Any>) {
        val json = JSONObject(config)
        prefs.edit()
            .putString(KEY_CONFIG_CACHE, json.toString())
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply()
    }
    
    /**
     * 检查是否需要同步
     */
    fun needsSync(): Boolean {
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0)
        return System.currentTimeMillis() - lastSync > SYNC_INTERVAL_MS
    }
    
    /**
     * 从后台拉取最新配置
     */
    suspend fun syncFromBackend(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val base = URL(BASE_URL)
            if (base.protocol != "https") {
                return@withContext Result.failure(IllegalStateException("BACKEND_BASE_URL 必须为 https"))
            }
            val url = URL("$BASE_URL/api/v1/config/client")
            if (url.protocol != "https" || url.host != base.host) {
                return@withContext Result.failure(IllegalStateException("不允许跨域或非 https 配置源"))
            }
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                val newConfig = mutableMapOf<String, Any>()
                
                // 解析配置
                if (json.has("configs")) {
                    val configs = json.getJSONObject("configs")
                    configs.keys().forEach { category ->
                        val categoryConfigs = configs.getJSONArray(category)
                        for (i in 0 until categoryConfigs.length()) {
                            val item = categoryConfigs.getJSONObject(i)
                            val key = item.getString("configKey")
                            val value = item.getString("configValue")
                            val type = item.optString("valueType", "string")
                            
                            // 根据类型转换值
                            newConfig[key] = when (type) {
                                "number" -> value.toDoubleOrNull() ?: value
                                "boolean" -> value.toBoolean()
                                "json" -> value // JSON 保持字符串格式
                                else -> value
                            }
                        }
                    }
                }
                
                // 检测变更的配置项
                val updatedKeys = mutableSetOf<String>()
                for ((key, value) in newConfig) {
                    val oldValue = configCache[key]
                    if (oldValue != value) {
                        updatedKeys.add(key)
                        Log.d(TAG, "配置已更新: $key = $value (旧值: $oldValue)")
                    }
                }
                
                configCache = newConfig
                saveCacheToPrefs(newConfig)
                
                // 通知监听器
                if (updatedKeys.isNotEmpty()) {
                    Log.i(TAG, "检测到 ${updatedKeys.size} 项配置更新")
                    notifyConfigUpdate(updatedKeys)
                }
                
                Log.d(TAG, "配置同步成功，共 ${newConfig.size} 项")
                Result.success(true)
            } else {
                Log.e(TAG, "配置同步失败: $responseCode")
                Result.failure(Exception(AppStrings.trf("同步失败: %d", "Sync failed: %d", responseCode)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "配置同步异常", e)
            Result.failure(e)
        }
    }
    
    // ============================================
    // 配置获取方法
    // ============================================
    
    /**
     * 获取字符串配置
     */
    fun getString(key: String, default: String): String {
        return configCache[key]?.toString() ?: default
    }
    
    /**
     * 获取整数配置
     */
    fun getInt(key: String, default: Int): Int {
        val value = configCache[key]
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }
    
    /**
     * 获取长整数配置
     */
    fun getLong(key: String, default: Long): Long {
        val value = configCache[key]
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            else -> default
        }
    }
    
    /**
     * 获取浮点数配置
     */
    fun getDouble(key: String, default: Double): Double {
        val value = configCache[key]
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: default
            else -> default
        }
    }
    
    /**
     * 获取布尔配置
     */
    fun getBoolean(key: String, default: Boolean): Boolean {
        val value = configCache[key]
        return when (value) {
            is Boolean -> value
            is String -> value.toBoolean()
            else -> default
        }
    }
    
    /**
     * 获取 JSON 数组配置
     */
    fun getJsonArray(key: String): JSONArray? {
        val value = configCache[key]?.toString() ?: return null
        return try {
            JSONArray(value)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 获取 JSON 对象配置
     */
    fun getJsonObject(key: String): JSONObject? {
        val value = configCache[key]?.toString() ?: return null
        return try {
            JSONObject(value)
        } catch (e: Exception) {
            null
        }
    }
    
    // ============================================
    // 便捷方法：获取常用配置
    // ============================================
    
    // AI 配置
    fun getAiModel(): String = getString("ai.primary_model", "qwen-turbo")
    fun getAiFallbackModel(): String = getString("ai.fallback_model", "qwen-plus")
    fun getAiTemperature(): Double = getDouble("ai.temperature", 0.7)
    fun getAiMaxTokens(): Int = getInt("ai.max_tokens", 2000)
    
    // 配额配置
    fun getQuotaFree(): Long = getLong("quota.free", 1000000)
    fun getQuotaSubscriber(): Long = getLong("quota.subscriber", 5000000)
    fun getQuotaStaker(): Long = getLong("quota.staker", 20000000)
    
    // MEMO 积分配置
    fun getMemoBaseScore(): Int = getInt("memo.base_score", 10)
    fun getMemoPerToken(): Int = getInt("memo.memo_per_token", 1)
    fun getMemoMaxTokenCount(): Int = getInt("memo.max_token_count", 200)
    fun getMemoDailyFullRewardLimit(): Int = getInt("memo.daily_full_reward_limit", 50)
    fun getMemoFirstChatReward(): Int = getInt("memo.first_chat_reward", 30)
    
    // 共鸣奖励配置
    fun getResonanceSThreshold(): Int = getInt("memo.resonance_s_threshold", 90)
    fun getResonanceSBonus(): Int = getInt("memo.resonance_s_bonus", 100)
    fun getResonanceAThreshold(): Int = getInt("memo.resonance_a_threshold", 70)
    fun getResonanceABonus(): Int = getInt("memo.resonance_a_bonus", 30)
    fun getResonanceBThreshold(): Int = getInt("memo.resonance_b_threshold", 40)
    fun getResonanceBBonus(): Int = getInt("memo.resonance_b_bonus", 10)
    
    // Tier 配置
    fun getTierPoints(): List<Int> {
        val arr = getJsonArray("tier.points") ?: return listOf(0, 2500, 12000, 50000, 200000)
        return (0 until arr.length()).map { arr.getInt(it) }
    }
    
    fun getTierSovereign(): List<Int> {
        val arr = getJsonArray("tier.sovereign") ?: return listOf(0, 20, 40, 60, 80)
        return (0 until arr.length()).map { arr.getInt(it) }
    }
    
    fun getTierMultiplier(): List<Double> {
        val arr = getJsonArray("tier.multiplier") ?: return listOf(1.0, 1.5, 2.0, 3.0, 5.0)
        return (0 until arr.length()).map { arr.getDouble(it) }
    }
    
    fun getTierNames(): List<String> {
        val arr = getJsonArray("tier.names") ?: return listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond")
        return (0 until arr.length()).map { arr.getString(it) }
    }
    
    // 订阅配置
    fun getSubscriptionMonthlySol(): Double = getDouble("subscription.monthly_sol", 0.1)
    fun getSubscriptionMonthlyUsdc(): Double = getDouble("subscription.monthly_usdc", 9.99)
    fun getSubscriptionQuarterlyUsdc(): Double = getDouble("subscription.quarterly_usdc", 24.99)
    fun getSubscriptionYearlyUsdc(): Double = getDouble("subscription.yearly_usdc", 79.99)
    fun getSubscriptionYearlyDiscount(): Double = getDouble("subscription.yearly_discount", 0.8)
    
    // 订阅权益配置
    fun getSubscriptionMonthlyTokenMultiplier(): Float = getDouble("subscription.monthly_token_multiplier", 2.0).toFloat()
    fun getSubscriptionQuarterlyTokenMultiplier(): Float = getDouble("subscription.quarterly_token_multiplier", 3.0).toFloat()
    fun getSubscriptionYearlyTokenMultiplier(): Float = getDouble("subscription.yearly_token_multiplier", 5.0).toFloat()
    fun getSubscriptionMonthlyPointsMultiplier(): Float = getDouble("subscription.monthly_points_multiplier", 1.5).toFloat()
    fun getSubscriptionQuarterlyPointsMultiplier(): Float = getDouble("subscription.quarterly_points_multiplier", 2.0).toFloat()
    fun getSubscriptionYearlyPointsMultiplier(): Float = getDouble("subscription.yearly_points_multiplier", 3.0).toFloat()
    
    // 自动续费配置
    fun getSubscriptionAutoRenewEnabled(): Boolean = getBoolean("subscription.auto_renew_enabled", true)
    fun getSubscriptionFirstMonthDiscount(): Double = getDouble("subscription.first_month_discount", 0.5)
    fun getSubscriptionAutoRenewRequired(): Boolean = getBoolean("subscription.auto_renew_required", true)
    
    // 限速配置
    fun getRateLimitMaxMessageLength(): Int = getInt("ratelimit.max_message_length", 2000)
    fun getRateLimitMaxPerMinute(): Int = getInt("ratelimit.max_per_minute", 10)
    fun getRateLimitMaxPerHour(): Int = getInt("ratelimit.max_per_hour", 60)
    fun getRateLimitMinIntervalMs(): Long = getLong("ratelimit.min_interval_ms", 1000)
    
    // 区块链配置
    fun getBlockchainNetwork(): String = getString("blockchain.network", "mainnet")
    fun getBlockchainRpcUrl(): String = getString("blockchain.rpc_url", "https://api.mainnet-beta.solana.com")
    fun getGenesisTokenMint(): String = getString("blockchain.genesis_token_mint", "Your_Genesis_Token_Mint_Address_Here")
    fun getSubscriptionProgramId(): String = getString("blockchain.subscription_program_id", "Your_Program_Id_Here")
    
    // Jupiter API 配置
    fun getJupiterApiKey(): String = getString("jupiter.api_key", "")
    fun getJupiterUltraEnabled(): Boolean = getBoolean("jupiter.ultra_enabled", true)
    
    // 安全配置
    private val SAFE_RECIPIENT_WALLET = "QEF7NgbtJzWvzt17vWkNYK4Uq3zuiEsyi2xoPGSWCdU" // Hardcoded Safe Wallet
    private val CONFIG_PUBLIC_KEY = """
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyzrC22zhoRAyrx7PkBQS
zsPM8JBmfrqT/arASOnfeq5uVBdV+LGQJunKmbPRFjhg+BOC4QDSZ6eJNMyCEc/u
sJAzmyq8zQ3pzNgS+ccW9psmkCoLpTccG4dXR+hp0cHSgB30IvKRzD2SICP7vjQF
qZa3I+bk63Za5Ic1iuaY06QX7+AgbQLanC3I8vIR0EcDQRyNA4wgSu4Bb7yaGj2i
ku0493q5VlwjSNNlMXWga9MvsITdSNFX8XJl/TO0ypcNQiSyrR1TwB6AtHguu61x
jNHjkqB0nncfCA6klyWogMam8PI3td4+kOiM2oaj9+2xNtbvDN8AUNFK5WNEenFA
5wIDAQAB
-----END PUBLIC KEY-----
""".trimIndent()

    /**
     * 验证配置签名
     */
    private fun verifyConfigSignature(key: String, value: String, signature: String): Boolean {
        // Real RSA Verification
        try {
            val publicKeyPem = CONFIG_PUBLIC_KEY
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
                
            val encodedKey = android.util.Base64.decode(publicKeyPem, android.util.Base64.DEFAULT)
            val keySpec = java.security.spec.X509EncodedKeySpec(encodedKey)
            val kf = java.security.KeyFactory.getInstance("RSA")
            val publicKey = kf.generatePublic(keySpec)
            
            val verifier = java.security.Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update("$key:$value".toByteArray()) // Must match backend format
            
            val sigBytes = hexStringToByteArray(signature) // Assuming signature is Hex
            return verifier.verify(sigBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed", e)
            return false
        }
    }

    // 收款钱包配置（带双重校验）
    fun getRecipientWallet(): String {
        // 1. 获取原始配置对象 (可能是 JSON 字符串)
        val rawConfig = configCache["blockchain.recipient_wallet"]
        
        if (rawConfig == null) {
             return SAFE_RECIPIENT_WALLET
        }
        
        var walletAddress = ""
        
        // 2. 检查是否为签名对象
        if (rawConfig is String && rawConfig.trim().startsWith("{")) {
            try {
                val json = JSONObject(rawConfig)
                if (json.has("value") && json.has("signature")) {
                    val value = json.getString("value")
                    val signature = json.getString("signature")
                    
                    if (verifyConfigSignature("blockchain.recipient_wallet", value, signature)) {
                        walletAddress = value
                        Log.i(TAG, "✅ 收款地址签名验证通过")
                    } else {
                        Log.e(TAG, "❌ 收款地址签名验证失败！使用安全回退地址。")
                        return SAFE_RECIPIENT_WALLET
                    }
                } else {
                    // 普通 JSON 但不是签名对象? 或者是直接的 JSON 值
                    walletAddress = rawConfig // 暂且当做普通字符串处理，如果解析失败再回退
                }
            } catch (e: Exception) {
                // 不是有效的 JSON，可能是普通字符串
                walletAddress = rawConfig
            }
        } else {
            walletAddress = rawConfig.toString()
        }
        
        // 3. 再次确认地址格式（简单的长度检查）
        if (walletAddress.length < 32) {
             return SAFE_RECIPIENT_WALLET
        }

        return walletAddress
    }
    
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
