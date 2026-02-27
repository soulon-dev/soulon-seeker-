package com.soulon.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soulon.app.i18n.AppStrings

/**
 * Soulon 现代化设计系统
 * 
 * 设计理念：
 * - 大圆角 (24-32dp) 营造柔和现代感
 * - 柔和渐变增加层次感
 * - 精致阴影提升立体感
 * - 充足留白保持清爽
 */

// ============================================
// 圆角规范 - 更大更现代
// ============================================
object AppCorners {
    /** 超小型: 标签、徽章、小按钮 */
    val XSmall = 8.dp
    
    /** 小型: 输入框内部元素、小卡片 */
    val Small = 12.dp
    
    /** 中型: 按钮、列表项、消息气泡 */
    val Medium = 16.dp
    
    /** 大型: 标准卡片、对话框 */
    val Large = 20.dp
    
    /** 超大型: 主要卡片、底部弹窗 */
    val XLarge = 24.dp
    
    /** 特大型: 大型展示卡片 */
    val XXLarge = 28.dp
    
    /** 巨型: 全屏弹窗顶部 */
    val Huge = 32.dp
    
    /** 圆形/胶囊形 */
    val Full = 100.dp
}

// ============================================
// 预定义形状
// ============================================
object AppShapes {
    /** 小按钮 */
    val SmallButton = RoundedCornerShape(AppCorners.Small)
    
    /** 标准按钮 - 胶囊形 */
    val Button = RoundedCornerShape(AppCorners.Full)
    
    /** 方形按钮 */
    val SquareButton = RoundedCornerShape(AppCorners.Medium)
    
    /** 输入框 */
    val Input = RoundedCornerShape(AppCorners.XLarge)
    
    /** 标准卡片 */
    val Card = RoundedCornerShape(AppCorners.XLarge)
    
    /** 大卡片 */
    val LargeCard = RoundedCornerShape(AppCorners.XXLarge)
    
    /** 特大卡片 */
    val XLargeCard = RoundedCornerShape(AppCorners.Huge)
    
    /** 用户消息气泡 */
    fun userBubble() = RoundedCornerShape(
        topStart = AppCorners.XLarge,
        topEnd = AppCorners.XLarge,
        bottomStart = AppCorners.XLarge,
        bottomEnd = AppCorners.XSmall
    )
    
    /** AI消息气泡 */
    fun aiBubble() = RoundedCornerShape(
        topStart = AppCorners.XLarge,
        topEnd = AppCorners.XLarge,
        bottomStart = AppCorners.XSmall,
        bottomEnd = AppCorners.XLarge
    )
    
    /** 底部弹窗 */
    val BottomSheet = RoundedCornerShape(
        topStart = AppCorners.Huge,
        topEnd = AppCorners.Huge,
        bottomStart = 0.dp,
        bottomEnd = 0.dp
    )
    
    /** 对话框 */
    val Dialog = RoundedCornerShape(AppCorners.XXLarge)
    
    /** 标签/徽章 */
    val Tag = RoundedCornerShape(AppCorners.Full)
    
    /** 搜索框 */
    val SearchBar = RoundedCornerShape(AppCorners.Full)
    
    /** 导航栏项目 */
    val NavItem = RoundedCornerShape(AppCorners.Medium)
}

// ============================================
// 现代化颜色
// ============================================
object AppColors {
    // 主色调 - 渐变紫蓝
    val PrimaryGradientStart = Color(0xFF6366F1)  // Indigo
    val PrimaryGradientEnd = Color(0xFF8B5CF6)    // Purple
    
    // 次要色 - 青色
    val SecondaryGradientStart = Color(0xFF06B6D4) // Cyan
    val SecondaryGradientEnd = Color(0xFF0EA5E9)   // Sky
    
    // 成功色 - 绿色
    val SuccessGradientStart = Color(0xFF10B981)  // Emerald
    val SuccessGradientEnd = Color(0xFF34D399)    // Green
    
    // 警告色 - 橙色
    val WarningGradientStart = Color(0xFFF59E0B)  // Amber
    val WarningGradientEnd = Color(0xFFFBBF24)    // Yellow
    
