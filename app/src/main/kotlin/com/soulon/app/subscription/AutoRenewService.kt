package com.soulon.app.subscription

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
 * 自动续费服务
 * 管理用户的自动续费订阅状态
 */
class AutoRenewService(private val context: Context) {
    
    companion object {
        private const val TAG = "AutoRenewService"
        private val BASE_URL = BuildConfig.BACKEND_BASE_URL
        
        // 订阅方案类型
        const val PLAN_MONTHLY = 1
        const val PLAN_QUARTERLY = 2
        const val PLAN_YEARLY = 3
        
        // 订阅周期（秒）
        const val PERIOD_MONTHLY = 30 * 24 * 60 * 60L  // 30 天
        const val PERIOD_QUARTERLY = 90 * 24 * 60 * 60L  // 90 天
        const val PERIOD_YEARLY = 365 * 24 * 60 * 60L  // 365 天
        
        @Volatile
        private var instance: AutoRenewService? = null
        
        fun getInstance(context: Context): AutoRenewService {
            return instance ?: synchronized(this) {
                instance ?: AutoRenewService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs = context.getSharedPreferences("auto_renew_prefs", Context.MODE_PRIVATE)

    private fun keyPendingPlanType(walletAddress: String) = "pending_plan_type_$walletAddress"
    private fun keyPendingAmountUsdc(walletAddress: String) = "pending_amount_usdc_$walletAddress"
    private fun keyPendingEffectiveAt(walletAddress: String) = "pending_effective_at_$walletAddress"
    private fun keyCancelLockedUntil(walletAddress: String) = "cancel_locked_until_$walletAddress"
    
    /**
     * 创建自动续费订阅
     */
    suspend fun createAutoRenewSubscription(
        walletAddress: String,
        planType: Int,
        amountUsdc: Double,
        tokenAccountPda: String = ""
    ): Result<AutoRenewResult> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "创建自动续费: wallet=$walletAddress, plan=$planType, amount=$amountUsdc")
            
            val periodSeconds = when (planType) {
                PLAN_MONTHLY -> PERIOD_MONTHLY
                PLAN_QUARTERLY -> PERIOD_QUARTERLY
                PLAN_YEARLY -> PERIOD_YEARLY
                else -> PERIOD_MONTHLY
            }
            
            val url = URL("$BASE_URL/api/v1/subscription/auto-renew")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val requestBody = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("planType", planType)
                put("amountUsdc", amountUsdc)
                put("periodSeconds", periodSeconds)
                put("tokenAccountPda", tokenAccountPda)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(requestBody.toString()) }
            
            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }
            
            Log.d(TAG, "Response: $responseCode - $responseBody")
            
