package com.soulon.app.i18n

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import com.soulon.app.ui.theme.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext

/**
 * 首次启动语言选择界面 - 深色现代化设计
 * 
 * 设计特点：
 * - 深色渐变背景
 * - 玻璃效果卡片
 * - 渐变按钮
 */
@Composable
fun WelcomeLanguageSelectionScreen(
    localeManager: LocaleManager,
    onLanguageSelected: () -> Unit
) {
    val context = LocalContext.current
    var selectedLanguage by remember { 
        mutableStateOf(localeManager.getSelectedLanguageCode()) 
    }
    var isNavigating by remember { mutableStateOf(false) }
    val warmupState by TranslationWarmupManager.state.collectAsState()
    val selectedBaseLang = remember(selectedLanguage) { selectedLanguage.substringBefore('-').ifBlank { "en" } }
    val isPreparingSelected = warmupState.languageCode == selectedBaseLang && warmupState.isActive
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.XLarge),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.XHuge))
            
            // Logo / 图标 - 渐变光晕效果
            Box(contentAlignment = Alignment.Center) {
                // 外层光晕
                Surface(
                    modifier = Modifier.size(120.dp),
                    shape = RoundedCornerShape(60.dp),
                    color = AppColors.SecondaryGradientStart.copy(alpha = 0.1f)
                ) {}
                // 内层图标容器
                Surface(
                    modifier = Modifier.size(88.dp),
                    shape = RoundedCornerShape(44.dp),
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        AppColors.SecondaryGradientStart,
                                        AppColors.SecondaryGradientEnd
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = Color.White
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.XLarge))
            
            // 标题（多语言显示）
            Text(
                text = AppStrings.languageWelcomeTitle,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            
            // 副标题
            Text(
                text = AppStrings.languageWelcomeSubtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.XLarge))
            
            // 语言列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                items(LocaleManager.SUPPORTED_LANGUAGES) { language ->
                    WelcomeLanguageItem(
                        language = language,
                        isSelected = selectedLanguage == language.code,
                        onClick = { 
                            selectedLanguage = language.code
                            TranslationWarmupManager.start(context, language.code)
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Large))
            
            // 确认按钮 - 渐变背景
            Button(
                onClick = {
                    if (isNavigating) return@Button
                    isNavigating = true
                    localeManager.setPendingLanguage(selectedLanguage, isFirstSelection = true)
                    TranslationWarmupManager.start(context, selectedLanguage)
                    onLanguageSelected()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = AppShapes.Button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                ),
                contentPadding = PaddingValues(0.dp),
                enabled = !isNavigating
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isNavigating)
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
                    if (isNavigating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AppIconSizes.Medium),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = when {
                                isPreparingSelected && warmupState.stage == TranslationWarmupManager.Stage.Checking ->
                                    AppStrings.tr("正在检查组件...", "Checking components...")
                                isPreparingSelected && warmupState.stage == TranslationWarmupManager.Stage.PreparingModel ->
                                    AppStrings.tr("正在准备语言包...", "Preparing language pack...")
                                isPreparingSelected && warmupState.stage == TranslationWarmupManager.Stage.PreparingBundle ->
                                    AppStrings.tr("正在准备翻译包...", "Preparing translations...")
                                else -> AppStrings.continueText
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
            
            AnimatedVisibility(
                visible = isPreparingSelected && (warmupState.stage == TranslationWarmupManager.Stage.PreparingModel || warmupState.stage == TranslationWarmupManager.Stage.PreparingBundle),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.08f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.PrimaryGradientStart
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val pct = warmupState.progressPercent
                        Text(
                            text = if (pct == null) {
                                AppStrings.tr(
                                    "正在准备语言包，您可以先浏览，准备好后将自动生效。",
                                    "Preparing language pack. You can continue browsing; it will apply automatically."
                                )
                            } else {
                                AppStrings.trf(
                                    "正在准备语言包 (%d%%)，您可以先浏览，准备好后将自动生效。",
                                    "Preparing language pack (%d%%). You can continue browsing; it will apply automatically.",
                                    pct
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Large))
        }
    }
}

/**
 * 欢迎页语言选项 - 深色玻璃效果
 */
