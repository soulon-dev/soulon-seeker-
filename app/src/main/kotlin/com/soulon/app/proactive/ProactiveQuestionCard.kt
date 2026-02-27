package com.soulon.app.proactive

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soulon.app.ui.theme.AppColors
import com.soulon.app.ui.theme.AppCorners
import com.soulon.app.ui.theme.AppSpacing
import com.soulon.app.i18n.AppStrings
import java.text.SimpleDateFormat
import java.util.*

/**
 * AI 奇遇卡片
 * 
 * 显示在 AI 聊天界面顶部，邀请用户探索新的奇遇
 * 
 * @param question 奇遇问题
 * @param onAnswerClick 点击开始探索
 * @param onSkipClick 点击稍后探索
 */
@Composable
fun ProactiveQuestionCard(
    question: ProactiveQuestionEntity,
    onAnswerClick: () -> Unit,
    onSkipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val category = try {
        QuestionCategory.valueOf(question.category)
    } catch (e: Exception) {
        QuestionCategory.DAILY_LIFE
    }
    
    // 动画效果
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppSpacing.Medium),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            AppColors.SecondaryGradientStart.copy(alpha = 0.15f),
                            AppColors.PrimaryGradientStart.copy(alpha = 0.1f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(AppSpacing.Medium)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                // 顶部：标签和类别
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 奇遇标签和进度
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 奇遇标签
                        Surface(
                            shape = RoundedCornerShape(AppCorners.Full),
                            color = AppColors.SecondaryGradientStart.copy(alpha = glowAlpha)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = AppStrings.tr("✨ 奇遇任务", "✨ Adventure"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    
                    // 类别标签
                    Surface(
                        shape = RoundedCornerShape(AppCorners.Small),
                        color = Color.White.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = category.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                
                // 问题内容
                Text(
                    text = question.questionText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = AppSpacing.XSmall)
                )
                
                // 提示信息
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = AppSpacing.XSmall)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = AppStrings.tr("每一次探索，都是了解自己的旅程", "Every adventure is a journey of self-discovery"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
                
                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    // 稍后按钮
                    OutlinedButton(
                        onClick = onSkipClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White.copy(alpha = 0.6f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.2f)
                        )
                    ) {
                        Text(AppStrings.tr("稍后探索", "Later"), style = MaterialTheme.typography.labelMedium)
                    }
                    
                    // 开始探索按钮
                    Button(
                        onClick = onAnswerClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.SecondaryGradientStart
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(AppStrings.tr("开始探索", "Start"), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * 待回答问题列表
 * 
 * @param questions 待回答的奇遇列表
 */
@Composable
fun PendingQuestionsOverlay(
    questions: List<ProactiveQuestionEntity>,
    onAnswerQuestion: (ProactiveQuestionEntity) -> Unit,
    onSkipQuestion: (ProactiveQuestionEntity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    
    if (questions.isEmpty()) {
        onDismiss()
        return
    }
    
    val currentQuestion = questions.getOrNull(currentIndex)
    
    AnimatedVisibility(
        visible = currentQuestion != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        currentQuestion?.let { question ->
            ProactiveQuestionCard(
                question = question,
                onAnswerClick = { onAnswerQuestion(question) },
                onSkipClick = {
                    onSkipQuestion(question)
                    if (currentIndex < questions.size - 1) {
                        currentIndex++
                    } else {
                        onDismiss()
                    }
                },
                modifier = modifier
            )
        }
    }
}

/**
 * 待探索奇遇计数徽章
 */
@Composable
fun PendingQuestionsBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Surface(
            modifier = modifier
                .clip(RoundedCornerShape(AppCorners.Full))
                .clickable { onClick() },
            color = AppColors.SecondaryGradientStart
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = AppStrings.trf(
                        "%d 个奇遇待探索",
                        "%d adventures pending",
                        count
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * 奇遇探索对话框
 * 
 * @param question 奇遇问题
 * @param onSubmit 提交回答回调
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveQuestionAnswerDialog(
    question: ProactiveQuestionEntity,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var answerText by remember { mutableStateOf("") }
    
    val category = try {
        QuestionCategory.valueOf(question.category)
    } catch (e: Exception) {
        QuestionCategory.DAILY_LIFE
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFF1A1A2E),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = AppColors.SecondaryGradientStart,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = AppStrings.tr("✨ 奇遇探索", "✨ Adventure"),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(AppCorners.Small),
                    color = Color.White.copy(alpha = 0.05f)
                ) {
                    Text(
                        text = category.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                // 问题
                Text(
                    text = question.questionText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
                
                // 回答输入框
                OutlinedTextField(
                    value = answerText,
                    onValueChange = { answerText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = {
                        Text(
                            text = AppStrings.tr("分享你的想法...", "Share your thoughts..."),
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AppColors.SecondaryGradientStart,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        cursorColor = AppColors.SecondaryGradientStart,
                        focusedContainerColor = Color.White.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.03f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                
                // 提示
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = AppStrings.tr(
                            "你的回答将被加密存储，帮助 AI 更好地了解你",
                            "Your answer will be stored encrypted to help the AI understand you better"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.3f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (answerText.isNotBlank()) {
                        onSubmit(answerText)
                    }
                },
                enabled = answerText.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.SecondaryGradientStart
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Done,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(AppStrings.tr("完成探索", "Done"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White.copy(alpha = 0.6f)
                )
            ) {
                Text(AppStrings.tr("稍后", "Later"))
            }
        }
    )
}

/**
 * 奇遇完成动画对话框
 * 
 * 显示奇遇完成时的庆祝动画和积分奖励
 * 
 * @param rewardAmount 获得的积分奖励（默认150）
 * @param onDismiss 关闭回调
 */
@Composable
fun AdventureCompletionDialog(
    rewardAmount: Int = 150,
    onDismiss: () -> Unit
) {
    // 动画状态
    val infiniteTransition = rememberInfiniteTransition(label = "celebration")
    
    // 星星闪烁动画
    val starScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "starScale"
    )
    
    // 光晕动画
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    
    AlertDialog(
        onDismissRequest = { onDismiss() },
        shape = RoundedCornerShape(28.dp),
        containerColor = Color(0xFF1A1A2E),
        icon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(100.dp)
            ) {
                // 外层光晕
                Surface(
                    modifier = Modifier.size(100.dp),
                    shape = RoundedCornerShape(50.dp),
                    color = AppColors.SecondaryGradientStart.copy(alpha = glowAlpha * 0.3f)
                ) {}
                
                // 中层光晕
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = AppColors.SecondaryGradientStart.copy(alpha = glowAlpha * 0.5f)
                ) {}
                
                // 星星图标
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = AppColors.SecondaryGradientStart,
                    modifier = Modifier
                        .size((48 * starScale).dp)
                )
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = AppStrings.tr("✨ 奇遇完成！", "✨ Adventure complete!"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 积分奖励显示
                if (rewardAmount > 0) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF14F195).copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                tint = Color(0xFF14F195),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "+" + rewardAmount + " " + AppStrings.tr("MEMO", "MEMO"),
                                color = Color(0xFF14F195),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
                
                // 提示文字
                Text(
                    text = AppStrings.tr(
                        "回答已保存，人格分析后台进行中\n继续探索，让 AI 更懂你",
                        "Answer saved. Persona analysis is running in the background.\nKeep exploring to help the AI know you better."
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.SecondaryGradientStart
                )
            ) {
                Text(AppStrings.tr("继续", "Continue"), fontWeight = FontWeight.Bold)
            }
        }
    )
}
