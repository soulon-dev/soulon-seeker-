package com.soulon.app.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soulon.app.i18n.AppStrings
import com.soulon.app.staking.GuardianStakingManager
import com.soulon.app.staking.CertifiedGuardian
import com.soulon.app.staking.GuardianStakingStatus
import com.soulon.app.staking.StakingBonus
import com.soulon.app.sovereign.SovereignScoreManager
import com.soulon.app.sovereign.SovereignScoreManager.SovereignLevel
import com.soulon.app.sovereign.SovereignInfo
import com.soulon.app.ui.theme.*
import com.soulon.app.wallet.SolanaRpcClient
import kotlinx.coroutines.launch

/**
 * 质押仪表盘 - Solana Seeker S2 原生体验
 */
@Composable
fun StakingDashboard(
    walletAddress: String?,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onStakeToGuardian: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isLoading by remember { mutableStateOf(true) }
    var stakingStatus by remember { mutableStateOf<GuardianStakingStatus?>(null) }
    var stakingBonus by remember { mutableStateOf<StakingBonus?>(null) }
    var sovereignInfo by remember { mutableStateOf<SovereignInfo?>(null) }
    var certifiedGuardians by remember { mutableStateOf<List<CertifiedGuardian>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 初始化 Manager
    val rpcClient = remember { SolanaRpcClient() }
    val stakingManager = remember { GuardianStakingManager(context, rpcClient) }
    val sovereignManager = remember { SovereignScoreManager(context, rpcClient) }
    
    // 加载数据
    LaunchedEffect(walletAddress) {
        if (walletAddress != null) {
            isLoading = true
            errorMessage = null
            
            try {
                // 并行加载数据
                val statusResult = stakingManager.getStakingStatus(walletAddress)
                stakingStatus = statusResult
                stakingBonus = stakingManager.calculateStakingBonus(statusResult)
                
                sovereignInfo = sovereignManager.getSovereignInfo(walletAddress)
                certifiedGuardians = stakingManager.getCertifiedGuardians()
                
            } catch (e: Exception) {
                errorMessage = AppStrings.trf(
                    "加载失败: %s",
                    "Load failed: %s",
                    e.message
                )
            } finally {
                isLoading = false
            }
        }
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
                StakingHeader(onNavigateBack)
            }
            
            // 加载状态
            if (isLoading) {
                item {
                    LoadingCard()
                }
            } else if (errorMessage != null) {
                item {
                    ErrorCard(errorMessage!!) {
                        scope.launch {
                            walletAddress?.let {
                                isLoading = true
                                try {
                                    val statusResult = stakingManager.getStakingStatus(it)
                                    stakingStatus = statusResult
                                    stakingBonus = stakingManager.calculateStakingBonus(statusResult)
                                    sovereignInfo = sovereignManager.getSovereignInfo(it)
                                    errorMessage = null
                                } catch (e: Exception) {
                                    errorMessage = AppStrings.trf(
                                        "加载失败: %s",
                                        "Load failed: %s",
                                        e.message
                                    )
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    }
                }
            } else {
                // Sovereign Score 卡片
                item {
                    SovereignScoreCard(sovereignInfo)
                }
                
                // 质押状态卡片
                item {
                    StakingStatusCard(stakingStatus, stakingBonus)
                }
                
                // 加成收益卡片
                item {
                    BonusCard(stakingBonus)
                }
                
                // 认证 Guardian 列表
                item {
                    Text(
                        text = AppStrings.tr("认证 Guardian 节点", "Certified Guardian Nodes"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(top = AppSpacing.Medium)
                    )
                }
                
                items(certifiedGuardians) { guardian ->
                    GuardianCard(
                        guardian = guardian,
                        isSelected = stakingStatus?.guardianAddress == guardian.address,
                        onSelect = { onStakeToGuardian(guardian.address) }
                    )
                }
                
                // 底部说明
                item {
                    InfoCard()
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
private fun StakingHeader(onNavigateBack: () -> Unit) {
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
            text = AppStrings.tr("质押 & Guardian", "Staking & Guardian"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        
        // Seeker 徽章
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF14F195), Color(0xFF9945FF))
                    ),
                    shape = AppShapes.Tag
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = AppStrings.tr("Seeker S2", "Seeker S2"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
            ) {
                CircularProgressIndicator(
                    color = AppColors.PrimaryGradientStart,
                    strokeWidth = 3.dp
                )
                Text(
                    text = AppStrings.tr("正在加载质押数据...", "Loading staking data..."),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A1A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.XLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = AppColors.ErrorGradientStart,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.ErrorGradientStart
                ),
                shape = AppShapes.Button
            ) {
                Text(AppStrings.retry)
            }
        }
    }
}

/**
 * Sovereign Score 卡片
 */
@Composable
private fun SovereignScoreCard(info: SovereignInfo?) {
    val level = info?.level ?: SovereignLevel.BRONZE
    val score = info?.score ?: 0
    
    // 等级对应的颜色
    val levelColors = when (level) {
        SovereignLevel.BRONZE -> listOf(Color(0xFFCD7F32), Color(0xFFB87333))
        SovereignLevel.SILVER -> listOf(Color(0xFFC0C0C0), Color(0xFFA8A8A8))
        SovereignLevel.GOLD -> listOf(Color(0xFFFFD700), Color(0xFFDAA520))
        SovereignLevel.PLATINUM -> listOf(Color(0xFFE5E4E2), Color(0xFF9C9C9C))
        SovereignLevel.DIAMOND -> listOf(Color(0xFFB9F2FF), Color(0xFF89CFF0))
    }
    
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
                    Brush.linearGradient(colors = levelColors),
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
                            text = AppStrings.tr("Sovereign 等级", "Sovereign level"),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Black.copy(alpha = 0.7f)
                        )
                        Text(
                            text = level.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                    
                    // 等级图标
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                Color.White.copy(alpha = 0.2f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (level) {
                                SovereignLevel.DIAMOND -> Icons.Rounded.Diamond
                                SovereignLevel.PLATINUM -> Icons.Rounded.Verified
                                SovereignLevel.GOLD -> Icons.Rounded.Stars
                                SovereignLevel.SILVER -> Icons.Rounded.Star
                                else -> Icons.Rounded.StarBorder
                            },
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                // 分数和倍数
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = AppStrings.tr("积分", "Score"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black.copy(alpha = 0.6f)
                        )
                        Text(
                            text = score.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = AppStrings.tr("收益倍数", "Multiplier"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black.copy(alpha = 0.6f)
                        )
                        Text(
                            text = level.multiplier.toString() + "x",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
                
                // 进度条
                if (info?.pointsToNextLevel != null && info.pointsToNextLevel > 0 && info.nextLevel != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    
                    // 计算进度
                    val currentLevelMin = level.minScore
                    val nextLevelMin = info.nextLevel.minScore
                    val progressToNext = if (nextLevelMin > currentLevelMin) {
                        ((score - currentLevelMin).toFloat() / (nextLevelMin - currentLevelMin)).coerceIn(0f, 1f)
                    } else 0f
                    
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = AppStrings.tr("距离下一等级", "To next level"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black.copy(alpha = 0.6f)
                            )
                            Text(
                                text = AppStrings.trf(
                                    "%d 积分",
                                    "%d points",
                                    info.pointsToNextLevel
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                        
                        LinearProgressIndicator(
                            progress = { progressToNext },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(AppShapes.Tag),
                            color = Color.Black.copy(alpha = 0.6f),
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 质押状态卡片
 */
@Composable
private fun StakingStatusCard(
    status: GuardianStakingStatus?,
    bonus: StakingBonus?
) {
    val isStaking = status?.isStaking == true
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(
            containerColor = if (isStaking) Color(0xFF1A2A1A) else Color(0xFF1A1A24)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Icon(
                        imageVector = if (isStaking) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = if (isStaking) AppColors.SuccessGradientStart else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = AppStrings.tr("质押状态", "Staking status"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                // 状态标签
                Box(
                    modifier = Modifier
                        .background(
                            if (isStaking) AppColors.SuccessGradientStart.copy(alpha = 0.2f)
                            else Color.White.copy(alpha = 0.1f),
                            shape = AppShapes.Tag
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isStaking) AppStrings.tr("已质押", "Staked") else AppStrings.tr("未质押", "Not staked"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isStaking) AppColors.SuccessGradientStart else Color.White.copy(alpha = 0.5f)
                    )
                }
            }
            
            if (isStaking && status != null) {
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                // 质押金额
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StakingStatItem(
                        label = AppStrings.tr("质押金额", "Amount"),
                        value = formatStakedAmount(status.stakedAmount),
                        icon = Icons.Rounded.AccountBalance
                    )
                    
                    StakingStatItem(
                        label = AppStrings.tr("验证者", "Validator"),
                        value = if (status.isCertifiedGuardian) AppStrings.tr("认证 Guardian", "Certified Guardian") else AppStrings.tr("普通节点", "Standard"),
                        icon = Icons.Rounded.VerifiedUser,
                        valueColor = if (status.isCertifiedGuardian) 
                            AppColors.SuccessGradientStart else Color.White.copy(alpha = 0.7f)
                    )
                }
                
                // 验证者地址
                if (status.guardianAddress != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color.White.copy(alpha = 0.05f),
                                shape = AppShapes.SmallButton
                            )
                            .padding(AppSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = status.guardianAddress.take(8) + "..." + status.guardianAddress.takeLast(8),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                Text(
                    text = AppStrings.tr(
                        "质押 \$SKR 到认证 Guardian 节点可获得额外收益加成",
                        "Stake \$SKR to a certified Guardian node to earn extra bonuses"
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun StakingStatItem(
    label: String,
    value: String,
    icon: ImageVector,
    valueColor: Color = Color.White
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XXSmall)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

/**
 * 加成收益卡片
 */
@Composable
private fun BonusCard(bonus: StakingBonus?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2A))
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
                    imageVector = Icons.Rounded.TrendingUp,
                    contentDescription = null,
                    tint = AppColors.PrimaryGradientStart,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = AppStrings.tr("质押加成", "Staking bonus"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BonusItem(
                    label = AppStrings.tr("MEMO 加成", "MEMO bonus"),
                    value = "+${bonus?.extraMemoPercent ?: 0}%",
                    icon = Icons.Rounded.Stars,
                    color = AppColors.WarningGradientStart
                )
                
                BonusItem(
                    label = AppStrings.tr("手续费减免", "Fee discount"),
                    value = "${((bonus?.feeDiscount ?: 0f) * 100).toInt()}%",
                    icon = Icons.Rounded.Discount,
                    color = AppColors.SuccessGradientStart
                )
                
                BonusItem(
                    label = AppStrings.tr("总倍数", "Total multiplier"),
                    value = "${bonus?.getMemoMultiplier() ?: 1.0f}x",
                    icon = Icons.Rounded.Speed,
                    color = AppColors.PrimaryGradientStart
                )
            }
            
            // 解锁功能
            if (bonus != null && bonus.unlockFeatures.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                Text(
                    text = AppStrings.tr("已解锁功能", "Unlocked features"),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    bonus.unlockFeatures.forEach { feature ->
                        val (label, icon) = when (feature) {
                            "premium_skin" -> AppStrings.tr("高级皮肤", "Premium skins") to Icons.Rounded.Palette
                            "priority_queue" -> AppStrings.tr("优先队列", "Priority queue") to Icons.Rounded.Speed
                            "exclusive_airdrops" -> AppStrings.tr("专属空投", "Exclusive airdrops") to Icons.Rounded.CardGiftcard
                            else -> feature to Icons.Rounded.CheckCircle
                        }
                        
                        Box(
                            modifier = Modifier
                                .background(
                                    AppColors.PrimaryGradientStart.copy(alpha = 0.15f),
                                    shape = AppShapes.Tag
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = AppColors.PrimaryGradientStart,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.PrimaryGradientStart,
                                    fontWeight = FontWeight.Medium
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
private fun BonusItem(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color.copy(alpha = 0.15f), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

/**
 * Guardian 卡片
 */
@Composable
private fun GuardianCard(
    guardian: CertifiedGuardian,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF14F195), Color(0xFF9945FF))
                    ),
                    AppShapes.Card
                ) else Modifier
            )
            .clickable { onSelect() },
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF1A2A2A) else Color(0xFF1A1A24)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF14F195), Color(0xFF9945FF))
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = guardian.name.take(2),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // 信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
                ) {
                    Text(
                        text = guardian.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = AppStrings.tr("认证", "Verified"),
                        tint = Color(0xFF14F195),
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                Text(
                    text = guardian.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.XSmall))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
                ) {
                    Text(
                        text = "APY ${guardian.apy}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.SuccessGradientStart
                    )
                    
                    Text(
                        text = "+20% MEMO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.WarningGradientStart
                    )
                }
            }
            
            // 选中指示
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = AppStrings.tr("已选中", "Selected"),
                    tint = Color(0xFF14F195),
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = AppStrings.tr("选择", "Choose"),
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * 信息卡片
 */
@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = AppColors.SecondaryGradientStart,
                modifier = Modifier.size(24.dp)
            )
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
            ) {
                Text(
                    text = AppStrings.tr("关于 Guardian 质押", "About Guardian staking"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Text(
                    text = AppStrings.tr(
                        "质押 \$SKR 到官方认证的 Guardian 验证者节点，不仅能获得质押收益，还能在 Soulon 中获得额外的 MEMO 积分加成和专属功能。",
                        "Stake \$SKR to an officially certified Guardian validator to earn staking rewards, plus extra MEMO bonuses and exclusive features in Soulon."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    lineHeight = 20.sp
                )
            }
        }
    }
}

/**
 * 格式化质押金额
 */
private fun formatStakedAmount(lamports: Long): String {
    val skr = lamports / 1_000_000_000.0
    return when {
        skr >= 1_000_000 -> String.format("%.2fM SKR", skr / 1_000_000)
        skr >= 1_000 -> String.format("%.2fK SKR", skr / 1_000)
        else -> String.format("%.2f SKR", skr)
    }
}
