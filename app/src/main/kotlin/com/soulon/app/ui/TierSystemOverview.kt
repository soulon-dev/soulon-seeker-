package com.soulon.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.soulon.app.tier.MemberTierManager.MemberInfo
import com.soulon.app.tier.UserTierManager
import com.soulon.app.tier.UserTierManager.UserLevelInfo
import com.soulon.app.ui.theme.*
import com.soulon.app.ui.showComingSoonToast
import com.soulon.app.i18n.AppStrings
import kotlinx.coroutines.launch

/**
 * 等级系统总览
 * 
 * 整合展示两个独立的等级系统：
 * 1. 会员等级 (Member Tier) - 影响项目奖励（空投、NFT、实物）
 * 2. 用户级别 (User Level) - 影响 Token 限额和积分速度
 */
@Composable
fun TierSystemOverview(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onNavigateToMemberTier: () -> Unit = {},
    onNavigateToUserLevel: () -> Unit = {},
    onNavigateToSubscribe: () -> Unit = {},
    onNavigateToStake: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val memberTierManager = remember { MemberTierManager(context) }
    val rewardsRepository = remember { RewardsRepository(context) }
    val userTierManager = remember { UserTierManager(context, rewardsRepository) }
    
    var memberInfo by remember { mutableStateOf<MemberInfo?>(null) }
    var userLevelInfo by remember { mutableStateOf<UserLevelInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    // 获取钱包地址
    val prefs = remember { context.getSharedPreferences("wallet_prefs", android.content.Context.MODE_PRIVATE) }
    val walletAddress = remember { prefs.getString("connected_wallet", null) }
    
    // 加载数据（支持从后端同步）
    LaunchedEffect(Unit) {
        isLoading = true
        
        // 先从后端同步最新数据
        if (walletAddress != null) {
            try {
                val synced = rewardsRepository.syncFromBackend(walletAddress)
                if (synced) {
                    timber.log.Timber.d("等级系统：后端数据同步成功")
                }
            } catch (e: Exception) {
                timber.log.Timber.w(e, "等级系统：后端同步失败")
            }
        }
        
        // 然后加载本地数据
        memberInfo = memberTierManager.getMemberInfo()
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
                OverviewHeader(onNavigateBack)
            }
            
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = AppColors.PrimaryGradientStart,
                            strokeWidth = 3.dp
                        )
                    }
                }
            } else {
                // 系统说明卡片
                item {
                    SystemExplanationCard()
                }
                
                // 会员等级概览卡片
                memberInfo?.let { info ->
                    item {
                        MemberTierOverviewCard(
                            info = info,
                            onClick = onNavigateToMemberTier
                        )
                    }
                }
                
                // 用户级别概览卡片
                userLevelInfo?.let { info ->
                    item {
                        UserLevelOverviewCard(
                            info = info,
                            manager = userTierManager,
                            onClick = onNavigateToUserLevel
                        )
                    }
                }
                
                // 两系统联动说明
                item {
                    SynergyCard(
                        userLevel = userLevelInfo?.level,
                        memberTier = memberInfo?.tier
                    )
                }
                
                // 快速操作
                item {
                    QuickActionsCard(
                        onSubscribe = onNavigateToSubscribe,
                        onStake = onNavigateToStake
                    )
                }
                
                // 底部留白
                item {
                    Spacer(modifier = Modifier.height(AppSpacing.XXLarge))
                }
            }
        }
    }
}

@Composable
private fun OverviewHeader(onNavigateBack: () -> Unit) {
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
            text = AppStrings.tr("等级系统", "Tier System"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 系统说明卡片
 */
@Composable
private fun SystemExplanationCard() {
    var showDescription by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(
            containerColor = AppColors.PrimaryGradientStart.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Large)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                // 可点击的图标
                IconButton(
                    onClick = { showDescription = !showDescription },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = AppStrings.tr("说明", "Info"),
                        tint = AppColors.PrimaryGradientStart
                    )
                }
                Text(
                    text = AppStrings.tr("两个独立的等级系统", "Two independent tier systems"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // 点击图标后显示描述
            if (showDescription) {
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                
                Text(
                    text = AppStrings.tr(
                        "Soulon 拥有两个独立但相互关联的等级系统：\n\n" +
                            "• 会员等级 - 通过日常使用积累积分，影响空投、NFT 和实物奖励\n" +
                            "• 用户级别 - 通过订阅或质押提升，影响 Token 限额和积分速度\n\n" +
                            "用户级别越高，会员积分累积越快！",
                        "Soulon has two independent yet connected tier systems:\n\n" +
                            "• Member Tier – earn points through daily use; affects airdrops, NFTs, and physical rewards\n" +
                            "• User Level – upgrade via subscription or staking; affects token quota and earning speed\n\n" +
                            "Higher user levels accelerate member point accumulation!"
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    lineHeight = 22.sp
                )
            }
        }
    }
}

/**
 * 会员等级概览卡片
 */
@Composable
private fun MemberTierOverviewCard(
    info: MemberInfo,
    onClick: () -> Unit
) {
    val tierColor = Color(info.tier.colorHex)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24))
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(tierColor.copy(alpha = 0.2f), shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = info.tier.iconEmoji,
                            fontSize = 24.sp
                        )
                    }
                    
                    Column {
                        Text(
                            text = AppStrings.tr("会员等级", "Member Tier"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = info.tier.localizedName(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = tierColor
                        )
                    }
                }
                
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = AppStrings.tr("查看详情", "View details"),
                    tint = Color.White.copy(alpha = 0.3f)
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            
            // 进度条
            if (info.nextTier != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${info.tier.localizedName()} → ${info.nextTier.localizedName()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "${(info.progressPercent * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = tierColor
                    )
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                
                LinearProgressIndicator(
                    progress = { info.progressPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(Color.White.copy(alpha = 0.1f), shape = AppShapes.Tag),
                    color = tierColor,
                    trackColor = Color.Transparent,
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            
            // 权益预览
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MiniStat(label = AppStrings.tr("空投倍数", "Airdrop"), value = "${info.benefits.airdropMultiplier}x")
                MiniStat(label = AppStrings.tr("NFT 几率", "NFT rate"), value = "${(info.benefits.nftDropRate * 100).toInt()}%")
                MiniStat(label = AppStrings.tr("会员积分", "Points"), value = "%,d".format(info.totalPoints))
            }
        }
    }
}

