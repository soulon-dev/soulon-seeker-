package com.soulon.app.did

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soulon.app.i18n.AppStrings
import com.soulon.app.rewards.PersonaData
import kotlinx.coroutines.launch

/**
 * DID 管理页面
 * 
 * 高级功能：仅订阅用户或高等级用户可用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DIDManagementScreen(
    didManager: DIDManager,
    memoryMergeService: MemoryMergeService,
    currentWallet: String?,
    isSubscribed: Boolean,
    onBack: () -> Unit,
    onNavigateToKYC: () -> Unit,
    onNavigateToSubscription: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    
    val coroutineScope = rememberCoroutineScope()
    
    // 状态
    val currentDID by didManager.currentDID.collectAsState()
    val mergeState by memoryMergeService.mergeState.collectAsState()
    var walletsOverview by remember { mutableStateOf<List<MemoryMergeService.WalletMemoryInfo>>(emptyList()) }
    var showLinkWalletDialog by remember { mutableStateOf(false) }
    var showMergeConfirmDialog by remember { mutableStateOf(false) }
    var mergeResult by remember { mutableStateOf<MemoryMergeService.MergeResult?>(null) }
    
    // 检查权限 - 仅订阅用户可用
    val hasPermission = didManager.hasPermission(isSubscribed)
    
    // 加载钱包概况
    LaunchedEffect(currentDID) {
        if (currentDID != null) {
            walletsOverview = memoryMergeService.getWalletsMemoryOverview()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            TopAppBar(
                title = {
                    Text(
                        text = AppStrings.tr("身份管理", "Identity"),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = AppStrings.back,
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
            
            if (!hasPermission) {
                // 无权限提示 - 仅订阅用户可用
                SubscriptionRequiredView(
                    onSubscribe = onNavigateToSubscription
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // DID 状态卡片
                    item {
                        DIDStatusCard(
                            did = currentDID,
                            onCreateDID = onNavigateToKYC
                        )
                    }
                    
                    if (currentDID != null) {
                        // 绑定的钱包
                        item {
                            LinkedWalletsSection(
                                wallets = walletsOverview,
                                primaryWallet = currentDID?.primaryWallet,
                                currentWallet = currentWallet,
                                maxWallets = DIDManager.MAX_LINKED_WALLETS,
                                onLinkWallet = { showLinkWalletDialog = true },
                                onUnlinkWallet = { wallet ->
                                    coroutineScope.launch {
                                        didManager.unlinkWallet(wallet)
                                        walletsOverview = memoryMergeService.getWalletsMemoryOverview()
                                    }
                                }
                            )
                        }
                        
                        // 记忆合并
                        item {
                            MemoryMergeSection(
                                mergeState = mergeState,
                                walletsCount = walletsOverview.size,
                                totalMemories = walletsOverview.sumOf { it.memoryCount },
                                onStartMerge = { showMergeConfirmDialog = true },
                                onViewResult = mergeResult?.let { { } }
                            )
                        }
                        
                        // 合并历史
                        item {
                            MergeHistoryCard(
                                lastMergedAt = currentDID?.lastMergedAt,
                                totalMerged = currentDID?.totalMemoriesMerged ?: 0
                            )
                        }
                    }
                    
                    // 说明
                    item {
                        InfoCard()
                    }
                }
            }
        }
        
        // 绑定钱包对话框
        if (showLinkWalletDialog) {
            LinkWalletDialog(
                onDismiss = { showLinkWalletDialog = false },
                onConfirm = { walletAddress, signature ->
                    coroutineScope.launch {
                        val result = didManager.linkWallet(walletAddress, signature)
                        if (result.isSuccess) {
                            walletsOverview = memoryMergeService.getWalletsMemoryOverview()
                        }
                        showLinkWalletDialog = false
                    }
                }
            )
        }
        
        // 合并确认对话框
        if (showMergeConfirmDialog) {
            MergeConfirmDialog(
                walletsCount = walletsOverview.size,
                totalMemories = walletsOverview.sumOf { it.memoryCount },
                onDismiss = { showMergeConfirmDialog = false },
                onConfirm = {
                    showMergeConfirmDialog = false
                    coroutineScope.launch {
                        currentWallet?.let { wallet ->
                            val result = memoryMergeService.mergeAllMemories(wallet)
                            mergeResult = result
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun SubscriptionRequiredView(
    onSubscribe: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = Color(0xFFFFD700),
            modifier = Modifier.size(80.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = AppStrings.tr("订阅专属功能", "Subscriber-only feature"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = AppStrings.tr(
                "身份管理是订阅会员专属功能\n开通会员后即可使用",
                "Identity management is for subscribers.\nSubscribe to use this feature."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 功能介绍
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.05f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeatureItem(text = AppStrings.tr("绑定多个钱包到同一身份", "Link multiple wallets to one identity"))
                FeatureItem(text = AppStrings.tr("跨钱包记忆合并", "Merge memories across wallets"))
                FeatureItem(text = AppStrings.tr("统一人格画像分析", "Unified persona analysis"))
                FeatureItem(text = AppStrings.tr("数据永不丢失", "Data never lost"))
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onSubscribe,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFD700)
            ),
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = AppStrings.tr("开通会员", "Subscribe"),
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun FeatureItem(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = Color(0xFF00D4AA),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun DIDStatusCard(
    did: DIDManager.DIDIdentity?,
    onCreateDID: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.05f)
    ) {
        if (did != null) {
            // 已有 DID
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF00D4AA), Color(0xFF00B4D8))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = AppStrings.tr("DID 身份", "DID Identity"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = did.did,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF00D4AA).copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = AppStrings.tr("已验证", "Verified"),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF00D4AA)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 统计信息
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        icon = Icons.Rounded.AccountBalanceWallet,
                        value = "${did.linkedWallets.size}",
                        label = AppStrings.tr("绑定钱包", "Wallets")
                    )
                    StatItem(
                        icon = Icons.Rounded.MergeType,
                        value = "${did.totalMemoriesMerged}",
                        label = AppStrings.tr("已合并记忆", "Merged")
                    )
                }
            }
        } else {
            // 未创建 DID
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCreateDID)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.PersonAdd,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = AppStrings.tr("创建 DID 身份", "Create DID"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Text(
                    text = AppStrings.tr("完成 KYC 认证后自动创建", "Created automatically after KYC"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onCreateDID,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6366F1)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(AppStrings.tr("开始 KYC 认证", "Start KYC"))
                }
            }
        }
    }
}

@Composable
private fun StatItem(
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
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

@Composable
private fun LinkedWalletsSection(
    wallets: List<MemoryMergeService.WalletMemoryInfo>,
    primaryWallet: String?,
    currentWallet: String?,
    maxWallets: Int,
    onLinkWallet: () -> Unit,
    onUnlinkWallet: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.05f)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AppStrings.tr("绑定的钱包", "Linked wallets"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Text(
                    text = wallets.size.toString() + "/" + maxWallets,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            wallets.forEach { wallet ->
                WalletItem(
                    wallet = wallet,
                    isPrimary = wallet.walletAddress == primaryWallet,
                    isCurrent = wallet.walletAddress == currentWallet,
                    onUnlink = { onUnlinkWallet(wallet.walletAddress) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            if (wallets.size < maxWallets) {
                OutlinedButton(
                    onClick = onLinkWallet,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White.copy(alpha = 0.7f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.2f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(AppStrings.tr("绑定新钱包", "Link a new wallet"))
                }
            }
        }
    }
}

@Composable
private fun WalletItem(
    wallet: MemoryMergeService.WalletMemoryInfo,
    isPrimary: Boolean,
    isCurrent: Boolean,
    onUnlink: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isCurrent) Color(0xFF6366F1).copy(alpha = 0.2f) 
                else Color.White.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountBalanceWallet,
                contentDescription = null,
                tint = if (isPrimary) Color(0xFFFFD700) else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = wallet.walletAddress.take(8) + "..." + wallet.walletAddress.takeLast(6),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    
                    if (isPrimary) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFFFD700).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = AppStrings.tr("主", "Primary"),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFD700),
                                fontSize = 10.sp
                            )
                        }
                    }
                    
                    if (isCurrent) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF6366F1).copy(alpha = 0.3f)
                        ) {
                            Text(
                                text = AppStrings.tr("当前", "Current"),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF6366F1),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
                
                Text(
                    text = AppStrings.trf(
                        "%d 条记忆",
                        "%d memories",
                        wallet.memoryCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
            
            if (!isPrimary) {
                IconButton(
                    onClick = onUnlink,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LinkOff,
                        contentDescription = AppStrings.tr("解绑", "Unlink"),
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryMergeSection(
    mergeState: MemoryMergeService.MergeState,
    walletsCount: Int,
    totalMemories: Int,
    onStartMerge: () -> Unit,
    onViewResult: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.05f)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MergeType,
                    contentDescription = null,
                    tint = Color(0xFF00D4AA),
                    modifier = Modifier.size(24.dp)
                )
                
                Text(
                    text = AppStrings.tr("记忆合并", "Memory merge"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = AppStrings.trf(
                    "将 %1\$d 个钱包的 %2\$d 条记忆合并到统一身份",
                    "Merge %2\$d memories from %1\$d wallets into one identity",
                    walletsCount,
                    totalMemories
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (mergeState.isMerging) {
                // 合并进度
                Column {
                    Text(
                        text = when (mergeState.currentStep) {
                            MemoryMergeService.MergeStep.SCANNING_WALLETS -> AppStrings.tr("扫描钱包...", "Scanning wallets...")
                            MemoryMergeService.MergeStep.DOWNLOADING_MEMORIES -> AppStrings.tr("下载记忆...", "Downloading memories...")
                            MemoryMergeService.MergeStep.DECRYPTING -> AppStrings.tr("解密中...", "Decrypting...")
                            MemoryMergeService.MergeStep.DEDUPLICATING -> AppStrings.tr("去重中...", "Deduplicating...")
                            MemoryMergeService.MergeStep.RE_ENCRYPTING -> AppStrings.tr("重新加密...", "Re-encrypting...")
                            MemoryMergeService.MergeStep.ANALYZING_PERSONA -> AppStrings.tr("分析人格...", "Analyzing persona...")
                            MemoryMergeService.MergeStep.SAVING -> AppStrings.tr("保存中...", "Saving...")
                            else -> AppStrings.tr("处理中...", "Processing...")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LinearProgressIndicator(
                        progress = { mergeState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF00D4AA),
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = AppStrings.trf(
                            "已处理 %d / %d 条记忆",
                            "Processed %d / %d memories",
                            mergeState.memoriesMerged,
                            mergeState.memoriesFound
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            } else {
                Button(
                    onClick = onStartMerge,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = walletsCount >= 2,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00D4AA),
                        disabledContainerColor = Color.White.copy(alpha = 0.1f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MergeType,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (walletsCount >= 2)
                            AppStrings.tr("开始合并", "Start merge")
                        else
                            AppStrings.tr("至少需要 2 个钱包", "At least 2 wallets required")
                    )
                }
            }
        }
    }
}

@Composable
private fun MergeHistoryCard(
    lastMergedAt: Long?,
    totalMerged: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.05f)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = AppStrings.tr("合并历史", "Merge history"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = AppStrings.tr("最后合并", "Last merge"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = if (lastMergedAt != null) {
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(lastMergedAt))
                        } else {
                            AppStrings.tr("从未合并", "Never")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = AppStrings.tr("累计合并", "Total merged"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = AppStrings.trf(
                            "%d 条记忆",
                            "%d memories",
                            totalMerged
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF6366F1).copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = Color(0xFF6366F1),
                modifier = Modifier.size(20.dp)
            )
            
            Column {
                Text(
                    text = AppStrings.tr("关于 DID 身份", "About DID"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6366F1)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = AppStrings.tr(
                        "DID (去中心化身份) 允许您将多个钱包绑定到同一身份，实现跨钱包的记忆合并和人格画像统一。合并后的数据使用您的主钱包密钥加密，确保安全性。",
                        "A DID (Decentralized Identity) lets you link multiple wallets to one identity, enabling cross-wallet memory merging and unified persona analysis. Merged data is encrypted with your primary wallet key."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun LinkWalletDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, ByteArray) -> Unit
) {
    var walletAddress by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A24),
        title = {
            Text(
                text = AppStrings.tr("绑定新钱包", "Link a new wallet"),
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column {
                Text(
                    text = AppStrings.tr("请使用新钱包登录并授权绑定", "Please sign in with the new wallet and authorize linking"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = walletAddress,
                    onValueChange = { walletAddress = it },
                    label = { Text(AppStrings.tr("钱包地址", "Wallet address")) },
                    placeholder = { Text(AppStrings.tr("输入钱包地址", "Enter wallet address")) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = Color(0xFF6366F1),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (walletAddress.isNotBlank()) {
                        onConfirm(walletAddress, "signature".toByteArray())
                    }
                },
                enabled = walletAddress.isNotBlank()
            ) {
                Text(AppStrings.tr("确认绑定", "Confirm"), color = Color(0xFF6366F1))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.cancel, color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}

@Composable
private fun MergeConfirmDialog(
    walletsCount: Int,
    totalMemories: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A24),
        icon = {
            Icon(
                imageVector = Icons.Rounded.MergeType,
                contentDescription = null,
                tint = Color(0xFF00D4AA),
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = AppStrings.tr("确认合并记忆", "Confirm merge"),
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column {
                Text(
                    text = AppStrings.trf(
                        "将合并 %1\$d 个钱包的 %2\$d 条记忆",
                        "This will merge %2\$d memories from %1\$d wallets",
                        walletsCount,
                        totalMemories
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = AppStrings.tr(
                        "• 自动去除重复记忆\n• 重新分析人格画像\n• 数据将统一加密存储",
                        "• Automatically remove duplicates\n• Re-analyze persona profile\n• Encrypt and store under one identity"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00D4AA)
                )
            ) {
                Text(AppStrings.tr("开始合并", "Start merge"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.cancel, color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}
