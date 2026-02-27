package com.soulon.app.proactive

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soulon.app.chat.ChatRepository
import com.soulon.app.data.BackendApiClient
import com.soulon.app.i18n.AppStrings
import com.soulon.app.onboarding.OnboardingState
import com.soulon.app.ui.AIChatScreen
import com.soulon.app.ui.ChatResponse
import com.soulon.app.ui.theme.AppColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 带奇遇功能的 AI 聊天界面
 * 
 * 在完成问卷调查后解锁奇遇功能：
 * - 显示 AI 的奇遇问题
 * - 用户探索后强化人格画像
 * - 通过通知提醒用户新的奇遇
 */
@Composable
fun AIChatWithProactiveQuestions(
    memoBalance: Int,
    tierName: String,
    tierMultiplier: Float,
    chatRepository: ChatRepository,
    onSendMessage: suspend (String, String?) -> ChatResponse,
    onDecryptAndAnswer: suspend (String, List<String>, String?) -> ChatResponse,
    onNavigateToHome: () -> Unit = {},
    onNavigateToSubscribe: () -> Unit = {},
    externalSessionId: String? = null,
    onSessionIdChange: (String?) -> Unit = {},
    // 主动提问相关
    pendingQuestionId: String? = null, // 从通知点击传入的问题 ID
    onAnswerSubmitted: (String, String) -> Unit = { _, _ -> }, // questionId, answer
    // 资源防护
    walletAddress: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // 主动提问管理器
    val questionManager = remember { ProactiveQuestionManager(context) }
    
    // 是否解锁主动提问功能（完成问卷后解锁）
    val isFeatureUnlocked = remember { questionManager.isFeatureUnlocked() }
    
    // 待回答的问题列表
    val rawPendingQuestions by questionManager.getPendingQuestions().collectAsState(initial = emptyList())

    val baseLang = AppStrings.getCurrentLanguage().substringBefore('-').ifBlank { "en" }

    fun containsHan(text: String): Boolean {
        return text.any { c -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN }
    }

    fun containsLatin(text: String): Boolean {
        return text.any { c -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN }
    }

    fun needsTranslation(text: String): Boolean {
        return when (baseLang) {
            "en" -> containsHan(text)
            "zh" -> containsLatin(text) && !containsHan(text)
            else -> false
        }
    }

    var translatedQuestionText by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(rawPendingQuestions, baseLang) {
        try {
            val toTranslate = rawPendingQuestions.filter { needsTranslation(it.questionText) }
            if (toTranslate.isEmpty()) {
                translatedQuestionText = emptyMap()
                return@LaunchedEffect
            }

            val api = BackendApiClient.getInstance(context)
            val translations = api.translateUiStrings(
                targetLang = baseLang,
                items = toTranslate.map { it.id to it.questionText }
            )

            translatedQuestionText = translations.filterValues { it.isNotBlank() }
        } catch (_e: Exception) {
            translatedQuestionText = emptyMap()
        }
    }

    val pendingQuestions = remember(rawPendingQuestions, translatedQuestionText) {
        rawPendingQuestions.map { q ->
            val translated = translatedQuestionText[q.id]
            if (translated.isNullOrBlank()) q else q.copy(questionText = translated)
        }
    }
    
    // 最近获得的奇遇奖励积分（用于显示在完成对话框中）
    var lastRewardAmount by remember { mutableIntStateOf(0) }
    
    // 当前选中要回答的问题
    var selectedQuestion by remember { mutableStateOf<ProactiveQuestionEntity?>(null) }
    
    // 是否显示问题回答对话框
    var showAnswerDialog by remember { mutableStateOf(false) }
    
    // 是否显示待回答问题卡片（默认隐藏，只有从通知点击进入时才显示）
    var showQuestionCard by remember { mutableStateOf(pendingQuestionId != null) }
    
    // 完成动画相关状态
    var showCompletionDialog by remember { mutableStateOf(false) }
    
    // 处理从通知点击进入的奇遇
    LaunchedEffect(pendingQuestionId, baseLang) {
        if (pendingQuestionId != null) {
            coroutineScope.launch {
                val question = ProactiveQuestionDatabase.getInstance(context)
                    .proactiveQuestionDao()
                    .getQuestion(pendingQuestionId)
                
                if (question != null && question.status != QuestionStatus.ANSWERED.name) {
                    val localizedQuestion = if (needsTranslation(question.questionText)) {
                        runCatching {
                            val api = BackendApiClient.getInstance(context)
                            val translated = api.translateUiStrings(
                                targetLang = baseLang,
                                items = listOf(question.id to question.questionText)
                            )[question.id]
                            if (translated.isNullOrBlank()) question else question.copy(questionText = translated)
                        }.getOrNull() ?: question
                    } else {
                        question
                    }
                    selectedQuestion = localizedQuestion
                    showAnswerDialog = true
                    Timber.d("从通知打开奇遇: ${question.questionText}")
                }
            }
        }
    }
    
    // 奇遇功能通过定时任务和通知推送，不在 UI 中自动生成
    // 只有当用户点击通知或手动查看时才显示待探索的奇遇
    
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 显示待回答问题卡片（仅在从通知进入或点击徽章时显示）
            AnimatedVisibility(
                visible = isFeatureUnlocked && showQuestionCard && pendingQuestions.isNotEmpty(),
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                PendingQuestionsOverlay(
                    questions = pendingQuestions,
                    onAnswerQuestion = { question ->
                        selectedQuestion = question
                        showAnswerDialog = true
                    },
                    onSkipQuestion = { question ->
                        coroutineScope.launch {
                            questionManager.skipQuestion(question.id)
                        }
                    },
                    onDismiss = {
                        showQuestionCard = false
                    }
                )
            }
            
            // 原有的 AI 聊天界面
            AIChatScreen(
                memoBalance = memoBalance,
                tierName = tierName,
                tierMultiplier = tierMultiplier,
                chatRepository = chatRepository,
                onSendMessage = onSendMessage,
                onDecryptAndAnswer = onDecryptAndAnswer,
                onNavigateToHome = onNavigateToHome,
                onNavigateToSubscribe = onNavigateToSubscribe,
                externalSessionId = externalSessionId,
                onSessionIdChange = onSessionIdChange,
                modifier = Modifier.weight(1f)
            )
        }
        
        // 待探索奇遇徽章（右上角，只有当有待探索奇遇且卡片隐藏时显示）
        if (isFeatureUnlocked && walletAddress != null) {
            Column(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.End
            ) {
                if (!showQuestionCard && pendingQuestions.isNotEmpty()) {
                    PendingQuestionsBadge(
                        count = pendingQuestions.size,
                        onClick = { showQuestionCard = true }
                    )
                }
            }
        }
        
        // 问题回答对话框
        if (showAnswerDialog && selectedQuestion != null) {
            ProactiveQuestionAnswerDialog(
                question = selectedQuestion!!,
                onSubmit = { answer ->
                    val question = selectedQuestion!!
                    
                    // 关闭回答对话框，显示完成动画
                    showAnswerDialog = false
                    showCompletionDialog = true
                    
                    coroutineScope.launch {
                        try {
                            // 发放奇遇奖励（通过后端验证，防止重复领取）
                            val rewardsRepository = com.soulon.app.rewards.RewardsRepository(context)
                            val rewardAmount = if (walletAddress != null) {
                                rewardsRepository.rewardAdventure(
                                    walletAddress = walletAddress,
                                    questionId = question.id,
                                    questionText = question.questionText
                                )
                            } else {
                                Timber.w("钱包地址为空，无法验证奇遇奖励")
                                0
                            }
                            
                            // 保存奖励积分用于显示
                            lastRewardAmount = rewardAmount
                            
                            // 保存回答（带上奖励积分）
                            questionManager.answerQuestion(
                                questionId = question.id,
                                answer = answer,
                                personaImpact = null,
                                rewardedMemo = rewardAmount
                            )
                            
                            // 回调通知外部
                            onAnswerSubmitted(question.id, answer)
                            
                        } finally {
                            selectedQuestion = null
                        }
                        
                        // 人格分析在后台异步进行，不阻塞用户
                        // 用户可以立即看到奖励和完成动画
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                Timber.d("后台启动人格分析: ${question.questionText.take(20)}...")
                                
                                // 异步进行人格分析
                                analyzeAdventureResponseAsync(context, question, answer)
                                
                                Timber.i("后台人格分析完成: ${question.questionText.take(20)}...")
                            } catch (e: Exception) {
                                Timber.e(e, "后台人格分析失败（不影响用户体验）")
                            }
                        }
                    }
                },
                onDismiss = {
                    showAnswerDialog = false
                    selectedQuestion = null
                }
            )
        }
        
        // 完成动画对话框
        if (showCompletionDialog) {
            AdventureCompletionDialog(
                rewardAmount = lastRewardAmount,
                onDismiss = {
                    showCompletionDialog = false
                    lastRewardAmount = 0
                }
            )
        }
    }

    LaunchedEffect(isFeatureUnlocked, walletAddress) {
        questionManager.setWalletAddress(walletAddress)
    }
}