    // 错误色 - 红色
    val ErrorGradientStart = Color(0xFFEF4444)    // Red
    val ErrorGradientEnd = Color(0xFFF87171)      // Light Red
    
    // 卡片背景 - 柔和灰
    val CardBackground = Color(0xFFF8FAFC)        // Slate 50
    val CardBackgroundDark = Color(0xFF1E293B)    // Slate 800
    
    // 表面色
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceDark = Color(0xFF0F172A)           // Slate 900
    
    // 边框色
    val BorderLight = Color(0xFFE2E8F0)           // Slate 200
    val BorderDark = Color(0xFF334155)            // Slate 700
    
    // 文字色
    val TextPrimary = Color(0xFF0F172A)           // Slate 900
    val TextSecondary = Color(0xFF64748B)         // Slate 500
    val TextTertiary = Color(0xFF94A3B8)          // Slate 400
    
    // 渐变
    val primaryGradient = Brush.linearGradient(
        colors = listOf(PrimaryGradientStart, PrimaryGradientEnd)
    )
    
    val secondaryGradient = Brush.linearGradient(
        colors = listOf(SecondaryGradientStart, SecondaryGradientEnd)
    )
    
    val successGradient = Brush.linearGradient(
        colors = listOf(SuccessGradientStart, SuccessGradientEnd)
    )
    
    val cardGradient = Brush.verticalGradient(
        colors = listOf(Color.White, Color(0xFFF8FAFC))
    )
    
    val heroGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF6366F1).copy(alpha = 0.1f),
            Color(0xFF8B5CF6).copy(alpha = 0.05f),
            Color.Transparent
        )
    )
}

// ============================================
// 阴影规范
// ============================================
object AppElevations {
    /** 无阴影 */
    val None = 0.dp
    
    /** 微弱阴影: 悬浮元素 */
    val XSmall = 1.dp
    
    /** 小阴影: 普通卡片 */
    val Small = 2.dp
    
    /** 中阴影: 交互卡片 */
    val Medium = 4.dp
    
    /** 大阴影: 重要卡片 */
    val Large = 8.dp
    
    /** 特大阴影: 浮动元素、对话框 */
    val XLarge = 16.dp
    
    /** 巨型阴影: 模态框 */
    val XXLarge = 24.dp
}

/**
 * 现代化卡片阴影
 */
fun Modifier.modernCardShadow(
    elevation: Dp = AppElevations.Medium,
    shape: RoundedCornerShape = AppShapes.Card
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.08f),
        spotColor = Color.Black.copy(alpha = 0.12f)
    )

/**
 * 柔和多层阴影 - 更真实的阴影效果
 */
fun Modifier.softLayeredShadow(
    cornerRadius: Dp = AppCorners.XLarge
): Modifier = this
    .shadow(
        elevation = 1.dp,
        shape = RoundedCornerShape(cornerRadius),
        ambientColor = Color.Black.copy(alpha = 0.04f)
    )
    .shadow(
        elevation = 4.dp,
        shape = RoundedCornerShape(cornerRadius),
        ambientColor = Color.Black.copy(alpha = 0.06f)
    )
    .shadow(
        elevation = 10.dp,
        shape = RoundedCornerShape(cornerRadius),
        ambientColor = Color.Black.copy(alpha = 0.08f)
    )

/**
 * 主色调渐变背景
 */
fun Modifier.primaryGradientBackground(
    shape: RoundedCornerShape = AppShapes.Card
): Modifier = this
    .clip(shape)
    .background(AppColors.primaryGradient)

/**
 * 卡片渐变背景
 */
fun Modifier.cardGradientBackground(
    shape: RoundedCornerShape = AppShapes.Card
): Modifier = this
    .clip(shape)
    .background(AppColors.cardGradient)

// ============================================
// 间距规范 - 更宽松现代
// ============================================
object AppSpacing {
    val XXSmall = 4.dp
    val XSmall = 8.dp
    val Small = 12.dp
    val Medium = 16.dp
    val Large = 20.dp
    val XLarge = 24.dp
    val XXLarge = 32.dp
    val XXXLarge = 40.dp
    val Huge = 48.dp
    val XHuge = 64.dp
}

