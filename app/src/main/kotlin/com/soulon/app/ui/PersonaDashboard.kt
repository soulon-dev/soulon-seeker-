package com.soulon.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import com.soulon.app.i18n.AppStrings
import com.soulon.app.persona.PersonaRadarChart
import com.soulon.app.persona.PersonaSummaryCard
import com.soulon.app.config.RemoteConfigManager
import com.soulon.app.rewards.PersonaEvidenceV2
import com.soulon.app.rewards.PersonaProfileV2
import com.soulon.app.rewards.PersonaTrait
import com.soulon.app.rewards.UserProfile
import com.soulon.app.rewards.UserLevelManager
import com.soulon.app.ui.theme.*

/**
 * 人格仪表盘 - 现代化设计
 */
@Composable
fun PersonaDashboard(
    userProfile: UserProfile?,
    modifier: Modifier = Modifier,
    onNavigateToChat: () -> Unit = {},
    onNavigateToMemories: () -> Unit = {}
) {
    if (userProfile == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = AppColors.PrimaryGradientStart,
                strokeWidth = 3.dp
            )
        }
        return
    }
    
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F)),
        contentPadding = PaddingValues(AppSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)
    ) {
        // 顶部卡片：积分和等级
        item {
            MemoTierCard(userProfile)
        }
        
        // 快速操作按钮
        item {
            QuickActionButtons(
                onChat = onNavigateToChat,
                onMemories = onNavigateToMemories
            )
        }
        
        // 人格分析卡片
        if (userProfile.personaData != null || userProfile.personaProfileV2 != null) {
            item {
                PersonaCard(userProfile)
            }
        }
        
        // 统计卡片
        item {
            StatsCard(userProfile)
        }
    }
}

/**
 * V1 $MEMO & Tier 卡片 - 渐变背景设计
 * 
 * 显示格式：[徽章] [Tier 名称] | [MEMO 余额] | [当前倍数]
 * V1 新增：Sovereign Ratio 显示
 */
