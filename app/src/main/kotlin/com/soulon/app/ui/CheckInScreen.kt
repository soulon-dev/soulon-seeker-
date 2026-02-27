package com.soulon.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soulon.app.data.BalanceState
import com.soulon.app.rewards.CheckInResult
import com.soulon.app.rewards.RewardsRepository
import com.soulon.app.ui.theme.*
import com.soulon.app.i18n.AppStrings
import kotlinx.coroutines.launch

/**
 * 签到页面
 * 
 * 🔒 后端优先架构（Backend-First Architecture）
 * 
 * 功能：
 * - 7天签到循环展示
 * - 连续签到天数
 * - 签到动画效果
 * - 积分奖励展示
 * - 网络错误处理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    rewardsRepository: RewardsRepository,
    walletAddress: String?,
    onBack: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    // 状态
    var isLoading by remember { mutableStateOf(true) }
    var checkInResult by remember { mutableStateOf<CheckInResult?>(null) }
    var showSuccessAnimation by remember { mutableStateOf(false) }
    var consecutiveDays by remember { mutableIntStateOf(0) }
    var weeklyProgress by remember { mutableIntStateOf(0) }
    var totalCheckInDays by remember { mutableIntStateOf(0) }
    var hasCheckedInToday by remember { mutableStateOf(false) }
    var currentMemoBalance by remember { mutableIntStateOf(0) }
    var currentTier by remember { mutableIntStateOf(1) }  // 用户等级 (1-5)
    
    // 🆕 网络错误状态
    var networkError by remember { mutableStateOf<String?>(null) }
    var isCheckingIn by remember { mutableStateOf(false) }
    
    // 7天奖励循环
    val weeklyRewards = listOf(20, 20, 20, 50, 50, 50, 150)
    
    // 🆕 收集后端优先状态流
    val balanceState by rewardsRepository.getBalanceStateFlow().collectAsState()
    
    // 实时收集用户档案更新（使用 produceState 确保初始值正确）
    val userProfile by produceState<com.soulon.app.rewards.UserProfile?>(initialValue = null) {
        // 首先同步获取当前档案
        val initialProfile = rewardsRepository.getUserProfile()
        value = initialProfile
        // 然后持续监听更新
        rewardsRepository.getUserProfileFlow().collect { profile ->
            if (profile != null) {
                value = profile
            }
        }
    }
    
    // 🆕 根据后端状态更新 UI
    LaunchedEffect(balanceState) {
        when (val state = balanceState) {
            is BalanceState.Loading -> {
                // 只有在没有显示数据时才显示全屏加载
                // 如果已经有缓存数据，则静默刷新
                if (totalCheckInDays == 0 && currentMemoBalance == 0) {
                    isLoading = true
                }
                networkError = null
            }
            is BalanceState.Success -> {
                isLoading = false
                networkError = null
                // 从后端数据更新 UI
                consecutiveDays = state.data.consecutiveCheckInDays
                weeklyProgress = state.data.weeklyCheckInProgress
                totalCheckInDays = state.data.totalCheckInDays
                currentMemoBalance = state.data.memoBalance
                currentTier = state.data.currentTier
                hasCheckedInToday = state.data.hasCheckedInToday
            }
            is BalanceState.Error -> {
                isLoading = false
                // 如果有缓存数据，仅显示 Toast 或 Snackbar 提示（这里通过 networkError 变量控制 UI）
                // 如果没有数据，显示全屏错误
                if (totalCheckInDays == 0) {
                    networkError = state.message
                } else {
                    // TODO: 可以改为显示 Snackbar
                    // networkError = state.message 
                }
            }
        }
    }
    
    // 当用户档案更新时，更新 UI 状态（作为后备）
    LaunchedEffect(userProfile) {
        userProfile?.let { profile ->
            // 只有在后端状态不可用时才使用本地数据
            if (balanceState !is BalanceState.Success) {
                consecutiveDays = profile.consecutiveCheckInDays
                weeklyProgress = profile.weeklyCheckInProgress
                totalCheckInDays = profile.totalCheckInDays
                currentMemoBalance = profile.memoBalance
                currentTier = profile.currentTier
                
                // 检查今日是否已签到
                val today = java.time.LocalDate.now().toString()
                hasCheckedInToday = profile.lastCheckInDate == today
                
                isLoading = false
            }
        }
    }
    
    // 🆕 初始化时刷新后端数据
    LaunchedEffect(walletAddress) {
        if (walletAddress != null) {
            scope.launch {
                rewardsRepository.initializeBackendFirst(walletAddress)
                rewardsRepository.refreshFromBackend()
            }
        }
    }
    
    // 签到动画
    val bounceAnimation = rememberInfiniteTransition(label = "bounce")
    val scale by bounceAnimation.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        AppStrings.tr("每日签到", "Daily Check-in"),
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = AppStrings.back)
                    }
                },
                actions = {
                    TextButton(onClick = onNavigateToHistory) {
                        Text(AppStrings.tr("积分记录", "History"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶部统计卡片（使用等级颜色）
                item {
                    CheckInStatsCard(
                        consecutiveDays = consecutiveDays,
                        totalCheckInDays = totalCheckInDays,
                        currentBalance = currentMemoBalance,
                        currentTier = currentTier,
                        onCardClick = onNavigateToHistory
                    )
                }
                
                // 7天签到进度
                item {
                    WeeklyProgressCard(
                        weeklyProgress = weeklyProgress,
                        weeklyRewards = weeklyRewards,
                        hasCheckedInToday = hasCheckedInToday
                    )
                }
                
                // 🆕 网络错误提示
                if (networkError != null) {
                    item {
                        NetworkErrorCard(
                            errorMessage = networkError!!,
                            onRetry = {
                                scope.launch {
                                    networkError = null
                                    isLoading = true
                                    rewardsRepository.refreshFromBackend()
                                }
                            }
                        )
                    }
                }
                
                // 签到按钮
                item {
                    CheckInButton(
                        hasCheckedInToday = hasCheckedInToday,
                        showSuccessAnimation = showSuccessAnimation,
                        scale = if (!hasCheckedInToday && !isCheckingIn) scale else 1f,
                        checkInResult = checkInResult,
                        isCheckingIn = isCheckingIn,
                        onCheckIn = {
                            if (walletAddress != null && !isCheckingIn) {
                                scope.launch {
                                    isCheckingIn = true
                                    networkError = null
                                    
                                    val result = rewardsRepository.checkIn(walletAddress)
                                    checkInResult = result
                                    
                                    if (result.success) {
                                        showSuccessAnimation = true
                                        hasCheckedInToday = true
                                        consecutiveDays = result.consecutiveDays
                                        weeklyProgress = result.weeklyProgress
                                        totalCheckInDays++
                                        currentMemoBalance += result.reward
                                        
                                        // 3秒后隐藏动画
                                        kotlinx.coroutines.delay(3000)
                                        showSuccessAnimation = false
                                    } else if (result.message.contains("网络") || result.message.contains("错误")) {
                                        // 🆕 显示网络错误
                                        networkError = result.message
                                    }
                                    
                                    isCheckingIn = false
                                }
                            }
                        }
                    )
                }
                
                // 签到规则说明
                item {
                    CheckInRulesCard()
                }
                
                // 底部间距
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun CheckInStatsCard(
    consecutiveDays: Int,
    totalCheckInDays: Int,
    currentBalance: Int,
    currentTier: Int,
    onCardClick: () -> Unit
) {
    // 根据等级获取渐变颜色
    val tierGradient = TierColors.getGradientBrush(currentTier)
    val tierName = TierColors.getTierNameLocalized(currentTier)
    val tierEmoji = TierColors.getTierEmoji(currentTier)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() },  // 点击跳转到积分记录
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = tierGradient)
                .padding(24.dp)
        ) {
            Column {
                // 等级标识
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = tierEmoji + " " + tierName,
                        color = Color.Black.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        value = consecutiveDays.toString(),
                        label = AppStrings.tr("连续签到", "Streak"),
                        icon = Icons.Default.LocalFireDepartment
                    )
                    StatItem(
                        value = totalCheckInDays.toString(),
                        label = AppStrings.tr("累计签到", "Total"),
                        icon = Icons.Default.CalendarMonth
                    )
                    StatItem(
                        value = currentBalance.toString(),
                        label = AppStrings.tr("MEMO余额", "MEMO"),
                        icon = Icons.Default.AccountBalanceWallet
                    )
                }
                
                // 点击提示
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = AppStrings.tr("点击查看积分记录 →", "Tap to view history →"),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            color = Color.Black,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.Black.copy(alpha = 0.7f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun WeeklyProgressCard(
    weeklyProgress: Int,
    weeklyRewards: List<Int>,
    hasCheckedInToday: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color.White)
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
                    text = AppStrings.tr("本周签到进度", "This week"),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = AppStrings.trf(
                        "%d / 7 天",
                        "%d / 7 days",
                        weeklyProgress
                    ),
                    color = AppColors.PrimaryGradientStart,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // 7天进度展示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                weeklyRewards.forEachIndexed { index, reward ->
                    val dayNumber = index + 1
                    val isCompleted = dayNumber <= weeklyProgress
                    val isToday = dayNumber == weeklyProgress + 1 && !hasCheckedInToday
                    val isTodayCompleted = dayNumber == weeklyProgress && hasCheckedInToday
                    
                    DayItem(
                        day = dayNumber,
                        reward = reward,
                        isCompleted = isCompleted,
                        isToday = isToday || isTodayCompleted,
                        isTodayCompleted = isTodayCompleted
                    )
                }
            }
        }
    }
}

@Composable
private fun DayItem(
    day: Int,
    reward: Int,
    isCompleted: Boolean,
    isToday: Boolean,
    isTodayCompleted: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 日期圆圈
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isCompleted -> AppColors.SuccessGradientStart
                        isToday -> AppColors.PrimaryGradientStart
                        else -> Color(0xFFE2E8F0)
                    }
                )
                .then(
                    if (isToday && !isTodayCompleted) {
                        Modifier.border(2.dp, AppColors.PrimaryGradientEnd, CircleShape)
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = day.toString(),
                    color = if (isToday) Color.White else Color(0xFF94A3B8),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        // 奖励数值
        Text(
            text = "+" + reward,
            fontSize = 11.sp,
            fontWeight = if (reward >= 100) FontWeight.Bold else FontWeight.Medium,
            color = when {
                reward >= 100 -> AppColors.WarningGradientStart
                isCompleted -> AppColors.SuccessGradientStart
                else -> Color(0xFF94A3B8)
            }
        )
    }
}

/**
 * 🆕 网络错误卡片
 */
