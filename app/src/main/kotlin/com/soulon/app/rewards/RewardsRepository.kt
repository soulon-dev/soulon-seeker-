package com.soulon.app.rewards

import android.content.Context
import com.soulon.app.BuildConfig
import com.soulon.app.data.BackendFirstRepository
import com.soulon.app.data.BalanceState
import com.soulon.app.data.DialogueRewardResult
import com.soulon.app.data.AdventureRewardResult
import com.soulon.app.i18n.LocaleManager
import com.soulon.app.sovereign.SovereignScoreManager
import com.soulon.app.staking.GuardianStakingManager
import com.soulon.app.teepin.TeepinAttestationManager
import com.soulon.app.wallet.SolanaRpcClient
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Rewards Repository
 * 
 * $MEMO 积分系统的业务逻辑层
 * 
 * 🔒 后端优先架构（Backend-First Architecture）
 * 
 * 核心原则：
 * - 后端是唯一数据源 - 所有积分操作必须通过后端验证
 * - 本地数据库只作为只读缓存 - 不允许本地写入影响权威数据
 * - 无离线支持 - 所有操作必须在线进行
 * 
 * 功能：
 * - 积分发放与管理（通过后端 API）
 * - 交易记录（只读缓存）
 * - 用户档案管理（后端同步）
 * - Sovereign Score 联动（Seeker S2）
 * - Guardian 质押加成
 * - TEEPIN Attestation 倍数
 * 
 * Phase 3 Week 2: Task_Tier_System
 * Phase 4: Solana Seeker S2 Integration
 * Phase 5: Backend-First Architecture
 */
class RewardsRepository(private val context: Context) {
    
    private val database = RewardsDatabase.getInstance(context)
    private val dao = database.rewardsDao()
    
    // 🆕 后端优先仓库 - 所有写操作通过此仓库
    private val backendFirstRepo by lazy { BackendFirstRepository.getInstance(context) }
    
    // Solana Seeker S2 组件（延迟初始化）
    private val rpcClient by lazy { SolanaRpcClient() }
    private val sovereignManager by lazy { SovereignScoreManager(context, rpcClient) }
    private val stakingManager by lazy { GuardianStakingManager(context, rpcClient) }
    
    // OkHttp 客户端（解决 SSL 初始化问题）
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept-Language", LocaleManager.getAcceptLanguage(context))
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    companion object {
        // ========== 后端配置 ==========
        private val BACKEND_URL = BuildConfig.BACKEND_BASE_URL
        
        // ========== V1 积分参数（仅用于显示，实际计算由后端完成） ==========
        
        /** 每条对话基础分（固定值，不基于 Token） */
        private const val BASE_SCORE = 10
        
        /** 每日全额积分对话次数上限 */
        private const val DAILY_FULL_REWARD_LIMIT = 50
        
        /** 超出每日限制后的固定积分 */
        private const val OVER_LIMIT_REWARD = 1
        
        /** 每日首聊奖励 */
        private const val FIRST_CHAT_REWARD = 30
        
        /** 7天签到奖励循环 */
        val CHECK_IN_REWARDS = listOf(20, 20, 20, 50, 50, 50, 150)
        
        /** 奇遇任务奖励（大额奖励） */
        private const val ADVENTURE_REWARD = 150
        
        // ========== 人格共鸣评分等级 ==========
        
        /** S级共鸣 (90-100分): +100 MEMO */
        private const val RESONANCE_S_THRESHOLD = 90
        private const val RESONANCE_S_BONUS = 100
        
        /** A级共鸣 (70-89分): +30 MEMO */
        private const val RESONANCE_A_THRESHOLD = 70
        private const val RESONANCE_A_BONUS = 30
        
        /** B级共鸣 (40-69分): +10 MEMO */
        private const val RESONANCE_B_THRESHOLD = 40
        private const val RESONANCE_B_BONUS = 10
        
        /** C级共鸣 (<40分): +0 MEMO */
        private const val RESONANCE_C_BONUS = 0
        
        private const val DEFAULT_USER_ID = "default_user"
    }
    
    // ======================== 后端优先初始化 ========================
    
    /**
     * 初始化后端优先架构（登录后调用）
     * 
     * 🔒 这是启用后端优先数据同步的关键方法
     * 
     * @param walletAddress 用户钱包地址
     */
    suspend fun initializeBackendFirst(walletAddress: String) {
        backendFirstRepo.initialize(walletAddress)
        Timber.i("🔄 后端优先架构已初始化: $walletAddress")
    }
    
    /**
     * 从后端刷新余额和用户状态
     * 
     * 🔒 这是获取最新数据的推荐方法
     */
    suspend fun refreshFromBackend(): Result<com.soulon.app.data.RealTimeBalanceResult> {
        return backendFirstRepo.refreshBalance()
    }
    
    /**
     * 获取后端优先仓库的余额状态流
     */
    fun getBalanceStateFlow() = backendFirstRepo.balanceState
    
    /**
     * 获取后端优先仓库的 Solana 状态流
     */
    fun getSolanaStateFlow() = backendFirstRepo.solanaState
    
