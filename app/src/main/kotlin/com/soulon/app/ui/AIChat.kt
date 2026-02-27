package com.soulon.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import com.soulon.app.rewards.ResonanceGrade
import com.soulon.app.ui.theme.AppCorners
import com.soulon.app.ui.theme.AppShapes
import com.soulon.app.ui.theme.AppIconSizes
import com.soulon.app.ui.theme.AppSpacing
import com.soulon.app.ui.theme.AppColors
import com.soulon.app.chat.ChatRepository
import com.soulon.app.chat.ChatRateLimiter
import com.soulon.app.chat.ChatSession as ChatSessionModel
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.UserFacingErrors
import com.soulon.app.wallet.WalletScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/**
 * AI 智能对话界面 - 深色现代化设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatScreen(
    memoBalance: Int,
    tierName: String,
    tierMultiplier: Float,
    chatRepository: ChatRepository,
    onSendMessage: suspend (String, String?) -> ChatResponse,
    onDecryptAndAnswer: suspend (String, List<String>, String?) -> ChatResponse,
    onNavigateToHome: () -> Unit = {},
    onNavigateToSubscribe: () -> Unit = {},
    // 外部传入的会话 ID（用于保持状态）
    externalSessionId: String? = null,
    onSessionIdChange: (String?) -> Unit = {},
    // V1 新增参数
    currentTier: Int = 1,
    recentReward: RecentRewardInfo? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val walletAddress = remember(context) { WalletScope.currentWalletAddress(context).orEmpty() }
    val backendApi = remember { com.soulon.app.data.BackendApiClient.getInstance(context) }
    
    // 🔄 使用 Repository 确保与其他页面共享同一个数据源
    val rewardsRepository = remember { com.soulon.app.rewards.RewardsRepository(context) }
    
    var aiQuotaStatus by remember { mutableStateOf<com.soulon.app.data.AiQuotaStatus?>(null) }
    var showQuotaWarning by remember { mutableStateOf(false) }
    LaunchedEffect(walletAddress) {
        if (walletAddress.isNotBlank()) {
            aiQuotaStatus = backendApi.getAiQuotaStatus(walletAddress)
        }
    }

    // 使用 produceState 确保初始值正确加载
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
    
    // 使用实时数据，回退到传入参数（兼容性）
    val actualMemoBalance = userProfile?.memoBalance ?: memoBalance
    val actualTierName = userProfile?.getTierName() ?: tierName
    val actualTierMultiplier = userProfile?.getTierMultiplier() ?: tierMultiplier
    val actualCurrentTier = userProfile?.currentTier ?: currentTier
    
    // 调试日志：帮助诊断同步问题
    LaunchedEffect(userProfile?.memoBalance) {
        timber.log.Timber.d("🔄 AIChat 积分更新: ${userProfile?.memoBalance} (传入参数: $memoBalance)")
    }
    
    var messageText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    
    // 防刷限制器
    val rateLimiter = remember { ChatRateLimiter(context) }
    var rateLimitError by remember { mutableStateOf<String?>(null) }
    var showLengthWarning by remember { mutableStateOf(false) }
    
    // 侧边栏状态
    var showHistorySidebar by remember { mutableStateOf(false) }
    
    // 当前会话 ID - 使用外部传入的或本地状态
    var currentSessionId by remember { mutableStateOf(externalSessionId) }
    
    // 从数据库加载历史会话
    val chatHistory by chatRepository.getAllSessions().collectAsState(initial = emptyList())
    
    // 标记是否已经发送过消息（用于决定是否保存会话）
    var hasMessagesSent by remember { mutableStateOf(false) }
    
    // 同步外部传入的会话 ID
    LaunchedEffect(externalSessionId) {
        if (externalSessionId != null && externalSessionId != currentSessionId) {
            currentSessionId = externalSessionId
            val savedMessages = chatRepository.getMessagesOnce(externalSessionId)
            messages.clear()
            messages.addAll(savedMessages.map { it.toChatMessage() })
            hasMessagesSent = savedMessages.isNotEmpty()
            Timber.d("从外部加载会话: $externalSessionId, 消息数: ${messages.size}")
        }
    }
    
    // 初始化：加载已有会话
    LaunchedEffect(chatHistory) {
        // 如果有外部传入的会话 ID，优先使用它
        if (externalSessionId != null) {
            if (currentSessionId != externalSessionId) {
                currentSessionId = externalSessionId
                val savedMessages = chatRepository.getMessagesOnce(externalSessionId)
                messages.clear()
                messages.addAll(savedMessages.map { it.toChatMessage() })
                hasMessagesSent = savedMessages.isNotEmpty()
                Timber.d("初始化加载外部会话: $externalSessionId, 消息数: ${messages.size}")
            }
        } else if (currentSessionId == null && chatHistory.isNotEmpty()) {
            // 没有外部会话 ID，加载最新会话
            currentSessionId = chatHistory.first().id
            onSessionIdChange(currentSessionId) // 通知外部
            val savedMessages = chatRepository.getMessagesOnce(chatHistory.first().id)
            messages.clear()
            messages.addAll(savedMessages.map { it.toChatMessage() })
            hasMessagesSent = savedMessages.isNotEmpty()
            Timber.d("加载最新会话: ${currentSessionId}, 消息数: ${messages.size}")
        }
    }
    
    var showDecryptDialog by remember { mutableStateOf(false) }
    var pendingDecryptMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingUserQuery by remember { mutableStateOf("") }
    
    // 解密确认对话框
    if (showDecryptDialog && pendingDecryptMessage != null) {
        DecryptConfirmDialog(
            memoryCount = pendingDecryptMessage!!.encryptedMemoryIds.size,
            onConfirm = {
                showDecryptDialog = false
                val messageToUpdate = pendingDecryptMessage!!
                val query = pendingUserQuery
                
                coroutineScope.launch {
                    isLoading = true
                    try {
                        val response = onDecryptAndAnswer(query, messageToUpdate.encryptedMemoryIds, currentSessionId)
                        
                        val index = messages.indexOfFirst { it.messageId == messageToUpdate.messageId }
                        if (index >= 0) {
                            messages[index] = ChatMessage(
                                text = response.answer,
                                isUser = false,
                                timestamp = System.currentTimeMillis(),
                                retrievedMemories = response.retrievedMemories,
                                rewardedMemo = response.rewardedMemo,
                                pendingDecryption = false,
                                messageId = messageToUpdate.messageId
                            )
                            
                            // 保存解密后的 AI 回复到数据库
                            currentSessionId?.let { sessionId ->
                                chatRepository.addMessage(
                                    sessionId = sessionId,
                                    text = response.answer,
                                    isUser = false,
                                    retrievedMemories = response.retrievedMemories,
                                    rewardedMemo = response.rewardedMemo
                                )
                            }
                        }
                        listState.animateScrollToItem(messages.size - 1)
                    } catch (e: Exception) {
                        val index = messages.indexOfFirst { it.messageId == messageToUpdate.messageId }
                        if (index >= 0) {
                            messages[index] = ChatMessage(
                                text = UserFacingErrors.decryptFailed(e.message),
                                isUser = false,
                                timestamp = System.currentTimeMillis(),
                                isError = true,
                                messageId = messageToUpdate.messageId
                            )
                        }
                    } finally {
                        isLoading = false
                        pendingDecryptMessage = null
                        pendingUserQuery = ""
                    }
                }
            },
            onDismiss = {
                showDecryptDialog = false
                val messageToUpdate = pendingDecryptMessage!!
                val query = pendingUserQuery
                
                coroutineScope.launch {
                    isLoading = true
                    try {
                        val response = onSendMessage(
                            "${AppStrings.tr("【无记忆模式】", "[No-memory mode] ")}$query",
                            currentSessionId
                        )
                        
                        val index = messages.indexOfFirst { it.messageId == messageToUpdate.messageId }
                        if (index >= 0) {
                            val answerText = "${AppStrings.tr("（未解密记忆）", "(Memories not decrypted)")}\n\n${response.answer}"
                            messages[index] = ChatMessage(
                                text = answerText,
                                isUser = false,
                                timestamp = System.currentTimeMillis(),
                                rewardedMemo = response.rewardedMemo,
                                pendingDecryption = false,
                                messageId = messageToUpdate.messageId
                            )
                            
                            // 保存无记忆模式的 AI 回复到数据库
                            currentSessionId?.let { sessionId ->
                                chatRepository.addMessage(
                                    sessionId = sessionId,
                                    text = answerText,
                                    isUser = false,
                                    rewardedMemo = response.rewardedMemo
                                )
                            }
                        }
                        listState.animateScrollToItem(messages.size - 1)
                    } catch (e: Exception) {
                        // 错误处理
                    } finally {
                        isLoading = false
                        pendingDecryptMessage = null
                        pendingUserQuery = ""
                    }
                }
            }
        )
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .imePadding()
    ) {
        // 主内容
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏 (V1 白皮书格式) - 使用实时数据
            ChatTopBar(
                memoBalance = actualMemoBalance,
                tierName = actualTierName,
                tierMultiplier = actualTierMultiplier,
                onMenuClick = { showHistorySidebar = true },
                onHomeClick = onNavigateToHome,
                currentTier = actualCurrentTier,
                recentReward = recentReward
            )
            
            // 消息列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                if (messages.isEmpty()) {
                    item {
                        EmptyStateMessage()
                    }
                }
                
                items(messages, key = { it.messageId }) { message ->
                    ChatMessageBubble(
                        message = message,
                        onDecryptClick = if (message.pendingDecryption) {
                            {
                                pendingDecryptMessage = message
                                showDecryptDialog = true
                            }
                        } else null
                    )
                }
                
                if (isLoading) {
                    item {
                        LoadingIndicator()
                    }
                }
            }
            
            // 防刷限制错误提示
            AnimatedVisibility(
                visible = rateLimitError != null,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFF4444).copy(alpha = 0.9f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = rateLimitError ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            LaunchedEffect(aiQuotaStatus) {
                val status = aiQuotaStatus
                showQuotaWarning = if (status == null) {
                    false
                } else {
                    status.monthlyRemainingRatio() <= 0.05f
                }
            }

            AnimatedVisibility(
                visible = showQuotaWarning,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFD700).copy(alpha = 0.12f)
                ) {
                    val linkText = AppStrings.tr("订阅会员", "Subscribe")
                    val prefix = AppStrings.tr("您的 AI 对话额度即将用完，点击升级", "Your AI chat quota is running low. Upgrade: ")
                    val suffix = ""
                    val annotated: AnnotatedString = buildAnnotatedString {
                        append(prefix)
                        val start = length
                        append(linkText)
                        val end = length
                        addStringAnnotation(tag = "subscribe", annotation = "subscription", start = start, end = end)
                        addStyle(
                            style = androidx.compose.ui.text.SpanStyle(
                                color = Color(0xFFFFD700),
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline
                            ),
                            start = start,
                            end = end
                        )
                        append(suffix)
                    }

                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ClickableText(
                            text = annotated,
                            style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.85f)),
                            onClick = { offset ->
                                annotated.getStringAnnotations(tag = "subscribe", start = offset, end = offset)
                                    .firstOrNull()
                                    ?.let { onNavigateToSubscribe() }
                            }
                        )
                    }
                }
            }

            // 自动清除错误提示
            LaunchedEffect(rateLimitError) {
                if (rateLimitError != null) {
                    delay(3000)
                    rateLimitError = null
                }
            }
            
            // 字符数警告
            AnimatedVisibility(
                visible = showLengthWarning,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFD700).copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = AppStrings.tr("字符数", "Chars") + ": " + messageText.length + "/" + rateLimiter.getCurrentLimits().maxMessageLength,
                            color = Color(0xFFFFD700),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            
            // 更新长度警告状态
            LaunchedEffect(messageText) {
                showLengthWarning = rateLimiter.isNearLengthLimit(messageText)
            }
            
            // 输入框
            ChatInputBar(
                text = messageText,
                onTextChange = { newText ->
                    // 限制最大输入长度
                    val maxLength = rateLimiter.getCurrentLimits().maxMessageLength
                    if (newText.length <= maxLength) {
                        messageText = newText
                    } else {
                        // 超出限制时截断
                        messageText = newText.take(maxLength)
                        rateLimitError = AppStrings.trf("消息已达最大长度限制（%d字符）", "Message reached the maximum length (%d chars)", maxLength)
                    }
                },
                onSend = {
                    if (messageText.isNotBlank() && !isLoading) {
                        // 检查防刷限制
                        when (val result = rateLimiter.checkCanSend(messageText)) {
                            is ChatRateLimiter.CheckResult.Allowed -> {
                                // 允许发送
                                rateLimiter.recordMessageSent()
                                rateLimitError = null
                                isLoading = true
                                
                                val userMessage = messageText
                                messageText = ""
                                
                                val userChatMessage = ChatMessage(
                                    text = userMessage,
                                    isUser = true,
                                    timestamp = System.currentTimeMillis()
                                )
                                messages.add(userChatMessage)
                                
                                coroutineScope.launch {
                                    listState.animateScrollToItem(messages.size - 1)
                                }
                                
                                coroutineScope.launch {
                                    try {
                                // 如果还没有会话，在发送第一条消息时创建
                                if (currentSessionId == null) {
                                    // 使用第一条消息的前 20 个字符作为会话标题
                                    val sessionTitle = if (userMessage.length > 20) {
                                        userMessage.take(20) + "..."
                                    } else {
                                        userMessage
                                    }
                                    val newSession = chatRepository.createSession(sessionTitle)
                                    currentSessionId = newSession.id
                                    onSessionIdChange(newSession.id) // 通知外部
                                    Timber.d("发送消息时创建新会话: ${newSession.id}")
                                }
                                
                                hasMessagesSent = true
                                
                                // 保存用户消息到数据库
                                currentSessionId?.let { sessionId ->
                                    chatRepository.addMessage(
                                        sessionId = sessionId,
                                        text = userMessage,
                                        isUser = true
                                    )
                                }
                                
                                val response = onSendMessage(userMessage, currentSessionId)
                                if (walletAddress.isNotBlank()) {
                                    aiQuotaStatus = backendApi.getAiQuotaStatus(walletAddress)
                                }
                                
                                // 直接使用记忆，不再显示解密确认弹窗
                                val aiMessage = ChatMessage(
                                    text = response.answer,
                                    isUser = false,
                                    timestamp = System.currentTimeMillis(),
                                    retrievedMemories = response.retrievedMemories,
                                    rewardedMemo = response.rewardedMemo
                                )
                                messages.add(aiMessage)
                                
                                // 保存 AI 回复到数据库
                                currentSessionId?.let { sessionId ->
                                    chatRepository.addMessage(
                                        sessionId = sessionId,
                                        text = response.answer,
                                        isUser = false,
                                        retrievedMemories = response.retrievedMemories,
                                        rewardedMemo = response.rewardedMemo
                                    )
                                }
                                
                                listState.animateScrollToItem(messages.size - 1)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                Timber.d("AI 对话发送取消: ${e.message}")
                                throw e
                            } catch (e: java.io.InterruptedIOException) {
                                Timber.w(e, "AI 对话发送中断")
                                messages.add(
                                    ChatMessage(
                                        text = UserFacingErrors.generationInterrupted(),
                                        isUser = false,
                                        timestamp = System.currentTimeMillis(),
                                        isError = true
                                    )
                                )
                            } catch (e: Exception) {
                                Timber.e(e, "AI 对话发送失败")
                                val m = e.message.orEmpty()
                                if (m.contains("monthly_quota", ignoreCase = true) || m.contains("quota", ignoreCase = true) || m.contains("429")) {
                                    showQuotaWarning = true
                                }
                                val userFacingMessage = if (e.message?.contains("mutation interrupted", ignoreCase = true) == true) {
                                    UserFacingErrors.generationInterrupted()
                                } else {
                                    UserFacingErrors.genericRetryLater()
                                }
                                messages.add(
                                    ChatMessage(
                                        text = userFacingMessage,
                                        isUser = false,
                                        timestamp = System.currentTimeMillis(),
                                        isError = true
                                    )
                                )
                            } finally {
                                isLoading = false
                            }
                                }
                            }
                            is ChatRateLimiter.CheckResult.TextTooLong -> {
                                rateLimitError = result.message
                            }
                            is ChatRateLimiter.CheckResult.RateLimited -> {
                                rateLimitError = result.message
                            }
                            is ChatRateLimiter.CheckResult.TooFast -> {
                                rateLimitError = result.message
                            }
                            is ChatRateLimiter.CheckResult.InCooldown -> {
                                rateLimitError = result.message
                            }
                        }
                    }
                },
                enabled = !isLoading
            )
        }
        
        // 历史记录侧边栏遮罩
        if (showHistorySidebar) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showHistorySidebar = false }
            )
        }
        
        // 历史记录侧边栏
        AnimatedVisibility(
            visible = showHistorySidebar,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it }
        ) {
            ChatHistorySidebar(
                chatHistory = chatHistory.map { 
                    ChatSession(it.id, it.title, it.timestamp) 
                },
                onSessionClick = { session ->
                    // 加载选中的会话
                    coroutineScope.launch {
                        currentSessionId = session.id
                        onSessionIdChange(session.id) // 通知外部
                        val savedMessages = chatRepository.getMessagesOnce(session.id)
                        messages.clear()
                        messages.addAll(savedMessages.map { it.toChatMessage() })
                        Timber.d("切换到会话: ${session.id}, 消息数: ${messages.size}")
                    }
                    showHistorySidebar = false
                },
                onNewChat = {
                    // 清空当前对话，准备新对话（会话会在发送第一条消息时创建）
                    currentSessionId = null
                    onSessionIdChange(null) // 通知外部
                    messages.clear()
                    hasMessagesSent = false
                    Timber.d("准备新对话，会话将在发送消息时创建")
                    showHistorySidebar = false
                },
                onClose = { showHistorySidebar = false }
            )
        }
    }
}

/**
 * 历史会话数据类
 */
