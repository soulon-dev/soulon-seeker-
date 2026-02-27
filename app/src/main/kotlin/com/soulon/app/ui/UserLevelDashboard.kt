package com.soulon.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soulon.app.rewards.RewardsRepository
import com.soulon.app.tier.MemberTierManager
import com.soulon.app.tier.MemberTierManager.MemberTier
import com.soulon.app.tier.UserTierManager
import com.soulon.app.tier.UserTierManager.UserLevel
import com.soulon.app.tier.UserTierManager.UserLevelInfo
import com.soulon.app.ui.theme.*
import com.soulon.app.ui.showComingSoonToast
import com.soulon.app.i18n.AppStrings
import kotlinx.coroutines.launch

/**
 * 用户级别仪表盘
 * 
 * 展示用户级别（影响 Token 限额和积分速度）
 */
@Composable
fun UserLevelDashboard(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onNavigateToSubscribe: () -> Unit = {},
    onNavigateToStake: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val rewardsRepository = remember { RewardsRepository(context) }
    val userTierManager = remember { UserTierManager(context, rewardsRepository) }
    
    var userLevelInfo by remember { mutableStateOf<UserLevelInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    // 加载数据
    LaunchedEffect(Unit) {
        isLoading = true
        userLevelInfo = userTierManager.getUserLevelInfo()
        isLoading = false
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AppSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)
        ) {
            // 顶部导航
            item {
                UserLevelHeader(onNavigateBack)
            }
            
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = AppColors.PrimaryGradientStart,
                            strokeWidth = 3.dp
                        )
                    }
                }
            } else {
                userLevelInfo?.let { info ->
                    // 当前用户级别卡片
                    item {
                        CurrentUserLevelCard(info, userTierManager)
                    }
                    
                    // 级别权益对比
                    item {
                        LevelBenefitsComparison(info.level)
                    }
                    
                    // 升级选项
                    item {
                        UpgradeOptionsCard(
                            currentLevel = info.level,
                            onSubscribe = onNavigateToSubscribe,
                            onStake = {
                                showComingSoonToast(context)
                                // onNavigateToStake()
                            }
                        )
                    }
                    
                    // 所有级别说明
                    item {
                        AllLevelsGuide()
                    }
                    
                    // 底部留白
                    item {
                        Spacer(modifier = Modifier.height(AppSpacing.XXLarge))
                    }
                }
            }
        }
    }
}

