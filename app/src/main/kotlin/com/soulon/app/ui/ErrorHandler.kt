package com.soulon.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import timber.log.Timber
import com.soulon.app.ui.theme.*
import com.soulon.app.i18n.AppStrings

/**
 * 错误处理工具 - 现代化设计
 */
object ErrorHandler {
    
    enum class ErrorType {
        NETWORK, WALLET, AUTH, STORAGE, ENCRYPTION, TIMEOUT, CANCELLED, UNKNOWN
    }
    
    data class UserFriendlyError(
        val type: ErrorType,
        val title: String,
        val message: String,
        val icon: ImageVector,
        val canRetry: Boolean = true
    )
    
    fun parseError(e: Throwable?): UserFriendlyError {
        val rawMessage = e?.message?.trim().orEmpty()
        val errorMessage = rawMessage.lowercase()
        Timber.e(e, "原始错误: ${e?.message}")
        
        return when {
            errorMessage.contains("cancel") || 
            errorMessage.contains("decline") ||
            errorMessage.contains("用户取消") -> {
                UserFriendlyError(
                    type = ErrorType.CANCELLED,
                    title = AppStrings.tr("操作已取消", "Cancelled"),
                    message = AppStrings.tr("您已取消此操作，可以随时重试", "You canceled this action. You can retry anytime."),
                    icon = Icons.Rounded.Close,
                    canRetry = true
                )
            }
            
            errorMessage.contains("network") ||
            errorMessage.contains("connect") ||
            errorMessage.contains("timeout") ||
            errorMessage.contains("socket") ||
            errorMessage.contains("websocket") ||
            errorMessage.contains("internet") ||
            errorMessage.contains("host") -> {
                UserFriendlyError(
                    type = ErrorType.NETWORK,
                    title = AppStrings.tr("网络连接失败", "Network error"),
                    message = AppStrings.tr("请检查网络连接后重试", "Please check your connection and retry."),
                    icon = Icons.Rounded.WifiOff,
                    canRetry = true
                )
            }
            
            errorMessage.contains("no wallet") ||
            errorMessage.contains("wallet not found") ||
            errorMessage.contains("未找到钱包") -> {
                UserFriendlyError(
                    type = ErrorType.WALLET,
                    title = AppStrings.tr("未检测到钱包", "No wallet detected"),
                    message = AppStrings.tr("请先安装 Phantom 或 Solflare 钱包", "Please install Phantom or Solflare."),
                    icon = Icons.Rounded.AccountBalanceWallet,
                    canRetry = true
                )
            }
            
            errorMessage.contains("wallet") ||
            errorMessage.contains("钱包") -> {
                UserFriendlyError(
                    type = ErrorType.WALLET,
                    title = AppStrings.tr("钱包连接失败", "Wallet connection failed"),
                    message = AppStrings.tr("请确保钱包应用已打开并重试", "Please make sure your wallet app is open and retry."),
                    icon = Icons.Rounded.AccountBalanceWallet,
                    canRetry = true
                )
            }
            
            errorMessage.contains("biometric") ||
            errorMessage.contains("fingerprint") ||
            errorMessage.contains("face") ||
            errorMessage.contains("usernotauthenticatedexception") ||
            errorMessage.contains("biometricprompt") -> {
                UserFriendlyError(
                    type = ErrorType.AUTH,
                    title = AppStrings.tr("身份验证失败", "Authentication failed"),
                    message = AppStrings.tr("请使用指纹或面部识别重新验证", "Please verify again with biometrics."),
                    icon = Icons.Rounded.Fingerprint,
                    canRetry = true
                )
            }
            
            errorMessage.contains("unauthorized") ||
            errorMessage.contains("401") ||
            errorMessage.contains("token") ||
            errorMessage.contains("session") ||
            errorMessage.contains("challenge") ||
            errorMessage.contains("jwt") ||
            errorMessage.contains("登录失败") ||
            errorMessage.contains("认证") ||
            errorMessage.contains("身份验证") ||
            errorMessage.contains("auth") -> {
                UserFriendlyError(
                    type = ErrorType.AUTH,
                    title = AppStrings.tr("登录失败", "Sign-in failed"),
                    message = AppStrings.tr(
                        "请重新连接钱包后重试",
                        "Please reconnect your wallet and try again."
                    ),
                    icon = Icons.Rounded.Lock,
                    canRetry = true
                )
            }
            
            errorMessage.contains("sign") ||
            errorMessage.contains("签名") -> {
                val detail = rawMessage
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .replace(Regex("\\s+"), " ")
                    .take(200)
                    .trim()
                val baseMessage = when {
                    errorMessage.contains("invalid signature") ->
                        AppStrings.tr(
                            "签名被服务端拒绝，请重新连接钱包并重试",
                            "The server rejected the signature. Please reconnect your wallet and try again."
                        )
                    errorMessage.contains("extract signature") ||
                    errorMessage.contains("nosuchfieldexception") ||
                    errorMessage.contains("messages array is empty") ||
                    errorMessage.contains("signatures array is empty") ->
                        AppStrings.tr(
                            "无法解析钱包返回的签名结果，请更新钱包或重新安装应用后重试",
                            "Unable to parse the wallet signature result. Please update your wallet or reinstall the app and try again."
                        )
                    else ->
                        AppStrings.tr("请在钱包中确认签名请求", "Please confirm the signature request in your wallet.")
                }
                val message = if (detail.isNotBlank()) {
                    baseMessage + "\n\n" + AppStrings.tr("详情: ", "Details: ") + detail
                } else {
                    baseMessage
                }
                UserFriendlyError(
                    type = ErrorType.AUTH,
                    title = AppStrings.tr("签名失败", "Signature failed"),
                    message = message,
                    icon = Icons.Rounded.Edit,
                    canRetry = true
                )
            }
            
            errorMessage.contains("upload") ||
            errorMessage.contains("storage") ||
            errorMessage.contains("irys") ||
            errorMessage.contains("上传") ||
            errorMessage.contains("存储") -> {
                UserFriendlyError(
                    type = ErrorType.STORAGE,
                    title = AppStrings.tr("存储失败", "Storage failed"),
                    message = AppStrings.tr("数据保存失败，请稍后重试", "Save failed. Please try again later."),
                    icon = Icons.Rounded.CloudOff,
                    canRetry = true
                )
            }
            
            errorMessage.contains("encrypt") ||
            errorMessage.contains("decrypt") ||
            errorMessage.contains("加密") ||
            errorMessage.contains("解密") -> {
                UserFriendlyError(
                    type = ErrorType.ENCRYPTION,
                    title = AppStrings.tr("数据处理失败", "Data processing failed"),
                    message = AppStrings.tr("请重新验证身份后重试", "Please authenticate again and retry."),
                    icon = Icons.Rounded.Lock,
                    canRetry = true
                )
            }
            
            errorMessage.contains("timeout") ||
            errorMessage.contains("超时") -> {
                UserFriendlyError(
                    type = ErrorType.TIMEOUT,
                    title = AppStrings.tr("操作超时", "Timed out"),
                    message = AppStrings.tr("响应时间过长，请检查网络后重试", "Response took too long. Please check your network and retry."),
                    icon = Icons.Rounded.Timer,
                    canRetry = true
                )
            }
            
            errorMessage.contains("expire") ||
            errorMessage.contains("过期") -> {
                UserFriendlyError(
                    type = ErrorType.AUTH,
                    title = AppStrings.tr("已过期", "Expired"),
                    message = AppStrings.tr("请重新操作", "Please try again."),
                    icon = Icons.Rounded.Schedule,
                    canRetry = true
                )
            }
            
            errorMessage.contains("balance") ||
            errorMessage.contains("余额") ||
            errorMessage.contains("insufficient") -> {
                UserFriendlyError(
                    type = ErrorType.WALLET,
                    title = AppStrings.tr("余额不足", "Insufficient balance"),
                    message = AppStrings.tr("请确保钱包有足够的 SOL", "Please make sure you have enough SOL."),
                    icon = Icons.Rounded.MoneyOff,
                    canRetry = false
                )
            }
            
            else -> {
                UserFriendlyError(
                    type = ErrorType.UNKNOWN,
                    title = AppStrings.tr("操作失败", "Operation failed"),
                    message = AppStrings.tr("出现了一些问题，请稍后重试", "Something went wrong. Please try again later."),
                    icon = Icons.Rounded.ErrorOutline,
                    canRetry = true
                )
            }
        }
    }
    
