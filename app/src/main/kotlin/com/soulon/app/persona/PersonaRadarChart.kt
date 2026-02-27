package com.soulon.app.persona

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soulon.app.i18n.AppStrings
import com.soulon.app.rewards.PersonaData
import com.soulon.app.rewards.PersonaProfileV2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OCEAN 人格雷达图
 * 
 * 五维人格特征的可视化展示
 * 
 * Phase 3 Week 2: Task_Persona_Analysis
 */
@Composable
fun PersonaRadarChart(
    personaData: PersonaData,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 雷达图
        Canvas(
            modifier = Modifier
                .size(300.dp)
                .padding(32.dp)
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2
            
            // 绘制背景网格
            drawRadarGrid(center, radius)
            
            // 绘制数据区域
            drawPersonaData(center, radius, personaData, accentColor)
            
            // 绘制顶点标记
            drawDataPoints(center, radius, personaData, accentColor)
        }
        
        if (showLabels) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // 维度标签和分数
            PersonaDimensionLabels(personaData)
        }
    }
}

@Composable
fun PersonaRadarChart(
    personaProfile: PersonaProfileV2,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val ocean = personaProfile.ocean
    val values = listOf(
        ocean.openness.mean,
        ocean.conscientiousness.mean,
        ocean.extraversion.mean,
        ocean.agreeableness.mean,
        ocean.neuroticism.mean
    )
    val confidences = listOf(
        ocean.openness.confidence,
        ocean.conscientiousness.confidence,
        ocean.extraversion.confidence,
        ocean.agreeableness.confidence,
        ocean.neuroticism.confidence
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .size(300.dp)
                .padding(32.dp)
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2

            drawRadarGrid(center, radius)
            drawPersonaValues(center, radius, values, accentColor, confidences.average().toFloat())
            drawDataPointsValues(center, radius, values, accentColor)
        }

        if (showLabels) {
            Spacer(modifier = Modifier.height(16.dp))
            PersonaDimensionLabels(values, confidences)
        }
    }
}

/**
 * 绘制雷达图网格
 */