@Composable
private fun UserLevelHeader(onNavigateBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = AppStrings.back,
                tint = Color.White
            )
        }
        
        Text(
            text = AppStrings.tr("用户级别", "User Level"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        
        // 用户级别标识
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                    ),
                    shape = AppShapes.Tag
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = AppStrings.tr("级别", "LEVEL"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * 当前用户级别卡片
 */
@Composable
private fun CurrentUserLevelCard(
    info: UserLevelInfo,
    manager: UserTierManager
) {
    val levelColor = Color(info.level.color)
    val levelIcon = manager.getLevelIcon(info.level)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .modernCardShadow(AppElevations.Large, AppShapes.LargeCard),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            levelColor.copy(alpha = 0.3f),
                            levelColor.copy(alpha = 0.1f)
                        )
                    ),
                    shape = AppShapes.LargeCard
                )
                .border(
                    width = 1.dp,
                    color = levelColor.copy(alpha = 0.5f),
                    shape = AppShapes.LargeCard
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.XLarge)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = AppStrings.tr("当前级别", "Current level"),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text = manager.getLevelDisplayName(info.level),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    
                    // 级别图标
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                levelColor.copy(alpha = 0.2f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = levelIcon,
                            fontSize = 36.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                // 权益展示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    LevelBenefitItem(
                        label = AppStrings.tr("每月限额", "Monthly quota"),
                        value = formatTokenLimit(info.benefits.monthlyTokenLimit),
                        icon = Icons.Rounded.Token,
                        color = AppColors.SecondaryGradientStart
                    )
                    
                    LevelBenefitItem(
                        label = AppStrings.tr("积分倍率", "Point multiplier"),
                        value = "${info.benefits.memoMultiplier}x",
                        icon = Icons.Rounded.Speed,
                        color = AppColors.WarningGradientStart
                    )
                    
                    LevelBenefitItem(
                        label = AppStrings.tr("优先准入", "Priority"),
                        value = if (info.benefits.priorityAccess) "✓" else "✗",
                        icon = Icons.Rounded.VerifiedUser,
                        color = if (info.benefits.priorityAccess) AppColors.SuccessGradientStart else Color.Gray
                    )
                }
                
                // 订阅/质押状态
                if (info.level == UserLevel.SUBSCRIBER && info.subscriptionExpiry != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color.White.copy(alpha = 0.1f),
                                shape = AppShapes.SmallButton
                            )
                            .padding(AppSpacing.Small),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.XSmall))
                        Text(
                            text = AppStrings.trf(
                                "订阅到期: %s",
                                "Subscription expires: %s",
                                formatDate(info.subscriptionExpiry)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
                
                if (info.level == UserLevel.STAKER && info.stakedAmount > 0) {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color.White.copy(alpha = 0.1f),
                                shape = AppShapes.SmallButton
                            )
                            .padding(AppSpacing.Small),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.XSmall))
                        Text(
                            text = AppStrings.trf(
                                "已质押: %s",
                                "Staked: %s",
                                formatStakeAmount(info.stakedAmount)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelBenefitItem(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color.copy(alpha = 0.15f), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

/**
 * 级别权益对比
 */
@Composable
private fun LevelBenefitsComparison(currentLevel: UserLevel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.XLarge)
        ) {
            Text(
                text = AppStrings.tr("权益对比", "Benefits comparison"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            // 表头
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = AppStrings.tr("权益", "Benefit"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = AppStrings.tr("当前", "Current"),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.PrimaryGradientStart,
                    modifier = Modifier.width(60.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = AppStrings.tr("下一级", "Next"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.width(60.dp),
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            
            val nextLevel = getNextUserLevel(currentLevel)
            
            // Token 限额
            ComparisonRow(
                label = AppStrings.tr("每月 Token", "Monthly tokens"),
                currentValue = formatTokenLimit(currentLevel.monthlyTokenLimit),
                nextValue = nextLevel?.let { formatTokenLimit(it.monthlyTokenLimit) } ?: "-"
            )
            
            // 积分倍率
            ComparisonRow(
                label = AppStrings.tr("积分倍率", "Point multiplier"),
                currentValue = "${currentLevel.memoMultiplier}x",
                nextValue = nextLevel?.let { "${it.memoMultiplier}x" } ?: "-"
            )
            
            // 优先准入
            ComparisonRow(
                label = AppStrings.tr("生态优先准入", "Ecosystem priority"),
                currentValue = if (currentLevel.priority >= 2) "✓" else "✗",
                nextValue = nextLevel?.let { if (it.priority >= 2) "✓" else "✗" } ?: "-"
            )
            
            // 高级功能
            ComparisonRow(
                label = AppStrings.tr("高级功能", "Advanced features"),
                currentValue = if (currentLevel.priority >= 2) "✓" else "✗",
                nextValue = nextLevel?.let { if (it.priority >= 2) "✓" else "✗" } ?: "-"
            )
            
            // 代币奖励
            ComparisonRow(
                label = AppStrings.tr("项目代币奖励", "Project token rewards"),
                currentValue = if (currentLevel.priority >= 3) "✓" else "✗",
                nextValue = nextLevel?.let { if (it.priority >= 3) "✓" else "✗" } ?: "-"
            )
        }
    }
}

@Composable
private fun ComparisonRow(
    label: String,
    currentValue: String,
    nextValue: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.XSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = currentValue,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = AppColors.PrimaryGradientStart,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center
        )
        Text(
            text = nextValue,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 升级选项卡片
 */
@Composable
private fun UpgradeOptionsCard(
    currentLevel: UserLevel,
    onSubscribe: () -> Unit,
    onStake: () -> Unit
) {
    if (currentLevel == UserLevel.FOUNDER || currentLevel == UserLevel.EXPERT) {
        return // 最高级别不显示升级选项
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.XLarge)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Upgrade,
                    contentDescription = null,
                    tint = AppColors.SuccessGradientStart,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = AppStrings.tr("升级选项", "Upgrade options"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            // 订阅选项
            if (currentLevel == UserLevel.FREE) {
                UpgradeOption(
                    title = AppStrings.tr("订阅会员", "Subscribe"),
                    description = AppStrings.tr("月付 SOL/USDC，享受 2x 积分加速和 500万 Token 限额", "Pay monthly in SOL/USDC for 2x points and 5M token quota"),
                    icon = Icons.Rounded.Sync,
                    color = AppColors.SecondaryGradientStart,
                    onClick = onSubscribe
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
            }
            
            // 质押选项
            if (currentLevel.priority < UserLevel.STAKER.priority) {
                UpgradeOption(
                    title = AppStrings.tr("质押升级", "Stake to upgrade"),
                    description = AppStrings.tr("锁定代币，享受 3x 积分加速、2000万 Token 限额和项目代币奖励", "Lock tokens for 3x points, 20M token quota, and project rewards"),
                    icon = Icons.Rounded.Lock,
                    color = AppColors.PrimaryGradientStart,
                    onClick = onStake
                )
            }
        }
    }
}

@Composable
private fun UpgradeOption(
    title: String,
    description: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Large),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(color.copy(alpha = 0.2f), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(AppSpacing.Medium))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.3f)
            )
        }
    }
}

/**
 * 所有级别说明
 */
@Composable
private fun AllLevelsGuide() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.XLarge)
        ) {
            Text(
                text = AppStrings.tr("用户级别说明", "User level guide"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            val levels = listOf(
                Triple("👤", AppStrings.tr("普通用户", "Standard"), AppStrings.tr("基础功能，每日 100 万 Token", "Basic features, 1M tokens/day")),
                Triple("⭐", AppStrings.tr("订阅用户", "Subscriber"), AppStrings.tr("月付订阅，2x 积分，500 万 Token", "Monthly subscription, 2x points, 5M tokens")),
                Triple("💎", AppStrings.tr("质押用户", "Staker"), AppStrings.tr("质押代币，3x 积分，2000 万 Token", "Stake tokens, 3x points, 20M tokens")),
                Triple("👑", AppStrings.tr("创始人用户", "Founder"), AppStrings.tr("大额质押，5x 积分，无限 Token，投票权", "Large stake, 5x points, unlimited tokens, voting rights")),
                Triple("🔧", AppStrings.tr("技术专家", "Expert"), AppStrings.tr("特殊贡献者，5x 积分，无限 Token", "Special contributor, 5x points, unlimited tokens"))
            )
            
            levels.forEach { (_, title, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.Small),
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// ========== 辅助函数 ==========

private fun formatTokenLimit(limit: Long): String {
    return when {
        limit == Long.MAX_VALUE -> AppStrings.tr("无限", "Unlimited")
        limit >= 1_000_000 -> "${limit / 1_000_000}M"
        limit >= 1_000 -> "${limit / 1_000}K"
        else -> limit.toString()
    }
}

private fun formatStakeAmount(lamports: Long): String {
    val sol = lamports / 1_000_000_000.0
    return when {
        sol >= 1000 -> "%.1fK SOL".format(sol / 1000)
        else -> "%.2f SOL".format(sol)
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

private fun getNextUserLevel(current: UserLevel): UserLevel? {
    return when (current) {
        UserLevel.FREE -> UserLevel.SUBSCRIBER
        UserLevel.SUBSCRIBER -> UserLevel.STAKER
        UserLevel.STAKER -> UserLevel.FOUNDER
        UserLevel.FOUNDER -> UserLevel.EXPERT
        UserLevel.EXPERT -> null
    }
}
