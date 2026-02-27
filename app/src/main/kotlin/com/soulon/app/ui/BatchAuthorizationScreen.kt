package com.soulon.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import com.soulon.app.ui.theme.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soulon.app.i18n.AppStrings

/**
 * 记忆存储确认屏幕
 * 
 * 在用户完成问卷后，上传记忆前显示
 * 告知用户记忆将被加密并永久存储
 */
@Composable
fun BatchAuthorizationScreen(
    totalMemoryCount: Int,
    onStartAuthorization: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onLearnMore: () -> Unit = {},  // 保留参数兼容性
    isProcessing: Boolean = false
) {
    Scaffold(
        containerColor = Color(0xFF0A0A0F),
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacing.Medium),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isProcessing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                            color = AppColors.PrimaryGradientStart
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Medium))
                        Text(
                            text = AppStrings.tr("正在准备上传...", "Preparing upload..."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Button(
                        onClick = onStartAuthorization,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        shape = AppShapes.Button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            AppColors.PrimaryGradientStart,
                                            AppColors.PrimaryGradientEnd
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.Small))
                                Text(
                                    text = AppStrings.tr("确认并开始上传", "Confirm & upload"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.Large)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.XLarge))

            Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(AppCorners.Large),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    AppColors.PrimaryGradientStart.copy(alpha = 0.2f),
                                    AppColors.PrimaryGradientEnd.copy(alpha = 0.2f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = AppColors.PrimaryGradientStart
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Large))

            Text(
                text = AppStrings.tr("确认上传加密记忆", "Confirm encrypted upload"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(AppSpacing.Small))

            Text(
                text = AppStrings.trf(
                    "您已完成 %d 道问卷，点击下方按钮开始加密上传",
                    "You completed %d questions. Tap below to start encrypted upload.",
                    totalMemoryCount
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(AppSpacing.XLarge))

            SecurityFeatureCard(
                icon = Icons.Rounded.Lock,
                title = AppStrings.tr("端到端加密", "End-to-end encryption"),
                description = AppStrings.tr("使用 AES-GCM-256 加密，密钥由您的设备安全存储", "Encrypted with AES-GCM-256; keys are securely stored on your device"),
                iconColor = AppColors.PrimaryGradientStart
            )

            Spacer(modifier = Modifier.height(AppSpacing.Medium))

            SecurityFeatureCard(
                icon = Icons.Rounded.Cloud,
                title = AppStrings.tr("永久存储", "Permanent storage"),
                description = AppStrings.tr("加密数据上传至 Arweave 网络，永久保存不丢失", "Encrypted data is uploaded to Arweave for permanent storage"),
                iconColor = AppColors.SecondaryGradientStart
            )

            Spacer(modifier = Modifier.height(AppSpacing.Medium))

            SecurityFeatureCard(
                icon = Icons.Rounded.Verified,
                title = AppStrings.tr("自动签名", "Auto signing"),
                description = AppStrings.tr("使用已授权的会话密钥自动签名，无需额外操作", "Uses an authorized session key to sign automatically"),
                iconColor = Color(0xFF4CAF50)
            )

            Spacer(modifier = Modifier.height(120.dp))
        }
    }
}

/**
 * 安全特性卡片
 */
@Composable
private fun SecurityFeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    iconColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color.White.copy(alpha = 0.05f),
                shape = AppShapes.Card
            )
            .padding(AppSpacing.Medium),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(AppCorners.Small),
            color = iconColor.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = iconColor
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.Medium))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * 上传进度屏幕
 * 
 * 显示记忆加密上传的进度
 */
@Composable
fun AuthorizationProgressScreen(
    currentStep: Int,
    totalSteps: Int,
    currentOperation: String,
    @Suppress("UNUSED_PARAMETER") isWaitingForWallet: Boolean = false  // 保留参数兼容性
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val progress = if (totalSteps > 0) currentStep.toFloat() / totalSteps.toFloat() else 0f
    val percentComplete = (progress * 100).toInt()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Large),
            shape = AppShapes.LargeCard,
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A24)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.XLarge),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 动画图标
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(AppCorners.Large),
                    color = AppColors.PrimaryGradientStart.copy(alpha = 0.15f * pulseAlpha)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = AppColors.PrimaryGradientStart
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                // 标题
                Text(
                    text = AppStrings.tr("正在加密上传", "Encrypting & uploading"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                
                // 进度文字
                Text(
                    text = AppStrings.trf(
                        "已完成 %d / %d 条记忆",
                        "Completed %d / %d memories",
                        currentStep,
                        totalSteps
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.PrimaryGradientStart,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.XLarge))
                
                // 进度条
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .background(
                                Color.White.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(6.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            AppColors.PrimaryGradientStart,
                                            AppColors.PrimaryGradientEnd
                                        )
                                    ),
                                    shape = RoundedCornerShape(6.dp)
                                )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(AppSpacing.Small))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = percentComplete.toString() + "%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text = AppStrings.tr("加密存储中", "Encrypting storage"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.XLarge))
                
                // 当前操作
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color.White.copy(alpha = 0.05f),
                            shape = AppShapes.Card
                        )
                        .padding(AppSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.PrimaryGradientStart
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Medium))
                    Column {
                        Text(
                            text = AppStrings.tr("当前操作", "Current step"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = currentOperation,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Large))
                
                // 安全提示
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.XSmall))
                    Text(
                        text = AppStrings.tr("数据已加密，安全上传中", "Data encrypted and uploading securely"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
