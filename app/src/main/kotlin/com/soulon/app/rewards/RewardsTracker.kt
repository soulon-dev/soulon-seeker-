package com.soulon.app.rewards

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

/**
 * Seeker Rewards Tracker - SKR 奖励埋点系统
 * 
 * 功能：
 * - 追踪用户活动并触发奖励
 * - 与 Solana Seeker 奖励系统集成
 * - 用户使用应用可获得 SKR 代币
 * 
 * 关键活动：
 * - 存储记忆：10 SKR
 * - 检索记忆：5 SKR
 * - 首次设置：50 SKR
 * - 连接钱包：20 SKR
 * - 每日登录：5 SKR
 * 
 * 官方文档：
 * - https://docs.solanamobile.com/seeker/rewards
 * 
 * @property context Android 应用上下文
 */
class RewardsTracker(private val context: Context) {
    
    companion object {
        // 奖励事件类型
        const val EVENT_MEMORY_STORED = "memory_stored"
        const val EVENT_MEMORY_RETRIEVED = "memory_retrieved"
        const val EVENT_FIRST_SETUP = "first_setup_complete"
        const val EVENT_WALLET_CONNECTED = "wallet_connected"
        const val EVENT_DAILY_LOGIN = "daily_login"
        
        // 奖励金额（SKR）
        const val REWARD_MEMORY_STORED = 10
        const val REWARD_MEMORY_RETRIEVED = 5
        const val REWARD_FIRST_SETUP = 50
        const val REWARD_WALLET_CONNECTED = 20
        const val REWARD_DAILY_LOGIN = 5
        
        // SharedPreferences 键
        private const val PREFS_NAME = "memory_ai_rewards"
        private const val KEY_TOTAL_REWARDS = "total_rewards"
        private const val KEY_LAST_LOGIN_DATE = "last_login_date"
        private const val KEY_FIRST_SETUP_COMPLETED = "first_setup_completed"
    }
    
    private val prefs: SharedPreferences
        get() = WalletScope.scopedPrefs(context, PREFS_NAME)
    
    /**
     * 追踪活动事件
     * 
     * @param eventType 事件类型
     * @param metadata 事件元数据
     * @return 奖励金额
     */
    suspend fun trackActivity(
        eventType: String,
        metadata: Map<String, String> = emptyMap()
    ): Int = withContext(Dispatchers.IO) {
        try {
            Timber.i("追踪活动: $eventType")
            
            // 计算奖励
            val reward = calculateReward(eventType)
            
            if (reward > 0) {
                // 更新本地统计
                updateLocalRewards(reward)
                
                // 记录活动事件
                Timber.i("活动追踪成功，奖励: $reward SKR, 事件: $eventType")
                
                // 尝试同步到 Seeker Rewards (如果 SDK 可用)
                try {
                    syncToSeekerRewards(eventType, metadata, reward)
                } catch (e: Exception) {
                    // Seeker SDK 可能尚未集成或不可用，静默处理
                    Timber.d("Seeker Rewards 同步跳过: ${e.message}")
                }
            }
            
            reward
        } catch (e: Exception) {
            Timber.e(e, "活动追踪失败")
            0
        }
    }
    
    /**
     * 计算奖励金额
     */
    private fun calculateReward(eventType: String): Int {
        return when (eventType) {
            EVENT_MEMORY_STORED -> {
                REWARD_MEMORY_STORED
            }
            EVENT_MEMORY_RETRIEVED -> {
                REWARD_MEMORY_RETRIEVED
            }
            EVENT_FIRST_SETUP -> {
                // 检查是否已经完成首次设置
                if (prefs.getBoolean(KEY_FIRST_SETUP_COMPLETED, false)) {
                    0 // 已经领取过，不再奖励
                } else {
                    prefs.edit().putBoolean(KEY_FIRST_SETUP_COMPLETED, true).apply()
                    REWARD_FIRST_SETUP
                }
            }
            EVENT_WALLET_CONNECTED -> {
                REWARD_WALLET_CONNECTED
            }
            EVENT_DAILY_LOGIN -> {
                // 检查是否今天已经登录
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val lastLogin = prefs.getString(KEY_LAST_LOGIN_DATE, "")
                
                if (lastLogin == today) {
                    0 // 今天已经登录，不再奖励
                } else {
                    prefs.edit().putString(KEY_LAST_LOGIN_DATE, today).apply()
                    REWARD_DAILY_LOGIN
                }
            }
            else -> 0
        }
    }
    
    /**
     * 更新本地奖励统计
     */
    private fun updateLocalRewards(reward: Int) {
        val currentTotal = prefs.getLong(KEY_TOTAL_REWARDS, 0)
        val newTotal = currentTotal + reward
        prefs.edit().putLong(KEY_TOTAL_REWARDS, newTotal).apply()
        Timber.i("累计奖励: $newTotal SKR (+$reward)")
    }
    
    /**
     * 获取累计奖励
     */
    fun getTotalRewards(): Long {
        return prefs.getLong(KEY_TOTAL_REWARDS, 0)
    }
    
    /**
     * 重置奖励统计（测试用）
     */
    fun resetRewards() {
        prefs.edit().clear().apply()
        Timber.i("奖励统计已重置")
    }
    
    /**
     * 同步到 Seeker Rewards 系统
     * 
     * 当 Seeker Rewards SDK 正式发布后，在此处集成真实实现：
     * - 官方文档: https://docs.solanamobile.com/seeker/rewards
     * - 需要添加 SDK 依赖到 build.gradle
     * 
     * 集成步骤:
     * 1. 添加 Seeker Rewards SDK 依赖
     * 2. 初始化 SDK (在 Application 或 Activity 中)
     * 3. 调用 trackEvent API 上报活动
     * 
     * @param eventType 事件类型
     * @param metadata 事件元数据
     * @param reward 奖励金额
     */
    private fun syncToSeekerRewards(
        eventType: String,
        metadata: Map<String, String>,
        reward: Int
    ) {
        // Seeker Rewards SDK 集成占位符
        // 
        // 当 SDK 可用时，替换为类似以下代码:
        // ```
        // val seekerRewards = SeekerRewards.getInstance(context)
        // seekerRewards.trackEvent(
        //     eventName = eventType,
        //     eventData = metadata,
        //     rewardAmount = reward
        // )
        // ```
        
        // 目前记录到本地日志，便于调试和统计
        val eventLog = ActivityEvent(
            type = eventType,
            metadata = metadata,
            reward = reward
        )
        Timber.d("Seeker Rewards 事件记录: $eventLog")
    }
    
    /**
     * 检查 Seeker Rewards SDK 是否可用
     * 
     * @return true 如果 SDK 已集成并初始化
     */
    fun isSeekerRewardsAvailable(): Boolean {
        // 当 SDK 集成后，检查是否在 Seeker 设备上运行
        // return SeekerRewards.isAvailable(context)
        return false
    }
    
    /**
     * 获取 Seeker 奖励余额（如果可用）
     * 
     * @return SKR 余额，如果 SDK 不可用则返回本地统计
     */
    suspend fun getSeekerBalance(): Long = withContext(Dispatchers.IO) {
        // 当 SDK 集成后:
        // val seekerRewards = SeekerRewards.getInstance(context)
        // return seekerRewards.getBalance()
        
        // 目前返回本地统计
        getTotalRewards()
    }
}

/**
 * 活动事件
 */
data class ActivityEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
    val reward: Int = 0
)
