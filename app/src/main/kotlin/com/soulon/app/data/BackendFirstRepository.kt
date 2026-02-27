package com.soulon.app.data

import android.content.Context
import com.soulon.app.i18n.AppStrings
import com.soulon.app.rewards.CheckInResult
import com.soulon.app.rewards.CheckInStatus
import com.soulon.app.rewards.RewardsDatabase
import com.soulon.app.rewards.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 后端优先数据仓库
 * 
 * 🔒 核心原则：
 * 1. 后端是唯一数据源 - 所有资源获取和交易数据必须在线
 * 2. 本地数据库只作为只读缓存 - 不允许本地写入影响权威数据
 * 3. 所有链上操作通过后端代理 - 防止客户端篡改和网络威胁
 * 4. 无离线支持 - 所有操作必须在线进行
 * 
 * 功能：
 * - 实时余额获取（MEMO、SOL、代币）
 * - 签到（后端验证防刷）
 * - 对话奖励（后端计算和发放）
 * - 奇遇任务（后端验证防重复）
 * - Solana 链上操作代理
 */
class BackendFirstRepository(private val context: Context) {
    
    private val apiClient = BackendApiClient.getInstance(context)
    private val database = RewardsDatabase.getInstance(context)
    private val dao = database.rewardsDao()
    
    // 当前钱包地址
    private var currentWalletAddress: String? = null
    
    // 实时数据状态流
    private val _balanceState = MutableStateFlow<BalanceState>(BalanceState.Loading)
    val balanceState: StateFlow<BalanceState> = _balanceState.asStateFlow()
    
    private val _solanaState = MutableStateFlow<SolanaState>(SolanaState.Loading)
    val solanaState: StateFlow<SolanaState> = _solanaState.asStateFlow()
    
    companion object {
        @Volatile
        private var INSTANCE: BackendFirstRepository? = null
        
        fun getInstance(context: Context): BackendFirstRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackendFirstRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
        
        private const val TAG = "BackendFirstRepo"
    }
    
    // ======================== 初始化 ========================
    
    /**
     * 初始化仓库（登录后调用）
     * 
     * @param walletAddress 用户钱包地址
     */
    suspend fun initialize(walletAddress: String) {
        currentWalletAddress = walletAddress
        Timber.tag(TAG).i("🔄 初始化后端优先仓库: $walletAddress")
        
        // 立即刷新所有数据
        refreshAllData()
    }
    
    /**
     * 刷新所有数据
     */
    suspend fun refreshAllData() {
        val wallet = currentWalletAddress ?: run {
            Timber.tag(TAG).w("未初始化，无法刷新数据")
            return
        }
        
        // 并行刷新余额和 Solana 数据
        coroutineScope {
            launch { refreshBalance() }
            launch { refreshSolanaData() }
        }
    }
    
    // ======================== 实时余额 API ========================
    
