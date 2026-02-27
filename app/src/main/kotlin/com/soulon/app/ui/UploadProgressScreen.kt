package com.soulon.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.soulon.app.ui.theme.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soulon.app.i18n.AppStrings
import com.soulon.app.storage.UploadProgressManager

/**
 * 上传进度屏幕 - 简洁现代设计
 * 
 * 使用圆形进度环代替多个卡片，更加简洁
 */
@Composable
fun UploadProgressScreen(
    uploadStates: Map<String, UploadProgressManager.UploadState>,
    isAnalyzingPersona: Boolean = false,
    onComplete: () -> Unit,
    onRetry: (String) -> Unit = {},
    onEmptyAction: (() -> Unit)? = null
) {
    // 统计各状态数量
    val totalCount = uploadStates.size
    val completedCount = uploadStates.values.count { 
        it.status == UploadProgressManager.UploadStatus.COMPLETED 
    }
    val failedCount = uploadStates.values.count { 
        it.status == UploadProgressManager.UploadStatus.FAILED 
    }
    val inProgressCount = uploadStates.values.count { 
        it.status == UploadProgressManager.UploadStatus.UPLOADING ||
        it.status == UploadProgressManager.UploadStatus.ENCRYPTING ||
        it.status == UploadProgressManager.UploadStatus.MINTING ||
        it.status == UploadProgressManager.UploadStatus.RETRYING
    }
    
    val allCompleted = completedCount == totalCount && totalCount > 0
    val anyFailed = failedCount > 0
    val fullyCompleted = allCompleted && !isAnalyzingPersona
    val isEmptyState = totalCount == 0
    
    // 计算总体进度
    val overallProgress = if (totalCount > 0) {
        completedCount.toFloat() / totalCount.toFloat()
    } else 0f
    
    // 获取当前正在处理的记忆
    val currentProcessing = uploadStates.values.find { 
        it.status == UploadProgressManager.UploadStatus.UPLOADING ||
        it.status == UploadProgressManager.UploadStatus.ENCRYPTING ||
        it.status == UploadProgressManager.UploadStatus.MINTING ||
        it.status == UploadProgressManager.UploadStatus.RETRYING
    }
    
    Scaffold(
        containerColor = Color(0xFF0A0A0F),
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    isEmptyState -> {
                        Button(
                            onClick = { onEmptyAction?.invoke() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = AppShapes.Button,
                            enabled = onEmptyAction != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                disabledContainerColor = Color.White.copy(alpha = 0.1f)
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
                                Text(
                                    text = AppStrings.tr("返回继续", "Back to continue"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    isAnalyzingPersona && allCompleted -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = AppColors.PrimaryGradientStart
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = AppStrings.tr("正在分析您的人格特征，请稍候...", "Analyzing your persona, please wait..."),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    fullyCompleted -> {
                        Button(
                            onClick = onComplete,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
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
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = AppStrings.tr("开始探索", "Start exploring"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                    anyFailed -> {
                        Button(
                            onClick = {
                                uploadStates.values
                                    .filter { it.status == UploadProgressManager.UploadStatus.FAILED }
                                    .forEach { onRetry(it.memoryId) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = AppShapes.Button,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF3D2020)
                            )
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = Color(0xFFFF6B6B)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                AppStrings.trf(
                                    "重试失败的上传 (%d)",
                                    "Retry failed uploads (%d)",
                                    failedCount
                                ),
                                color = Color(0xFFFF6B6B),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        TextButton(
                            onClick = onComplete,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = AppStrings.tr("跳过等待，后台上传", "Skip and upload in background"),
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                    else -> {
                        // 上传进行中
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 显示一个小加载圈，提示仍在工作
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White.copy(alpha = 0.5f),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // 更加显眼的后台运行按钮
                            Button(
                                onClick = onComplete,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = AppShapes.Button,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.1f)
                                )
                            ) {
                                Text(
                                    text = AppStrings.tr("后台运行 (可稍后查看)", "Run in background"),
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
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = when {
                    isAnalyzingPersona && allCompleted -> AppStrings.tr("正在分析人格特征...", "Analyzing persona...")
                    fullyCompleted -> AppStrings.tr("初始化完成！", "Initialization complete!")
                    anyFailed -> AppStrings.tr("部分上传失败", "Some uploads failed")
                    else -> AppStrings.tr("正在上传记忆...", "Uploading memories...")
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    isAnalyzingPersona && allCompleted -> AppStrings.tr("AI 正在根据您的回答分析人格特征", "AI is analyzing your persona based on your answers")
                    fullyCompleted -> AppStrings.tr("所有记忆已安全存储到区块链", "All memories are securely stored on-chain")
                    anyFailed -> AppStrings.trf(
                        "%d 条记忆上传失败，请重试",
                        "%d uploads failed. Please retry.",
                        failedCount
                    )
                    else -> AppStrings.tr("正在将您的记忆加密并上传至区块链", "Encrypting and uploading your memories on-chain")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            Box(
                modifier = Modifier.size(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressRing(
                    progress = 1f,
                    color = Color.White.copy(alpha = 0.1f),
                    strokeWidth = 12.dp
                )

                if (isAnalyzingPersona && allCompleted) {
                    val infiniteTransition = rememberInfiniteTransition(label = "persona")
                    val rotationAngle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "rotation"
                    )
                    CircularProgressRing(
                        progress = 0.75f,
                        color = AppColors.PrimaryGradientStart,
                        strokeWidth = 12.dp,
                        startAngle = rotationAngle
                    )
                } else {
                    val animatedProgress by animateFloatAsState(
                        targetValue = overallProgress,
                        animationSpec = tween(500),
                        label = "progress"
                    )
                    CircularProgressRing(
                        progress = animatedProgress,
                        gradientColors = if (anyFailed) {
                            listOf(Color(0xFFFF6B6B), Color(0xFFFF8E8E))
                        } else {
                            listOf(AppColors.PrimaryGradientStart, AppColors.PrimaryGradientEnd)
                        },
                        strokeWidth = 12.dp
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isAnalyzingPersona && allCompleted) {
                        Icon(
                            imageVector = Icons.Rounded.Psychology,
                            contentDescription = null,
                            tint = AppColors.PrimaryGradientStart,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = AppStrings.tr("分析中", "Analyzing"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    } else if (fullyCompleted) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = AppColors.SuccessGradientStart,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = AppStrings.tr("完成", "Done"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    } else {
                        Text(
                            text = completedCount.toString(),
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontSize = 48.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "/ " + totalCount,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            if (!isEmptyState) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Card,
                    color = Color.White.copy(alpha = 0.05f)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        if (currentProcessing != null && !fullyCompleted && !isAnalyzingPersona) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (currentProcessing.status) {
                                        UploadProgressManager.UploadStatus.ENCRYPTING -> Icons.Rounded.Lock
                                        UploadProgressManager.UploadStatus.MINTING -> Icons.Rounded.AccountBalanceWallet
                                        else -> Icons.Rounded.CloudUpload
                                    },
                                    contentDescription = null,
                                    tint = AppColors.PrimaryGradientStart,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = when (currentProcessing.status) {
                                        UploadProgressManager.UploadStatus.ENCRYPTING -> AppStrings.tr("正在加密记忆...", "Encrypting memory...")
                                        UploadProgressManager.UploadStatus.MINTING -> AppStrings.tr("正在铸造凭证...", "Minting credential...")
                                        UploadProgressManager.UploadStatus.RETRYING -> AppStrings.tr("正在重试...", "Retrying...")
                                        else -> AppStrings.tr("正在上传至 Arweave...", "Uploading to Arweave...")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(currentProcessing.progress.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    AppColors.PrimaryGradientStart,
                                                    AppColors.PrimaryGradientEnd
                                                )
                                            )
                                        )
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatusStat(
                                icon = Icons.Rounded.CheckCircle,
                                count = completedCount,
                                label = AppStrings.tr("已完成", "Done"),
                                color = AppColors.SuccessGradientStart
                            )
                            StatusStat(
                                icon = Icons.Rounded.CloudUpload,
                                count = inProgressCount,
                                label = AppStrings.tr("进行中", "In progress"),
                                color = AppColors.PrimaryGradientStart
                            )
                            StatusStat(
                                icon = Icons.Rounded.Error,
                                count = failedCount,
                                label = AppStrings.tr("失败", "Failed"),
                                color = Color(0xFFFF6B6B)
                            )
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Card,
                    color = Color.White.copy(alpha = 0.05f)
                ) {
                    Text(
                        text = AppStrings.tr(
                            "上传任务尚未创建，可能是网络或状态恢复导致的延迟。点击下方按钮返回继续。",
                            "No upload task found yet. Tap the button below to go back and continue."
                        ),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 圆形进度环
 */
@Composable
private fun CircularProgressRing(
    progress: Float,
    color: Color? = null,
    gradientColors: List<Color>? = null,
    strokeWidth: androidx.compose.ui.unit.Dp,
    startAngle: Float = -90f
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val sweepAngle = progress * 360f
        val stroke = Stroke(
            width = strokeWidth.toPx(),
            cap = StrokeCap.Round
        )
        
        drawArc(
            color = color ?: gradientColors?.first() ?: Color.White,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = stroke
        )
    }
}

/**
 * 状态统计项
 */
@Composable
private fun StatusStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (count > 0) color else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) Color.White else Color.White.copy(alpha = 0.3f)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}