    // ======================== 用户档案管理 ========================
    
    /**
     * 获取用户档案（Flow）
     * 使用 onStart 确保在 Flow 开始收集时创建默认档案
     */
    fun getUserProfileFlow(): Flow<UserProfile?> {
        return dao.getUserProfileFlow(DEFAULT_USER_ID)
            .onStart {
                // 确保用户档案存在
                ensureUserProfileExists()
            }
            .map { profile ->
                // 如果仍然为 null（理论上不应该发生），创建默认档案
                profile ?: run {
                    val defaultProfile = UserProfile(userId = DEFAULT_USER_ID)
                    dao.insertUserProfile(defaultProfile)
                    Timber.w("⚠️ Flow 返回 null，创建默认档案")
                    defaultProfile
                }
            }
    }
    
    /**
     * 确保用户档案存在（内部使用）
     */
    private suspend fun ensureUserProfileExists() {
        withContext(Dispatchers.IO) {
            val profile = dao.getUserProfile(DEFAULT_USER_ID)
            if (profile == null) {
                val defaultProfile = UserProfile(userId = DEFAULT_USER_ID)
                dao.insertUserProfile(defaultProfile)
                Timber.i("✅ 创建默认用户档案: $DEFAULT_USER_ID")
            }
        }
    }
    
    /**
     * 获取用户档案
     */
    suspend fun getUserProfile(): UserProfile = withContext(Dispatchers.IO) {
        var profile = dao.getUserProfile(DEFAULT_USER_ID)
        
        if (profile == null) {
            // 创建默认档案
            profile = UserProfile(userId = DEFAULT_USER_ID)
            dao.insertUserProfile(profile)
            Timber.i("创建新用户档案: $DEFAULT_USER_ID")
        }

        val personaProfileV2 = profile.personaProfileV2
        if (profile.personaData == null && personaProfileV2 != null) {
            val derived = personaProfileV2.toLegacyPersonaData()
            val updated = profile.copy(
                personaData = derived,
                lastPersonaAnalysis = derived.analyzedAt
            )
            dao.updateUserProfile(updated)
            profile = updated
        }
        
        return@withContext profile
    }
    