    /**
     * 刷新 MEMO 余额和用户状态（从后端获取）
     * 
     * 🔒 这是获取用户数据的唯一正确方式
     */
    suspend fun refreshBalance(): Result<RealTimeBalanceResult> = withContext(Dispatchers.IO) {
        val wallet = currentWalletAddress ?: return@withContext Result.failure(
            IllegalStateException(AppStrings.tr("未初始化钱包地址", "Wallet address not initialized"))
        )
        
        _balanceState.value = BalanceState.Loading
        
        try {
            val result = apiClient.getRealTimeBalance(wallet)
            
            if (result != null) {
                // 更新本地缓存（只读）
                updateLocalCache(result)
                
                _balanceState.value = BalanceState.Success(result)
                Timber.tag(TAG).i("✅ 余额刷新成功: ${result.memoBalance} MEMO, Tier ${result.currentTier}")
                Result.success(result)
            } else {
                val error = AppStrings.tr("获取余额失败", "Failed to load balance")
                _balanceState.value = BalanceState.Error(error)
                Timber.tag(TAG).e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            val error = AppStrings.trf("网络错误: %s", "Network error: %s", e.message ?: "")
            _balanceState.value = BalanceState.Error(error)
            Timber.tag(TAG).e(e, "❌ $error")
            Result.failure(e)
        }
    }

    suspend fun refreshBalance(walletAddress: String): Result<RealTimeBalanceResult> {
        currentWalletAddress = walletAddress
        return refreshBalance()
    }
    
    /**
     * 获取当前余额（从缓存状态）
     */
    fun getCurrentBalance(): RealTimeBalanceResult? {
        return when (val state = _balanceState.value) {
            is BalanceState.Success -> state.data
            else -> null
        }
    }
    
    /**
     * 更新本地缓存（只读，用于 UI 显示）
     */
    private suspend fun updateLocalCache(data: RealTimeBalanceResult) {
        try {
            val existingProfile = dao.getUserProfile("default_user")
            
            val updatedProfile = (existingProfile ?: UserProfile(userId = "default_user")).copy(
                memoBalance = data.memoBalance,
                currentTier = data.currentTier,
                totalMemoEarned = data.totalMemoEarned,
                subscriptionType = data.subscriptionType,
                subscriptionExpiry = data.subscriptionExpiry,
                dailyDialogueCount = data.dailyDialogueCount,
                hasFirstChatToday = data.hasFirstChatToday,
                consecutiveCheckInDays = data.consecutiveCheckInDays,
                weeklyCheckInProgress = data.weeklyCheckInProgress,
                totalCheckInDays = data.totalCheckInDays
            )
            
            if (existingProfile == null) {
                dao.insertUserProfile(updatedProfile)
            } else {
                dao.updateUserProfile(updatedProfile)
            }
            
            Timber.tag(TAG).d("📦 本地缓存已更新")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "更新本地缓存失败（非致命）")
        }
    }
    
    // ======================== 签到 API ========================
    
