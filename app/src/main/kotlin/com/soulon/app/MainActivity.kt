package com.soulon.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.ui.draw.scale
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import com.soulon.app.ui.theme.AppCorners
import com.soulon.app.ui.theme.AppIconSizes
import com.soulon.app.ui.theme.AppSpacing
import com.soulon.app.ui.theme.AppShapes
import com.soulon.app.ui.theme.AppColors
import com.soulon.app.ui.theme.AppElevations
import com.soulon.app.ui.theme.modernCardShadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import kotlinx.coroutines.withContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.soulon.app.ai.QwenCloudManager
import com.soulon.app.rag.PersonalizedRAG
import com.soulon.app.rewards.RewardsRepository
import com.soulon.app.ui.AIChatScreen
import com.soulon.app.ui.ChatResponse
import com.soulon.app.ui.PersonaDashboard
import com.soulon.app.ui.OnboardingScreen
import com.soulon.app.ui.OnboardingCompletionScreen
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.LocaleManager
import com.soulon.app.i18n.OnDeviceTranslationManager
import com.soulon.app.i18n.TranslationBundleStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import com.soulon.app.ui.showComingSoonToast
import com.soulon.app.wallet.WalletScope

/**
 * MainActivity - 应用主界面
 * 
 * 整合功能：
 * - Phase 2: 钱包连接、记忆存储、Irys 永久存储
 * - Phase 3: AI 对话、$MEMO 积分、人格分析、RAG
 * - 国际化: 多语言支持
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {
    
    // Phase 2 管理器
    private lateinit var keyManager: SeedVaultKeyManager
    private lateinit var storageManager: StorageManager
    private lateinit var walletManager: com.soulon.app.wallet.WalletManager
    private lateinit var activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender
    
    // Phase 3 管理器
    private lateinit var rewardsRepository: RewardsRepository
    private lateinit var personalizedRAG: PersonalizedRAG
    private lateinit var userLevelManager: com.soulon.app.rewards.UserLevelManager
    private lateinit var irysSyncService: com.soulon.app.sync.IrysSyncService
    
    // 语言管理器
    private lateinit var localeManager: com.soulon.app.i18n.LocaleManager
    
    // 远程配置管理器 - 实时同步后台配置
    private lateinit var remoteConfigManager: com.soulon.app.config.RemoteConfigManager

    override fun attachBaseContext(newBase: Context) {
        val languageCode = LocaleManager.getSavedLanguageCode(newBase)
            ?: LocaleManager.getDefaultLanguageCode(newBase)
        super.attachBaseContext(LocaleManager.applyLocaleToContext(newBase, languageCode))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化语言管理器（必须在 setContent 之前）
        localeManager = com.soulon.app.i18n.LocaleManager(applicationContext)
        localeManager.initializeLocale()
        OnDeviceTranslationManager.initialize(applicationContext)
        TranslationBundleStore.initialize(applicationContext)
        val desiredLang = localeManager.getPendingLanguageCode() ?: localeManager.getSelectedLanguageCode()
        com.soulon.app.i18n.TranslationWarmupManager.start(applicationContext, desiredLang)
        
        // 设置状态栏为黑色
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        
        // 初始化 Timber 日志
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.i("Soulon 启动 - Phase 2 + Phase 3 完整版")
        
        // 初始化 ActivityResultSender（必须在 onCreate 中，STARTED 之前）
        activityResultSender = com.solana.mobilewalletadapter.clientlib.ActivityResultSender(this)
        
        // 初始化 Phase 2 管理器
        keyManager = SeedVaultKeyManager(applicationContext)
        walletManager = com.soulon.app.wallet.WalletManager(applicationContext)
        // irysClient 现在在 StorageManager 内部初始化
        storageManager = StorageManager(applicationContext, keyManager, walletManager)
        
        // 初始化 Phase 3 管理器
        rewardsRepository = RewardsRepository(this)
        personalizedRAG = PersonalizedRAG(this)
        userLevelManager = com.soulon.app.rewards.UserLevelManager(this)
        irysSyncService = com.soulon.app.sync.IrysSyncService(this, storageManager)
        
        // 初始化远程配置管理器
        remoteConfigManager = com.soulon.app.config.RemoteConfigManager(applicationContext)
        
        // 🔑 确保用户档案在 UI 渲染之前就存在
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                rewardsRepository.getUserProfile()
                Timber.i("✅ 用户档案初始化完成")
            } catch (e: Exception) {
                Timber.e(e, "用户档案初始化失败")
            }
        }
        
        // 异步初始化 AI 服务和同步远程配置
        lifecycleScope.launch {
            try {
                // 🔄 首先同步远程配置（确保使用最新的后台配置）
                Timber.i("同步远程配置...")
                val syncResult = remoteConfigManager.syncFromBackend()
                if (syncResult.isSuccess) {
                    Timber.i("远程配置同步成功")
                } else {
                    Timber.w("远程配置同步失败，使用本地缓存")
                }
                
                // 初始化 AI 服务
                Timber.i("初始化 AI 服务...")
                personalizedRAG.initialize()
                Timber.i("AI 服务初始化完成")
            } catch (e: Exception) {
                Timber.e(e, "初始化失败")
            }
        }
        
        // 从通知 Intent 获取主动提问 ID
        val pendingQuestionId = intent?.getStringExtra(
            com.soulon.app.proactive.ProactiveQuestionNotificationManager.EXTRA_QUESTION_ID
        )
        val fromNotification = intent?.getBooleanExtra(
            com.soulon.app.proactive.ProactiveQuestionNotificationManager.EXTRA_FROM_NOTIFICATION,
            false
        ) ?: false
        
        setContent {
            MemoryAITheme {
                MemoryAIApp(
                        activity = this,
                    // Phase 2
                        keyManager = keyManager,
                    storageManager = storageManager,
                    walletManager = walletManager,
                    activityResultSender = activityResultSender,
                    // Phase 3
                    rewardsRepository = rewardsRepository,
                    personalizedRAG = personalizedRAG,
                    userLevelManager = userLevelManager,
                    irysSyncService = irysSyncService,
                    // 语言管理
                    localeManager = localeManager,
                    // 奇遇功能
                    pendingQuestionId = pendingQuestionId
                )
            }
        }
    }
    
    /**
     * 语言切换后重新创建 Activity
     */
    fun recreateForLanguageChange() {
        recreate()
    }

    fun recreateForWalletChange() {
        com.soulon.app.rewards.RewardsDatabase.clearInstance()
        recreate()
    }
}

/**
 * Soulon 应用主框架
 * 
 * 三个主界面：
 * 1. 仪表盘 (Phase 3) - $MEMO、Tier、人格雷达
 * 2. 记忆 (Phase 2) - 钱包、存储、检索
 * 3. AI 对话 (Phase 3) - 智能对话
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun MemoryAIApp(
    activity: MainActivity,
    // Phase 2
    keyManager: SeedVaultKeyManager,
    storageManager: StorageManager,
    walletManager: com.soulon.app.wallet.WalletManager,
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    // Phase 3
    rewardsRepository: RewardsRepository,
    personalizedRAG: PersonalizedRAG,
    userLevelManager: com.soulon.app.rewards.UserLevelManager,
    irysSyncService: com.soulon.app.sync.IrysSyncService,
    // 语言管理
    localeManager: com.soulon.app.i18n.LocaleManager,
    // 奇遇功能
    pendingQuestionId: String? = null
) {
    // 🆕 游戏仓库
    val voyageRepository = remember { com.soulon.app.game.VoyageRepository(activity) }

    // 🌍 首次启动语言选择
    var isLanguageSelected by remember { mutableStateOf(localeManager.isLanguageSelected()) }
    
    // 🔄 同步语言设置给 AppStrings（确保 UI 组件使用正确的语言）
    LaunchedEffect(Unit) {
        if (localeManager.isLanguageSelected()) {
            val code = localeManager.getSelectedLanguageCode()
            com.soulon.app.i18n.AppStrings.setLanguage(code)
            Timber.i("🌍 AppStrings 语言已同步: $code")
        }
    }
    
    // 如果用户还没选择语言，显示语言选择界面
    if (!isLanguageSelected) {
        com.soulon.app.i18n.WelcomeLanguageSelectionScreen(
            localeManager = localeManager,
            onLanguageSelected = {
                isLanguageSelected = true
                // 重新创建 Activity 以应用语言
                activity.recreateForLanguageChange()
            }
        )
        return
    }
    
    // 🔑 钱包连接状态（强制要求）
    var walletConnected by remember { mutableStateOf(false) }
    var walletAddress by remember { mutableStateOf<String?>(null) }
    var walletBalance by remember { mutableStateOf(0L) }
    var isWalletConnecting by remember { mutableStateOf(false) }
    var walletConnectionError by remember { mutableStateOf<String?>(null) }
    
    // 检查钱包连接状态
    LaunchedEffect(Unit) {
        if (walletManager.isConnected()) {
            val session = walletManager.getSession()
            if (session != null) {
                walletConnected = true
                val address = session.getPublicKeyBase58()
                walletAddress = address
                Timber.i("✅ 已恢复钱包连接: $walletAddress")

                val backendAuth = com.soulon.app.auth.BackendAuthManager.getInstance(activity)
                val backendSession = backendAuth.ensureSession(activityResultSender, walletManager)
                if (backendSession.isFailure) {
                    backendAuth.clear()
                    walletManager.disconnect()
                    walletConnected = false
                    walletAddress = null
                    walletConnectionError = backendSession.exceptionOrNull()?.message
                        ?: AppStrings.tr("后端认证失败，请重试", "Backend authentication failed. Please retry.")
                    Timber.w("恢复会话时后端认证失败: ${walletConnectionError}")
                    return@LaunchedEffect
                }
                
                try {
                    walletBalance = walletManager.getBalance()
                    Timber.i("💰 钱包余额: ${walletBalance / 1_000_000_000.0} SOL")
                } catch (e: Exception) {
                    Timber.e(e, "获取余额失败")
                }
                
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        rewardsRepository.initializeBackendFirst(address)
                        rewardsRepository.restoreMemoFromBackend(address)
                        rewardsRepository.restorePersonaFromBackend(address)
                        val restoredQuestionnaire = com.soulon.app.onboarding.OnboardingState.restoreQuestionnaireFromBackend(activity, address)
                        if (!restoredQuestionnaire) {
                            com.soulon.app.onboarding.OnboardingState.checkAndRestoreFromBackend(activity, address)
                        }
                        val cloudRepo = com.soulon.app.data.CloudDataRepository.getInstance(activity)
                        cloudRepo.initialize(address)
                        cloudRepo.syncFullProfile()
                        Timber.i("✅ 后端优先架构已初始化 (恢复会话)")
                    } catch (e: Exception) {
                        Timber.w("初始化后端优先架构失败: ${e.message}")
                    }
                }
            }
        }
    }

    LaunchedEffect(walletConnected, walletAddress) {
        if (walletConnected && !walletAddress.isNullOrBlank()) {
            val prefs = activity.getSharedPreferences("app_state", android.content.Context.MODE_PRIVATE)
            val scopeId = WalletScope.scopeId(walletAddress!!)
            val last = prefs.getString("active_wallet_scope_id", null)
            if (last != scopeId) {
                prefs.edit().putString("active_wallet_scope_id", scopeId).apply()
                activity.recreateForWalletChange()
            }
        }
    }
    
    // 🚨 强制钱包连接 - 未连接时显示引导页面
    if (!walletConnected) {
        com.soulon.app.ui.WalletOnboardingScreen(
            onConnect = {
                activity.lifecycleScope.launch {
                    isWalletConnecting = true
                    walletConnectionError = null
                    
                    try {
                        // 0. 清除旧会话密钥（确保每次连接都重新授权）
                        storageManager.revokeSessionKey()
                        
                        // 1. 准备会话密钥授权消息
                        val authMessage = storageManager.prepareSessionKeyAuthMessage()
                        Timber.i("📝 已准备会话密钥授权消息")
                        
                        // 2. 一站式连接钱包并签名授权（只需用户确认一次！）
                        val (session, signature) = walletManager.connectAndSign(
                            activityResultSender = activityResultSender,
                            authMessage = authMessage
                        )
                        val address = session.getPublicKeyBase58() ?: ""
                        
                        Timber.i("🎉 一站式连接成功: $address")
                        
                        // 3. 使用已签名的授权完成会话密钥初始化
                        storageManager.completeSessionKeyWithSignature(
                            mainWalletPublicKey = session.publicKey,
                            signature = signature
                        )
                        Timber.i("🔑 会话密钥初始化成功")
                        storageManager.setUseSessionKey(true)
                        com.soulon.app.sync.BackendMemoryMigrationWorker.schedulePeriodicWork(activity.applicationContext)
                        com.soulon.app.sync.BackendMemoryMigrationWorker.runOnce(activity.applicationContext)
                        
                        // 4. 获取余额
                        val balance = walletManager.getBalance()
                        
                        // 5. 从 Irys 同步本地索引（恢复该钱包的历史记忆索引）
                        // 重要：卸载应用后本地索引会丢失，需要从 Irys 恢复
                        Timber.i("📥 开始从 Irys 同步本地索引...")
                        val syncedCount = storageManager.syncWithIrys(address)
                        if (syncedCount > 0) {
                            Timber.i("✅ 已从 Irys 恢复 $syncedCount 条记忆索引")
                            Timber.i("   注意：加密内容需要硬件授权才能解密")
                        } else {
                            Timber.i("📭 没有需要同步的记忆")
                        }
                        
                        // 清空内存缓存，确保下次使用记忆时需要硬件解密
                        com.soulon.app.cache.MemoryCache.clear()
                        Timber.d("已清空内存缓存，确保需要硬件授权解密")
                        
                        // 6. 全部成功后更新状态
                        walletConnected = true
                        walletAddress = address
                        walletBalance = balance
                    } catch (e: Exception) {
                        // 任何步骤失败都断开钱包
                        walletManager.disconnect()
                        storageManager.revokeSessionKey()
                        
                        // 传递原始错误消息，由 UI 层解析为友好提示
                        walletConnectionError = e.message ?: "连接失败"
                        Timber.e(e, "钱包连接/授权失败")
                    } finally {
                        isWalletConnecting = false
                    }
                }
            },
            isConnecting = isWalletConnecting,
            errorMessage = walletConnectionError
        )
        return  // 未连接钱包时，不显示主应用
    }
    
    // ✅ 钱包已连接 - 显示主应用
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    
    // 📚 导航栈 - 用于返回上一页面
    val navigationStack = remember { mutableStateListOf<Screen>() }
    
    // 🔀 导航到新页面（将当前页面推入栈）
    val navigateTo: (Screen) -> Unit = { targetScreen ->
        if (targetScreen != currentScreen) {
            navigationStack.add(currentScreen)
            currentScreen = targetScreen
        }
    }
    
    // ⬅️ 返回上一页面（从栈中弹出）
    val navigateBack: () -> Unit = {
        if (navigationStack.isNotEmpty()) {
            currentScreen = navigationStack.removeLast()
        } else {
            // 如果栈为空，返回到 Dashboard
            currentScreen = Screen.Dashboard
        }
    }

    LaunchedEffect(Unit) {
        com.soulon.app.x402.PaymentRequiredBus.challenge.collect { pending ->
            val challenge = pending ?: return@collect
            com.soulon.app.x402.X402ChallengeStore.set(challenge)
            com.soulon.app.x402.PaymentRequiredBus.consume()
            navigateTo(Screen.PaymentEcosystem)
        }
    }
    
    // 🔄 定期从后端同步用户配置（每5分钟）
    // 🔄 定期同步用户配置（每5分钟拉取+推送）
    LaunchedEffect(walletAddress) {
        if (walletAddress != null) {
            while (true) {
                kotlinx.coroutines.delay(5 * 60 * 1000L) // 5分钟
                try {
                    // 从后端拉取配置
                    val synced = rewardsRepository.syncFromBackend(walletAddress!!)
                    if (synced) {
                        Timber.d("🔄 定期后端配置同步完成")
                    }
                    
                    // 推送本地积分到后端（确保不丢失）
                    val profile = rewardsRepository.getUserProfile()
                    rewardsRepository.syncMemoToBackend(
                        walletAddress!!,
                        profile.memoBalance,
                        profile.currentTier,
                        profile.totalMemoEarned
                    )
                    Timber.d("🔄 积分已同步到后端: ${profile.memoBalance}")
                } catch (e: Exception) {
                    Timber.w(e, "定期后端配置同步失败")
                }
            }
        }
    }

    // 🔄 钱包连接后立即尝试从 Irys 恢复人格数据（解锁雷达图）
    LaunchedEffect(walletAddress) {
        val wallet = walletAddress ?: return@LaunchedEffect
        run {
            try {
                val profile = rewardsRepository.getUserProfile()
                val hasLocalPersona = (profile.personaData?.sampleSize ?: 0) > 0 || (profile.personaProfileV2?.sampleCount ?: 0) > 0

                if (!hasLocalPersona) {
                    try {
                        val restored = rewardsRepository.restorePersonaFromBackend(wallet)
                        if (restored) {
                            Timber.i("✅ 人格画像已从后端恢复（启动时）")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "从后端恢复人格画像失败（将尝试 Irys）")
                    }
                }

                val refreshed = rewardsRepository.getUserProfile()
                val hasPersonaAfterBackend =
                    (refreshed.personaData?.sampleSize ?: 0) > 0 || (refreshed.personaProfileV2?.sampleCount ?: 0) > 0

                if (!hasPersonaAfterBackend) {
                    when (val restore = irysSyncService.restorePersonaDataFromIrys(wallet, activity)) {
                        is com.soulon.app.sync.IrysSyncService.RestoreResult.Success -> {
                            if (restore.personaRestored) {
                                Timber.i("✅ 人格数据已从 Irys 恢复（启动时）")
                            } else {
                                Timber.d("启动时人格数据未恢复: ${restore.note}")
                            }
                        }
                        is com.soulon.app.sync.IrysSyncService.RestoreResult.Error -> {
                            Timber.w("启动时人格数据恢复失败: ${restore.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "启动时人格数据恢复异常")
            }
        }
    }
    
    // 📤 应用进入后台时同步积分到后端
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentWalletAddress = walletAddress  // 捕获当前值避免 smart cast 问题
    DisposableEffect(lifecycleOwner, currentWalletAddress) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && currentWalletAddress != null) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val profile = rewardsRepository.getUserProfile()
                        rewardsRepository.syncMemoToBackend(
                            currentWalletAddress,
                            profile.memoBalance,
                            profile.currentTier,
                            profile.totalMemoEarned
                        )
                        Timber.i("📤 应用进入后台，积分已同步: ${profile.memoBalance}")
                    } catch (e: Exception) {
                        Timber.w("后台同步积分失败: ${e.message}")
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 📤 自动同步聊天数据到 Irys（每隔一小时检查）
    LaunchedEffect(walletAddress) {
        if (walletAddress != null && irysSyncService.needsSync()) {
            try {
                Timber.i("⏰ 开始定期数据同步...")
                
                // 同步聊天数据
                val chatResult = irysSyncService.syncChatDataToIrys(activityResultSender)
                when (chatResult) {
                    is com.soulon.app.sync.IrysSyncService.SyncResult.Success -> {
                        Timber.i("✅ 聊天数据同步完成: ${chatResult.sessionsUploaded} 会话, ${chatResult.messagesUploaded} 消息")
                    }
                    is com.soulon.app.sync.IrysSyncService.SyncResult.Error -> {
                        Timber.w("聊天数据同步失败: ${chatResult.message}")
                    }
                }
                
                // 同步用户档案
                val profile = rewardsRepository.getUserProfile()
                irysSyncService.uploadUserProfile(profile, activityResultSender)

                // 同步人格画像（独立类型，便于跨设备恢复）
                irysSyncService.uploadPersonaSnapshot(profile, activityResultSender)

                // 🔄 尝试从 Irys 恢复人格数据（用于跨设备/重装恢复）
                if (profile.personaData == null) {
                    when (val restore = irysSyncService.restorePersonaDataFromIrys(walletAddress!!, activity)) {
                        is com.soulon.app.sync.IrysSyncService.RestoreResult.Success -> {
                            if (restore.personaRestored) {
                                Timber.i("✅ 人格数据已从 Irys 恢复，雷达图将解锁")
                            } else {
                                Timber.d("人格数据未恢复: ${restore.note}")
                            }
                        }
                        is com.soulon.app.sync.IrysSyncService.RestoreResult.Error -> {
                            Timber.w("人格数据恢复失败: ${restore.message}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                Timber.e(e, "定期同步失败")
            }
        }
    }
    
    // 开屏 Logo 淡出动画状态
    var showSplashOverlay by remember { mutableStateOf(true) }
    
    // 启动淡出动画
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300) // 短暂延迟后开始淡出
        showSplashOverlay = false
    }
    
    // 淡出动画值
    val splashAlpha by animateFloatAsState(
        targetValue = if (showSplashOverlay) 1f else 0f,
        animationSpec = tween(
            durationMillis = 600,
            easing = FastOutSlowInEasing
        ),
        label = "splashAlpha"
    )
    
    // 全局返回手势处理 - 使用导航栈，与返回按钮逻辑一致
    BackHandler(enabled = currentScreen != Screen.Dashboard && 
                          currentScreen != Screen.Chat && 
                          currentScreen != Screen.Profile) {
        navigateBack()
    }
    
    // Phase 3 状态 - 使用 produceState 确保初始值正确加载
    val userProfile by produceState<com.soulon.app.rewards.UserProfile?>(initialValue = null) {
        // 首先同步获取当前档案（确保有初始值）
        value = rewardsRepository.getUserProfile()
        // 然后持续监听更新
        rewardsRepository.getUserProfileFlow().collect { profile ->
            if (profile != null) {
                value = profile
            }
        }
    }
    
    // 🔒 后端优先：观察后端余额状态流，实时更新 UI
    val backendBalanceState by rewardsRepository.getBalanceStateFlow().collectAsState()
    
    // 当后端余额状态变化时，强制刷新本地缓存
    LaunchedEffect(backendBalanceState) {
        when (val state = backendBalanceState) {
            is com.soulon.app.data.BalanceState.Success -> {
                val data = state.data
                Timber.d("🔄 后端余额更新: ${data.memoBalance} MEMO, Tier ${data.currentTier}")
            }
            is com.soulon.app.data.BalanceState.Error -> {
                Timber.w("后端余额获取失败: ${state.message}")
            }
            is com.soulon.app.data.BalanceState.Loading -> {
                // 加载中，不处理
            }
        }
    }
    
    // Chat Repository - 提升到顶层以保持状态
    val chatRepository = remember { com.soulon.app.chat.ChatRepository(activity) }
    
    // 当前会话 ID - 提升到顶层以保持状态（切换页面后不丢失）
    var currentChatSessionId by remember { mutableStateOf<String?>(null) }
    
    // Phase 2 状态
    var memories by remember { mutableStateOf<List<MemoryIndex>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var retrievedContent by remember { mutableStateOf<String?>(null) }
    
    // 刷新函数
    val refreshData: suspend () -> Unit = {
        isRefreshing = true
        try {
            // 如果钱包已连接，从网络同步记忆
            if (walletConnected && walletAddress != null) {
                try {
                    // 1. 从后端同步用户数据（订阅状态、积分等）
                    // 同步后 userProfile Flow 会自动更新
                    val synced = rewardsRepository.syncFromBackend(walletAddress!!)
                    if (synced) {
                        Timber.i("☁️ 刷新时从后端同步用户数据成功")
                    }
                    
                    // 2. 同步记忆
                    val syncedCount = storageManager.syncMemoriesFromNetwork(walletAddress!!)
                    if (syncedCount > 0) {
                        Timber.i("📥 刷新时同步了 $syncedCount 条记忆")
                    }
                    
                    // 3. 刷新钱包余额
                    walletBalance = walletManager.getBalance()
                } catch (e: Exception) {
                    Timber.e(e, "同步/刷新失败")
                }
            }
            // 加载 Phase 2 记忆（从本地索引）
            memories = storageManager.getAllMemories()
        } catch (e: Exception) {
            Timber.e(e, "刷新数据失败")
        } finally {
            isRefreshing = false
        }
    }
    
    // Irys 同步状态
    var isSyncingFromIrys by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    
    // 加载数据（钱包已连接）
    LaunchedEffect(Unit) {
        // 🔑 确保用户档案存在（首次安装时自动创建）
        try {
            rewardsRepository.getUserProfile()
            Timber.d("用户档案已加载或创建")
        } catch (e: Exception) {
            Timber.e(e, "用户档案初始化失败")
        }
        
        // 加载 Phase 2 记忆
        memories = try {
            storageManager.getAllMemories()
        } catch (e: Exception) {
            Timber.e(e, "加载记忆失败")
            emptyList()
        }
    }
    
    // 自动从 Irys 同步数据（页面进入时）
    LaunchedEffect(currentScreen, walletConnected, walletAddress) {
        // 只在首页且钱包已连接时触发同步
        if (currentScreen == Screen.Dashboard && walletConnected && walletAddress != null) {
            // 检查是否需要同步（使用简单的时间检查）
            val prefs = activity.getSharedPreferences("irys_sync", android.content.Context.MODE_PRIVATE)
            val lastSyncTime = prefs.getLong("last_sync_time", 0)
            val oneHour = 60 * 60 * 1000L
            
            if (System.currentTimeMillis() - lastSyncTime > oneHour) {
                try {
                    isSyncingFromIrys = true
                    Timber.i("🔄 自动从 Irys 同步数据...")
                    
                    val syncedCount = storageManager.syncMemoriesFromNetwork(walletAddress!!)
                    
                    if (syncedCount > 0) {
                        syncMessage = "已同步 $syncedCount 条记忆"
                        // 刷新本地数据
                        memories = storageManager.getAllMemories()
                    }
                    
                    // 更新最后同步时间
                    prefs.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
                    
                    Timber.i("✅ Irys 同步完成，共 $syncedCount 条")
                } catch (e: Exception) {
                    Timber.e(e, "Irys 同步失败")
                } finally {
                    isSyncingFromIrys = false
                    // 3秒后清除消息
                    kotlinx.coroutines.delay(3000)
                    syncMessage = null
                }
            }
        }
    }

    val translationWarmupState by com.soulon.app.i18n.TranslationWarmupManager.state.collectAsState()
    
    // 主容器 - 包含 Scaffold 和 Splash 覆盖层
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,  // 让 Scaffold 背景透明
            contentWindowInsets = WindowInsets(0, 0, 0, 0),  // 禁用 Scaffold 默认的 WindowInsets 处理
            bottomBar = {
            // 二级/三级屏幕不显示底部导航栏
            if (currentScreen !in listOf(
                Screen.TierDetails, 
                Screen.About, 
                Screen.QA, 
                Screen.Settings, 
                Screen.Evaluation, 
                Screen.LanguageSettings,
                Screen.Game,
                Screen.SeasonRewards,
                Screen.MyAssets,
                Screen.Memories,
                Screen.TierSystemOverview,
                Screen.MemberTierDashboard,
                Screen.UserLevelDashboard,
                Screen.StakingDashboard,
                Screen.Subscription,
                Screen.EcoStaking,
                Screen.Security,
                Screen.KYCVerification,
                Screen.DIDManagement,
                Screen.NotificationSettings,
                Screen.BugReport,
                Screen.ContactUs
            )) {
                // 毛玻璃效果 Tab Bar - 悬浮圆角设计 + 水滴滑动动画
                LiquidTabBar(
                    selectedIndex = when (currentScreen) {
                        Screen.Dashboard -> 0
                        Screen.Chat -> 1
                        Screen.Profile -> 2
                        else -> 0
                    },
                    onItemSelected = { index ->
                        currentScreen = when (index) {
                            0 -> Screen.Dashboard
                            1 -> Screen.Chat
                            2 -> Screen.Profile
                            else -> Screen.Dashboard
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            // 只在记忆界面显示 FAB
            if (currentScreen == Screen.Memories) {
                FloatingActionButton(
                    onClick = {
                        activity.lifecycleScope.launch {
                isLoading = true
                statusMessage = "正在存储记忆..."
                
                            try {
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())
                
                val testContent = "这是一条测试记忆，包含敏感信息。时间戳：$timestamp"
                
                val result = storageManager.storeMemory(
                    content = testContent,
                    metadata = mapOf(
                        "type" to "test",
                        "timestamp" to timestamp
                                    ),
                                    activityResultSender = activityResultSender  // ✅ 传递 ActivityResultSender
                                )
                                
                                if (result.success && result.memoryId != null) {
                                    // 🔐 不缓存明文，确保后续访问需要硬件解密
                                    // 仅生成向量用于语义搜索
                                    
                                    // 🔑 关键：生成并保存向量
                                    try {
                                        val vectorRepository = com.soulon.app.rag.VectorRepository(activity)
                                        val embeddingService = com.soulon.app.rag.EmbeddingService(activity)
                                        
                                        val embeddingResult = embeddingService.embed(testContent, "document")
                                        when (embeddingResult) {
                                            is com.soulon.app.rag.EmbeddingResult.Success -> {
                                                val vector = embeddingResult.vectors.firstOrNull()
                                                if (vector != null) {
                                                    vectorRepository.saveVector(
                                                        memoryId = result.memoryId,
                                                        vector = vector,
                                                        textLength = testContent.length
                                                    )
                                                    Timber.d("记忆已缓存并生成向量，可用于 AI 检索")
                                                }
                                            }
                                            is com.soulon.app.rag.EmbeddingResult.Error -> {
                                                Timber.w("向量生成失败: ${embeddingResult.message}")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Timber.e(e, "向量保存失败")
                                    }
                                    
                    statusMessage = com.soulon.app.i18n.AppStrings.trf(
                        "存储成功！\n记忆 ID: %s\nIrys Tx: %s\n已缓存并建立向量索引",
                        "Stored successfully!\nMemory ID: %s\nIrys Tx: %s\nCached and indexed",
                        result.memoryId,
                        result.cnftId
                    )
                                    memories = storageManager.getAllMemories()
                } else {
                    statusMessage = com.soulon.app.i18n.AppStrings.trf(
                        "存储失败: %s",
                        "Store failed: %s",
                        result.message
                    )
                }
            } catch (e: Exception) {
                statusMessage = com.soulon.app.i18n.AppStrings.trf(
                    "存储失败: %s",
                    "Store failed: %s",
                    e.message
                )
                Timber.e(e, "存储记忆失败")
            } finally {
                isLoading = false
            }
        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = com.soulon.app.i18n.AppStrings.tr("存储测试记忆", "Store test memory")
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                Screen.Dashboard -> {
                    val coroutineScope = rememberCoroutineScope()
                    var showShipNftDialog by remember { mutableStateOf(false) }
                    var shipNftChecking by remember { mutableStateOf(false) }
                    var shipNftMinting by remember { mutableStateOf(false) }
                    var shipHasNft by remember { mutableStateOf<Boolean?>(null) }
                    var shipNftError by remember { mutableStateOf<String?>(null) }
                    var shipMintStartAt by remember { mutableStateOf<Long?>(null) }
                    var shipMintEnabled by remember { mutableStateOf<Boolean?>(null) }
                    var shipQueueCount by remember { mutableStateOf<Long?>(null) }
                    var shipMintAutoShown by remember { mutableStateOf(false) }

                    fun openShipNftDialog() {
                        if (!walletConnected || walletAddress.isNullOrBlank()) {
                            statusMessage = AppStrings.tr("请先连接钱包", "Please connect your wallet first")
                            return
                        }
                        showShipNftDialog = true
                        shipNftChecking = true
                        shipHasNft = null
                        shipNftError = null
                        shipMintStartAt = null
                        shipMintEnabled = null
                        coroutineScope.launch {
                            try {
                                val status = voyageRepository.getShipEligibility(walletAddress!!)
                                shipHasNft = status?.hasNft
                                shipMintStartAt = status?.startAt
                                shipMintEnabled = status?.mintEnabled
                                shipQueueCount = status?.queueCount
                                shipNftError = if (status == null) AppStrings.tr("检测失败", "Check failed") else null
                            } finally {
                                shipNftChecking = false
                            }
                        }
                    }

                    LaunchedEffect(walletConnected, walletAddress) {
                        if (!shipMintAutoShown && walletConnected && !walletAddress.isNullOrBlank()) {
                            shipMintAutoShown = true
                            openShipNftDialog()
                        }
                    }

                    // Phase 3: 仪表盘（带下拉刷新 + 钱包卡片）
                    RefreshablePersonaDashboard(
                        activity = activity,
                        userProfile = userProfile,
                        userLevelManager = userLevelManager,
                        isRefreshing = isRefreshing,
                        onRefresh = { activity.lifecycleScope.launch { refreshData() } },
                        // 钱包状态
                        walletConnected = walletConnected,
                        walletAddress = walletAddress,
                        walletBalance = walletBalance,
                        // 管理器
                        walletManager = walletManager,
                        storageManager = storageManager,
                        activityResultSender = activityResultSender,
                        // 回调
                        onWalletUpdate = { connected, address, balance ->
                            walletConnected = connected
                            walletAddress = address
                            walletBalance = balance
                        },
                        onStatusUpdate = { statusMessage = it },
                        onLoadingUpdate = { isLoading = it },
                        onSessionKeyRevoke = { 
                            storageManager.revokeSessionKey()
                        },
                        onNavigateToChat = { navigateTo(Screen.Chat) },
                        onNavigateToSeasonRewards = { navigateTo(Screen.TierSystemOverview) },  // 赛季奖励按钮进入会员权益页面
                        onNavigateToMyAssets = { navigateTo(Screen.MyAssets) },
                        onNavigateToSeekerStatus = { navigateTo(Screen.TierSystemOverview) },
                        onNavigateToSubscribe = { navigateTo(Screen.Subscription) },
                        onNavigateToEcoStaking = { navigateTo(Screen.EcoStaking) },
                        onNavigateToCheckIn = { navigateTo(Screen.CheckIn) },
                        onNavigateToGame = { openShipNftDialog() }
                    )

                    if (showShipNftDialog) {
                        val appContext = LocalContext.current
                        val toastText = AppStrings.tr("将在开售前15分钟提醒你", "We'll remind you 15 minutes before sale")
                        val showCenteredTopToast = { message: String ->
                            val ctx = appContext.applicationContext
                            val density = ctx.resources.displayMetrics.density
                            val padH = (16f * density).toInt()
                            val padV = (10f * density).toInt()
                            val tv = android.widget.TextView(ctx).apply {
                                text = message
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 14f
                                setPadding(padH, padV, padH, padV)
                                gravity = android.view.Gravity.CENTER
                                val bg = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                    cornerRadius = 12f * density
                                    setColor(android.graphics.Color.argb(217, 0, 0, 0))
                                }
                                background = bg
                            }
                            android.widget.Toast(ctx).apply {
                                view = tv
                                duration = android.widget.Toast.LENGTH_SHORT
                                setGravity(android.view.Gravity.CENTER, 0, 0)
                                show()
                            }
                        }
                        val permissionPrefs = remember(appContext) {
                            appContext.getSharedPreferences("soulon_permissions", android.content.Context.MODE_PRIVATE)
                        }
                        val askedKey = "asked_post_notifications"
                        val openNotificationSettings = {
                            val intent = android.content.Intent().apply {
                                action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                                putExtra("app_package", appContext.packageName)
                                putExtra("app_uid", appContext.applicationInfo.uid)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                appContext.startActivity(intent)
                            } catch (_: Exception) {
                                val fallback = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${appContext.packageName}")
                                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                appContext.startActivity(fallback)
                            }
                        }
                        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                            onResult = { granted ->
                                permissionPrefs.edit().putBoolean(askedKey, true).apply()
                                if (granted) {
                                    val startAt = shipMintStartAt ?: 0L
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                        voyageRepository.subscribeShipMintNotify(startAt)
                                    }
                                    com.soulon.app.proactive.ShipSaleNotificationWorker.scheduleIfPossible(appContext, startAt)
                                    showCenteredTopToast(toastText)
                                } else {
                                    showCenteredTopToast(AppStrings.tr("请在系统设置开启通知权限", "Please enable notifications in Settings"))
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        val shouldShow = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                            activity,
                                            android.Manifest.permission.POST_NOTIFICATIONS
                                        )
                                        if (!shouldShow) openNotificationSettings()
                                    }
                                }
                            }
                        )
                        AlertDialog(
                            onDismissRequest = { if (!shipNftMinting) showShipNftDialog = false },
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = Color(0xFFFFD54F).copy(alpha = 0.9f),
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            AppStrings.tr("珍稀", "Rare"),
                                            color = Color(0xFF1A1A1A),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = { if (!shipNftMinting) showShipNftDialog = false },
                                        enabled = !shipNftMinting,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = AppStrings.tr("关闭", "Close"),
                                            tint = Color.White
                                        )
                                    }
                                }
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    AndroidView(
                                        factory = { ctx ->
                                            VideoView(ctx).apply {
                                                val uri = Uri.parse("android.resource://${ctx.packageName}/${R.raw.ship_basic_freighter}")
                                                setVideoURI(uri)
                                                setOnPreparedListener { mp ->
                                                    mp.isLooping = true
                                                    mp.setVolume(0f, 0f)
                                                    start()
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(280.dp)
                                    )

                                    Text(
                                        AppStrings.tr(
                                            "Seeker Spaceship",
                                            "Seeker Spaceship"
                                        ),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Text(
                                        AppStrings.tr(
                                            "限量领取NFT内测门票",
                                            "Limited beta pass NFT"
                                        ),
                                        color = Color.White.copy(alpha = 0.92f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Text(
                                        AppStrings.tr(
                                            "暂不可转赠",
                                            "Non-transferable for now"
                                        ),
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    val nowSec = System.currentTimeMillis() / 1000
                                    val mintDisabled = shipMintEnabled == false
                                    val mintNotStarted = shipMintStartAt != null && shipMintStartAt!! > 0 && nowSec < shipMintStartAt!!
                                    if (mintDisabled) {
                                        Text(
                                            AppStrings.tr("Mint 尚未开放", "Mint is not enabled"),
                                            color = Color.White.copy(alpha = 0.75f)
                                        )
                                    }
                                    if (mintNotStarted) {
                                        Text(
                                            AppStrings.tr("Mint 尚未开始", "Mint not started"),
                                            color = Color.White.copy(alpha = 0.75f)
                                        )
                                    }

                                    if (shipNftChecking) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.White.copy(alpha = 0.7f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                AppStrings.tr("正在检测持有情况…", "Checking ownership…"),
                                                color = Color.White.copy(alpha = 0.8f)
                                            )
                                        }
                                    }

                                    shipNftError?.let { err ->
                                        Text(
                                            AppStrings.trf("错误：%s", "Error: %s", err),
                                            color = Color(0xFFFF4444)
                                        )
                                    }

                                    val canEnter = shipHasNft == true && !shipNftChecking && !shipNftMinting
                                    val canMint = shipHasNft == false && !shipNftChecking && !shipNftMinting && !mintNotStarted && !mintDisabled
                                    val canRetry = shipHasNft == null && !shipNftChecking && !shipNftMinting
                                    val buttonText = when {
                                        canEnter -> AppStrings.tr("继续", "Continue")
                                        canMint -> AppStrings.tr("MINT", "MINT")
                                        mintDisabled -> AppStrings.tr("尚未开放", "Not enabled")
                                        mintNotStarted -> AppStrings.tr("尚未开始", "Not started")
                                        canRetry -> AppStrings.tr("重新检测", "Retry")
                                        else -> AppStrings.tr("请稍候", "Please wait")
                                    }
                                    val enabled = canEnter || canMint || canRetry
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = {
                                                when {
                                                    canEnter -> {
                                                        showShipNftDialog = false
                                                        navigateTo(Screen.GameLoading)
                                                    }
                                                    canMint -> {
                                                        shipNftMinting = true
                                                        shipNftError = null
                                                        coroutineScope.launch {
                                                            try {
                                                                val tx = voyageRepository.requestShipMintTx(walletAddress!!).getOrElse { throw it }
                                                                val bytes = android.util.Base64.decode(tx.transactionBase64, android.util.Base64.DEFAULT)
                                                                val sig = walletManager.signAndSendTransaction(bytes, activityResultSender)
                                                                val confirmed = voyageRepository.confirmShipMint(walletAddress!!, sig, tx.assetAddress).getOrElse { throw it }
                                                                if (confirmed.success && confirmed.hasNft) {
                                                                    shipHasNft = true
                                                                    showShipNftDialog = false
                                                                    navigateTo(Screen.GameLoading)
                                                                } else {
                                                                    shipNftError = confirmed.message ?: AppStrings.tr("Mint 失败", "Mint failed")
                                                                }
                                                            } catch (e: Exception) {
                                                                shipNftError = e.message ?: AppStrings.tr("Mint 失败", "Mint failed")
                                                            } finally {
                                                                shipNftMinting = false
                                                            }
                                                        }
                                                    }
                                                    canRetry -> {
                                                        shipNftChecking = true
                                                        shipNftError = null
                                                        coroutineScope.launch {
                                                            try {
                                                                val status = voyageRepository.getShipEligibility(walletAddress!!)
                                                                shipHasNft = status?.hasNft
                                                                shipMintStartAt = status?.startAt
                                                                shipMintEnabled = status?.mintEnabled
                                                                shipQueueCount = status?.queueCount
                                                                shipNftError = if (status == null) AppStrings.tr("检测失败", "Check failed") else null
                                                            } finally {
                                                                shipNftChecking = false
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = enabled,
                                            modifier = Modifier.widthIn(min = 160.dp, max = 210.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF9E9E9E).copy(alpha = 0.35f),
                                                contentColor = Color.White,
                                                disabledContainerColor = Color(0xFF9E9E9E).copy(alpha = 0.18f),
                                                disabledContentColor = Color.White.copy(alpha = 0.75f)
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Text(
                                                buttonText,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        val startAt = shipMintStartAt ?: 0L
                                        val now = System.currentTimeMillis() / 1000
                                        val showBell = startAt > 0 && now < startAt
                                        if (showBell) {
                                            IconButton(
                                                onClick = {
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                                            appContext,
                                                            android.Manifest.permission.POST_NOTIFICATIONS
                                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                        if (!granted) {
                                                            val asked = permissionPrefs.getBoolean(askedKey, false)
                                                            val shouldShow = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                                                activity,
                                                                android.Manifest.permission.POST_NOTIFICATIONS
                                                            )
                                                            if (!asked || shouldShow) {
                                                                permissionPrefs.edit().putBoolean(askedKey, true).apply()
                                                                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                                            } else {
                                                            showCenteredTopToast(AppStrings.tr("请在系统设置开启通知权限", "Please enable notifications in Settings"))
                                                                openNotificationSettings()
                                                            }
                                                            return@IconButton
                                                        }
                                                    }
                                                    coroutineScope.launch {
                                                        voyageRepository.subscribeShipMintNotify(startAt)
                                                    }
                                                    com.soulon.app.proactive.ShipSaleNotificationWorker.scheduleIfPossible(appContext, startAt)
                                                    showCenteredTopToast(toastText)
                                                },
                                                enabled = !shipNftMinting,
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .background(Color(0xFF9E9E9E).copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Notifications,
                                                    contentDescription = AppStrings.tr("发售提醒", "Sale alert"),
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                    }

                                    val showSaleInfo = shipMintStartAt != null && shipMintStartAt!! > 0 && nowSec < shipMintStartAt!!
                                    if (showSaleInfo) {
                                        val sdf = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
                                        sdf.timeZone = java.util.TimeZone.getTimeZone("America/New_York")
                                        val dt = sdf.format(java.util.Date(shipMintStartAt!! * 1000))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            AppStrings.trf("发售时间（美东）：%s", "Sale time (ET): %s", dt),
                                            color = Color.White.copy(alpha = 0.75f),
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        val q = shipQueueCount
                                        if (q != null) {
                                            Text(
                                                AppStrings.trf("排队人数：%d", "Queue: %d", q.toInt()),
                                                color = Color.White.copy(alpha = 0.75f),
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            containerColor = Color(0xFF16161D),
                            titleContentColor = Color.White,
                            textContentColor = Color.White
                        )
                    }
                }
                Screen.Memories -> {
                    // Phase 2: 记忆管理（带下拉刷新）
                    RefreshableMemoriesScreen(
                        activity = activity,
                        memories = memories,
                        isLoading = isLoading,
                        isRefreshing = isRefreshing,
                        onRefresh = { activity.lifecycleScope.launch { refreshData() } },
                        statusMessage = statusMessage,
                        retrievedContent = retrievedContent,
                        walletConnected = walletConnected,
                        walletAddress = walletAddress,
                        walletBalance = walletBalance,
                        storageManager = storageManager,
                        walletManager = walletManager,
                        activityResultSender = activityResultSender,
                        onMemoriesUpdate = { memories = it },
                        onStatusUpdate = { statusMessage = it },
                        onLoadingUpdate = { isLoading = it },
                        onRetrievedContentUpdate = { retrievedContent = it },
                        onWalletUpdate = { connected, address, balance ->
                            walletConnected = connected
                            walletAddress = address
                            walletBalance = balance
                        }
                    )
                }
                Screen.Chat -> {
                    // Phase 3: AI 对话（带初始化检查）
                    val resolvedWallet = walletAddress ?: WalletScope.currentWalletAddress(activity)
                    var isOnboardingComplete by remember(resolvedWallet) {
                        mutableStateOf(com.soulon.app.onboarding.OnboardingState.isCompleted(activity, resolvedWallet))
                    }

                    LaunchedEffect(resolvedWallet) {
                        isOnboardingComplete = com.soulon.app.onboarding.OnboardingState.isCompleted(activity, resolvedWallet)
                    }
                    
                    if (!isOnboardingComplete) {
                        // 首次使用：显示初始化问卷
                        OnboardingFlow(
                            activity = activity,
                            storageManager = storageManager,
                            personalizedRAG = personalizedRAG,
                            activityResultSender = activityResultSender,
                            walletAddress = walletAddress,
                            onComplete = {
                                isOnboardingComplete = true
                                
                                // 启动奇遇定期任务（通过通知推送奇遇，而不是立即显示）
                                com.soulon.app.proactive.ProactiveQuestionWorker.schedulePeriodicWork(activity)
                                Timber.i("✨ 奇遇定时任务已启动，将通过通知推送")
                            },
                            onNavigateToHome = { navigateTo(Screen.Dashboard) }
                        )
                    } else {
                        // 已完成初始化：显示带主动提问功能的对话界面
                        com.soulon.app.proactive.AIChatWithProactiveQuestions(
                            memoBalance = userProfile?.memoBalance ?: 0,
                            tierName = userProfile?.getTierName() ?: "Bronze",
                            tierMultiplier = userProfile?.getTierMultiplier() ?: 1.0f,
                            chatRepository = chatRepository,
                            externalSessionId = currentChatSessionId,
                            onSessionIdChange = { newSessionId ->
                                currentChatSessionId = newSessionId
                            },
                            walletAddress = walletAddress,
                            onSendMessage = { message, sessionId ->
                                // 🔐 第一阶段：检索记忆（不解密）
                                handleChatMessageWithEncryption(
                                    message = message,
                                    sessionId = sessionId,
                                    chatRepository = chatRepository,
                                    personalizedRAG = personalizedRAG,
                                    rewardsRepository = rewardsRepository,
                                    storageManager = storageManager,
                                    activity = activity,
                                    activityResultSender = activityResultSender,
                                    walletManager = walletManager,
                                    decrypt = false
                                )
                            },
                            onDecryptAndAnswer = { message, memoryIds, sessionId ->
                                // 🔐 第二阶段：解密记忆并回答
                                handleChatMessageWithDecryption(
                                    message = message,
                                    memoryIds = memoryIds,
                                    sessionId = sessionId,
                                    chatRepository = chatRepository,
                                    personalizedRAG = personalizedRAG,
                                    rewardsRepository = rewardsRepository,
                                    storageManager = storageManager,
                                    activity = activity,
                                    activityResultSender = activityResultSender,
                                    walletManager = walletManager
                                )
                            },
                            onNavigateToHome = { navigateTo(Screen.Dashboard) },
                            onNavigateToSubscribe = { navigateTo(Screen.Subscription) },
                            pendingQuestionId = pendingQuestionId,
                            onAnswerSubmitted = { questionId, answer ->
                                Timber.d("主动提问已回答: $questionId")
                            }
                        )
                    }
                }
                Screen.Profile -> {
                    // 我的页面
                    ProfileScreen(
                        onNavigateToLanguage = { navigateTo(Screen.LanguageSettings) },
                        onNavigateToNotification = { navigateTo(Screen.NotificationSettings) },
                        onNavigateToSecurity = { navigateTo(Screen.Security) },
                        onNavigateToQA = { navigateTo(Screen.QA) },
                        onNavigateToBugReport = { navigateTo(Screen.BugReport) },
                        onNavigateToContactUs = { navigateTo(Screen.ContactUs) },
                        onNavigateToAbout = { navigateTo(Screen.About) },
                        onNavigateToSubscriptionManage = { navigateTo(Screen.SubscriptionManage) },
                        onNavigateToPaymentEcosystem = { navigateTo(Screen.PaymentEcosystem) },
                        onNavigateToPaymentEcosystemDev = { navigateTo(Screen.PaymentEcosystemDev) },
                        currentLanguage = localeManager.getSelectedLanguage(),
                        walletAddress = walletAddress
                    )
                }
                Screen.PaymentEcosystem -> {
                    com.soulon.app.paymentecosystem.PaymentEcosystemScreen(
                        onBack = navigateBack,
                        activityResultSender = activityResultSender,
                    )
                }
                Screen.PaymentEcosystemDev -> {
                    com.soulon.app.paymentecosystem.PaymentEcosystemDevScreen(
                        onBack = navigateBack
                    )
                }
                Screen.SubscriptionManage -> {
                    // 订阅管理页面
                    SubscriptionManageScreen(
                        walletAddress = walletAddress,
                        onBack = navigateBack
                    )
                }
                Screen.About -> {
                    // 关于页面
                    AboutScreen(
                        onBack = navigateBack
                    )
                }
                Screen.QA -> {
                    // 常见问题页面
                    QAScreen(
                        onBack = navigateBack
                    )
                }
                Screen.Settings -> {
                    // 设置页面
                    SettingsScreen(
                        onBack = navigateBack,
                        onNavigateToEvaluation = { navigateTo(Screen.Evaluation) },
                        onNavigateToLanguage = { navigateTo(Screen.LanguageSettings) },
                        currentLanguage = localeManager.getSelectedLanguage().nativeName
                    )
                }
                Screen.Evaluation -> {
                    // 问卷评估页面
                    EvaluationScreen(
                        activity = activity,
                        onBack = navigateBack
                    )
                }
                Screen.TierDetails -> {
                    // Phase 3: 等级详情
                    TierDetailsScreen(
                        userProfile = userProfile,
                        userLevelManager = userLevelManager,
                        onBack = navigateBack
                    )
                }
                Screen.LanguageSettings -> {
                    // 语言设置页面
                    com.soulon.app.i18n.LanguageSettingsScreen(
                        localeManager = localeManager,
                        onBack = navigateBack,
                        onLanguageChanged = {
                            // 语言切换后重新创建 Activity
                            activity.recreateForLanguageChange()
                        }
                    )
                }
                Screen.SeasonRewards -> {
                    // 已废弃：重定向到会员权益页面
                    LaunchedEffect(Unit) {
                        navigateTo(Screen.TierSystemOverview)
                    }
                }
                Screen.MyAssets -> {
                    // 我的资产页面
                    MyAssetsScreen(
                        userProfile = userProfile,
                        walletConnected = walletConnected,
                        walletAddress = walletAddress,
                        onBack = navigateBack,
                        voyageRepository = voyageRepository,
                        onOpenAssetDetail = { a ->
                            navigateTo(Screen.AssetDetail(kind = a.kind, name = a.name, assetAddress = a.assetAddress, metadataUri = a.metadataUri))
                        }
                    )
                }
                is Screen.AssetDetail -> {
                    val s = currentScreen as Screen.AssetDetail
                    AssetDetailScreen(
                        kind = s.kind,
                        name = s.name,
                        assetAddress = s.assetAddress,
                        metadataUri = s.metadataUri,
                        onBack = navigateBack
                    )
                }
                Screen.NotificationSettings -> {
                    // 通知设置页面
                    NotificationSettingsScreen(
                        onBack = navigateBack
                    )
                }
                Screen.Security -> {
                    // 安全页面
                    SecurityScreen(
                        onBack = navigateBack,
                        onNavigateToKYC = { navigateTo(Screen.KYCVerification) },
                        onNavigateToDID = { navigateTo(Screen.DIDManagement) }
                    )
                }
                Screen.DIDManagement -> {
                    // DID 身份管理页面（订阅用户专属功能）
                    val didManager = remember { com.soulon.app.did.DIDManager(activity) }
                    val memoryMergeService = remember {
                        com.soulon.app.did.MemoryMergeService(
                            context = activity,
                            didManager = didManager,
                            storageManager = storageManager,
                            keyManager = keyManager,
                            rewardsRepository = rewardsRepository
                        )
                    }
                    
                    com.soulon.app.did.DIDManagementScreen(
                        didManager = didManager,
                        memoryMergeService = memoryMergeService,
                        currentWallet = walletManager.getWalletAddress(),
                        isSubscribed = userProfile?.isSubscribed == true,
                        onBack = navigateBack,
                        onNavigateToKYC = { navigateTo(Screen.KYCVerification) },
                        onNavigateToSubscription = { navigateTo(Screen.Subscription) }
                    )
                }
                Screen.BugReport -> {
                    // Bug 报告页面
                    BugReportScreen(
                        onBack = navigateBack
                    )
                }
                Screen.ContactUs -> {
                    // 联系我们页面
                    ContactUsScreen(
                        onBack = navigateBack
                    )
                }
                Screen.KYCVerification -> {
                    // KYC 认证页面
                    KYCVerificationScreen(
                        onBack = navigateBack
                    )
                }
                Screen.TierSystemOverview -> {
                    // 等级系统总览
                    com.soulon.app.ui.TierSystemOverview(
                        onNavigateBack = navigateBack,
                        onNavigateToMemberTier = { navigateTo(Screen.MemberTierDashboard) },
                        onNavigateToUserLevel = { navigateTo(Screen.UserLevelDashboard) },
                        onNavigateToSubscribe = { navigateTo(Screen.Subscription) },
                        onNavigateToStake = { navigateTo(Screen.StakingDashboard) }
                    )
                }
                Screen.MemberTierDashboard -> {
                    // 会员等级仪表盘
                    com.soulon.app.ui.MemberTierDashboard(
                        onNavigateBack = navigateBack,
                        onNavigateToMemoHistory = { navigateTo(Screen.MemoHistory) }
                    )
                }
                Screen.UserLevelDashboard -> {
                    // 用户级别仪表盘
                    com.soulon.app.ui.UserLevelDashboard(
                        onNavigateBack = navigateBack,
                        onNavigateToSubscribe = { navigateTo(Screen.Subscription) },
                        onNavigateToStake = { navigateTo(Screen.StakingDashboard) }
                    )
                }
                Screen.StakingDashboard -> {
                    // 项目质押仪表盘已移除，暂显示敬请期待
                    // 实际应该不会导航到这里，因为入口都已改为 Toast 提示
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(AppStrings.tr("Coming Soon", "Coming Soon"), color = Color.White)
                    }
                }
                Screen.Subscription -> {
                    // 会员订阅页面
                    SubscriptionScreen(
                        walletAddress = walletAddress,
                        activityResultSender = activityResultSender,
                        onNavigateBack = navigateBack,
                        onSubscriptionSuccess = { 
                            // 订阅成功后刷新数据
                            activity.lifecycleScope.launch { refreshData() }
                            navigateBack()
                        }
                    )
                }
                Screen.EcoStaking -> {
                    // 生态质押页面
                    EcoStakingScreen(
                        walletAddress = walletAddress,
                        activityResultSender = activityResultSender,
                        onNavigateBack = navigateBack
                    )
                }
                Screen.CheckIn -> {
                    // 每日签到页面
                    com.soulon.app.ui.CheckInScreen(
                        rewardsRepository = rewardsRepository,
                        walletAddress = walletAddress,
                        onBack = navigateBack,
                        onNavigateToHistory = { navigateTo(Screen.MemoHistory) }
                    )
                }
                Screen.MemoHistory -> {
                    // 积分历史记录页面
                    com.soulon.app.ui.MemoHistoryScreen(
                        rewardsRepository = rewardsRepository,
                        onBack = navigateBack
                    )
                }
                Screen.GameLoading -> {
                    GameLoadingScreen(
                        onBack = navigateBack
                    )
                }
                Screen.Game -> {
                    // 探索冒险游戏
                    val address = walletAddress ?: ""
                    var isEnsuringSession by remember(address) { mutableStateOf(true) }
                    var sessionError by remember(address) { mutableStateOf<String?>(null) }
                    var ensureAttempt by remember(address) { mutableStateOf(0) }

                    LaunchedEffect(address, ensureAttempt) {
                        isEnsuringSession = true
                        sessionError = null
                        if (address.isBlank()) {
                            sessionError = AppStrings.tr("钱包未连接", "Wallet not connected")
                            isEnsuringSession = false
                            return@LaunchedEffect
                        }
                        val backendAuth = com.soulon.app.auth.BackendAuthManager.getInstance(activity)
                        val result = backendAuth.ensureSession(activityResultSender, walletManager)
                        if (result.isFailure) {
                            backendAuth.clear()
                            sessionError = result.exceptionOrNull()?.message
                                ?: AppStrings.tr("后端认证失败，请重试", "Backend authentication failed. Please retry.")
                        }
                        isEnsuringSession = false
                    }

                    when {
                        isEnsuringSession -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                        color = AppColors.PrimaryGradientStart
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = AppStrings.tr("正在建立后端会话...", "Establishing backend session..."),
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                        !sessionError.isNullOrBlank() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = AppStrings.tr("连接失败", "Connection failed"),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = sessionError ?: "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(18.dp))
                                    Button(
                                        onClick = { ensureAttempt += 1 },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text(AppStrings.tr("重试登录", "Retry login"))
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedButton(
                                        onClick = navigateBack,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text(AppStrings.tr("返回", "Back"))
                                    }
                                }
                            }
                        }
                        else -> {
                            com.soulon.app.ui.game.VoyageScreen(
                                onNavigateBack = navigateBack,
                                walletAddress = address,
                                activityResultSender = activityResultSender,
                                walletManager = walletManager,
                                voyageRepository = voyageRepository
                            )
                        }
                    }
                }
            }
        }
        }
        
        if (translationWarmupState.isActive) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1A1A24)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.PrimaryGradientStart
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val pct = translationWarmupState.progressPercent
                        val txt = when (translationWarmupState.stage) {
                            com.soulon.app.i18n.TranslationWarmupManager.Stage.Checking ->
                                AppStrings.tr("正在检查组件...", "Checking components...")
                            com.soulon.app.i18n.TranslationWarmupManager.Stage.PreparingModel,
                            com.soulon.app.i18n.TranslationWarmupManager.Stage.PreparingBundle -> {
                                if (pct == null) {
                                    AppStrings.tr(
                                        "正在准备语言包，准备好后将自动生效。",
                                        "Preparing language pack. It will apply automatically."
                                    )
                                } else {
                                    AppStrings.trf(
                                        "正在准备语言包 (%d%%)，准备好后将自动生效。",
                                        "Preparing language pack (%d%%). It will apply automatically.",
                                        pct
                                    )
                                }
                            }
                            else -> AppStrings.tr("正在准备语言包...", "Preparing language pack...")
                        }
                        Text(
                            text = txt,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // ========== 开屏 Logo 淡出覆盖层 ==========
        if (splashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0A0F))
                    .graphicsLayer { alpha = splashAlpha },
                contentAlignment = Alignment.Center
            ) {
                // Logo 图标
                Image(
                    painter = painterResource(id = R.drawable.ic_splash_logo),
                    contentDescription = AppStrings.tr("Soulon Logo", "Soulon Logo"),
                    modifier = Modifier.size(120.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

/**
 * Phase 3: 仪表盘（带下拉刷新 + 钱包卡片）
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun RefreshablePersonaDashboard(
    activity: MainActivity,
    userProfile: com.soulon.app.rewards.UserProfile?,
    userLevelManager: com.soulon.app.rewards.UserLevelManager,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    // 钱包状态
    walletConnected: Boolean,
    walletAddress: String?,
    walletBalance: Long,
    // 管理器
    walletManager: com.soulon.app.wallet.WalletManager,
    storageManager: StorageManager,  // 🔑 用于会话密钥管理
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    // 回调
    onWalletUpdate: (Boolean, String?, Long) -> Unit,
    onStatusUpdate: (String) -> Unit,
    onLoadingUpdate: (Boolean) -> Unit,
    onSessionKeyRevoke: () -> Unit,  // 🔑 撤销会话密钥回调
    onNavigateToChat: () -> Unit,
    onNavigateToSeasonRewards: () -> Unit,
    onNavigateToMyAssets: () -> Unit,
    onNavigateToSeekerStatus: () -> Unit = {},
    onNavigateToSubscribe: () -> Unit = {},
    onNavigateToEcoStaking: () -> Unit = {},
    onNavigateToCheckIn: () -> Unit = {},
    onNavigateToGame: () -> Unit = {}
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = onRefresh
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState)
    ) {
        // 构建钱包连接回调（同时初始化会话密钥，授权失败则连接失败）
        val onWalletConnect: () -> Unit = {
            activity.lifecycleScope.launch {
                if (isRefreshing) return@launch
                
                onLoadingUpdate(true)
                onStatusUpdate("正在连接钱包...")
                
                try {
                    // 0. 清除旧会话密钥（确保每次连接都重新授权）
                    onSessionKeyRevoke()
                    
                    // 1. 连接钱包
                    val session = walletManager.connect(activityResultSender)
                    val address = session.getPublicKeyBase58() ?: ""
                    val balance = walletManager.getBalance()
                    
                    Timber.i("钱包连接成功: $address")
                    
                    // 2. 必须初始化会话密钥（授权失败则连接失败）
                    onStatusUpdate("正在授权会话密钥...")
                    storageManager.initializeSessionKey(activityResultSender)
                    Timber.i("🔑 会话密钥初始化成功")
                    
                    // 3. 全部成功后更新状态
                    onWalletUpdate(true, address, balance)
                    onStatusUpdate("钱包连接成功！")
                } catch (e: Exception) {
                    // 任何步骤失败都断开钱包
                    walletManager.disconnect()
                    onSessionKeyRevoke()
                    
                    val errorMsg = when {
                        e.message?.contains("User declined") == true ||
                        e.message?.contains("cancel") == true -> "用户取消了授权"
                        e.message?.contains("No wallet") == true -> "未找到钱包应用"
                        else -> "连接失败: ${e.message}"
                    }
                    onStatusUpdate(errorMsg)
                    Timber.e(e, "钱包连接/授权失败")
                } finally {
                    onLoadingUpdate(false)
                }
            }
        }
        
        val onWalletDisconnect: () -> Unit = {
            walletManager.disconnect()
            // 🔑 撤销会话密钥（钱包断开时自动撤销）
            onSessionKeyRevoke()
            onWalletUpdate(false, null, 0)
            onStatusUpdate("钱包已断开")
        }
        
        // 使用自定义的 PersonaDashboard，传入钱包信息
        PersonaDashboardWithWallet(
            activity = activity,
            userProfile = userProfile,
            userLevelManager = userLevelManager,
            walletConnected = walletConnected,
            walletAddress = walletAddress,
            walletBalance = walletBalance,
            onWalletConnect = onWalletConnect,
            onWalletDisconnect = onWalletDisconnect,
            onNavigateToChat = onNavigateToChat,
            onNavigateToSeasonRewards = onNavigateToSeasonRewards,
            onNavigateToMyAssets = onNavigateToMyAssets,
            onNavigateToSeekerStatus = onNavigateToSeekerStatus,
            onNavigateToSubscribe = onNavigateToSubscribe,
            onNavigateToEcoStaking = onNavigateToEcoStaking,
            onNavigateToCheckIn = onNavigateToCheckIn,
            onNavigateToGame = onNavigateToGame
        )
        
        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * Phase 3: 仪表盘（包含钱包卡片）
 */
@Composable
fun PersonaDashboardWithWallet(
    activity: MainActivity,
    userProfile: com.soulon.app.rewards.UserProfile?,
    userLevelManager: com.soulon.app.rewards.UserLevelManager,
    walletConnected: Boolean,
    walletAddress: String?,
    walletBalance: Long,
    onWalletConnect: () -> Unit,
    onWalletDisconnect: () -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToSeasonRewards: () -> Unit,
    onNavigateToMyAssets: () -> Unit,
    onNavigateToSeekerStatus: () -> Unit = {},
    onNavigateToSubscribe: () -> Unit = {},
    onNavigateToEcoStaking: () -> Unit = {},
    onNavigateToCheckIn: () -> Unit = {},
    onNavigateToGame: () -> Unit = {}
) {
    if (userProfile == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }
    
    // 订阅状态
    val isSubscribed = userProfile.subscriptionType != "FREE"
    
    // 获取 Tier 进度
    var tierProgress by remember { mutableStateOf<com.soulon.app.rewards.TierProgress?>(null) }
    
    // 强制刷新人格数据的状态
    var forceRefreshedPersonaData by remember { mutableStateOf<com.soulon.app.rewards.PersonaData?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var personaRecomputeAttempted by remember { mutableStateOf(false) }
    var onboardingAnswersRestoreAttempted by remember { mutableStateOf(false) }
    var showRedoOnboardingDialog by remember { mutableStateOf(false) }
    var showRetryPersonaDialog by remember { mutableStateOf(false) }
    var retryPersonaDialogMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    
    // 每次组件显示或 userProfile 变化时，检查并刷新人格数据
    LaunchedEffect(userProfile, refreshTrigger) {
        // 检查是否完成了问卷（每次都重新读取，确保获取最新状态）
        val isOnboardingComplete = com.soulon.app.onboarding.OnboardingState.isCompleted(activity, walletAddress)
        timber.log.Timber.d("🔍 检查人格数据状态:")
        timber.log.Timber.d("  - isOnboardingComplete: $isOnboardingComplete")
        timber.log.Timber.d("  - userProfile.personaData: ${userProfile.personaData}")
        timber.log.Timber.d("  - userProfile.personaProfileV2: ${userProfile.personaProfileV2 != null}")
        timber.log.Timber.d("  - forceRefreshedPersonaData: $forceRefreshedPersonaData")

        if (!isOnboardingComplete && !walletAddress.isNullOrBlank()) {
            val restored = com.soulon.app.onboarding.OnboardingState.restoreQuestionnaireFromBackend(activity, walletAddress)
            val restoredCompleted = if (!restored) {
                com.soulon.app.onboarding.OnboardingState.checkAndRestoreFromBackend(activity, walletAddress)
            } else {
                true
            }
            if (restoredCompleted) {
                refreshTrigger++
                return@LaunchedEffect
            }
        }
        
        val dbProfile = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.soulon.app.rewards.RewardsDatabase.getInstance(activity)
                .rewardsDao()
                .getUserProfile("default_user")
        }
        val dbPersona = dbProfile?.personaData ?: dbProfile?.personaProfileV2?.toLegacyPersonaData()
        if (dbPersona != null && dbPersona.sampleSize > 0 && forceRefreshedPersonaData == null) {
            forceRefreshedPersonaData = dbPersona
        }
        val localPersonaSampleCount = listOf(
            userProfile.personaData?.sampleSize ?: 0,
            userProfile.personaProfileV2?.sampleCount ?: 0,
            dbProfile?.personaData?.sampleSize ?: 0,
            dbProfile?.personaProfileV2?.sampleCount ?: 0
        ).maxOrNull() ?: 0

        if (isOnboardingComplete && localPersonaSampleCount <= 0 && forceRefreshedPersonaData == null) {
            val remoteConfig = com.soulon.app.config.RemoteConfigManager.getInstance(activity)
            val allowRecompute = remoteConfig.getBoolean("persona.recompute.onboarding.enabled", true)
            val requireRedo = remoteConfig.getBoolean("persona.onboarding.redo_if_persona_missing", true)
            val maxRetries = remoteConfig.getInt("persona.persona_missing.max_retries", 3)
            var evaluations = com.soulon.app.onboarding.OnboardingEvaluationStorage(activity).getAllEvaluations()
            var hasOnboardingAnswers = evaluations.isNotEmpty()

            if (requireRedo && !hasOnboardingAnswers && !onboardingAnswersRestoreAttempted && !walletAddress.isNullOrBlank()) {
                onboardingAnswersRestoreAttempted = true
                val restored = com.soulon.app.onboarding.OnboardingState.restoreQuestionnaireFromBackend(activity, walletAddress)
                if (restored) {
                    refreshTrigger++
                    return@LaunchedEffect
                }
                evaluations = com.soulon.app.onboarding.OnboardingEvaluationStorage(activity).getAllEvaluations()
                hasOnboardingAnswers = evaluations.isNotEmpty()
            }

            if (requireRedo && !hasOnboardingAnswers) {
                showRedoOnboardingDialog = true
                return@LaunchedEffect
            }

            if (!personaRecomputeAttempted && allowRecompute) {
                personaRecomputeAttempted = true
                try {
                    if (hasOnboardingAnswers) {
                        val questions = com.soulon.app.onboarding.OnboardingQuestions.getAllQuestions()
                        val texts = evaluations.mapNotNull { eval ->
                            val q = questions.find { it.id == eval.questionId }?.question
                            if (q.isNullOrBlank()) null else "问题：$q\n回答：${eval.originalAnswer}"
                        }
                        if (texts.isNotEmpty()) {
                            timber.log.Timber.i("🧠 本地人格数据缺失，基于问卷答案重建人格画像: inputs=${texts.size}")
                            val qwen = com.soulon.app.ai.QwenCloudManager(activity)
                            qwen.initialize()
                            val extractor = com.soulon.app.persona.PersonaExtractor(activity, qwen)
                            when (val result = extractor.extractPersona(texts)) {
                                is com.soulon.app.persona.PersonaExtractionResult.Success -> {
                                    timber.log.Timber.i("🧠 重建人格画像成功: $result")
                                }
                                is com.soulon.app.persona.PersonaExtractionResult.Error -> {
                                    retryPersonaDialogMessage = "人格画像生成失败：${result.message}"
                                    showRetryPersonaDialog = true
                                    return@LaunchedEffect
                                }
                            }
                        } else {
                            retryPersonaDialogMessage = "问卷答案文本为空，无法生成人格画像"
                            showRetryPersonaDialog = true
                            return@LaunchedEffect
                        }
                    } else {
                        showRedoOnboardingDialog = true
                        return@LaunchedEffect
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryPersonaDialogMessage = "网络异常或服务不可用，暂时无法生成人格画像"
                    showRetryPersonaDialog = true
                    timber.log.Timber.w(e, "⚠️ 基于问卷答案重建人格画像失败")
                    return@LaunchedEffect
                } finally {
                    refreshTrigger++
                }
                return@LaunchedEffect
            }

            // 问卷已完成但 Flow 还没更新，直接从数据库查询
            timber.log.Timber.d("问卷已完成但人格数据为空，从数据库强制查询...")
            
            val database = com.soulon.app.rewards.RewardsDatabase.getInstance(activity)
            val freshProfile = database.rewardsDao().getUserProfile("default_user")
            
            timber.log.Timber.d("数据库查询结果:")
            timber.log.Timber.d("  - freshProfile: $freshProfile")
            timber.log.Timber.d("  - personaData: ${freshProfile?.personaData}")
            timber.log.Timber.d("  - sampleSize: ${freshProfile?.personaData?.sampleSize}")
            
            val derivedFromV2 = freshProfile?.personaProfileV2?.toLegacyPersonaData()
            val effective = freshProfile?.personaData ?: derivedFromV2

            effective?.let { data ->
                if (data.sampleSize > 0) {
                    forceRefreshedPersonaData = data
                    timber.log.Timber.d("✅ 强制刷新成功! sampleSize=${data.sampleSize}")
                } else {
                    timber.log.Timber.d("⚠️ 人格数据 sampleSize 为 0")
                }
            } ?: run {
                if (refreshTrigger >= maxRetries && requireRedo) {
                    retryPersonaDialogMessage = com.soulon.app.i18n.AppStrings.tr(
                        "人格画像仍为空：可能是网络异常导致生成失败。你可以稍后重试，或重新填写问卷。",
                        "Persona is still empty. This may be due to a network error. Please retry later or redo the questionnaire."
                    )
                    showRetryPersonaDialog = true
                } else {
                    timber.log.Timber.d("⚠️ 数据库中没有人格数据，500ms 后重试")
                    kotlinx.coroutines.delay(500)
                    refreshTrigger++
                }
            }
        }
    }
    
    // 使用强制刷新的数据或原始数据
    val effectivePersonaData = userProfile.personaData
        ?: userProfile.personaProfileV2?.toLegacyPersonaData()
        ?: forceRefreshedPersonaData

    val personaNowValid = (effectivePersonaData?.sampleSize ?: 0) > 0
    LaunchedEffect(personaNowValid) {
        if (personaNowValid) {
            showRedoOnboardingDialog = false
            showRetryPersonaDialog = false
            retryPersonaDialogMessage = ""
        }
    }

    if (showRedoOnboardingDialog) {
        AlertDialog(
            onDismissRequest = { showRedoOnboardingDialog = false },
            title = { Text(com.soulon.app.i18n.AppStrings.tr("人格画像未生成", "Persona not generated")) },
            text = {
                Text(
                    com.soulon.app.i18n.AppStrings.tr(
                        "检测到问卷已完成但人格画像为空，且本地问卷数据缺失或已损坏。需要重新填写问卷以生成有效的人格画像。",
                        "The questionnaire is completed, but persona data is empty and local questionnaire data is missing or corrupted. Please redo the questionnaire to generate a valid persona profile."
                    )
                )
            },
            containerColor = Color(0xFF0A0A0F),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.85f),
            confirmButton = {
                TextButton(
                    onClick = {
                        showRedoOnboardingDialog = false
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                com.soulon.app.onboarding.OnboardingEvaluationStorage(activity).clearAll()
                                com.soulon.app.onboarding.OnboardingState.reset(activity)
                                val db = com.soulon.app.rewards.RewardsDatabase.getInstance(activity)
                                val dao = db.rewardsDao()
                                val current = dao.getUserProfile("default_user")
                                if (current != null) {
                                    dao.updateUserProfile(
                                        current.copy(
                                            personaData = null,
                                            personaProfileV2 = null,
                                            personaSyncRate = null,
                                            lastPersonaAnalysis = null
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                timber.log.Timber.w(e, "重置问卷与人格数据失败")
                            }
                        }
                        onNavigateToChat()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                ) {
                    Text(com.soulon.app.i18n.AppStrings.tr("重新填写问卷", "Redo questionnaire"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRedoOnboardingDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.85f))
                ) {
                    Text(com.soulon.app.i18n.AppStrings.tr("稍后再说", "Not now"))
                }
            }
        )
    }

    if (showRetryPersonaDialog) {
        AlertDialog(
            onDismissRequest = { showRetryPersonaDialog = false },
            title = { Text(com.soulon.app.i18n.AppStrings.tr("人格画像生成失败", "Persona generation failed")) },
            text = { Text(retryPersonaDialogMessage) },
            containerColor = Color(0xFF0A0A0F),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.85f),
            confirmButton = {
                TextButton(
                    onClick = {
                        showRetryPersonaDialog = false
                        personaRecomputeAttempted = false
                        forceRefreshedPersonaData = null
                        refreshTrigger++
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                ) {
                    Text(com.soulon.app.i18n.AppStrings.tr("重试生成", "Retry"))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showRetryPersonaDialog = false
                            showRedoOnboardingDialog = true
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.85f))
                    ) {
                        Text(com.soulon.app.i18n.AppStrings.tr("重新填写问卷", "Redo questionnaire"))
                    }
                    TextButton(
                        onClick = { showRetryPersonaDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.85f))
                    ) {
                        Text(com.soulon.app.i18n.AppStrings.tr("稍后再说", "Not now"))
                    }
                }
            }
        )
    }
    
    // 添加日志，便于调试
    timber.log.Timber.d("📊 effectivePersonaData: $effectivePersonaData, sampleSize: ${effectivePersonaData?.sampleSize}")
    
    LaunchedEffect(userProfile.currentTier, userProfile.totalMemoEarned) {
        tierProgress = userLevelManager.getTierProgress()
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 左右布局：Seeker S2 状态卡片(2/3) + 钱包卡片(1/3)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // 左侧：会员订阅卡片（2/3）
                SeekerS2CompactCard(
                    activity = activity,
                    walletConnected = walletConnected,
                    walletAddress = walletAddress,
                    onNavigateToDetails = onNavigateToSeekerStatus,
                    onNavigateToSubscribe = onNavigateToSubscribe,
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight()
                )
                
                // 右侧：钱包连接卡片（1/3）
                WalletCard(
                    walletConnected = walletConnected,
                    walletAddress = walletAddress,
                    walletBalance = walletBalance,
                    onConnect = onWalletConnect,
                    onDisconnect = onWalletDisconnect,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
        
        // 3. 快速操作按钮（包含生态质押）
        item {
            QuickActionButtons(
                onSeasonRewards = onNavigateToSeasonRewards,
                onMyAssets = onNavigateToMyAssets,
                onEcoStaking = onNavigateToEcoStaking,
                onCheckIn = onNavigateToCheckIn,
                isSubscribed = isSubscribed,
                onShowSubscribeDialog = onNavigateToSubscribe,
                onNavigateToGame = onNavigateToGame
            )
        }
        
        // 4. 人格分析卡片（始终显示，支持触摸交互）
        item {
            // 检查初始化状态
            val isOnboardingComplete = com.soulon.app.onboarding.OnboardingState.isCompleted(activity, walletAddress)
            
            // 使用 effectivePersonaData（已在上面处理了强制刷新）
            val hasValidData = effectivePersonaData != null && effectivePersonaData.sampleSize > 0
            
            // 使用默认数据或用户数据
            val personaData = effectivePersonaData ?: com.soulon.app.rewards.PersonaData(
                openness = 0.5f,
                conscientiousness = 0.5f,
                extraversion = 0.5f,
                agreeableness = 0.5f,
                neuroticism = 0.5f,
                sampleSize = 0
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .modernCardShadow(AppElevations.Medium, AppShapes.LargeCard),
                shape = AppShapes.LargeCard,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF1A1A2E),
                                    Color(0xFF16213E)
                                )
                            )
                        )
                        .padding(AppSpacing.Large)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 标题行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.tr("我的数字孪生", "My Digital Twin"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (hasValidData)
                                        com.soulon.app.i18n.AppStrings.tr("触摸查看各维度", "Tap to explore dimensions")
                                    else
                                        com.soulon.app.i18n.AppStrings.tr("完成初始化后解锁", "Unlock after setup"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                            // 样本数
                            if (hasValidData) {
                                Surface(
                                    shape = RoundedCornerShape(AppCorners.Full),
                                    color = AppColors.PrimaryGradientStart.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = com.soulon.app.i18n.AppStrings.trf(
                                            "%d 样本",
                                            "%d samples",
                                            personaData.sampleSize
                                        ),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(AppSpacing.Small))
                        
                        // 雷达图区域（带模糊遮罩）
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            // 雷达图（放大尺寸）
                            com.soulon.app.persona.InteractivePersonaRadarChart(
                                personaData = personaData,
                                accentColor = AppColors.PrimaryGradientStart,
                                modifier = Modifier.fillMaxWidth(),
                                chartSize = 320
                            )
                            
                            // 如果未完成初始化或没有有效人格数据，显示模糊遮罩
                            if (!isOnboardingComplete || !hasValidData) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.radialGradient(
                                                colors = listOf(
                                                    Color(0xFF1A1A2E).copy(alpha = 0.85f),
                                                    Color(0xFF16213E).copy(alpha = 0.95f)
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(AppSpacing.XLarge)
                                    ) {
                                        // 锁定图标
                                        Surface(
                                            modifier = Modifier.size(64.dp),
                                            shape = RoundedCornerShape(32.dp),
                                            color = AppColors.PrimaryGradientStart.copy(alpha = 0.15f)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Lock,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(32.dp),
                                                    tint = AppColors.PrimaryGradientStart
                                                )
                                            }
                                        }
                                        
                                        Spacer(modifier = Modifier.height(AppSpacing.Medium))
                                        
                                        Text(
                                            text = com.soulon.app.i18n.AppStrings.tr("人格分析已锁定", "Persona analysis locked"),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        
                                        Spacer(modifier = Modifier.height(AppSpacing.Small))
                                        
                                        Text(
                                            text = if (!isOnboardingComplete) 
                                                com.soulon.app.i18n.AppStrings.tr(
                                                    "请先完成 AI 助手初始化问卷\n才能查看您的人格特征分析",
                                                    "Complete the AI setup questionnaire first\nto view your persona analysis"
                                                )
                                            else 
                                                com.soulon.app.i18n.AppStrings.tr(
                                                    "与 AI 助手对话后\n将生成您的专属人格画像",
                                                    "Chat with the AI\nand your persona profile will be generated"
                                                ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White.copy(alpha = 0.7f),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            lineHeight = 22.sp
                                        )
                                        
                                        Spacer(modifier = Modifier.height(AppSpacing.Large))
                                        
                                        // 引导按钮
                                        Surface(
                                            modifier = Modifier.clickable { 
                                                onNavigateToChat()
                                            },
                                            shape = RoundedCornerShape(AppCorners.Full),
                                            color = AppColors.PrimaryGradientStart
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (!isOnboardingComplete) 
                                                        Icons.Rounded.PlayArrow 
                                                    else 
                                                        Icons.Rounded.Forum,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = Color.White
                                                )
                                                Text(
                                                    text = if (!isOnboardingComplete)
                                                        com.soulon.app.i18n.AppStrings.tr("开始初始化", "Start setup")
                                                    else
                                                        com.soulon.app.i18n.AppStrings.tr("开始对话", "Start chat"),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
    }
}

/**
 * 钱包 + $MEMO 积分合并卡片
 * - 未连接钱包：显示连接钱包按钮和说明
 * - 已连接钱包：显示钱包信息 + $MEMO 积分和等级 + 进度条
 * - 可点击查看等级详情
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletMemoCard(
    walletConnected: Boolean,
    walletAddress: String?,
    walletBalance: Long,
    userProfile: com.soulon.app.rewards.UserProfile?,
    tierProgress: com.soulon.app.rewards.TierProgress?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClick: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .modernCardShadow(AppElevations.Large, AppShapes.LargeCard)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) 
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (walletConnected) 
                AppColors.PrimaryGradientStart.copy(alpha = 0.08f)
            else 
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = AppShapes.LargeCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.XLarge)
        ) {
            // 钱包连接区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    // 钱包图标容器
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(AppCorners.Medium),
                        color = if (walletConnected) 
                            AppColors.PrimaryGradientStart.copy(alpha = 0.15f)
                        else 
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.AccountBalanceWallet,
                                contentDescription = null,
                                modifier = Modifier.size(AppIconSizes.Large),
                                tint = if (walletConnected) 
                                    AppColors.PrimaryGradientStart 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Column {
                        Text(
                            text = if (walletConnected)
                                com.soulon.app.i18n.AppStrings.tr("钱包已连接", "Wallet connected")
                            else
                                com.soulon.app.i18n.AppStrings.tr("连接钱包", "Connect wallet"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (walletConnected && walletAddress != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${walletAddress.take(6)}...${walletAddress.takeLast(6)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (!walletConnected) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("连接后查看 \$MEMO 积分", "Connect to view \$MEMO points"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // 连接/断开按钮
                if (walletConnected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = AppShapes.Button,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Text(
                            com.soulon.app.i18n.AppStrings.tr("断开", "Disconnect"),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        shape = AppShapes.Button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.PrimaryGradientStart
                        )
                    ) {
                        Text(
                            com.soulon.app.i18n.AppStrings.tr("连接钱包", "Connect wallet"),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            
            // 已连接：显示钱包余额和积分信息
            if (walletConnected && userProfile != null) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(16.dp))
                
                // SOL 余额
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("SOL 余额", "SOL balance"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${walletBalance / 1_000_000_000.0} SOL",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(16.dp))
                
                // Tier 等级信息
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "🏆 ${userProfile.getTierName()}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = AppStrings.trf(
                                "等级 %d",
                                "Tier %d",
                                userProfile.currentTier
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        
                        // 显示 Seeker 专属礼物提示（仅当未订阅且未兑换过时显示）
                        if (!userProfile.isSubscribed && !userProfile.genesisTokenRedeemed) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr(
                                    "为 seeker 用户准备了一份专属礼物",
                                    "A special gift is prepared for Seeker users"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.SuccessGradientStart
                            )
                        }
                    }
                    
                    Text(
                        text = userProfile.getTierMultiplier().toString() + "x",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // 升级进度条
                if (tierProgress != null && tierProgress.currentTier < 5) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("升级进度", "Upgrade progress"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "${(tierProgress.progressPercent * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LinearProgressIndicator(
                            progress = tierProgress.progressPercent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = com.soulon.app.i18n.AppStrings.trf(
                                "还需 %d \$MEMO 升级到 Tier %d",
                                "Need %d \$MEMO to reach Tier %d",
                                tierProgress.memoNeeded,
                                tierProgress.nextTier
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                        
                        if (onClick != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("点击查看所有等级 →", "Tap to view all tiers →"),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else if (tierProgress != null && tierProgress.currentTier == 5) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("✨ 已达最高等级！", "✨ Max tier reached!"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // $MEMO 积分
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("\$MEMO 余额", "\$MEMO balance"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "${userProfile.memoBalance}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("累计收入", "Total earned"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "${userProfile.totalMemoEarned}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * 等级积分卡片（左侧，2/3 宽度）
 */
/**
 * 积分等级卡片 - 现代化设计
 * 
 * 设计特点：
 * - 渐变背景
 * - 大圆角 (28dp)
 * - 柔和阴影
 * - Material Icons
 * - 与WalletCard高度对齐
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TierCard(
    walletConnected: Boolean,
    userProfile: com.soulon.app.rewards.UserProfile?,
    tierProgress: com.soulon.app.rewards.TierProgress?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .modernCardShadow(AppElevations.Medium, AppShapes.Card)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) 
                else Modifier
            ),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (walletConnected && userProfile != null)
                        Brush.linearGradient(
                            colors = listOf(
                                AppColors.PrimaryGradientStart,
                                AppColors.PrimaryGradientEnd
                            )
                        )
                    else
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFF1F5F9),
                                Color(0xFFE2E8F0)
                            )
                        )
                )
                .padding(AppSpacing.Medium)
        ) {
            if (walletConnected && userProfile != null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 顶部：等级信息（紧凑版）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
                        ) {
                            Icon(
                                imageVector = getMainTierIcon(userProfile.currentTier),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(AppIconSizes.Medium)
                            )
                            Text(
                                text = userProfile.getTierName(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Surface(
                            shape = AppShapes.Tag,
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = userProfile.getTierMultiplier().toString() + "x",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    
                    // 中间：进度条（紧凑版）
                    if (tierProgress != null && tierProgress.currentTier < 5) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(AppCorners.Full))
                                    .background(Color.White.copy(alpha = 0.2f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(tierProgress.progressPercent)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(AppCorners.Full))
                                        .background(Color.White)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${(tierProgress.progressPercent * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                    
                    // 底部：余额
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = "${userProfile.memoBalance}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "\$MEMO",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                // 未连接钱包状态（紧凑版）
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.EmojiEvents,
                        contentDescription = null,
                        tint = AppColors.PrimaryGradientStart,
                        modifier = Modifier.size(AppIconSizes.Medium)
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("等级系统", "Tier System"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("连接钱包查看", "Connect wallet to view"),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * 钱包连接卡片 - 现代化设计
 * 
 * 设计特点：
 * - 渐变背景 (青色系)
 * - 大圆角 (28dp)
 * - 柔和阴影
 * - Material Icons
 * - 与TierCard高度对齐
 */
@Composable
private fun WalletCard(
    walletConnected: Boolean,
    walletAddress: String?,
    walletBalance: Long,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .modernCardShadow(AppElevations.Medium, AppShapes.Card),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    // 比会员卡片稍亮的背景
                    Color.White.copy(alpha = 0.10f)
                )
                .padding(AppSpacing.Medium)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 钱包图标 - 统一白色
                Icon(
                    imageVector = if (walletConnected) 
                        Icons.Rounded.AccountBalanceWallet 
                    else 
                        Icons.Rounded.LinkOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 按钮
                Button(
                    onClick = if (walletConnected) onDisconnect else onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Button,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (walletConnected) 
                            Color.White.copy(alpha = 0.15f) 
                        else 
                            Color(0xFF14F195),
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text(
                        text = if (walletConnected)
                            com.soulon.app.i18n.AppStrings.tr("断开", "Disconnect")
                        else
                            com.soulon.app.i18n.AppStrings.tr("连接", "Connect"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * 快速操作按钮 - 签到、生态质押、赛季奖励、我的资产
 */
@Composable
private fun QuickActionButtons(
    onSeasonRewards: () -> Unit,
    onMyAssets: () -> Unit,
    onEcoStaking: () -> Unit = {},
    onCheckIn: () -> Unit = {},
    isSubscribed: Boolean = false,
    onShowSubscribeDialog: () -> Unit = {},
    onNavigateToGame: () -> Unit = {}
) {
    val context = LocalContext.current
    
    Column(
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
    ) {
        // 🆕 探索冒险入口 (放在最上方显眼位置)
        Surface(
            onClick = onNavigateToGame,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = AppShapes.Card,
            color = Color(0xFF673AB7) // Deep Purple
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Explore, // 使用 Explore 图标
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("探索冒险", "Explore Adventure"),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 第一行：签到 + 生态质押
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            // 每日签到按钮
            Surface(
                onClick = onCheckIn,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = AppShapes.Card,
                color = Color(0xFF14F195).copy(alpha = 0.15f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Rounded.CalendarToday,
                            contentDescription = com.soulon.app.i18n.AppStrings.tr("每日签到", "Daily check-in"),
                            modifier = Modifier.size(22.dp),
                            tint = Color(0xFF14F195)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("签到", "Check-in"),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF14F195),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            
            // 生态质押按钮
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = isSubscribed) {
                            showComingSoonToast(context)
                            // onEcoStaking()
                        },
                    shape = AppShapes.Card,
                    color = Color.White.copy(alpha = 0.10f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Rounded.Savings,
                                contentDescription = com.soulon.app.i18n.AppStrings.tr("生态质押", "Eco staking"),
                                modifier = Modifier.size(22.dp),
                                tint = if (isSubscribed) Color.White else Color.White.copy(alpha = 0.3f)
                            )
                            if (!isSubscribed) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.tr("已锁定", "Locked"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
                
                // 未订阅时的模糊遮罩
                if (!isSubscribed) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(AppShapes.Card)
                            .background(Color.Black.copy(alpha = 0.4f))
                    )
                }
            }
        }
        
        // 第二行：赛季奖励和我的资产按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            // 赛季奖励按钮
            Surface(
                onClick = onSeasonRewards,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = AppShapes.Card,
                color = Color.White.copy(alpha = 0.10f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.EmojiEvents, 
                        contentDescription = com.soulon.app.i18n.AppStrings.tr("赛季奖励", "Season rewards"),
                        modifier = Modifier.size(26.dp),
                        tint = Color.White
                    )
                }
            }
            
            // 我的资产按钮
            Surface(
                onClick = onMyAssets,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = AppShapes.Card,
                color = Color.White.copy(alpha = 0.10f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Lock,  // 保险箱图标
                        contentDescription = com.soulon.app.i18n.AppStrings.tr("我的资产", "My assets"),
                        modifier = Modifier.size(26.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Phase 2: 记忆管理界面（带下拉刷新）
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun RefreshableMemoriesScreen(
    activity: MainActivity,
    memories: List<MemoryIndex>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    statusMessage: String,
    retrievedContent: String?,
    walletConnected: Boolean,
    walletAddress: String?,
    walletBalance: Long,
    storageManager: StorageManager,
    walletManager: com.soulon.app.wallet.WalletManager,
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    onMemoriesUpdate: (List<MemoryIndex>) -> Unit,
    onStatusUpdate: (String) -> Unit,
    onLoadingUpdate: (Boolean) -> Unit,
    onRetrievedContentUpdate: (String?) -> Unit,
    onWalletUpdate: (Boolean, String?, Long) -> Unit
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = onRefresh
    )
    
    Box(
            modifier = Modifier
                .fillMaxSize()
            .pullRefresh(pullRefreshState)
    ) {
        MemoriesScreenContent(
            activity = activity,
            memories = memories,
            isLoading = isLoading,
            statusMessage = statusMessage,
            retrievedContent = retrievedContent,
            walletConnected = walletConnected,
            walletAddress = walletAddress,
            walletBalance = walletBalance,
            storageManager = storageManager,
            walletManager = walletManager,
            activityResultSender = activityResultSender,
            onMemoriesUpdate = onMemoriesUpdate,
            onStatusUpdate = onStatusUpdate,
            onLoadingUpdate = onLoadingUpdate,
            onRetrievedContentUpdate = onRetrievedContentUpdate,
            onWalletUpdate = onWalletUpdate
        )
        
        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * Phase 2: 记忆管理界面内容
 */
@Composable
fun MemoriesScreenContent(
    activity: MainActivity,
    memories: List<MemoryIndex>,
    isLoading: Boolean,
    statusMessage: String,
    retrievedContent: String?,
    walletConnected: Boolean,
    walletAddress: String?,
    walletBalance: Long,
    storageManager: StorageManager,
    walletManager: com.soulon.app.wallet.WalletManager,
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    onMemoriesUpdate: (List<MemoryIndex>) -> Unit,
    onStatusUpdate: (String) -> Unit,
    onLoadingUpdate: (Boolean) -> Unit,
    onRetrievedContentUpdate: (String?) -> Unit,
    onWalletUpdate: (Boolean, String?, Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // 钱包卡片（简化版，仅显示钱包信息）
        SimpleWalletCard(
            connected = walletConnected,
            address = walletAddress,
            balance = walletBalance,
            onConnect = {
                activity.lifecycleScope.launch {
                    if (isLoading) return@launch
                    
                    onLoadingUpdate(true)
                    onStatusUpdate(com.soulon.app.i18n.AppStrings.tr("正在连接钱包...", "Connecting wallet..."))
                    
                    try {
                        val session = walletManager.connect(activityResultSender)
                        val address = session.getPublicKeyBase58() ?: ""
                        val balance = walletManager.getBalance()
                        
                        onWalletUpdate(true, address, balance)
                        onStatusUpdate(com.soulon.app.i18n.AppStrings.tr("钱包连接成功！", "Wallet connected!"))
                    } catch (e: Exception) {
                        val errorMsg = when {
                            e.message?.contains("User declined") == true -> com.soulon.app.i18n.AppStrings.tr("用户取消了连接", "User canceled")
                            e.message?.contains("No wallet") == true -> com.soulon.app.i18n.AppStrings.tr("未找到钱包应用", "Wallet app not found")
                            else -> com.soulon.app.i18n.AppStrings.trf(
                                "钱包连接失败: %s",
                                "Wallet connect failed: %s",
                                e.message
                            )
                        }
                        onStatusUpdate(errorMsg)
                        Timber.e(e, "钱包连接失败")
                    } finally {
                        onLoadingUpdate(false)
                    }
                }
            },
            onDisconnect = {
                walletManager.disconnect()
                // 🔑 撤销会话密钥（钱包断开时自动撤销）
                storageManager.revokeSessionKey()
                onWalletUpdate(false, null, 0)
                onStatusUpdate(com.soulon.app.i18n.AppStrings.tr("钱包已断开", "Wallet disconnected"))
            }
        )
        
        // 状态消息
        if (statusMessage.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                    text = statusMessage,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
        // 检索内容
        if (retrievedContent != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("解密内容", "Decrypted content"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        IconButton(onClick = { onRetrievedContentUpdate(null) }) {
                            Icon(Icons.Rounded.Close, contentDescription = com.soulon.app.i18n.AppStrings.tr("关闭", "Close"))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                        text = retrievedContent,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            // 记忆列表
            Text(
            text = com.soulon.app.i18n.AppStrings.trf(
                "存储的记忆 (%d)",
                "Stored memories (%d)",
                memories.size
            ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
        if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
        } else if (memories.isEmpty()) {
                Card {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr(
                            "暂无存储的记忆\n点击 + 按钮创建测试记忆",
                            "No stored memories\nTap + to create a test memory"
                        ),
                        modifier = Modifier.padding(32.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                items(memories) { memory ->
                        MemoryCard(
                            memory = memory,
                        onRetrieve = {
                            activity.lifecycleScope.launch {
                                onLoadingUpdate(true)
                                onStatusUpdate(com.soulon.app.i18n.AppStrings.tr("正在检索记忆（需要身份验证）...", "Retrieving memory (authentication required)..."))
                                onRetrievedContentUpdate(null)
                                
                                try {
                                    val content = storageManager.retrieveMemory(memory.id, activity)
                                    
                                    if (content != null) {
                                        onRetrievedContentUpdate(content)
                                        onStatusUpdate(com.soulon.app.i18n.AppStrings.tr("检索成功！", "Retrieved!"))
                                    } else {
                                        onStatusUpdate(com.soulon.app.i18n.AppStrings.tr("检索失败或已取消", "Retrieve failed or canceled"))
                                    }
                                } catch (e: Exception) {
                                    onStatusUpdate(
                                        com.soulon.app.i18n.AppStrings.trf(
                                            "检索失败: %s",
                                            "Retrieve failed: %s",
                                            e.message
                                        )
                                    )
                                    Timber.e(e, "检索记忆失败")
                                } finally {
                                    onLoadingUpdate(false)
                                }
                            }
                        }
                    )
                }

            }
        }
    }
}

/**
 * Phase 2: 记忆卡片
 */
@Composable
fun MemoryCard(
    memory: MemoryIndex,
    onRetrieve: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = com.soulon.app.i18n.AppStrings.trf(
                        "记忆 #%s",
                        "Memory #%s",
                        memory.id.take(8)
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = onRetrieve,
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = com.soulon.app.i18n.AppStrings.tr("解密", "Decrypt"),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(com.soulon.app.i18n.AppStrings.tr("解密", "Decrypt"))
                }
            }
            
            Text(
                text = AppStrings.trf(
                    "Irys Tx: %s...",
                    "Irys Tx: %s...",
                    memory.cnftId.take(20)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = com.soulon.app.i18n.AppStrings.trf(
                    "时间: %s",
                    "Time: %s",
                    formatTimestamp(memory.timestamp)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Phase 2: 简化钱包卡片（仅显示钱包信息，用于记忆界面）
 */
@Composable
fun SimpleWalletCard(
    connected: Boolean,
    address: String?,
    balance: Long,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
            Text(
                        text = if (connected)
                            com.soulon.app.i18n.AppStrings.tr("💳 钱包已连接", "💳 Wallet connected")
                        else
                            com.soulon.app.i18n.AppStrings.tr("💳 连接钱包", "💳 Connect wallet"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (connected && address != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${address.take(8)}...${address.takeLast(8)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
                    }
                }
                
                if (connected) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(com.soulon.app.i18n.AppStrings.tr("断开", "Disconnect"))
                    }
                } else {
                    Button(onClick = onConnect) {
                        Text(com.soulon.app.i18n.AppStrings.tr("连接", "Connect"))
                    }
                }
            }
            
            if (connected) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                Text(
                        text = com.soulon.app.i18n.AppStrings.tr("余额:", "Balance:"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${balance / 1_000_000_000.0} SOL",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Phase 3: 等级详情页面 - 现代化设计
 * 
 * 设计特点：
 * - 渐变背景
 * - 横向滑动大卡片
 * - 现代化指示器
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TierDetailsScreen(
    userProfile: com.soulon.app.rewards.UserProfile?,
    userLevelManager: com.soulon.app.rewards.UserLevelManager,
    onBack: () -> Unit
) {
    // 处理返回手势
    BackHandler(onBack = onBack)
    
    // 获取所有等级信息
    val tierLevels = remember { userLevelManager.getAllTierLevels() }
    var tierProgress by remember { mutableStateOf<com.soulon.app.rewards.TierProgress?>(null) }
    
    // HorizontalPager 状态，初始显示当前等级
    val initialPage = (userProfile?.currentTier ?: 1) - 1
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, tierLevels.size - 1),
        pageCount = { tierLevels.size }
    )
    
    LaunchedEffect(userProfile) {
        tierProgress = userLevelManager.getTierProgress()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .padding(top = AppSpacing.Large)
    ) {
        // 横向滑动的大卡片
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            contentPadding = PaddingValues(horizontal = AppSpacing.XLarge),
            pageSpacing = AppSpacing.Medium
        ) { page ->
            val tierLevel = tierLevels[page]
            val isCurrentTier = userProfile?.currentTier == tierLevel.tier
            val isUnlocked = (userProfile?.currentTier ?: 0) >= tierLevel.tier
            
            // 大卡片：仅包含等级信息和要求
            TierDetailCard(
                tierLevel = tierLevel,
                isCurrentTier = isCurrentTier,
                isUnlocked = isUnlocked,
                userProfile = userProfile,
                tierProgress = if (isCurrentTier) tierProgress else null
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.Large))
        
        // 现代化页面指示器 - 胶囊形
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.Small),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(tierLevels.size) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = if (isSelected) 24.dp else 8.dp, height = 8.dp)
                        .clip(RoundedCornerShape(AppCorners.Full))
                        .background(
                            color = if (isSelected) 
                                AppColors.PrimaryGradientStart
                            else 
                                AppColors.PrimaryGradientStart.copy(alpha = 0.2f)
                        )
                )
            }
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        
        // 独立显示的等级权益区域
        TierPrivilegesBottomSection(
            tierLevel = tierLevels[pagerState.currentPage],
            isUnlocked = (userProfile?.currentTier ?: 0) >= tierLevels[pagerState.currentPage].tier,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = AppSpacing.XLarge)
        )
        
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
    }
}

/**
 * V1 当前等级卡片
 * 
 * 显示：等级 + 积分 + Sovereign Ratio + 升级进度
 * 特殊：Sovereign Ratio 不足时显示"等级已锁定"
 */
@Composable
private fun CurrentTierCard(
    userProfile: com.soulon.app.rewards.UserProfile,
    tierProgress: com.soulon.app.rewards.TierProgress?,
    modifier: Modifier = Modifier
) {
    // 获取下一等级的 Sovereign 要求
    val nextTierSovereignReq = when (userProfile.currentTier) {
        1 -> 0.2f  // Silver 需要 20%
        2 -> 0.4f  // Gold 需要 40%
        3 -> 0.6f  // Platinum 需要 60%
        4 -> 0.8f  // Diamond 需要 80%
        else -> 1.0f
    }
    
    // 检查是否因 Sovereign Ratio 锁定
    val isLockedBySovereign = tierProgress?.isLockedBySovereign == true
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLockedBySovereign) {
                Color(0xFF2D1F1F) // 锁定状态：深红色调
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // 顶部：等级标题 + 锁定状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = com.soulon.app.i18n.AppStrings.tr("当前等级", "Current tier"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                
                // 锁定状态标签
                if (isLockedBySovereign) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFF5722).copy(alpha = 0.2f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = Color(0xFFFF5722),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("等级已锁定", "Locked"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF5722)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 等级信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (userProfile.currentTier) {
                                1 -> "🥉"
                                2 -> "🥈"
                                3 -> "🥇"
                                4 -> "💎"
                                5 -> "👑"
                                else -> "⭐"
                            },
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = userProfile.getTierName(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                    text = AppStrings.trf(
                        "等级 %d/5",
                        "Tier %d/5",
                        userProfile.currentTier
                    ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                
                // 倍数显示
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = userProfile.getTierMultiplier().toString() + "x",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("积分倍数", "Point multiplier"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
            
            // V1 新增：Sovereign Ratio 门槛提示
            if (isLockedBySovereign) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFF5722).copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFF5722),
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr(
                                    "主权比率不足，等级已锁定",
                                    "Insufficient Sovereign ratio; tier locked"
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF5722)
                            )
                            Text(
                                text = com.soulon.app.i18n.AppStrings.trf(
                                    "当前 %d%%，需要 %d%% 解锁下一等级",
                                    "Current %d%%, need %d%% to unlock next tier",
                                    (userProfile.sovereignRatio * 100).toInt(),
                                    (nextTierSovereignReq * 100).toInt()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF5722).copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
            
            if (tierProgress != null && tierProgress.currentTier < 5) {
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(16.dp))
                
                // 下一等级信息
                val nextTierName = when (tierProgress.nextTier) {
                    2 -> "Silver"
                    3 -> "Gold"
                    4 -> "Platinum"
                    5 -> "Diamond"
                    else -> "Tier ${tierProgress.nextTier}"
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.trf(
                            "升级到 %s",
                            "Upgrade to %s",
                            nextTierName
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    // V1 新增：下一等级倍数预览
                    val nextMultiplier = when (tierProgress.nextTier) {
                        2 -> 1.5f
                        3 -> 2.0f
                        4 -> 3.0f
                        5 -> 5.0f
                        else -> 1.0f
                    }
                    Text(
                        text = "→ " + nextMultiplier + "x",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 双进度条：$MEMO 和 Sovereign Ratio
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // $MEMO 进度
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("\$MEMO 进度", "\$MEMO progress"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "${userProfile.totalMemoEarned} / ${tierProgress.memoNeeded + userProfile.totalMemoEarned}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        LinearProgressIndicator(
                            progress = { tierProgress.memoProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF4CAF50),
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        )
                    }
                    
                    // Sovereign Ratio 进度
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("主权比率", "Sovereign Ratio"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "${(userProfile.sovereignRatio * 100).toInt()}% / ${(nextTierSovereignReq * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (userProfile.sovereignRatio >= nextTierSovereignReq) {
                                    Color(0xFF4CAF50)
                                } else {
                                    Color(0xFFFF9800)
                                }
                            )
                        }
                        LinearProgressIndicator(
                            progress = { tierProgress.sovereignProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (userProfile.sovereignRatio >= nextTierSovereignReq) {
                                Color(0xFF4CAF50)
                            } else {
                                Color(0xFFFF9800)
                            },
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // V1 升级条件说明
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr(
                                "升级需同时满足：积分达标 AND Sovereign Ratio 达标",
                                "Upgrade requires: points met AND Sovereign Ratio met"
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }
            } else if (tierProgress != null && tierProgress.currentTier == 5) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "👑", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr(
                            "恭喜！你已达到最高等级 Diamond！",
                            "Congrats! You’ve reached the highest tier: Diamond!"
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD700)
                    )
                }
            }
        }
    }
}

/**
 * 单个等级卡片（简化版，用于横向滑动）
 */
@Composable
private fun TierLevelCard(
    tierLevel: com.soulon.app.rewards.UserLevelManager.Companion.TierLevel,
    isCurrentTier: Boolean,
    isUnlocked: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrentTier -> MaterialTheme.colorScheme.primaryContainer
                isUnlocked -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isCurrentTier) 4.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 等级标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (tierLevel.tier) {
                            1 -> "🥉"
                            2 -> "🥈"
                            3 -> "🥇"
                            4 -> "💎"
                            5 -> "👑"
                            else -> "⭐"
                        },
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = tierLevel.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = AppStrings.trf(
                                "等级 %d",
                                "Tier %d",
                                tierLevel.tier
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
                
                if (isCurrentTier) {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("当前", "Current"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (!isUnlocked) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = com.soulon.app.i18n.AppStrings.tr("未解锁", "Locked"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            
            // 倍数
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = com.soulon.app.i18n.AppStrings.tr("积分倍数", "Point multiplier"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = tierLevel.multiplier.toString() + "x",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // V1 白皮书要求
            if (tierLevel.tier > 1) {
                // $MEMO 要求
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("\$MEMO 要求", "\$MEMO requirement"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatMemoRequirement(tierLevel.memoRequired),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Sovereign Ratio 要求
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("主权比率", "Sovereign Ratio"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(tierLevel.sovereignRequired * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // V1 预期时间
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("预期时间", "ETA"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when (tierLevel.tier) {
                            2 -> com.soulon.app.i18n.AppStrings.tr("2-3 周", "2–3 weeks")
                            3 -> com.soulon.app.i18n.AppStrings.tr("1.5-2 个月", "1.5–2 months")
                            4 -> com.soulon.app.i18n.AppStrings.tr("~3 个月", "~3 months")
                            5 -> com.soulon.app.i18n.AppStrings.tr("长期目标", "Long-term goal")
                            else -> "-"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            } else {
                // Bronze 等级
                Text(
                    text = com.soulon.app.i18n.AppStrings.tr("注册即获得", "Granted on sign-up"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 格式化 MEMO 要求显示
 */
private fun formatMemoRequirement(value: Int): String {
    return when {
        value >= 1000 -> String.format("%,d", value)
        else -> value.toString()
    }
}

/**
 * 获取 Tier 图标 (MainActivity 专用)
 */
private fun getMainTierIcon(tier: Int): androidx.compose.ui.graphics.vector.ImageVector {
    return when (tier) {
        1 -> Icons.Rounded.Shield       // Bronze
        2 -> Icons.Rounded.Star         // Silver
        3 -> Icons.Rounded.Diamond      // Gold
        4 -> Icons.Rounded.Verified     // Platinum
        5 -> Icons.Rounded.EmojiEvents  // Diamond
        else -> Icons.Rounded.Shield
    }
}

/**
 * 等级详情大卡片 - 现代化设计
 * 
 * 设计特点：
 * - 渐变背景（根据等级不同颜色）
 * - 大圆角
 * - Material Icons
 * - 柔和阴影
 */
@Composable
private fun TierDetailCard(
    tierLevel: com.soulon.app.rewards.UserLevelManager.Companion.TierLevel,
    isCurrentTier: Boolean,
    isUnlocked: Boolean,
    userProfile: com.soulon.app.rewards.UserProfile?,
    tierProgress: com.soulon.app.rewards.TierProgress?
) {
    // 根据等级选择渐变颜色
    val gradientColors = when {
        isCurrentTier -> listOf(AppColors.PrimaryGradientStart, AppColors.PrimaryGradientEnd)
        isUnlocked -> listOf(AppColors.SecondaryGradientStart, AppColors.SecondaryGradientEnd)
        else -> listOf(Color(0xFF94A3B8), Color(0xFF64748B)) // Slate 灰色
    }
    
    Card(
        modifier = Modifier
            .fillMaxSize()
            .modernCardShadow(
                if (isCurrentTier) AppElevations.Large else AppElevations.Medium,
                AppShapes.LargeCard
            ),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(colors = gradientColors))
                .padding(AppSpacing.XLarge)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 顶部：等级信息
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
                    ) {
                        // 等级图标容器
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = RoundedCornerShape(AppCorners.Medium),
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = getMainTierIcon(tierLevel.tier),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(AppIconSizes.XLarge)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = tierLevel.name,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = AppStrings.trf(
                                    "等级 %d/5",
                                    "Tier %d/5",
                                    tierLevel.tier
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        // 倍数标签
                        Surface(
                            shape = AppShapes.Tag,
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = tierLevel.multiplier.toString() + "x",
                                modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.XSmall),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                        if (isCurrentTier) {
                            Surface(
                                shape = AppShapes.Tag,
                                color = Color.White.copy(alpha = 0.3f)
                            ) {
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.tr("当前等级", "Current tier"),
                                    modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.XXSmall),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        } else if (!isUnlocked) {
                            Surface(
                                modifier = Modifier.size(32.dp),
                                shape = RoundedCornerShape(AppCorners.Small),
                                color = Color.White.copy(alpha = 0.2f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = com.soulon.app.i18n.AppStrings.tr("未解锁", "Locked"),
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(AppIconSizes.Small)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 中间：进度条或状态
                if (isCurrentTier && tierProgress != null && tierProgress.currentTier < 5) {
                    Column {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("升级进度", "Upgrade progress"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        
                        Spacer(modifier = Modifier.height(AppSpacing.Small))
                        
                        // 现代化进度条
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(AppCorners.Full))
                                    .background(Color.White.copy(alpha = 0.2f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(tierProgress.progressPercent)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(AppCorners.Full))
                                        .background(Color.White)
                                )
                            }
                            Spacer(modifier = Modifier.width(AppSpacing.Small))
                            Text(
                                text = "${(tierProgress.progressPercent * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                        
                        Text(
                            text = com.soulon.app.i18n.AppStrings.trf(
                                "还需 %d \$MEMO 升级到 Tier %d",
                                "Need %d \$MEMO to reach Tier %d",
                                tierProgress.memoNeeded,
                                tierProgress.nextTier
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                } else if (isCurrentTier && tierProgress != null && tierProgress.currentTier == 5) {
                    Surface(
                        shape = AppShapes.Tag,
                        color = Color.White.copy(alpha = 0.2f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(AppIconSizes.Small)
                            )
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("恭喜！已达最高等级", "Congrats! Max tier reached"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                } else {
                    // 占位空间
                    Spacer(modifier = Modifier.height(AppSpacing.Large))
                }
                
                // 底部：升级要求
                if (tierLevel.tier > 1) {
                    Column {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(AppSpacing.Medium))
                        
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("升级要求", "Requirements"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        
                        Spacer(modifier = Modifier.height(AppSpacing.Small))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // $MEMO 要求
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Token,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(AppIconSizes.Small)
                                )
                                Column {
                                    Text(
                                        text = "\$MEMO",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = "${tierLevel.memoRequired}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                            
                            // Sovereign 要求
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Security,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(AppIconSizes.Small)
                                )
                                Column {
                                    Text(
                                        text = AppStrings.tr("主权", "Sovereign"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = "${(tierLevel.sovereignRequired * 100).toInt()}%",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Tier 1 没有要求
                    Surface(
                        shape = AppShapes.Tag,
                        color = Color.White.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("基础等级 · 无需要求", "Base tier · No requirements"),
                            modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 等级权益独立展示区域 - 现代化设计
 * 
 * 设计特点：
 * - 卡片式权益列表
 * - 渐变图标背景
 * - 柔和阴影
 */
@Composable
private fun TierPrivilegesBottomSection(
    tierLevel: com.soulon.app.rewards.UserLevelManager.Companion.TierLevel,
    isUnlocked: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
    ) {
        // 标题栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(AppCorners.Small),
                color = AppColors.PrimaryGradientStart.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.CardGiftcard,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.Medium),
                        tint = AppColors.PrimaryGradientStart
                    )
                }
            }
            Text(
                text = com.soulon.app.i18n.AppStrings.tr("等级权益", "Benefits"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        
        val privileges = when (tierLevel.tier) {
            1 -> listOf(
                Pair(Icons.Rounded.Speed, com.soulon.app.i18n.AppStrings.tr("基础 1.0x 积分倍数", "Base 1.0x points")),
                Pair(Icons.Rounded.Forum, com.soulon.app.i18n.AppStrings.tr("访问 AI 对话功能", "Access AI chat")),
                Pair(Icons.Rounded.Lock, com.soulon.app.i18n.AppStrings.tr("记忆加密存储", "Encrypted memory storage")),
                Pair(Icons.Rounded.Analytics, com.soulon.app.i18n.AppStrings.tr("基础数据分析", "Basic analytics"))
            )
            2 -> listOf(
                Pair(Icons.Rounded.Speed, com.soulon.app.i18n.AppStrings.tr("1.5x 积分倍数", "1.5x points")),
                Pair(Icons.Rounded.Bolt, com.soulon.app.i18n.AppStrings.tr("优先 AI 推理", "Priority inference")),
                Pair(Icons.Rounded.Psychology, com.soulon.app.i18n.AppStrings.tr("高级人格分析", "Advanced persona analysis")),
                Pair(Icons.Rounded.BarChart, com.soulon.app.i18n.AppStrings.tr("数据可视化", "Data visualization"))
            )
            3 -> listOf(
                Pair(Icons.Rounded.Speed, com.soulon.app.i18n.AppStrings.tr("2.0x 积分倍数", "2.0x points")),
                Pair(Icons.Rounded.Search, com.soulon.app.i18n.AppStrings.tr("RAG 语义检索", "RAG semantic search")),
                Pair(Icons.Rounded.Face, com.soulon.app.i18n.AppStrings.tr("自定义 AI 人格", "Custom AI persona")),
                Pair(Icons.Rounded.Star, com.soulon.app.i18n.AppStrings.tr("高级功能访问", "Advanced features"))
            )
            4 -> listOf(
                Pair(Icons.Rounded.Speed, com.soulon.app.i18n.AppStrings.tr("3.0x 积分倍数", "3.0x points")),
                Pair(Icons.Rounded.CardGiftcard, com.soulon.app.i18n.AppStrings.tr("空投优先权", "Airdrop priority")),
                Pair(Icons.Rounded.Token, com.soulon.app.i18n.AppStrings.tr("Soulbound Token", "Soulbound Token")),
                Pair(Icons.Rounded.MilitaryTech, com.soulon.app.i18n.AppStrings.tr("社区徽章", "Community badge")),
                Pair(Icons.Rounded.SupportAgent, com.soulon.app.i18n.AppStrings.tr("优先客服支持", "Priority support"))
            )
            5 -> listOf(
                Pair(Icons.Rounded.Speed, com.soulon.app.i18n.AppStrings.tr("5.0x 积分倍数", "5.0x points")),
                Pair(Icons.Rounded.HowToVote, com.soulon.app.i18n.AppStrings.tr("治理投票权", "Governance voting")),
                Pair(Icons.Rounded.Verified, com.soulon.app.i18n.AppStrings.tr("独家功能访问", "Exclusive features")),
                Pair(Icons.Rounded.Diamond, com.soulon.app.i18n.AppStrings.tr("VIP 身份标识", "VIP badge")),
                Pair(Icons.Rounded.Celebration, com.soulon.app.i18n.AppStrings.tr("专属活动邀请", "Exclusive events")),
                Pair(Icons.Rounded.Handshake, com.soulon.app.i18n.AppStrings.tr("优先合作机会", "Priority partnerships"))
            )
            else -> emptyList()
        }
        
        // 权益卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .modernCardShadow(AppElevations.Small, AppShapes.Card),
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                items(privileges.size) { index ->
                    val (icon, text) = privileges[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppCorners.Small))
                            .background(
                                if (isUnlocked) 
                                    AppColors.SuccessGradientStart.copy(alpha = 0.05f)
                                else 
                                    Color.Transparent
                            )
                            .padding(AppSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                    ) {
                        // 图标容器
                        Surface(
                            modifier = Modifier.size(32.dp),
                            shape = RoundedCornerShape(AppCorners.XSmall),
                            color = if (isUnlocked) 
                                AppColors.SuccessGradientStart.copy(alpha = 0.1f)
                            else 
                                AppColors.TextTertiary.copy(alpha = 0.1f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isUnlocked) Icons.Rounded.CheckCircle else icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(AppIconSizes.Small),
                                    tint = if (isUnlocked) 
                                        AppColors.SuccessGradientStart
                                    else 
                                        AppColors.TextTertiary
                                )
                            }
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isUnlocked) 
                                Color.White
                            else 
                                AppColors.TextTertiary,
                            fontWeight = if (isUnlocked) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
                
                // 提示信息
                if (tierLevel.tier > 1 && !isUnlocked) {
                    item {
                        Spacer(modifier = Modifier.height(AppSpacing.Small))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(AppCorners.Small),
                            color = AppColors.WarningGradientStart.copy(alpha = 0.1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(AppSpacing.Small),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lightbulb,
                                    contentDescription = null,
                                    modifier = Modifier.size(AppIconSizes.Small),
                                    tint = AppColors.WarningGradientStart
                                )
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.trf(
                                        "累计 %d \$MEMO + %d%% 主权比率解锁",
                                        "Unlock with %d \$MEMO + %d%% Sovereign ratio",
                                        tierLevel.memoRequired,
                                        (tierLevel.sovereignRequired * 100).toInt()
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 等级权益详情区域
 */
@Composable
private fun TierPrivilegesSection(
    tierLevel: com.soulon.app.rewards.UserLevelManager.Companion.TierLevel,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = com.soulon.app.i18n.AppStrings.tr("等级权益", "Benefits"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = AppStrings.trf(
                    "%s（等级 %d）",
                    "%s (Tier %d)",
                    tierLevel.name,
                    tierLevel.tier
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            
            val privileges = when (tierLevel.tier) {
                1 -> listOf(
                    com.soulon.app.i18n.AppStrings.tr("基础 1.0x 积分倍数", "Base 1.0x points"),
                    com.soulon.app.i18n.AppStrings.tr("访问 AI 对话功能", "Access AI chat"),
                    com.soulon.app.i18n.AppStrings.tr("记忆加密存储", "Encrypted memory storage"),
                    com.soulon.app.i18n.AppStrings.tr("基础数据分析", "Basic analytics")
                )
                2 -> listOf(
                    com.soulon.app.i18n.AppStrings.tr("1.5x 积分倍数", "1.5x points"),
                    com.soulon.app.i18n.AppStrings.tr("优先 AI 推理", "Priority inference"),
                    com.soulon.app.i18n.AppStrings.tr("高级人格分析", "Advanced persona analysis"),
                    com.soulon.app.i18n.AppStrings.tr("数据可视化", "Data visualization")
                )
                3 -> listOf(
                    com.soulon.app.i18n.AppStrings.tr("2.0x 积分倍数", "2.0x points"),
                    com.soulon.app.i18n.AppStrings.tr("RAG 语义检索", "RAG semantic search"),
                    com.soulon.app.i18n.AppStrings.tr("自定义 AI 人格", "Custom AI persona"),
                    com.soulon.app.i18n.AppStrings.tr("高级功能访问", "Advanced features")
                )
                4 -> listOf(
                    com.soulon.app.i18n.AppStrings.tr("3.0x 积分倍数", "3.0x points"),
                    com.soulon.app.i18n.AppStrings.tr("空投优先权", "Airdrop priority"),
                    com.soulon.app.i18n.AppStrings.tr("Soulbound Token", "Soulbound Token"),
                    com.soulon.app.i18n.AppStrings.tr("社区徽章", "Community badge"),
                    com.soulon.app.i18n.AppStrings.tr("优先客服支持", "Priority support")
                )
                5 -> listOf(
                    com.soulon.app.i18n.AppStrings.tr("5.0x 积分倍数", "5.0x points"),
                    com.soulon.app.i18n.AppStrings.tr("治理投票权", "Governance voting"),
                    com.soulon.app.i18n.AppStrings.tr("独家功能访问", "Exclusive features"),
                    com.soulon.app.i18n.AppStrings.tr("VIP 身份标识", "VIP badge"),
                    com.soulon.app.i18n.AppStrings.tr("专属活动邀请", "Exclusive events"),
                    com.soulon.app.i18n.AppStrings.tr("优先合作机会", "Priority partnerships")
                )
                else -> emptyList()
            }
            
            privileges.forEachIndexed { index, privilege ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.Small),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = privilege,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                if (index < privileges.size - 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            
            // 特殊提示
            if (tierLevel.tier > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.Medium),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("提示", "Tip"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = com.soulon.app.i18n.AppStrings.trf(
                        "升级到此等级需要累计 %d \$MEMO 并保持 %d%% 主权比率",
                        "To reach this tier: accumulate %d \$MEMO and maintain %d%% Sovereign ratio",
                        tierLevel.memoRequired,
                        (tierLevel.sovereignRequired * 100).toInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Phase 3: 处理聊天消息
 */
/**
 * 🔐 处理 AI 对话消息（带加密记忆检索）
 * 
 * 第一阶段：检索加密记忆的元数据（不解密），询问用户是否要解密
 */
private suspend fun handleChatMessageWithEncryption(
    message: String,
    sessionId: String?,
    chatRepository: com.soulon.app.chat.ChatRepository,
    personalizedRAG: PersonalizedRAG,
    rewardsRepository: RewardsRepository,
    storageManager: StorageManager,
    activity: MainActivity,
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    walletManager: com.soulon.app.wallet.WalletManager,
    decrypt: Boolean
): ChatResponse {
    suspend fun runOnce(): ChatResponse {
        val auth = com.soulon.app.auth.BackendAuthManager.getInstance(activity)
        auth.ensureSession(activityResultSender, walletManager).getOrThrow()

        // 🌅 检查并发放每日首聊奖励
        try {
            val firstChatResult = rewardsRepository.rewardFirstChat()
            if (firstChatResult.amount > 0) {
                Timber.i("🌅 每日首聊奖励已发放: +${firstChatResult.amount} MEMO")
            }
        } catch (e: Exception) {
            Timber.w(e, "每日首聊奖励检查失败")
        }
        
        // 如果是无记忆模式请求
        if (message.startsWith("【无记忆模式】")) {
            val actualMessage = message.removePrefix("【无记忆模式】")
            return handleSimpleChat(actualMessage, sessionId, chatRepository, personalizedRAG, rewardsRepository, activity)
        }
        
        Timber.d("🔐 处理消息（检索相关记忆）: $message")
        
        // Step 1: 获取所有记忆索引（不解密）
        val allMemories = storageManager.getAllMemories()
        Timber.d("📦 本地记忆索引: ${allMemories.size} 条")
        
        if (allMemories.isEmpty()) {
            Timber.i("没有存储的记忆，使用简化对话模式")
            return handleSimpleChat(message, sessionId, chatRepository, personalizedRAG, rewardsRepository, activity)
        }
        
        // Step 2: 🔍 进行向量搜索，找到最相关的记忆（Top-K）
        val semanticSearchEngine = com.soulon.app.rag.SemanticSearchEngine(activity)
        val searchQuery = buildSearchQuery(sessionId, chatRepository, message)
        val searchResults = semanticSearchEngine.search(
            query = searchQuery,
            topK = 5,  // 搜索前 5 条最相关的记忆
            threshold = 0.5f  // 相似度阈值 50%
        )
        
        val relevantMemoryIds = when (searchResults) {
            is com.soulon.app.rag.SearchResults.Success -> {
                Timber.i("🎯 向量搜索成功：找到 ${searchResults.results.size} 条相关记忆")
                searchResults.results.map { it.memoryId }
            }
            is com.soulon.app.rag.SearchResults.Empty -> {
                Timber.i("📭 没有找到相关记忆: ${searchResults.message}，使用简化模式")
                return handleSimpleChat(message, sessionId, chatRepository, personalizedRAG, rewardsRepository, activity)
            }
            is com.soulon.app.rag.SearchResults.Error -> {
                Timber.w("⚠️ 向量搜索失败: ${searchResults.message}，使用简化模式")
                return handleSimpleChat(message, sessionId, chatRepository, personalizedRAG, rewardsRepository, activity)
            }
        }
        
        if (relevantMemoryIds.isEmpty()) {
            Timber.i("没有找到相关记忆，使用简化对话模式")
            return handleSimpleChat(message, sessionId, chatRepository, personalizedRAG, rewardsRepository, activity)
        }
        
        // Step 3: 从缓存检查哪些记忆已解密
        val cachedContents = mutableMapOf<String, String>()
        val uncachedMemoryIds = mutableListOf<String>()
        
        relevantMemoryIds.forEach { memoryId ->
            val cachedContent = com.soulon.app.cache.MemoryCache.get(memoryId)
            if (cachedContent != null) {
                cachedContents[memoryId] = cachedContent
            } else {
                uncachedMemoryIds.add(memoryId)
            }
        }
        
        Timber.d("📊 相关记忆统计：已缓存=${cachedContents.size}，未缓存=${uncachedMemoryIds.size}")
        
        // Step 4: 如果所有相关记忆都已缓存，直接使用 RAG
        if (cachedContents.isNotEmpty() && uncachedMemoryIds.isEmpty()) {
            Timber.i("✅ 所有相关记忆已在缓存中，直接使用 RAG")
            return handleRAGChat(message, sessionId, chatRepository, relevantMemoryIds, cachedContents, personalizedRAG, rewardsRepository, activity)
        }
        
        // Step 5: 如果有部分或全部记忆未缓存，自动解密（不再需要用户确认）
        if (uncachedMemoryIds.isNotEmpty()) {
            Timber.i("🔐 发现 ${uncachedMemoryIds.size} 条相关但未解密的记忆，自动解密...")
            
            // 自动批量解密记忆（使用钱包密钥，无需用户确认）
            try {
                val decryptedContents = storageManager.retrieveMemoriesBatch(uncachedMemoryIds, activity)
                
                if (decryptedContents.isNotEmpty()) {
                    // 缓存解密后的内容
                    decryptedContents.forEach { (memoryId, content) ->
                        com.soulon.app.cache.MemoryCache.put(memoryId, content)
                        cachedContents[memoryId] = content
                    }
                    Timber.i("✅ 自动解密成功：${decryptedContents.size} 条记忆")
                } else {
                    Timber.w("解密返回空结果，使用已缓存内容或简化模式")
                }
            } catch (e: Exception) {
                Timber.w(e, "自动解密失败，使用已缓存内容或简化模式")
            }
            
            // 使用所有可用的内容（已缓存 + 新解密的）进行 RAG 对话
            if (cachedContents.isNotEmpty()) {
                return handleRAGChat(message, sessionId, chatRepository, relevantMemoryIds, cachedContents, personalizedRAG, rewardsRepository, activity)
            } else {
                return handleSimpleChat(message, sessionId, chatRepository, personalizedRAG, rewardsRepository, activity)
            }
        }
        
        // 不应该到达这里
        Timber.w("⚠️ 未预期的流程分支")
        return handleSimpleChat(message, sessionId, chatRepository, personalizedRAG, rewardsRepository, activity)
    }

    fun isUnauthorized(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val m = cur.message?.lowercase().orEmpty()
            if (m.contains("401") || m.contains("unauthorized") || m.contains("missing_token")) return true
            cur = cur.cause
        }
        return false
    }

    return try {
        runOnce()
    } catch (e: com.soulon.app.x402.PaymentRequiredException) {
        com.soulon.app.x402.X402ChallengeStore.set(e.challenge)
        com.soulon.app.x402.PaymentRequiredBus.publish(e.challenge)
        ChatResponse(
            answer = "需要完成支付验证后才能继续。",
            retrievedMemories = emptyList(),
            rewardedMemo = 0
        )
    } catch (e: Exception) {
        if (isUnauthorized(e)) {
            val auth = com.soulon.app.auth.BackendAuthManager.getInstance(activity)
            auth.clear()
            return try {
                auth.ensureSession(activityResultSender, walletManager).getOrThrow()
                runOnce()
            } catch (e2: Exception) {
                Timber.e(e2, "对话失败(重登后仍失败)")
                ChatResponse(
                    answer = "抱歉，对话失败：${e2.message}",
                    retrievedMemories = emptyList(),
                    rewardedMemo = 0
                )
            }
        }
        Timber.e(e, "对话失败")
        ChatResponse(
            answer = "抱歉，对话失败：${e.message}",
            retrievedMemories = emptyList(),
            rewardedMemo = 0
        )
    }
}

/**
 * 🔐 解密记忆并回答
 * 
 * 第二阶段：用户确认后，批量解密指定记忆并使用 RAG 回答
 * 只需一次生物识别验证，即可批量解密所有记忆
 */
private suspend fun handleChatMessageWithDecryption(
    message: String,
    memoryIds: List<String>,
    sessionId: String?,
    chatRepository: com.soulon.app.chat.ChatRepository,
    personalizedRAG: PersonalizedRAG,
    rewardsRepository: RewardsRepository,
    storageManager: StorageManager,
    activity: MainActivity,
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    walletManager: com.soulon.app.wallet.WalletManager
): ChatResponse {
    return try {
        val auth = com.soulon.app.auth.BackendAuthManager.getInstance(activity)
        auth.ensureSession(activityResultSender, walletManager).getOrThrow()
        Timber.i("🔓 开始批量解密 ${memoryIds.size} 条记忆（一次验证）...")
        
        // 🔐 一次性身份验证 + 批量解密
        val decryptedContents = storageManager.retrieveMemoriesBatch(memoryIds, activity)
        
        if (decryptedContents.isEmpty()) {
            Timber.w("没有成功解密任何记忆")
            return handleSimpleChat(message, sessionId, chatRepository, personalizedRAG, rewardsRepository, activity)
        }
        
        // 缓存解密后的内容
        decryptedContents.forEach { (memoryId, content) ->
            com.soulon.app.cache.MemoryCache.put(memoryId, content)
        }
        
        Timber.i("🔓 成功解密并缓存 ${decryptedContents.size} 条记忆")
        
        // 使用解密后的内容进行 RAG 对话
        return handleRAGChat(message, sessionId, chatRepository, memoryIds, decryptedContents, personalizedRAG, rewardsRepository, activity)
        
    } catch (e: SecurityException) {
        Timber.e(e, "身份验证失败")
        ChatResponse(
            answer = "身份验证失败，无法解密记忆。请重试。",
            retrievedMemories = emptyList(),
            rewardedMemo = 0
        )
    } catch (e: Exception) {
        Timber.e(e, "解密并回答失败")
        ChatResponse(
            answer = "抱歉，解密失败：${e.message}",
            retrievedMemories = emptyList(),
            rewardedMemo = 0
        )
    }
}

/**
 * 简单对话（无记忆）
 */
private suspend fun buildChatHistory(
    sessionId: String?,
    chatRepository: com.soulon.app.chat.ChatRepository,
    currentUserMessage: String,
    limit: Int = 12
): List<com.soulon.app.ai.QwenCloudManager.Message> {
    if (sessionId.isNullOrBlank()) return emptyList()
    val all = runCatching { chatRepository.getMessagesOnce(sessionId) }.getOrNull().orEmpty()
        .filter { !it.isError }
    val recent = all.takeLast(limit)
    val trimmed = if (recent.isNotEmpty() && recent.last().isUser && recent.last().text.trim() == currentUserMessage.trim()) {
        recent.dropLast(1)
    } else {
        recent
    }
    return trimmed.map { m ->
        com.soulon.app.ai.QwenCloudManager.Message(
            role = if (m.isUser) "user" else "assistant",
            content = m.text
        )
    }
}

private suspend fun buildSearchQuery(
    sessionId: String?,
    chatRepository: com.soulon.app.chat.ChatRepository,
    currentUserMessage: String
): String {
    if (sessionId.isNullOrBlank()) return currentUserMessage
    val all = runCatching { chatRepository.getMessagesOnce(sessionId) }.getOrNull().orEmpty()
        .filter { it.isUser && !it.isError }
    val recent = all.takeLast(2)
    val trimmed = if (recent.isNotEmpty() && recent.last().text.trim() == currentUserMessage.trim()) recent.dropLast(1) else recent
    val previous = trimmed.lastOrNull()?.text
    return if (previous.isNullOrBlank()) currentUserMessage else previous + "\n" + currentUserMessage
}

private suspend fun handleSimpleChat(
    message: String,
    sessionId: String?,
    chatRepository: com.soulon.app.chat.ChatRepository,
    personalizedRAG: PersonalizedRAG,
    rewardsRepository: RewardsRepository,
    activity: MainActivity
): ChatResponse {
    // 确保 PersonalizedRAG 已初始化
    try {
        personalizedRAG.initialize()
    } catch (e: Exception) {
        Timber.e(e, "PersonalizedRAG 初始化失败")
        return ChatResponse(
            answer = "AI 服务初始化失败：${e.message}",
            needsDecryption = false,
            encryptedMemoryIds = emptyList()
        )
    }
    
    val responseBuilder = StringBuilder()
    var chunkCount = 0

    val history = buildChatHistory(sessionId, chatRepository, message)

    try {
        kotlinx.coroutines.withTimeout(60_000) {
            personalizedRAG.simpleChat(
                userQuery = message,
                usePersona = true,
                history = history
            ).collect { token ->
                responseBuilder.append(token)
                chunkCount++
            }
        }
    } catch (e: com.soulon.app.x402.PaymentRequiredException) {
        com.soulon.app.x402.X402ChallengeStore.set(e.challenge)
        com.soulon.app.x402.PaymentRequiredBus.publish(e.challenge)
        return ChatResponse(
            answer = "需要完成支付验证后才能继续。",
            retrievedMemories = emptyList(),
            rewardedMemo = 0
        )
    }
    
    val answer = responseBuilder.toString()
    if (answer.isBlank()) {
        throw IllegalStateException("Empty AI response")
    }
    
    // 每条对话固定积分（不基于 Token 数量）
    val reward = rewardsRepository.rewardAIInference()
    
    Timber.i("简单对话完成，发放奖励: ${reward.amount} \$MEMO")
    
    // 分析对话相关性（异步）
    activity.lifecycleScope.launch {
        try {
            analyzeConversationRelevance(message, answer, activity)
        } catch (e: Exception) {
            Timber.e(e, "对话分析失败")
        }
    }
    
    return ChatResponse(
        answer = answer,
        retrievedMemories = emptyList(),
        rewardedMemo = reward.amount
    )
}

/**
 * RAG 对话（带记忆检索）
 */
private suspend fun handleRAGChat(
    message: String,
    sessionId: String?,
    chatRepository: com.soulon.app.chat.ChatRepository,
    memoryOrder: List<String>,
    memoryContents: Map<String, String>,
    personalizedRAG: PersonalizedRAG,
    rewardsRepository: RewardsRepository,
    activity: MainActivity
): ChatResponse {
    Timber.i("使用 RAG 模式，基于 ${memoryContents.size} 条已解密记忆生成回答...")
    
    try {
        // 确保 PersonalizedRAG 已初始化
        try {
            personalizedRAG.initialize()
        } catch (e: Exception) {
            Timber.e(e, "PersonalizedRAG 初始化失败")
            return ChatResponse(
                answer = "AI 服务初始化失败：${e.message}",
                needsDecryption = false,
                encryptedMemoryIds = emptyList()
            )
        }
        val history = buildChatHistory(sessionId, chatRepository, message)
        val memoryContext = buildString {
            append("【参考记忆（用户以前记录，不是 AI 的记忆）】\n")
            append("仅当与用户问题直接相关时引用；若记忆不足以回答，请明确说明并提出澄清问题。\n\n")
            var remaining = 1800
            var idx = 1
            memoryOrder.forEach { id ->
                val content = memoryContents[id] ?: return@forEach
                val snippet = if (content.length > 400) content.take(400) + "..." else content
                if (snippet.length > remaining) return@forEach
                append("记忆 ").append(idx).append(": ").append(snippet).append("\n\n")
                remaining -= snippet.length
                idx++
            }
        }

        Timber.d("开始生成回答（基于 ${memoryContents.size} 条记忆，history=${history.size}）...")

        val responseBuilder = StringBuilder()
        var chunkCount = 0
        
        kotlinx.coroutines.withTimeout(60_000) {
            personalizedRAG.simpleChat(
                userQuery = message,
                usePersona = true,
                history = history,
                extraSystemContext = memoryContext
            ).collect { token ->
                responseBuilder.append(token)
                chunkCount++
            }
        }
        
        val answer = responseBuilder.toString()
        if (answer.isBlank()) {
            throw IllegalStateException("Empty AI response")
        }
        
        // 每条对话固定积分（不基于 Token 数量）
        val reward = rewardsRepository.rewardAIInference()
        
        Timber.i("RAG 对话完成，使用 ${memoryContents.size} 条记忆，发放奖励: ${reward.amount} \$MEMO")
        
        val retrievedMemories = memoryOrder.mapNotNull { id -> memoryContents[id] }
            .take(3)
            .map { content -> "记忆片段: ${content.take(100)}..." }
        
        // 分析对话相关性（异步）
        activity.lifecycleScope.launch {
            try {
                analyzeConversationRelevance(message, answer, activity)
            } catch (e: Exception) {
                Timber.e(e, "对话分析失败")
            }
        }
        
        return ChatResponse(
            answer = answer,
            retrievedMemories = retrievedMemories,
            rewardedMemo = reward.amount
        )
        
    } catch (e: com.soulon.app.x402.PaymentRequiredException) {
        com.soulon.app.x402.X402ChallengeStore.set(e.challenge)
        com.soulon.app.x402.PaymentRequiredBus.publish(e.challenge)
        return ChatResponse(
            answer = "需要完成支付验证后才能继续。",
            retrievedMemories = emptyList(),
            rewardedMemo = 0
        )
    }
}

/**
 * 分析对话与问卷的相关性，并计算人格共鸣评分
 */
private suspend fun analyzeConversationRelevance(
    message: String,
    answer: String,
    activity: MainActivity
) {
    try {
        // 检查是否已完成初始化
        val isOnboardingComplete = com.soulon.app.onboarding.OnboardingState.isCompleted(activity)
        if (!isOnboardingComplete) {
            return
        }
        
        // 获取问卷答案
        val storage = com.soulon.app.onboarding.OnboardingEvaluationStorage(activity)
        val evaluations = storage.getAllEvaluations()
        
        if (evaluations.isEmpty()) {
            Timber.d("没有问卷评估数据，跳过分析")
            return
        }
        
        val questionnaireAnswers = evaluations.map { it.questionId to it.originalAnswer }
        
        // 创建并初始化 QwenCloudManager
        val qwenManager = com.soulon.app.ai.QwenCloudManager(activity)
        qwenManager.initialize()
        
        // 创建分析器
        val analyzer = com.soulon.app.onboarding.ConversationAnalyzer(
            activity,
            qwenManager
        )
        
        // 分析对话（传入 null 作为 memoryId，因为我们在内存缓存中）
        analyzer.analyzeConversation(
            userMessage = message,
            aiResponse = answer,
            newMemoryId = null,
            questionnaireAnswers = questionnaireAnswers
        )
        
        // 获取更新后的评估报告
        val evaluationManager = com.soulon.app.onboarding.OnboardingEvaluationManager(activity)
        val report = evaluationManager.getOverallReport()
        
        Timber.i(
            "评估报告更新：整体可信度=${(report.overallReliability * 100).toInt()}%，" +
            "等级=${report.getReliabilityGrade()}"
        )
        
        // ========== 计算人格共鸣评分并补发奖励 ==========
        val resonanceScore = calculateResonanceScore(message, answer, evaluations, qwenManager)
        if (resonanceScore >= 70) {  // 只有 A 级及以上才补发
            val rewardsRepository = com.soulon.app.rewards.RewardsRepository(activity)
            val bonusAmount = rewardsRepository.rewardResonanceBonus(resonanceScore)
            if (bonusAmount > 0) {
                Timber.i("🎯 人格共鸣评分: $resonanceScore, 补发奖励: +$bonusAmount MEMO")
            }
        }

        tryReinforcePersonaFromChat(message, activity, qwenManager)
        
    } catch (e: Exception) {
        Timber.e(e, "对话分析失败")
    }
}

private suspend fun tryReinforcePersonaFromChat(
    userMessage: String,
    activity: MainActivity,
    qwenManager: com.soulon.app.ai.QwenCloudManager
) {
    try {
        val trimmed = userMessage.trim()
        if (trimmed.length < 60) return

        val relevanceAnalyzer = com.soulon.app.ai.PersonaRelevanceAnalyzer(activity, qwenManager)
        if (!relevanceAnalyzer.quickCheck(trimmed)) return

        val repo = com.soulon.app.rewards.RewardsRepository(activity)
        val current = repo.getUserProfile()
        val now = System.currentTimeMillis()
        val last = current.lastPersonaAnalysis ?: 0L
        if (now - last < 6 * 60 * 60 * 1000L) return

        val prompt = """
请根据以下用户消息，快速估计用户的 OCEAN 五大人格维度分数（0.0-1.0）。

【用户消息】
$trimmed

只输出 JSON，不要输出其他任何文字：
{
  "openness": 0.0,
  "conscientiousness": 0.0,
  "extraversion": 0.0,
  "agreeableness": 0.0,
  "neuroticism": 0.0
}
    """.trimIndent()

        val sb = StringBuilder()
        qwenManager.generateStream(
            prompt = prompt,
            systemPrompt = "你是一个人格评估器。只输出 JSON。分数范围必须是 0.0 到 1.0。",
            maxNewTokens = 120,
            functionType = "persona"
        ).collect { sb.append(it) }

        val text = sb.toString().trim()
        val jsonText = "```json\\s*([\\s\\S]*?)\\s*```".toRegex().find(text)?.groupValues?.get(1)?.trim()
            ?: "```\\s*([\\s\\S]*?)\\s*```".toRegex().find(text)?.groupValues?.get(1)?.trim()
            ?: text

        val obj = org.json.JSONObject(jsonText)
        val estimate = com.soulon.app.rewards.PersonaData(
            openness = obj.optDouble("openness", 0.5).toFloat().coerceIn(0f, 1f),
            conscientiousness = obj.optDouble("conscientiousness", 0.5).toFloat().coerceIn(0f, 1f),
            extraversion = obj.optDouble("extraversion", 0.5).toFloat().coerceIn(0f, 1f),
            agreeableness = obj.optDouble("agreeableness", 0.5).toFloat().coerceIn(0f, 1f),
            neuroticism = obj.optDouble("neuroticism", 0.5).toFloat().coerceIn(0f, 1f),
            analyzedAt = now,
            sampleSize = 1
        )

        val wallet = com.soulon.app.wallet.WalletScope.currentWalletAddress(activity)
        repo.reinforcePersonaFromChatEstimate(wallet, estimate, sourceId = null)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_e: Exception) {
    }
}

/**
 * 计算人格共鸣评分
 * 
 * 评估用户对话内容与人格画像的匹配度
 * 
 * @return 0-100 的评分
 */
private suspend fun calculateResonanceScore(
    userMessage: String,
    aiResponse: String,
    evaluations: List<com.soulon.app.onboarding.OnboardingEvaluation>,
    qwenManager: com.soulon.app.ai.QwenCloudManager
): Int {
    val trimmedMessage = userMessage.trim()
    
    // ========== 快速过滤：简短消息直接返回低分，不调用 AI ==========
    // 太短的消息不可能体现人格特征
    if (trimmedMessage.length < 10) {
        Timber.d("🎯 消息太短 (${trimmedMessage.length}字), 跳过人格共鸣评分")
        return 30  // C级
    }
    
    // 简单问候语直接返回低分
    val greetings = listOf("你好", "您好", "hi", "hello", "嗨", "在吗", "在不在", "早", "早安", "晚安", "晚上好", "下午好", "上午好")
    if (greetings.any { trimmedMessage.equals(it, ignoreCase = true) || trimmedMessage.startsWith(it) && trimmedMessage.length < 15 }) {
        Timber.d("🎯 简单问候语, 跳过人格共鸣评分")
        return 35  // C级
    }
    
    // 纯表情或符号
    if (trimmedMessage.all { !it.isLetterOrDigit() }) {
        return 25  // C级
    }
    
    // 没有人格问卷数据
    if (evaluations.isEmpty()) {
        Timber.d("🎯 无人格问卷数据, 跳过评分")
        return 40  // 低B级
    }
    
    return try {
        // 构建人格特征摘要
        val personaTraits = evaluations.take(5).joinToString("\n") { eval ->
            "- ${eval.questionId}: ${eval.originalAnswer}"
        }
        
        // 使用 AI 评估对话与人格的匹配度
        val prompt = """
请严格评估以下用户对话与其人格特征的匹配程度。

【评分标准】
- 90-100 (S级): 必须是深度自我剖析、详细描述个人价值观/人生经历的长消息 (100字以上)
- 70-89 (A级): 体现明确的个人观点、情感表达，与人格特征有明显关联 (50字以上)
- 40-69 (B级): 普通对话，有一定相关性
- 0-39 (C级): 简短回复、闲聊、问候、无关话题

【用户人格特征摘要】
$personaTraits

【用户消息】
$trimmedMessage

【消息长度】${trimmedMessage.length}字

注意：简短消息(少于30字)通常应评为B级或更低。只有真正展现深度人格特征的长消息才能获得A级以上。

只返回一个数字（0-100），不要其他内容。
        """.trimIndent()
        
        val responseBuilder = StringBuilder()
        // 使用 generateStream 流式生成
        qwenManager.generateStream(
            prompt = prompt,
            systemPrompt = "你是一个严格的人格分析专家。对于简短消息要给低分，只有真正深度的自我表达才值得高分。只返回评分数字。",
            maxNewTokens = 10
        ).collect { chunk: String ->
            responseBuilder.append(chunk)
        }
        
        // 提取数字
        val scoreText = responseBuilder.toString().trim()
        var score = scoreText.filter { it.isDigit() }.take(3).toIntOrNull() ?: 50
        
        // 额外保护：根据消息长度限制最高分
        val maxScoreByLength = when {
            trimmedMessage.length < 20 -> 50   // 短消息最高 B级
            trimmedMessage.length < 50 -> 69   // 中等消息最高 B级
            trimmedMessage.length < 100 -> 85  // 较长消息最高 A级
            else -> 100                         // 长消息可以 S级
        }
        score = score.coerceIn(0, maxScoreByLength)
        
        Timber.d("🎯 人格共鸣评分: $score (消息长度: ${trimmedMessage.length}字, 上限: $maxScoreByLength)")
        score
    } catch (e: Exception) {
        Timber.w(e, "人格共鸣评分计算失败，使用默认值")
        45  // 默认低 B 级
    }
}

/**
 * Material Design 3 主题
 */
@Composable
fun MemoryAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF6200EE),
            secondary = androidx.compose.ui.graphics.Color(0xFF03DAC6),
            tertiary = androidx.compose.ui.graphics.Color(0xFFBB86FC),
            background = androidx.compose.ui.graphics.Color(0xFF121212),
            surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
        ),
        shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(com.soulon.app.ui.theme.AppCorners.Small),
            small = androidx.compose.foundation.shape.RoundedCornerShape(com.soulon.app.ui.theme.AppCorners.Medium),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(com.soulon.app.ui.theme.AppCorners.XLarge),
            large = androidx.compose.foundation.shape.RoundedCornerShape(com.soulon.app.ui.theme.AppCorners.Huge),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(com.soulon.app.ui.theme.AppCorners.Huge)
        ),
        content = content
    )
}

/**
 * 初始化问卷流程
 */
@Composable
fun OnboardingFlow(
    activity: MainActivity,
    storageManager: StorageManager,
    personalizedRAG: PersonalizedRAG,
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    walletAddress: String?,
    onComplete: () -> Unit,
    onNavigateToHome: () -> Unit = {}
) {
    val questions = remember { com.soulon.app.onboarding.OnboardingQuestions.getAllQuestions() }
    val evaluationStorage = remember { com.soulon.app.onboarding.OnboardingEvaluationStorage(activity) }
    var currentQuestionIndex by remember { 
        mutableStateOf(com.soulon.app.onboarding.OnboardingState.getCurrentQuestionIndex(activity))
    }
    var answers by remember {
        mutableStateOf(
            evaluationStorage.getAllEvaluations()
                .distinctBy { it.questionId }
                .mapNotNull { eval ->
                    val question = questions.find { it.id == eval.questionId } ?: return@mapNotNull null
                    com.soulon.app.onboarding.OnboardingAnswer(
                        questionId = eval.questionId,
                        answer = eval.originalAnswer,
                        dimension = question.dimension
                    )
                }
        )
    }
    var isProcessing by remember { mutableStateOf(false) }
    var showBatchAuthPrompt by remember { mutableStateOf(false) }  // ✅ 新增：批量授权提示
    var uploadStarted by remember { mutableStateOf(com.soulon.app.onboarding.OnboardingState.isUploadStarted(activity, walletAddress)) }
    var showUploadProgress by remember { mutableStateOf(uploadStarted) }
    var showCompletion by remember { mutableStateOf(false) }
    var personaAnalysisComplete by remember { mutableStateOf(com.soulon.app.onboarding.OnboardingState.isPersonaAnalysisComplete(activity, walletAddress)) }  // 人格分析完成标志
    
    // 订阅上传进度
    val rawUploadStates by storageManager.uploadProgressManager.uploadStates.collectAsState()
    val uploadStates = rawUploadStates.filterKeys { it.startsWith("onboarding_") || it == "persona_data_v1" }
    val hasUploadTasks = uploadStates.isNotEmpty()
    val hasActiveUploads = uploadStates.values.any { it.status != com.soulon.app.storage.UploadProgressManager.UploadStatus.COMPLETED }
    val shouldShowUploadProgress = showUploadProgress || hasActiveUploads || (uploadStarted && hasUploadTasks)

    LaunchedEffect(walletAddress) {
        uploadStarted = com.soulon.app.onboarding.OnboardingState.isUploadStarted(activity, walletAddress)
        if (!showUploadProgress && uploadStarted && hasUploadTasks) showUploadProgress = true
        personaAnalysisComplete = com.soulon.app.onboarding.OnboardingState.isPersonaAnalysisComplete(activity, walletAddress)
    }
    
    // 上传失败状态
    var uploadFailedCount by remember { mutableStateOf(0) }
    var showUploadError by remember { mutableStateOf(false) }
    var uploadErrorMessage by remember { mutableStateOf("") }
    
    // 监控上传状态，检测失败
    LaunchedEffect(uploadStates) {
        val staleAfterMs = 15 * 60 * 1000L
        val now = System.currentTimeMillis()
        uploadStates.values.forEach { state ->
            if (state.status != com.soulon.app.storage.UploadProgressManager.UploadStatus.COMPLETED
                && state.status != com.soulon.app.storage.UploadProgressManager.UploadStatus.FAILED
                && (now - state.timestamp) > staleAfterMs
            ) {
                storageManager.uploadProgressManager.markFailed(state.memoryId, "上传超时，请重试")
            }
        }

        val failedUploads = uploadStates.values.filter { 
            it.status == com.soulon.app.storage.UploadProgressManager.UploadStatus.FAILED 
        }
        uploadFailedCount = failedUploads.size
        
        if (failedUploads.isNotEmpty() && shouldShowUploadProgress) {
            uploadErrorMessage = "有 ${failedUploads.size} 条记忆上传失败"
            showUploadError = true
        }
    }
    
    // 当所有上传完成且人格分析完成时，自动跳转到完成界面
    LaunchedEffect(uploadStates, personaAnalysisComplete, shouldShowUploadProgress) {
        val allUploadsCompleted = uploadStates.values.all { 
            it.status == com.soulon.app.storage.UploadProgressManager.UploadStatus.COMPLETED 
        }
        val anyFailed = uploadStates.values.any {
            it.status == com.soulon.app.storage.UploadProgressManager.UploadStatus.FAILED
        }
        
        // 只有所有上传成功且人格分析完成才跳转
        if (allUploadsCompleted && !anyFailed && personaAnalysisComplete && shouldShowUploadProgress) {
            showUploadProgress = false
            uploadStarted = false
            com.soulon.app.onboarding.OnboardingState.setUploadStarted(activity, walletAddress, false)
            showCompletion = true
        }
    }

    val hasInFlightUploads = uploadStates.values.any {
        it.status == com.soulon.app.storage.UploadProgressManager.UploadStatus.PENDING
            || it.status == com.soulon.app.storage.UploadProgressManager.UploadStatus.ENCRYPTING
            || it.status == com.soulon.app.storage.UploadProgressManager.UploadStatus.UPLOADING
            || it.status == com.soulon.app.storage.UploadProgressManager.UploadStatus.MINTING
    }

    var autoResumeTriggered by remember { mutableStateOf(false) }

    fun launchOnboardingUpload() {
        if (answers.isEmpty()) {
            showUploadProgress = true
            uploadStarted = false
            com.soulon.app.onboarding.OnboardingState.setUploadStarted(activity, walletAddress, false)
            isProcessing = false
            uploadErrorMessage = com.soulon.app.i18n.AppStrings.tr(
                "未找到已保存的问卷答案，请重新完成问卷后再上传。",
                "No saved questionnaire answers found. Please complete the questionnaire again before uploading."
            )
            showUploadError = true
            return
        }

        showBatchAuthPrompt = false
        showUploadProgress = true
        uploadStarted = true
        com.soulon.app.onboarding.OnboardingState.setUploadStarted(activity, walletAddress, true)
        personaAnalysisComplete = false
        com.soulon.app.onboarding.OnboardingState.setPersonaAnalysisComplete(activity, walletAddress, false)
        isProcessing = true

        activity.lifecycleScope.launch {
            try {
                Timber.i("🚀 开始批量上传 ${answers.size} 条记忆...")

                val existingIds = storageManager.getAllMemories().map { it.id }.toSet()
                storageManager.uploadProgressManager.uploadStates.value.keys.forEach { id ->
                    if (existingIds.contains(id)) {
                        storageManager.uploadProgressManager.markCompleted(id)
                    }
                }
                val memoryTexts = mutableListOf<String>()

                answers.forEach { answer ->
                    val question = questions.find { it.id == answer.questionId }
                    if (question != null) {
                        val memoryContent = "${question.question}\n答：${answer.answer}"
                        memoryTexts.add(memoryContent)
                        val memoryId = "onboarding_${answer.questionId}"
                        if (existingIds.contains(memoryId)) {
                            storageManager.uploadProgressManager.markCompleted(memoryId)
                            return@forEach
                        }

                        val result = storageManager.storeMemoryWithId(
                            memoryId = memoryId,
                            content = memoryContent,
                            metadata = mapOf(
                                "type" to "onboarding",
                                "question_id" to answer.questionId.toString(),
                                "dimension" to answer.dimension.name
                            ),
                            activityResultSender = activityResultSender
                        )

                        if (result.success && result.memoryId != null) {
                            Timber.d("初始化记忆 ${answer.questionId} 已保存")
                        } else {
                            throw IllegalStateException(result.message)
                        }
                    }
                }

                Timber.i("✅ 所有初始化记忆已保存")

                Timber.i("===== 开始人格分析 =====")
                Timber.d("人格分析输入文本数量: ${memoryTexts.size}")

                val qwenManagerForPersona = com.soulon.app.ai.QwenCloudManager(activity)
                qwenManagerForPersona.initialize()
                Timber.d("QwenCloudManager 已初始化")

                val personaExtractor = com.soulon.app.persona.PersonaExtractor(
                    activity,
                    qwenManagerForPersona
                )

                if (memoryTexts.isNotEmpty()) {
                    Timber.i("🧠 调用 PersonaExtractor.extractPersona()...")
                    val extractionResult = personaExtractor.extractPersona(memoryTexts)
                    Timber.d("📋 人格分析结果: $extractionResult")

                    when (extractionResult) {
                        is com.soulon.app.persona.PersonaExtractionResult.Success -> {
                            val personaData = extractionResult.personaData
                            val (dominantTrait, score) = personaData.getDominantTrait()
                            Timber.i("✅ 人格分析成功！")
                            Timber.i("  - 主导特质: $dominantTrait (${(score * 100).toInt()}%)")
                            Timber.i("  - 开放性: ${personaData.openness}")
                            Timber.i("  - 尽责性: ${personaData.conscientiousness}")
                            Timber.i("  - 外向性: ${personaData.extraversion}")
                            Timber.i("  - 宜人性: ${personaData.agreeableness}")
                            Timber.i("  - 神经质: ${personaData.neuroticism}")
                            Timber.i("  - 样本数: ${personaData.sampleSize}")

                            val rewardsRepo = com.soulon.app.rewards.RewardsRepository(activity)
                            val currentProfile = rewardsRepo.getUserProfile()
                            val updatedProfile = currentProfile.copy(
                                personaData = personaData,
                                lastPersonaAnalysis = System.currentTimeMillis(),
                                personaSyncRate = extractionResult.syncRate
                            )
                            val database = com.soulon.app.rewards.RewardsDatabase.getInstance(activity)
                            database.rewardsDao().updateUserProfile(updatedProfile)
                            Timber.i("✅ 人格数据已保存到本地数据库")

                            try {
                                Timber.i("上传人格数据到 Irys...")
                                val personaJson = com.google.gson.Gson().toJson(personaData)

                                val personaResult = storageManager.storeMemoryWithId(
                                    memoryId = "persona_data_v1",
                                    content = personaJson,
                                    metadata = mapOf(
                                        "type" to "PersonaData",
                                        "version" to "1.0",
                                        "timestamp" to System.currentTimeMillis().toString()
                                    ),
                                    activityResultSender = activityResultSender
                                )

                                if (personaResult.success) {
                                    Timber.i("✅ 人格数据已上传到 Irys: ${personaResult.irysUri}")
                                } else {
                                    Timber.w("人格数据上传失败: ${personaResult.message}")
                                }
                            } catch (personaUploadError: Exception) {
                                Timber.w(personaUploadError, "人格数据上传到 Irys 失败，将在下次同步时重试")
                            }

                            personaAnalysisComplete = true
                            com.soulon.app.onboarding.OnboardingState.setPersonaAnalysisComplete(activity, walletAddress, true)
                        }

                        is com.soulon.app.persona.PersonaExtractionResult.Error -> {
                            Timber.e("❌ 人格分析失败: ${extractionResult.message}")
                            val msg = extractionResult.message
                            val isAiNotConfigured = msg.contains("AI service not configured", ignoreCase = true) ||
                                msg.contains("missing_qwen_api_key", ignoreCase = true)
                            if (isAiNotConfigured) {
                                personaAnalysisComplete = true
                                com.soulon.app.onboarding.OnboardingState.setPersonaAnalysisComplete(activity, walletAddress, true)
                                Timber.w("AI 服务未配置，跳过人格分析以完成初始化")
                            } else {
                                uploadErrorMessage = "人格分析失败: ${extractionResult.message}"
                                showUploadError = true
                                personaAnalysisComplete = false
                                com.soulon.app.onboarding.OnboardingState.setPersonaAnalysisComplete(activity, walletAddress, false)
                            }
                        }
                    }
                } else {
                    Timber.e("❌ 没有可用的记忆数据进行人格分析")
                    uploadErrorMessage = "没有可用的记忆数据进行人格分析"
                    showUploadError = true
                    personaAnalysisComplete = false
                    com.soulon.app.onboarding.OnboardingState.setPersonaAnalysisComplete(activity, walletAddress, false)
                }

                Timber.i("初始化评估系统...")
                val evaluationManager = com.soulon.app.onboarding.OnboardingEvaluationManager(activity)
                evaluationManager.initializeEvaluations(answers)
                Timber.i("评估系统已初始化")

                com.soulon.app.onboarding.OnboardingState.markCompletedAndSync(activity, walletAddress ?: "")

                com.soulon.app.proactive.ProactiveQuestionWorker.schedulePeriodicWork(activity)
                Timber.i("✨ 奇遇定时任务已启动，将通过通知推送")

                try {
                    val adventureManager = com.soulon.app.proactive.ProactiveQuestionManager(activity)
                    adventureManager.setWalletAddress(walletAddress)
                    val seeds = adventureManager.generateQuestions(count = 1)
                    if (seeds.isNotEmpty()) {
                        val notificationManager = com.soulon.app.proactive.ProactiveQuestionNotificationManager(activity)
                        val sent = notificationManager.sendQuestionNotification(seeds.first())
                        if (sent) {
                            adventureManager.markQuestionAsNotified(seeds.first().id)
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "初始化奇遇失败（不影响主流程）")
                }

                isProcessing = false
                Timber.i("✅ 初始化完成！所有数据已同步到 Irys")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "批量上传失败")
                isProcessing = false
                uploadErrorMessage = e.message
                    ?: com.soulon.app.i18n.AppStrings.tr("上传失败，请检查网络连接", "Upload failed. Please check your network connection.")
                showUploadError = true
            }
        }
    }

    LaunchedEffect(uploadStarted, shouldShowUploadProgress, hasInFlightUploads, personaAnalysisComplete, answers) {
        if (uploadStarted && shouldShowUploadProgress && answers.isNotEmpty() && !hasInFlightUploads && !personaAnalysisComplete && !autoResumeTriggered) {
            autoResumeTriggered = true
            launchOnboardingUpload()
        }
    }
    
    when {
        showCompletion -> {
            // 显示完成界面
            OnboardingCompletionScreen(
                onStartChat = onComplete,
                onNavigateToHome = onNavigateToHome
            )
        }
        showBatchAuthPrompt -> {
            // ✅ 显示批量授权提示
            com.soulon.app.ui.BatchAuthorizationScreen(
                totalMemoryCount = answers.size,
                onStartAuthorization = {
                    launchOnboardingUpload()
                },
                onLearnMore = {
                    Timber.i("用户请求了解更多关于授权的信息")
                },
                isProcessing = isProcessing
            )
        }
        shouldShowUploadProgress -> {
            // 显示上传进度界面
            val analyzingPersona = !personaAnalysisComplete && uploadStates.isNotEmpty()
            com.soulon.app.ui.UploadProgressScreen(
                uploadStates = uploadStates,
                isAnalyzingPersona = analyzingPersona,
                onComplete = {
                    // 只有当人格分析也完成时且没有失败才允许继续
                    if (personaAnalysisComplete && uploadFailedCount == 0) {
                        showUploadProgress = false
                        uploadStarted = false
                        com.soulon.app.onboarding.OnboardingState.setUploadStarted(activity, walletAddress, false)
                        showCompletion = true
                    }
                },
                onRetry = { memoryId ->
                    // 重置错误状态
                    showUploadError = false
                    uploadErrorMessage = ""
                    Timber.i("重试上传: $memoryId")
                    activity.lifecycleScope.launch {
                        try {
                            // 重新上传失败的记忆
                            storageManager.uploadProgressManager.markRetrying(memoryId)
                            
                            val questionId = memoryId.removePrefix("onboarding_").toIntOrNull()
                            val failedAnswer = if (questionId != null) {
                                answers.find { it.questionId == questionId }
                            } else {
                                null
                            }
                            
                            if (failedAnswer != null) {
                                val question = questions.find { it.id == failedAnswer.questionId }
                                val memoryContent = "${question?.question}\n答：${failedAnswer.answer}"
                                
                                val result = storageManager.storeMemoryWithId(
                                    memoryId = memoryId,
                                    content = memoryContent,
                                    metadata = mapOf(
                                        "type" to "onboarding",
                                        "question_id" to failedAnswer.questionId.toString()
                                    ),
                                    activityResultSender = activityResultSender
                                )
                                
                                if (!result.success) {
                                    Timber.e("重试失败: ${result.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "重试上传失败")
                            uploadErrorMessage = com.soulon.app.i18n.AppStrings.trf(
                                "重试失败: %s",
                                "Retry failed: %s",
                                e.message
                            )
                            showUploadError = true
                        }
                    }
                },
                onEmptyAction = {
                    showUploadProgress = false
                    uploadStarted = false
                    com.soulon.app.onboarding.OnboardingState.setUploadStarted(activity, walletAddress, false)
                    showBatchAuthPrompt = true
                }
            )
            
            // 上传错误对话框
            if (showUploadError) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showUploadError = false },
                    icon = {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    Color(0xFFFF6B6B).copy(alpha = 0.15f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = null,
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    },
                    title = {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("上传失败", "Upload failed"),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = uploadErrorMessage,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr(
                                    "您的数据需要成功上传到区块链才能确保永久保存。请检查网络连接后重试。",
                                    "Your data must be uploaded on-chain to ensure permanent storage. Please check your network connection and retry."
                                ),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.Button(
                            onClick = {
                                showUploadError = false
                                // 重试所有失败的上传
                                activity.lifecycleScope.launch {
                                    uploadStates.forEach { (memoryId, state) ->
                                        if (state.status == com.soulon.app.storage.UploadProgressManager.UploadStatus.FAILED) {
                                            storageManager.uploadProgressManager.markRetrying(memoryId)
                                        }
                                    }
                                }
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = com.soulon.app.ui.theme.AppColors.PrimaryGradientStart
                            )
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(com.soulon.app.i18n.AppStrings.tr("重试上传", "Retry upload"))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showUploadError = false },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = Color.White.copy(alpha = 0.7f)
                            )
                        ) {
                            Text(com.soulon.app.i18n.AppStrings.tr("稍后再试", "Try later"))
                        }
                    },
                    containerColor = Color(0xFF1A1A2E),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                )
            }
        }
        else -> {
        // 显示问卷界面
        OnboardingScreen(
            questions = questions,
            currentIndex = currentQuestionIndex,
            answers = answers,
            onAnswerChanged = { questionId: Int, answer: String ->
                // 更新答案
                val question = questions.find { it.id == questionId }
                if (question != null) {
                    val newAnswers = answers.filter { it.questionId != questionId }.toMutableList()
                    newAnswers.add(
                        com.soulon.app.onboarding.OnboardingAnswer(
                            questionId = questionId,
                            answer = answer,
                            dimension = question.dimension
                        )
                    )
                    answers = newAnswers
                    evaluationStorage.saveEvaluation(
                        com.soulon.app.onboarding.OnboardingEvaluation(
                            questionId = questionId,
                            originalAnswer = answer
                        )
                    )
                }
            },
            onNext = {
                // 保存进度
                com.soulon.app.onboarding.OnboardingState.saveProgress(activity, currentQuestionIndex + 1)
                currentQuestionIndex++
            },
            onPrevious = {
                com.soulon.app.onboarding.OnboardingState.saveProgress(activity, currentQuestionIndex - 1)
                currentQuestionIndex--
            },
            onComplete = {
                // ✅ 用户完成问卷，显示批量授权提示
                showUploadProgress = false
                uploadStarted = false
                com.soulon.app.onboarding.OnboardingState.setUploadStarted(activity, walletAddress, false)
                showBatchAuthPrompt = true
            },
            onNavigateToHome = onNavigateToHome,
            isProcessing = isProcessing
        )
        }
    }
}

/**
 * 我的资产页面
 * 展示用户已获得的奖励（暂未开放）
 */
@Composable
fun MyAssetsScreen(
    userProfile: com.soulon.app.rewards.UserProfile?,
    walletConnected: Boolean,
    walletAddress: String?,
    onBack: () -> Unit,
    voyageRepository: com.soulon.app.game.VoyageRepository? = null,
    onOpenAssetDetail: ((com.soulon.app.game.VoyageRepository.MyAsset) -> Unit)? = null
) {
    // 提示框显示状态
    var showTooltip by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var assets by remember { mutableStateOf<List<com.soulon.app.game.VoyageRepository.MyAsset>>(emptyList()) }

    LaunchedEffect(walletConnected, walletAddress) {
        val addr = walletAddress?.trim().orEmpty()
        if (!walletConnected || addr.isBlank() || voyageRepository == null) return@LaunchedEffect
        isLoading = true
        errorText = null
        try {
            assets = voyageRepository.getMyAssets(addr)
        } catch (e: Exception) {
            errorText = e.message ?: AppStrings.tr("加载失败", "Load failed")
        } finally {
            isLoading = false
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = com.soulon.app.i18n.AppStrings.tr("我的资产", "My assets"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // 感叹号图标带提示
                Box {
                    IconButton(
                        onClick = { showTooltip = !showTooltip },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = com.soulon.app.i18n.AppStrings.tr("说明", "Info"),
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    // 提示框
                    androidx.compose.material3.DropdownMenu(
                        expanded = showTooltip,
                        onDismissRequest = { showTooltip = false },
                        modifier = Modifier.background(Color(0xFF1A1A2E))
                    ) {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr(
                                "这里将会显示您已获得的奖励",
                                "Your earned rewards will appear here"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
            
            // 空白内容区域 - 居中显示占位提示
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.Large),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = AppStrings.tr("正在加载...", "Loading..."),
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                    !errorText.isNullOrBlank() -> {
                        Text(
                            text = AppStrings.trf("错误：%s", "Error: %s", errorText!!),
                            color = Color(0xFFFF4444)
                        )
                    }
                    assets.isNotEmpty() -> {
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(assets.size) { idx ->
                                val item = assets[idx]
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = onOpenAssetDetail != null) {
                                            onOpenAssetDetail?.invoke(item)
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16161D))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_ship_basic_freighter),
                                            contentDescription = item.name,
                                            modifier = Modifier.size(72.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = item.name,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Rounded.Inventory2,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = Color.White.copy(alpha = 0.15f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("暂无资产", "No assets yet"),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.3f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr(
                                    "您获得的奖励将会显示在这里",
                                    "Rewards you earn will be shown here"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GameLoadingScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val targetMs = remember {
        val cal = java.util.Calendar.getInstance()
        cal.set(2026, java.util.Calendar.APRIL, 15, 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val remaining = (targetMs - nowMs).coerceAtLeast(0L)
    val totalSec = remaining / 1000
    val days = totalSec / 86400
    val hours = (totalSec % 86400) / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = AppStrings.back,
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = AppStrings.tr("欢迎你、冒险家", "Welcome, adventurer"),
                color = Color(0xFF14F195),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = AppStrings.tr("信号来源不明，正在搭建稳定通道", "Unknown signal source. Stabilizing channel..."),
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = AppStrings.tr("预计开启时间 2026/04/15", "Estimated launch 2026/04/15"),
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = String.format("%dD %02d:%02d:%02d", days, hours, mins, secs),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("https://x.com/Soulon_Memo")
                    }
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF16161D),
                    contentColor = Color(0xFF14F195)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = AppStrings.tr("关注官方账号", "Follow official"),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun AssetDetailScreen(
    kind: String,
    name: String,
    assetAddress: String?,
    metadataUri: String?,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.Medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = AppStrings.back,
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_ship_basic_freighter),
                        contentDescription = name,
                        modifier = Modifier.size(110.dp),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    assetAddress?.let {
                        Text(
                            text = it,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    metadataUri?.let {
                        Text(
                            text = it,
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {},
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = Color(0xFF9E9E9E).copy(alpha = 0.18f),
                        disabledContentColor = Color.White.copy(alpha = 0.55f)
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = AppStrings.tr("发送", "Send"),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = {},
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = Color(0xFF9E9E9E).copy(alpha = 0.18f),
                        disabledContentColor = Color.White.copy(alpha = 0.55f)
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = AppStrings.tr("烧毁", "Burn"),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 我的页面 - 现代化卡片设计
 */
@Composable
fun ProfileScreen(
    onNavigateToLanguage: () -> Unit,
    onNavigateToNotification: () -> Unit,
    onNavigateToSecurity: () -> Unit,
    onNavigateToQA: () -> Unit,
    onNavigateToBugReport: () -> Unit,
    onNavigateToContactUs: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToSubscriptionManage: () -> Unit = {},
    onNavigateToPaymentEcosystem: () -> Unit = {},
    onNavigateToPaymentEcosystemDev: () -> Unit = {},
    currentLanguage: com.soulon.app.i18n.Language,
    walletAddress: String? = null
) {
    val context = LocalContext.current
    val autoRenewService = remember { com.soulon.app.subscription.AutoRenewService.getInstance(context) }
    val isAutoRenewEnabled = walletAddress?.let { autoRenewService.isAutoRenewEnabled(it) } ?: false
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            // ========== 偏好设置 ==========
            item {
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                ProfileSectionHeader(title = com.soulon.app.i18n.AppStrings.profileSectionPreferences)
            }
            
            // 语言
            item {
                ProfileMenuCard(
                    title = com.soulon.app.i18n.AppStrings.profileLanguage,
                    subtitle = currentLanguage.nativeName,
                    onClick = onNavigateToLanguage
                )
            }
            
            // 通知
            item {
                ProfileMenuCard(
                    title = com.soulon.app.i18n.AppStrings.profileNotifications,
                    subtitle = com.soulon.app.i18n.AppStrings.profileNotificationsDesc,
                    onClick = onNavigateToNotification
                )
            }
            
            // 安全
            item {
                ProfileMenuCard(
                    title = com.soulon.app.i18n.AppStrings.profileSecurity,
                    subtitle = com.soulon.app.i18n.AppStrings.profileSecurityDesc,
                    onClick = onNavigateToSecurity
                )
            }
            
            // ========== 帮助 & 支持 ==========
            item {
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                ProfileSectionHeader(title = com.soulon.app.i18n.AppStrings.profileSectionHelpSupport)
            }

            item {
                ProfileMenuCard(
                    title = AppStrings.tr("加入我们", "Join us"),
                    subtitle = AppStrings.tr("获取最新活动奖励", "Get the latest event rewards"),
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("https://x.com/Soulon_Memo")
                        }
                        context.startActivity(intent)
                    }
                )
            }
            
            // 常见问题
            item {
                ProfileMenuCard(
                    title = com.soulon.app.i18n.AppStrings.profileFaq,
                    subtitle = com.soulon.app.i18n.AppStrings.profileFaqDesc,
                    onClick = onNavigateToQA
                )
            }

            // Bug 报告
            item {
                ProfileMenuCard(
                    title = com.soulon.app.i18n.AppStrings.profileBugReport,
                    subtitle = com.soulon.app.i18n.AppStrings.profileBugReportDesc,
                    onClick = onNavigateToBugReport
                )
            }
            
            // 关于
            item {
                ProfileMenuCard(
                    title = com.soulon.app.i18n.AppStrings.profileAbout,
                    subtitle = com.soulon.app.i18n.AppStrings.profileAboutDesc,
                    onClick = onNavigateToAbout
                )
            }
        }
    }
}

/**
 * 我的页面分组标题
 */
@Composable
private fun ProfileSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier.padding(vertical = AppSpacing.Small, horizontal = AppSpacing.Small)
    )
}

/**
 * 水滴流淌效果 Tab Bar
 */
@Composable
private fun LiquidTabBar(
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    val items = listOf(
        Icons.Rounded.Home,
        Icons.Rounded.SmartToy,
        Icons.Rounded.Person
    )
    
    // 水滴位置动画 - 使用 spring 实现流体感
    val targetPosition by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 300f
        ),
        label = "liquidPosition"
    )
    
    // 水滴拉伸效果 - 移动时拉伸
    var previousIndex by remember { mutableStateOf(selectedIndex) }
    val isMoving = previousIndex != selectedIndex
    
    LaunchedEffect(selectedIndex) {
        previousIndex = selectedIndex
    }
    
    // 拉伸动画
    val stretchFactor by animateFloatAsState(
        targetValue = if (isMoving) 1.4f else 1f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = 400f
        ),
        label = "stretchFactor"
    )
    
    // 外层容器 - 简洁深色风格 Tab Bar（无边框）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(
                // 与 ProfileMenuCard 一致的风格
                Color.White.copy(alpha = 0.08f)
            )
    ) {
            
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val itemWidth = maxWidth / items.size
                val indicatorWidth = 52.dp
                val indicatorOffset = (itemWidth.value * targetPosition + (itemWidth.value - indicatorWidth.value) / 2).dp
                
                // 水滴指示器背景 - 选中状态高亮
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width((indicatorWidth.value * stretchFactor).dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            // 选中指示器使用稍高透明度
                            Color.White.copy(alpha = 0.12f)
                        )
                ) {
                    // 指示器顶部微弱高光
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Color.White.copy(alpha = 0.15f))
                    )
                }
                
                // 导航图标
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, icon ->
                        val isSelected = index == selectedIndex
                        
                        // 图标缩放动画
                        val scale by animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 1f,
                            animationSpec = spring(
                                dampingRatio = 0.6f,
                                stiffness = 400f
                            ),
                            label = "iconScale$index"
                        )
                        
                        // 图标透明度动画
                        val alpha by animateFloatAsState(
                            targetValue = if (isSelected) 1f else 0.5f,
                            animationSpec = tween(200),
                            label = "iconAlpha$index"
                        )
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null  // 移除点击涟漪效果
                                ) { onItemSelected(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(26.dp)
                                    .scale(scale),
                                tint = Color.White.copy(alpha = alpha)
                            )
                        }
                    }
                }
            }
    }
}

/**
 * 我的页面菜单卡片 - 深色现代化设计（无图标版本）
 */
@Composable
private fun ProfileMenuCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: String? = null,
    badgeColor: Color = Color(0xFF14F195)
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AppShapes.Card,
        color = Color.White.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * 关于页面 - 现代化设计
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = com.soulon.app.i18n.AppStrings.profileAbout,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Large),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = AppStrings.tr("Soulon", "Soulon"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                    Text(
                        text = AppStrings.tr("Soulon", "Soulon"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .modernCardShadow(AppElevations.Small, AppShapes.LargeCard),
                    shape = AppShapes.LargeCard,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.XLarge),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
                    ) {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.aboutAppIntroTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = com.soulon.app.i18n.AppStrings.aboutIntro,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .modernCardShadow(AppElevations.Small, AppShapes.LargeCard),
                    shape = AppShapes.LargeCard,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.XLarge),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.aboutVersionNameLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * 常见问题页面 - 现代化设计
 */
@Composable
fun QAScreen(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = com.soulon.app.i18n.AppStrings.profileFaq,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // QA 列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
            ) {
                // 基础使用
                item {
                    Text(
                        text = AppStrings.tr("基础使用", "Basics"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = AppSpacing.XSmall)
                    )
                }
                
                item {
                    QACard(
                        index = 1,
                        question = AppStrings.tr("如何连接钱包？", "How do I connect a wallet?"),
                        answer = AppStrings.tr(
                            "在首页点击右上角的钱包卡片，选择您的 Solana 钱包应用进行连接。支持 Phantom、Solflare 等主流钱包。连接后您的钱包地址将显示在左上角的会员卡片中。",
                            "On the home screen, tap the wallet card in the top-right and choose your Solana wallet app. Phantom and Solflare are supported. After connecting, your wallet address appears on the membership card at the top-left."
                        )
                    )
                }
                
                item {
                    QACard(
                        index = 2,
                        question = AppStrings.tr("什么是奇遇任务？", "What is an Adventure?"),
                        answer = AppStrings.tr(
                            "奇遇任务是 AI 主动向您提问的互动功能。完成初始问卷后解锁，AI 会随机不定时向您推送个性化问题。每次完成奇遇任务可获得 50-200 积分奖励，同时帮助强化您的人格画像，让 AI 更懂你。",
                            "Adventures are interactive prompts initiated by the AI. After completing the initial questionnaire, the AI will occasionally send you personalized questions. Each completed Adventure grants 50–200 points and helps refine your persona profile."
                        )
                    )
                }
                
                item {
                    QACard(
                        index = 3,
                        question = AppStrings.tr("如何与 AI 对话？", "How do I chat with the AI?"),
                        answer = AppStrings.tr(
                            "点击底部导航栏的 AI 图标进入对话界面。您可以：\n• 自由对话，AI 会基于您的人格画像个性化回复\n• 存储重要记忆到链上\n• 检索历史记忆\n每次对话都可能触发人格画像的更新。",
                            "Tap the AI icon in the bottom navigation to open chat. You can:\n• Chat freely — replies are personalized using your persona profile\n• Store important memories on-chain\n• Search your past memories\nChats may trigger persona updates."
                        )
                    )
                }
                
                // 积分与等级
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.Small))
                    Text(
                        text = AppStrings.tr("积分与等级", "Points & tiers"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = AppSpacing.XSmall)
                    )
                }
                
                item {
                    QACard(
                        index = 4,
                        question = AppStrings.tr("如何获得 \$MEMO 积分？", "How do I earn \$MEMO points?"),
                        answer = AppStrings.tr(
                            "获取积分的方式：\n• AI 对话：基础 10 分 + Token 加成（最高 +200）\n• 人格共鸣奖励：根据 AI 评分获得 S/A/B/C 级加成\n• 每日签到：7 天循环奖励（20-150 分）\n• 每日首聊：+30 分\n积分会根据您的等级倍数加成。",
                            "Ways to earn points:\n• AI chat: base 10 + token bonus (up to +200)\n• Persona resonance: S/A/B/C bonuses based on AI scoring\n• Daily check-in: 7-day cycle rewards (20–150)\n• First chat of the day: +30\nPoints are multiplied by your tier multiplier."
                        )
                    )
                }
                
                item {
                    QACard(
                        index = 5,
                        question = AppStrings.tr("如何升级等级？", "How do I upgrade tiers?"),
                        answer = AppStrings.tr(
                            "等级从 Bronze 到 Diamond 共 5 级。升级需要同时满足：\n• 累计足够的 \$MEMO 积分\n• Sovereign Ratio 达标（反映您在生态中的参与深度）\n满足条件后自动升级，等级越高积分倍数越大（最高 5x）。",
                            "There are 5 tiers from Bronze to Diamond. Upgrading requires both:\n• Enough accumulated \$MEMO points\n• A qualifying Sovereign Ratio (reflecting ecosystem participation)\nOnce met, upgrades happen automatically. Higher tiers have larger multipliers (up to 5x)."
                        )
                    )
                }
                
                item {
                    QACard(
                        index = 6,
                        question = AppStrings.tr("什么是 Sovereign Ratio？", "What is Sovereign Ratio?"),
                        answer = AppStrings.tr(
                            "Sovereign Ratio（主权比率）是衡量您在生态参与深度的指标，通过以下方式提升：\n• 持有/质押代币\n• 参与守护者节点\n• TEEPIN 硬件贡献\n比率越高，等级上限越高，收益倍数也越大。",
                            "Sovereign Ratio measures how deeply you participate in the ecosystem. Increase it by:\n• Holding/staking tokens\n• Participating as a Guardian node\n• Contributing TEEPIN hardware\nHigher ratio unlocks higher tier caps and larger multipliers."
                        )
                    )
                }
                
                // 会员与订阅
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.Small))
                    Text(
                        text = AppStrings.tr("会员与订阅", "Membership"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = AppSpacing.XSmall)
                    )
                }
                
                item {
                    QACard(
                        index = 7,
                        question = AppStrings.tr("订阅会员有什么权益？", "What benefits do subscribers get?"),
                        answer = AppStrings.tr(
                            "订阅会员享有：\n• 更高的每月 Token 限额\n• 积分获取加速倍数\n• 身份管理功能（多钱包绑定、记忆合并）\n• 生态质押功能\n• 优先参与空投和活动\n支持月付和年付，年付更优惠。",
                            "Subscribers get:\n• Higher monthly token limits\n• Faster point earning multiplier\n• Identity management (multi-wallet linking, memory merge)\n• Eco staking features\n• Priority access to airdrops and events\nMonthly and yearly plans are supported; yearly is more cost-effective."
                        )
                    )
                }
                
                item {
                    QACard(
                        index = 8,
                        question = AppStrings.tr("如何开通订阅？", "How do I subscribe?"),
                        answer = AppStrings.tr(
                            "在首页点击左上角的会员卡片，或进入\"我的\"页面选择订阅。支持 SOL 和 USDC 支付，所有支付通过 Solana Pay 完成，安全便捷。",
                            "On the home screen, tap the membership card at the top-left, or go to the “Me” page and choose a plan. SOL and USDC are supported. Payments are completed via Solana Pay."
                        )
                    )
                }
                
                // 数据安全
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.Small))
                    Text(
                        text = AppStrings.tr("数据安全", "Security"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = AppSpacing.XSmall)
                    )
                }
                
                item {
                    QACard(
                        index = 9,
                        question = AppStrings.tr("我的数据安全吗？", "Is my data safe?"),
                        answer = AppStrings.tr(
                            "您的数据通过钱包派生密钥进行端对端加密，这是无法关闭的基础安全协议。只有您的钱包才能解密数据，即使是我们的服务器也无法读取您的记忆内容。",
                            "Your data is encrypted end-to-end using keys derived from your wallet. This baseline security cannot be disabled. Only your wallet can decrypt the data — even our servers cannot read your memories."
                        )
                    )
                }
                
                item {
                    QACard(
                        index = 10,
                        question = AppStrings.tr("换设备后数据还在吗？", "Will my data remain if I switch devices?"),
                        answer = AppStrings.tr(
                            "是的！只要在新设备上连接相同的钱包，您的所有记忆数据都会自动恢复。数据存储在去中心化存储（Irys）上，不会因为更换设备或卸载应用而丢失。",
                            "Yes. As long as you connect the same wallet on the new device, your memories will be restored automatically. Data is stored on decentralized storage (Irys), so it won’t be lost when switching devices or uninstalling the app."
                        )
                    )
                }
                
                item {
                    QACard(
                        index = 11,
                        question = AppStrings.tr("什么是 KYC 认证？", "What is KYC verification?"),
                        answer = AppStrings.tr(
                            "KYC（Know Your Customer）认证用于验证您的真实身份。完成认证后您将获得去中心化身份（DID）凭证，可用于：\n• 领取空投奖励\n• 参与高级功能\n• 合作方身份验证",
                            "KYC (Know Your Customer) verifies your real identity. After completing it, you receive a decentralized identity (DID) credential that can be used for:\n• Claiming airdrops\n• Accessing advanced features\n• Partner verification"
                        )
                    )
                }
                
                // 人格画像
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.Small))
                    Text(
                        text = AppStrings.tr("人格画像", "Persona"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = AppSpacing.XSmall)
                    )
                }
                
                item {
                    QACard(
                        index = 12,
                        question = AppStrings.tr("什么是 AI 人格画像？", "What is an AI persona profile?"),
                        answer = AppStrings.tr(
                            "AI 会分析您的对话和记忆内容，建立基于 OCEAN 五大人格特征的画像：\n• 开放性 (Openness)\n• 尽责性 (Conscientiousness)\n• 外向性 (Extraversion)\n• 宜人性 (Agreeableness)\n• 神经质 (Neuroticism)\n在仪表盘可以看到您的人格雷达图。",
                            "The AI analyzes your chats and memories to build an OCEAN-based personality profile:\n• Openness\n• Conscientiousness\n• Extraversion\n• Agreeableness\n• Neuroticism\nYou can see your persona radar chart in the dashboard."
                        )
                    )
                }
                
                item {
                    QACard(
                        index = 13,
                        question = AppStrings.tr("人格画像有什么用？", "What is the persona profile used for?"),
                        answer = AppStrings.tr(
                            "人格画像让 AI 更懂您：\n• 个性化对话回复\n• 精准的奇遇任务问题（完成可获大量积分）\n• 人格共鸣评分和奖励\n• 未来可能用于匹配志同道合的用户\n画像越完善，AI 的回复就越贴合您的性格。",
                            "Your persona profile helps the AI understand you better:\n• Personalized replies\n• More accurate Adventures (with larger point rewards)\n• Persona resonance scoring and rewards\n• Potential future matching with like-minded users\nThe more complete your profile, the better the AI fits your style."
                        )
                    )
                }
            }
        }
    }
}

/**
 * QA 卡片组件 - 现代化设计
 */
@Composable
private fun QACard(
    index: Int,
    question: String,
    answer: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .modernCardShadow(AppElevations.Small, AppShapes.LargeCard),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.XLarge)
        ) {
            // 问题部分
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = RoundedCornerShape(AppCorners.Small),
                    color = AppColors.PrimaryGradientStart.copy(alpha = 0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = AppStrings.trf("Q%d", "Q%d", index),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Text(
                    text = question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            
            // 答案部分
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AppShapes.Card,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.Small),
                        tint = AppColors.WarningGradientStart
                    )
                    Text(
                        text = answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 问卷评估页面
 */
@Composable
fun EvaluationScreen(
    activity: MainActivity,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    
    // 加载评估报告
    var evaluationReport by remember { mutableStateOf<com.soulon.app.onboarding.EvaluationReport?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        try {
            val manager = com.soulon.app.onboarding.OnboardingEvaluationManager(activity)
            evaluationReport = manager.getOverallReport()
            isLoading = false
        } catch (e: Exception) {
            Timber.e(e, "加载评估报告失败")
            isLoading = false
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = com.soulon.app.i18n.AppStrings.back
                )
            }
            Text(
                text = com.soulon.app.i18n.AppStrings.tr("问卷评估", "Questionnaire Evaluation"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        
        HorizontalDivider()
        
        // 内容
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val report = evaluationReport
            
            if (report == null || report.totalQuestions == 0) {
                // 未完成问卷
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("暂无评估数据", "No evaluation data"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("请先完成初始化问卷", "Please complete the setup questionnaire first"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // 显示评估报告
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 整体评估卡片
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = when (report.getReliabilityGrade()) {
                                    "优秀" -> androidx.compose.ui.graphics.Color(0xFF4CAF50).copy(alpha = 0.15f)
                                    "良好" -> androidx.compose.ui.graphics.Color(0xFF2196F3).copy(alpha = 0.15f)
                                    "中等" -> androidx.compose.ui.graphics.Color(0xFFFFC107).copy(alpha = 0.15f)
                                    else -> androidx.compose.ui.graphics.Color(0xFFF44336).copy(alpha = 0.15f)
                                }
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = com.soulon.app.i18n.AppStrings.tr("整体评估", "Overall"),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = report.getReliabilityGrade(),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                LinearProgressIndicator(
                                    progress = { report.overallReliability },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(12.dp),
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    text = "${(report.overallReliability * 100).toInt()}%",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    
                    // 详细指标
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.tr("详细指标", "Metrics"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                EvaluationMetricRow(com.soulon.app.i18n.AppStrings.tr("平均真诚度", "Avg sincerity"), report.averageSincerity)
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                EvaluationMetricRow(com.soulon.app.i18n.AppStrings.tr("平均置信度", "Avg confidence"), report.averageConfidence)
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                EvaluationMetricRow(com.soulon.app.i18n.AppStrings.tr("整体可信度", "Overall reliability"), report.overallReliability)
                            }
                        }
                    }
                    
                    // 答案分布
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.tr("答案分布", "Answer distribution"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    DistributionItem(
                                        label = com.soulon.app.i18n.AppStrings.tr("已验证", "Verified"),
                                        count = report.highReliabilityCount,
                                        color = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                    )
                                    DistributionItem(
                                        label = com.soulon.app.i18n.AppStrings.tr("待验证", "Pending"),
                                        count = report.mediumReliabilityCount,
                                        color = androidx.compose.ui.graphics.Color(0xFFFFC107)
                                    )
                                    DistributionItem(
                                        label = com.soulon.app.i18n.AppStrings.tr("可疑", "Suspicious"),
                                        count = report.lowReliabilityCount,
                                        color = androidx.compose.ui.graphics.Color(0xFFF44336)
                                    )
                                }
                            }
                        }
                    }
                    
                    // 验证统计
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.tr("验证统计", "Verification stats"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = com.soulon.app.i18n.AppStrings.tr("验证次数", "Verifications"),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = com.soulon.app.i18n.AppStrings.trf(
                                            "%d 次",
                                            "%d times",
                                            report.totalVerifications
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = com.soulon.app.i18n.AppStrings.tr("矛盾次数", "Contradictions"),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = com.soulon.app.i18n.AppStrings.trf(
                                            "%d 次",
                                            "%d times",
                                            report.totalContradictions
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color(0xFFF44336)
                                    )
                                }
                            }
                        }
                    }
                    
                    // 影响说明
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.tr("可信度影响", "Impact"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                val bonus = when {
                                    report.overallReliability >= 0.85f -> "+5"
                                    report.overallReliability >= 0.70f -> "+3"
                                    report.overallReliability >= 0.50f -> "+1"
                                    else -> "+0"
                                }
                                
                                val multiplier = 0.8f + (report.overallReliability * 0.4f)
                                val effect = when {
                                    multiplier > 1.0f -> com.soulon.app.i18n.AppStrings.trf(
                                        "更容易升级 (%d%% 减免)",
                                        "Easier to upgrade (%d%% reduction)",
                                        ((multiplier - 1.0f) * 100).toInt()
                                    )
                                    multiplier < 1.0f -> com.soulon.app.i18n.AppStrings.trf(
                                        "更难升级 (%d%% 额外要求)",
                                        "Harder to upgrade (%d%% extra requirement)",
                                        ((1.0f - multiplier) * 100).toInt()
                                    )
                                    else -> com.soulon.app.i18n.AppStrings.tr("标准要求", "Standard requirements")
                                }
                                
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.trf(
                                        "• 每次对话奖励加成：%s MEMO",
                                        "• Chat reward bonus: %s MEMO",
                                        bonus
                                    ),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.trf(
                                        "• 升级要求调整：%s",
                                        "• Upgrade requirement: %s",
                                        effect
                                    ),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.tr(
                                        "• 可信度系数：${String.format("%.2f", multiplier)}x",
                                        "• Reliability multiplier: ${String.format("%.2f", multiplier)}x"
                                    ),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                    
                    // 提示信息
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(AppCorners.Medium)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lightbulb,
                                        contentDescription = null,
                                        modifier = Modifier.size(AppIconSizes.Medium),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = com.soulon.app.i18n.AppStrings.tr("提示", "Tip"),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = com.soulon.app.i18n.AppStrings.tr(
                                        "持续进行真实的对话，可以提升问卷的可信度评分。高可信度用户将获得更多 MEMO 奖励，并更容易升级到更高等级。",
                                        "Keep having honest conversations to improve your questionnaire reliability. Higher reliability earns more MEMO rewards and makes upgrades easier."
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvaluationMetricRow(label: String, value: Float) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { value },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
    }
}

@Composable
private fun DistributionItem(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 设置页面 - 现代化设计
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToEvaluation: () -> Unit = {},
    onNavigateToLanguage: () -> Unit = {},
    currentLanguage: String = "简体中文"
) {
    BackHandler(onBack = onBack)
    
    val context = LocalContext.current
    
    // 对话框状态
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var isClearingCache by remember { mutableStateOf(false) }
    
    // 清除缓存对话框
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { if (!isClearingCache) showClearCacheDialog = false },
            containerColor = Color(0xFF1A1A24),
            title = {
                Text(
                    text = com.soulon.app.i18n.AppStrings.tr("清除缓存", "Clear cache"),
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                if (isClearingCache) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = AppColors.PrimaryGradientStart,
                            strokeWidth = 2.dp
                        )
                        Text(
                            com.soulon.app.i18n.AppStrings.tr("正在清除...", "Clearing..."),
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr(
                            "这将清除应用的临时数据和缓存文件，不会影响您的记忆数据和账户信息。\n\n确定要清除缓存吗？",
                            "This clears temporary data and cache files, without affecting your memories or account.\n\nClear cache now?"
                        ),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                if (!isClearingCache) {
                    TextButton(
                        onClick = {
                            isClearingCache = true
                            // 清除缓存
                            try {
                                context.cacheDir.deleteRecursively()
                                context.externalCacheDir?.deleteRecursively()
                            } catch (e: Exception) {
                                timber.log.Timber.e(e, "清除缓存失败")
                            }
                            isClearingCache = false
                            showClearCacheDialog = false
                        }
                    ) {
                        Text(com.soulon.app.i18n.AppStrings.tr("确定", "Confirm"), color = AppColors.WarningGradientStart)
                    }
                }
            },
            dismissButton = {
                if (!isClearingCache) {
                    TextButton(onClick = { showClearCacheDialog = false }) {
                        Text(com.soulon.app.i18n.AppStrings.cancel, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        )
    }
    
    // 隐私政策对话框
    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            containerColor = Color(0xFF1A1A24),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Policy,
                        contentDescription = null,
                        tint = AppColors.PrimaryGradientStart,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("隐私政策", "Privacy Policy"),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
                ) {
                    PrivacySection(
                        com.soulon.app.i18n.AppStrings.tr("数据收集", "Data collection"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "我们收集您提供的记忆数据用于个性化 AI 服务。所有数据均经过加密存储，只有您能够访问。",
                            "We collect the memory data you provide to deliver personalized AI services. All data is stored encrypted and only you can access it."
                        )
                    )
                    PrivacySection(
                        com.soulon.app.i18n.AppStrings.tr("数据存储", "Data storage"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "记忆数据通过 Irys 永久存储在 Arweave 网络，采用 AES-GCM-256 加密，密钥由 Android KeyStore 保护。",
                            "Memory data is permanently stored on the Arweave network via Irys, encrypted with AES-GCM-256. Keys are protected by Android KeyStore."
                        )
                    )
                    PrivacySection(
                        com.soulon.app.i18n.AppStrings.tr("数据使用", "Data use"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "您的数据仅用于提供个性化 AI 对话服务，不会用于广告或分享给第三方。",
                            "Your data is used only to provide personalized AI chat services. It is not used for advertising or shared with third parties."
                        )
                    )
                    PrivacySection(
                        com.soulon.app.i18n.AppStrings.tr("数据删除", "Data deletion"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "您可以随时删除本地数据。由于 Arweave 的特性，链上数据无法删除，但加密确保其不可读。",
                            "You can delete local data at any time. Due to Arweave’s nature, on-chain data cannot be deleted, but encryption keeps it unreadable."
                        )
                    )
                    PrivacySection(
                        com.soulon.app.i18n.AppStrings.tr("钱包信息", "Wallet information"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "我们不存储您的钱包私钥。所有签名操作通过 Mobile Wallet Adapter 在您的钱包应用中完成。",
                            "We do not store your wallet private keys. All signing operations are completed in your wallet app via Mobile Wallet Adapter."
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text(com.soulon.app.i18n.AppStrings.tr("我知道了", "Got it"), color = AppColors.PrimaryGradientStart)
                }
            }
        )
    }
    
    // 用户协议对话框
    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            containerColor = Color(0xFF1A1A24),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = AppColors.SecondaryGradientStart,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("用户协议", "Terms of Service"),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
                ) {
                    TermsSection(
                        com.soulon.app.i18n.AppStrings.tr("服务说明", "Service description"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "Soulon 是一款基于 Solana 区块链的长期记忆 AI 助手应用，提供记忆存储、检索和个性化对话服务。",
                            "Soulon is a long-term memory AI assistant built on Solana, providing memory storage, retrieval, and personalized chat services."
                        )
                    )
                    TermsSection(
                        com.soulon.app.i18n.AppStrings.tr("使用条款", "Terms of use"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "使用本应用即表示您同意以下条款：\n• 您对提交的内容负责\n• 不得滥用服务或进行违法活动\n• 遵守相关法律法规",
                            "By using this app, you agree that:\n• You are responsible for content you submit\n• You will not abuse the service or engage in illegal activities\n• You will comply with applicable laws and regulations"
                        )
                    )
                    TermsSection(
                        com.soulon.app.i18n.AppStrings.tr("区块链交互", "Blockchain interactions"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "部分功能涉及 Solana 区块链交互，可能产生网络费用。请确保您了解区块链操作的不可逆性。",
                            "Some features interact with the Solana blockchain and may incur network fees. Please understand that blockchain operations can be irreversible."
                        )
                    )
                    TermsSection(
                        com.soulon.app.i18n.AppStrings.tr("免责声明", "Disclaimer"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "本应用按\"现状\"提供，不保证服务的持续可用性。对于因使用本应用造成的任何损失，我们不承担责任。",
                            "This app is provided “as is” without guarantees of continuous availability. We are not liable for any losses arising from the use of this app."
                        )
                    )
                    TermsSection(
                        com.soulon.app.i18n.AppStrings.tr("更新与变更", "Updates and changes"),
                        com.soulon.app.i18n.AppStrings.tr(
                            "我们可能会不时更新本协议。继续使用服务即表示接受更新后的条款。",
                            "We may update these terms from time to time. Continued use of the service indicates acceptance of the updated terms."
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTermsDialog = false }) {
                    Text(com.soulon.app.i18n.AppStrings.tr("我知道了", "Got it"), color = AppColors.PrimaryGradientStart)
                }
            }
        )
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 现代化 Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = com.soulon.app.i18n.AppStrings.profileSettings,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // 设置项列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
            ) {
                // 个人档案分组
                item {
                    SettingsGroupHeader(
                        icon = Icons.Rounded.Person,
                        title = AppStrings.tr("个人档案", "Profile"),
                        color = AppColors.PrimaryGradientStart
                    )
                }
                
                item {
                    SettingsItemCard(
                        icon = Icons.Rounded.Quiz,
                        title = AppStrings.tr("问卷评估", "Questionnaire"),
                        subtitle = AppStrings.tr("查看问卷可信度和评估详情", "View reliability and evaluation details"),
                        iconColor = AppColors.PrimaryGradientStart,
                        onClick = onNavigateToEvaluation
                    )
                }
                
                // 通用设置分组
                item {
                    SettingsGroupHeader(
                        icon = Icons.Rounded.Tune,
                        title = AppStrings.tr("通用", "General"),
                        color = AppColors.SecondaryGradientStart
                    )
                }
                
                item {
                    SettingsItemCard(
                        icon = Icons.Rounded.Language,
                        title = com.soulon.app.i18n.AppStrings.settingsLanguage,
                        subtitle = currentLanguage,
                        iconColor = AppColors.SecondaryGradientStart,
                        onClick = onNavigateToLanguage
                    )
                }
                
                // 数据设置分组
                item {
                    SettingsGroupHeader(
                        icon = Icons.Rounded.Storage,
                        title = AppStrings.tr("数据", "Data"),
                        color = AppColors.SuccessGradientStart
                    )
                }
                
                item {
                    SettingsItemCard(
                        icon = Icons.Rounded.DeleteSweep,
                        title = com.soulon.app.i18n.AppStrings.settingsClearCache,
                        subtitle = AppStrings.tr("清除临时数据", "Clear temporary data"),
                        iconColor = AppColors.WarningGradientStart,
                        onClick = { showClearCacheDialog = true }
                    )
                }
                
                // 隐私设置分组
                item {
                    SettingsGroupHeader(
                        icon = Icons.Rounded.Security,
                        title = AppStrings.tr("隐私", "Privacy"),
                        color = AppColors.ErrorGradientStart
                    )
                }
                
                item {
                    SettingsItemCard(
                        icon = Icons.Rounded.Policy,
                        title = com.soulon.app.i18n.AppStrings.settingsPrivacyPolicy,
                        subtitle = AppStrings.tr("查看隐私政策", "View privacy policy"),
                        iconColor = AppColors.TextSecondary,
                        onClick = { showPrivacyDialog = true }
                    )
                }
                
                item {
                    SettingsItemCard(
                        icon = Icons.Rounded.Description,
                        title = com.soulon.app.i18n.AppStrings.settingsTerms,
                        subtitle = AppStrings.tr("查看用户协议", "View terms"),
                        iconColor = AppColors.TextSecondary,
                        onClick = { showTermsDialog = true }
                    )
                }
            }
        }
    }
}

/**
 * 隐私政策章节
 */
@Composable
private fun PrivacySection(title: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = AppColors.PrimaryGradientStart
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

/**
 * 用户协议章节
 */
@Composable
private fun TermsSection(title: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = AppColors.SecondaryGradientStart
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

/**
 * 设置分组标题 - 现代化设计
 */
@Composable
private fun SettingsGroupHeader(
    icon: ImageVector,
    title: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(AppCorners.XSmall),
            color = color.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(AppIconSizes.Small),
                    tint = color
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/**
 * 设置项卡片 - 现代化设计
 */
@Composable
private fun SettingsItemCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .modernCardShadow(AppElevations.Small, AppShapes.Card)
            .clickable(onClick = onClick),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Large),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标容器
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(AppCorners.Medium),
                color = iconColor.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.Medium),
                        tint = iconColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(AppSpacing.Medium))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(AppSpacing.XXSmall))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
            
            Surface(
                modifier = Modifier.size(28.dp),
                shape = RoundedCornerShape(AppCorners.XSmall),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.Small),
                        tint = AppColors.TextTertiary
                    )
                }
            }
        }
    }
}

// =============================================
// 新页面：安全、通知、Bug报告、联系我们、KYC认证
// =============================================

/**
 * 安全页面 - 深色现代化设计
 */
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    onNavigateToKYC: () -> Unit,
    onNavigateToDID: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    
    // 钱包加密说明弹窗状态
    var showEncryptionDialog by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = com.soulon.app.i18n.AppStrings.tr("安全", "Security"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                // 钱包安全
                item {
                    SecurityMenuCard(
                        icon = Icons.Rounded.Key,
                        title = com.soulon.app.i18n.AppStrings.tr("钱包加密", "Wallet encryption"),
                        subtitle = com.soulon.app.i18n.AppStrings.tr("您的数据已通过钱包密钥加密保护", "Your data is encrypted with your wallet key"),
                        iconColor = AppColors.SuccessGradientStart,
                        badge = com.soulon.app.i18n.AppStrings.tr("已启用", "Enabled"),
                        onClick = { showEncryptionDialog = true }
                    )
                }
                
                // 说明卡片
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.Card,
                        color = Color.White.copy(alpha = 0.03f)
                    ) {
                        Row(
                            modifier = Modifier.padding(AppSpacing.Medium),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.Small))
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr(
                                    "完成 KYC 认证后，您将获得去中心化身份（DID）凭证，可用于合作方的身份验证和权益领取。身份管理功能为订阅会员专属，支持绑定多个钱包并合并记忆。",
                                    "After completing KYC, you will receive a decentralized identity (DID) credential for partner verification and benefit claims. Identity management is for subscribers, supports multiple wallets and memory merging."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
        
        // 钱包加密说明 Toast - 自动消失
        if (showEncryptionDialog) {
            // 2秒后自动消失
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2000)
                showEncryptionDialog = false
            }
            
            // 底部弹出的 Toast 样式提示
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 100.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1A1A2E),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = AppColors.SuccessGradientStart.copy(alpha = 0.15f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Shield,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = AppColors.SuccessGradientStart
                                )
                            }
                        }
                        Column {
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("基础安全协议已启用", "Basic security enabled"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr(
                                    "钱包加密保护您的数据，无法关闭",
                                    "Wallet encryption protects your data and cannot be disabled"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SecurityMenuCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    iconColor: Color,
    badge: String? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AppShapes.Card,
        color = Color.White.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(AppCorners.Medium),
                color = iconColor.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(AppSpacing.Medium))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(AppSpacing.Small))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = AppColors.SuccessGradientStart.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = badge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.SuccessGradientStart,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * 通知设置页面 - 深色现代化设计
 * 使用 SharedPreferences 持久化通知设置
 */
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("notification_settings", android.content.Context.MODE_PRIVATE) }
    var pushEnabledPref by remember { mutableStateOf(prefs.getBoolean("push_enabled", true)) }
    val needsRuntimePermission = remember {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val computeNotificationEnabled = remember(needsRuntimePermission) {
        {
            val systemEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (!systemEnabled) {
                false
            } else if (!needsRuntimePermission) {
                true
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }
    var hasPermission by remember {
        mutableStateOf(computeNotificationEnabled())
    }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var pendingEnablePush by remember { mutableStateOf(false) }
    val systemNotificationsEnabled = remember {
        {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = computeNotificationEnabled()
            if (granted && pendingEnablePush) {
                pendingEnablePush = false
                pushEnabledPref = true
                prefs.edit().putBoolean("push_enabled", true).apply()
            } else if (!granted) {
                pendingEnablePush = false
                pushEnabledPref = false
                prefs.edit().putBoolean("push_enabled", false).apply()
                showPermissionDialog = true
            }
        }
    )
    var askedPostNotifications by remember { mutableStateOf(prefs.getBoolean("asked_post_notifications", false)) }

    DisposableEffect(lifecycleOwner, needsRuntimePermission) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = computeNotificationEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    // 从 SharedPreferences 读取设置
    var chatNotification by remember { mutableStateOf(prefs.getBoolean("chat_notification", true)) }
    var rewardNotification by remember { mutableStateOf(prefs.getBoolean("reward_notification", true)) }
    var adventureNotification by remember { mutableStateOf(prefs.getBoolean("adventure_notification", true)) }
    var dailyReminder by remember { mutableStateOf(prefs.getBoolean("daily_reminder", false)) }
    var systemNotification by remember { mutableStateOf(prefs.getBoolean("system_notification", false)) }
    var soundEnabled by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
    var vibrationEnabled by remember { mutableStateOf(prefs.getBoolean("vibration_enabled", true)) }

    val pushEnabled = pushEnabledPref && hasPermission
    
    // 保存设置的辅助函数
    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = com.soulon.app.i18n.AppStrings.notificationsTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                // 主开关
                item {
                    NotificationSwitchCard(
                        title = com.soulon.app.i18n.AppStrings.notificationsPush,
                        subtitle = com.soulon.app.i18n.AppStrings.notificationsPushDesc,
                        checked = pushEnabled,
                        onCheckedChange = { 
                            if (!it) {
                                pendingEnablePush = false
                                pushEnabledPref = false
                                saveBoolean("push_enabled", false)
                                return@NotificationSwitchCard
                            }

                            if (!systemNotificationsEnabled()) {
                                pendingEnablePush = false
                                pushEnabledPref = false
                                saveBoolean("push_enabled", false)
                                showPermissionDialog = true
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                }
                                return@NotificationSwitchCard
                            }

                            if (needsRuntimePermission && !hasPermission) {
                                pendingEnablePush = true
                                val activity = context as? android.app.Activity
                                val shouldShow = activity?.let {
                                    androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                        it,
                                        android.Manifest.permission.POST_NOTIFICATIONS
                                    )
                                } ?: true
                                if (askedPostNotifications && !shouldShow) {
                                    showPermissionDialog = true
                                    try {
                                        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                    }
                                    pendingEnablePush = false
                                    pushEnabledPref = false
                                    saveBoolean("push_enabled", false)
                                    return@NotificationSwitchCard
                                }
                                askedPostNotifications = true
                                prefs.edit().putBoolean("asked_post_notifications", true).apply()
                                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                return@NotificationSwitchCard
                            }

                            pendingEnablePush = false
                            pushEnabledPref = true
                            saveBoolean("push_enabled", true)
                        }
                    )
                }
                
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.Small))
                    Text(
                        text = com.soulon.app.i18n.AppStrings.notificationsMessageTypes,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = AppSpacing.XSmall)
                    )
                }
                
                // 奇遇任务通知
                item {
                    NotificationSwitchCard(
                        title = com.soulon.app.i18n.AppStrings.notificationsAdventure,
                        subtitle = com.soulon.app.i18n.AppStrings.notificationsAdventureDesc,
                        checked = adventureNotification && pushEnabled,
                        enabled = pushEnabled && hasPermission,
                        onCheckedChange = { 
                            adventureNotification = it
                            saveBoolean("adventure_notification", it)
                        }
                    )
                }
                
                // AI 对话通知
                item {
                    NotificationSwitchCard(
                        title = com.soulon.app.i18n.AppStrings.notificationsAiChat,
                        subtitle = com.soulon.app.i18n.AppStrings.notificationsAiChatDesc,
                        checked = chatNotification && pushEnabled,
                        enabled = pushEnabled && hasPermission,
                        onCheckedChange = { 
                            chatNotification = it
                            saveBoolean("chat_notification", it)
                        }
                    )
                }
                
                // 奖励通知
                item {
                    NotificationSwitchCard(
                        title = com.soulon.app.i18n.AppStrings.notificationsRewards,
                        subtitle = com.soulon.app.i18n.AppStrings.notificationsRewardsDesc,
                        checked = rewardNotification && pushEnabled,
                        enabled = pushEnabled && hasPermission,
                        onCheckedChange = { 
                            rewardNotification = it
                            saveBoolean("reward_notification", it)
                        }
                    )
                }
                
                // 每日提醒
                item {
                    NotificationSwitchCard(
                        title = com.soulon.app.i18n.AppStrings.notificationsDailyReminder,
                        subtitle = com.soulon.app.i18n.AppStrings.notificationsDailyReminderDesc,
                        checked = dailyReminder && pushEnabled,
                        enabled = pushEnabled && hasPermission,
                        onCheckedChange = { 
                            dailyReminder = it
                            saveBoolean("daily_reminder", it)
                        }
                    )
                }
                
                // 系统通知
                item {
                    NotificationSwitchCard(
                        title = com.soulon.app.i18n.AppStrings.notificationsSystem,
                        subtitle = com.soulon.app.i18n.AppStrings.notificationsSystemDesc,
                        checked = systemNotification && pushEnabled,
                        enabled = pushEnabled && hasPermission,
                        onCheckedChange = { 
                            systemNotification = it
                            saveBoolean("system_notification", it)
                        }
                    )
                }
                
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    Text(
                        text = com.soulon.app.i18n.AppStrings.notificationsDeliveryMethods,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = AppSpacing.XSmall)
                    )
                }
                
                // 声音
                item {
                    NotificationSwitchCard(
                        title = com.soulon.app.i18n.AppStrings.notificationsSound,
                        subtitle = com.soulon.app.i18n.AppStrings.notificationsSoundDesc,
                        checked = soundEnabled && pushEnabled,
                        enabled = pushEnabled && hasPermission,
                        onCheckedChange = { 
                            soundEnabled = it
                            saveBoolean("sound_enabled", it)
                        }
                    )
                }
                
                // 振动
                item {
                    NotificationSwitchCard(
                        title = com.soulon.app.i18n.AppStrings.notificationsVibration,
                        subtitle = com.soulon.app.i18n.AppStrings.notificationsVibrationDesc,
                        checked = vibrationEnabled && pushEnabled,
                        enabled = pushEnabled && hasPermission,
                        onCheckedChange = { 
                            vibrationEnabled = it
                            saveBoolean("vibration_enabled", it)
                        }
                    )
                }
                
                // 免打扰说明
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.Card,
                        color = Color.White.copy(alpha = 0.03f)
                    ) {
                        Row(
                            modifier = Modifier.padding(AppSpacing.Medium),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.Small))
                            Text(
                                text = com.soulon.app.i18n.AppStrings.notificationsInfoText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }

        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = {
                    Text(
                        text = com.soulon.app.i18n.AppStrings.notificationsTitle,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(com.soulon.app.i18n.AppStrings.notificationsPermissionRequired)
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showPermissionDialog = false
                            val pkg = context.packageName
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:$pkg")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        }
                    ) {
                        Text(com.soulon.app.i18n.AppStrings.notificationsPermissionGoSettings, color = AppColors.PrimaryGradientStart)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionDialog = false }) {
                        Text(com.soulon.app.i18n.AppStrings.cancel, color = Color.White.copy(alpha = 0.8f))
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun NotificationSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = Color.White.copy(alpha = if (enabled) 0.05f else 0.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = if (enabled) 0.6f else 0.3f)
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AppColors.PrimaryGradientStart,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
                    disabledCheckedThumbColor = Color.White.copy(alpha = 0.5f),
                    disabledCheckedTrackColor = AppColors.PrimaryGradientStart.copy(alpha = 0.3f),
                    disabledUncheckedThumbColor = Color.White.copy(alpha = 0.3f),
                    disabledUncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                )
            )
        }
    }
}

/**
 * Bug 报告页面 - 深色现代化设计
 */
@Composable
fun BugReportScreen(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val walletPrefs = remember { context.getSharedPreferences("wallet_prefs", android.content.Context.MODE_PRIVATE) }
    val currentWalletAddress = remember { walletPrefs.getString("connected_wallet", null) }
    var bugDescription by remember { mutableStateOf("") }
    var contactEmail by remember { mutableStateOf("") }
    var includeDeviceInfo by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var contactEmailTouched by remember { mutableStateOf(false) }
    var descriptionTouched by remember { mutableStateOf(false) }
    val isContactEmailValid = remember(contactEmail) {
        android.util.Patterns.EMAIL_ADDRESS.matcher(contactEmail.trim()).matches()
    }
    val isDescriptionValid = remember(bugDescription) { bugDescription.trim().length >= 10 }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = com.soulon.app.i18n.AppStrings.tr("Bug 报告", "Bug Report"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
            ) {
                // 奖励说明
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Card,
                    color = Color(0xFF14F195).copy(alpha = 0.08f)
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.Medium)) {
                        Text(
                            text = AppStrings.tr("贡献度奖励", "Contribution rewards"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                        Text(
                            text = AppStrings.tr(
                                "提交 Bug 后，我们会评估严重程度并发放贡献度奖励。\n高质量报告还有机会获得“技术专家”等级。",
                                "After you submit a bug, we’ll assess severity and grant contribution rewards.\nHigh-quality reports may grant a “Technical Expert” tier."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                            lineHeight = 18.sp
                        )
                    }
                }

                // 问题描述
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Card,
                    color = Color.White.copy(alpha = 0.05f)
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.Medium)) {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("问题描述", "Issue description"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Small))
                        OutlinedTextField(
                            value = bugDescription,
                            onValueChange = {
                                bugDescription = it
                                if (!descriptionTouched) descriptionTouched = true
                            },
                            placeholder = {
                                Text(
                                    com.soulon.app.i18n.AppStrings.tr("请详细描述您遇到的问题...", "Describe the issue in detail..."),
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            },
                            isError = descriptionTouched && !isDescriptionValid,
                            supportingText = {
                                if (descriptionTouched && !isDescriptionValid) {
                                    Text(
                                        AppStrings.tr("请至少填写 10 个字符", "Please enter at least 10 characters"),
                                        color = Color(0xFFFFB4AB)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            minLines = 5,
                            maxLines = 10,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AppColors.PrimaryGradientStart,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                cursorColor = AppColors.PrimaryGradientStart
                            )
                        )
                    }
                }
                
                // 联系方式（可选）
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Card,
                    color = Color.White.copy(alpha = 0.05f)
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.Medium)) {
                        Text(
                            text = com.soulon.app.i18n.AppStrings.tr("联系邮箱", "Contact email"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Small))
                        OutlinedTextField(
                            value = contactEmail,
                            onValueChange = {
                                contactEmail = it
                                if (!contactEmailTouched) contactEmailTouched = true
                            },
                            placeholder = {
                                Text(
                                    com.soulon.app.i18n.AppStrings.tr("请输入邮箱", "Enter your email"),
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = contactEmailTouched && !isContactEmailValid,
                            supportingText = {
                                if (contactEmailTouched && !isContactEmailValid) {
                                    Text(
                                        AppStrings.tr("邮箱格式不正确", "Invalid email format"),
                                        color = Color(0xFFFFB4AB)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AppColors.PrimaryGradientStart,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                cursorColor = AppColors.PrimaryGradientStart
                            )
                        )
                    }
                }
                
                // 包含设备信息
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Card,
                    color = Color.White.copy(alpha = 0.05f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("包含设备信息", "Include device info"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = com.soulon.app.i18n.AppStrings.tr("帮助我们更快定位问题", "Helps us troubleshoot faster"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = includeDeviceInfo,
                            onCheckedChange = { includeDeviceInfo = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AppColors.PrimaryGradientStart,
                                uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // 提交按钮
                Button(
                    onClick = {
                        if (isSubmitting) return@Button
                        isSubmitting = true
                        errorMessage = ""

                        val appVersion = com.soulon.app.BuildConfig.VERSION_NAME + " (" + com.soulon.app.BuildConfig.VERSION_CODE + ")"
                        val deviceInfo = if (includeDeviceInfo) {
                            com.soulon.app.support.DeviceInfoCollector.collect(context, appVersion)
                        } else null

                        scope.launch {
                            contactEmailTouched = true
                            descriptionTouched = true
                            if (!isContactEmailValid || !isDescriptionValid) {
                                errorMessage = AppStrings.tr(
                                    "请检查填写内容后再提交。",
                                    "Please check your input before submitting."
                                )
                                showErrorDialog = true
                                isSubmitting = false
                                return@launch
                            }

                            val ok = com.soulon.app.data.BackendApiClient.getInstance(context).submitBugReport(
                                description = bugDescription,
                                contactEmail = contactEmail.trim(),
                                walletAddress = currentWalletAddress,
                                includeDeviceInfo = includeDeviceInfo,
                                deviceInfo = deviceInfo,
                                appVersion = appVersion
                            )

                            if (ok) {
                                showSuccessDialog = true
                                isSubmitting = false
                                return@launch
                            }
                            errorMessage = AppStrings.tr(
                                "提交失败，请检查网络后重试。",
                                "Submit failed. Please check your network and try again."
                            )
                            showErrorDialog = true
                            isSubmitting = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = isDescriptionValid && isContactEmailValid && !isSubmitting,
                    shape = AppShapes.Button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.PrimaryGradientStart,
                        disabledContainerColor = AppColors.PrimaryGradientStart.copy(alpha = 0.3f)
                    )
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Small))
                        Text(com.soulon.app.i18n.AppStrings.tr("提交报告", "Submit report"), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        
        // 成功对话框
        if (showSuccessDialog) {
            AlertDialog(
                onDismissRequest = { 
                    showSuccessDialog = false
                    onBack()
                },
                title = { 
                    Text(
                        com.soulon.app.i18n.AppStrings.tr("感谢反馈", "Thanks for your feedback"),
                        fontWeight = FontWeight.Bold
                    ) 
                },
                text = { 
                    Text(
                        AppStrings.tr(
                            "Bug 报告已提交，我们会尽快处理。",
                            "Your bug report has been submitted. We’ll look into it soon."
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = { 
                        showSuccessDialog = false
                        onBack()
                    }) {
                        Text(com.soulon.app.i18n.AppStrings.tr("确定", "OK"), color = AppColors.PrimaryGradientStart)
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }
        
        // 错误对话框
        if (showErrorDialog) {
            AlertDialog(
                onDismissRequest = { showErrorDialog = false },
                title = { 
                    Text(
                        com.soulon.app.i18n.AppStrings.tr("提交失败", "Submit failed"),
                        fontWeight = FontWeight.Bold
                    ) 
                },
                text = { 
                    Text(
                        if (errorMessage.isNotBlank()) {
                            errorMessage
                        } else {
                            AppStrings.tr(
                                "提交失败，请稍后重试。",
                                "Submit failed. Please try again later."
                            )
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showErrorDialog = false }) {
                        Text(com.soulon.app.i18n.AppStrings.tr("确定", "OK"), color = AppColors.PrimaryGradientStart)
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * 联系我们页面 - 深色现代化设计
 */
@Composable
fun ContactUsScreen(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    
    val context = LocalContext.current
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = AppStrings.tr("联系我们", "Contact us"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                // 电子邮件
                item {
                    ContactCard(
                        icon = Icons.Rounded.Email,
                        title = AppStrings.tr("电子邮件", "Email"),
                        value = "support@memoryai.app",
                        iconColor = AppColors.PrimaryGradientStart,
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                data = android.net.Uri.parse("mailto:support@memoryai.app")
                            }
                            context.startActivity(intent)
                        }
                    )
                }
                
                // Twitter/X
                item {
                    ContactCard(
                        icon = Icons.Rounded.Tag,
                        title = AppStrings.tr("Twitter / X", "Twitter / X"),
                        value = "@MemoryAI_App",
                        iconColor = Color(0xFF1DA1F2),
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                data = android.net.Uri.parse("https://twitter.com/MemoryAI_App")
                            }
                            context.startActivity(intent)
                        }
                    )
                }
                
                // Discord
                item {
                    ContactCard(
                        icon = Icons.Rounded.Forum,
                        title = AppStrings.tr("Discord", "Discord"),
                        value = AppStrings.tr("加入社区", "Join the community"),
                        iconColor = Color(0xFF5865F2),
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                data = android.net.Uri.parse("https://discord.gg/memoryai")
                            }
                            context.startActivity(intent)
                        }
                    )
                }
                
                // Telegram
                item {
                    ContactCard(
                        icon = Icons.Rounded.Send,
                        title = AppStrings.tr("Telegram", "Telegram"),
                        value = "@MemoryAI_Official",
                        iconColor = Color(0xFF0088CC),
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                data = android.net.Uri.parse("https://t.me/MemoryAI_Official")
                            }
                            context.startActivity(intent)
                        }
                    )
                }
                
                // 说明
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.Card,
                        color = Color.White.copy(alpha = 0.03f)
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.Medium)) {
                            Text(
                                text = AppStrings.tr("工作时间", "Hours"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                            Text(
                                text = AppStrings.tr(
                                    "周一至周五 9:00 - 18:00 (UTC+8)\n通常在 24 小时内回复",
                                    "Mon–Fri 9:00–18:00 (UTC+8)\nWe typically reply within 24 hours"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    iconColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AppShapes.Card,
        color = Color.White.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(AppCorners.Medium),
                color = iconColor.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = iconColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(AppSpacing.Medium))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * KYC 认证页面 - 深色现代化设计
 * 
 * 开发计划：
 * 1. 用户填写基本信息（姓名、国籍、出生日期）
 * 2. 上传身份证件照片（正面/背面）
 * 3. 人脸识别活体检测
 * 4. 提交验证等待审核
 * 5. 审核通过后发放 DID 凭证空投
 * 
 * DID 凭证用途：
 * - 去中心化身份验证
 * - 合作方权益领取
 * - 高级功能解锁
 */
@Composable
fun KYCVerificationScreen(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    
    var kycStatus by remember { mutableStateOf(KYCStatus.NOT_STARTED) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = com.soulon.app.i18n.AppStrings.tr("KYC 认证", "KYC Verification"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            when (kycStatus) {
                KYCStatus.NOT_STARTED -> {
                    KYCIntroView(
                        onStartKYC = { kycStatus = KYCStatus.IN_PROGRESS }
                    )
                }
                KYCStatus.IN_PROGRESS -> {
                    KYCFormView(
                        onSubmit = { kycStatus = KYCStatus.PENDING_REVIEW }
                    )
                }
                KYCStatus.PENDING_REVIEW -> {
                    KYCPendingView()
                }
                KYCStatus.APPROVED -> {
                    KYCApprovedView()
                }
                KYCStatus.REJECTED -> {
                    KYCRejectedView(
                        onRetry = { kycStatus = KYCStatus.IN_PROGRESS }
                    )
                }
            }
        }
    }
}

private enum class KYCStatus {
    NOT_STARTED,
    IN_PROGRESS,
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}

@Composable
private fun KYCIntroView(
    onStartKYC: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
    ) {
        // 顶部图标
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(100.dp),
                shape = RoundedCornerShape(50.dp),
                color = AppColors.PrimaryGradientEnd.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        modifier = Modifier.size(50.dp),
                        tint = AppColors.PrimaryGradientEnd
                    )
                }
            }
        }
        
        Text(
            text = com.soulon.app.i18n.AppStrings.tr("验证您的身份", "Verify your identity"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        
        Text(
            text = com.soulon.app.i18n.AppStrings.tr(
                "完成 KYC 认证后，您将获得专属的去中心化身份（DID）凭证",
                "After KYC, you will receive a decentralized identity (DID) credential"
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        
        // 权益说明
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.Card,
            color = AppColors.SuccessGradientStart.copy(alpha = 0.1f)
        ) {
            Column(modifier = Modifier.padding(AppSpacing.Medium)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CardGiftcard,
                        contentDescription = null,
                        tint = AppColors.SuccessGradientStart,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Small))
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("认证奖励", "Rewards"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                
                KYCBenefitItem(text = com.soulon.app.i18n.AppStrings.tr("免费获得 DID 凭证空投", "Receive a DID credential airdrop"))
                KYCBenefitItem(text = com.soulon.app.i18n.AppStrings.tr("解锁合作方专属权益", "Unlock partner benefits"))
                KYCBenefitItem(text = com.soulon.app.i18n.AppStrings.tr("获得高级功能访问权限", "Access advanced features"))
                KYCBenefitItem(text = com.soulon.app.i18n.AppStrings.tr("参与独家活动资格", "Eligibility for exclusive events"))
            }
        }
        
        // 步骤说明
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.Card,
            color = Color.White.copy(alpha = 0.05f)
        ) {
            Column(modifier = Modifier.padding(AppSpacing.Medium)) {
                Text(
                    text = com.soulon.app.i18n.AppStrings.tr("认证步骤", "Steps"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                
                KYCStepItem(number = "1", text = com.soulon.app.i18n.AppStrings.tr("填写基本信息", "Fill basic info"))
                KYCStepItem(number = "2", text = com.soulon.app.i18n.AppStrings.tr("上传身份证件", "Upload ID document"))
                KYCStepItem(number = "3", text = com.soulon.app.i18n.AppStrings.tr("完成人脸验证", "Complete face verification"))
                KYCStepItem(number = "4", text = com.soulon.app.i18n.AppStrings.tr("等待审核（约 1-3 个工作日）", "Wait for review (~1–3 business days)"))
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // 开始按钮
        Button(
            onClick = onStartKYC,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = AppShapes.Button,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                AppColors.PrimaryGradientStart,
                                AppColors.PrimaryGradientEnd
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Start,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Small))
                    Text(
                        text = com.soulon.app.i18n.AppStrings.tr("开始认证", "Start"),
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
        
        // 隐私说明
        Text(
            text = com.soulon.app.i18n.AppStrings.tr(
                "您的信息将被加密保护，仅用于身份验证",
                "Your information is encrypted and used only for verification"
            ),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppSpacing.Small)
        )
    }
}

@Composable
private fun KYCBenefitItem(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = AppColors.SuccessGradientStart,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(AppSpacing.Small))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun KYCStepItem(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = RoundedCornerShape(12.dp),
            color = AppColors.PrimaryGradientStart.copy(alpha = 0.2f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.PrimaryGradientStart
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.Small))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun KYCFormView(
    onSubmit: () -> Unit
) {
    var fullName by remember { mutableStateOf("") }
    var nationality by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
    ) {
        // 进度指示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            KYCProgressStep(number = "1", label = AppStrings.tr("基本信息", "Info"), isActive = true, isCompleted = false)
            KYCProgressStep(number = "2", label = AppStrings.tr("证件上传", "ID"), isActive = false, isCompleted = false)
            KYCProgressStep(number = "3", label = AppStrings.tr("人脸验证", "Face"), isActive = false, isCompleted = false)
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.Small))
        
        // 表单
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.Card,
            color = Color.White.copy(alpha = 0.05f)
        ) {
            Column(modifier = Modifier.padding(AppSpacing.Medium)) {
                Text(
                    text = AppStrings.tr("基本信息", "Basic information"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text(AppStrings.tr("姓名（与证件一致）", "Full name (as on ID)"), color = Color.White.copy(alpha = 0.7f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AppColors.PrimaryGradientStart,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        cursorColor = AppColors.PrimaryGradientStart
                    )
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                
                OutlinedTextField(
                    value = nationality,
                    onValueChange = { nationality = it },
                    label = { Text(AppStrings.tr("国籍", "Nationality"), color = Color.White.copy(alpha = 0.7f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AppColors.PrimaryGradientStart,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        cursorColor = AppColors.PrimaryGradientStart
                    )
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                
                OutlinedTextField(
                    value = birthDate,
                    onValueChange = { birthDate = it },
                    label = { Text(AppStrings.tr("出生日期（YYYY-MM-DD）", "Date of birth (YYYY-MM-DD)"), color = Color.White.copy(alpha = 0.7f)) },
                    placeholder = { Text(AppStrings.tr("例如：1990-01-01", "e.g. 1990-01-01"), color = Color.White.copy(alpha = 0.4f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AppColors.PrimaryGradientStart,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        cursorColor = AppColors.PrimaryGradientStart
                    )
                )
            }
        }
        
        // 隐私提示
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.Card,
            color = Color.White.copy(alpha = 0.03f)
        ) {
            Row(
                modifier = Modifier.padding(AppSpacing.Medium),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.Small))
                Text(
                    text = AppStrings.tr(
                        "您的个人信息将使用端到端加密存储，仅用于身份验证目的，不会与第三方共享。",
                        "Your personal information is stored with end-to-end encryption, used only for verification, and not shared with third parties."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // 下一步按钮
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = fullName.isNotBlank() && nationality.isNotBlank() && birthDate.isNotBlank(),
            shape = AppShapes.Button,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.PrimaryGradientStart,
                disabledContainerColor = AppColors.PrimaryGradientStart.copy(alpha = 0.3f)
            )
        ) {
            Text(AppStrings.tr("下一步：上传证件", "Next: upload ID"), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(AppSpacing.XSmall))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun KYCProgressStep(
    number: String,
    label: String,
    isActive: Boolean,
    isCompleted: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(16.dp),
            color = when {
                isCompleted -> AppColors.SuccessGradientStart
                isActive -> AppColors.PrimaryGradientStart
                else -> Color.White.copy(alpha = 0.1f)
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive || isCompleted) Color.White else Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun KYCPendingView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(100.dp),
            shape = RoundedCornerShape(50.dp),
            color = AppColors.WarningGradientStart.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = AppColors.WarningGradientStart
                )
            }
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.XLarge))
        
        Text(
            text = AppStrings.tr("审核中", "In review"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(AppSpacing.Small))
        
        Text(
            text = AppStrings.tr(
                "您的 KYC 申请已提交，我们正在审核中\n预计 1-3 个工作日内完成",
                "Your KYC application has been submitted and is under review.\nEstimated completion: 1–3 business days"
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun KYCApprovedView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(100.dp),
            shape = RoundedCornerShape(50.dp),
            color = AppColors.SuccessGradientStart.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = AppColors.SuccessGradientStart
                )
            }
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.XLarge))
        
        Text(
            text = AppStrings.tr("认证通过", "Verified"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(AppSpacing.Small))
        
        Text(
            text = AppStrings.tr(
                "恭喜！您的身份已验证\nDID 凭证已发放到您的钱包",
                "Congrats! Your identity is verified.\nYour DID credential has been issued to your wallet."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(AppSpacing.XLarge))
        
        // DID 凭证卡片
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.LargeCard,
            color = Color.White.copy(alpha = 0.05f)
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Large),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.Badge,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = AppColors.PrimaryGradientEnd
                )
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                Text(
                    text = AppStrings.tr("Soulon DID", "Soulon DID"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = AppStrings.tr("已验证用户身份凭证", "Verified identity credential"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun KYCRejectedView(
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(100.dp),
            shape = RoundedCornerShape(50.dp),
            color = Color(0xFF3D2020)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = Color(0xFFFF6B6B)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.XLarge))
        
        Text(
            text = AppStrings.tr("认证未通过", "Verification failed"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(AppSpacing.Small))
        
        Text(
            text = AppStrings.tr(
                "您提交的信息可能存在问题\n请检查后重新提交",
                "There may be issues with your submission.\nPlease review and resubmit."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(AppSpacing.XLarge))
        
        Button(
            onClick = onRetry,
            modifier = Modifier.height(48.dp),
            shape = AppShapes.Button,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryGradientStart)
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Small))
            Text(AppStrings.tr("重新提交", "Resubmit"), fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 会员订阅卡片（左上角显示）
 * 
 * 显示订阅状态和钱包地址
 * - 未订阅：显示"未解锁特权，点击解锁"（优雅渐变设计）
 * - 已订阅：显示"欢迎，月费/年费会员"（高贵金色动画）
 */
@Composable
fun SeekerS2CompactCard(
    activity: MainActivity,
    walletConnected: Boolean,
    walletAddress: String?,
    onNavigateToDetails: () -> Unit,
    onNavigateToSubscribe: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = activity
    
    // 订阅状态
    var isSubscribed by remember { mutableStateOf(false) }
    var subscriptionType by remember { mutableStateOf("") }  // "monthly" or "yearly"
    var subscriptionExpiry by remember { mutableStateOf<Long?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
    // 简化的动画值（静态）
    val glowAlpha = 0.6f
    val sparkleOffset = 0.5f
    val breatheScale = 1f
    
    // 用于触发刷新的计数器
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // 加载订阅状态（支持从后端同步）
    LaunchedEffect(walletAddress, walletConnected, refreshTrigger) {
        if (!walletConnected || walletAddress == null) return@LaunchedEffect
        
        // 🆕 1. 立即显示本地缓存数据，避免加载动画
        try {
            val rewardsRepository = com.soulon.app.rewards.RewardsRepository(context)
            // 尝试读取本地缓存（非阻塞）
            val cachedProfile = rewardsRepository.getUserProfile()
            if (cachedProfile.subscriptionType != "FREE") {
                isSubscribed = true
                subscriptionType = when (cachedProfile.subscriptionType) {
                    "YEARLY" -> "yearly"
                    "QUARTERLY" -> "quarterly"
                    "MONTHLY" -> "monthly"
                    else -> "monthly"
                }
                subscriptionExpiry = cachedProfile.subscriptionExpiry
            } else {
                // 如果本地显示未订阅，暂时不更新状态，等待网络请求确认
                // 这样可以避免从“已订阅”闪烁到“未订阅”再变回“已订阅”
                isSubscribed = false
                subscriptionType = ""
                subscriptionExpiry = null
            }
        } catch (e: Exception) {
            // 忽略读取错误，等待网络请求
        }
        
        // 只有在没有任何数据时才显示 Loading
        // isLoading = true  <-- 移除这行，改为下面的逻辑
        
        try {
            val rewardsRepository = com.soulon.app.rewards.RewardsRepository(context)
            
            // 2. 静默同步最新数据
            try {
                // 在后台执行同步，不显示 Loading 状态
                val synced = rewardsRepository.syncFromBackend(walletAddress)
                if (synced) {
                    timber.log.Timber.d("会员卡片：后端数据同步成功")
                }
            } catch (syncError: Exception) {
                timber.log.Timber.w(syncError, "会员卡片：后端同步失败，使用本地数据")
            }
            
            // 3. 再次从本地数据库读取（已包含同步后的数据）并更新 UI
            val profile = rewardsRepository.getUserProfile()
            isSubscribed = profile.subscriptionType != "FREE"
            subscriptionType = if (isSubscribed) {
                when (profile.subscriptionType) {
                    "YEARLY" -> "yearly"
                    "QUARTERLY" -> "quarterly"
                    "MONTHLY" -> "monthly"
                    else -> "monthly"
                }
            } else ""
            subscriptionExpiry = profile.subscriptionExpiry
            
            timber.log.Timber.d("会员卡片：订阅状态=${profile.subscriptionType}, isSubscribed=$isSubscribed")
        } catch (e: Exception) {
            timber.log.Timber.e(e, "加载订阅状态失败")
        } finally {
            isLoading = false
        }
    }
    
    // 每30秒自动刷新一次
    LaunchedEffect(walletConnected) {
        if (walletConnected) {
            while (true) {
                kotlinx.coroutines.delay(30000L) // 30秒
                refreshTrigger++
            }
        }
    }
    
    // 未订阅渐变（优雅暗色）
    val lockedGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF2D2D3A).copy(alpha = 0.9f),
            Color(0xFF1A1A2E).copy(alpha = 0.95f)
        )
    )
    
    Card(
        modifier = modifier
            .modernCardShadow(
                if (isSubscribed) AppElevations.Large else AppElevations.Medium, 
                AppShapes.Card
            )
            .clickable(enabled = walletConnected) { 
                if (isSubscribed) onNavigateToDetails() else onNavigateToSubscribe() 
            },
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    lockedGradient
                )
                .padding(AppSpacing.Medium)
        ) {
            if (!walletConnected) {
                // 未连接钱包状态
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = AppStrings.tr("会员特权", "Member perks"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = AppStrings.tr("连接钱包查看", "Connect wallet to view"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.3f)
                    )
                }
            } else if (isLoading) {
                // 加载中
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFFFD700),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else if (isSubscribed) {
                // ========== 已订阅状态 - 高贵设计 ==========
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 上半部分 - 欢迎语
                        Column {
                            Text(
                                text = AppStrings.tr("欢迎", "Welcome"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFD700).copy(alpha = 0.9f)
                            )
                            Text(
                                text = AppStrings.tr("订阅会员", "Subscribed member"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            subscriptionExpiry?.let { expiry ->
                                val expiryText = runCatching {
                                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                        .format(java.util.Date(expiry))
                                }.getOrNull()
                                if (!expiryText.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (expiry > System.currentTimeMillis()) {
                                            AppStrings.tr("到期：$expiryText", "Expires: $expiryText")
                                        } else {
                                            AppStrings.tr("已到期：$expiryText", "Expired: $expiryText")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.75f)
                                    )
                                }
                            }
                        }
                        
                        // 下半部分 - 钱包地址（带金色点缀）
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        AppColors.PrimaryGradientStart.copy(alpha = 0.6f),
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = walletAddress?.let { "${it.take(6)}...${it.takeLast(4)}" } ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                // ========== 未订阅状态 - 优雅设计 ==========
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = breatheScale
                            scaleY = breatheScale
                        },
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 上半部分 - 锁定图标和提示
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = Color(0xFF9945FF),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = AppStrings.tr("特权未解锁", "Perks locked"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // 渐变文字提示
                        Text(
                            text = AppStrings.tr("点击开启会员之旅 →", "Tap to start your membership →"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF14F195)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = AppStrings.tr(
                                "验证 Seeker Genesis Token 获得礼物",
                                "Verify your Seeker Genesis Token to receive a gift"
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                    }
                    
                    // 下半部分 - 钱包地址
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(
                                    Color(0xFF9945FF).copy(alpha = 0.6f),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = walletAddress?.let { "${it.take(6)}...${it.takeLast(4)}" } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Seeker S2 状态卡片（详情页用，保留备用）
 * 
 * 展示 Seeker S2 原生功能状态：
 * - TEEPIN 验证状态
 * - Genesis Token 状态
 * - Sovereign Score
 * - 质押状态
 */
@Composable
fun SeekerS2StatusCard(
    activity: MainActivity,
    walletAddress: String,
    onNavigateToDetails: () -> Unit
) {
    val context = activity
    val scope = rememberCoroutineScope()
    
    // 状态
    var sovereignLevel by remember { mutableStateOf("Bronze") }
    var sovereignMultiplier by remember { mutableStateOf(1.0f) }
    var hasGenesisToken by remember { mutableStateOf(false) }
    var isStaking by remember { mutableStateOf(false) }
    var attestationValid by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    
    // 质押信息
    var stakingTier by remember { mutableStateOf("未质押") }
    var stakingAmount by remember { mutableStateOf("") }
    
    // 加载数据
    LaunchedEffect(walletAddress) {
        isLoading = true
        try {
            // 获取 Sovereign Score
            val rpcClient = com.soulon.app.wallet.SolanaRpcClient()
            val sovereignManager = com.soulon.app.sovereign.SovereignScoreManager(context, rpcClient)
            val level = sovereignManager.getSovereignLevel(walletAddress)
            sovereignLevel = level.displayName
            sovereignMultiplier = level.multiplier
            
            // 获取项目质押状态
            val projectStakingManager = com.soulon.app.staking.ProjectStakingManager(context, rpcClient)
            val stakingInfo = projectStakingManager.getStakingInfo(walletAddress)
            isStaking = stakingInfo.isStaking
            if (stakingInfo.isStaking) {
                stakingTier = stakingInfo.stakingTier.displayName
                stakingAmount = stakingInfo.getFormattedAmount()
            }
            
            // 检查 Genesis Token
            val genesisVerifier = com.soulon.app.teepin.GenesisTokenVerifier(rpcClient)
            hasGenesisToken = genesisVerifier.findGenesisToken(walletAddress) != null
            
            // 检查 TEEPIN 验证 - 需要 WalletManager，这里简化处理
            attestationValid = false // 简化：需要完整的 WalletManager 支持
            
        } catch (e: Exception) {
            timber.log.Timber.e(e, "加载 Seeker S2 状态失败")
        } finally {
            isLoading = false
        }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToDetails() }
            .modernCardShadow(AppElevations.Medium, AppShapes.LargeCard),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF14F195).copy(alpha = 0.15f),
                            Color(0xFF9945FF).copy(alpha = 0.15f)
                        )
                    )
                )
                .padding(AppSpacing.Large)
        ) {
            Column {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                    ) {
                        // Seeker 图标
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFF14F195), Color(0xFF9945FF))
                                    ),
                                    shape = AppShapes.SmallButton
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = AppStrings.tr("S2", "S2"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        
                        Column {
                            Text(
                                text = AppStrings.tr("Seeker S2 原生功能", "Seeker S2 native features"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = AppStrings.tr("TEEPIN · Sovereign · Guardian", "TEEPIN · Sovereign · Guardian"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                    
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = AppStrings.tr("查看详情", "View details"),
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF14F195),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    // 状态指标行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Sovereign Score
                        SeekerStatusItem(
                            icon = "🏆",
                            label = AppStrings.tr("主权", "Sovereign"),
                            value = sovereignLevel,
                            subValue = "${sovereignMultiplier}x",
                            isActive = true
                        )
                        
                        // Genesis Token
                        SeekerStatusItem(
                            icon = "🎫",
                            label = AppStrings.tr("Genesis", "Genesis"),
                            value = if (hasGenesisToken) AppStrings.tr("持有", "Held") else AppStrings.tr("未持有", "Not held"),
                            subValue = if (hasGenesisToken) "+50%" else "-",
                            isActive = hasGenesisToken
                        )
                        
                        // 项目质押状态
                        SeekerStatusItem(
                            icon = "💎",
                            label = "质押",
                            value = if (isStaking) stakingTier else "未质押",
                            subValue = if (isStaking) stakingAmount else "-",
                            isActive = isStaking
                        )
                        
                        // TEEPIN 验证
                        SeekerStatusItem(
                            icon = "🔐",
                            label = "TEEPIN",
                            value = if (attestationValid) "已验证" else "未验证",
                            subValue = if (attestationValid) "1.5x" else "-",
                            isActive = attestationValid
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekerStatusItem(
    icon: String,
    label: String,
    value: String,
    subValue: String,
    isActive: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            fontSize = 20.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (isActive) Color.White else Color.White.copy(alpha = 0.4f)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f)
        )
        if (isActive && subValue != "-") {
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF14F195)
            )
        }
    }
}

/**
 * 屏幕导航
 */
sealed class Screen {
    object Dashboard : Screen()  // Phase 3: 仪表盘
    object Memories : Screen()   // Phase 2: 记忆（保留但不在导航栏显示）
    object Chat : Screen()       // Phase 3: AI 对话
    object Profile : Screen()    // 我的页面
    object About : Screen()      // 关于页面
    object QA : Screen()         // 常见问题页面
    object Settings : Screen()   // 设置页面
    object Evaluation : Screen() // 问卷评估页面
    object TierDetails : Screen()  // Phase 3: 等级详情
    // 语言设置
    object LanguageSettings : Screen() // 语言设置页面
    // 新增页面
    object SeasonRewards : Screen()   // 赛季奖励页面
    object MyAssets : Screen()        // 我的资产页面
    data class AssetDetail(
        val kind: String,
        val name: String,
        val assetAddress: String? = null,
        val metadataUri: String? = null
    ) : Screen()
    // 偏好设置相关
    object NotificationSettings : Screen() // 通知设置页面
    object Security : Screen()             // 安全页面（入口页）
    // 帮助&支持相关
    object BugReport : Screen()      // Bug 报告页面
    object ContactUs : Screen()      // 联系我们页面
    // KYC 认证
    object KYCVerification : Screen() // KYC 认证页面
    // DID 身份管理（高级功能）
    object DIDManagement : Screen()   // DID 身份管理页面
    // Seeker S2 质押和等级系统
    object StakingDashboard : Screen()    // 质押仪表盘
    object MemberTierDashboard : Screen() // 会员等级仪表盘
    object UserLevelDashboard : Screen()  // 用户级别仪表盘
    object TierSystemOverview : Screen()  // 等级系统总览
    // 订阅和生态质押
    object Subscription : Screen()        // 会员订阅页面
    object EcoStaking : Screen()          // 生态质押页面
    // 积分系统
    object CheckIn : Screen()             // 每日签到页面
    object MemoHistory : Screen()         // 积分历史记录页面
    // 订阅管理
    object SubscriptionManage : Screen()  // 订阅管理页面（含自动续费）
    object PaymentEcosystem : Screen()    // 支付生态正式流程页
    object PaymentEcosystemDev : Screen() // 支付生态调试页面
    object GameLoading : Screen()         // 游戏预开服加载页
    object Game : Screen()                // 🆕 探索冒险游戏
}

/**
 * 格式化时间戳
 */
private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date(timestamp))
}

/**
 * 会员订阅页面
 * 
 * 横排三张卡片，选中后下方显示会员权益
 * 真实集成 Solana Pay 支付
 */
@Composable
fun SubscriptionScreen(
    walletAddress: String?,
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    onNavigateBack: () -> Unit,
    onSubscriptionSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val remoteConfig = com.soulon.app.config.RemoteConfigManager.getInstance(context)
    val rewardsRepository = remember { RewardsRepository(context) }
    val autoRenewService = remember { com.soulon.app.subscription.AutoRenewService.getInstance(context) }
    val genesisTrialService = remember { com.soulon.app.subscription.GenesisTrialService.getInstance(context) }
    val solanaRpcClient = remember {
        com.soulon.app.wallet.SolanaRpcClient().apply { initBackendProxy(context) }
    }
    
    // 确保钱包地址可用（如果传入为 null，尝试从 WalletManager 获取）
    var actualWalletAddress by remember { mutableStateOf(walletAddress) }
    
    // 如果传入的 walletAddress 为 null，尝试恢复
    LaunchedEffect(walletAddress) {
        if (walletAddress == null) {
            try {
                val walletManager = com.soulon.app.wallet.WalletManager(context)
                actualWalletAddress = walletManager.getWalletAddress()
                if (actualWalletAddress != null) {
                    Timber.i("✅ 从 WalletManager 恢复钱包地址: $actualWalletAddress")
                } else {
                    Timber.w("⚠️ 无法获取钱包地址，WalletManager 返回 null")
                }
            } catch (e: Exception) {
                Timber.e(e, "获取钱包地址失败")
            }
        } else {
            actualWalletAddress = walletAddress
        }
    }
    
    var subscriptionPlansConfig by remember { mutableStateOf(remoteConfig.getJsonObject("subscription.plans")) }

    DisposableEffect(remoteConfig) {
        val listener = object : com.soulon.app.config.RemoteConfigManager.OnConfigUpdateListener {
            override fun onConfigUpdated(updatedKeys: Set<String>) {
                if (updatedKeys.any { it.startsWith("subscription.") }) {
                    subscriptionPlansConfig = remoteConfig.getJsonObject("subscription.plans")
                }
            }
        }
        remoteConfig.addConfigListener(listener)
        onDispose { remoteConfig.removeConfigListener(listener) }
    }

    LaunchedEffect(Unit) {
        remoteConfig.syncFromBackend()
        subscriptionPlansConfig = remoteConfig.getJsonObject("subscription.plans")
        while (true) {
            kotlinx.coroutines.delay(10_000)
            remoteConfig.syncFromBackend()
            subscriptionPlansConfig = remoteConfig.getJsonObject("subscription.plans")
        }
    }

    val defaultSelectedPlanId = subscriptionPlansConfig
        ?.optString("defaultSelectedId")
        ?.takeIf { it.isNotBlank() }
        ?: "yearly"

    var selectedPlan by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(defaultSelectedPlanId) {
        if (selectedPlan == null) {
            selectedPlan = defaultSelectedPlanId
        }
    }
    var selectedPaymentToken by remember { mutableStateOf("USDC") }  // 支付方式：USDC（锚定）/ SOL / SKR
    var isProcessing by remember { mutableStateOf(false) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var paymentError by remember { mutableStateOf<String?>(null) }
    var quoteSolPriceAtDialog by remember { mutableDoubleStateOf(0.0) }
    var quoteSkrPriceAtDialog by remember { mutableDoubleStateOf(0.0) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var transactionSignature by remember { mutableStateOf<String?>(null) }
    var showScheduleUpgradeDialog by remember { mutableStateOf(false) }
    var scheduleTargetPlanId by remember { mutableStateOf<String?>(null) }
    var scheduleToPlanType by remember { mutableIntStateOf(0) }
    var scheduleDialogTitle by remember { mutableStateOf(AppStrings.tr("确认升级", "Confirm upgrade")) }
    var scheduleDialogDescription by remember { mutableStateOf<String?>(null) }
    var showScheduleUpgradeSuccessDialog by remember { mutableStateOf(false) }

    var showGenesisTrialDialog by remember { mutableStateOf(false) }
    var genesisChecking by remember { mutableStateOf(false) }
    var genesisHasToken by remember { mutableStateOf<Boolean?>(null) }
    var genesisRedeemed by remember { mutableStateOf<Boolean?>(null) }
    var genesisError by remember { mutableStateOf<String?>(null) }
    var genesisProcessing by remember { mutableStateOf(false) }
    var showGenesisSuccessDialog by remember { mutableStateOf(false) }
    var genesisTxSignature by remember { mutableStateOf<String?>(null) }
    var genesisFollowedX by remember { mutableStateOf(false) }

    var autoRenewActive by remember { mutableStateOf(false) }
    var autoRenewPlanType by remember { mutableIntStateOf(0) }
    var autoRenewNextPaymentAt by remember { mutableLongStateOf(0L) }
    var pendingPlanType by remember { mutableIntStateOf(0) }
    var pendingEffectiveAt by remember { mutableLongStateOf(0L) }
    var cancelLockedUntil by remember { mutableLongStateOf(0L) }
    
    // Jupiter Ultra API 实时汇率
    val ultraService = remember { com.soulon.app.payment.JupiterUltraService.getInstance(context) }
    val isLoadingRates by ultraService.isLoading.collectAsState()
    val rateError by ultraService.lastError.collectAsState()
    
    // 汇率状态
    var solPriceUsdc by remember { mutableStateOf(150.0) }  // 默认值
    var skrPriceUsdc by remember { mutableStateOf(0.01) }   // 默认值
    var ratesLoaded by remember { mutableStateOf(false) }
    val skrMintValid = remember {
        runCatching { org.bitcoinj.core.Base58.decode(com.soulon.app.payment.JupiterUltraService.SKR_MINT).size == 32 }
            .getOrDefault(false)
    }
    val quoteTtlMs = 15_000L
    val maxSlippageBps = 50
    val maxSlippageRatio = maxSlippageBps / 10_000.0
    var lastRatesRefreshAt by remember { mutableLongStateOf(0L) }
    var quoteCountdownProgress by remember { mutableFloatStateOf(0f) }
    var quoteCountdownSecondsLeft by remember { mutableIntStateOf(0) }
    
    suspend fun refreshRates() {
        lastRatesRefreshAt = System.currentTimeMillis()
        ultraService.clearPriceCache()
        ultraService.clearLastError()
        ultraService.getSolUsdcRate()?.let {
            solPriceUsdc = it
            ratesLoaded = true
        }
        if (skrMintValid) {
            ultraService.getSkrUsdcRate()?.let {
                skrPriceUsdc = it
                ratesLoaded = true
            }
        }
    }

    LaunchedEffect(showPaymentDialog) {
        if (showPaymentDialog) {
            quoteSolPriceAtDialog = solPriceUsdc
            quoteSkrPriceAtDialog = skrPriceUsdc
        }
    }
    
    // 页面加载时从后台获取配置和汇率
    LaunchedEffect(Unit, skrMintValid) {
        refreshRates()
        while (true) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRatesRefreshAt
            val remaining = (quoteTtlMs - elapsed).coerceIn(0L, quoteTtlMs)
            quoteCountdownProgress = remaining.toFloat() / quoteTtlMs.toFloat()
            quoteCountdownSecondsLeft = kotlin.math.ceil(remaining / 1000.0).toInt()
            
            val shouldRefresh = elapsed >= quoteTtlMs && !isProcessing && !isLoadingRates
            if (shouldRefresh) {
                refreshRates()
            }
            
            kotlinx.coroutines.delay(250)
        }
    }

    LaunchedEffect(skrMintValid) {
        if (!skrMintValid && selectedPaymentToken == "SKR") {
            selectedPaymentToken = "USDC"
        }
    }
    
    // 订阅方案（以 USDC 为锚定价格）
    data class SubscriptionPlan(
        val id: String,
        val basePlanId: String,
        val kind: String = "subscription",
        val name: String,
        val shortName: String,
        val priceUsdc: Double,       // USDC 锚定价格
        val renewalPriceUsdc: Double?,
        val pricePerMonth: String,
        val duration: String,
        val durationMonths: Int,
        val features: List<String>,
        val savings: String? = null,
        val badgeText: String? = null,
        val autoRenew: Boolean = false,
        val tokenMultiplier: Float,  // 每月 Token 限额倍数
        val pointsMultiplier: Float,  // 积分加速倍数
        val uiPriceText: String? = null,
        val uiPriceSubText: String? = null,
        val uiChipText: String? = null
    )
    
    // 从远程配置获取订阅价格和权益（用于回退 & 支付/续费映射）
    val monthlyPrice = remoteConfig.getSubscriptionMonthlyUsdc()
    val quarterlyPrice = remoteConfig.getSubscriptionQuarterlyUsdc()
    val yearlyPrice = remoteConfig.getSubscriptionYearlyUsdc()
    val monthlyTokenMult = remoteConfig.getSubscriptionMonthlyTokenMultiplier()
    val quarterlyTokenMult = remoteConfig.getSubscriptionQuarterlyTokenMultiplier()
    val yearlyTokenMult = remoteConfig.getSubscriptionYearlyTokenMultiplier()
    val monthlyPointsMult = remoteConfig.getSubscriptionMonthlyPointsMultiplier()
    val quarterlyPointsMult = remoteConfig.getSubscriptionQuarterlyPointsMultiplier()
    val yearlyPointsMult = remoteConfig.getSubscriptionYearlyPointsMultiplier()
    val quarterlyBadgeText = remoteConfig.getString("subscription.badge.quarterly", "推荐")
    val yearlyBadgeText = remoteConfig.getString("subscription.badge.yearly", "推荐")

    val monthlyBenefits = listOf(
        AppStrings.tr("解锁生态质押功能（即将上线）", "Unlock eco staking (coming soon)"),
        AppStrings.tr("突破每月 Token 限额", "Remove monthly token cap"),
        AppStrings.tr("加快积分累积", "Faster point accumulation"),
        AppStrings.tr("新功能准入", "New Feature Access")
    )
    
    // 自动续费配置
    val autoRenewEnabled = remoteConfig.getSubscriptionAutoRenewEnabled()
    val firstMonthDiscount = remoteConfig.getSubscriptionFirstMonthDiscount()
    val firstMonthPrice = monthlyPrice * firstMonthDiscount
    val firstMonthSavings = ((1 - firstMonthDiscount) * 100).toInt()
    
    // 计算每月价格和节省比例
    val quarterlyPerMonth = quarterlyPrice / 3
    val yearlyPerMonth = yearlyPrice / 12
    val quarterlySavings = ((monthlyPrice * 3 - quarterlyPrice) / (monthlyPrice * 3) * 100).toInt()
    val yearlySavings = ((monthlyPrice * 12 - yearlyPrice) / (monthlyPrice * 12) * 100).toInt()
    
    val showFirstMonthDiscount = autoRenewEnabled && firstMonthDiscount < 1.0
    
    val plansFromConfig = runCatching {
        val arr = subscriptionPlansConfig?.optJSONArray("plans") ?: return@runCatching null
        val list = mutableListOf<SubscriptionPlan>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            val basePlanId = o.optString("basePlanId", id).trim().ifBlank { id }
            val name = o.optString("name", id)
            val shortName = o.optString("shortName", name)
            val priceUsdc = o.optDouble("priceUsdc", Double.NaN)
            if (priceUsdc.isNaN()) continue
            val renewalPriceUsdc = if (o.has("renewalPriceUsdc") && !o.isNull("renewalPriceUsdc")) {
                o.optDouble("renewalPriceUsdc")
            } else null
            val pricePerMonth = o.optString("pricePerMonth", "")
            val duration = o.optString("duration", "")
            val durationMonths = o.optInt("durationMonths", 1).coerceAtLeast(1)
            val featuresArr = o.optJSONArray("features")
            val features = if (featuresArr != null) {
                (0 until featuresArr.length()).mapNotNull { idx -> featuresArr.optString(idx).takeIf { it.isNotBlank() } }
            } else {
                emptyList()
            }
            val savings = o.optString("savings").takeIf { it.isNotBlank() }
            val badgeText = o.optString("badgeText").takeIf { it.isNotBlank() }
            val autoRenew = o.optBoolean("autoRenew", false)
            val tokenMultiplierFallback = when (basePlanId) {
                "monthly" -> monthlyTokenMult
                "quarterly" -> quarterlyTokenMult
                "yearly" -> yearlyTokenMult
                else -> 1.0f
            }
            val pointsMultiplierFallback = when (basePlanId) {
                "monthly" -> monthlyPointsMult
                "quarterly" -> quarterlyPointsMult
                "yearly" -> yearlyPointsMult
                else -> 1.0f
            }
            val tokenMultiplier = o.optDouble("tokenMultiplier", tokenMultiplierFallback.toDouble()).toFloat()
            val pointsMultiplier = o.optDouble("pointsMultiplier", pointsMultiplierFallback.toDouble()).toFloat()

            list.add(
                SubscriptionPlan(
                    id = id,
                    basePlanId = basePlanId,
                    name = name,
                    shortName = shortName,
                    priceUsdc = priceUsdc,
                    renewalPriceUsdc = renewalPriceUsdc,
                    pricePerMonth = pricePerMonth,
                    duration = duration,
                    durationMonths = durationMonths,
                    features = features,
                    savings = savings,
                    badgeText = badgeText,
                    autoRenew = autoRenew,
                    tokenMultiplier = tokenMultiplier,
                    pointsMultiplier = pointsMultiplier
                )
            )
        }
        list.takeIf { it.isNotEmpty() }
    }.getOrNull()

    val rawPlans = plansFromConfig ?: run {
        listOf(
            SubscriptionPlan(
                id = "monthly",
                basePlanId = "monthly",
                name = AppStrings.tr("月费", "Monthly"),
                shortName = AppStrings.tr("月费", "Monthly"),
                priceUsdc = monthlyPrice,
                renewalPriceUsdc = null,
                pricePerMonth = AppStrings.trf(
                    "≈ \$%s/月",
                    "≈ \$%s/mo",
                    String.format("%.2f", monthlyPrice)
                ),
                duration = AppStrings.tr("1 个月", "1 month"),
                durationMonths = 1,
                features = monthlyBenefits,
                tokenMultiplier = monthlyTokenMult,
                pointsMultiplier = monthlyPointsMult
            ),
            SubscriptionPlan(
                id = "yearly",
                basePlanId = "yearly",
                name = AppStrings.tr("12 个月", "12 months"),
                shortName = AppStrings.tr("12个月", "12 mo"),
                priceUsdc = yearlyPrice,
                renewalPriceUsdc = null,
                pricePerMonth = AppStrings.trf(
                    "≈ \$%s/月",
                    "≈ \$%s/mo",
                    String.format("%.2f", yearlyPerMonth)
                ),
                duration = AppStrings.tr("12 个月", "12 months"),
                durationMonths = 12,
                features = monthlyBenefits,
                savings = if (yearlySavings > 0) AppStrings.trf("省%d%%", "Save %d%%", yearlySavings) else null,
                badgeText = yearlyBadgeText,
                tokenMultiplier = yearlyTokenMult,
                pointsMultiplier = yearlyPointsMult
            ),
            SubscriptionPlan(
                id = "quarterly",
                basePlanId = "quarterly",
                name = AppStrings.tr("3 个月", "3 months"),
                shortName = AppStrings.tr("3个月", "3 mo"),
                priceUsdc = quarterlyPrice,
                renewalPriceUsdc = null,
                pricePerMonth = AppStrings.trf(
                    "≈ \$%s/月",
                    "≈ \$%s/mo",
                    String.format("%.2f", quarterlyPerMonth)
                ),
                duration = AppStrings.tr("3 个月", "3 months"),
                durationMonths = 3,
                features = monthlyBenefits,
                savings = if (quarterlySavings > 0) AppStrings.trf("省%d%%", "Save %d%%", quarterlySavings) else null,
                badgeText = quarterlyBadgeText,
                tokenMultiplier = quarterlyTokenMult,
                pointsMultiplier = quarterlyPointsMult
            ),
            SubscriptionPlan(
                id = "monthly_one_time",
                basePlanId = "monthly",
                name = AppStrings.tr("一个月", "1 month"),
                shortName = AppStrings.tr("一个月", "1 mo"),
                priceUsdc = monthlyPrice,
                renewalPriceUsdc = null,
                pricePerMonth = AppStrings.trf(
                    "≈ \$%s/月",
                    "≈ \$%s/mo",
                    String.format("%.2f", monthlyPrice)
                ),
                duration = AppStrings.tr("1 个月", "1 month"),
                durationMonths = 1,
                features = monthlyBenefits,
                tokenMultiplier = monthlyTokenMult,
                pointsMultiplier = monthlyPointsMult
            )
        )
    }

    val plans = remember(
        rawPlans,
        monthlyBenefits,
        monthlyPrice,
        quarterlyPerMonth,
        yearlyPerMonth,
        quarterlySavings,
        yearlySavings,
        quarterlyBadgeText,
        yearlyBadgeText
    ) {
        val localizedQuarterlyBadge = if (quarterlyBadgeText == "推荐") AppStrings.tr("推荐", "Recommended") else quarterlyBadgeText
        val localizedYearlyBadge = if (yearlyBadgeText == "推荐") AppStrings.tr("推荐", "Recommended") else yearlyBadgeText

        rawPlans.map { p ->
            val rawSavings = p.savings?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            val rawBadge = p.badgeText?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            val isMonthlyDuration = p.basePlanId == "monthly" && p.durationMonths == 1

            val localizedName = when {
                isMonthlyDuration -> AppStrings.tr("月费", "Monthly")
                p.basePlanId == "quarterly" || p.durationMonths == 3 -> AppStrings.tr("季度", "Quarterly")
                p.basePlanId == "yearly" || p.durationMonths == 12 -> AppStrings.tr("年度", "Annual")
                else -> p.name
            }

            val localizedShortName = when {
                isMonthlyDuration -> AppStrings.tr("月费", "Monthly")
                p.basePlanId == "quarterly" || p.durationMonths == 3 -> AppStrings.tr("季度", "Quarterly")
                p.basePlanId == "yearly" || p.durationMonths == 12 -> AppStrings.tr("年度", "Annual")
                else -> p.shortName
            }

            val localizedDuration = when {
                p.durationMonths == 1 -> AppStrings.tr("1 个月", "1 month")
                p.durationMonths == 3 -> AppStrings.tr("3 个月", "3 months")
                p.durationMonths == 12 -> AppStrings.tr("12 个月", "12 months")
                else -> p.duration
            }

            val perMonthPrice = if (p.durationMonths > 0) (p.priceUsdc / p.durationMonths) else p.priceUsdc
            val localizedPerMonth = if (p.durationMonths > 0) {
                AppStrings.trf(
                    "≈ \$%s/月",
                    "≈ \$%s/mo",
                    String.format("%.2f", perMonthPrice)
                )
            } else {
                p.pricePerMonth
            }

            val localizedSavings = when (p.durationMonths) {
                3 -> if (quarterlySavings > 0) AppStrings.trf("省%d%%", "Save %d%%", quarterlySavings) else null
                12 -> if (yearlySavings > 0) AppStrings.trf("省%d%%", "Save %d%%", yearlySavings) else null
                else -> rawSavings
            }

            val localizedBadge = when (p.durationMonths) {
                3 -> localizedQuarterlyBadge
                12 -> localizedYearlyBadge
                else -> rawBadge
            }

            p.copy(
                name = localizedName,
                shortName = localizedShortName,
                duration = localizedDuration,
                pricePerMonth = localizedPerMonth,
                features = monthlyBenefits,
                savings = if (isMonthlyDuration) null else localizedSavings,
                badgeText = if (isMonthlyDuration) null else localizedBadge
            )
        }
    }

    LaunchedEffect(actualWalletAddress) {
        val wallet = actualWalletAddress ?: return@LaunchedEffect
        autoRenewService.syncStatus(wallet)
        autoRenewActive = autoRenewService.isAutoRenewEnabled(wallet)
        autoRenewPlanType = autoRenewService.getCurrentPlanType(wallet)
        autoRenewNextPaymentAt = autoRenewService.getNextPaymentAt(wallet)
        pendingPlanType = autoRenewService.getPendingPlanType(wallet)
        pendingEffectiveAt = autoRenewService.getPendingEffectiveAt(wallet)
        cancelLockedUntil = autoRenewService.getCancelLockedUntil(wallet)

        runCatching {
            val prefs = WalletScope.scopedPrefs(context, "subscription_prefs", wallet)
            val planId = prefs.getString("subscription_type", null)
            val expiry = prefs.getLong("subscription_expiry", 0L)
            val tx = prefs.getString("tx_signature", null)
            val lastSyncedTx = prefs.getString("backend_synced_tx", null)
            if (!planId.isNullOrBlank() && expiry > System.currentTimeMillis() && !tx.isNullOrBlank() && tx != lastSyncedTx) {
                val apiClient = com.soulon.app.data.BackendApiClient.getInstance(context)
                val startDate = maxOf(System.currentTimeMillis(), expiry - (30L * 24 * 60 * 60 * 1000))
                val ok = apiClient.syncSubscription(
                    walletAddress = wallet,
                    planId = planId.lowercase(),
                    startDate = startDate,
                    endDate = expiry,
                    amount = 0.0,
                    transactionId = tx
                )
                if (ok) {
                    prefs.edit().putString("backend_synced_tx", tx).apply()
                    rewardsRepository.syncFromBackend(wallet)
                }
            }
        }
    }

    val subscriptionPrefs = remember(actualWalletAddress) { WalletScope.scopedPrefs(context, "subscription_prefs", actualWalletAddress) }
    val currentSubscriptionType = subscriptionPrefs.getString("subscription_type", null)
    val currentSubscriptionExpiry = subscriptionPrefs.getLong("subscription_expiry", 0L)
    val isCurrentSubscriptionActive = currentSubscriptionExpiry > System.currentTimeMillis()

    val uiRules = subscriptionPlansConfig?.optJSONObject("uiRules")

    fun conditionMatches(condition: org.json.JSONObject?): Boolean {
        if (condition == null) return false

        if (condition.has("any")) {
            val arr = condition.optJSONArray("any") ?: return false
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (conditionMatches(c)) return true
            }
            return false
        }

        if (condition.has("all")) {
            val arr = condition.optJSONArray("all") ?: return false
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (!conditionMatches(c)) return false
            }
            return true
        }

        condition.optJSONArray("autoRenewPlanTypeIn")?.let { arr ->
            val matched = (0 until arr.length()).any { idx -> arr.optInt(idx) == autoRenewPlanType }
            if (!matched) return false
        }
        condition.optJSONArray("pendingPlanTypeIn")?.let { arr ->
            val matched = (0 until arr.length()).any { idx -> arr.optInt(idx) == pendingPlanType }
            if (!matched) return false
        }
        condition.optJSONArray("activeSubscriptionTypeIn")?.let { arr ->
            val matched = (0 until arr.length()).any { idx -> arr.optString(idx) == currentSubscriptionType } && isCurrentSubscriptionActive
            if (!matched) return false
        }

        return true
    }

    fun hidePlanByRules(planId: String): Boolean {
        val hideArr = uiRules?.optJSONArray("hidePlans") ?: return false
        for (i in 0 until hideArr.length()) {
            val rule = hideArr.optJSONObject(i) ?: continue
            val planIds = rule.optJSONArray("planIds") ?: continue
            val hit = (0 until planIds.length()).any { idx -> planIds.optString(idx) == planId }
            if (!hit) continue
            if (conditionMatches(rule.optJSONObject("when"))) return true
        }
        return false
    }

    fun disallowSelectMessage(planId: String): String? {
        val arr = uiRules?.optJSONArray("disallowSelect") ?: return null
        for (i in 0 until arr.length()) {
            val rule = arr.optJSONObject(i) ?: continue
            val planIds = rule.optJSONArray("planIds") ?: continue
            val hit = (0 until planIds.length()).any { idx -> planIds.optString(idx) == planId }
            if (!hit) continue
            if (conditionMatches(rule.optJSONObject("when"))) {
                val msg = rule.optString("message").trim()
                return msg.ifBlank { AppStrings.tr("当前状态不允许选择该档位", "This plan is not available right now.") }
            }
        }
        return null
    }

    fun findAutoRenewUpgradeRule(planId: String, desiredPlanType: Int): org.json.JSONObject? {
        val arr = uiRules?.optJSONArray("autoRenewUpgrade") ?: return null
        for (i in 0 until arr.length()) {
            val rule = arr.optJSONObject(i) ?: continue
            val fromPlanType = rule.optInt("fromPlanType", 0)
            val toPlanType = rule.optInt("toPlanType", 0)
            if (fromPlanType == 0 || toPlanType == 0) continue
            if (!autoRenewActive || autoRenewPlanType != fromPlanType) continue
            if (desiredPlanType != toPlanType) continue
            val targets = rule.optJSONArray("targetPlanIds") ?: continue
            val hit = (0 until targets.length()).any { idx -> targets.optString(idx) == planId }
            if (!hit) continue
            val action = rule.optString("action", "").trim()
            if (action != "schedule_change") continue
            return rule
        }
        return null
    }

    val subscriptionDisplayPlans = remember(plans, autoRenewActive, autoRenewPlanType, pendingPlanType, currentSubscriptionType, currentSubscriptionExpiry) {
        val visible = plans.filterNot { hidePlanByRules(it.id) }
        val monthlyCandidates = visible.filter { it.basePlanId == "monthly" && it.durationMonths == 1 }
        val keepMonthly = monthlyCandidates.lastOrNull { it.id == "monthly_one_time" }
            ?: monthlyCandidates.lastOrNull { !it.autoRenew }
            ?: monthlyCandidates.lastOrNull()

        val quarterlyCandidates = visible.filter { it.durationMonths == 3 || it.basePlanId == "quarterly" }
        val keepQuarterly = quarterlyCandidates.lastOrNull { it.basePlanId == "quarterly" && it.id == "quarterly" }
            ?: quarterlyCandidates.lastOrNull { !it.autoRenew }
            ?: quarterlyCandidates.lastOrNull()

        visible.filterNot { p ->
            p.basePlanId == "monthly" && p.durationMonths == 1 && keepMonthly != null && p.id != keepMonthly.id
                    || p.durationMonths == 3 && keepQuarterly != null && p.id != keepQuarterly.id
        }
    }

    val genesisTrialPlan = remember(monthlyTokenMult, monthlyPointsMult) {
        SubscriptionPlan(
            id = "genesis_trial",
            basePlanId = "genesis_trial",
            kind = "genesis_trial",
            name = AppStrings.tr("Seeker Genesis Token 7天体验卡", "Seeker Genesis Token 7-day Trial"),
            shortName = AppStrings.tr("7天体验卡", "7-day trial"),
            priceUsdc = 0.0,
            renewalPriceUsdc = null,
            pricePerMonth = "",
            duration = AppStrings.tr("7 天", "7 days"),
            durationMonths = 1,
            features = monthlyBenefits,
            savings = null,
            badgeText = AppStrings.tr("限时", "Limited"),
            autoRenew = false,
            tokenMultiplier = monthlyTokenMult,
            pointsMultiplier = monthlyPointsMult,
            uiChipText = AppStrings.tr("需要 Genesis Token", "Requires Genesis Token")
        )
    }

    val displayPlans = remember(genesisTrialPlan, subscriptionDisplayPlans) {
        listOf(genesisTrialPlan) + subscriptionDisplayPlans
    }

    LaunchedEffect(subscriptionDisplayPlans) {
        val current = selectedPlan
        if (current == null || subscriptionDisplayPlans.none { it.id == current }) {
            selectedPlan = subscriptionDisplayPlans.firstOrNull()?.id
        }
    }
    
    // 获取显示价格（以 USDC 为锚定，支持 SOL/USDC/SKR）
    fun getDisplayPrice(plan: SubscriptionPlan): String {
        plan.uiPriceText?.let { return it }
        return when (selectedPaymentToken) {
            "SOL" -> if (solPriceUsdc.isFinite() && solPriceUsdc > 0.0) {
                "${String.format("%.4f", plan.priceUsdc / solPriceUsdc)} SOL"
            } else {
                "${plan.priceUsdc} USDC"
            }
            "SKR" -> if (skrMintValid && skrPriceUsdc.isFinite() && skrPriceUsdc > 0.0) {
                "${String.format("%.0f", plan.priceUsdc / skrPriceUsdc)} SKR"
            } else {
                "${plan.priceUsdc} USDC"
            }
            else -> "${plan.priceUsdc} USDC"
        }
    }
    
    // 获取等价 USDC 价格显示
    fun getUsdcEquivalent(plan: SubscriptionPlan): String {
        return "≈ \$${plan.priceUsdc}"
    }
    
    // 保存订阅信息
    fun saveSubscription(
        ctx: android.content.Context, 
        walletAddress: String,
        plan: SubscriptionPlan, 
        txSignature: String, 
        payToken: String, 
        payAmount: String
    ) {
        val prefs = WalletScope.scopedPrefs(ctx, "subscription_prefs", walletAddress)
        val existingExpiry = prefs.getLong("subscription_expiry", 0L)
        val baseTime = maxOf(System.currentTimeMillis(), existingExpiry)
        val expiryTime = baseTime + (plan.durationMonths * 30L * 24 * 60 * 60 * 1000)
        prefs.edit()
            .putString("subscription_type", plan.id)
            .putLong("subscription_expiry", expiryTime)
            .putString("subscription_tx", txSignature)
            .putFloat("token_multiplier", plan.tokenMultiplier)
            .putFloat("points_multiplier", plan.pointsMultiplier)
            .putString("payment_token", payToken)
            .putString("payment_amount", payAmount)
            .apply()
    }

    fun saveGenesisTrialSubscription(
        ctx: android.content.Context,
        walletAddress: String,
        txSignature: String
    ) {
        val prefs = WalletScope.scopedPrefs(ctx, "subscription_prefs", walletAddress)
        val expiryTime = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
        prefs.edit()
            .putString("subscription_type", "genesis_trial")
            .putLong("subscription_expiry", expiryTime)
            .putString("subscription_tx", txSignature)
            .putFloat("token_multiplier", monthlyTokenMult)
            .putFloat("points_multiplier", monthlyPointsMult)
            .putString("payment_token", "SOL")
            .putString("payment_amount", "0.05 SOL")
            .apply()
    }
    
    // 获取当前选中的方案
    val selectedPlanData = subscriptionDisplayPlans.find { it.id == selectedPlan }
    val plansListState = rememberLazyListState()
    val monthlyAnchorPriceUsdc = plans.firstOrNull { it.id == "monthly" }?.priceUsdc
        ?: plans.firstOrNull { it.basePlanId == "monthly" && it.durationMonths == 1 && !it.autoRenew }?.priceUsdc
        ?: monthlyPrice

    fun discountChipText(plan: SubscriptionPlan): String {
        val anchor = monthlyAnchorPriceUsdc
        if (anchor <= 0.0) return plan.duration
        val months = plan.durationMonths.coerceAtLeast(1)
        val perMonth = plan.priceUsdc / months
        val rate = perMonth / anchor
        if (!rate.isFinite() || rate <= 0.0) return plan.duration

        if (abs(rate - 1.0) < 0.01) return AppStrings.tr("原价", "Standard")

        val zhe = rate * 10.0
        val zheRounded = (zhe * 10.0).roundToInt() / 10.0
        val zheText = if (abs(zheRounded - zheRounded.toInt().toDouble()) < 0.05) {
            zheRounded.toInt().toString()
        } else {
            String.format("%.1f", zheRounded)
        }
        val percentText = ((rate * 100.0).roundToInt()).toString()
        return AppStrings.trf("%1\$s折/月", "%2\$s%%/mo", zheText, percentText)
    }

    LaunchedEffect(displayPlans, selectedPlan) {
        val idx = displayPlans.indexOfFirst { it.id == selectedPlan }
        if (idx >= 0) {
            runCatching { plansListState.animateScrollToItem(idx) }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部导航栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = com.soulon.app.i18n.AppStrings.tr("关闭", "Close"),
                        tint = Color.White
                    )
                }
                Text(
                    text = AppStrings.tr("开通会员", "Subscribe"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.size(48.dp))
            }
            
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                state = plansListState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(displayPlans, key = { it.id }) { plan ->
                    val isSelected = plan.kind == "subscription" && selectedPlan == plan.id
                    Box(
                        modifier = Modifier.width(172.dp)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.8f)
                                .clickable {
                                    if (plan.kind == "genesis_trial") {
                                        val wallet = actualWalletAddress
                                        if (wallet.isNullOrBlank()) {
                                            paymentError = AppStrings.tr("请先连接钱包", "Please connect your wallet first")
                                            return@clickable
                                        }

                                        showGenesisTrialDialog = true
                                        genesisChecking = true
                                        genesisHasToken = null
                                        genesisRedeemed = null
                                        genesisError = null

                                        coroutineScope.launch {
                                            try {
                                                val eligibility = genesisTrialService.getEligibility(wallet).getOrElse { throw it }
                                                genesisHasToken = eligibility.hasGenesisToken
                                                genesisRedeemed = eligibility.redeemed
                                                if (!eligibility.rpcConfigured) {
                                                    genesisError = AppStrings.tr(
                                                        "后台未配置 SOLANA_RPC_URL（需要支持 DAS 的 RPC，例如 Helius）。",
                                                        "Backend SOLANA_RPC_URL is not configured (must be a DAS-enabled RPC such as Helius)."
                                                    )
                                                } else if (!eligibility.dasSupported) {
                                                    genesisError = AppStrings.tr(
                                                        "当前 RPC 不支持 Genesis Token 检测，请配置支持 DAS 的 RPC。",
                                                        "The current RPC does not support Genesis Token detection. Configure a DAS-enabled RPC."
                                                    )
                                                } else if (!eligibility.hasGenesisToken) {
                                                    genesisError = null
                                                }
                                            } catch (e: Exception) {
                                                genesisError = e.message ?: AppStrings.tr("检测失败", "Check failed")
                                            } finally {
                                                genesisChecking = false
                                            }
                                        }
                                    } else {
                                        selectedPlan = plan.id
                                    }
                                },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    Color(0xFF14F195).copy(alpha = 0.12f)
                                else
                                    Color.White.copy(alpha = 0.04f)
                            ),
                            border = if (isSelected)
                                androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF14F195))
                            else
                                androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 16.dp, horizontal = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Text(
                                    text = plan.shortName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color(0xFF14F195) else Color.White,
                                    textAlign = TextAlign.Center
                                )

                                if (plan.kind == "genesis_trial") {
                                    Icon(
                                        imageVector = Icons.Rounded.CardGiftcard,
                                        contentDescription = null,
                                        modifier = Modifier.size(44.dp),
                                        tint = Color(0xFF14F195)
                                    )
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = plan.uiPriceText ?: getUsdcEquivalent(plan),
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = plan.uiPriceSubText ?: plan.pricePerMonth,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.5f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                plan.uiChipText?.let { chip ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    ) {
                                        Text(
                                            text = chip,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                if (plan.savings != null) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFFFFD700).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = plan.savings,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFFD700),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        val badgeText = plan.badgeText
                        if (!badgeText.isNullOrBlank()) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-8).dp),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF14F195)
                            ) {
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    maxLines = 2,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 下方显示选中方案的会员权益
            selectedPlanData?.let { plan ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    // 权益标题
                    Text(
                        text = AppStrings.tr("会员权益", "Membership benefits"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    // 权益列表
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.05f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            monthlyBenefits.forEachIndexed { index, feature ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 序号圆圈
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF14F195).copy(alpha = 0.2f),
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Text(
                                                text = "${index + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF14F195)
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    Text(
                                        text = feature,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                                
                                // 分隔线（最后一项不显示）
                                if (index < monthlyBenefits.size - 1) {
                                    Divider(
                                        color = Color.White.copy(alpha = 0.05f),
                                        modifier = Modifier.padding(start = 36.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // 支付方式标题
            Text(
                text = AppStrings.tr("选择支付方式", "Choose a payment method"),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            // 支付方式选择（USDC/SOL/SKR）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // USDC 支付（默认/推荐）
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedPaymentToken = "USDC" },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedPaymentToken == "USDC") Color(0xFF2775CA).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f),
                    border = if (selectedPaymentToken == "USDC") 
                        androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF2775CA)) 
                    else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "USDC",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedPaymentToken == "USDC") Color(0xFF2775CA) else Color.White
                        )
                        selectedPlanData?.let {
                            Text(
                                "${it.priceUsdc}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                
                // SOL 支付
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedPaymentToken = "SOL" },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedPaymentToken == "SOL") Color(0xFF14F195).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f),
                    border = if (selectedPaymentToken == "SOL") 
                        androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF14F195)) 
                    else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "SOL",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedPaymentToken == "SOL") Color(0xFF14F195) else Color.White
                        )
                        selectedPlanData?.let {
                            Text(
                                if (solPriceUsdc.isFinite() && solPriceUsdc > 0.0) {
                                    String.format("%.4f", it.priceUsdc / solPriceUsdc)
                                } else {
                                    "--"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                
                // SKR 支付
                if (skrMintValid) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedPaymentToken = "SKR" },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selectedPaymentToken == "SKR") Color(0xFFE040FB).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f),
                        border = if (selectedPaymentToken == "SKR")
                            androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFE040FB))
                        else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                "SKR",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedPaymentToken == "SKR") Color(0xFFE040FB) else Color.White
                            )
                            selectedPlanData?.let {
                                Text(
                                    if (skrPriceUsdc.isFinite() && skrPriceUsdc > 0.0) {
                                        String.format("%.0f", it.priceUsdc / skrPriceUsdc)
                                    } else {
                                        "--"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
            
            // 汇率说明（来自 Jupiter Ultra API）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoadingRates) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (ratesLoaded) {
                        if (skrMintValid) {
                            AppStrings.tr(
                                "实时汇率: 1 SOL ≈ \$${if (solPriceUsdc.isFinite() && solPriceUsdc > 0.0) String.format("%.2f", solPriceUsdc) else "--"}  |  1 SKR ≈ \$${if (skrPriceUsdc.isFinite() && skrPriceUsdc > 0.0) String.format("%.4f", skrPriceUsdc) else "--"}",
                                "Live rates: 1 SOL ≈ \$${if (solPriceUsdc.isFinite() && solPriceUsdc > 0.0) String.format("%.2f", solPriceUsdc) else "--"}  |  1 SKR ≈ \$${if (skrPriceUsdc.isFinite() && skrPriceUsdc > 0.0) String.format("%.4f", skrPriceUsdc) else "--"}"
                            )
                        } else {
                            AppStrings.tr(
                                "实时汇率: 1 SOL ≈ \$${if (solPriceUsdc.isFinite() && solPriceUsdc > 0.0) String.format("%.2f", solPriceUsdc) else "--"}",
                                "Live rates: 1 SOL ≈ \$${if (solPriceUsdc.isFinite() && solPriceUsdc > 0.0) String.format("%.2f", solPriceUsdc) else "--"}"
                            )
                        }
                    } else {
                        AppStrings.tr(
                            "参考汇率: 1 SOL ≈ \$${String.format("%.2f", solPriceUsdc)}",
                            "Reference: 1 SOL ≈ \$${String.format("%.2f", solPriceUsdc)}"
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
                if (!isLoadingRates) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "🔄",
                        modifier = Modifier.clickable {
                            coroutineScope.launch {
                                refreshRates()
                            }
                        }
                    )
                }
            }

            if (ratesLoaded) {
                LinearProgressIndicator(
                    progress = quoteCountdownProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = Color.White.copy(alpha = 0.25f),
                    trackColor = Color.White.copy(alpha = 0.06f)
                )
                Text(
                    text = AppStrings.trf(
                        "报价刷新倒计时 %d 秒",
                        "Refreshing in %d s",
                        quoteCountdownSecondsLeft
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
            
            // 状态说明
            if (!ratesLoaded) {
                Text(
                    text = AppStrings.tr(
                        "💡 当前无法获取实时汇率，将使用默认参考值",
                        "💡 Live rates unavailable; using default reference values"
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF14F195).copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            } else if (rateError != null) {
                Text(
                    text = "⚠ " + rateError,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFD700).copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 底部订阅按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Button(
                    onClick = {
                        if (selectedPlan != null && actualWalletAddress != null && !isProcessing) {
                            val wallet = actualWalletAddress!!
                            val plan = selectedPlanData
                            if (plan != null) {
                                disallowSelectMessage(plan.id)?.let { msg ->
                                    paymentError = msg
                                    return@Button
                                }

                                if (plan.autoRenew) {
                                    val desiredPlanType = when (plan.basePlanId) {
                                        "monthly" -> com.soulon.app.subscription.AutoRenewService.PLAN_MONTHLY
                                        "quarterly" -> com.soulon.app.subscription.AutoRenewService.PLAN_QUARTERLY
                                        "yearly" -> com.soulon.app.subscription.AutoRenewService.PLAN_YEARLY
                                        else -> 0
                                    }

                                    val upgradeRule = findAutoRenewUpgradeRule(plan.id, desiredPlanType)
                                    if (upgradeRule != null) {
                                        scheduleTargetPlanId = plan.id
                                        scheduleToPlanType = upgradeRule.optInt("toPlanType", desiredPlanType)
                                        scheduleDialogTitle = upgradeRule.optString("title", "确认升级").ifBlank { "确认升级" }
                                        scheduleDialogDescription = upgradeRule.optString("description").takeIf { it.isNotBlank() }
                                        showScheduleUpgradeDialog = true
                                        return@Button
                                    }
                                }
                            }
                            showPaymentDialog = true
                        } else if (actualWalletAddress == null) {
                            paymentError = AppStrings.tr("请先连接钱包", "Please connect your wallet first")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = selectedPlan != null && actualWalletAddress != null && !isProcessing,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF14F195),
                        disabledContainerColor = Color.White.copy(alpha = 0.1f)
                    )
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = AppStrings.tr("处理中...", "Processing..."),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.Black
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = AppStrings.tr("立即开通", "Subscribe now"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            selectedPlanData?.let {
                                Text(
                                    text = " · ${getDisplayPrice(it)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        if (showGenesisTrialDialog) {
            val eligible = genesisHasToken == true && genesisRedeemed == false && !genesisChecking
            LaunchedEffect(showGenesisTrialDialog) {
                if (showGenesisTrialDialog) {
                    genesisFollowedX = false
                }
            }
            AlertDialog(
                onDismissRequest = { if (!genesisProcessing) showGenesisTrialDialog = false },
                title = {
                    Text(
                        AppStrings.tr("Genesis 7 天体验", "Genesis 7-day trial"),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (genesisChecking) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    AppStrings.tr("正在检测 Genesis Token…", "Checking Genesis Token…"),
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        } else {
                            if (genesisHasToken == false) {
                                Text(
                                    AppStrings.tr(
                                        "未检测到 Seeker Genesis Token，无法领取体验。",
                                        "No Seeker Genesis Token detected. Trial not available."
                                    ),
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            } else if (genesisRedeemed == true) {
                                Text(
                                    AppStrings.tr(
                                        "该钱包已领取过体验卡，无法重复领取。",
                                        "This wallet has already redeemed the trial."
                                    ),
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            } else {
                                Text(
                                    AppStrings.tr(
                                        "你可领取 7 天会员体验，请点击关注官方 X 账号领取。",
                                        "You can claim a 7-day trial. Please follow our X account to claim."
                                    ),
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }

                        genesisError?.let { err ->
                            Text(
                                AppStrings.trf("错误：%s", "Error: %s", err),
                                color = Color(0xFFFF4444)
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    genesisFollowedX = true
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse("https://x.com/Soulon_Memo")
                                    }
                                    context.startActivity(intent)
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White.copy(alpha = 0.06f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("X", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold)
                                Column {
                                    Text(
                                        AppStrings.tr("关注官方 X 账号领取", "Follow our X account to claim"),
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "@Soulon_Memo",
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val wallet = actualWalletAddress ?: return@Button
                            if (!eligible || genesisProcessing || !genesisFollowedX) return@Button
                            genesisProcessing = true
                            genesisError = null
                            coroutineScope.launch {
                                try {
                                    android.widget.Toast.makeText(
                                        context,
                                        AppStrings.tr("领取时会有少量验证费用，请知晓", "A small verification fee may apply."),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    val memo = "Seeker Genesis Trial Registration"
                                    val mwaClient = com.soulon.app.wallet.MobileWalletAdapterClient(context)
                                    val payManager = com.soulon.app.payment.SolanaPayManager(context, mwaClient, solanaRpcClient)
                                    val paymentResult = payManager.paySol(
                                        activityResultSender,
                                        0.05,
                                        memo,
                                        wallet
                                    )

                                    when (paymentResult) {
                                        is com.soulon.app.payment.PaymentResult.Success -> {
                                            val sig = paymentResult.signature
                                            val redeemOk = genesisTrialService.redeem(
                                                wallet = wallet,
                                                signature = sig
                                            ).getOrElse { throw it }

                                            if (!redeemOk) {
                                                throw IllegalStateException(AppStrings.tr("领取失败", "Redeem failed"))
                                            }

                                            saveGenesisTrialSubscription(context, wallet, sig)
                                            genesisTxSignature = sig
                                            showGenesisTrialDialog = false
                                            showGenesisSuccessDialog = true
                                        }

                                        is com.soulon.app.payment.PaymentResult.NoWalletFound -> {
                                            genesisError = AppStrings.tr("未找到兼容的 Solana 钱包", "No compatible Solana wallet found")
                                        }

                                        is com.soulon.app.payment.PaymentResult.Error -> {
                                            genesisError = null
                                        }
                                    }
                                } catch (e: Exception) {
                                    genesisError = null
                                } finally {
                                    genesisProcessing = false
                                }
                            }
                        },
                        enabled = eligible && !genesisProcessing && genesisFollowedX,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14F195))
                    ) {
                        if (genesisProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                AppStrings.tr("领取体验", "Claim trial"),
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showGenesisTrialDialog = false },
                        enabled = !genesisProcessing
                    ) {
                        Text(AppStrings.cancel, color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }

        if (showGenesisSuccessDialog) {
            AlertDialog(
                onDismissRequest = { },
                icon = {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = Color(0xFF14F195).copy(alpha = 0.2f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color(0xFF14F195)
                            )
                        }
                    }
                },
                title = {
                    Text(
                        AppStrings.tr("领取成功！", "Claimed successfully!"),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            AppStrings.tr(
                                "你已获得 7 天会员体验。",
                                "You now have a 7-day membership trial."
                            ),
                            textAlign = TextAlign.Center
                        )
                        genesisTxSignature?.let { sig ->
                            Text(
                                AppStrings.trf(
                                    "交易签名: %s...%s",
                                    "Tx signature: %s...%s",
                                    sig.take(8),
                                    sig.takeLast(8)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showGenesisSuccessDialog = false
                            onSubscriptionSuccess()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14F195))
                    ) {
                        Text(AppStrings.tr("开始使用", "Get started"), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }

        // 支付确认弹窗
        if (showPaymentDialog && selectedPlanData != null) {
            AlertDialog(
                onDismissRequest = { if (!isProcessing) showPaymentDialog = false },
                title = {
                    Text(
                        AppStrings.tr("确认支付", "Confirm payment"),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(AppStrings.tr("您将支付：", "You will pay:"))
                        
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.1f)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(AppStrings.tr("方案", "Plan"), color = Color.White.copy(alpha = 0.6f))
                                    Text(selectedPlanData!!.name, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(AppStrings.tr("金额", "Amount"), color = Color.White.copy(alpha = 0.6f))
                                    Text(
                                        getDisplayPrice(selectedPlanData!!),
                                        fontWeight = FontWeight.Bold,
                                        color = when (selectedPaymentToken) {
                                            "SOL" -> Color(0xFF14F195)
                                            "SKR" -> Color(0xFFE040FB)
                                            else -> Color(0xFF2775CA)
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(AppStrings.tr("等值", "Equivalent"), color = Color.White.copy(alpha = 0.6f))
                                    Text(
                                        AppStrings.trf(
                                            "≈ \$%s USDC",
                                            "≈ \$%s USDC",
                                            selectedPlanData!!.priceUsdc
                                        ),
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(AppStrings.tr("时长", "Duration"), color = Color.White.copy(alpha = 0.6f))
                                    Text(selectedPlanData!!.duration, color = Color.White)
                                }
                            }
                        }
                        
                        Text(
                            AppStrings.trf(
                                "将发起 %s 转账完成支付，一次签名即可。",
                                "A %s transfer will be sent to complete payment with a single signature.",
                                selectedPaymentToken
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val now = System.currentTimeMillis()
                            val needsQuote = selectedPaymentToken != "USDC"
                            if (needsQuote) {
                                val quoteExpired = (lastRatesRefreshAt == 0L) || (now - lastRatesRefreshAt > quoteTtlMs)
                                if (quoteExpired) {
                                    paymentError = AppStrings.tr(
                                        "报价已过期，请等待自动刷新后重试",
                                        "Quote expired. Please wait for refresh and try again."
                                    )
                                    coroutineScope.launch { refreshRates() }
                                    return@Button
                                }

                                val reference = when (selectedPaymentToken) {
                                    "SKR" -> quoteSkrPriceAtDialog
                                    else -> quoteSolPriceAtDialog
                                }
                                val current = when (selectedPaymentToken) {
                                    "SKR" -> skrPriceUsdc
                                    else -> solPriceUsdc
                                }

                                if (reference > 0.0) {
                                    val deviation = kotlin.math.abs(current - reference) / reference
                                    if (deviation > maxSlippageRatio) {
                                        paymentError = AppStrings.trf(
                                            "汇率波动超过 %.2f%%，请刷新后重试",
                                            "Rate moved more than %.2f%%. Please refresh and try again.",
                                            maxSlippageRatio * 100
                                        )
                                        coroutineScope.launch { refreshRates() }
                                        return@Button
                                    }
                                }
                            }

                            isProcessing = true
                            paymentError = null
                            
                            coroutineScope.launch {
                                try {
                                    val plan = selectedPlanData!!
                                    val memo = "Soulon ${plan.name} - ${plan.duration}"
                                    
                                    // 使用 SolanaPayManager 进行支付
                                    val mwaClient = com.soulon.app.wallet.MobileWalletAdapterClient(context)
                                    val rpcClient = com.soulon.app.wallet.SolanaRpcClient()
                                    val payManager = com.soulon.app.payment.SolanaPayManager(context, mwaClient, rpcClient)
                                    
                                    // 获取发送方地址
                                    val senderAddr = actualWalletAddress
                                    Timber.i("订阅支付: 使用钱包地址 $senderAddr, 支付方式: $selectedPaymentToken")
                                    
                                    val result = when (selectedPaymentToken) {
                                        "USDC" -> {
                                            val usdcAmount = (plan.priceUsdc * 1_000_000).toLong()
                                            payManager.payToken(
                                                activityResultSender,
                                                com.soulon.app.payment.SolanaPayManager.PaymentToken.USDC,
                                                usdcAmount,
                                                memo,
                                                senderAddr
                                            )
                                        }

                                        "SKR" -> {
                                            if (!skrMintValid || !skrPriceUsdc.isFinite() || skrPriceUsdc <= 0.0) {
                                                throw IllegalStateException(AppStrings.tr("SKR 汇率不可用", "SKR rate unavailable"))
                                            }
                                            val skr = plan.priceUsdc / skrPriceUsdc
                                            val skrAtomic = (skr * 1_000_000).toLong()
                                            payManager.payToken(
                                                activityResultSender,
                                                com.soulon.app.payment.SolanaPayManager.PaymentToken.SKR,
                                                skrAtomic,
                                                memo,
                                                senderAddr
                                            )
                                        }

                                        else -> {
                                            if (!solPriceUsdc.isFinite() || solPriceUsdc <= 0.0) {
                                                throw IllegalStateException(AppStrings.tr("SOL 汇率不可用", "SOL rate unavailable"))
                                            }
                                            val solAmount = plan.priceUsdc / solPriceUsdc
                                            payManager.paySol(
                                                activityResultSender,
                                                solAmount,
                                                memo,
                                                senderAddr
                                            )
                                        }
                                    }
                                    
                                    when (result) {
                                        is com.soulon.app.payment.PaymentResult.Success -> {
                                            transactionSignature = result.signature
                                            saveSubscription(context, senderAddr ?: "", plan, result.signature, selectedPaymentToken, getDisplayPrice(plan))

                                            try {
                                                val prefs = WalletScope.scopedPrefs(context, "subscription_prefs", senderAddr ?: "")
                                                val existingExpiry = prefs.getLong("subscription_expiry", 0L)
                                                val baseTime = maxOf(System.currentTimeMillis(), existingExpiry)
                                                val endTime = baseTime + (plan.durationMonths * 30L * 24 * 60 * 60 * 1000)
                                                val apiClient = com.soulon.app.data.BackendApiClient.getInstance(context)
                                                val synced = apiClient.syncSubscription(
                                                    walletAddress = senderAddr ?: "",
                                                    planId = plan.basePlanId,
                                                    startDate = baseTime,
                                                    endDate = endTime,
                                                    amount = plan.priceUsdc,
                                                    transactionId = result.signature
                                                )
                                                if (synced) {
                                                    rewardsRepository.syncFromBackend(senderAddr ?: "")
                                                } else {
                                                    Timber.w("订阅开通同步失败（支付已成功）")
                                                }
                                            } catch (e: Exception) {
                                                Timber.w(e, "订阅开通同步异常（支付已成功）")
                                            }
                                            
                                            // 如果选择了连续方案，创建自动续费订阅
                                            if (plan.autoRenew && autoRenewEnabled) {
                                                try {
                                                    val autoRenewService = com.soulon.app.subscription.AutoRenewService.getInstance(context)
                                                    val planTypeCode = when (plan.basePlanId) {
                                                        "monthly" -> com.soulon.app.subscription.AutoRenewService.PLAN_MONTHLY
                                                        "quarterly" -> com.soulon.app.subscription.AutoRenewService.PLAN_QUARTERLY
                                                        "yearly" -> com.soulon.app.subscription.AutoRenewService.PLAN_YEARLY
                                                        else -> com.soulon.app.subscription.AutoRenewService.PLAN_MONTHLY
                                                    }
                                                    val renewalAmount = plan.renewalPriceUsdc ?: plan.priceUsdc
                                                    autoRenewService.createAutoRenewSubscription(
                                                        walletAddress = senderAddr ?: "",
                                                        planType = planTypeCode,
                                                        amountUsdc = renewalAmount
                                                    )
                                                    Timber.i("自动续费订阅已创建: plan=${plan.id}, amount=$renewalAmount USDC")
                                                } catch (e: Exception) {
                                                    Timber.w(e, "创建自动续费失败，但支付已成功")
                                                }
                                            }
                                            
                                            showPaymentDialog = false
                                            showSuccessDialog = true
                                            Timber.i("订阅支付成功: ${getDisplayPrice(plan)}")
                                        }
                                        is com.soulon.app.payment.PaymentResult.NoWalletFound -> {
                                            paymentError = "未找到兼容的 Solana 钱包"
                                        }
                                        is com.soulon.app.payment.PaymentResult.Error -> {
                                            paymentError = "支付失败: ${result.message}"
                                        }
                                    }
                                    
                                } catch (e: Exception) {
                                    paymentError = "支付失败: ${e.message}"
                                    Timber.e(e, "订阅支付失败")
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF14F195)
                        )
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                AppStrings.tr("确认支付", "Confirm payment"),
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showPaymentDialog = false },
                        enabled = !isProcessing
                    ) {
                        Text(AppStrings.cancel, color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }

        if (showScheduleUpgradeDialog && scheduleTargetPlanId != null) {
            val wallet = actualWalletAddress
            val target = displayPlans.find { it.id == scheduleTargetPlanId }
            if (target == null) {
                showScheduleUpgradeDialog = false
                scheduleTargetPlanId = null
            }
        }
        if (showScheduleUpgradeDialog && scheduleTargetPlanId != null) {
            val wallet = actualWalletAddress
            val target = displayPlans.first { it.id == scheduleTargetPlanId }
            AlertDialog(
                onDismissRequest = { if (!isProcessing) showScheduleUpgradeDialog = false },
                title = { Text(scheduleDialogTitle, fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            scheduleDialogDescription
                                ?: AppStrings.tr(
                                    "将提交一次升级请求。升级将于当前周期到期后生效。",
                                    "An upgrade request will be submitted. It will take effect after the current billing cycle ends."
                                ),
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        if (autoRenewNextPaymentAt != 0L) {
                            Text(
                                AppStrings.trf(
                                    "生效时间：%s",
                                    "Effective: %s",
                                    autoRenewService.formatNextPaymentDate(autoRenewNextPaymentAt)
                                ),
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                        Text(
                            AppStrings.trf(
                                "下一次扣款金额：\$%s USDC",
                                "Next charge: \$%s USDC",
                                String.format("%.2f", (target.renewalPriceUsdc ?: target.priceUsdc))
                            ),
                            color = Color.White
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (wallet == null) return@Button
                            isProcessing = true
                            coroutineScope.launch {
                                try {
                                    val res = autoRenewService.schedulePlanChange(
                                        walletAddress = wallet,
                                        targetPlanType = scheduleToPlanType,
                                        targetAmountUsdc = target.renewalPriceUsdc ?: target.priceUsdc
                                    )
                                    res.fold(
                                        onSuccess = {
                                            autoRenewService.syncStatus(wallet)
                                            autoRenewActive = autoRenewService.isAutoRenewEnabled(wallet)
                                            autoRenewPlanType = autoRenewService.getCurrentPlanType(wallet)
                                            autoRenewNextPaymentAt = autoRenewService.getNextPaymentAt(wallet)
                                            pendingPlanType = autoRenewService.getPendingPlanType(wallet)
                                            pendingEffectiveAt = autoRenewService.getPendingEffectiveAt(wallet)
                                            cancelLockedUntil = autoRenewService.getCancelLockedUntil(wallet)
                                            showScheduleUpgradeDialog = false
                                            showScheduleUpgradeSuccessDialog = true
                                        },
                                        onFailure = { e ->
                                            paymentError = e.message ?: AppStrings.tr("升级失败", "Upgrade failed")
                                        }
                                    )
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        enabled = wallet != null && !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14F195))
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                AppStrings.tr("确认升级", "Confirm upgrade"),
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showScheduleUpgradeDialog = false }, enabled = !isProcessing) {
                        Text(AppStrings.cancel, color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }

        if (showScheduleUpgradeSuccessDialog) {
            AlertDialog(
                onDismissRequest = { },
                icon = {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = Color(0xFF14F195).copy(alpha = 0.2f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color(0xFF14F195)
                            )
                        }
                    }
                },
                title = {
                    Text(
                        AppStrings.tr("升级已提交", "Upgrade submitted"),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    val dateText = if (autoRenewNextPaymentAt != 0L) {
                        autoRenewService.formatNextPaymentDate(autoRenewNextPaymentAt)
                    } else {
                        null
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val targetName = autoRenewService.getPlanName(scheduleToPlanType)
                        Text(
                            AppStrings.tr(
                                "将于${dateText?.let { " $it " } ?: "下次扣款时 "}自动切换为${targetName}",
                                "Will automatically switch to ${targetName} ${dateText?.let { "on $it" } ?: "at the next charge"}"
                            ),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            AppStrings.tr(
                                "升级后的第一笔扣款前不可取消订阅合约",
                                "Cancellation is disabled until the first charge after the upgrade"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showScheduleUpgradeSuccessDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14F195))
                    ) {
                        Text(AppStrings.tr("知道了", "Got it"), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }
        
        // 支付成功弹窗
        if (showSuccessDialog) {
            AlertDialog(
                onDismissRequest = { },
                icon = {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = Color(0xFF14F195).copy(alpha = 0.2f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color(0xFF14F195)
                            )
                        }
                    }
                },
                title = {
                    Text(
                        AppStrings.tr("支付成功！", "Payment successful!"),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            AppStrings.trf(
                                "恭喜您成为 %s",
                                "Welcome! You’re now %s",
                                selectedPlanData?.name
                            ),
                            textAlign = TextAlign.Center
                        )
                        transactionSignature?.let { sig ->
                            Text(
                                AppStrings.trf(
                                    "交易签名: %s...%s",
                                    "Tx signature: %s...%s",
                                    sig.take(8),
                                    sig.takeLast(8)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showSuccessDialog = false
                            onSubscriptionSuccess()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF14F195)
                        )
                    ) {
                        Text(AppStrings.tr("开始使用", "Get started"), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }
        
        // 错误提示
        if (paymentError != null) {
            LaunchedEffect(paymentError) {
                kotlinx.coroutines.delay(3000)
                paymentError = null
            }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 100.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFF4444).copy(alpha = 0.9f)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = paymentError!!,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 质押项目数据模型
 */
data class StakingProjectData(
    val id: String,
    val name: String,
    val token: String,
    val icon: String,
    val apy: Float,
    val tvl: String,
    val minStake: String,
    val maxStake: String,
    val description: String,
    val longDescription: String,
    val status: StakingStatus,
    val userStaked: Double = 0.0,
    val userRewards: Double = 0.0,
    val stakingStartTime: Long? = null,
    val lockPeriodDays: Int = 0,
    val participants: Int = 0,
    val riskLevel: String = "中等",
    val features: List<String> = emptyList()
)

enum class StakingStatus {
    ACTIVE,      // 进行中
    COMING_SOON, // 即将开放
    ENDED,       // 已结束
    FULL         // 已满额
}

/**
 * 生态质押页面 - 项目列表
 * 真实集成链上质押功能
 */
@Composable
fun EcoStakingScreen(
    walletAddress: String?,
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    onNavigateBack: () -> Unit
) {
    // 当前查看的项目详情
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    
    // 质押项目列表
    val projects = remember {
        listOf(
            StakingProjectData(
                id = "memo_stake",
                name = "MEMO 质押池",
                token = "MEMO",
                icon = "🪙",
                apy = 18.5f,
                tvl = "1.2M MEMO",
                minStake = "100 MEMO",
                maxStake = "100,000 MEMO",
                description = "质押 MEMO 获取平台收益分成",
                longDescription = "MEMO 质押池是平台的核心质押产品。通过质押 MEMO 代币，您可以获得平台收益的分成，包括交易手续费、AI 服务费等。质押期间您的代币将被锁定，解锁需要 7 天冷却期。",
                status = StakingStatus.ACTIVE,
                userStaked = 500.0,
                userRewards = 12.5,
                stakingStartTime = System.currentTimeMillis() - 15 * 24 * 60 * 60 * 1000L,
                lockPeriodDays = 30,
                participants = 1256,
                riskLevel = "低",
                features = listOf("每日收益发放", "7天解锁冷却", "复利自动质押", "治理投票权")
            ),
            StakingProjectData(
                id = "sol_stake",
                name = "SOL 生态质押",
                token = "SOL",
                icon = "◎",
                apy = 12.0f,
                tvl = "50K SOL",
                minStake = "1 SOL",
                maxStake = "1,000 SOL",
                description = "支持生态发展，获取 MEMO 奖励",
                longDescription = "SOL 生态质押帮助我们建设更强大的 Solana 生态。您的 SOL 将用于支持验证节点和生态项目，作为回报您将获得 MEMO 代币奖励。",
                status = StakingStatus.ACTIVE,
                userStaked = 0.0,
                lockPeriodDays = 14,
                participants = 892,
                riskLevel = "低",
                features = listOf("SOL 原生质押", "MEMO 奖励", "14天锁定期", "生态治理权")
            ),
            StakingProjectData(
                id = "lp_stake",
                name = "LP 流动性挖矿",
                token = "LP",
                icon = "💧",
                apy = 35.0f,
                tvl = "800K USD",
                minStake = "50 USD",
                maxStake = "50,000 USD",
                description = "提供 MEMO/SOL 流动性获取高收益",
                longDescription = "流动性挖矿是为 MEMO/SOL 交易对提供流动性的高收益产品。您需要同时提供 MEMO 和 SOL，将获得 LP 代币凭证。风险较高但收益丰厚。",
                status = StakingStatus.ACTIVE,
                userStaked = 0.0,
                lockPeriodDays = 7,
                participants = 456,
                riskLevel = "高",
                features = listOf("双币质押", "高 APY", "无常损失风险", "随时可退出")
            ),
            StakingProjectData(
                id = "nft_stake",
                name = "NFT 质押",
                token = "NFT",
                icon = "🎨",
                apy = 25.0f,
                tvl = "500 NFTs",
                minStake = "1 NFT",
                maxStake = "10 NFT",
                description = "质押记忆 NFT 获取专属奖励",
                longDescription = "质押您的记忆 NFT，获取专属的 MEMO 奖励和稀有空投资格。不同稀有度的 NFT 获得的奖励倍数不同。",
                status = StakingStatus.COMING_SOON,
                lockPeriodDays = 30,
                participants = 0,
                riskLevel = "中等",
                features = listOf("NFT 质押", "稀有度加成", "空投资格", "专属权益")
            )
        )
    }
    
    // 用户总质押统计
    val totalStaked = projects.sumOf { it.userStaked }
    val totalRewards = projects.sumOf { it.userRewards }
    
    // 如果选中了项目，显示详情页
    if (selectedProjectId != null) {
        val project = projects.find { it.id == selectedProjectId }
        if (project != null) {
            StakingProjectDetailScreen(
                project = project,
                walletAddress = walletAddress,
                activityResultSender = activityResultSender,
                onNavigateBack = { selectedProjectId = null }
            )
            return
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部导航栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = AppStrings.tr("生态质押", "Eco staking"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                // 钱包地址
                if (walletAddress != null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF14F195), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${walletAddress.take(4)}...${walletAddress.takeLast(4)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 质押总览卡片
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF14F195).copy(alpha = 0.2f),
                                            Color(0xFF9945FF).copy(alpha = 0.2f)
                                        )
                                    )
                                )
                                .padding(20.dp)
                        ) {
                            Column {
                                Text(
                                    text = AppStrings.tr("我的质押", "My staking"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = if (totalStaked > 0) "$${String.format("%.2f", totalStaked)}" else "--",
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = AppStrings.tr("总质押价值", "Total staked value"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = if (totalRewards > 0) "+" + String.format("%.2f", totalRewards) else "--",
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF14F195)
                                        )
                                        Text(
                                            text = AppStrings.tr("累计收益", "Total rewards"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 质押项目标题
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = AppStrings.tr("质押项目", "Staking projects"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = AppStrings.trf(
                                "%d 个活跃",
                                "%d active",
                                projects.count { it.status == StakingStatus.ACTIVE }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF14F195)
                        )
                    }
                }
                
                // 质押项目列表
                items(projects.size) { index ->
                    val project = projects[index]
                    StakingProjectCard(
                        project = project,
                        onClick = { selectedProjectId = project.id }
                    )
                }
                
                // 底部间距
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * 质押项目卡片
 */
@Composable
private fun StakingProjectCard(
    project: StakingProjectData,
    onClick: () -> Unit
) {
    val isActive = project.status == StakingStatus.ACTIVE
    val hasStaked = project.userStaked > 0
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isActive) { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasStaked)
                Color(0xFF14F195).copy(alpha = 0.08f)
            else
                Color.White.copy(alpha = 0.05f)
        ),
        border = if (hasStaked)
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF14F195).copy(alpha = 0.3f))
        else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 顶部：图标、名称、状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 项目图标
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.1f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = project.icon,
                                fontSize = 22.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = project.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                    }
                }
                
                // 状态标签
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (project.status) {
                        StakingStatus.ACTIVE -> Color(0xFF14F195).copy(alpha = 0.2f)
                        StakingStatus.COMING_SOON -> Color(0xFFFFD700).copy(alpha = 0.2f)
                        StakingStatus.ENDED -> Color.Gray.copy(alpha = 0.2f)
                        StakingStatus.FULL -> Color(0xFFFF6B6B).copy(alpha = 0.2f)
                    }
                ) {
                    Text(
                        text = when (project.status) {
                            StakingStatus.ACTIVE -> AppStrings.tr("进行中", "Active")
                            StakingStatus.COMING_SOON -> AppStrings.tr("即将开放", "Coming soon")
                            StakingStatus.ENDED -> AppStrings.tr("已结束", "Ended")
                            StakingStatus.FULL -> AppStrings.tr("已满额", "Full")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = when (project.status) {
                            StakingStatus.ACTIVE -> Color(0xFF14F195)
                            StakingStatus.COMING_SOON -> Color(0xFFFFD700)
                            StakingStatus.ENDED -> Color.Gray
                            StakingStatus.FULL -> Color(0xFFFF6B6B)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 中部：APY 和数据
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // APY
                Column {
                    Text(
                        text = "APY",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "${project.apy}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF14F195)
                    )
                }
                
                // TVL
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "TVL",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = project.tvl,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                // 参与人数
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = AppStrings.tr("参与者", "Participants"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "${project.participants}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                // 风险等级
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = AppStrings.tr("风险", "Risk"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    val riskLabel = when (project.riskLevel) {
                        "低" -> AppStrings.tr("低", "Low")
                        "中等" -> AppStrings.tr("中等", "Medium")
                        else -> AppStrings.tr(project.riskLevel, project.riskLevel)
                    }
                    Text(
                        text = riskLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (project.riskLevel) {
                            "低" -> Color(0xFF14F195)
                            "中等" -> Color(0xFFFFD700)
                            else -> Color(0xFFFF6B6B)
                        }
                    )
                }
            }
            
            // 如果用户有质押，显示质押进度
            if (hasStaked) {
                Spacer(modifier = Modifier.height(16.dp))
                
                // 分隔线
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.1f))
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 用户质押信息
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = AppStrings.tr("我的质押", "My staking"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "${project.userStaked} ${project.token}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = AppStrings.tr("累计收益", "Total rewards"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "+${project.userRewards} ${project.token}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF14F195)
                        )
                    }
                }
                
                // 质押进度条
                if (project.stakingStartTime != null && project.lockPeriodDays > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val elapsedDays = ((System.currentTimeMillis() - project.stakingStartTime) / (24 * 60 * 60 * 1000)).toInt()
                    val progress = (elapsedDays.toFloat() / project.lockPeriodDays).coerceIn(0f, 1f)
                    
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = AppStrings.tr("锁定进度", "Lock progress"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = AppStrings.trf(
                                    "%d / %d 天",
                                    "%d / %d days",
                                    elapsedDays,
                                    project.lockPeriodDays
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                Color(0xFF14F195),
                                                Color(0xFF9945FF)
                                            )
                                        )
                                    )
                            )
                        }
                    }
                }
            }
            
            // 底部箭头
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = AppStrings.tr("查看详情", "View details"),
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 质押项目详情页
 * 真实集成链上质押功能
 */
@Composable
private fun StakingProjectDetailScreen(
    project: StakingProjectData,
    walletAddress: String?,
    activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("staking_prefs", android.content.Context.MODE_PRIVATE) }
    
    var stakeAmount by remember { mutableStateOf("") }
    var isStaking by remember { mutableStateOf(false) }
    var showStakeDialog by remember { mutableStateOf(false) }
    var showWithdrawDialog by remember { mutableStateOf(false) }
    var stakingError by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }
    
    // 从本地读取用户质押数据（实际应从链上读取）
    val userStakedKey = "staked_${project.id}_${walletAddress}"
    var actualUserStaked by remember { 
        mutableStateOf(prefs.getFloat(userStakedKey, project.userStaked.toFloat()).toDouble()) 
    }
    val userRewardsKey = "rewards_${project.id}_${walletAddress}"
    var actualUserRewards by remember {
        mutableStateOf(prefs.getFloat(userRewardsKey, project.userRewards.toFloat()).toDouble())
    }
    val stakingStartKey = "staking_start_${project.id}_${walletAddress}"
    val actualStakingStart = remember { 
        prefs.getLong(stakingStartKey, project.stakingStartTime ?: 0L)
    }
    
    val hasStaked = actualUserStaked > 0
    val elapsedDays = if (actualStakingStart > 0) {
        ((System.currentTimeMillis() - actualStakingStart) / (24 * 60 * 60 * 1000)).toInt()
    } else 0
    val progress = if (project.lockPeriodDays > 0 && hasStaked) {
        (elapsedDays.toFloat() / project.lockPeriodDays).coerceIn(0f, 1f)
    } else 0f
    
    // 模拟收益计算（每天收益 = 质押量 * APY / 365）
    LaunchedEffect(actualUserStaked, elapsedDays) {
        if (hasStaked && elapsedDays > 0) {
            val dailyRate = project.apy / 100f / 365f
            val newRewards = actualUserStaked * dailyRate * elapsedDays
            if (newRewards > actualUserRewards) {
                actualUserRewards = newRewards
                prefs.edit().putFloat(userRewardsKey, newRewards.toFloat()).apply()
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部导航栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 项目头部卡片
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF14F195).copy(alpha = 0.15f),
                                            Color(0xFF9945FF).copy(alpha = 0.15f)
                                        )
                                    )
                                )
                                .padding(24.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 项目图标
                                Surface(
                                    modifier = Modifier.size(72.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color.White.copy(alpha = 0.1f)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = project.icon,
                                            fontSize = 36.sp
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Text(
                                    text = project.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // APY 大字显示
                                Row(
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Text(
                                        text = "${project.apy}",
                                        style = MaterialTheme.typography.displaySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF14F195)
                                    )
                                    Text(
                                        text = AppStrings.tr("% APY", "% APY"),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF14F195),
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 如果用户有质押，显示质押状态
                if (hasStaked) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF14F195).copy(alpha = 0.1f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp)
                            ) {
                                Text(
                                    text = AppStrings.tr("我的质押", "My staking"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = AppStrings.tr("质押数量", "Staked"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                        Text(
                                            text = String.format("%.2f", actualUserStaked) + " " + project.token,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = AppStrings.tr("累计收益", "Total rewards"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                        Text(
                                            text = "+" + String.format("%.4f", actualUserRewards) + " " + project.token,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF14F195)
                                        )
                                    }
                                }
                                
                                // 质押进度
                                if (project.lockPeriodDays > 0) {
                                    Spacer(modifier = Modifier.height(20.dp))
                                    
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = AppStrings.tr("锁定进度", "Lock progress"),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.White.copy(alpha = 0.7f)
                                            )
                                            Text(
                                                text = if (progress >= 1f)
                                                    AppStrings.tr("已解锁", "Unlocked")
                                                else
                                                    AppStrings.trf(
                                                        "%d / %d 天",
                                                        "%d / %d days",
                                                        elapsedDays,
                                                        project.lockPeriodDays
                                                    ),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (progress >= 1f) Color(0xFF14F195) else Color.White
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(10.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(Color.White.copy(alpha = 0.1f))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(progress)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(
                                                        Brush.horizontalGradient(
                                                            colors = listOf(
                                                                Color(0xFF14F195),
                                                                Color(0xFF9945FF)
                                                            )
                                                        )
                                                    )
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Text(
                                            text = if (progress >= 1f) 
                                                AppStrings.tr("您的质押已解锁，可随时提取", "Your stake is unlocked and can be withdrawn anytime")
                                            else 
                                                AppStrings.trf(
                                                    "剩余 %d 天解锁",
                                                    "%d days remaining",
                                                    project.lockPeriodDays - elapsedDays
                                                ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 项目数据
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.05f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Text(
                                text = AppStrings.tr("项目数据", "Project data"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // 数据行
                            val riskLabel = when (project.riskLevel) {
                                "低" -> AppStrings.tr("低", "Low")
                                "中等" -> AppStrings.tr("中等", "Medium")
                                else -> AppStrings.tr(project.riskLevel, project.riskLevel)
                            }
                            listOf(
                                AppStrings.tr("TVL", "TVL") to project.tvl,
                                AppStrings.tr("参与者", "Participants") to AppStrings.trf(
                                    "%d 人",
                                    "%d people",
                                    project.participants
                                ),
                                AppStrings.tr("最低质押", "Min stake") to project.minStake,
                                AppStrings.tr("最高质押", "Max stake") to project.maxStake,
                                AppStrings.tr("锁定期", "Lock period") to AppStrings.trf(
                                    "%d 天",
                                    "%d days",
                                    project.lockPeriodDays
                                ),
                                AppStrings.tr("风险等级", "Risk") to riskLabel
                            ).forEach { (label, value) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 项目特点
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.05f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Text(
                                text = AppStrings.tr("项目特点", "Features"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            project.features.forEach { feature ->
                                Row(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF14F195),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = feature,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 项目描述
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.05f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Text(
                                text = AppStrings.tr("项目介绍", "About"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                text = project.longDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
                
                // 底部间距
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
            
            // 底部操作区
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF0A0A0F),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    if (project.status == StakingStatus.ACTIVE) {
                        // 质押金额输入
                        OutlinedTextField(
                            value = stakeAmount,
                            onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) stakeAmount = it },
                            label = { Text(AppStrings.tr("质押数量", "Stake amount")) },
                            placeholder = {
                                Text(
                                    AppStrings.trf(
                                        "输入 %s 数量",
                                        "Enter %s amount",
                                        project.token
                                    )
                                )
                            },
                            suffix = { Text(project.token, color = Color.White.copy(alpha = 0.5f)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF14F195),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                cursorColor = Color(0xFF14F195),
                                focusedLabelColor = Color(0xFF14F195),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // 质押/提取按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (hasStaked && progress >= 1f) {
                                // 可以提取
                                OutlinedButton(
                                    onClick = { showWithdrawDialog = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF14F195)),
                                    enabled = !isStaking
                                ) {
                                    Text(
                                        text = AppStrings.tr("提取", "Withdraw"),
                                        color = Color(0xFF14F195),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            
                            Button(
                                onClick = { 
                                    if (stakeAmount.isNotEmpty() && stakeAmount.toDoubleOrNull() != null) {
                                        showStakeDialog = true
                                    } else {
                                        stakingError = AppStrings.tr("请输入有效的质押数量", "Please enter a valid stake amount")
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF14F195)
                                ),
                                enabled = !isStaking && walletAddress != null && stakeAmount.isNotBlank()
                            ) {
                                if (isStaking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.Black,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        text = if (hasStaked) "追加质押" else "立即质押",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        // 不可质押状态
                        Button(
                            onClick = { },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            enabled = false,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                disabledContainerColor = Color.White.copy(alpha = 0.1f)
                            )
                        ) {
                            Text(
                                text = when (project.status) {
                                    StakingStatus.COMING_SOON -> "即将开放"
                                    StakingStatus.ENDED -> "已结束"
                                    StakingStatus.FULL -> "已满额"
                                    else -> "不可用"
                                },
                                color = Color.White.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        
        // 质押确认弹窗
        if (showStakeDialog) {
            val amount = stakeAmount.toDoubleOrNull() ?: 0.0
            
            AlertDialog(
                onDismissRequest = { if (!isStaking) showStakeDialog = false },
                title = { Text(AppStrings.tr("确认质押", "Confirm stake"), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            AppStrings.trf(
                                "您将质押 %s %s 到 %s",
                                "You will stake %s %s into %s",
                                stakeAmount,
                                project.token,
                                project.name
                            )
                        )
                        
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.1f)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(AppStrings.tr("预期年化", "APY"), color = Color.White.copy(alpha = 0.6f))
                                    Text("${project.apy}% APY", color = Color(0xFF14F195), fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(AppStrings.tr("锁定期", "Lock period"), color = Color.White.copy(alpha = 0.6f))
                                    Text(
                                        AppStrings.trf(
                                            "%d 天",
                                            "%d days",
                                            project.lockPeriodDays
                                        ),
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(AppStrings.tr("预计收益", "Estimated rewards"), color = Color.White.copy(alpha = 0.6f))
                                    val expectedReward = amount * project.apy / 100 * project.lockPeriodDays / 365
                                    Text("+" + String.format("%.4f", expectedReward) + " " + project.token, color = Color(0xFF14F195))
                                }
                            }
                        }
                        
                        Text(
                            AppStrings.tr(
                                "质押将通过智能合约完成，请在钱包中确认交易。锁定期内无法提取。",
                                "Staking will be executed via a smart contract. Please confirm the transaction in your wallet. Funds cannot be withdrawn during the lock period."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isStaking = true
                            coroutineScope.launch {
                                try {
                                    // 真实链上质押交易
                                    val mwaClient = com.soulon.app.wallet.MobileWalletAdapterClient(context)
                                    val rpcClient = com.soulon.app.wallet.SolanaRpcClient()
                                    val stakingManager = com.soulon.app.staking.StakingTransactionManager(
                                        context, mwaClient, rpcClient
                                    )
                                    
                                    val result = if (project.token == "SOL") {
                                        stakingManager.stakeSol(
                                            sender = activityResultSender,
                                            amount = amount,
                                            projectId = project.id,
                                            lockPeriodDays = project.lockPeriodDays
                                        )
                                    } else {
                                        stakingManager.stakeToken(
                                            sender = activityResultSender,
                                            amount = amount,
                                            tokenMint = "", // TODO: 从项目配置获取
                                            tokenSymbol = project.token,
                                            projectId = project.id,
                                            lockPeriodDays = project.lockPeriodDays
                                        )
                                    }
                                    
                                    when (result) {
                                        is com.soulon.app.staking.StakingTransactionManager.StakingResult.Success -> {
                                            // 更新本地存储
                                            val newStaked = actualUserStaked + amount
                                            prefs.edit()
                                                .putFloat(userStakedKey, newStaked.toFloat())
                                                .putLong(stakingStartKey, System.currentTimeMillis())
                                                .putString("${userStakedKey}_tx", result.signature)
                                                .apply()
                                            
                                            actualUserStaked = newStaked
                                            showStakeDialog = false
                                            stakeAmount = ""
                                            successMessage = AppStrings.trf(
                                                "成功质押 %s %s\n交易: %s...",
                                                "Staked successfully: %s %s\nTx: %s...",
                                                amount,
                                                project.token,
                                                result.signature.take(8)
                                            )
                                            showSuccessDialog = true
                                        }
                                        is com.soulon.app.staking.StakingTransactionManager.StakingResult.NoWalletFound -> {
                                            stakingError = AppStrings.tr("未找到兼容的 Solana 钱包", "No compatible Solana wallet found")
                                        }
                                        is com.soulon.app.staking.StakingTransactionManager.StakingResult.InsufficientBalance -> {
                                            stakingError = AppStrings.trf(
                                                "余额不足: 需要 %s，可用 %s",
                                                "Insufficient balance: need %s, available %s",
                                                result.required,
                                                String.format("%.4f", result.available)
                                            )
                                        }
                                        is com.soulon.app.staking.StakingTransactionManager.StakingResult.Error -> {
                                            stakingError = AppStrings.trf(
                                                "质押失败: %s",
                                                "Stake failed: %s",
                                                result.message
                                            )
                                        }
                                    }
                                    
                                } catch (e: Exception) {
                                    stakingError = AppStrings.trf(
                                        "质押失败: %s",
                                        "Stake failed: %s",
                                        e.message
                                    )
                                    timber.log.Timber.e(e, "质押交易失败")
                                } finally {
                                    isStaking = false
                                }
                            }
                        },
                        enabled = !isStaking,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14F195))
                    ) {
                        if (isStaking) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                        } else {
                            Text(AppStrings.tr("确认质押", "Confirm stake"), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStakeDialog = false }, enabled = !isStaking) {
                        Text(AppStrings.cancel, color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }
        
        // 提取确认弹窗
        if (showWithdrawDialog) {
            AlertDialog(
                onDismissRequest = { if (!isStaking) showWithdrawDialog = false },
                title = { Text(AppStrings.tr("确认提取", "Confirm withdraw"), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            AppStrings.trf(
                                "您将提取所有质押的 %s",
                                "You will withdraw all staked %s",
                                project.token
                            )
                        )
                        
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.1f)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(AppStrings.tr("质押本金", "Principal"), color = Color.White.copy(alpha = 0.6f))
                                    Text(String.format("%.2f", actualUserStaked) + " " + project.token, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(AppStrings.tr("累计收益", "Total rewards"), color = Color.White.copy(alpha = 0.6f))
                                    Text("+" + String.format("%.4f", actualUserRewards) + " " + project.token, color = Color(0xFF14F195), fontWeight = FontWeight.Bold)
                                }
                                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.1f))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(AppStrings.tr("总计获得", "Total"), color = Color.White)
                                    Text(String.format("%.4f", actualUserStaked + actualUserRewards) + " " + project.token, color = Color(0xFF14F195), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isStaking = true
                            coroutineScope.launch {
                                try {
                                    // 真实链上解押交易
                                    val mwaClient = com.soulon.app.wallet.MobileWalletAdapterClient(context)
                                    val rpcClient = com.soulon.app.wallet.SolanaRpcClient()
                                    val stakingManager = com.soulon.app.staking.StakingTransactionManager(
                                        context, mwaClient, rpcClient
                                    )
                                    
                                    val totalWithdrawn = actualUserStaked + actualUserRewards
                                    
                                    val result = stakingManager.unstake(
                                        sender = activityResultSender,
                                        amount = totalWithdrawn,
                                        tokenSymbol = project.token,
                                        projectId = project.id
                                    )
                                    
                                    when (result) {
                                        is com.soulon.app.staking.StakingTransactionManager.StakingResult.Success -> {
                                            // 清除本地存储
                                            prefs.edit()
                                                .remove(userStakedKey)
                                                .remove(userRewardsKey)
                                                .remove(stakingStartKey)
                                                .apply()
                                            
                                            actualUserStaked = 0.0
                                            actualUserRewards = 0.0
                                            showWithdrawDialog = false
                                            successMessage = AppStrings.trf(
                                                "成功提取 %s %s\n交易: %s...",
                                                "Withdrawn successfully: %s %s\nTx: %s...",
                                                String.format("%.4f", totalWithdrawn),
                                                project.token,
                                                result.signature.take(8)
                                            )
                                            showSuccessDialog = true
                                        }
                                        is com.soulon.app.staking.StakingTransactionManager.StakingResult.NoWalletFound -> {
                                            stakingError = AppStrings.tr("未找到兼容的 Solana 钱包", "No compatible Solana wallet found")
                                        }
                                        is com.soulon.app.staking.StakingTransactionManager.StakingResult.Error -> {
                                            stakingError = AppStrings.trf(
                                                "提取失败: %s",
                                                "Withdraw failed: %s",
                                                result.message
                                            )
                                        }
                                        else -> {
                                            stakingError = AppStrings.tr("提取失败: 未知错误", "Withdraw failed: unknown error")
                                        }
                                    }
                                    
                                } catch (e: Exception) {
                                    stakingError = AppStrings.trf(
                                        "提取失败: %s",
                                        "Withdraw failed: %s",
                                        e.message
                                    )
                                    timber.log.Timber.e(e, "解押交易失败")
                                } finally {
                                    isStaking = false
                                }
                            }
                        },
                        enabled = !isStaking,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14F195))
                    ) {
                        if (isStaking) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                        } else {
                            Text(AppStrings.tr("确认提取", "Confirm withdraw"), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWithdrawDialog = false }, enabled = !isStaking) {
                        Text(AppStrings.cancel, color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }
        
        // 成功提示
        if (showSuccessDialog) {
            AlertDialog(
                onDismissRequest = { showSuccessDialog = false },
                icon = {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = Color(0xFF14F195).copy(alpha = 0.2f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(32.dp), Color(0xFF14F195))
                        }
                    }
                },
                title = { Text(AppStrings.tr("操作成功", "Success"), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                text = { Text(successMessage, textAlign = TextAlign.Center) },
                confirmButton = {
                    Button(
                        onClick = { showSuccessDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14F195))
                    ) {
                        Text(AppStrings.tr("确定", "OK"), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }
        
        // 错误提示
        if (stakingError != null) {
            LaunchedEffect(stakingError) {
                kotlinx.coroutines.delay(3000)
                stakingError = null
            }
            
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 100.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFF4444).copy(alpha = 0.9f)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = Color.White)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stakingError!!, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * 订阅管理页面 - 管理会员订阅和自动续费
 */
@Composable
fun SubscriptionManageScreen(
    walletAddress: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val autoRenewService = remember { com.soulon.app.subscription.AutoRenewService.getInstance(context) }
    
    // 状态
    var isAutoRenewEnabled by remember { mutableStateOf(false) }
    var currentPlanType by remember { mutableIntStateOf(0) }
    var subscriptionAmount by remember { mutableDoubleStateOf(0.0) }
    var nextPaymentAt by remember { mutableLongStateOf(0L) }
    var pendingPlanType by remember { mutableIntStateOf(0) }
    var pendingAmountUsdc by remember { mutableDoubleStateOf(0.0) }
    var pendingEffectiveAt by remember { mutableLongStateOf(0L) }
    var cancelLockedUntil by remember { mutableLongStateOf(0L) }
    var isLoading by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    
    // 加载订阅状态
    LaunchedEffect(walletAddress) {
        walletAddress?.let { wallet ->
            autoRenewService.syncStatus(wallet)
            isAutoRenewEnabled = autoRenewService.isAutoRenewEnabled(wallet)
            currentPlanType = autoRenewService.getCurrentPlanType(wallet)
            subscriptionAmount = autoRenewService.getSubscriptionAmount(wallet)
            nextPaymentAt = autoRenewService.getNextPaymentAt(wallet)
            pendingPlanType = autoRenewService.getPendingPlanType(wallet)
            pendingAmountUsdc = autoRenewService.getPendingAmountUsdc(wallet)
            pendingEffectiveAt = autoRenewService.getPendingEffectiveAt(wallet)
            cancelLockedUntil = autoRenewService.getCancelLockedUntil(wallet)
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = com.soulon.app.i18n.AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = AppStrings.tr("订阅管理", "Subscription"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.size(48.dp))
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 当前订阅状态卡片
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isAutoRenewEnabled) 
                                Color(0xFF14F195).copy(alpha = 0.1f) 
                            else 
                                Color.White.copy(alpha = 0.05f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    AppStrings.tr("自动续费状态", "Auto-renewal"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isAutoRenewEnabled) 
                                        Color(0xFF14F195).copy(alpha = 0.2f) 
                                    else 
                                        Color.White.copy(alpha = 0.1f)
                                ) {
                                    Text(
                                        if (isAutoRenewEnabled) AppStrings.tr("已开通", "Enabled") else AppStrings.tr("未开通", "Disabled"),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isAutoRenewEnabled) Color(0xFF14F195) else Color.White.copy(alpha = 0.5f),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            
                            if (isAutoRenewEnabled) {
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // 订阅详情
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            AppStrings.tr("当前方案", "Current plan"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                        Text(
                                            autoRenewService.getPlanName(currentPlanType),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            AppStrings.tr("续费金额", "Renewal amount"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                        Text(
                                            "$" + String.format("%.2f", subscriptionAmount) + " USDC",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF14F195)
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            AppStrings.tr("下次扣款时间", "Next charge"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                        Text(
                                            autoRenewService.formatNextPaymentDate(nextPaymentAt),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White
                                        )
                                    }
                                }

                                if (pendingPlanType != 0 && pendingEffectiveAt != 0L) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF9945FF).copy(alpha = 0.12f)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                AppStrings.tr("升级待生效", "Upgrade pending"),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                AppStrings.tr(
                                                    "将于 ${autoRenewService.formatNextPaymentDate(pendingEffectiveAt)} 自动变更为 ${autoRenewService.getPlanName(pendingPlanType)}（\$${String.format("%.2f", pendingAmountUsdc)} USDC）",
                                                    "Will change on ${autoRenewService.formatNextPaymentDate(pendingEffectiveAt)} to ${autoRenewService.getPlanName(pendingPlanType)} ($${String.format("%.2f", pendingAmountUsdc)} USDC)"
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White.copy(alpha = 0.75f)
                                            )
                                            if (cancelLockedUntil > (System.currentTimeMillis() / 1000)) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    AppStrings.tr(
                                                        "升级后的第一笔扣款前不可取消订阅合约",
                                                        "Cancellation is disabled until the first charge after the upgrade"
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFFFFD700).copy(alpha = 0.9f)
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    AppStrings.tr(
                                        "开通会员订阅时勾选自动续费，即可享受首月优惠并自动续期",
                                        "Enable auto-renewal when subscribing to get first-month discounts and automatic renewals"
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
                
                // 自动续费说明
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF9945FF).copy(alpha = 0.1f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = Color(0xFF9945FF),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    AppStrings.tr("自动续费说明", "Auto-renewal notes"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                AppStrings.tr(
                                    "• 自动续费将在到期前自动从您的钱包扣款\n" +
                                        "• 扣款金额为订阅时选择的方案价格\n" +
                                        "• 您可以随时取消自动续费\n" +
                                        "• 取消后当前订阅期内权益不受影响",
                                    "• Auto-renewal will charge your wallet before expiration\n" +
                                        "• The amount equals your selected plan price\n" +
                                        "• You can cancel auto-renewal anytime\n" +
                                        "• Canceling won’t affect benefits within the current period"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
                
                // 取消自动续费按钮
                if (isAutoRenewEnabled) {
                    item {
                        val nowSec = System.currentTimeMillis() / 1000
                        val isCancelLocked = pendingPlanType != 0 || cancelLockedUntil > nowSec
                        Button(
                            onClick = { showCancelDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF4444).copy(alpha = 0.2f)
                            ),
                            enabled = !isLoading && !isCancelLocked
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color(0xFFFF4444),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    if (isCancelLocked)
                                        AppStrings.tr("取消自动续费（锁定中）", "Cancel auto-renewal (locked)")
                                    else
                                        AppStrings.tr("取消自动续费", "Cancel auto-renewal"),
                                    color = Color(0xFFFF4444),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // 取消确认对话框
        if (showCancelDialog) {
            AlertDialog(
                onDismissRequest = { showCancelDialog = false },
                title = {
                    Text(
                        AppStrings.tr("确认取消自动续费", "Confirm cancellation"),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    Text(
                        AppStrings.tr(
                            "取消后将不再自动续费，当前订阅期内的会员权益不受影响。确定要取消吗？",
                            "Auto-renewal will stop. Your current period benefits are not affected. Cancel now?"
                        ),
                        color = Color.White.copy(alpha = 0.8f)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showCancelDialog = false
                            isLoading = true
                            coroutineScope.launch {
                                try {
                                    walletAddress?.let { wallet ->
                                        val result = autoRenewService.cancelAutoRenewSubscription(wallet)
                                        result.fold(
                                            onSuccess = {
                                                isAutoRenewEnabled = false
                                                currentPlanType = 0
                                                subscriptionAmount = 0.0
                                                nextPaymentAt = 0
                                                successMessage = AppStrings.tr("已成功取消自动续费", "Auto-renewal canceled successfully")
                                            },
                                            onFailure = { e ->
                                                errorMessage = AppStrings.trf(
                                                    "取消失败: %s",
                                                    "Cancellation failed: %s",
                                                    e.message
                                                )
                                            }
                                        )
                                    }
                                } catch (e: Exception) {
                                    errorMessage = AppStrings.trf(
                                        "取消失败: %s",
                                        "Cancellation failed: %s",
                                        e.message
                                    )
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF4444)
                        )
                    ) {
                        Text(AppStrings.tr("确认取消", "Confirm"), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCancelDialog = false }) {
                        Text(AppStrings.tr("再想想", "Not now"), color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.8f)
            )
        }
        
        // 成功提示
        successMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2000)
                successMessage = null
            }
            
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 100.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF14F195).copy(alpha = 0.9f)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(msg, color = Color.Black, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        
        // 错误提示
        errorMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                errorMessage = null
            }
            
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 100.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFF4444).copy(alpha = 0.9f)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = Color.White)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(msg, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
