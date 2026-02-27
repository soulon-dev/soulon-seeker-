package com.soulon.app.chat

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.wallet.WalletScope
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AI 对话防刷限制器
 * 
 * 防止用户恶意刷消息，包括：
 * - 单条消息文本长度限制
 * - 发送频率限制（每分钟、每小时）
 * - 冷却时间控制
 * 
 * 注意：所有配置值从后台实时同步，可在管理后台修改
 */
class ChatRateLimiter(context: Context) {
    
    private val prefs: SharedPreferences = WalletScope.scopedPrefs(context, "chat_rate_limit")
    private val remoteConfig = com.soulon.app.config.RemoteConfigManager.getInstance(context)
    
    // 消息时间戳队列（用于频率检测）
    private val messageTimestamps = ConcurrentLinkedQueue<Long>()
    
    companion object {
        // ========== 默认值（当远程配置不可用时使用）==========
        const val DEFAULT_MAX_MESSAGE_LENGTH = 2000
        const val DEFAULT_WARNING_LENGTH = 1500
        const val DEFAULT_MAX_MESSAGES_PER_MINUTE = 10
        const val DEFAULT_MAX_MESSAGES_PER_HOUR = 60
        const val DEFAULT_MIN_INTERVAL_MS = 1000L
        const val DEFAULT_COOLDOWN_AFTER_LIMIT_MS = 30_000L
        
        // ========== 时间窗口（毫秒）==========
        const val ONE_MINUTE_MS = 60_000L
        const val ONE_HOUR_MS = 3_600_000L
        
        // SharedPreferences 键
        private const val KEY_LAST_MESSAGE_TIME = "last_message_time"
        private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
        private const val KEY_HOUR_MESSAGE_COUNT = "hour_message_count"
        private const val KEY_HOUR_START_TIME = "hour_start_time"
    }
    
    // ========== 从远程配置获取值 ==========
    private val maxMessageLength: Int
        get() = remoteConfig.getRateLimitMaxMessageLength()
    
    private val warningLength: Int
        get() = remoteConfig.getInt("ratelimit.warning_length", DEFAULT_WARNING_LENGTH)
    
    private val maxMessagesPerMinute: Int
        get() = remoteConfig.getRateLimitMaxPerMinute()
    
    private val maxMessagesPerHour: Int
        get() = remoteConfig.getRateLimitMaxPerHour()
    
    private val minIntervalMs: Long
        get() = remoteConfig.getRateLimitMinIntervalMs()
    
    private val cooldownAfterLimitMs: Long
        get() = remoteConfig.getLong("ratelimit.cooldown_ms", DEFAULT_COOLDOWN_AFTER_LIMIT_MS)
    
    /**
     * 检查结果
     */
    sealed class CheckResult {
        object Allowed : CheckResult()
        
        data class TextTooLong(
            val currentLength: Int,
            val maxLength: Int,
            val message: String = "消息过长，请精简后再发送（${currentLength}/${maxLength}字符）"
        ) : CheckResult()
        
        data class RateLimited(
            val waitSeconds: Int,
            val message: String
        ) : CheckResult()
        
        data class TooFast(
            val message: String = "发送太快了，请稍等片刻"
        ) : CheckResult()
        
        data class InCooldown(
            val remainingSeconds: Int,
            val message: String = "请等待 ${remainingSeconds} 秒后再发送"
        ) : CheckResult()
    }
    
    /**
     * 检查是否允许发送消息
     * 
     * @param text 要发送的消息文本
     * @return 检查结果
     */
    fun checkCanSend(text: String): CheckResult {
        val now = System.currentTimeMillis()
        
        // 1. 检查文本长度（使用远程配置值）
        if (text.length > maxMessageLength) {
            return CheckResult.TextTooLong(
                currentLength = text.length,
                maxLength = maxMessageLength
            )
        }
        
        // 2. 检查是否在冷却期
        val cooldownUntil = prefs.getLong(KEY_COOLDOWN_UNTIL, 0)
        if (now < cooldownUntil) {
            val remainingMs = cooldownUntil - now
            val remainingSeconds = (remainingMs / 1000).toInt() + 1
            return CheckResult.InCooldown(remainingSeconds)
        }
        
        // 3. 检查发送间隔（使用远程配置值）
        val lastMessageTime = prefs.getLong(KEY_LAST_MESSAGE_TIME, 0)
        if (now - lastMessageTime < minIntervalMs) {
            return CheckResult.TooFast()
        }
        
        // 4. 清理过期的时间戳
        cleanOldTimestamps(now)
        
        // 5. 检查每分钟频率（使用远程配置值）
        val messagesInLastMinute = countMessagesInWindow(now, ONE_MINUTE_MS)
        if (messagesInLastMinute >= maxMessagesPerMinute) {
            // 触发冷却
            setCooldown(cooldownAfterLimitMs)
            val waitSeconds = (cooldownAfterLimitMs / 1000).toInt()
            return CheckResult.RateLimited(
                waitSeconds = waitSeconds,
                message = "发送消息过于频繁，请等待 ${waitSeconds} 秒后再试"
            )
        }
        
        // 6. 检查每小时频率（使用远程配置值）
        val messagesInLastHour = getHourMessageCount(now)
        if (messagesInLastHour >= maxMessagesPerHour) {
            // 计算到下一小时的等待时间
            val hourStartTime = prefs.getLong(KEY_HOUR_START_TIME, now)
            val hourEndTime = hourStartTime + ONE_HOUR_MS
            val waitMs = hourEndTime - now
            val waitMinutes = (waitMs / 60_000).toInt() + 1
            return CheckResult.RateLimited(
                waitSeconds = (waitMs / 1000).toInt(),
                message = "本小时消息数已达上限（$maxMessagesPerHour 条），请等待 ${waitMinutes} 分钟后再试"
            )
        }
        
        return CheckResult.Allowed
    }
    
