package com.soulon.app.push

import android.content.Context
import android.util.Log
import com.soulon.app.BuildConfig
import com.soulon.app.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 推送通知服务
 * 管理 FCM Token 注册和通知处理
 */
class PushNotificationService(private val context: Context) {
    
    companion object {
        private const val TAG = "PushNotificationService"
        private val BASE_URL = BuildConfig.BACKEND_BASE_URL
        private const val PREFS_NAME = "push_prefs"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_TOKEN_REGISTERED = "token_registered"
        
        @Volatile
        private var instance: PushNotificationService? = null
        
        fun getInstance(context: Context): PushNotificationService {
            return instance ?: synchronized(this) {
                instance ?: PushNotificationService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 保存 FCM Token（由 FirebaseMessagingService 调用）
     */
    fun saveFcmToken(token: String) {
        Log.i(TAG, "保存 FCM Token: ${token.take(20)}...")
        prefs.edit()
            .putString(KEY_FCM_TOKEN, token)
            .putBoolean(KEY_TOKEN_REGISTERED, false)
            .apply()
    }
    
    /**
     * 获取保存的 FCM Token
     */
    fun getFcmToken(): String? {
        return prefs.getString(KEY_FCM_TOKEN, null)
    }
    
    /**
     * 注册 FCM Token 到后端
     */
    suspend fun registerTokenToBackend(walletAddress: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val token = getFcmToken()
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "没有可用的 FCM Token")
            return@withContext Result.failure(Exception("No FCM token available"))
        }
        
        // 检查是否已注册
        if (isTokenRegistered(walletAddress)) {
            Log.i(TAG, "Token 已注册")
            return@withContext Result.success(true)
        }
        
        try {
            Log.i(TAG, "注册 FCM Token: wallet=$walletAddress")
            
            val url = URL("$BASE_URL/api/v1/push/register")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val requestBody = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("fcmToken", token)
                put("deviceId", getDeviceId())
                put("platform", "android")
            }
            
            connection.outputStream.bufferedWriter().use { it.write(requestBody.toString()) }
            
            val responseCode = connection.responseCode
            
            if (responseCode in 200..299) {
                // 标记为已注册
                markTokenRegistered(walletAddress)
                Log.i(TAG, "FCM Token 注册成功")
                Result.success(true)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "注册失败: $responseCode - $errorBody")
                Result.failure(Exception("Registration failed: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册 FCM Token 失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 检查 Token 是否已注册
     */
    private fun isTokenRegistered(walletAddress: String): Boolean {
        return prefs.getBoolean("${KEY_TOKEN_REGISTERED}_$walletAddress", false)
    }
    
    /**
     * 标记 Token 已注册
     */
    private fun markTokenRegistered(walletAddress: String) {
        prefs.edit()
            .putBoolean("${KEY_TOKEN_REGISTERED}_$walletAddress", true)
            .apply()
    }
    
    /**
     * 获取设备 ID
     */
    private fun getDeviceId(): String {
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }
    
    /**
     * 处理收到的推送通知
     */
    fun handleNotification(data: Map<String, String>) {
        val type = data["type"]
        Log.i(TAG, "收到推送通知: type=$type")
        
        when (type) {
            "subscription_reminder" -> handleSubscriptionReminder(data)
            "payment_success" -> handlePaymentSuccess(data)
            "payment_failed" -> handlePaymentFailed(data)
            else -> Log.w(TAG, "未知通知类型: $type")
        }
    }
    
    /**
     * 处理订阅续费提醒
     */
    private fun handleSubscriptionReminder(data: Map<String, String>) {
        val subscriptionId = data["subscriptionId"]
        val amountUsdc = data["amountUsdc"]
        val nextPaymentAt = data["nextPaymentAt"]?.toLongOrNull()
        
        Log.i(TAG, "订阅续费提醒: id=$subscriptionId, amount=$amountUsdc USDC")
        
        // 可以在这里更新本地 UI 状态或显示应用内提醒
    }
    
    /**
     * 处理支付成功
     */
    private fun handlePaymentSuccess(data: Map<String, String>) {
        val transactionId = data["transactionId"]
        Log.i(TAG, "支付成功通知: tx=$transactionId")
    }
    
    /**
     * 处理支付失败
     */
    private fun handlePaymentFailed(data: Map<String, String>) {
        val errorMessage = data["errorMessage"]
        Log.w(TAG, "支付失败通知: $errorMessage")
    }
}