    /**
     * 从后端同步用户配置
     * 当管理员在后台修改用户数据时，App 调用此方法拉取最新数据
     * 
     * @param walletAddress 用户钱包地址
     * @return 是否同步成功
     */
    suspend fun syncFromBackend(walletAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 🔒 使用后端优先架构获取实时余额
            val balanceResult = backendFirstRepo.refreshBalance(walletAddress)
            
            return@withContext balanceResult.fold(
                onSuccess = { balanceData ->
                    Timber.i("🔄 从后端同步: balance=${balanceData.memoBalance}, tier=${balanceData.currentTier}")
                    
                    // 更新本地数据库
                    val currentProfile = getUserProfile()
                    val updatedProfile = currentProfile.copy(
                        memoBalance = balanceData.memoBalance,
                        currentTier = balanceData.currentTier,
                        totalMemoEarned = balanceData.totalMemoEarned,
                        subscriptionType = balanceData.subscriptionType,
                        subscriptionExpiry = balanceData.subscriptionExpiry,
                        dailyDialogueCount = balanceData.dailyDialogueCount,
                        hasFirstChatToday = balanceData.hasFirstChatToday,
                        consecutiveCheckInDays = balanceData.consecutiveCheckInDays,
                        weeklyCheckInProgress = balanceData.weeklyCheckInProgress,
                        totalCheckInDays = balanceData.totalCheckInDays
                    )
                    dao.updateUserProfile(updatedProfile)
                    
                    Timber.i("✅ 后端同步完成: balance=${updatedProfile.memoBalance}, tier=${updatedProfile.currentTier}")
                    true
                },
                onFailure = { e ->
                    Timber.e(e, "❌ 从后端同步失败")
                    false
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ 从后端同步失败")
            return@withContext false
        }
    }
    
    /**
     * 同步本地用户数据到后端
     */
    suspend fun syncToBackend(walletAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val profile = getUserProfile()
            val cloudRepo = com.soulon.app.data.CloudDataRepository.getInstance(context)
            
            // 同步到 CloudDataRepository
            cloudRepo.syncUserDataToBackend(
                memoBalance = profile.memoBalance,
                currentTier = profile.currentTier,
                subscriptionType = profile.subscriptionType,
                subscriptionExpiry = profile.subscriptionExpiry,
                stakedAmount = profile.stakedAmount,
                totalTokensUsed = profile.totalLifetimeTokens.toInt(),
                memoriesCount = 0
            )
            
            // 同时同步到 user_profiles 表（确保卸载重装后恢复）
            syncMemoToBackend(walletAddress, profile.memoBalance, profile.currentTier, profile.totalMemoEarned)
            
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "同步到后端失败")
            return@withContext false
        }
    }
    
    /**
     * 同步积分和等级到后端 user_profiles 表
     * 这是确保卸载重装后能恢复数据的关键
     */
    suspend fun syncMemoToBackend(walletAddress: String, memoBalance: Int, currentTier: Int, totalMemoEarned: Int) = withContext(Dispatchers.IO) {
        try {
            val body = org.json.JSONObject().apply {
                put("memoBalance", memoBalance)
                put("currentTier", currentTier)
                put("totalMemoEarned", totalMemoEarned)
            }
            
            val request = Request.Builder()
                .url("$BACKEND_URL/api/v1/user/$walletAddress/profile")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.code == 200) {
                Timber.d("✅ 积分同步到后端成功: balance=$memoBalance, tier=$currentTier")
            } else {
                Timber.w("积分同步失败: ${response.code}")
            }
        } catch (e: Exception) {
            Timber.e("积分同步异常: ${e.message}")
        }
    }
    
    /**
     * 从后端恢复积分和等级（卸载重装后调用）
     * 
     * 🔒 使用后端优先架构的 /balance 端点获取实时数据
     */
    suspend fun restoreMemoFromBackend(walletAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 🔒 使用 BackendApiClient 获取实时余额
            val result = backendFirstRepo.refreshBalance(walletAddress)
            
            return@withContext result.fold(
                onSuccess = { balanceData ->
                    val backendBalance = balanceData.memoBalance
                    val backendTier = balanceData.currentTier
                    val backendTotalEarned = balanceData.totalMemoEarned
                    
                    // 更新本地数据库
                    val profile = getUserProfile()
                    val updatedProfile = profile.copy(
                        memoBalance = backendBalance,
                        currentTier = backendTier,
                        totalMemoEarned = backendTotalEarned,
                        dailyDialogueCount = balanceData.dailyDialogueCount,
                        hasFirstChatToday = balanceData.hasFirstChatToday,
                        consecutiveCheckInDays = balanceData.consecutiveCheckInDays,
                        weeklyCheckInProgress = balanceData.weeklyCheckInProgress,
                        totalCheckInDays = balanceData.totalCheckInDays
                    )
                    dao.updateUserProfile(updatedProfile)
                    
                    Timber.i("✅ 从后端恢复积分: balance=$backendBalance, tier=$backendTier, totalEarned=$backendTotalEarned")
                    true
                },
                onFailure = { e ->
                    Timber.e("从后端恢复积分失败: ${e.message}")
                    false
                }
            )
        } catch (e: Exception) {
            Timber.e("从后端恢复积分失败: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * 更新 Sovereign Ratio
     */
    suspend fun updateSovereignRatio(ratio: Float) = withContext(Dispatchers.IO) {
        dao.updateSovereignRatio(DEFAULT_USER_ID, ratio.coerceIn(0f, 1f))
        Timber.d("更新 Sovereign Ratio: $ratio")
    }
    
    /**
     * 更新人格数据
     * 
     * @param personaData 新的人格数据
     */
    suspend fun updatePersonaData(personaData: PersonaData) = withContext(Dispatchers.IO) {
        val profile = getUserProfile()
        val updatedProfile = profile.copy(
            personaData = personaData,
            lastPersonaAnalysis = System.currentTimeMillis(),
            personaSyncRate = 1.0f  // 合并后的数据视为完全同步
        )
        dao.updateUserProfile(updatedProfile)
        val wallet = WalletScope.currentWalletAddress(context)
        if (!wallet.isNullOrBlank()) {
            try {
                val api = com.soulon.app.data.BackendApiClient.getInstance(context)
                api.updatePersona(
                    walletAddress = wallet,
                    persona = com.soulon.app.data.PersonaData(
                        openness = personaData.openness,
                        conscientiousness = personaData.conscientiousness,
                        extraversion = personaData.extraversion,
                        agreeableness = personaData.agreeableness,
                        neuroticism = personaData.neuroticism,
                        sampleSize = personaData.sampleSize,
                        analyzedAt = personaData.analyzedAt,
                        syncRate = updatedProfile.personaSyncRate ?: 1.0f
                    )
                )
                updatedProfile.personaProfileV2?.let { v2 ->
                    syncPersonaProfileV2ToBackend(wallet, v2)
                }
            } catch (e: Exception) {
                Timber.w(e, "同步人格数据到后端失败")
            }
        }
        Timber.d("更新人格数据: sampleSize=${personaData.sampleSize}")
    }

    suspend fun reinforcePersonaFromChatEstimate(
        walletAddress: String?,
        estimate: PersonaData,
        sourceId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val profile = getUserProfile()
            val updatedV2 = com.soulon.app.persona.PersonaProfileUpdateEngine.updateFromPointEstimate(
                existing = profile.personaProfileV2,
                estimate = estimate.copy(analyzedAt = now, sampleSize = estimate.sampleSize.coerceAtLeast(1)),
                timestamp = now,
                sourceType = EvidenceSourceType.CHAT,
                sourceId = sourceId
            )
            val updated = profile.copy(
                personaProfileV2 = updatedV2,
                personaData = updatedV2.toLegacyPersonaData(),
                lastPersonaAnalysis = now,
                personaSyncRate = (profile.personaSyncRate ?: 0f).coerceAtLeast(0.6f)
            )
            dao.updateUserProfile(updated)

            if (!walletAddress.isNullOrBlank()) {
                syncPersonaProfileV2ToBackend(walletAddress, updatedV2)
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "对话强化人格失败")
            false
        }
    }

    suspend fun restorePersonaFromBackend(walletAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val api = com.soulon.app.data.BackendApiClient.getInstance(context)
            val backendPersona = api.getPersona(walletAddress) ?: return@withContext false

            val analyzedAtRaw = backendPersona.analyzedAt
            val analyzedAtMs = if (analyzedAtRaw in 1..9_999_999_999L) analyzedAtRaw * 1000 else analyzedAtRaw

            val personaData = PersonaData(
                openness = backendPersona.openness,
                conscientiousness = backendPersona.conscientiousness,
                extraversion = backendPersona.extraversion,
                agreeableness = backendPersona.agreeableness,
                neuroticism = backendPersona.neuroticism,
                analyzedAt = if (analyzedAtMs > 0) analyzedAtMs else System.currentTimeMillis(),
                sampleSize = backendPersona.sampleSize
            )

            val profile = getUserProfile()
            val personaProfileV2 = backendPersona.personaProfileV2Json
                ?.let { json -> PersonaProfileV2Json.fromJson(json) }
            val mergedPersonaData = if (personaData.sampleSize <= 0 && personaProfileV2 != null && personaProfileV2.sampleCount > 0) {
                personaProfileV2.toLegacyPersonaData()
            } else {
                personaData
            }
            val updated = profile.copy(
                personaData = mergedPersonaData,
                personaProfileV2 = personaProfileV2 ?: profile.personaProfileV2,
                lastPersonaAnalysis = mergedPersonaData.analyzedAt,
                personaSyncRate = backendPersona.syncRate
            )
            dao.updateUserProfile(updated)
            Timber.i("✅ 已从后端恢复人格画像: sampleSize=${mergedPersonaData.sampleSize}")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "⚠️ 从后端恢复人格画像失败")
            false
        }
    }

    suspend fun syncPersonaProfileV2ToBackend(walletAddress: String, personaProfileV2: PersonaProfileV2): Boolean = withContext(Dispatchers.IO) {
        try {
            val api = com.soulon.app.data.BackendApiClient.getInstance(context)
            val json = PersonaProfileV2Json.toJson(personaProfileV2)
            val ok = api.updatePersonaProfileV2(walletAddress, json)
            if (ok) {
                Timber.i("✅ 已同步人格画像V2到后端")
            }
            ok
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "同步人格画像V2到后端失败")
            false
        }
    }
    
    // ======================== 积分管理 ========================
    
    /**
     * 获取当前积分余额
     */
    suspend fun getMemoBalance(): Int = withContext(Dispatchers.IO) {
        getUserProfile().memoBalance
    }
    
    /**
     * V1 白皮书积分公式（后端优先架构）
     * 
     * 🔒 重要：积分计算和发放完全由后端控制
     * 
     * Total_MEMO = (Base + Personality_Bonus) × Multiplier
     * 
     * 其中：
     * - Base = 10（每条对话固定分，每日前50条全额，之后1 MEMO/条）
     * - Personality_Bonus = 人格共鸣评分奖励（S/A/B/C 级）
     * - Multiplier = Tier倍数 × Sovereign加成 × 质押加成 × TEEPIN加成
     * 
     * @param resonanceScore 人格共鸣评分 (0-100)，由 AI 评估用户回复与人格画像的匹配度
     * @param memoryId 关联的记忆 ID（本地记录用）
     * @param attestationMultiplier TEEPIN Attestation 倍数（可选，由后端验证）
     * @return RewardResult 包含积分和状态信息
     */
    suspend fun rewardAIInference(
        resonanceScore: Int = 50, // 默认 B 级
        memoryId: String? = null,
        attestationMultiplier: Float? = null
    ): RewardResult = withContext(Dispatchers.IO) {
        // 获取本地缓存的用户档案（用于显示）
        val profile = getUserProfile()
        val dialogueIndex = profile.dailyDialogueCount + 1
        
        // 🔒 通过后端 API 记录对话奖励（积分计算由后端完成）
        val result = backendFirstRepo.recordDialogueReward(
            dialogueIndex = dialogueIndex,
            resonanceScore = resonanceScore
        )
        
        return@withContext when (result) {
            is DialogueRewardResult.Success -> {
                val (_, resonanceGrade) = calculateResonanceBonus(resonanceScore)
                
                // 构建奖励描述
                val bonusDetails = buildList {
                    if (result.resonanceBonus > 0) add("共鸣+${result.resonanceBonus}")
                    if (result.firstChatBonus > 0) add("首聊+${result.firstChatBonus}")
                    if (result.tierMultiplier > 1.0f) add("Tier×${String.format("%.1f", result.tierMultiplier)}")
                }
                val bonusDescription = if (bonusDetails.isNotEmpty()) " (${bonusDetails.joinToString(", ")})" else ""
                
                // 记录本地交易（只读缓存，用于离线查看历史）
                val transaction = MemoTransaction(
                    type = TransactionType.AI_INFERENCE_REWARD,
                    amount = result.reward,
                    description = "AI 对话奖励：${if (result.isOverLimit) "超限" else "第${result.dialogueIndex}条"}$bonusDescription",
                    memoryId = memoryId,
                    userTier = profile.currentTier,
                    tierMultiplier = result.tierMultiplier
                )
                dao.insertTransaction(transaction)
                
                // 🔄 立即更新本地用户档案余额，确保 UI 能及时刷新
                try {
                    dao.updateMemoBalance(DEFAULT_USER_ID, result.reward)
                    dao.incrementDailyDialogueCount(DEFAULT_USER_ID)
                    Timber.d("✅ 本地余额已更新: +${result.reward} MEMO")
                } catch (e: Exception) {
                    Timber.w(e, "更新本地余额失败")
                }
                
                Timber.i("🎯 后端积分: +${result.reward} \$MEMO | 第${result.dialogueIndex}条 | 新余额=${result.newBalance}")
                
                RewardResult(
                    amount = result.reward,
                    resonanceGrade = resonanceGrade,
                    description = bonusDescription,
                    isOverDailyLimit = result.isOverLimit,
                    dailyDialogueCount = result.dialogueIndex
                )
            }
            
            is DialogueRewardResult.Error -> {
                Timber.e("❌ 对话奖励失败: ${result.message}")
                
                // 网络错误时返回零奖励
                RewardResult(
                    amount = 0,
                    resonanceGrade = ResonanceGrade.C,
                    description = "网络错误",
                    isOverDailyLimit = false,
                    dailyDialogueCount = dialogueIndex
                )
            }
        }
    }
    
    /**
     * 计算人格共鸣奖励
     * 
     * S 级 (90-100): +100 MEMO (触发特效，极少见)
     * A 级 (70-89): +30 MEMO (高度符合人格特征)
     * B 级 (40-69): +10 MEMO (正常互动)
     * C 级 (<40): +0 (出戏或无效回复)
     */
    private fun calculateResonanceBonus(score: Int): Pair<Int, ResonanceGrade> {
        return when {
            score >= RESONANCE_S_THRESHOLD -> RESONANCE_S_BONUS to ResonanceGrade.S
            score >= RESONANCE_A_THRESHOLD -> RESONANCE_A_BONUS to ResonanceGrade.A
            score >= RESONANCE_B_THRESHOLD -> RESONANCE_B_BONUS to ResonanceGrade.B
            else -> RESONANCE_C_BONUS to ResonanceGrade.C
        }
    }
    
    /**
     * 补发人格共鸣奖励（异步调用）
     * 
     * 当对话质量分析完成后，根据共鸣评分补发额外奖励
     * 只有 A 级和 S 级才会补发（因为基础奖励已包含 B 级的 +10）
     * 
     * @param resonanceScore 人格共鸣评分 (0-100)
     * @return 补发的积分数量
     */
    suspend fun rewardResonanceBonus(resonanceScore: Int): Int = withContext(Dispatchers.IO) {
        val (bonus, grade) = calculateResonanceBonus(resonanceScore)
        
        // 只有 A 级和 S 级才需要补发（基础已包含 B 级 +10）
        val extraBonus = when (grade) {
            ResonanceGrade.S -> bonus - RESONANCE_B_BONUS  // 100 - 10 = 90
            ResonanceGrade.A -> bonus - RESONANCE_B_BONUS  // 30 - 10 = 20
            else -> 0
        }
        
        if (extraBonus > 0) {
            val profile = getUserProfile()
            
            val transaction = MemoTransaction(
                type = TransactionType.SOUL_RESONANCE,
                amount = extraBonus,
                description = "${grade.displayName}级人格共鸣奖励"
            )
            dao.insertTransaction(transaction)
            
            dao.updateMemoBalance(DEFAULT_USER_ID, extraBonus)
            dao.updateTotalMemoEarned(DEFAULT_USER_ID, extraBonus)
            
            // 检查等级升级
            checkAndUpgradeTier(profile.totalMemoEarned, extraBonus, profile.currentTier)
            
            Timber.i("✨ 人格共鸣奖励: +$extraBonus \$MEMO (${grade.displayName}级, 评分=$resonanceScore)")
        }
        
        return@withContext extraBonus
    }
    
    /**
     * 根据积分计算等级
     * 
     * V1 白皮书等级门槛：
     * - Bronze (1): 0 积分
     * - Silver (2): 2,500 积分
     * - Gold (3): 12,000 积分
     * - Platinum (4): 50,000 积分
     * - Diamond (5): 200,000 积分
     */
    private fun calculateTierFromPoints(totalPoints: Int): Int {
        return when {
            totalPoints >= 200_000 -> 5  // Diamond
            totalPoints >= 50_000 -> 4   // Platinum
            totalPoints >= 12_000 -> 3   // Gold
            totalPoints >= 2_500 -> 2    // Silver
            else -> 1                    // Bronze
        }
    }
    
    /**
     * 检查并升级等级（在积分更新后调用）
     * 
     * @param previousPoints 之前的总积分
     * @param addedPoints 新增的积分
     * @param currentTier 当前等级
     */
    private suspend fun checkAndUpgradeTier(previousPoints: Int, addedPoints: Int, currentTier: Int) {
        val newTotalPoints = previousPoints + addedPoints
        val newTier = calculateTierFromPoints(newTotalPoints)
        if (newTier > currentTier) {
            dao.updateTier(DEFAULT_USER_ID, newTier)
            Timber.i("🎉 等级提升！从 $currentTier 升级到 $newTier (总积分: $newTotalPoints)")
        }
    }
    
    /**
     * 奖励每日首聊
     * 
     * 🔒 后端优先架构：首聊奖励作为对话奖励的一部分由后端自动发放
     * 
     * AI 根据人格主动问候，用户回复：+30 MEMO
     * 
     * 注意：首聊奖励现在通过 rewardAIInference 的 firstChatBonus 自动发放
     * 此方法保留用于兼容性，但不再直接发放奖励
     */
    suspend fun rewardFirstChat(): RewardResult = withContext(Dispatchers.IO) {
        // 🔒 后端优先：首聊奖励通过 recordDialogueReward API 自动发放
        // 检查后端返回的余额状态
        val balanceData = backendFirstRepo.getCurrentBalance()
        
        if (balanceData?.hasFirstChatToday == true) {
            Timber.d("今日首聊奖励已由后端发放")
            return@withContext RewardResult(0, ResonanceGrade.B, "今日已领取")
        }
        
        // 如果后端显示未领取首聊，触发一次对话奖励（首聊奖励会自动计入）
        val result = backendFirstRepo.recordDialogueReward(
            dialogueIndex = 1,
            resonanceScore = 50,
            isFirstChat = true
        )
        
        return@withContext when (result) {
            is DialogueRewardResult.Success -> {
                if (result.firstChatBonus > 0) {
                    // 记录本地交易
                    val transaction = MemoTransaction(
                        type = TransactionType.DAILY_BONUS,
                        amount = result.firstChatBonus,
                        description = "每日首聊奖励 (Tier×${String.format("%.1f", result.tierMultiplier)})"
                    )
                    dao.insertTransaction(transaction)
                    
                    Timber.i("🌅 每日首聊(后端优先): +${result.firstChatBonus} \$MEMO")
                }
                
                RewardResult(result.firstChatBonus, ResonanceGrade.B, "每日首聊")
            }
            
            is DialogueRewardResult.Error -> {
                Timber.e("❌ 首聊奖励失败: ${result.message}")
                RewardResult(0, ResonanceGrade.C, "网络错误")
            }
        }
    }
    
    /**
     * 每日签到奖励 - 通过后端验证防刷
     * 
     * 🔒 后端优先架构：签到逻辑完全由后端控制
     * 
     * 采用 7 天循环（20, 20, 20, 50, 50, 50, 150）
     * 全球统一使用 UTC 0点作为日切换时间（北京时间早上8点）
     */
    suspend fun checkIn(walletAddress: String): CheckInResult = withContext(Dispatchers.IO) {
        // 🔒 通过 BackendFirstRepository 进行签到
        val result = backendFirstRepo.checkIn()
        
        if (result.success) {
            // 记录本地交易（只读缓存，用于历史查看）
            val transaction = MemoTransaction(
                type = TransactionType.CHECK_IN,
                amount = result.reward,
                description = "签到奖励：第 ${result.weeklyProgress} 天 (连续 ${result.consecutiveDays} 天)"
            )
            dao.insertTransaction(transaction)
            
            Timber.i("📅 签到成功(后端优先): +${result.reward} \$MEMO | 第 ${result.weeklyProgress} 天/7 | 连续 ${result.consecutiveDays} 天")
        }
        
        return@withContext result
    }
    
    /**
     * 计算距离下次 UTC 0点的剩余秒数（本地计算）
     */
    private fun calculateSecondsUntilUtcMidnight(): Int {
        val now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC)
        return java.time.Duration.between(now, nextMidnight).seconds.toInt()
    }
    
    /**
     * 获取签到状态（用于显示倒计时）
     * 
     * 🔒 后端优先架构：从 BackendFirstRepository 获取状态
     * 
     * @param walletAddress 钱包地址
     * @return CheckInStatus 包含签到状态和倒计时信息
     */
    suspend fun getCheckInStatus(walletAddress: String): CheckInStatus = withContext(Dispatchers.IO) {
        // 🔒 通过 BackendFirstRepository 获取签到状态
        backendFirstRepo.getCheckInStatus()
    }
    
    /**
     * 奖励奇遇任务完成
     * 
     * 🔒 后端优先架构：奇遇验证和奖励发放完全由后端控制
     * 
     * 完成奇遇任务获得大额奖励 (150 MEMO)
     * 
     * @param walletAddress 钱包地址（用于后端验证）
     * @param questionId 奇遇问题 ID
     * @param questionText 奇遇问题内容
     * @return 实际获得的积分（含等级倍数加成）
     */
    suspend fun rewardAdventure(
        walletAddress: String,
        questionId: String,
        questionText: String
    ): Int = withContext(Dispatchers.IO) {
        // 🔒 通过 BackendFirstRepository 完成奇遇
        val result = backendFirstRepo.completeAdventure(questionId, questionText)
        
        return@withContext when (result) {
            is AdventureRewardResult.Success -> {
                // 记录本地交易（只读缓存）
                val transaction = MemoTransaction(
                    type = TransactionType.TASK_COMPLETION,
                    amount = result.reward,
                    description = "✨ 奇遇任务完成：${questionText.take(20)}..."
                )
                dao.insertTransaction(transaction)
                
                Timber.i("✨ 奇遇完成(后端优先): +${result.reward} \$MEMO | 新余额=${result.newBalance}")
                result.reward
            }
            
            is AdventureRewardResult.AlreadyCompleted -> {
                Timber.w("奇遇已完成，无法重复领取")
                0
            }
            
            is AdventureRewardResult.Error -> {
                Timber.e("❌ 奇遇奖励失败: ${result.message}")
                0
            }
        }
    }
    
    /**
     * 奖励通用积分
     * 
     * ⚠️ 后端优先架构注意：
     * 此方法仅用于本地记录，不会同步到后端。
     * 对于需要后端验证的奖励（如对话、签到、奇遇），
     * 请使用对应的后端 API 方法。
     * 
     * 此方法仅用于：
     * - 本地测试/调试
     * - 离线模式下的临时记录（需后续同步）
     */
    @Deprecated(
        message = "后端优先架构下，积分发放应通过后端 API 进行。此方法仅用于本地记录。",
        replaceWith = ReplaceWith("backendFirstRepo.recordDialogueReward()")
    )
    suspend fun rewardMemo(
        amount: Int,
        type: TransactionType,
        description: String,
        memoryId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (amount <= 0) {
            Timber.w("奖励金额必须大于 0: $amount")
            return@withContext false
        }
        
        Timber.w("⚠️ 使用本地 rewardMemo，积分不会同步到后端")
        
        val profile = getUserProfile()
        
        val transaction = MemoTransaction(
            type = type,
            amount = amount,
            description = "[本地] $description",
            memoryId = memoryId
        )
        dao.insertTransaction(transaction)
        
        dao.updateMemoBalance(DEFAULT_USER_ID, amount)
        dao.updateTotalMemoEarned(DEFAULT_USER_ID, amount)
        
        // 检查等级升级
        checkAndUpgradeTier(profile.totalMemoEarned, amount, profile.currentTier)
        
        Timber.i("⚠️ 本地奖励积分: +$amount \$MEMO - $description (未同步到后端)")
        
        return@withContext true
    }
    
    /**
     * 消耗积分
     * 
     * ⚠️ 后端优先架构注意：
     * 此方法仅用于本地记录，不会同步到后端。
     * 对于需要后端验证的消费操作，请通过后端 API 进行。
     */
    @Deprecated(
        message = "后端优先架构下，积分消费应通过后端 API 进行。此方法仅用于本地记录。"
    )
    suspend fun spendMemo(
        amount: Int,
        description: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (amount <= 0) {
            Timber.w("消耗金额必须大于 0: $amount")
            return@withContext false
        }
        
        Timber.w("⚠️ 使用本地 spendMemo，积分变动不会同步到后端")
        
        val currentBalance = getMemoBalance()
        if (currentBalance < amount) {
            Timber.w("积分不足: 需要 $amount, 当前 $currentBalance")
            return@withContext false
        }
        
        val transaction = MemoTransaction(
            type = TransactionType.SPEND,
            amount = -amount,
            description = "[本地] $description"
        )
        dao.insertTransaction(transaction)
        
        dao.updateMemoBalance(DEFAULT_USER_ID, -amount)
        
        Timber.i("⚠️ 本地消耗积分: -$amount \$MEMO - $description (未同步到后端)")
        
        return@withContext true
    }
    
    // ======================== 交易记录 ========================
    
    /**
     * 获取所有交易记录（Flow）
     */
    fun getAllTransactionsFlow(): Flow<List<MemoTransaction>> {
        return dao.getAllTransactionsFlow()
    }

    fun getAllTransactionLogsFlow(): Flow<List<MemoTransactionLog>> {
        return dao.getAllTransactionLogsFlow()
    }
    
    /**
     * 获取最近的交易记录
     */
    suspend fun getRecentTransactions(limit: Int = 20): List<MemoTransaction> = withContext(Dispatchers.IO) {
        dao.getRecentTransactions(limit)
    }
    
    /**
     * 根据记忆 ID 获取交易记录
     */
    suspend fun getTransactionsByMemory(memoryId: String): List<MemoTransaction> = withContext(Dispatchers.IO) {
        dao.getTransactionsByMemory(memoryId)
    }
    
    /**
     * 计算今日收入
     */
    suspend fun getTodayIncome(): Int = withContext(Dispatchers.IO) {
        val startOfDay = getStartOfDay()
        val endOfDay = System.currentTimeMillis()
        dao.getTotalIncomeBetween(startOfDay, endOfDay) ?: 0
    }
    
    /**
     * 计算今日支出
     */
    suspend fun getTodaySpend(): Int = withContext(Dispatchers.IO) {
        val startOfDay = getStartOfDay()
        val endOfDay = System.currentTimeMillis()
        dao.getTotalSpendBetween(startOfDay, endOfDay) ?: 0
    }
    
    // ======================== 统计与分析 ========================
    
    /**
     * 获取积分统计
     */
    suspend fun getMemoStats(): MemoStats = withContext(Dispatchers.IO) {
        val profile = getUserProfile()
        val todayIncome = getTodayIncome()
        val todaySpend = getTodaySpend()
        val transactionCount = dao.getTransactionCount()
        
        MemoStats(
            currentBalance = profile.memoBalance,
            totalEarned = profile.totalMemoEarned,
            todayIncome = todayIncome,
            todaySpend = todaySpend,
            currentTier = profile.currentTier,
            tierMultiplier = profile.getTierMultiplier(),
            totalTokens = profile.totalTokensGenerated,
            totalInferences = profile.totalInferences,
            transactionCount = transactionCount
        )
    }
    
    // ======================== 辅助方法 ========================
    
    /**
     * 获取今天零点的时间戳
     */
    private fun getStartOfDay(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}