data class ChatSession(
    val id: String,
    val title: String,
    val timestamp: Long
)

/**
 * 历史记录侧边栏 - 圆角设计
 */
@Composable
private fun ChatHistorySidebar(
    chatHistory: List<ChatSession>,
    onSessionClick: (ChatSession) -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .clip(RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)),
        color = Color(0xFF12121A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.Medium)
        ) {
            // 顶部：标题和关闭按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacing.Medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AppStrings.tr("历史记录", "History"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = AppStrings.tr("关闭", "Close"),
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            // 新建对话按钮
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onNewChat() },
                color = AppColors.PrimaryGradientStart.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = AppColors.PrimaryGradientStart,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = AppStrings.tr("新建对话", "New Chat"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.PrimaryGradientStart
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            
            // 今天的会话
            if (chatHistory.isNotEmpty()) {
                Text(
                    text = AppStrings.tr("最近", "Recent"),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = AppSpacing.XSmall)
                )
            }
            
            // 历史会话列表
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
            ) {
                items(chatHistory) { session ->
                    HistorySessionItem(
                        session = session,
                        onClick = { onSessionClick(session) }
                    )
                }
            }
            
            // 底部信息
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                color = Color.White.copy(alpha = 0.03f)
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = AppStrings.tr("对话记录本地存储", "Chat history is stored locally"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

/**
 * 历史会话项
 */
@Composable
private fun HistorySessionItem(
    session: ChatSession,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        color = Color.White.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatRelativeTime(session.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
        }
    }
}