    /**
     * 执行签到（后端验证）
     * 
     * 🔒 签到逻辑完全由后端控制，防止刷签到
     */
    suspend fun checkIn(): CheckInResult = withContext(Dispatchers.IO) {
        val wallet = currentWalletAddress ?: return@withContext CheckInResult(
            success = false,
            reward = 0,
            consecutiveDays = 0,
            weeklyProgress = 0,
            message = AppStrings.tr("请先连接钱包", "Please connect your wallet first"),
            secondsUntilReset = 0
        )
        
        try {
            val apiResult = apiClient.checkIn(wallet)
            when (apiResult) {
                is CheckInApiResult.Success -> {
                    // 刷新余额以获取最新数据
                    refreshBalance()
                    
                    Timber.tag(TAG).i("✅ 签到成功: +${apiResult.reward} MEMO")
                    
                    return@withContext CheckInResult(
                        success = true,
                        reward = apiResult.reward,
                        consecutiveDays = apiResult.consecutiveDays,
                        weeklyProgress = apiResult.weeklyProgress,
                        message = if (apiResult.weeklyProgress == 7) {
                            AppStrings.tr("🎉 第7天！获得额外奖励！", "🎉 Day 7! Extra bonus!")
                        } else {
                            AppStrings.tr("签到成功", "Check-in successful")
                        },
                        secondsUntilReset = apiResult.secondsUntilReset
                    )
                }
                
                is CheckInApiResult.AlreadyCheckedIn -> {
                    Timber.tag(TAG).d("今日已签到")
                    return@withContext CheckInResult(
                        success = false,
                        reward = 0,
                        consecutiveDays = 0,
                        weeklyProgress = 0,
                        message = AppStrings.tr("今日已签到", "Already checked in today"),
                        secondsUntilReset = apiResult.secondsUntilReset
                    )
                }
                
                is CheckInApiResult.Error -> {
                    Timber.tag(TAG).e("签到失败: ${apiResult.message}")
                    return@withContext CheckInResult(
                        success = false,
                        reward = 0,
                        consecutiveDays = 0,
                        weeklyProgress = 0,
                        message = apiResult.message,
                        secondsUntilReset = 0
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "签到请求异常")
            CheckInResult(
                success = false,
                reward = 0,
                consecutiveDays = 0,
                weeklyProgress = 0,
                message = AppStrings.tr("网络错误，请稍后重试", "Network error, please try again later"),
                secondsUntilReset = 0
            )
        }
    }
    
    /**
     * 获取签到状态
     */
    suspend fun getCheckInStatus(): CheckInStatus {
        val balanceData = getCurrentBalance()
        
        return CheckInStatus(
            hasCheckedInToday = balanceData?.hasCheckedInToday ?: false,
            consecutiveDays = balanceData?.consecutiveCheckInDays ?: 0,
            weeklyProgress = balanceData?.weeklyCheckInProgress ?: 0,
            totalCheckInDays = balanceData?.totalCheckInDays ?: 0,
            secondsUntilReset = calculateSecondsUntilUtcMidnight()
        )
    }
    
    // ======================== 对话奖励 API ========================
    
    /**
     * 记录对话奖励（后端计算和发放）
     * 
     * 🔒 奖励计算完全由后端控制，客户端不能自行发放积分
     * 
     * @param dialogueIndex 今日第几条对话（用于日志）
     * @param resonanceScore 人格共鸣评分 (0-100)
     * @param isFirstChat 是否为今日首聊
     * @param sessionId 会话 ID（可选）
     * @return DialogueRewardResult 奖励结果
     */
    suspend fun recordDialogueReward(
        dialogueIndex: Int,
        resonanceScore: Int = 50,
        isFirstChat: Boolean = false,
        sessionId: String? = null
    ): DialogueRewardResult = withContext(Dispatchers.IO) {
        val wallet = currentWalletAddress ?: return@withContext DialogueRewardResult.Error(
            AppStrings.tr("请先连接钱包", "Please connect your wallet first")
        )
        
        try {
            // 转换共鸣评分为等级
            val resonanceGrade = when {
                resonanceScore >= 90 -> "S"
                resonanceScore >= 70 -> "A"
                resonanceScore >= 40 -> "B"
                else -> "C"
            }
            
            val result = apiClient.recordDialogueReward(
                walletAddress = wallet,
                sessionId = sessionId,
                isFirstChat = isFirstChat,
                resonanceGrade = resonanceGrade,
                resonanceScore = resonanceScore
            )
            
            if (result is DialogueRewardResult.Success) {
                // 刷新余额
                refreshBalance()
                Timber.tag(TAG).i("✅ 对话奖励: +${result.reward} MEMO (第${result.dialogueIndex}条)")
            }
            
            result
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "对话奖励请求异常")
            DialogueRewardResult.Error(AppStrings.trf("网络错误: %s", "Network error: %s", e.message ?: ""))
        }
    }
    
    // ======================== 奇遇任务 API ========================
    
    /**
     * 完成奇遇任务（后端验证防重复）
     * 
     * 🔒 奇遇完成由后端验证，防止重复领取
     * 
     * @param questionId 奇遇问题 ID
     * @param questionText 奇遇问题内容
     * @return AdventureRewardResult 奖励结果
     */
    suspend fun completeAdventure(
        questionId: String,
        questionText: String
    ): AdventureRewardResult = withContext(Dispatchers.IO) {
        val wallet = currentWalletAddress ?: return@withContext AdventureRewardResult.Error(
            AppStrings.tr("请先连接钱包", "Please connect your wallet first")
        )
        
        try {
            val result = apiClient.completeAdventure(wallet, questionId, questionText)
            
            if (result is AdventureRewardResult.Success) {
                // 刷新余额
                refreshBalance()
                Timber.tag(TAG).i("✅ 奇遇完成: +${result.reward} MEMO")
            }
            
            result
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "奇遇任务请求异常")
            AdventureRewardResult.Error(AppStrings.trf("网络错误: %s", "Network error: %s", e.message ?: ""))
        }
    }
    
    // ======================== Solana 链上操作代理 ========================
    