@Composable
private fun WelcomeLanguageItem(
    language: Language,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.Card)
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.border(
                    width = 2.dp,
                    color = AppColors.PrimaryGradientStart,
                    shape = AppShapes.Card
                ) else Modifier
            ),
        shape = AppShapes.Card,
        color = if (isSelected) 
            AppColors.PrimaryGradientStart.copy(alpha = 0.15f)
        else 
            Color.White.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = language.nativeName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.9f)
                )
                Text(
                    text = language.englishName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            if (isSelected) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = AppColors.PrimaryGradientStart
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * 设置页面中的语言选择界面 - 现代化设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(
    localeManager: LocaleManager,
    onBack: () -> Unit,
    onLanguageChanged: () -> Unit
) {
    var selectedLanguage by remember { 
        mutableStateOf(localeManager.getSelectedLanguageCode()) 
    }
    val currentLanguage = localeManager.getSelectedLanguageCode()
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingLanguage by remember { mutableStateOf<String?>(null) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 现代化 Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = AppStrings.back,
                        tint = Color.White
                    )
                }
                Text(
                    text = getLocalizedText("language_settings", selectedLanguage),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // 内容区域
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
            ) {
                // 当前语言卡片
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .modernCardShadow(AppElevations.Small, AppShapes.LargeCard),
                        shape = AppShapes.LargeCard,
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            AppColors.PrimaryGradientStart,
                                            AppColors.PrimaryGradientEnd
                                        )
                                    )
                                )
                                .padding(AppSpacing.Large)
                        ) {
                            Column {
                                Text(
                                    text = getLocalizedText("current_language", selectedLanguage),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(AppSpacing.XXSmall))
                                Text(
                                    text = localeManager.getSelectedLanguage().nativeName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                
                // 可选语言标题
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                    ) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = RoundedCornerShape(AppCorners.XSmall),
                            color = AppColors.SecondaryGradientStart.copy(alpha = 0.1f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Translate,
                                    contentDescription = null,
                                    modifier = Modifier.size(AppIconSizes.Small),
                                    tint = AppColors.SecondaryGradientStart
                                )
                            }
                        }
                        Text(
                            text = AppStrings.tr("可选语言", "Available languages"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                
                // 语言列表
                items(LocaleManager.SUPPORTED_LANGUAGES) { language ->
                    LanguageItem(
                        language = language,
                        isSelected = selectedLanguage == language.code,
                        showCheckmark = true,
                        onClick = {
                            if (language.code != currentLanguage) {
                                pendingLanguage = language.code
                                showConfirmDialog = true
                            }
                            selectedLanguage = language.code
                        }
                    )
                }
            }
        }
    }
    
    // 现代化确认对话框
    if (showConfirmDialog && pendingLanguage != null) {
        AlertDialog(
            onDismissRequest = { 
                showConfirmDialog = false
                pendingLanguage = null
                selectedLanguage = currentLanguage
            },
            shape = AppShapes.Dialog,
            title = {
                Text(
                    text = getLocalizedText("change_language", currentLanguage),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                val targetLang = LocaleManager.SUPPORTED_LANGUAGES.find { it.code == pendingLanguage }
                Text(
                    getLocalizedText("change_language_confirm", currentLanguage)
                        .replace("{language}", targetLang?.nativeName ?: "")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingLanguage?.let { lang ->
                            // 设置语言
                            localeManager.setLanguage(lang)
                            selectedLanguage = lang
                            
                            // 更新全局 AppStrings
                            com.soulon.app.i18n.AppStrings.setLanguage(lang)
                            
                            // 回调
                            onLanguageChanged()
                        }
                        showConfirmDialog = false
                    },
                    shape = AppShapes.Button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.PrimaryGradientStart
                    )
                ) {
                    Text(
                        text = getLocalizedText("confirm", currentLanguage),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showConfirmDialog = false
                        pendingLanguage = null
                        selectedLanguage = currentLanguage
                    },
                    shape = AppShapes.Button,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        AppColors.PrimaryGradientStart.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        text = getLocalizedText("cancel", currentLanguage),
                        color = Color.White
                    )
                }
            }
        )
    }
}

/**
 * 语言项组件 - 简洁设计
 */
@Composable
private fun LanguageItem(
    language: Language,
    isSelected: Boolean,
    showCheckmark: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .modernCardShadow(
                if (isSelected) AppElevations.Medium else AppElevations.Small,
                AppShapes.Card
            )
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = AppColors.PrimaryGradientStart,
                        shape = AppShapes.Card
                    )
                } else {
                    Modifier
                }
            ),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                AppColors.PrimaryGradientStart.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 语言名称
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.nativeName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = Color.White
                )
                if (language.nativeName != language.englishName) {
                    Spacer(modifier = Modifier.height(AppSpacing.XXSmall))
                    Text(
                        text = language.englishName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
            
            // 选中标记
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = AppColors.PrimaryGradientStart
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = AppStrings.tr("已选中", "Selected"),
                            tint = Color.White,
                            modifier = Modifier.size(AppIconSizes.Small)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 获取本地化文本（使用 AppStrings）
 */
private fun getLocalizedText(key: String, languageCode: String): String {
    val mappedKey = when (key) {
        "language_settings" -> "language_settings"
        "current_language" -> "language_current"
        "change_language" -> "language_change"
        "change_language_confirm" -> "language_change_confirm"
        "confirm" -> "confirm"
        "cancel" -> "cancel"
        else -> key
    }
    return AppStrings.resolve(mappedKey, languageCode)
}