/**
 * 积分统计数据
 */
data class MemoStats(
    val currentBalance: Int,
    val totalEarned: Int,
    val todayIncome: Int,
    val todaySpend: Int,
    val currentTier: Int,
    val tierMultiplier: Float,
    val totalTokens: Int,
    val totalInferences: Int,
    val transactionCount: Int
)

/**
 * V1 积分奖励结果
 */
data class RewardResult(
    /** 获得的积分数量 */
    val amount: Int,
    /** 人格共鸣等级 */
    val resonanceGrade: ResonanceGrade,
    /** 描述信息 */
    val description: String,
    /** 是否超出每日限制 */
    val isOverDailyLimit: Boolean = false,
    /** 今日对话条数 */
    val dailyDialogueCount: Int = 0
) {
    /** 是否触发 S 级特效 */
    val isSoulResonance: Boolean get() = resonanceGrade == ResonanceGrade.S
    
    /** 获取显示文本，如 "+30 (Soul Resonance!)" */
    fun getDisplayText(): String {
        return when {
            isSoulResonance -> "+$amount (Soul Resonance!)"
            resonanceGrade == ResonanceGrade.A -> "+$amount (共鸣)"
            else -> "+$amount"
        }
    }
}

/**
 * 人格共鸣等级
 */
enum class ResonanceGrade(val displayName: String, val minScore: Int, val bonus: Int) {
    S("灵魂共鸣", 90, 100),  // 极少见，触发特效
    A("高度共鸣", 70, 30),   // 高度符合人格特征
    B("正常互动", 40, 10),   // 普通交互
    C("无效回复", 0, 0)      // 出戏或无效
}

