package com.soulon.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soulon.app.rewards.MemoTransactionLog
import com.soulon.app.rewards.RewardsRepository
import com.soulon.app.rewards.TransactionType
import com.soulon.app.ui.theme.*
import com.soulon.app.i18n.AppStrings
import java.text.SimpleDateFormat
import java.util.*

private fun MemoTransactionLog.toFilterType(): TransactionType {
    val t = transactionType.lowercase(Locale.getDefault())
    return when {
        amount < 0 -> TransactionType.SPEND
        t.contains("check") -> TransactionType.CHECK_IN
        t.contains("login") -> TransactionType.DAILY_LOGIN
        t.contains("tier") -> TransactionType.TIER_BONUS
        t.contains("dialogue") || t.contains("inference") || t.contains("chat") -> TransactionType.AI_INFERENCE_REWARD
        t.contains("adventure") || t.contains("task") -> TransactionType.TASK_COMPLETION
        else -> TransactionType.OTHER
    }
}

/**
 * 积分历史记录页面
 * 
 * 功能：
 * - 显示所有积分交易记录
 * - 按类型筛选
 * - 积分统计概览
 * - 卡片颜色随等级变化
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoHistoryScreen(
    rewardsRepository: RewardsRepository,
    onBack: () -> Unit
) {
    // 状态
    var isLoading by remember { mutableStateOf(true) }
    var transactions by remember { mutableStateOf<List<MemoTransactionLog>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf<TransactionType?>(null) }
    var currentBalance by remember { mutableIntStateOf(0) }
    var totalEarned by remember { mutableIntStateOf(0) }
    var totalSpent by remember { mutableIntStateOf(0) }
    var todayEarned by remember { mutableIntStateOf(0) }
    var currentTier by remember { mutableIntStateOf(1) }  // 用户等级 (1-5)
    
    // 收集交易记录流
    val transactionsFlow = rewardsRepository.getAllTransactionLogsFlow()
        .collectAsState(initial = emptyList())
    
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
    
    // 当用户档案更新时，更新 UI 状态
    LaunchedEffect(userProfile) {
        userProfile?.let { profile ->
            currentBalance = profile.memoBalance
            currentTier = profile.currentTier
            totalEarned = profile.totalMemoEarned
            isLoading = false
        }
    }
    
    // 更新交易列表
    LaunchedEffect(transactionsFlow.value) {
        transactions = transactionsFlow.value
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        todayEarned = transactions.sumOf { if (it.createdAt >= startOfDay && it.amount > 0) it.amount else 0 }
        totalSpent = transactions.sumOf { if (it.amount < 0) kotlin.math.abs(it.amount) else 0 }
    }
    
    // 筛选后的交易
    val filteredTransactions = remember(transactions, selectedFilter) {
        if (selectedFilter == null) {
            transactions
        } else {
            transactions.filter { it.toFilterType() == selectedFilter }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        AppStrings.tr("积分记录", "Point History"),
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = AppStrings.back)
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
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 统计卡片（使用等级颜色）
                item {
                    MemoStatsCard(
                        currentBalance = currentBalance,
                        totalEarned = totalEarned,
                        totalSpent = totalSpent,
                        todayEarned = todayEarned,
                        currentTier = currentTier,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                
                // 筛选标签
                item {
                    FilterChips(
                        selectedFilter = selectedFilter,
                        onFilterSelected = { selectedFilter = it },
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
                
                // 交易记录列表
                if (filteredTransactions.isEmpty()) {
                    item {
                        EmptyState(
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                } else {
                    // 按日期分组
                    val groupedTransactions = filteredTransactions.groupBy { transaction ->
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(Date(transaction.createdAt))
                    }
                    
                    groupedTransactions.forEach { (date, dayTransactions) ->
                        item {
                            DateHeader(
                                date = date,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                        
                        items(dayTransactions) { transaction ->
                            TransactionItem(
                                transaction = transaction,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
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
private fun MemoStatsCard(
    currentBalance: Int,
    totalEarned: Int,
    totalSpent: Int,
    todayEarned: Int,
    currentTier: Int,
    modifier: Modifier = Modifier
) {
    // 根据等级获取渐变颜色
    val tierGradient = TierColors.getGradientBrush(currentTier)
    val tierName = TierColors.getTierNameLocalized(currentTier)
    val tierEmoji = TierColors.getTierEmoji(currentTier)
    
    Card(
        modifier = modifier.fillMaxWidth(),
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = AppStrings.tr("当前余额", "Balance"),
                        color = Color.Black.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                    Text(
                        text = AppStrings.trf(
                            "%s %s 会员",
                            "%s %s member",
                            tierEmoji,
                            tierName
                        ),
                        color = Color.Black.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = currentBalance.toString() + " " + AppStrings.tr("MEMO", "MEMO"),
                    color = Color.Black,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MiniStatItem(
                        label = AppStrings.tr("累计获得", "Total earned"),
                        value = "+$totalEarned",
                        color = Color(0xFF059669)  // 深绿色，在各种等级颜色上都清晰
                    )
                    MiniStatItem(
                        label = AppStrings.tr("累计消耗", "Total spent"),
                        value = "-$totalSpent",
                        color = Color(0xFFDC2626)  // 深红色
                    )
                    MiniStatItem(
                        label = AppStrings.tr("今日收入", "Today earned"),
                        value = "+$todayEarned",
                        color = Color(0xFFD97706)  // 深橙色
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniStatItem(
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.Black.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun FilterChips(
    selectedFilter: TransactionType?,
    onFilterSelected: (TransactionType?) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = listOf(
        null to AppStrings.tr("全部", "All"),
        TransactionType.AI_INFERENCE_REWARD to AppStrings.tr("AI对话", "AI Chat"),
        TransactionType.CHECK_IN to AppStrings.tr("签到", "Check-in"),
        TransactionType.DAILY_BONUS to AppStrings.tr("首聊", "First chat"),
        TransactionType.SOUL_RESONANCE to AppStrings.tr("共鸣", "Resonance"),
        TransactionType.SPEND to AppStrings.tr("消耗", "Spend")
    )
    
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 16.dp)
    ) {
        items(filters) { (type, label) ->
            FilterChip(
                selected = selectedFilter == type,
                onClick = { onFilterSelected(type) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.PrimaryGradientStart,
                    selectedLabelColor = Color.White
                )
            )
        }
    }
}

@Composable
private fun DateHeader(
    date: String,
    modifier: Modifier = Modifier
) {
    val displayDate = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 86400000))
        
        when (date) {
            today -> AppStrings.tr("今天", "Today")
            yesterday -> AppStrings.tr("昨天", "Yesterday")
            else -> {
                val pattern = AppStrings.tr("MM月dd日", "MMM dd")
                SimpleDateFormat(pattern, Locale.getDefault()).format(parsed!!)
            }
        }
    } catch (e: Exception) {
        date
    }
    
    Text(
        text = displayDate,
        modifier = modifier.padding(vertical = 8.dp),
        color = AppColors.TextSecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun TransactionItem(
    transaction: MemoTransactionLog,
    modifier: Modifier = Modifier
) {
    val type = transaction.toFilterType()
    val (icon, iconColor, bgColor) = getTransactionStyle(type)
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 描述
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = getTransactionTypeName(type),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    text = transaction.description,
                    color = AppColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // 时间
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(transaction.createdAt)),
                    color = AppColors.TextTertiary,
                    fontSize = 12.sp
                )
            }
            
            // 积分
            Text(
                text = transaction.getSignedAmount(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (transaction.isIncome()) 
                    AppColors.SuccessGradientStart 
                else 
                    Color(0xFFEF4444)
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Receipt,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = AppStrings.tr("暂无记录", "No records"),
                color = AppColors.TextSecondary,
                fontSize = 16.sp
            )
            Text(
                text = AppStrings.tr("完成任务或签到可获得积分", "Complete tasks or check in to earn points"),
                color = AppColors.TextTertiary,
                fontSize = 14.sp
            )
        }
    }
}

private fun getTransactionTypeName(type: TransactionType): String {
    return when (type) {
        TransactionType.AI_INFERENCE_REWARD -> AppStrings.tr("AI对话奖励", "AI chat reward")
        TransactionType.TIER_BONUS -> AppStrings.tr("等级加成", "Tier bonus")
        TransactionType.DAILY_LOGIN -> AppStrings.tr("每日登录", "Daily login")
        TransactionType.CHECK_IN -> AppStrings.tr("每日签到", "Daily check-in")
        TransactionType.DAILY_BONUS -> AppStrings.tr("每日首聊", "First chat of the day")
        TransactionType.TASK_COMPLETION -> AppStrings.tr("任务完成", "Task completed")
        TransactionType.MEMORY_CREATION -> AppStrings.tr("创建记忆", "Memory created")
        TransactionType.MEMORY_SHARE -> AppStrings.tr("分享记忆", "Memory shared")
        TransactionType.SOUL_RESONANCE -> AppStrings.tr("人格共鸣", "Persona resonance")
        TransactionType.SPEND -> AppStrings.tr("积分消耗", "Points spent")
        TransactionType.SYSTEM_ADJUSTMENT -> AppStrings.tr("系统调整", "System adjustment")
        TransactionType.OTHER -> AppStrings.tr("其他", "Other")
    }
}

private fun getTransactionStyle(type: TransactionType): Triple<ImageVector, Color, Color> {
    return when (type) {
        TransactionType.AI_INFERENCE_REWARD -> Triple(
            Icons.Default.Chat,
            AppColors.PrimaryGradientStart,
            AppColors.PrimaryGradientStart.copy(alpha = 0.1f)
        )
        TransactionType.CHECK_IN -> Triple(
            Icons.Default.CalendarToday,
            AppColors.SuccessGradientStart,
            AppColors.SuccessGradientStart.copy(alpha = 0.1f)
        )
        TransactionType.DAILY_BONUS -> Triple(
            Icons.Default.WbSunny,
            AppColors.WarningGradientStart,
            AppColors.WarningGradientStart.copy(alpha = 0.1f)
        )
        TransactionType.SOUL_RESONANCE -> Triple(
            Icons.Default.Favorite,
            Color(0xFFEC4899),
            Color(0xFFEC4899).copy(alpha = 0.1f)
        )
        TransactionType.TIER_BONUS -> Triple(
            Icons.Default.TrendingUp,
            AppColors.SecondaryGradientStart,
            AppColors.SecondaryGradientStart.copy(alpha = 0.1f)
        )
        TransactionType.MEMORY_CREATION -> Triple(
            Icons.Default.Create,
            Color(0xFF8B5CF6),
            Color(0xFF8B5CF6).copy(alpha = 0.1f)
        )
        TransactionType.SPEND -> Triple(
            Icons.Default.ShoppingCart,
            Color(0xFFEF4444),
            Color(0xFFEF4444).copy(alpha = 0.1f)
        )
        else -> Triple(
            Icons.Default.Star,
            AppColors.TextSecondary,
            AppColors.TextSecondary.copy(alpha = 0.1f)
        )
    }
}
