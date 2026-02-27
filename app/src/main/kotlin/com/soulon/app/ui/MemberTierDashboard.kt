package com.soulon.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soulon.app.tier.MemberTierManager
import com.soulon.app.tier.MemberTierManager.MemberTier
import com.soulon.app.tier.MemberTierManager.MemberInfo
import com.soulon.app.tier.UserTierManager
import com.soulon.app.tier.UserTierManager.UserLevel
import com.soulon.app.ui.theme.*
import com.soulon.app.i18n.AppStrings
import kotlinx.coroutines.launch

/**
 * 会员等级仪表盘
 * 
 * 展示两个独立的等级系统：
 * 1. 会员等级 (Member Tier) - 影响项目奖励
 * 2. 用户级别 (User Level) - 影响 Token 限额和积分速度
 */
@Composable
fun MemberTierDashboard(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onNavigateToMemoHistory: () -> Unit = {}  // 新增：跳转到积分记录页面
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val memberTierManager = remember { MemberTierManager(context) }
    val rewardsRepository = remember { com.soulon.app.rewards.RewardsRepository(context) }
    
    // 初始化时直接尝试获取缓存数据
    var memberInfo by remember { mutableStateOf<MemberInfo?>(null) }
    // 只有当没有缓存数据时才显示加载状态
    var isLoading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }
    
    // 获取钱包地址
    val prefs = remember { context.getSharedPreferences("wallet_prefs", android.content.Context.MODE_PRIVATE) }
    val walletAddress = remember { prefs.getString("connected_wallet", null) }
    
    // 加载数据（支持从后端同步）
    LaunchedEffect(Unit) {
        // 先尝试获取本地数据
        memberInfo = memberTierManager.getMemberInfo()
        isLoading = memberInfo == null
        
        // 先从后端同步最新数据（静默同步，不阻塞 UI 显示缓存数据）
        if (walletAddress != null) {
            try {
                // 在后台执行同步
                launch {
                    val synced = rewardsRepository.syncFromBackend(walletAddress)
                    if (synced) {
                        timber.log.Timber.d("会员等级页面：后端数据同步成功")
                        // 同步成功后刷新显示
                        memberInfo = memberTierManager.getMemberInfo()
                    }
                }
            } catch (e: Exception) {
                timber.log.Timber.w(e, "会员等级页面：后端同步失败")
            }
        }
        
        // 确保至少显示一次数据（如果之前是 null）
        if (memberInfo == null) {
            memberInfo = memberTierManager.getMemberInfo()
        }
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
                MemberHeader(onNavigateBack)
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
                memberInfo?.let { info ->
                    // 当前会员等级卡片（可点击跳转到积分记录）
                    item {
                        CurrentMemberTierCard(
                            info = info,
                            onCardClick = onNavigateToMemoHistory
                        )
                    }
                    
                    // 会员权益卡片
                    item {
                        MemberBenefitsCard(info)
                    }
                    
                    // 会员统计
                    item {
                        MemberStatsCard(info)
                    }
                    
                    // 等级路线图
                    item {
                        TierRoadmap(
                            currentTier = info.tier,
                            allTiers = memberTierManager.getAllTiers()
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
}

@Composable
private fun MemberHeader(onNavigateBack: () -> Unit) {
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
            text = AppStrings.tr("会员等级", "Member Tier"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        
        // 会员标识
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500))
                    ),
                    shape = AppShapes.Tag
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = AppStrings.tr("会员", "MEMBER"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
    }
}

/**
 * 当前会员等级卡片
 * 点击可跳转到积分记录页面
 */
@Composable
private fun CurrentMemberTierCard(
    info: MemberInfo,
    onCardClick: () -> Unit = {}
) {
    val tierColor = Color(info.tier.colorHex)
    
    // 动画效果
    val infiniteTransition = rememberInfiniteTransition(label = "tier_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .modernCardShadow(AppElevations.Large, AppShapes.LargeCard)
            .clickable { onCardClick() },  // 点击跳转到积分记录页面
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            tierColor.copy(alpha = 0.8f),
                            tierColor.copy(alpha = 0.6f)
                        )
                    ),
                    shape = AppShapes.LargeCard
                )
        ) {
            // 光晕效果
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = glowAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
            
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
                        val baseLang = AppStrings.getCurrentLanguage().substringBefore('-')
                        Text(
                            text = AppStrings.tr("当前会员等级", "Current member tier"),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Black.copy(alpha = 0.7f)
                        )
                        Text(
                            text = info.tier.localizedName(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text(
                            text = when (baseLang) {
                                "zh" -> info.tier.displayName
                                "en" -> info.tier.displayNameCn
                                else -> info.tier.displayName
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Black.copy(alpha = 0.6f)
                        )
                    }
                    
                    // 等级图标
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                Color.White.copy(alpha = 0.3f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = info.tier.iconEmoji,
                            fontSize = 40.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                // 积分和进度
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = AppStrings.tr("会员积分", "Member points"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black.copy(alpha = 0.6f)
                        )
                        Text(
                            text = java.text.NumberFormat.getIntegerInstance().format(info.totalPoints),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                    
                    if (info.nextTier != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = AppStrings.tr("距离下一级", "To next tier"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black.copy(alpha = 0.6f)
                            )
                            Text(
                                text = java.text.NumberFormat.getIntegerInstance().format(info.pointsToNextTier),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }
                
                // 进度条
                if (info.nextTier != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = AppStrings.tr(info.tier.displayNameCn, info.tier.displayName),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black.copy(alpha = 0.7f)
                            )
                            Text(
                                text = AppStrings.tr(info.nextTier.displayNameCn, info.nextTier.displayName),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black.copy(alpha = 0.7f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                        
                        LinearProgressIndicator(
                            progress = { info.progressPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(AppShapes.Tag),
                            color = Color.Black.copy(alpha = 0.6f),
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                        
                        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                        
                        Text(
                            text = ((info.progressPercent * 100).toInt()).toString() + "%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    
                    Text(
                        text = AppStrings.tr("🎉 已达最高等级！", "🎉 Max tier reached!"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                
                // 点击提示
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                Text(
                    text = AppStrings.tr("点击查看积分记录 →", "Tap to view point history →"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 会员权益卡片
 */
@Composable
private fun MemberBenefitsCard(info: MemberInfo) {
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
                    imageVector = Icons.Rounded.CardGiftcard,
                    contentDescription = null,
                    tint = AppColors.WarningGradientStart,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = AppStrings.tr("会员权益", "Member benefits"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            // 权益列表
            val benefits = listOf(
                Triple(
                    Icons.Rounded.LocalActivity,
                    AppStrings.tr("空投倍数", "Airdrop"),
                    "${info.benefits.airdropMultiplier}x"
                ),
                Triple(
                    Icons.Rounded.Image,
                    AppStrings.tr("NFT 掉落率", "NFT rate"),
                    "${(info.benefits.nftDropRate * 100).toInt()}%"
                ),
                Triple(
                    Icons.Rounded.Redeem,
                    AppStrings.tr("实物奖励资格", "Physical rewards"),
                    if (info.benefits.physicalRewardEligible) "✓" else "✗"
                ),
                Triple(
                    Icons.Rounded.Event,
                    AppStrings.tr("专属活动", "Exclusive events"),
                    if (info.benefits.exclusiveEvents) "✓" else "✗"
                )
            )
            
            benefits.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { (icon, label, value) ->
                        BenefitItem(
                            icon = icon,
                            label = label,
                            value = value,
                            isEnabled = value != "✗"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
            }
        }
    }
}

@Composable
private fun BenefitItem(
    icon: ImageVector,
    label: String,
    value: String,
    isEnabled: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(140.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (isEnabled) AppColors.PrimaryGradientStart.copy(alpha = 0.15f)
                    else Color.White.copy(alpha = 0.05f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isEnabled) AppColors.PrimaryGradientStart else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(24.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
        
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.3f)
        )
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 会员统计卡片
 */
@Composable
private fun MemberStatsCard(info: MemberInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Large),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatColumn(
                icon = Icons.Rounded.CalendarToday,
                value = "${info.stats.daysAsMember}",
                label = AppStrings.tr("会员天数", "Days")
            )
            
            StatColumn(
                icon = Icons.Rounded.LocalFireDepartment,
                value = "${info.stats.currentStreak}",
                label = AppStrings.tr("连续登录", "Streak")
            )
            
            StatColumn(
                icon = Icons.Rounded.Inbox,
                value = "${info.stats.totalAirdropsReceived}",
                label = AppStrings.tr("空投次数", "Airdrops")
            )
            
            StatColumn(
                icon = Icons.Rounded.Collections,
                value = "${info.stats.totalNftsReceived}",
                label = AppStrings.tr("NFT 数量", "NFTs")
            )
        }
    }
}

@Composable
private fun StatColumn(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
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
 * 等级路线图
 */
@Composable
private fun TierRoadmap(
    currentTier: MemberTier,
    allTiers: List<MemberTier>
) {
    Column {
        Text(
            text = AppStrings.tr("等级路线", "Tier roadmap"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = AppSpacing.Medium)
        )
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            items(allTiers) { tier ->
                TierMilestone(
                    tier = tier,
                    isCurrentTier = tier == currentTier,
                    isUnlocked = tier.level <= currentTier.level
                )
            }
        }
    }
}

@Composable
private fun TierMilestone(
    tier: MemberTier,
    isCurrentTier: Boolean,
    isUnlocked: Boolean
) {
    val tierColor = Color(tier.colorHex)
    
    Card(
        modifier = Modifier
            .width(120.dp)
            .then(
                if (isCurrentTier) Modifier.border(
                    2.dp,
                    tierColor,
                    AppShapes.Card
                ) else Modifier
            ),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = if (isUnlocked) tierColor.copy(alpha = 0.2f) else Color(0xFF1A1A24)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.Medium),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = tier.iconEmoji,
                fontSize = 32.sp
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.XSmall))
            
            Text(
                text = tier.localizedName(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (isUnlocked) Color.White else Color.White.copy(alpha = 0.4f)
            )
            
            Text(
                text = AppStrings.tr("%,d 积分".format(tier.pointsRequired), "%,d points".format(tier.pointsRequired)),
                style = MaterialTheme.typography.labelSmall,
                color = if (isUnlocked) tierColor else Color.White.copy(alpha = 0.3f)
            )
            
            if (isCurrentTier) {
                Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                Box(
                    modifier = Modifier
                        .background(tierColor, shape = AppShapes.Tag)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = AppStrings.tr("当前", "Current"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

/**
 * V1 积分获取指南
 * 
 * 公式：Total_MEMO = (Base + Personality_Bonus) × Multiplier
 */
@Composable
private fun PointsEarningGuide() {
    return
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
                    imageVector = Icons.Rounded.TipsAndUpdates,
                    contentDescription = null,
                    tint = AppColors.SecondaryGradientStart,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = AppStrings.tr("V1 积分获取公式", "V1 points formula"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            
            // V1 公式展示
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = AppColors.PrimaryGradientStart.copy(alpha = 0.15f)
            ) {
                Column(modifier = Modifier.padding(AppSpacing.Medium)) {
                    Text(
                        text = AppStrings.tr("Total_MEMO = (Base + 人格共鸣奖) × 倍数", "Total_MEMO = (Base + Personality Bonus) × Multiplier"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = AppStrings.tr("基础分 = 10 + min(Tokens, 200)", "Base = 10 + min(Tokens, 200)"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            // 基础分获取方式
            Text(
                text = AppStrings.tr("基础分获取", "How to earn base points"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            
            val baseEarnings = listOf(
                Triple("💬", AppStrings.tr("AI 对话", "AI chat"), AppStrings.tr("10-210 MEMO/条（每日前50条全额）", "10–210 MEMO per message (first 50/day)")),
                Triple("📅", AppStrings.tr("每日签到", "Daily check-in"), AppStrings.tr("20-150 MEMO（7天循环：20,20,20,50,50,50,150）", "20–150 MEMO (7-day cycle: 20,20,20,50,50,50,150)")),
                Triple("🌅", AppStrings.tr("每日首聊", "First chat of the day"), AppStrings.tr("+30 MEMO", "+30 MEMO"))
            )
            
            baseEarnings.forEach { (emoji, action, points) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.XSmall),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                    ) {
                        Text(text = emoji, fontSize = 18.sp)
                        Text(
                            text = action,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                    Text(
                        text = points,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.SuccessGradientStart
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            // 人格共鸣奖励
            Text(
                text = AppStrings.tr("人格共鸣奖 (Personality Bonus)", "Personality Bonus"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            
            Text(
                text = AppStrings.tr("AI 根据人格画像评估您的回复质量（0-100分）", "AI evaluates your response quality based on your persona (0–100)"),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            
            val resonanceGrades = listOf(
                Pair(AppStrings.tr("S级 (90-100)", "S (90–100)"), "+100 MEMO") to Color(0xFFFFD700),
                Pair(AppStrings.tr("A级 (70-89)", "A (70–89)"), "+30 MEMO") to Color(0xFF4CAF50),
                Pair(AppStrings.tr("B级 (40-69)", "B (40–69)"), "+10 MEMO") to Color(0xFF2196F3),
                Pair(AppStrings.tr("C级 (<40)", "C (<40)"), "+0 MEMO") to Color.Gray
            )
            
            resonanceGrades.forEach { (grade, color) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = color
                        ) {}
                        Text(
                            text = grade.first,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                    Text(
                        text = grade.second,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Medium))
            
            // S级特效提示
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFFD700).copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "✨", fontSize = 20.sp)
                    Column {
                        Text(
                            text = AppStrings.tr("灵魂共鸣！", "Soul Resonance!"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFD700)
                        )
                        Text(
                            text = AppStrings.tr("触发 S 级共鸣时会显示特效动画", "Special effects appear when S-grade resonance triggers"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFD700).copy(alpha = 0.8f)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            // 倍数说明
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = AppColors.PrimaryGradientStart.copy(alpha = 0.1f)
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = AppColors.PrimaryGradientStart,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = AppStrings.tr(
                            "最终积分 = (基础分 + 人格共鸣奖) × Tier倍数 × Sovereign加成 × 质押加成",
                            "Final points = (Base + Personality Bonus) × Tier multiplier × Sovereign bonus × Staking bonus"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