// ============================================
// 图标尺寸规范
// ============================================
object AppIconSizes {
    /** 迷你图标: 标签内 */
    val XSmall = 14.dp
    
    /** 小图标: 按钮内、列表 */
    val Small = 18.dp
    
    /** 普通图标: 导航、工具栏 */
    val Medium = 22.dp
    
    /** 大图标: 卡片标题、功能入口 */
    val Large = 26.dp
    
    /** 特大图标: 空状态、引导 */
    val XLarge = 40.dp
    
    /** 巨大图标: 主要展示 */
    val XXLarge = 56.dp
    
    /** 超大图标: Logo、欢迎页 */
    val Hero = 72.dp
    
    /** 特大展示图标 */
    val Giant = 96.dp
}

// ============================================
// 动画时长
// ============================================
object AppAnimations {
    const val Fast = 150
    const val Normal = 300
    const val Slow = 500
    const val VerySlow = 800
}

// ============================================
// 会员等级颜色
// ============================================
object TierColors {
    // Bronze - 青铜色
    val BronzeStart = Color(0xFFCD7F32)
    val BronzeEnd = Color(0xFFB87333)
    
    // Silver - 白银色
    val SilverStart = Color(0xFFC0C0C0)
    val SilverEnd = Color(0xFFA8A8A8)
    
    // Gold - 黄金色
    val GoldStart = Color(0xFFFFD700)
    val GoldEnd = Color(0xFFFFA500)
    
    // Platinum - 铂金色
    val PlatinumStart = Color(0xFFE5E4E2)
    val PlatinumEnd = Color(0xFFB4B4B4)
    
    // Diamond - 钻石蓝
    val DiamondStart = Color(0xFFB9F2FF)
    val DiamondEnd = Color(0xFF00BFFF)
    
    /**
     * 根据等级获取渐变颜色
     * @param tier 1-5 对应 Bronze, Silver, Gold, Platinum, Diamond
     */
    fun getGradientColors(tier: Int): List<Color> {
        return when (tier) {
            1 -> listOf(BronzeStart, BronzeEnd)
            2 -> listOf(SilverStart, SilverEnd)
            3 -> listOf(GoldStart, GoldEnd)
            4 -> listOf(PlatinumStart, PlatinumEnd)
            5 -> listOf(DiamondStart, DiamondEnd)
            else -> listOf(BronzeStart, BronzeEnd)
        }
    }
    
    /**
     * 根据等级获取渐变 Brush
     */
    fun getGradientBrush(tier: Int): Brush {
        return Brush.linearGradient(colors = getGradientColors(tier))
    }
    
    /**
     * 根据等级获取主颜色
     */
    fun getPrimaryColor(tier: Int): Color {
        return when (tier) {
            1 -> BronzeStart
            2 -> SilverStart
            3 -> GoldStart
            4 -> PlatinumStart
            5 -> DiamondStart
            else -> BronzeStart
        }
    }
    
    /**
     * 获取等级名称
     */
    fun getTierName(tier: Int): String {
        return when (tier) {
            1 -> "Bronze"
            2 -> "Silver"
            3 -> "Gold"
            4 -> "Platinum"
            5 -> "Diamond"
            else -> "Bronze"
        }
    }
    
    /**
     * 获取等级中文名称
     */
    fun getTierNameCn(tier: Int): String {
        return when (tier) {
            1 -> "青铜"
            2 -> "白银"
            3 -> "黄金"
            4 -> "铂金"
            5 -> "钻石"
            else -> "青铜"
        }
    }

    fun getTierNameLocalized(tier: Int): String {
        return AppStrings.tr(getTierNameCn(tier), getTierName(tier))
    }
    
    /**
     * 获取等级图标
     */
    fun getTierEmoji(tier: Int): String {
        return when (tier) {
            1 -> "🥉"
            2 -> "🥈"
            3 -> "🥇"
            4 -> "💫"
            5 -> "💎"
            else -> "🥉"
        }
    }
}