    /**
     * 刷新 Solana 数据（SOL 余额、代币、质押）
     */
    suspend fun refreshSolanaData() = withContext(Dispatchers.IO) {
        val wallet = currentWalletAddress ?: return@withContext
        
        _solanaState.value = SolanaState.Loading
        
        try {
            // 并行获取 SOL 余额、代币和质押状态
            val solResult = apiClient.getSolanaBalance(wallet)
            val tokensResult = apiClient.getSolanaTokens(wallet)
            val stakingResult = apiClient.getSolanaStaking(wallet)
            
            val solBalance: SolanaBalanceResult.Success? = when (solResult) {
                is SolanaBalanceResult.Success -> solResult
                else -> null
            }
            
            val tokens: List<TokenBalance> = when (tokensResult) {
                is SolanaTokensResult.Success -> tokensResult.tokens
                else -> emptyList()
            }
            
            val staking: SolanaStakingResult.Success? = when (stakingResult) {
                is SolanaStakingResult.Success -> stakingResult
                else -> null
            }
            
            _solanaState.value = SolanaState.Success(
                solBalance = solBalance,
                tokens = tokens,
                staking = staking
            )
            
            Timber.tag(TAG).i("✅ Solana 数据刷新成功")
        } catch (e: Exception) {
            _solanaState.value = SolanaState.Error(
                AppStrings.trf("Solana 数据获取失败: %s", "Failed to load Solana data: %s", e.message ?: "")
            )
            Timber.tag(TAG).e(e, "❌ Solana 数据刷新失败")
        }
    }
    
    /**
     * 获取 SOL 余额（通过后端代理）
     */
    suspend fun getSolanaBalance(): SolanaBalanceResult = withContext(Dispatchers.IO) {
        val wallet = currentWalletAddress ?: return@withContext SolanaBalanceResult.Error(
            AppStrings.tr("请先连接钱包", "Please connect your wallet first")
        )
        
        apiClient.getSolanaBalance(wallet)
    }
    
    /**
     * 获取代币余额（通过后端代理）
     */
    suspend fun getSolanaTokens(): SolanaTokensResult = withContext(Dispatchers.IO) {
        val wallet = currentWalletAddress ?: return@withContext SolanaTokensResult.Error(
            AppStrings.tr("请先连接钱包", "Please connect your wallet first")
        )
        
        apiClient.getSolanaTokens(wallet)
    }
    
    /**
     * 获取质押状态（通过后端代理）
     */
    suspend fun getSolanaStaking(): SolanaStakingResult = withContext(Dispatchers.IO) {
        val wallet = currentWalletAddress ?: return@withContext SolanaStakingResult.Error(
            AppStrings.tr("请先连接钱包", "Please connect your wallet first")
        )
        
        apiClient.getSolanaStaking(wallet)
    }
    
    /**
     * 验证 Solana 交易（通过后端代理）
     * 
     * 🔒 交易验证必须通过后端，防止伪造交易
     * 
     * @param signature 交易签名
     * @return TransactionVerifyResult 验证结果
     */
    suspend fun verifySolanaTransaction(signature: String): TransactionVerifyResult = withContext(Dispatchers.IO) {
        apiClient.verifySolanaTransaction(signature)
    }
    
    // ======================== 辅助方法 ========================
    
    /**
     * 计算距离下次 UTC 0点的剩余秒数
     */
    private fun calculateSecondsUntilUtcMidnight(): Int {
        val now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC)
        return java.time.Duration.between(now, nextMidnight).seconds.toInt()
    }
    
    /**
     * 获取当前钱包地址
     */
    fun getCurrentWalletAddress(): String? = currentWalletAddress
    
    /**
     * 清除会话（登出时调用）
     */
    fun clearSession() {
        currentWalletAddress = null
        _balanceState.value = BalanceState.Loading
        _solanaState.value = SolanaState.Loading
        Timber.tag(TAG).i("会话已清除")
    }
}

// ======================== 状态类 ========================

/**
 * MEMO 余额状态
 */
sealed class BalanceState {
    object Loading : BalanceState()
    data class Success(val data: RealTimeBalanceResult) : BalanceState()
    data class Error(val message: String) : BalanceState()
}

/**
 * Solana 数据状态
 */
sealed class SolanaState {
    object Loading : SolanaState()
    data class Success(
        val solBalance: SolanaBalanceResult.Success?,
        val tokens: List<TokenBalance>,
        val staking: SolanaStakingResult.Success?
    ) : SolanaState()
    data class Error(val message: String) : SolanaState()
}
