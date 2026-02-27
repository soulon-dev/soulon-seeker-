package com.soulon.app.api

import android.content.Context
import android.util.Log
import com.soulon.app.BuildConfig
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与后台管理系统同步数据的 API 客户端
 */
class AdminApiClient(private val context: Context) {
    
    companion object {
        private const val TAG = "AdminApiClient"
        // 开发环境使用本地地址，生产环境使用 Cloudflare Workers
        private const val BASE_URL_DEV = "http://10.0.2.2:8787"  // Android 模拟器访问本地
        private val BASE_URL_PROD = BuildConfig.BACKEND_BASE_URL
        
        private fun getBaseUrl(): String {
            // 生产环境使用 Cloudflare Workers
            return BASE_URL_PROD
        }
    }
    
    /**
     * 从后端获取用户配置（用于同步后台管理员的修改）
     * 
     * @return 用户配置数据，如果用户不存在则返回 null
     */
    suspend fun fetchUserProfile(walletAddress: String): Result<UserProfileFromBackend?> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getBaseUrl()}/api/v1/users/profile?wallet=$walletAddress")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.readTimeout = 10000
            connection.readTimeout = 10000
            connection.readTimeout = 10000
            connection.readTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                if (!json.optBoolean("exists", false)) {
                    Log.d(TAG, "用户在后端不存在: $walletAddress")
                    Result.success(null)
                } else {
                    val profile = json.getJSONObject("profile")
                    val quotaOverride = if (json.has("quotaOverride") && !json.isNull("quotaOverride")) {
                        val qo = json.getJSONObject("quotaOverride")
                        QuotaOverrideFromBackend(
                            monthlyTokenLimit = qo.optInt("monthlyTokenLimit", -1).takeIf { it >= 0 },
                            dailyConversationLimit = qo.optInt("dailyConversationLimit", -1).takeIf { it >= 0 },
                            customMultiplier = qo.optDouble("customMultiplier", -1.0).takeIf { it >= 0 },
                            expiresAt = qo.optLong("expiresAt", -1).takeIf { it > 0 },
                            reason = qo.optString("reason", null)
                        )
                    } else null
                    
                    Result.success(UserProfileFromBackend(
                        id = profile.getString("id"),
                        walletAddress = profile.getString("walletAddress"),
                        memoBalance = profile.optDouble("memoBalance", 0.0),
                        currentTier = profile.optInt("currentTier", 1),
                        subscriptionType = profile.optString("subscriptionType", "FREE"),
                        subscriptionExpiry = profile.optLong("subscriptionExpiry", -1).takeIf { it > 0 },
                        stakedAmount = profile.optDouble("stakedAmount", 0.0),
                        isFounder = profile.optBoolean("isFounder", false),
                        isExpert = profile.optBoolean("isExpert", false),
                        isBanned = profile.optBoolean("isBanned", false),
                        totalTokensUsed = profile.optInt("totalTokensUsed", 0),
                        memoriesCount = profile.optInt("memoriesCount", 0),
                        quotaOverride = quotaOverride
                    ))
                }
            } else {
                Log.e(TAG, "获取用户配置失败: $responseCode")
                Result.failure(Exception(AppStrings.trf("获取失败: %d", "Fetch failed: %d", responseCode)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取用户配置异常", e)
            Result.failure(e)
        }
    }
    
    /**
     * 后端返回的用户配置数据
     */
    data class UserProfileFromBackend(
        val id: String,
        val walletAddress: String,
        val memoBalance: Double,
        val currentTier: Int,
        val subscriptionType: String,
        val subscriptionExpiry: Long?,
        val stakedAmount: Double,
        val isFounder: Boolean,
        val isExpert: Boolean,
        val isBanned: Boolean,
        val totalTokensUsed: Int,
        val memoriesCount: Int,
        val quotaOverride: QuotaOverrideFromBackend?
    )
    
    data class QuotaOverrideFromBackend(
        val monthlyTokenLimit: Int?,
        val dailyConversationLimit: Int?,
        val customMultiplier: Double?,
        val expiresAt: Long?,
        val reason: String?
    )
    
    /**
     * 同步用户数据到后端
     */
    suspend fun syncUserData(
        walletAddress: String,
        memoBalance: Double,
        currentTier: Int,
        subscriptionType: String,
        subscriptionExpiry: Long?,
        stakedAmount: Double,
        totalTokensUsed: Int,
        memoriesCount: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getBaseUrl()}/api/v1/users/sync")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("memoBalance", memoBalance)
                put("currentTier", currentTier)
                put("subscriptionType", subscriptionType)
                put("subscriptionExpiry", subscriptionExpiry)
                put("stakedAmount", stakedAmount)
                put("totalTokensUsed", totalTokensUsed)
                put("memoriesCount", memoriesCount)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "用户数据同步成功: $walletAddress")
                Result.success(true)
            } else {
                Log.e(TAG, "用户数据同步失败: $responseCode")
                Result.failure(Exception(AppStrings.trf("同步失败: %d", "Sync failed: %d", responseCode)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "用户数据同步异常", e)
            Result.failure(e)
        }
    }
    
    /**
     * 同步订阅状态到后端
     */
    suspend fun syncSubscription(
        walletAddress: String,
        planId: String,
        startDate: Long,
        endDate: Long,
        amount: Double,
        transactionId: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getBaseUrl()}/api/v1/subscriptions/sync")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("planId", planId)
                put("startDate", startDate)
                put("endDate", endDate)
                put("amount", amount)
                put("transactionId", transactionId)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            val responseCode = connection.responseCode
            Result.success(responseCode == HttpURLConnection.HTTP_OK)
        } catch (e: Exception) {
            Log.e(TAG, "订阅同步异常", e)
            Result.failure(e)
        }
    }
    
    /**
     * 同步质押记录到后端
     */
    suspend fun syncStaking(
        walletAddress: String,
        projectId: String,
        amount: Double,
        startTime: Long,
        unlockTime: Long,
        status: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getBaseUrl()}/api/v1/staking/sync")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("projectId", projectId)
                put("amount", amount)
                put("startTime", startTime)
                put("unlockTime", unlockTime)
                put("status", status)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            val responseCode = connection.responseCode
            Result.success(responseCode == HttpURLConnection.HTTP_OK)
        } catch (e: Exception) {
            Log.e(TAG, "质押同步异常", e)
            Result.failure(e)
        }
    }
    
    /**
     * 记录聊天日志到后端
     */
    suspend fun logChat(
        walletAddress: String,
        userMessagePreview: String,
        tokensUsed: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getBaseUrl()}/api/v1/chats/log")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("userMessagePreview", userMessagePreview.take(100)) // 只发送前100字符
                put("tokensUsed", tokensUsed)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            val responseCode = connection.responseCode
            Result.success(responseCode == HttpURLConnection.HTTP_OK)
        } catch (e: Exception) {
            Log.e(TAG, "聊天日志同步异常", e)
            Result.failure(e)
        }
    }
    
    /**
     * 记录记忆上传到后端
     */
    suspend fun logMemory(
        walletAddress: String,
        irysId: String,
        type: String,
        size: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getBaseUrl()}/api/v1/memories/log")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("irysId", irysId)
                put("type", type)
                put("size", size)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            val responseCode = connection.responseCode
            Result.success(responseCode == HttpURLConnection.HTTP_OK)
        } catch (e: Exception) {
            Log.e(TAG, "记忆日志同步异常", e)
            Result.failure(e)
        }
    }
}