            if (responseCode in 200..299) {
                val json = JSONObject(responseBody)
                val subscriptionId = json.optString("id", "")
                val nextPaymentAt = json.optLong("nextPaymentAt", 0)
                
                // 保存本地状态
                saveLocalState(walletAddress, planType, amountUsdc, nextPaymentAt)
                clearPendingState(walletAddress)
                
                Result.success(AutoRenewResult(
                    success = true,
                    subscriptionId = subscriptionId,
                    nextPaymentAt = nextPaymentAt
                ))
            } else {
                Result.failure(Exception("创建失败: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建自动续费失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 取消自动续费
     */
    suspend fun cancelAutoRenewSubscription(walletAddress: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "取消自动续费: wallet=$walletAddress")
            
            val url = URL("$BASE_URL/api/v1/subscription/auto-renew/$walletAddress/cancel")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            
            if (responseCode in 200..299) {
                // 清除本地状态
                clearLocalState(walletAddress)
                clearPendingState(walletAddress)
                Result.success(true)
            } else {
                val err = connection.errorStream?.bufferedReader()?.readText()
                Result.failure(Exception(err ?: "取消失败: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "取消自动续费失败", e)
            Result.failure(e)
        }
    }

    suspend fun schedulePlanChange(
        walletAddress: String,
        targetPlanType: Int,
        targetAmountUsdc: Double,
        effectiveAt: Long? = null
    ): Result<AutoRenewScheduleResult> = withContext(Dispatchers.IO) {
        try {
            val periodSeconds = when (targetPlanType) {
                PLAN_MONTHLY -> PERIOD_MONTHLY
                PLAN_QUARTERLY -> PERIOD_QUARTERLY
                PLAN_YEARLY -> PERIOD_YEARLY
                else -> PERIOD_MONTHLY
            }

            val url = URL("$BASE_URL/api/v1/subscription/auto-renew")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val requestBody = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("tokenAccountPda", "")
                put("action", "schedule_change")
                put("targetPlanType", targetPlanType)
                put("targetAmountUsdc", targetAmountUsdc)
                put("targetPeriodSeconds", periodSeconds)
                effectiveAt?.let { put("effectiveAt", it) }
            }

            connection.outputStream.bufferedWriter().use { it.write(requestBody.toString()) }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }

            if (responseCode in 200..299) {
                val json = JSONObject(responseBody)
                val effectiveAtSec = json.optLong("effectiveAt", 0)
                val cancelLockedUntil = json.optLong("cancelLockedUntil", 0)
                savePendingState(walletAddress, targetPlanType, targetAmountUsdc, effectiveAtSec)
                saveCancelLockedUntil(walletAddress, cancelLockedUntil)
                Result.success(
                    AutoRenewScheduleResult(
                        success = true,
                        effectiveAt = effectiveAtSec,
                        cancelLockedUntil = cancelLockedUntil
                    )
                )
            } else {
                Result.failure(Exception("升级失败: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "计划变更失败", e)
            Result.failure(e)
        }
    }

    suspend fun syncStatus(walletAddress: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/subscription/auto-renew/$walletAddress")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }

            if (responseCode in 200..299) {
                val json = JSONObject(responseBody)
                val active = json.optBoolean("active", false)
                val cancelLockedUntil = json.optLong("cancelLockedUntil", 0)
                saveCancelLockedUntil(walletAddress, cancelLockedUntil)

                if (active) {
                    val sub = json.optJSONObject("subscription")
                    val planType = sub?.optInt("planType", 0) ?: 0
                    val amountUsdc = sub?.optDouble("amountUsdc", 0.0) ?: 0.0
                    val nextPaymentAt = sub?.optLong("nextPaymentAt", 0) ?: 0
                    saveLocalState(walletAddress, planType, amountUsdc, nextPaymentAt)
                } else {
                    clearLocalState(walletAddress)
                }

                val pending = json.optJSONObject("pendingChange")
                if (pending != null) {
                    val toPlanType = pending.optInt("toPlanType", 0)
                    val toAmountUsdc = pending.optDouble("toAmountUsdc", 0.0)
                    val effectiveAt = pending.optLong("effectiveAt", 0)
                    if (toPlanType != 0 && effectiveAt != 0L) {
                        savePendingState(walletAddress, toPlanType, toAmountUsdc, effectiveAt)
                    } else {
                        clearPendingState(walletAddress)
                    }
                } else {
                    clearPendingState(walletAddress)
                }

                Result.success(true)
            } else {
                Result.failure(Exception("同步失败: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "同步状态失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 检查是否已开通自动续费
     */
    fun isAutoRenewEnabled(walletAddress: String): Boolean {
        return prefs.getBoolean("auto_renew_enabled_$walletAddress", false)
    }
    
    /**
     * 获取下次扣款时间
     */
    fun getNextPaymentAt(walletAddress: String): Long {
        return prefs.getLong("next_payment_at_$walletAddress", 0)
    }
    
    /**
     * 获取当前订阅方案
     */
    fun getCurrentPlanType(walletAddress: String): Int {
        return prefs.getInt("plan_type_$walletAddress", 0)
    }
    
    /**
     * 获取订阅金额
     */
    fun getSubscriptionAmount(walletAddress: String): Double {
        return prefs.getFloat("amount_usdc_$walletAddress", 0f).toDouble()
    }

    fun getPendingPlanType(walletAddress: String): Int {
        return prefs.getInt(keyPendingPlanType(walletAddress), 0)
    }

    fun getPendingAmountUsdc(walletAddress: String): Double {
        return prefs.getFloat(keyPendingAmountUsdc(walletAddress), 0f).toDouble()
    }

    fun getPendingEffectiveAt(walletAddress: String): Long {
        return prefs.getLong(keyPendingEffectiveAt(walletAddress), 0L)
    }

    fun getCancelLockedUntil(walletAddress: String): Long {
        return prefs.getLong(keyCancelLockedUntil(walletAddress), 0L)
    }
    
    /**
     * 保存本地状态
     */
    private fun saveLocalState(
        walletAddress: String,
        planType: Int,
        amountUsdc: Double,
        nextPaymentAt: Long
    ) {
        prefs.edit()
            .putBoolean("auto_renew_enabled_$walletAddress", true)
            .putInt("plan_type_$walletAddress", planType)
            .putFloat("amount_usdc_$walletAddress", amountUsdc.toFloat())
            .putLong("next_payment_at_$walletAddress", nextPaymentAt)
            .apply()
    }

    private fun savePendingState(
        walletAddress: String,
        planType: Int,
        amountUsdc: Double,
        effectiveAt: Long
    ) {
        prefs.edit()
            .putInt(keyPendingPlanType(walletAddress), planType)
            .putFloat(keyPendingAmountUsdc(walletAddress), amountUsdc.toFloat())
            .putLong(keyPendingEffectiveAt(walletAddress), effectiveAt)
            .apply()
    }

    private fun saveCancelLockedUntil(walletAddress: String, lockedUntil: Long) {
        prefs.edit()
            .putLong(keyCancelLockedUntil(walletAddress), lockedUntil)
            .apply()
    }

    private fun clearPendingState(walletAddress: String) {
        prefs.edit()
            .remove(keyPendingPlanType(walletAddress))
            .remove(keyPendingAmountUsdc(walletAddress))
            .remove(keyPendingEffectiveAt(walletAddress))
            .remove(keyCancelLockedUntil(walletAddress))
            .apply()
    }
    
    /**
     * 清除本地状态
     */
    private fun clearLocalState(walletAddress: String) {
        prefs.edit()
            .putBoolean("auto_renew_enabled_$walletAddress", false)
            .remove("plan_type_$walletAddress")
            .remove("amount_usdc_$walletAddress")
            .remove("next_payment_at_$walletAddress")
            .apply()
    }
    
    /**
     * 获取方案名称
     */
    fun getPlanName(planType: Int): String {
        return when (planType) {
            PLAN_MONTHLY -> "月费会员"
            PLAN_QUARTERLY -> "季度会员"
            PLAN_YEARLY -> "年费会员"
            else -> "未知"
        }
    }
    
    /**
     * 格式化下次扣款时间
     */
    fun formatNextPaymentDate(timestamp: Long): String {
        if (timestamp == 0L) return "未设置"
        val date = java.util.Date(timestamp * 1000)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return format.format(date)
    }
}

/**
 * 自动续费结果
 */
data class AutoRenewResult(
    val success: Boolean,
    val subscriptionId: String = "",
    val nextPaymentAt: Long = 0,
    val errorMessage: String = ""
)

data class AutoRenewScheduleResult(
    val success: Boolean,
    val effectiveAt: Long = 0,
    val cancelLockedUntil: Long = 0,
    val errorMessage: String = ""
)