/**
 * 格式化相对时间
 */
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 3600000 -> AppStrings.trf("%d 分钟前", "%d minutes ago", (diff / 60000))
        diff < 86400000 -> AppStrings.trf("%d 小时前", "%d hours ago", (diff / 3600000))
        diff < 604800000 -> AppStrings.trf("%d 天前", "%d days ago", (diff / 86400000))
        else -> {
            val pattern = AppStrings.tr("MM月dd日", "MMM dd")
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
        }
    }
}

/**
 * V1 白皮书 Top Bar
 * 
 * 显示格式：[徽章] [Tier 名称] | [MEMO 余额] | [当前倍数]
 * 
 * 特效：
 * - Platinum (4级) 及以上用户，增加金色流光效果
 * - AI 评分较高时，积分数值下方显示淡出文本动画
 */
@Composable
fun ChatTopBar(
    memoBalance: Int,
    tierName: String,
    tierMultiplier: Float,
    onMenuClick: () -> Unit,
    onHomeClick: () -> Unit,
    // V1 新增：最近的奖励信息（用于动画）
    recentReward: RecentRewardInfo? = null,
    currentTier: Int = 1
) {
    // Platinum (4级) 及以上显示金色流光
    val isPlatinumOrAbove = currentTier >= 4
    
    // 流光动画
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -200f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )
    
    // 奖励动画状态
    var showRewardAnimation by remember { mutableStateOf(false) }
    var rewardText by remember { mutableStateOf("") }
    
    // 监听新奖励
    LaunchedEffect(recentReward) {
        if (recentReward != null && recentReward.amount > 0) {
            rewardText = recentReward.getDisplayText()
            showRewardAnimation = true
            delay(2500) // 显示 2.5 秒后消失
            showRewardAnimation = false
        }
    }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.Medium)
            .then(
                if (isPlatinumOrAbove) {
                    Modifier.drawBehind {
                        // 金色流光效果
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFFFFD700).copy(alpha = 0.3f),
                                    Color(0xFFFFA500).copy(alpha = 0.2f),
                                    Color.Transparent
                                ),
                                start = Offset(shimmerOffset, 0f),
                                end = Offset(shimmerOffset + 200f, size.height)
                            )
                        )
                    }
                } else Modifier
            ),
        shape = RoundedCornerShape(20.dp),
        color = if (isPlatinumOrAbove) {
            Color(0xFF1A1510).copy(alpha = 0.9f) // 金色调深色背景
        } else {
            Color.White.copy(alpha = 0.08f)
        }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.XSmall),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：菜单按钮
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = AppStrings.tr("历史记录", "History"),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                // 中间：V1 格式 [徽章] [Tier 名称] | [MEMO 余额] | [当前倍数]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Tier 徽章和名称
                    Surface(
                        shape = RoundedCornerShape(AppCorners.Full),
                        color = Color.White.copy(alpha = 0.08f) // 统一使用未解锁背景色
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Tier 徽章图标
                            Icon(
                                imageVector = getChatTierIcon(currentTier),
                                contentDescription = null,
                                tint = getChatTierColor(currentTier),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (currentTier > 1) AppStrings.tr("订阅会员", "Subscriber") else tierName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = getChatTierColor(currentTier)
                            )
                        }
                    }
                    
                    // 分隔符
                    Text(
                        text = "|",
                        color = Color.White.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    
                    // MEMO 余额（带奖励动画）
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = RoundedCornerShape(AppCorners.Full),
                                color = AppColors.SuccessGradientStart.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Token,
                                        contentDescription = null,
                                        tint = AppColors.SuccessGradientStart,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = formatMemoBalance(memoBalance),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = AppColors.SuccessGradientStart
                                    )
                                }
                            }
                            
                            // 奖励动画文本
                            AnimatedVisibility(
                                visible = showRewardAnimation,
                                enter = fadeIn() + slideInVertically { -it },
                                exit = fadeOut() + slideOutVertically { it }
                            ) {
                                Text(
                                    text = rewardText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (recentReward?.isSoulResonance == true) {
                                        Color(0xFFFFD700) // 金色 - 灵魂共鸣
                                    } else {
                                        AppColors.SuccessGradientStart
                                    }
                                )
                            }
                        }
                    }
                    
                    // 分隔符
                    Text(
                        text = "|",
                        color = Color.White.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    
                    // 当前倍数
                    Surface(
                        shape = RoundedCornerShape(AppCorners.Full),
                        color = Color(0xFF9C27B0).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = String.format("%.1f", tierMultiplier) + "x",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFCE93D8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                
                // 右侧：返回首页按钮
                IconButton(
                    onClick = onHomeClick,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Home,
                        contentDescription = AppStrings.tr("返回首页", "Back to Home"),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

        }
    }
}