@Composable
private fun NetworkErrorCard(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = Color(0xFFDC2626),
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = AppStrings.tr("网络连接失败", "Network connection failed"),
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFDC2626),
                    fontSize = 14.sp
                )
                Text(
                    text = errorMessage,
                    color = Color(0xFF991B1B),
                    fontSize = 12.sp
                )
            }
            
            TextButton(onClick = onRetry) {
                Text(
                    text = AppStrings.retry,
                    color = AppColors.PrimaryGradientStart,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun CheckInButton(
    hasCheckedInToday: Boolean,
    showSuccessAnimation: Boolean,
    scale: Float,
    checkInResult: CheckInResult?,
    isCheckingIn: Boolean = false,
    onCheckIn: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = showSuccessAnimation,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 成功动画
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(AppColors.SuccessGradientStart),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = AppStrings.tr("签到成功！", "Check-in success!"),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.SuccessGradientStart
                    )
                    
                    checkInResult?.let { result ->
                        Text(
                            text = "+${result.reward} MEMO",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.PrimaryGradientStart
                        )
                        
                        if (result.message.isNotEmpty()) {
                            Text(
                                text = result.message,
                                color = AppColors.TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
            
            AnimatedVisibility(
                visible = !showSuccessAnimation,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Button(
                    onClick = { if (!hasCheckedInToday && !isCheckingIn) onCheckIn() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .scale(scale),
                    enabled = !hasCheckedInToday && !isCheckingIn,
                    shape = AppShapes.Button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            isCheckingIn -> AppColors.PrimaryGradientStart.copy(alpha = 0.7f)
                            hasCheckedInToday -> Color(0xFFE2E8F0)
                            else -> AppColors.PrimaryGradientStart
                        },
                        disabledContainerColor = Color(0xFFE2E8F0)
                    )
                ) {
                    when {
                        isCheckingIn -> {
                            // 🆕 签到中状态
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = AppStrings.tr("签到中...", "Checking in..."),
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        hasCheckedInToday -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AppColors.SuccessGradientStart
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = AppStrings.tr("今日已签到", "Checked in today"),
                                color = AppColors.SuccessGradientStart,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = AppStrings.tr("立即签到", "Check in now"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckInRulesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = AppColors.PrimaryGradientStart,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = AppStrings.tr("签到规则", "Rules"),
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val rules = listOf(
                AppStrings.tr("每日签到可获得 MEMO 积分奖励", "Daily check-in earns MEMO rewards"),
                AppStrings.tr("7天为一个周期：20→20→20→50→50→50→150", "7-day cycle: 20→20→20→50→50→50→150"),
                AppStrings.tr("连续签到不中断，第7天可获得150积分大奖", "Keep the streak to get 150 points on day 7"),
                AppStrings.tr("断签后将从第1天重新开始", "Missing a day resets to day 1"),
                AppStrings.tr("积分可用于等级提升和生态权益", "Points are used for tier upgrades and benefits")
            )
            
            rules.forEachIndexed { index, rule ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "${index + 1}.",
                        color = AppColors.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(
                        text = rule,
                        color = AppColors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