/**
 * 异步分析奇遇回答，更新人格画像
 * 
 * 在后台线程执行，不阻塞用户操作
 * 用户可以立即看到奖励和完成动画
 */
private suspend fun analyzeAdventureResponseAsync(
    context: Context,
    question: ProactiveQuestionEntity,
    answer: String
) {
    try {
        // 检查是否已完成初始化
        val isOnboardingComplete = OnboardingState.isCompleted(context)
        if (!isOnboardingComplete) {
            return
        }
        
        // 获取问卷答案
        val storage = com.soulon.app.onboarding.OnboardingEvaluationStorage(context)
        val evaluations = storage.getAllEvaluations()
        
        if (evaluations.isEmpty()) {
            Timber.d("没有问卷评估数据，跳过分析")
            return
        }
        
        val questionnaireAnswers = evaluations.map { it.questionId to it.originalAnswer }
        
        // 创建并初始化 QwenCloudManager
        val qwenManager = com.soulon.app.ai.QwenCloudManager(context)
        qwenManager.initialize()
        
        // 创建分析器
        val analyzer = com.soulon.app.onboarding.ConversationAnalyzer(
            context,
            qwenManager
        )
        
        // 构建分析用的消息
        val category = try {
            QuestionCategory.valueOf(question.category)
        } catch (e: Exception) {
            QuestionCategory.DAILY_LIFE
        }
        
        val userMessage = "【奇遇探索 - ${category.displayName}】\n探索话题：${question.questionText}"
        val aiResponse = "用户回答：$answer"
        
        // 分析对话（传入 null 作为 memoryId）
        analyzer.analyzeConversation(
            userMessage = userMessage,
            aiResponse = aiResponse,
            newMemoryId = null,
            questionnaireAnswers = questionnaireAnswers
        )
        
        // 获取更新后的评估报告
        val evaluationManager = com.soulon.app.onboarding.OnboardingEvaluationManager(context)
        val report = evaluationManager.getOverallReport()
        
        Timber.i(
            "🧠 后台人格分析完成：整体可信度=${(report.overallReliability * 100).toInt()}%，" +
            "等级=${report.getReliabilityGrade()}"
        )
        
    } catch (e: Exception) {
        Timber.e(e, "后台人格分析失败")
    }
}