/**
 * 最近奖励信息（用于 Top Bar 动画）
 */
data class RecentRewardInfo(
    val amount: Int,
    val resonanceGrade: ResonanceGrade,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isSoulResonance: Boolean get() = resonanceGrade == ResonanceGrade.S
    
    fun getDisplayText(): String {
        return when (resonanceGrade) {
            ResonanceGrade.S -> AppStrings.trf("+%d (灵魂共鸣!)", "+%d (Soul Resonance!)", amount)
            ResonanceGrade.A -> AppStrings.trf("+%d (共鸣!)", "+%d (Resonance!)", amount)
            else -> "+$amount"
        }
    }
}

/**
 * 获取 Tier 对应的颜色（ChatTopBar 专用）
 */
private fun getChatTierColor(tier: Int): Color = when (tier) {
    1 -> Color(0xFFCD7F32) // Bronze
    2 -> Color(0xFFC0C0C0) // Silver
    3 -> Color(0xFFFFD700) // Gold
    4 -> Color(0xFFE5E4E2) // Platinum
    5 -> Color(0xFFB9F2FF) // Diamond
    else -> Color.White
}

/**
 * 获取 Tier 对应的图标（ChatTopBar 专用）
 */
private fun getChatTierIcon(tier: Int): androidx.compose.ui.graphics.vector.ImageVector = when (tier) {
    1 -> Icons.Rounded.Circle        // Bronze
    2 -> Icons.Rounded.Star          // Silver
    3 -> Icons.Rounded.Star          // Gold
    4 -> Icons.Rounded.Diamond       // Platinum
    5 -> Icons.Rounded.Diamond       // Diamond
    else -> Icons.Rounded.Circle
}