    /**
     * 记录消息发送
     */
    fun recordMessageSent() {
        val now = System.currentTimeMillis()
        
        // 记录时间戳
        messageTimestamps.add(now)
        
        // 更新最后发送时间
        prefs.edit()
            .putLong(KEY_LAST_MESSAGE_TIME, now)
            .apply()
        
        // 更新小时计数
        updateHourCount(now)
        
        Timber.d("📨 消息发送记录: 分钟内=${countMessagesInWindow(now, ONE_MINUTE_MS)}, 小时内=${getHourMessageCount(now)}")
    }
    
    /**
     * 获取剩余可发送消息数
     */
    fun getRemainingMessages(): RemainingMessages {
        val now = System.currentTimeMillis()
        cleanOldTimestamps(now)
        
        val inMinute = countMessagesInWindow(now, ONE_MINUTE_MS)
        val inHour = getHourMessageCount(now)
        
        return RemainingMessages(
            perMinute = maxOf(0, maxMessagesPerMinute - inMinute),
            perHour = maxOf(0, maxMessagesPerHour - inHour)
        )
    }
    
    /**
     * 检查文本长度是否接近限制
     */
    fun isNearLengthLimit(text: String): Boolean {
        return text.length >= warningLength
    }
    
    /**
     * 获取文本长度状态
     */
    fun getTextLengthStatus(text: String): TextLengthStatus {
        return when {
            text.length > maxMessageLength -> TextLengthStatus.EXCEEDED
            text.length >= warningLength -> TextLengthStatus.WARNING
            else -> TextLengthStatus.OK
        }
    }
    
    /**
     * 获取当前配置值（用于 UI 显示）
     */
    fun getCurrentLimits(): ConfigLimits {
        return ConfigLimits(
            maxMessageLength = maxMessageLength,
            maxPerMinute = maxMessagesPerMinute,
            maxPerHour = maxMessagesPerHour
        )
    }
    
    data class ConfigLimits(
        val maxMessageLength: Int,
        val maxPerMinute: Int,
        val maxPerHour: Int
    )
    
    // ========== 私有方法 ==========
    
    private fun cleanOldTimestamps(now: Long) {
        val cutoff = now - ONE_HOUR_MS
        while (messageTimestamps.isNotEmpty() && (messageTimestamps.peek() ?: Long.MAX_VALUE) < cutoff) {
            messageTimestamps.poll()
        }
    }
    
    private fun countMessagesInWindow(now: Long, windowMs: Long): Int {
        val cutoff = now - windowMs
        return messageTimestamps.count { it >= cutoff }
    }
    
    private fun getHourMessageCount(now: Long): Int {
        val hourStartTime = prefs.getLong(KEY_HOUR_START_TIME, 0)
        
        // 如果超过一小时，重置计数
        if (now - hourStartTime >= ONE_HOUR_MS) {
            prefs.edit()
                .putLong(KEY_HOUR_START_TIME, now)
                .putInt(KEY_HOUR_MESSAGE_COUNT, 0)
                .apply()
            return 0
        }
        
        return prefs.getInt(KEY_HOUR_MESSAGE_COUNT, 0)
    }
    
    private fun updateHourCount(now: Long) {
        val hourStartTime = prefs.getLong(KEY_HOUR_START_TIME, 0)
        
        // 如果超过一小时，重置
        if (now - hourStartTime >= ONE_HOUR_MS || hourStartTime == 0L) {
            prefs.edit()
                .putLong(KEY_HOUR_START_TIME, now)
                .putInt(KEY_HOUR_MESSAGE_COUNT, 1)
                .apply()
        } else {
            // 增加计数
            val currentCount = prefs.getInt(KEY_HOUR_MESSAGE_COUNT, 0)
            prefs.edit()
                .putInt(KEY_HOUR_MESSAGE_COUNT, currentCount + 1)
                .apply()
        }
    }
    
    private fun setCooldown(durationMs: Long) {
        val cooldownUntil = System.currentTimeMillis() + durationMs
        prefs.edit()
            .putLong(KEY_COOLDOWN_UNTIL, cooldownUntil)
            .apply()
        Timber.w("⏳ 触发冷却期: ${durationMs / 1000} 秒")
    }
    
    /**
     * 清除所有限制记录（用于测试）
     */
    fun clearAll() {
        messageTimestamps.clear()
        prefs.edit().clear().apply()
    }
    
    /**
     * 剩余消息数
     */
    data class RemainingMessages(
        val perMinute: Int,
        val perHour: Int
    )
    
    /**
     * 文本长度状态
     */
    enum class TextLengthStatus {
        OK,         // 正常
        WARNING,    // 接近限制
        EXCEEDED    // 超出限制
    }
}
