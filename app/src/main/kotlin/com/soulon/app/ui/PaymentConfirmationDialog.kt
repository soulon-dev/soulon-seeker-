package com.soulon.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import com.soulon.app.ui.theme.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.soulon.app.payment.PaymentManager
import com.soulon.app.i18n.AppStrings

/**
 * 支付确认对话框
 * 
 * 显示费用详情并请求用户确认
 */
@Composable
fun PaymentConfirmationDialog(
    operation: String,
    cost: PaymentManager.CostEstimate,
    currentBalance: Long,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题图标
                Icon(
                    imageVector = Icons.Rounded.AccountBalanceWallet,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 标题
                Text(
                    text = AppStrings.tr("支付确认", "Payment confirmation"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 操作描述
                Text(
                    text = operation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 内容大小卡片
                InfoCard(
                    label = AppStrings.tr("内容大小", "Content size"),
                    value = cost.formatSize()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 费用明细
                Text(
                    text = AppStrings.tr("费用明细", "Cost breakdown"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 费用项
                CostItem(
                    icon = Icons.Rounded.Cloud,
                    label = AppStrings.tr("Irys 上传", "Irys upload"),
                    amount = cost.formatSol(cost.irysUploadCost)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Divider()
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 总费用
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = AppStrings.tr("总计", "Total"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = cost.formatSol(cost.totalCost),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 余额信息
                BalanceInfo(
                    currentBalance = currentBalance,
                    costAmount = cost.totalCost
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(AppStrings.cancel)
                    }
                    
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(AppStrings.tr("确认支付", "Confirm"))
                    }
                }
            }
        }
    }
}

/**
 * 信息卡片
 */
@Composable
private fun InfoCard(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * 费用项
 */
@Composable
private fun CostItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    amount: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Text(
            text = amount,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 余额信息
 */
@Composable
private fun BalanceInfo(currentBalance: Long, costAmount: Long) {
    val remainingBalance = currentBalance - costAmount
    val isInsufficient = remainingBalance < 0
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isInsufficient) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 当前余额
            BalanceRow(
                label = AppStrings.tr("钱包余额", "Wallet balance"),
                amount = formatSol(currentBalance),
                color = if (isInsufficient) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // 支付后余额
            BalanceRow(
                label = AppStrings.tr("支付后余额", "Balance after payment"),
                amount = if (isInsufficient) {
                    AppStrings.tr("余额不足", "Insufficient")
                } else {
                    formatSol(remainingBalance)
                },
                color = if (isInsufficient) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            )
            
            // 余额不足警告
            if (isInsufficient) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    Text(
                        text = AppStrings.tr("余额不足，请先充值", "Insufficient balance. Please top up first."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 余额行
 */
@Composable
private fun BalanceRow(label: String, amount: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
        
        Text(
            text = amount,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/**
 * 支付处理对话框
 * 
 * 显示支付进度（签名、发送、确认）
 */
@Composable
fun PaymentProcessingDialog(
    status: PaymentStatus,
    transactionId: String? = null
) {
    Dialog(onDismissRequest = {}) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 状态图标
                PaymentStatusIcon(status)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 状态文本
                Text(
                    text = getStatusTitle(status),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = getStatusDescription(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                
                // 交易 ID（如果有）
                if (transactionId != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = AppStrings.trf(
                                "交易 ID\n%s...",
                                "Tx ID\n%s...",
                                transactionId.take(16)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // 进度指示器（处理中时显示）
                if (status is PaymentStatus.Signing ||
                    status is PaymentStatus.Sending ||
                    status is PaymentStatus.Confirming
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * 支付状态
 */
sealed class PaymentStatus {
    object Signing : PaymentStatus()        // 等待签名
    object Sending : PaymentStatus()        // 发送交易
    object Confirming : PaymentStatus()     // 确认交易
    object Success : PaymentStatus()        // 成功
    data class Failed(val reason: String) : PaymentStatus()  // 失败
}

/**
 * 支付状态图标
 */
@Composable
private fun PaymentStatusIcon(status: PaymentStatus) {
    when (status) {
        is PaymentStatus.Signing,
        is PaymentStatus.Sending,
        is PaymentStatus.Confirming -> {
            // 旋转动画
            val infiniteTransition = rememberInfiniteTransition(label = "rotate")
            val angle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotate"
            )
            
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .rotate(angle),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        is PaymentStatus.Success -> {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        is PaymentStatus.Failed -> {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 获取状态标题
 */
private fun getStatusTitle(status: PaymentStatus): String {
    return when (status) {
        is PaymentStatus.Signing -> AppStrings.tr("等待签名", "Awaiting signature")
        is PaymentStatus.Sending -> AppStrings.tr("发送交易", "Sending transaction")
        is PaymentStatus.Confirming -> AppStrings.tr("确认交易", "Confirming")
        is PaymentStatus.Success -> AppStrings.tr("支付成功", "Payment successful")
        is PaymentStatus.Failed -> AppStrings.tr("支付失败", "Payment failed")
    }
}

/**
 * 获取状态描述
 */
private fun getStatusDescription(status: PaymentStatus): String {
    return when (status) {
        is PaymentStatus.Signing -> AppStrings.tr("请在钱包中确认交易...", "Please confirm the transaction in your wallet...")
        is PaymentStatus.Sending -> AppStrings.tr("正在发送交易到 Solana 网络...", "Sending transaction to the Solana network...")
        is PaymentStatus.Confirming -> AppStrings.tr("等待网络确认...", "Waiting for network confirmation...")
        is PaymentStatus.Success -> AppStrings.tr("交易已成功确认", "Transaction confirmed")
        is PaymentStatus.Failed -> status.reason
    }
}

/**
 * 格式化 SOL 金额
 */
private fun formatSol(lamports: Long): String {
    val sol = lamports.toDouble() / 1_000_000_000
    return "%.8f SOL".format(sol)
}