/**
 * 格式化 MEMO 余额（大数字简写）
 */
private fun formatMemoBalance(balance: Int): String {
    return when {
        balance >= 1_000_000 -> String.format("%.1fM", balance / 1_000_000.0)
        balance >= 10_000 -> String.format("%.1fK", balance / 1_000.0)
        else -> balance.toString()
    }
}

/**
 * 消息气泡 - 深色设计
 */
@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onDecryptClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            color = when {
                message.isUser -> AppColors.PrimaryGradientStart
                message.isError -> Color(0xFF3D2020)
                message.pendingDecryption -> AppColors.SecondaryGradientStart.copy(alpha = 0.2f)
                else -> Color.White.copy(alpha = 0.08f)
            },
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Medium)
            ) {
                if (message.pendingDecryption) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                        modifier = Modifier.padding(bottom = AppSpacing.Small)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = AppColors.SecondaryGradientStart,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = AppStrings.tr("发现加密记忆", "Encrypted memories found"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.SecondaryGradientStart
                        )
                    }
                }
                
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        message.isUser -> Color.White
                        message.isError -> Color(0xFFFF6B6B)
                        else -> Color.White.copy(alpha = 0.9f)
                    }
                )
                
                if (message.pendingDecryption && onDecryptClick != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.Small))
                    Button(
                        onClick = onDecryptClick,
                        shape = RoundedCornerShape(AppCorners.Medium),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.SecondaryGradientStart
                        ),
                        contentPadding = PaddingValues(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(AppStrings.tr("解密查看", "Decrypt"), style = MaterialTheme.typography.labelMedium)
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isUser) {
                        Color.White.copy(alpha = 0.7f)
                    } else {
                        Color.White.copy(alpha = 0.4f)
                    }
                )
            }
        }
        
        if (!message.isUser && message.retrievedMemories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                message.retrievedMemories.take(3).forEach { _ ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = AppColors.SecondaryGradientStart.copy(alpha = 0.15f),
                        modifier = Modifier.height(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Memory,
                                contentDescription = null,
                                tint = AppColors.SecondaryGradientStart,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = AppStrings.tr("记忆", "Memory"),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.SecondaryGradientStart
                            )
                        }
                    }
                }
            }
        }
        
        if (!message.isUser && message.rewardedMemo > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = AppColors.SuccessGradientStart.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = AppColors.SuccessGradientStart,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = message.rewardedMemo.toString() + " " + AppStrings.tr("\$MEMO", "\$MEMO"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.SuccessGradientStart
                    )
                }
            }
        }
        
        // 本地存储警告（仅对非人格相关的 AI 回复显示）
        if (!message.isUser && !message.isPersonaRelevant && message.irysTransactionId == null && !message.pendingDecryption && !message.isError) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFFFF9800).copy(alpha = 0.1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = AppStrings.tr("仅本地存储，卸载应用将丢失", "Stored locally only. Uninstalling will erase it."),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF9800).copy(alpha = 0.8f)
                    )
                }
            }
        }
        
        // 已上传到 Irys 标识
        if (!message.isUser && message.isPersonaRelevant && message.irysTransactionId != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = AppColors.PrimaryGradientStart.copy(alpha = 0.1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = null,
                        tint = AppColors.PrimaryGradientStart,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = AppStrings.tr("已加密存储到区块链", "Encrypted and stored on-chain"),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.PrimaryGradientStart.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/**
 * 解密确认对话框 - 深色设计
 */
@Composable
fun DecryptConfirmDialog(
    memoryCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFF1A1A2E),
        icon = {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(28.dp),
                color = AppColors.SecondaryGradientStart.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = AppColors.SecondaryGradientStart,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        title = {
            Text(
                text = AppStrings.tr("解密记忆", "Decrypt Memories"),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                Text(
                    text = AppStrings.trf("发现 %d 条与您问题相关的加密记忆。", "Found %d encrypted memories related to your question.", memoryCount),
                    color = Color.White.copy(alpha = 0.9f)
                )
                Text(
                    text = AppStrings.tr("解密这些记忆后，AI 助手可以基于您的个人经历提供更准确的回答。", "After decrypting, the assistant can answer more accurately based on your personal experiences."),
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                Surface(
                    shape = RoundedCornerShape(AppCorners.Small),
                    color = Color.White.copy(alpha = 0.05f)
                ) {
                    Row(
                        modifier = Modifier.padding(AppSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Fingerprint,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.XSmall))
                        Text(
                            text = AppStrings.tr("解密需要生物识别验证", "Biometric authentication is required"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.SecondaryGradientStart
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(AppStrings.tr("解密并回答", "Decrypt & Answer"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White.copy(alpha = 0.7f)
                )
            ) {
                Text(AppStrings.tr("不解密", "Skip"))
            }
        }
    )
}

/**
 * 输入栏 - 圆角设计
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.Medium),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Small),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { 
                    Text(
                        AppStrings.tr("输入消息...", "Type a message..."),
                        color = Color.White.copy(alpha = 0.4f)
                    ) 
                },
                enabled = enabled,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    disabledTextColor = Color.White.copy(alpha = 0.5f),
                    cursorColor = AppColors.PrimaryGradientStart
                ),
                shape = RoundedCornerShape(20.dp)
            )
            
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = AppColors.PrimaryGradientStart,
                    contentColor = Color.White,
                    disabledContainerColor = Color.White.copy(alpha = 0.1f),
                    disabledContentColor = Color.White.copy(alpha = 0.3f)
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = AppStrings.tr("发送", "Send"),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 空状态消息 - 深色设计
 */
@Composable
fun EmptyStateMessage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.XXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
    ) {
        Surface(
            modifier = Modifier.size(100.dp),
            shape = RoundedCornerShape(50.dp),
            color = AppColors.PrimaryGradientStart.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = AppColors.PrimaryGradientStart.copy(alpha = 0.6f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.Small))
        
        Text(
            text = AppStrings.tr("开始对话", "Start chatting"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Text(
            text = AppStrings.tr("我会基于你的记忆和人格特征与你对话", "I’ll chat with you based on your memories and persona."),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f)
        )
        
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            SuggestionChip(
                icon = Icons.Rounded.Psychology,
                text = AppStrings.tr("了解自己", "Know yourself")
            )
            SuggestionChip(
                icon = Icons.Rounded.Memory,
                text = AppStrings.tr("回忆往事", "Recall memories")
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Surface(
        shape = RoundedCornerShape(AppCorners.Full),
        color = Color.White.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * 加载指示器 - 深色设计
 */
@Composable
fun LoadingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.08f),
            modifier = Modifier.widthIn(max = 180.dp)
        ) {
            Row(
                modifier = Modifier.padding(AppSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.PrimaryGradientStart
                )
                Text(
                    text = AppStrings.tr("AI 正在思考...", "AI is thinking..."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val retrievedMemories: List<String> = emptyList(),
    val rewardedMemo: Int = 0,
    val isError: Boolean = false,
    val pendingDecryption: Boolean = false,
    val encryptedMemoryIds: List<String> = emptyList(),
    val messageId: String = UUID.randomUUID().toString(),
    // 人格相关性字段
    val isPersonaRelevant: Boolean = false,    // 是否涉及人格
    val relevanceScore: Float = 0f,            // 人格相关度分数
    val irysTransactionId: String? = null      // Irys 交易 ID（已上传时非空）
)

data class ChatResponse(
    val answer: String,
    val retrievedMemories: List<String> = emptyList(),
    val rewardedMemo: Int = 0,
    val needsDecryption: Boolean = false,
    val encryptedMemoryIds: List<String> = emptyList(),
    val memoryPreviews: List<MemoryPreview> = emptyList(),
    // 人格相关性分析结果
    val isPersonaRelevant: Boolean = false,    // 是否涉及人格
    val relevanceScore: Float = 0f,            // 人格相关度分数
    val detectedTraits: List<String> = emptyList(), // 检测到的特质
    val irysTransactionId: String? = null      // Irys 交易 ID（已上传时非空）
)

data class MemoryPreview(
    val memoryId: String,
    val timestamp: Long,
    val similarity: Float,
    val tags: Map<String, String> = emptyMap()
)

/**
 * 将 ChatMessageModel 转换为 ChatMessage
 */
fun com.soulon.app.chat.ChatMessageModel.toChatMessage(): ChatMessage {
    return ChatMessage(
        text = text,
        isUser = isUser,
        timestamp = timestamp,
        retrievedMemories = retrievedMemories,
        rewardedMemo = rewardedMemo,
        isError = isError,
        pendingDecryption = pendingDecryption,
        encryptedMemoryIds = encryptedMemoryIds,
        messageId = id
    )
}