/**
 * 用户级别概览卡片
 */
@Composable
private fun UserLevelOverviewCard(
    info: UserLevelInfo,
    manager: UserTierManager,
    onClick: () -> Unit
) {
    val levelColor = Color(info.level.color)
    val levelIcon = manager.getLevelIcon(info.level)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24))
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(levelColor.copy(alpha = 0.2f), shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = levelIcon,
                            fontSize = 24.sp
                        )
                    }
                    
                    Column {
                        Text(
                            text = AppStrings.tr("用户级别", "User Level"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = manager.getLevelDisplayName(info.level),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = levelColor
                        )
                    }
                }
                
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = AppStrings.tr("查看详情", "View details"),
                    tint = Color.White.copy(alpha = 0.3f)
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            
            // 权益预览
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MiniStat(
                    label = AppStrings.tr("每月限额", "Monthly quota"),
                    value = formatTokenLimit(info.benefits.monthlyTokenLimit)
                )
                MiniStat(
                    label = AppStrings.tr("积分倍率", "Point multiplier"),
                    value = "${info.benefits.memoMultiplier}x"
                )
                MiniStat(
                    label = AppStrings.tr("优先准入", "Priority"),
                    value = if (info.benefits.priorityAccess) "✓" else "✗"
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
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
 * 两系统联动说明
 */
@Composable
private fun SynergyCard(
    userLevel: UserTierManager.UserLevel?,
    memberTier: MemberTierManager.MemberTier?
) {
    val multiplier = userLevel?.memoMultiplier ?: 1.0f
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = AppColors.SuccessGradientStart.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = null,
                tint = AppColors.SuccessGradientStart,
                modifier = Modifier.size(32.dp)
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = AppStrings.tr("系统联动效果", "Synergy"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                val levelName = when (userLevel) {
                    null -> AppStrings.tr("普通用户", "Free")
                    UserTierManager.UserLevel.FREE -> AppStrings.tr("普通用户", "Free")
                    UserTierManager.UserLevel.SUBSCRIBER -> AppStrings.tr("订阅用户", "Subscriber")
                    UserTierManager.UserLevel.STAKER -> AppStrings.tr("质押用户", "Staker")
                    UserTierManager.UserLevel.FOUNDER -> AppStrings.tr("创始人用户", "Founder")
                    UserTierManager.UserLevel.EXPERT -> AppStrings.tr("技术专家用户", "Expert")
                }
                Text(
                    text = AppStrings.tr(
                        "您的 $levelName 级别为会员积分提供 ${multiplier}x 加速",
                        "Your $levelName level boosts member points by ${multiplier}x"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
            
            Box(
                modifier = Modifier
                    .background(
                        AppColors.SuccessGradientStart.copy(alpha = 0.2f),
                        shape = AppShapes.Tag
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = multiplier.toString() + "x",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.SuccessGradientStart
                )
            }
        }
    }
}

/**
 * 快速操作卡片
 */
@Composable
private fun QuickActionsCard(
    onSubscribe: () -> Unit,
    onStake: () -> Unit
) {
    val context = LocalContext.current
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
                text = AppStrings.tr("快速提升", "Boost faster"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
            ) {
                // 订阅按钮
                Button(
                    onClick = onSubscribe,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.SecondaryGradientStart
                    ),
                    shape = AppShapes.Button
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync, // Changed to vector icon
                        contentDescription = null,
                        modifier = Modifier.size(16.dp), // Reduced size
                        tint = Color.White.copy(alpha = 0.7f) // Reduced opacity
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.XSmall))
                    Text(
                        text = AppStrings.tr("订阅", "Subscribe"),
                        color = Color.White.copy(alpha = 0.9f) // Slightly reduced opacity for text too
                    )
                }
                
                // 质押按钮
                Button(
                    onClick = {
                        showComingSoonToast(context)
                        // onStake()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.PrimaryGradientStart
                    ),
                    shape = AppShapes.Button
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.XSmall))
                    Text(AppStrings.tr("质押", "Stake"))
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
