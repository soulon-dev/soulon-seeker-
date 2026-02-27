package com.soulon.app.quota

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.i18n.AppStrings
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

/**
 * Token 使用限额管理器
 * 
 * 根据用户级别管理每月 Token 使用限额：
 * - 普通用户：100万 tokens/月
 * - 订阅用户：500万 tokens/月
 * - 质押用户：2000万 tokens/月
 * - 创始人用户：无限制
 * - 技术专家用户：无限制
 * 
 * 注意：配额值从后台实时同步，可在管理后台修改
 */
class TokenQuotaManager(
    private val context: Context,
    private val dateTimeProvider: DateTimeProvider
) {
    
    private val prefs: SharedPreferences
        get() = WalletScope.scopedPrefs(context, "token_quota")
    private val remoteConfig = com.soulon.app.config.RemoteConfigManager.getInstance(context)
    
    companion object {
        // 默认限额配置（当远程配置不可用时使用）
        const val DEFAULT_QUOTA_FREE = 1_000_000L
        const val DEFAULT_QUOTA_SUBSCRIBER = 5_000_000L
        const val DEFAULT_QUOTA_STAKER = 20_000_000L
        const val QUOTA_FOUNDER = Long.MAX_VALUE    // 创始人：无限制
        const val QUOTA_EXPERT = Long.MAX_VALUE     // 技术专家：无限制
        
        // SharedPreferences 键
        private const val KEY_USED_THIS_MONTH = "used_this_month"
        private const val KEY_LAST_RESET_MONTH = "last_reset_month"  // YYYY-MM 格式
        private const val KEY_TOTAL_LIFETIME = "total_lifetime"
        
        // 警告阈值（剩余 20% 时警告）
        private const val WARNING_THRESHOLD = 0.8f
    }
    
    // 从远程配置获取配额值
    private val quotaFree: Long get() = remoteConfig.getQuotaFree()
    private val quotaSubscriber: Long get() = remoteConfig.getQuotaSubscriber()
    private val quotaStaker: Long get() = remoteConfig.getQuotaStaker()
    
    /**
     * 用户级别类型
     * 注意：monthlyLimit 现在是动态值，从远程配置获取
     */
    enum class UserLevelType(
        private val displayNameZh: String,
        private val displayNameEn: String
    ) {
        FREE("普通用户", "Free"),
        SUBSCRIBER("订阅用户", "Subscriber"),
        STAKER("质押用户", "Staker"),
        FOUNDER("创始人用户", "Founder"),
        EXPERT("技术专家用户", "Expert");

        val displayName: String
            get() = AppStrings.tr(displayNameZh, displayNameEn)
    }
    
    /**
     * 获取用户级别的月度限额
     */
    fun getMonthlyLimit(level: UserLevelType): Long {
        return when (level) {
            UserLevelType.FREE -> quotaFree
            UserLevelType.SUBSCRIBER -> quotaSubscriber
            UserLevelType.STAKER -> quotaStaker
            UserLevelType.FOUNDER -> QUOTA_FOUNDER
            UserLevelType.EXPERT -> QUOTA_EXPERT
        }
    }
    
    /**
     * Token 配额状态
     */
    data class TokenQuota(
        val monthlyLimit: Long,          // 每月限额
        val usedThisMonth: Long,         // 本月已用
        val remaining: Long,             // 本月剩余
        val resetMonth: String,          // 重置月份 (YYYY-MM)
        val userLevel: UserLevelType,    // 用户级别
        val totalLifetime: Long,         // 累计使用
        val isDateTrusted: Boolean,      // 日期是否可信
        val daysUntilReset: Int          // 距离重置天数
    ) {
        val usagePercent: Float get() = if (monthlyLimit == Long.MAX_VALUE) 0f else (usedThisMonth.toFloat() / monthlyLimit)
        val isNearLimit: Boolean get() = usagePercent >= WARNING_THRESHOLD
        val isExceeded: Boolean get() = monthlyLimit != Long.MAX_VALUE && usedThisMonth >= monthlyLimit
    }
    
    /**
     * 配额检查结果
     */
    sealed class QuotaCheckResult {
        data class Allowed(
            val remainingTokens: Long,
            val willExceed: Boolean = false  // 本次使用后是否会超出
        ) : QuotaCheckResult()
        
        data class NearLimit(
            val remainingTokens: Long,
            val usagePercent: Float,
            val message: String
        ) : QuotaCheckResult()
        
        data class Exceeded(
            val monthlyLimit: Long,
            val usedThisMonth: Long,
            val resetTime: String,
            val message: String
        ) : QuotaCheckResult()
        
        data class UntrustedDate(
            val localDate: String,
            val message: String
        ) : QuotaCheckResult()
    }
    
    /**
     * 获取当前配额状态
     */
    suspend fun getQuotaStatus(userLevel: UserLevelType): TokenQuota = withContext(Dispatchers.IO) {
        // 获取可信日期
        val dateResult = dateTimeProvider.getCurrentDate()
        val currentDate = dateResult.getDate()
        val isTrusted = dateResult.isTrusted()
        
        // 提取当前月份 (YYYY-MM)
        val currentMonth = currentDate.substring(0, 7)  // "2024-01-15" -> "2024-01"
        
        // 检查是否需要重置
        val lastResetMonth = prefs.getString(KEY_LAST_RESET_MONTH, "")
        val usedThisMonth = if (lastResetMonth == currentMonth) {
            prefs.getLong(KEY_USED_THIS_MONTH, 0)
        } else {
            // 新的月份，重置使用量
            resetMonthlyUsage(currentMonth)
            0L
        }
        
        // 使用动态获取的月度限额
        val monthlyLimit = getMonthlyLimit(userLevel)
        val remaining = if (monthlyLimit == Long.MAX_VALUE) Long.MAX_VALUE else maxOf(0, monthlyLimit - usedThisMonth)
        val totalLifetime = prefs.getLong(KEY_TOTAL_LIFETIME, 0)
        
        // 计算距离下月重置的天数
        val daysUntilReset = calculateDaysUntilNextMonth(currentDate)
        
        TokenQuota(
            monthlyLimit = monthlyLimit,
            usedThisMonth = usedThisMonth,
            remaining = remaining,
            resetMonth = currentMonth,
            userLevel = userLevel,
            totalLifetime = totalLifetime,
            isDateTrusted = isTrusted,
            daysUntilReset = daysUntilReset
        )
    }
    
    /**
     * 检查是否允许使用指定数量的 tokens
     */
    suspend fun checkQuota(
        estimatedTokens: Long,
        userLevel: UserLevelType
    ): QuotaCheckResult {
        val quota = getQuotaStatus(userLevel)
        
        // 无限制用户直接允许
        if (quota.monthlyLimit == Long.MAX_VALUE) {
            return QuotaCheckResult.Allowed(Long.MAX_VALUE)
        }
        
        // 检查日期是否可信
        if (!quota.isDateTrusted) {
            Timber.w("⚠️ 日期不可信，但仍允许使用")
            // 不可信日期仍然允许使用，但记录警告
        }
        
        // 已超出限额
        if (quota.isExceeded) {
            return QuotaCheckResult.Exceeded(
                monthlyLimit = quota.monthlyLimit,
                usedThisMonth = quota.usedThisMonth,
                resetTime = "${quota.daysUntilReset} 天后（下月 1 日）",
                message = "本月 Token 限额已用完（${formatTokenCount(quota.usedThisMonth)}/${formatTokenCount(quota.monthlyLimit)}）"
            )
        }
        
        // 计算使用后的状态
        val afterUsage = quota.usedThisMonth + estimatedTokens
        val willExceed = afterUsage > quota.monthlyLimit
        
        // 接近限额警告
        if (quota.isNearLimit || willExceed) {
            val remaining = maxOf(0, quota.monthlyLimit - quota.usedThisMonth)
            return QuotaCheckResult.NearLimit(
                remainingTokens = remaining,
                usagePercent = quota.usagePercent,
                message = if (willExceed) {
                    "本次对话可能超出本月限额，剩余 ${formatTokenCount(remaining)} tokens"
                } else {
                    "本月 Token 使用量已达 ${(quota.usagePercent * 100).toInt()}%，距离重置还有 ${quota.daysUntilReset} 天"
                }
            )
        }
        
        // 允许使用
        return QuotaCheckResult.Allowed(
            remainingTokens = quota.remaining,
            willExceed = false
        )
    }
    
    /**
     * 记录 Token 使用量
     */
    suspend fun recordUsage(tokensUsed: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val dateResult = dateTimeProvider.getCurrentDate()
            val currentDate = dateResult.getDate()
            val currentMonth = currentDate.substring(0, 7)
            
            // 检查是否需要重置
            val lastResetMonth = prefs.getString(KEY_LAST_RESET_MONTH, "")
            val currentUsed = if (lastResetMonth == currentMonth) {
                prefs.getLong(KEY_USED_THIS_MONTH, 0)
            } else {
                resetMonthlyUsage(currentMonth)
                0L
            }
            
            // 更新使用量
            val newUsed = currentUsed + tokensUsed
            val totalLifetime = prefs.getLong(KEY_TOTAL_LIFETIME, 0) + tokensUsed
            
            prefs.edit()
                .putLong(KEY_USED_THIS_MONTH, newUsed)
                .putLong(KEY_TOTAL_LIFETIME, totalLifetime)
                .apply()
            
            Timber.d("📊 Token 使用记录: +$tokensUsed, 本月总计: $newUsed, 累计: $totalLifetime")
            
            true
        } catch (e: Exception) {
            Timber.e(e, "记录 Token 使用失败")
            false
        }
    }
    
    /**
     * 重置每月使用量
     */
    private fun resetMonthlyUsage(newMonth: String) {
        prefs.edit()
            .putLong(KEY_USED_THIS_MONTH, 0)
            .putString(KEY_LAST_RESET_MONTH, newMonth)
            .apply()
        
        Timber.i("🔄 每月 Token 使用量已重置 (月份: $newMonth)")
    }
    
    /**
     * 计算距离下月的天数
     */
    private fun calculateDaysUntilNextMonth(currentDate: String): Int {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = sdf.parse(currentDate) ?: return 30
            val calendar = Calendar.getInstance()
            calendar.time = date
            
            val lastDayOfMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            val currentDay = calendar.get(Calendar.DAY_OF_MONTH)
            
            lastDayOfMonth - currentDay + 1  // +1 因为要包括到下月 1 日
        } catch (e: Exception) {
            30  // 默认返回 30 天
        }
    }
    
    /**
     * 格式化 Token 数量显示
     */
    private fun formatTokenCount(count: Long): String {
        return when {
            count >= 1_000_000_000 -> "${count / 1_000_000_000}B"
            count >= 1_000_000 -> "${count / 1_000_000}M"
            count >= 1_000 -> "${count / 1_000}K"
            else -> count.toString()
        }
    }
    
    /**
     * 获取本月使用量
     */
    fun getThisMonthUsage(): Long {
        return prefs.getLong(KEY_USED_THIS_MONTH, 0)
    }
    
    /**
     * 获取累计使用量
     */
    fun getTotalLifetimeUsage(): Long {
        return prefs.getLong(KEY_TOTAL_LIFETIME, 0)
    }
    
    /**
     * 清除所有记录（用于测试）
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
    
    // ========== 兼容性方法 ==========
    
    /**
     * 兼容旧代码 - 获取今日使用量（现改为本月）
     */
    @Deprecated("使用 getThisMonthUsage() 代替", ReplaceWith("getThisMonthUsage()"))
    fun getTodayUsage(): Long = getThisMonthUsage()
}
