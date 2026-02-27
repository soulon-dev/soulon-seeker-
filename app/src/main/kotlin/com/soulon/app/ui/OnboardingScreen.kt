package com.soulon.app.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soulon.app.onboarding.OnboardingAnswer
import com.soulon.app.onboarding.OnboardingQuestion
import com.soulon.app.onboarding.QuestionType
import com.soulon.app.ui.theme.*
import androidx.compose.foundation.layout.imePadding
import com.soulon.app.i18n.AppStrings
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 初始化问卷界面 - 深色现代化设计，共享顶部栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    questions: List<OnboardingQuestion>,
    currentIndex: Int,
    answers: List<OnboardingAnswer>,
    onAnswerChanged: (Int, String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onComplete: () -> Unit,
    onNavigateToHome: () -> Unit = {},
    isProcessing: Boolean = false
) {
    val currentQuestion = questions[currentIndex]
    val currentAnswer = answers.find { it.questionId == currentQuestion.id }?.answer ?: ""
    val progress = (currentIndex + 1).toFloat() / questions.size.toFloat()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 共享顶部栏 - 和 AI Chat 相同的设计
            OnboardingTopBar(
                currentIndex = currentIndex,
                totalQuestions = questions.size,
                progress = progress,
                onHomeClick = onNavigateToHome
            )
            
            // 主要内容区
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Medium)
            ) {
                // 标题区（紧凑）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.Small),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = AppStrings.tr("初始化你的 AI 助手", "Initialize your AI assistant"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = AppStrings.tr("回答以下问题，帮助 AI 更好地了解你", "Answer a few questions to help the AI understand you better"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                
                // 中间：问题和答案
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 问题卡片
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.Card,
                        color = Color.White.copy(alpha = 0.08f)
                    ) {
                        Column(
                            modifier = Modifier.padding(AppSpacing.Large)
                        ) {
                            // 问题编号标签
                            Surface(
                                shape = RoundedCornerShape(AppCorners.Full),
                                color = AppColors.PrimaryGradientStart.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = AppStrings.trf("问题 %d", "Question %d", currentQuestion.id),
                                    modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = AppColors.PrimaryGradientStart
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(AppSpacing.Medium))
                            
                            // 问题内容
                            Text(
                                text = currentQuestion.question,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    
                    // 根据问题类型显示不同的输入方式
                    when (currentQuestion.type) {
                        QuestionType.SINGLE_CHOICE -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.XSmall)
                            ) {
                                currentQuestion.options.forEach { option ->
                                    val isSelected = currentAnswer == option
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = isSelected,
                                                enabled = !isProcessing,
                                                onClick = { onAnswerChanged(currentQuestion.id, option) },
                                                role = Role.RadioButton
                                            ),
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) {
                                            AppColors.PrimaryGradientStart.copy(alpha = 0.15f)
                                        } else {
                                            Color.White.copy(alpha = 0.05f)
                                        },
                                        border = if (isSelected) {
                                            androidx.compose.foundation.BorderStroke(
                                                width = 1.5.dp,
                                                color = AppColors.PrimaryGradientStart
                                            )
                                        } else null
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Medium),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 自定义选中指示器
                                            Surface(
                                                modifier = Modifier.size(22.dp),
                                                shape = RoundedCornerShape(AppCorners.Full),
                                                color = if (isSelected) {
                                                    AppColors.PrimaryGradientStart
                                                } else {
                                                    Color.Transparent
                                                },
                                                border = androidx.compose.foundation.BorderStroke(
                                                    width = 1.5.dp,
                                                    color = if (isSelected) {
                                                        AppColors.PrimaryGradientStart
                                                    } else {
                                                        Color.White.copy(alpha = 0.3f)
                                                    }
                                                )
                                            ) {
                                                if (isSelected) {
                                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Check,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = Color.White
                                                        )
                                                    }
                                                }
                                            }
                                            
                                            Spacer(modifier = Modifier.width(AppSpacing.Medium))
                                            
                                            Text(
                                                text = option,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                                color = Color.White,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        QuestionType.OPEN_TEXT -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.05f)
                            ) {
                                OutlinedTextField(
                                    value = currentAnswer,
                                    onValueChange = { onAnswerChanged(currentQuestion.id, it) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp),
                                    placeholder = {
                                        Text(
                                            text = currentQuestion.placeholder,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White.copy(alpha = 0.4f)
                                        )
                                    },
                                    enabled = !isProcessing,
                                    maxLines = 6,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AppColors.PrimaryGradientStart,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        cursorColor = AppColors.PrimaryGradientStart
                                    )
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(AppSpacing.Medium))
                    
                    // 提示文本
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = AppColors.WarningGradientStart.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(AppSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lightbulb,
                                contentDescription = null,
                                tint = AppColors.WarningGradientStart,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = AppStrings.tr("请真实回答，这将帮助 AI 更好地理解你", "Answer honestly to help the AI understand you better"),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.Medium))
                
                // 底部：导航按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AppSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    // 上一题按钮
                    if (currentIndex > 0) {
                        OutlinedButton(
                            onClick = onPrevious,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            enabled = !isProcessing,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Color.White.copy(alpha = 0.3f)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(AppStrings.tr("上一题", "Previous"), fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    
                    // 下一题/完成按钮
                    Button(
                        onClick = {
                            if (currentIndex < questions.size - 1) {
                                onNext()
                            } else {
                                onComplete()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        enabled = currentAnswer.isNotBlank() && !isProcessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.PrimaryGradientStart,
                            disabledContainerColor = Color.White.copy(alpha = 0.1f)
                        )
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(AppStrings.tr("处理中...", "Processing..."), fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(
                                text = if (currentIndex < questions.size - 1) {
                                    AppStrings.tr("下一题", "Next")
                                } else {
                                    AppStrings.tr("确认上传", "Confirm upload")
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (currentIndex < questions.size - 1) {
                                    Icons.Rounded.ArrowForward
                                } else {
                                    Icons.Rounded.CloudUpload
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 问卷顶部栏 - 和 AI Chat 共享设计风格
 */
@Composable
private fun OnboardingTopBar(
    currentIndex: Int,
    totalQuestions: Int,
    progress: Float,
    onHomeClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.Medium),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.XSmall),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：AI 助手图标
            Surface(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
                color = AppColors.PrimaryGradientStart.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = AppColors.PrimaryGradientStart,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            
            // 中间：进度信息
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                // 进度条
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        AppColors.PrimaryGradientStart,
                                        AppColors.PrimaryGradientEnd
                                    )
                                )
                            )
                    )
                }
                
                // 进度文字
                Surface(
                    shape = RoundedCornerShape(AppCorners.Full),
                    color = AppColors.PrimaryGradientStart.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = (currentIndex + 1).toString() + "/" + totalQuestions,
                        modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.XSmall),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.PrimaryGradientStart
                    )
                }
            }
            
            // 右侧：返回首页按钮
            IconButton(
                onClick = onHomeClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Home,
                    contentDescription = AppStrings.tr("返回首页", "Back to Home"),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * 完成界面 - 深色现代化设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingCompletionScreen(
    onStartChat: () -> Unit,
    onNavigateToHome: () -> Unit = {}
) {
    val enableAdventureNotificationPrompt = false
    if (enableAdventureNotificationPrompt) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var hasNotificationPermission by remember {
            mutableStateOf(com.soulon.app.proactive.NotificationPermissionHelper.hasNotificationPermission(context))
        }
        var showNotificationDialog by remember {
            mutableStateOf(!hasNotificationPermission)
        }

        fun openNotificationSettings() {
            runCatching {
                val intent = Intent().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    } else {
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasNotificationPermission = isGranted
            showNotificationDialog = false
            timber.log.Timber.d("通知权限请求结果: $isGranted")
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasNotificationPermission = com.soulon.app.proactive.NotificationPermissionHelper.hasNotificationPermission(context)
                    if (hasNotificationPermission) {
                        showNotificationDialog = false
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (showNotificationDialog) {
            val shouldRequestPermission = com.soulon.app.proactive.NotificationPermissionHelper.shouldRequestPermission(context)
            AlertDialog(
                onDismissRequest = { showNotificationDialog = false },
                shape = AppShapes.Dialog,
                containerColor = Color(0xFF1A1A2E),
                icon = {
                    Surface(
                        shape = AppShapes.Card,
                        color = AppColors.SecondaryGradientStart.copy(alpha = 0.2f),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                tint = AppColors.SecondaryGradientStart,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text = AppStrings.tr("开启奇遇通知", "Enable adventure notifications"),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
                    ) {
                        Text(
                            text = AppStrings.tr("开启通知后，AI 助手会随机向你发送奇遇探索邀请，每次完成都能获得丰厚积分奖励！", "With notifications enabled, the AI will occasionally invite you to adventures. Completing them earns generous rewards!"),
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.05f)
                        ) {
                            Column(
                                modifier = Modifier.padding(AppSpacing.Medium),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                            ) {
                                NotificationBenefitItem(
                                    icon = Icons.Rounded.Stars,
                                    text = AppStrings.tr("完成奇遇可获得 50-200 积分奖励", "Complete adventures to earn 50–200 points")
                                )
                                NotificationBenefitItem(
                                    icon = Icons.Rounded.AutoAwesome,
                                    text = AppStrings.tr("随机不定时推送，惊喜探索体验", "Random surprises delivered from time to time")
                                )
                                NotificationBenefitItem(
                                    icon = Icons.Rounded.Psychology,
                                    text = AppStrings.tr("每次互动都让 AI 更懂你", "Every interaction helps the AI know you better")
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (shouldRequestPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                return@Button
                            }
                            openNotificationSettings()
                            showNotificationDialog = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.SecondaryGradientStart
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (shouldRequestPermission) {
                                AppStrings.tr("立即开启", "Enable now")
                            } else {
                                AppStrings.tr("去设置开启", "Open settings")
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showNotificationDialog = false },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White.copy(alpha = 0.6f)
                        )
                    ) {
                        Text(AppStrings.tr("稍后再说", "Not now"))
                    }
                }
            )
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Medium),
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.08f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.XSmall),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧占位
                    Spacer(modifier = Modifier.size(44.dp))
                    
                    // 中间：完成标签
                    Surface(
                        shape = RoundedCornerShape(AppCorners.Full),
                        color = AppColors.SuccessGradientStart.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.XSmall),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = AppColors.SuccessGradientStart,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = AppStrings.tr("初始化完成", "Setup complete"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.SuccessGradientStart
                            )
                        }
                    }
                    
                    // 右侧：返回首页按钮
                    IconButton(
                        onClick = onNavigateToHome,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Home,
                            contentDescription = AppStrings.tr("返回首页", "Back to Home"),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            
            // 主内容
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.XLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 成功图标 - 渐变光晕效果
                Box(contentAlignment = Alignment.Center) {
                    // 外层光晕
                    Surface(
                        modifier = Modifier.size(120.dp),
                        shape = RoundedCornerShape(60.dp),
                        color = AppColors.SuccessGradientStart.copy(alpha = 0.1f)
                    ) {}
                    // 内层图标容器
                    Surface(
                        modifier = Modifier.size(88.dp),
                        shape = RoundedCornerShape(44.dp),
                        color = AppColors.SuccessGradientStart.copy(alpha = 0.2f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = AppColors.SuccessGradientStart
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.XLarge))
                
                Text(
                    text = AppStrings.tr("初始化完成！", "Initialization complete!"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                
                Text(
                    text = AppStrings.tr("你的 AI 助手已准备就绪", "Your AI assistant is ready"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(AppSpacing.XLarge))
                
                // 完成信息卡片 - 深色玻璃效果
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Card,
                    color = Color.White.copy(alpha = 0.05f)
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
                    ) {
                        CompletionItem(
                            icon = Icons.Rounded.CloudDone,
                            text = AppStrings.tr("初始记忆已安全存储到区块链", "Your initial memory has been securely stored on-chain"),
                            color = AppColors.SecondaryGradientStart
                        )
                        CompletionItem(
                            icon = Icons.Rounded.Psychology,
                            text = AppStrings.tr("AI 助手已了解你的基本信息", "The AI has learned your basic profile"),
                            color = AppColors.PrimaryGradientStart
                        )
                        CompletionItem(
                            icon = Icons.Rounded.Analytics,
                            text = AppStrings.tr("人格档案分析完成", "Persona profile analysis completed"),
                            color = AppColors.PrimaryGradientEnd
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.XXLarge))
                
                // 开始按钮 - 渐变背景
                Button(
                    onClick = onStartChat,
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Forum,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.Small))
                            Text(
                                text = AppStrings.tr("开始对话", "Start chatting"),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(10.dp),
            color = color.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = color
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

/**
 * 通知好处列表项
 */
@Composable
private fun NotificationBenefitItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.SecondaryGradientStart,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}