@Composable
fun MemoTierCard(userProfile: UserProfile) {
    // 获取下一等级的 Sovereign 要求
    val nextTierSovereignReq = when (userProfile.currentTier) {
        1 -> 0.2f
        2 -> 0.4f
        3 -> 0.6f
        4 -> 0.8f
        else -> 1.0f
    }
    
    // 检查是否因 Sovereign Ratio 锁定
    val isLockedBySovereign = userProfile.isLevelLockedBySovereign()
    
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
                        colors = if (isLockedBySovereign) {
                            listOf(Color(0xFF5D3A3A), Color(0xFF3D2020))
                        } else {
                            listOf(AppColors.PrimaryGradientStart, AppColors.PrimaryGradientEnd)
                        }
                    )
                )
                .padding(AppSpacing.XLarge)
        ) {
            Column {
                // 顶部：Tier 等级 + 锁定状态
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                    ) {
                        // Tier 图标容器
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(AppCorners.Medium),
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = getDashboardTierIcon(userProfile.currentTier),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(AppIconSizes.Large)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = userProfile.getTierName(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = AppStrings.tr("等级", "Tier") + " " + userProfile.currentTier + "/5",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                    
                    // 积分倍数标签
                    Column(horizontalAlignment = Alignment.End) {
                        Surface(
                            shape = AppShapes.Tag,
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = userProfile.getTierMultiplier().toString() + "x",
                                modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.XSmall),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        
                        // 锁定状态标签
                        if (isLockedBySovereign) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFFF5722).copy(alpha = 0.3f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = Color(0xFFFF5722),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = AppStrings.tr("已锁定", "Locked"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFFF5722)
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                // V1 新增：Sovereign Ratio 显示
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = AppStrings.tr("主权比率", "Sovereign Ratio"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        Text(
                            text = "${(userProfile.sovereignRatio * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (userProfile.sovereignRatio >= nextTierSovereignReq) {
                                Color(0xFF4CAF50)
                            } else {
                                Color(0xFFFF9800)
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                // 分隔线
                HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
                
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                // $MEMO 余额
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = AppStrings.tr("\$MEMO 余额", "\$MEMO balance"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.XXSmall))
                        Text(
                            text = formatMemoDisplay(userProfile.memoBalance),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = AppStrings.tr("累计收入", "Total earned"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text = formatMemoDisplay(userProfile.totalMemoEarned),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * 格式化 MEMO 显示（大数字简写）
 */
private fun formatMemoDisplay(value: Int): String {
    return when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 10_000 -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

/**
 * 人格分析卡片
 */
@Composable
private fun PersonaCard(userProfile: UserProfile) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .modernCardShadow(AppElevations.Medium, AppShapes.LargeCard),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.XLarge)) {
            // 标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(AppCorners.Medium),
                    color = AppColors.PrimaryGradientEnd.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Psychology,
                            contentDescription = null,
                            tint = AppColors.PrimaryGradientEnd,
                            modifier = Modifier.size(AppIconSizes.Large)
                        )
                    }
                }
                Text(
                    text = AppStrings.tr("我的数字孪生", "My Digital Twin"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            val personaProfileV2 = userProfile.personaProfileV2
            if (personaProfileV2 != null) {
                val data = personaProfileV2.toLegacyPersonaData()
                PersonaRadarChart(personaProfile = personaProfileV2, showLabels = true)
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                PersonaSummaryCard(
                    personaData = data,
                    syncRate = userProfile.personaSyncRate ?: 0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                PersonaEvidenceSection(personaProfileV2)
            } else {
                userProfile.personaData?.let { data ->
                    PersonaRadarChart(personaData = data, showLabels = true)
                    Spacer(modifier = Modifier.height(AppSpacing.Large))
                    PersonaSummaryCard(
                        personaData = data,
                        syncRate = userProfile.personaSyncRate ?: 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonaEvidenceSection(profile: PersonaProfileV2) {
    val context = LocalContext.current
    val remoteConfig = remember { RemoteConfigManager.getInstance(context) }
    val enabled = remoteConfig.getBoolean("persona.evidence.enabled", true)
    if (!enabled) return

    val items = profile.evidence.sortedByDescending { it.timestamp }.take(5)
    if (items.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        Text(
            text = AppStrings.tr("关键证据", "Key evidence"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        items.forEach { ev ->
            EvidenceItem(ev)
        }
    }
}

@Composable
private fun EvidenceItem(evidence: PersonaEvidenceV2) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppCorners.Medium),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = traitLabel(evidence.trait),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = AppStrings.trf(
                        "权重 %.1f",
                        "Weight %.1f",
                        (evidence.weight * 10).toInt() / 10f
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.XSmall))
            Text(
                text = evidence.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun traitLabel(trait: PersonaTrait): String {
    return when (trait) {
        PersonaTrait.OPENNESS -> AppStrings.tr("开放性", "Openness")
        PersonaTrait.CONSCIENTIOUSNESS -> AppStrings.tr("尽责性", "Conscientiousness")
        PersonaTrait.EXTRAVERSION -> AppStrings.tr("外向性", "Extraversion")
        PersonaTrait.AGREEABLENESS -> AppStrings.tr("宜人性", "Agreeableness")
        PersonaTrait.NEUROTICISM -> AppStrings.tr("神经质", "Neuroticism")
    }
}

/**
 * 快速操作按钮
 */
@Composable
fun QuickActionButtons(
    onChat: () -> Unit,
    onMemories: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
    ) {
        // AI 对话按钮
        Button(
            onClick = onChat,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = AppShapes.Button,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.PrimaryGradientStart
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = AppElevations.Small
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Forum,
                contentDescription = null,
                modifier = Modifier.size(AppIconSizes.Small)
            )
            Spacer(modifier = Modifier.width(AppSpacing.XSmall))
            Text(AppStrings.tr("AI 对话", "AI Chat"), fontWeight = FontWeight.SemiBold)
        }
        
        // 记忆管理按钮
        OutlinedButton(
            onClick = onMemories,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = AppShapes.Button,
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                AppColors.PrimaryGradientStart.copy(alpha = 0.5f)
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AppColors.PrimaryGradientStart
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(AppIconSizes.Small)
            )
            Spacer(modifier = Modifier.width(AppSpacing.XSmall))
            Text(AppStrings.tr("记忆", "Memories"), fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 统计卡片 - 现代化横向布局
 */
@Composable
fun StatsCard(userProfile: UserProfile) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .modernCardShadow(AppElevations.Medium, AppShapes.LargeCard),
        shape = AppShapes.LargeCard,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.XLarge)) {
            // 标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(AppCorners.Small),
                    color = AppColors.SecondaryGradientStart.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.BarChart,
                            contentDescription = null,
                            modifier = Modifier.size(AppIconSizes.Medium),
                            tint = AppColors.SecondaryGradientStart
                        )
                    }
                }
                Text(
                    text = AppStrings.tr("统计数据", "Stats"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            // 统计项横向排列
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    icon = Icons.Rounded.Psychology,
                    label = AppStrings.tr("AI 推理", "AI inferences"),
                    value = "${userProfile.totalInferences}",
                    color = AppColors.PrimaryGradientStart
                )
                StatItem(
                    icon = Icons.Rounded.Token,
                    label = "Token",
                    value = "${userProfile.totalTokensGenerated}",
                    color = AppColors.SecondaryGradientStart
                )
                StatItem(
                    icon = Icons.Rounded.Security,
                    label = "Sovereign",
                    value = "${(userProfile.sovereignRatio * 100).toInt()}%",
                    color = AppColors.SuccessGradientStart
                )
            }
        }
    }
}

/**
 * 统计项 - 垂直居中布局
 */
@Composable
fun StatItem(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color = AppColors.PrimaryGradientStart
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(AppCorners.Medium),
            color = color.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(AppIconSizes.Medium),
                    tint = color
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.XSmall))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 获取 Tier 图标（PersonaDashboard 专用）
 */
private fun getDashboardTierIcon(tier: Int): ImageVector {
    return when (tier) {
        1 -> Icons.Rounded.Shield
        2 -> Icons.Rounded.Star
        3 -> Icons.Rounded.Diamond
        4 -> Icons.Rounded.Verified
        5 -> Icons.Rounded.EmojiEvents
        else -> Icons.Rounded.Shield
    }
}

/**
 * 获取 Tier 颜色（PersonaDashboard 专用）
 */
private fun getDashboardTierColor(tier: Int): Color {
    return when (tier) {
        1 -> Color(0xFFCD7F32) // Bronze
        2 -> Color(0xFFC0C0C0) // Silver
        3 -> Color(0xFFFFD700) // Gold
        4 -> Color(0xFFE5E4E2) // Platinum
        5 -> Color(0xFFB9F2FF) // Diamond
        else -> Color.Gray
    }
}