    fun parseErrorMessage(message: String?): UserFriendlyError {
        return parseError(message?.let { Exception(it) })
    }
}

/**
 * 简洁的内联错误提示 - 现代化设计
 */
@Composable
fun MinimalErrorBanner(
    error: ErrorHandler.UserFriendlyError?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = error != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        error?.let {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.XSmall),
                color = AppColors.ErrorGradientStart.copy(alpha = 0.1f),
                shape = AppShapes.Card,
                shadowElevation = AppElevations.Small
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 图标容器
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(AppCorners.Small),
                        color = AppColors.ErrorGradientStart.copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = it.icon,
                                contentDescription = null,
                                tint = AppColors.ErrorGradientStart,
                                modifier = Modifier.size(AppIconSizes.Medium)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(AppSpacing.Medium))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = it.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.ErrorGradientStart
                        )
                        Text(
                            text = it.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (it.canRetry && onRetry != null) {
                        TextButton(
                            onClick = {
                                onDismiss()
                                onRetry()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = AppColors.ErrorGradientStart
                            )
                        ) {
                            Text(AppStrings.retry, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = AppStrings.tr("关闭", "Close"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(AppIconSizes.Small)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 全屏错误页面 - 现代化设计
 */
@Composable
fun FullScreenErrorView(
    error: ErrorHandler.UserFriendlyError,
    onRetry: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        AppColors.ErrorGradientStart.copy(alpha = 0.06f),
                        AppColors.ErrorGradientEnd.copy(alpha = 0.02f),
                        Color.Transparent
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.XXLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 图标
            Surface(
                modifier = Modifier.size(100.dp),
                shape = RoundedCornerShape(AppCorners.XXLarge),
                color = AppColors.ErrorGradientStart.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = error.icon,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.XXLarge),
                        tint = AppColors.ErrorGradientStart
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.XXLarge))
            
            Text(
                text = error.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.XXLarge))
            
            if (error.canRetry) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
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
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.Small)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.XSmall))
                    Text(AppStrings.retry, fontWeight = FontWeight.SemiBold)
                }
            }
            
            if (onBack != null) {
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = AppShapes.Button,
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                ) {
                    Text(AppStrings.back, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * 错误对话框 - 现代化设计
 */
@Composable
fun ErrorDialog(
    error: ErrorHandler.UserFriendlyError?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = AppShapes.Dialog,
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(AppCorners.Medium),
                    color = AppColors.ErrorGradientStart.copy(alpha = 0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = error.icon,
                            contentDescription = null,
                            tint = AppColors.ErrorGradientStart,
                            modifier = Modifier.size(AppIconSizes.Large)
                        )
                    }
                }
            },
            title = {
                Text(
                    text = error.title,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = error.message,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                if (error.canRetry && onRetry != null) {
                    Button(
                        onClick = {
                            onDismiss()
                            onRetry()
                        },
                        shape = AppShapes.Button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.PrimaryGradientStart
                        )
                    ) {
                        Text(AppStrings.retry, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        shape = AppShapes.Button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.PrimaryGradientStart
                        )
                    ) {
                        Text(AppStrings.tr("确定", "OK"), fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {
                if (error.canRetry && onRetry != null) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = AppShapes.Button,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(AppStrings.cancel)
                    }
                }
            }
        )
    }
}