private fun DrawScope.drawRadarGrid(center: Offset, radius: Float) {
    val dimensions = 5
    val angleStep = (2 * PI / dimensions).toFloat()
    
    // 绘制同心圆（5 个等级）
    for (level in 1..5) {
        val levelRadius = radius * (level / 5f)
        drawCircle(
            color = Color.Gray.copy(alpha = 0.2f),
            radius = levelRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }
    
    // 绘制维度轴线
    for (i in 0 until dimensions) {
        val angle = angleStep * i - (PI / 2).toFloat()
        val endX = center.x + radius * cos(angle)
        val endY = center.y + radius * sin(angle)
        
        drawLine(
            color = Color.Gray.copy(alpha = 0.3f),
            start = center,
            end = Offset(endX, endY),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/**
 * 绘制人格数据区域
 */
private fun DrawScope.drawPersonaData(
    center: Offset,
    radius: Float,
    personaData: PersonaData,
    accentColor: Color
) {
    val dimensions = 5
    val angleStep = (2 * PI / dimensions).toFloat()
    
    val values = listOf(
        personaData.openness,
        personaData.conscientiousness,
        personaData.extraversion,
        personaData.agreeableness,
        personaData.neuroticism
    )
    
    val path = Path()
    
    // 构建路径
    values.forEachIndexed { index, value ->
        val angle = angleStep * index - (PI / 2).toFloat()
        val distance = radius * value
        val x = center.x + distance * cos(angle)
        val y = center.y + distance * sin(angle)
        
        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()
    
    // 填充区域
    drawPath(
        path = path,
        color = accentColor.copy(alpha = 0.3f)
    )
    
    // 绘制边界线
    drawPath(
        path = path,
        color = accentColor,
        style = Stroke(width = 2.dp.toPx())
    )
}

private fun DrawScope.drawPersonaValues(
    center: Offset,
    radius: Float,
    values: List<Float>,
    accentColor: Color,
    confidence: Float
) {
    val dimensions = 5
    val angleStep = (2 * PI / dimensions).toFloat()

    val path = Path()
    values.forEachIndexed { index, value ->
        val angle = angleStep * index - (PI / 2).toFloat()
        val distance = radius * value.coerceIn(0f, 1f)
        val x = center.x + distance * cos(angle)
        val y = center.y + distance * sin(angle)

        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()

    val fillAlpha = (0.18f + 0.22f * confidence.coerceIn(0f, 1f)).coerceIn(0.12f, 0.4f)
    drawPath(
        path = path,
        color = accentColor.copy(alpha = fillAlpha)
    )
    drawPath(
        path = path,
        color = accentColor.copy(alpha = (0.6f + 0.4f * confidence.coerceIn(0f, 1f)).coerceIn(0.6f, 1f)),
        style = Stroke(width = 2.dp.toPx())
    )
}

/**
 * 绘制数据点
 */
private fun DrawScope.drawDataPoints(
    center: Offset,
    radius: Float,
    personaData: PersonaData,
    accentColor: Color
) {
    val dimensions = 5
    val angleStep = (2 * PI / dimensions).toFloat()
    
    val values = listOf(
        personaData.openness,
        personaData.conscientiousness,
        personaData.extraversion,
        personaData.agreeableness,
        personaData.neuroticism
    )
    
    values.forEachIndexed { index, value ->
        val angle = angleStep * index - (PI / 2).toFloat()
        val distance = radius * value
        val x = center.x + distance * cos(angle)
        val y = center.y + distance * sin(angle)
        
        // 绘制数据点
        drawCircle(
            color = accentColor,
            radius = 4.dp.toPx(),
            center = Offset(x, y)
        )
        
        // 绘制外圈
        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = Offset(x, y),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

private fun DrawScope.drawDataPointsValues(
    center: Offset,
    radius: Float,
    values: List<Float>,
    accentColor: Color
) {
    val dimensions = 5
    val angleStep = (2 * PI / dimensions).toFloat()

    values.forEachIndexed { index, value ->
        val angle = angleStep * index - (PI / 2).toFloat()
        val distance = radius * value.coerceIn(0f, 1f)
        val x = center.x + distance * cos(angle)
        val y = center.y + distance * sin(angle)

        drawCircle(
            color = accentColor,
            radius = 4.dp.toPx(),
            center = Offset(x, y)
        )

        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = Offset(x, y),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/**
 * 维度标签和分数
 */
@Composable
private fun PersonaDimensionLabels(personaData: PersonaData) {
    val dimensions = listOf(
        AppStrings.tr("开放性", "Openness") to personaData.openness,
        AppStrings.tr("尽责性", "Conscientiousness") to personaData.conscientiousness,
        AppStrings.tr("外向性", "Extraversion") to personaData.extraversion,
        AppStrings.tr("宜人性", "Agreeableness") to personaData.agreeableness,
        AppStrings.tr("神经质", "Neuroticism") to personaData.neuroticism
    )
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        dimensions.forEach { (name, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${(value * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PersonaDimensionLabels(values: List<Float>, confidences: List<Float>) {
    val dimensions = listOf(
        AppStrings.tr("开放性", "Openness") to 0,
        AppStrings.tr("尽责性", "Conscientiousness") to 1,
        AppStrings.tr("外向性", "Extraversion") to 2,
        AppStrings.tr("宜人性", "Agreeableness") to 3,
        AppStrings.tr("神经质", "Neuroticism") to 4
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        dimensions.forEach { (name, idx) ->
            val v = values.getOrNull(idx) ?: 0.5f
            val c = confidences.getOrNull(idx) ?: 0f
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${(v * 100).toInt()}% · " + AppStrings.tr("置信", "Conf") + " ${(c * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 人格摘要卡片
 */
@Composable
fun PersonaSummaryCard(
    personaData: PersonaData,
    syncRate: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 主导特质
        val (dominantTrait, score) = personaData.getDominantTrait()
        
        Text(
            text = AppStrings.tr("主导人格特质", "Dominant trait"),
            style = MaterialTheme.typography.titleMedium
        )
        
        Text(
            text = dominantTrait + " (" + ((score * 100).toInt()) + "%)",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        
        // 人格同步率
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = AppStrings.tr("人格同步率", "Persona sync rate"),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${(syncRate * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    syncRate >= 0.8f -> Color(0xFF4CAF50)
                    syncRate >= 0.6f -> Color(0xFFFFA726)
                    else -> Color(0xFFEF5350)
                }
            )
        }
        
        // 分析样本数
        Text(
            text = AppStrings.trf(
                "基于 %d 条记忆分析",
                "Analyzed from %d memories",
                personaData.sampleSize
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 可交互的人格雷达图
 * 
 * 支持触摸滑动显示各维度详情，手指松开立即消失
 */
@Composable
fun InteractivePersonaRadarChart(
    personaData: PersonaData,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    chartSize: Int = 320  // 雷达图尺寸（dp）
) {
    // 维度数据
    val dimensions = listOf(
        DimensionInfo("开放性", "Openness", personaData.openness),
        DimensionInfo("尽责性", "Conscientiousness", personaData.conscientiousness),
        DimensionInfo("外向性", "Extraversion", personaData.extraversion),
        DimensionInfo("宜人性", "Agreeableness", personaData.agreeableness),
        DimensionInfo("情绪稳定性", "Emotional Stability", 1f - personaData.neuroticism)
    )
    
    // 当前选中的维度索引（手指按住时显示，松开时为null）
    var selectedDimension by remember { mutableStateOf<Int?>(null) }
    
    // 存储画布尺寸信息
    var canvasCenter by remember { mutableStateOf(Offset.Zero) }
    var canvasRadius by remember { mutableStateOf(0f) }
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 雷达图
        Canvas(
            modifier = Modifier
                .size(chartSize.dp)
                .padding(32.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press, PointerEventType.Move -> {
                                    // 手指按下或移动时，计算当前位置对应的维度
                                    val position = event.changes.firstOrNull()?.position
                                    if (position != null) {
                                        selectedDimension = findTouchedDimension(
                                            tapOffset = position,
                                            center = canvasCenter,
                                            radius = canvasRadius,
                                            dimensions = 5
                                        )
                                    }
                                }
                                PointerEventType.Release, PointerEventType.Exit -> {
                                    // 手指松开或离开时，立即隐藏详情
                                    selectedDimension = null
                                }
                            }
                        }
                    }
                }
        ) {
            canvasCenter = Offset(size.width / 2, size.height / 2)
            canvasRadius = size.minDimension / 2
            
            // 绘制背景网格
            drawRadarGridInteractive(canvasCenter, canvasRadius)
            
            // 绘制数据区域
            drawPersonaDataInteractive(canvasCenter, canvasRadius, personaData, accentColor)
            
            // 绘制顶点标记（高亮选中的点）
            drawDataPointsInteractive(canvasCenter, canvasRadius, personaData, accentColor, selectedDimension)
            
            // 绘制维度标签在轴线末端
            drawDimensionLabels(canvasCenter, canvasRadius, dimensions, selectedDimension)
        }
        
        // 中央显示选中维度的详细信息（手指按住时显示）
        if (selectedDimension != null) {
            selectedDimension?.let { index ->
                val dimension = dimensions[index]
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    modifier = Modifier.padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = AppStrings.tr(dimension.nameCn, dimension.nameEn),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(dimension.value * 100).toInt()}%",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * 维度信息数据类
 */
private data class DimensionInfo(
    val nameCn: String,
    val nameEn: String,
    val value: Float
)

/**
 * 计算点击位置对应的维度索引
 */
private fun findTouchedDimension(
    tapOffset: Offset,
    center: Offset,
    radius: Float,
    dimensions: Int
): Int? {
    val dx = tapOffset.x - center.x
    val dy = tapOffset.y - center.y
    val distance = sqrt(dx * dx + dy * dy)
    
    // 如果点击在圆心附近，不选择任何维度
    if (distance < radius * 0.15f) return null
    
    // 如果点击在雷达图外部太远，不选择
    if (distance > radius * 1.3f) return null
    
    // 计算角度（从正上方开始，顺时针）
    var angle = atan2(dy, dx) + (PI / 2).toFloat()
    if (angle < 0) angle += (2 * PI).toFloat()
    
    // 计算属于哪个扇区
    val angleStep = (2 * PI / dimensions).toFloat()
    val halfStep = angleStep / 2
    
    for (i in 0 until dimensions) {
        val dimensionAngle = angleStep * i
        val startAngle = dimensionAngle - halfStep
        val endAngle = dimensionAngle + halfStep
        
        // 处理跨越 0/2π 的情况
        val normalizedAngle = if (angle < 0) angle + (2 * PI).toFloat() else angle
        val normalizedStart = if (startAngle < 0) startAngle + (2 * PI).toFloat() else startAngle
        val normalizedEnd = if (endAngle > (2 * PI).toFloat()) endAngle - (2 * PI).toFloat() else endAngle
        
        if (normalizedStart > normalizedEnd) {
            // 跨越 0 点的情况
            if (normalizedAngle >= normalizedStart || normalizedAngle <= normalizedEnd) {
                return i
            }
        } else {
            if (normalizedAngle >= normalizedStart && normalizedAngle <= normalizedEnd) {
                return i
            }
        }
    }
    
    return null
}

/**
 * 绘制雷达图网格（交互版）
 */
private fun DrawScope.drawRadarGridInteractive(center: Offset, radius: Float) {
    val dimensions = 5
    val angleStep = (2 * PI / dimensions).toFloat()
    
    // 绘制同心圆（5 个等级）
    for (level in 1..5) {
        val levelRadius = radius * (level / 5f)
        drawCircle(
            color = Color.White.copy(alpha = 0.15f),
            radius = levelRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }
    
    // 绘制维度轴线
    for (i in 0 until dimensions) {
        val angle = angleStep * i - (PI / 2).toFloat()
        val endX = center.x + radius * cos(angle)
        val endY = center.y + radius * sin(angle)
        
        drawLine(
            color = Color.White.copy(alpha = 0.2f),
            start = center,
            end = Offset(endX, endY),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/**
 * 绘制人格数据区域（交互版）
 */
private fun DrawScope.drawPersonaDataInteractive(
    center: Offset,
    radius: Float,
    personaData: PersonaData,
    accentColor: Color
) {
    val dimensions = 5
    val angleStep = (2 * PI / dimensions).toFloat()
    
    val values = listOf(
        personaData.openness,
        personaData.conscientiousness,
        personaData.extraversion,
        personaData.agreeableness,
        1f - personaData.neuroticism  // 转换为情绪稳定性
    )
    
    val path = Path()
    
    values.forEachIndexed { index, value ->
        val angle = angleStep * index - (PI / 2).toFloat()
        val distance = radius * value
        val x = center.x + distance * cos(angle)
        val y = center.y + distance * sin(angle)
        
        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()
    
    // 填充区域
    drawPath(
        path = path,
        color = accentColor.copy(alpha = 0.25f)
    )
    
    // 绘制边界线
    drawPath(
        path = path,
        color = accentColor,
        style = Stroke(width = 2.dp.toPx())
    )
}

/**
 * 绘制数据点（交互版，支持高亮）
 */
private fun DrawScope.drawDataPointsInteractive(
    center: Offset,
    radius: Float,
    personaData: PersonaData,
    accentColor: Color,
    selectedIndex: Int?
) {
    val dimensions = 5
    val angleStep = (2 * PI / dimensions).toFloat()
    
    val values = listOf(
        personaData.openness,
        personaData.conscientiousness,
        personaData.extraversion,
        personaData.agreeableness,
        1f - personaData.neuroticism
    )
    
    values.forEachIndexed { index, value ->
        val angle = angleStep * index - (PI / 2).toFloat()
        val distance = radius * value
        val x = center.x + distance * cos(angle)
        val y = center.y + distance * sin(angle)
        
        val isSelected = index == selectedIndex
        val pointRadius = if (isSelected) 8.dp.toPx() else 5.dp.toPx()
        val outerRadius = if (isSelected) 12.dp.toPx() else 7.dp.toPx()
        
        // 绘制外圈（高亮时更大更亮）
        drawCircle(
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
            radius = outerRadius,
            center = Offset(x, y),
            style = Stroke(width = if (isSelected) 3.dp.toPx() else 2.dp.toPx())
        )
        
        // 绘制数据点
        drawCircle(
            color = if (isSelected) Color.White else accentColor,
            radius = pointRadius,
            center = Offset(x, y)
        )
    }
}

/**
 * 绘制维度标签
 */
private fun DrawScope.drawDimensionLabels(
    center: Offset,
    radius: Float,
    dimensions: List<DimensionInfo>,
    selectedIndex: Int?
) {
    val angleStep = (2 * PI / dimensions.size).toFloat()
    val labelRadius = radius * 1.15f  // 标签位置在雷达图外圈
    
    dimensions.forEachIndexed { index, dimension ->
        val angle = angleStep * index - (PI / 2).toFloat()
        val x = center.x + labelRadius * cos(angle)
        val y = center.y + labelRadius * sin(angle)
        
        val isSelected = index == selectedIndex
        
        // 绘制小圆点作为维度指示
        drawCircle(
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
            radius = if (isSelected) 4.dp.toPx() else 3.dp.toPx(),
            center = Offset(x, y)
        )
    }
}