/**
 * 签到结果
 */
data class CheckInResult(
    val success: Boolean,
    val reward: Int,
    val consecutiveDays: Int,
    val weeklyProgress: Int,
    val message: String,
    /** 距离下次签到重置的剩余秒数（UTC 0点重置） */
    val secondsUntilReset: Int = 0
) {
    /**
     * 获取格式化的倒计时字符串 (HH:MM:SS)
     */
    fun getCountdownDisplay(): String {
        val hours = secondsUntilReset / 3600
        val minutes = (secondsUntilReset % 3600) / 60
        val seconds = secondsUntilReset % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}

/**
 * 签到状态（用于显示倒计时）
 * 
 * 全球统一使用 UTC 0点（北京时间早上8点）作为日切换时间
 */
data class CheckInStatus(
    /** 今日是否已签到 */
    val hasCheckedInToday: Boolean,
    /** 连续签到天数 */
    val consecutiveDays: Int,
    /** 本周签到进度 (1-7) */
    val weeklyProgress: Int,
    /** 总签到天数 */
    val totalCheckInDays: Int,
    /** 距离下次重置的剩余秒数 */
    val secondsUntilReset: Int
) {
    /**
     * 获取格式化的倒计时字符串 (HH:MM:SS)
     */
    fun getCountdownDisplay(): String {
        val hours = secondsUntilReset / 3600
        val minutes = (secondsUntilReset % 3600) / 60
        val seconds = secondsUntilReset % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
    
    /**
     * 获取用户友好的倒计时提示
     */
    fun getCountdownMessage(): String {
        return if (hasCheckedInToday) {
            "已签到 | 下次签到：${getCountdownDisplay()}"
        } else {
            "距离今日签到结束：${getCountdownDisplay()}"
        }
    }
}
