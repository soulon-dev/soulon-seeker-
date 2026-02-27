package com.soulon.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import com.soulon.app.R
import com.soulon.app.i18n.AppStrings
import com.soulon.app.ui.theme.*

/**
 * 钱包连接引导页面 - 深色现代化设计，带启动动画
 */
@Composable
fun WalletOnboardingScreen(
    onConnect: () -> Unit,
    isConnecting: Boolean = false,
    errorMessage: String? = null
) {
    val userError = remember(errorMessage) {
        errorMessage?.let { ErrorHandler.parseErrorMessage(it) }
    }
    
    // Logo 动画状态
    var animationStarted by remember { mutableStateOf(false) }
    
    // 启动动画
    LaunchedEffect(Unit) {
        animationStarted = true
    }
    
    // 使用 spring 动画实现更流畅的 60fps+ 动画效果
    // Logo 缩放动画：从全屏大小缩小到目标大小
    val logoScale by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 3f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logoScale"
    )
    
    // Logo 位置动画：从屏幕中心移动到顶部
    val logoOffsetY by animateFloatAsState(
        targetValue = if (animationStarted) 0f else 200f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logoOffsetY"
    )
    
    // 内容淡入动画 - 使用更流畅的缓动曲线
    val contentAlpha by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(
            durationMillis = 500,
            delayMillis = 300,
            easing = EaseOutCubic
        ),
        label = "contentAlpha"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.XLarge)
                .padding(top = AppSpacing.XHuge, bottom = AppSpacing.XXLarge),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部 Logo - 带缩放动画
            Box(
                modifier = Modifier
                    .offset(y = logoOffsetY.dp)
                    .scale(logoScale),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_splash_logo),
                    contentDescription = AppStrings.tr("Soulon Logo", "Soulon Logo"),
                    modifier = Modifier.size(120.dp),
                    contentScale = ContentScale.Fit
                )
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.XXLarge))
            
            // 以下内容带淡入动画
            Column(
                modifier = Modifier.alpha(contentAlpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                Text(
                    text = AppStrings.welcomeTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                
                // 副标题
                Text(
                    text = AppStrings.welcomeSubtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.7f)
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.XXLarge))
                
                // 功能卡片
                Column(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
                ) {
                    ModernFeatureCard(
                        icon = Icons.Rounded.Shield,
                        title = AppStrings.featureEncryption,
                        description = AppStrings.featureEncryptionDesc,
                        color = AppColors.PrimaryGradientStart
                    )
                    
                    ModernFeatureCard(
                        icon = Icons.Rounded.CloudSync,
                        title = AppStrings.featureStorage,
                        description = AppStrings.featureStorageDesc,
                        color = AppColors.SecondaryGradientStart
                    )
                    
                    ModernFeatureCard(
                        icon = Icons.Rounded.Fingerprint,
                        title = AppStrings.featureOwnership,
                        description = AppStrings.featureOwnershipDesc,
                        color = AppColors.SuccessGradientStart
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 底部区域也带淡入动画
            Column(
                modifier = Modifier.alpha(contentAlpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 错误提示
                if (userError != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = AppSpacing.Medium),
                        shape = AppShapes.Card,
                        color = Color(0xFF3D1A1A)
                    ) {
                        Row(
                            modifier = Modifier.padding(AppSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(AppIconSizes.Medium)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = userError.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFFF6B6B)
                                )
                                if (userError.message.isNotEmpty()) {
                                    Text(
                                        text = userError.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 连接按钮 - 渐变背景
                Button(
                    onClick = onConnect,
                    enabled = !isConnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = AppShapes.Button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (isConnecting)
                                    Brush.linearGradient(
                                        colors = listOf(
                                            AppColors.PrimaryGradientStart.copy(alpha = 0.5f),
                                            AppColors.PrimaryGradientEnd.copy(alpha = 0.5f)
                                        )
                                    )
                                else
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
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(AppIconSizes.Medium),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.Small))
                                Text(
                                    text = AppStrings.connecting,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.AccountBalanceWallet,
                                    contentDescription = null,
                                    modifier = Modifier.size(AppIconSizes.Medium),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.Small))
                                Text(
                                    text = AppStrings.connectWallet,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                
                // 底部提示
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                Text(
                    text = AppStrings.supportedWallets,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 现代化功能卡片 - 深色玻璃效果
 */
@Composable
private fun ModernFeatureCard(
    icon: ImageVector,
    title: String,
    description: String,
    color: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = Color.White.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
        ) {
            // 图标容器
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(AppCorners.Medium),
                color = color.copy(alpha = 0.15f)
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
            
            // 文字内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
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
}

// 保留旧的 FeatureCard 以保持兼容性
@Composable
private fun FeatureCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    ModernFeatureCard(
        icon = icon,
        title = title,
        description = description,
        color = AppColors.PrimaryGradientStart
    )
}