/**
 * 构建分析消息
 * 将用户的奇遇探索结果发送给 AI 进行人格分析
 */
private fun buildAnalysisMessage(question: ProactiveQuestionEntity, answer: String): String {
    val category = try {
        QuestionCategory.valueOf(question.category)
    } catch (e: Exception) {
        QuestionCategory.DAILY_LIFE
    }
    
    return """
【奇遇探索 - ${category.displayName}】

探索话题：${question.questionText}

我的分享：$answer

请基于这次奇遇探索，更新对我的人格画像理解。
    """.trimIndent()
}

/**
 * 奇遇状态管理
 */
class ProactiveQuestionState(context: Context) {
    private val manager = ProactiveQuestionManager(context)
    private val notificationManager = ProactiveQuestionNotificationManager(context)
    
    /**
     * 启动奇遇功能
     * 在问卷完成后调用
     */
    suspend fun enableFeature() {
        // 启动定期检查任务
        ProactiveQuestionWorker.triggerQuestionGeneration(manager.context)
        
        // 生成初始奇遇
        manager.generateQuestions(count = 3)
        
        Timber.i("奇遇功能已启用")
    }
    
    /**
     * 请求通知权限后的回调
     */
    fun onNotificationPermissionGranted() {
        // 立即检查是否有奇遇需要通知
        ProactiveQuestionWorker.runImmediateCheck(manager.context)
    }
    
    private val ProactiveQuestionManager.context: Context
        get() = this.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(this) as Context
}
