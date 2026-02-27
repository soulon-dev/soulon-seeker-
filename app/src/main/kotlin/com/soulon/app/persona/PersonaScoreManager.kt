package com.soulon.app.persona

import timber.log.Timber
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 人格特征评分管理器
 * 
 * 管理5点人格特征的评分系统，包括：
 * - 真诚度 (Sincerity)：用户回答的真实程度
 * - 置信度 (Confidence)：AI 对评分的信心程度
 * - 完整度 (Completeness)：评估数据的完整程度
 * 
 * 评分会根据时效性和更新频率周期性衰减
 */
class PersonaScoreManager {
    
    companion object {
        // 衰减参数
        private const val HALF_LIFE_DAYS = 30.0  // 半衰期：30天
        private const val MIN_SCORE = 0.1f       // 最小分数阈值
        private const val MAX_SCORE = 1.0f       // 最大分数
        
        // 加权系数
        private const val SINCERITY_WEIGHT = 0.4f    // 真诚度权重
        private const val CONFIDENCE_WEIGHT = 0.35f  // 置信度权重
        private const val COMPLETENESS_WEIGHT = 0.25f // 完整度权重
        
        /**
         * 计算时间衰减因子
         * 使用指数衰减模型：decay = e^(-λt)，其中 λ = ln(2) / half_life
         * 
         * @param lastUpdateTime 上次更新时间戳（毫秒）
         * @param currentTime 当前时间戳（毫秒）
         * @return 衰减因子（0.0 到 1.0）
         */
        fun calculateDecayFactor(lastUpdateTime: Long, currentTime: Long = System.currentTimeMillis()): Float {
            val elapsedDays = (currentTime - lastUpdateTime) / (1000.0 * 60 * 60 * 24)
            val lambda = ln(2.0) / HALF_LIFE_DAYS
            val decayFactor = exp(-lambda * elapsedDays).toFloat()
            return max(MIN_SCORE, min(MAX_SCORE, decayFactor))
        }
        
        /**
         * 计算加权评分
         * 
         * @param sincerity 真诚度评分 (0.0-1.0)
         * @param confidence 置信度评分 (0.0-1.0)
         * @param completeness 完整度评分 (0.0-1.0)
         * @return 加权后的综合评分 (0.0-1.0)
         */
        fun calculateWeightedScore(sincerity: Float, confidence: Float, completeness: Float): Float {
            return sincerity * SINCERITY_WEIGHT + 
                   confidence * CONFIDENCE_WEIGHT + 
                   completeness * COMPLETENESS_WEIGHT
        }
        
        /**
         * 应用衰减到人格分数
         */
        fun applyDecay(score: Float, decayFactor: Float): Float {
            return max(MIN_SCORE, score * decayFactor)
        }
    }
}

/**
 * 人格特征维度评分
 * 
 * 每个维度包含：
 * - baseScore: 基础分数（基于 AI 分析）
 * - sincerity: 真诚度评分
 * - confidence: 置信度评分
 * - completeness: 完整度评分
 * - lastUpdated: 最后更新时间
 */
data class DimensionScore(
    val baseScore: Float,           // 基础分数 (0.0-1.0)
    val sincerity: Float = 0.5f,    // 真诚度 (0.0-1.0)
    val confidence: Float = 0.5f,   // 置信度 (0.0-1.0)
    val completeness: Float = 0.5f, // 完整度 (0.0-1.0)
    val lastUpdated: Long = System.currentTimeMillis(),
    val sampleCount: Int = 0        // 用于评估的样本数量
) {
    /**
     * 获取考虑衰减后的有效分数
     */
    fun getEffectiveScore(currentTime: Long = System.currentTimeMillis()): Float {
        val decayFactor = PersonaScoreManager.calculateDecayFactor(lastUpdated, currentTime)
        val weightedScore = PersonaScoreManager.calculateWeightedScore(sincerity, confidence, completeness)
        return PersonaScoreManager.applyDecay(baseScore * weightedScore, decayFactor)
    }
    
    /**
     * 获取当前衰减因子
     */
    fun getDecayFactor(currentTime: Long = System.currentTimeMillis()): Float {
        return PersonaScoreManager.calculateDecayFactor(lastUpdated, currentTime)
    }
}

/**
 * 5点人格特征完整数据
 * 
 * 五大人格特质（Big Five / OCEAN）：
 * 1. 开放性 (Openness)
 * 2. 尽责性 (Conscientiousness)
 * 3. 外向性 (Extraversion)
 * 4. 宜人性 (Agreeableness)
 * 5. 情绪稳定性 (Emotional Stability) - 神经质的反面
 */
data class PersonaTraits(
    val openness: DimensionScore,           // 开放性
    val conscientiousness: DimensionScore,  // 尽责性
    val extraversion: DimensionScore,       // 外向性
    val agreeableness: DimensionScore,      // 宜人性
    val emotionalStability: DimensionScore, // 情绪稳定性
    val overallSincerity: Float = 0.5f,     // 整体真诚度
    val overallConfidence: Float = 0.5f,    // 整体置信度
    val overallCompleteness: Float = 0.5f,  // 整体完整度
    val lastAnalyzedAt: Long = System.currentTimeMillis(),
    val totalSampleCount: Int = 0
) {
    companion object {
        /**
         * 创建默认人格特征（新用户）
         */
        fun createDefault(): PersonaTraits {
            val defaultScore = DimensionScore(
                baseScore = 0.5f,
                sincerity = 0.5f,
                confidence = 0.3f,
                completeness = 0.2f,
                sampleCount = 0
            )
            return PersonaTraits(
                openness = defaultScore,
                conscientiousness = defaultScore,
                extraversion = defaultScore,
                agreeableness = defaultScore,
                emotionalStability = defaultScore,
                overallSincerity = 0.5f,
                overallConfidence = 0.3f,
                overallCompleteness = 0.2f,
                totalSampleCount = 0
            )
        }
    }
    
    /**
     * 获取所有维度的有效分数列表
     */
    fun getEffectiveScores(currentTime: Long = System.currentTimeMillis()): List<Float> {
        return listOf(
            openness.getEffectiveScore(currentTime),
            conscientiousness.getEffectiveScore(currentTime),
            extraversion.getEffectiveScore(currentTime),
            agreeableness.getEffectiveScore(currentTime),
            emotionalStability.getEffectiveScore(currentTime)
        )
    }
    
    /**
     * 获取维度名称列表
     */
    fun getDimensionNames(): List<String> {
        return listOf("开放性", "尽责性", "外向性", "宜人性", "情绪稳定性")
    }
    
    /**
     * 获取主导特质
     */
    fun getDominantTrait(currentTime: Long = System.currentTimeMillis()): Pair<String, Float> {
        val scores = getEffectiveScores(currentTime)
        val names = getDimensionNames()
        val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
        return Pair(names[maxIndex], scores[maxIndex])
    }
    
    /**
     * 获取平均衰减因子
     */
    fun getAverageDecayFactor(currentTime: Long = System.currentTimeMillis()): Float {
        return listOf(
            openness.getDecayFactor(currentTime),
            conscientiousness.getDecayFactor(currentTime),
            extraversion.getDecayFactor(currentTime),
            agreeableness.getDecayFactor(currentTime),
            emotionalStability.getDecayFactor(currentTime)
        ).average().toFloat()
    }
    
    /**
     * 计算整体健康度（基于衰减和完整度）
     */
    fun getHealthScore(currentTime: Long = System.currentTimeMillis()): Float {
        val avgDecay = getAverageDecayFactor(currentTime)
        val avgCompleteness = (openness.completeness + conscientiousness.completeness +
                extraversion.completeness + agreeableness.completeness + 
                emotionalStability.completeness) / 5f
        return (avgDecay * 0.5f + avgCompleteness * 0.5f)
    }
}

/**
 * 评分来源类型
 */
enum class ScoreSourceType {
    AI_ANALYSIS,      // AI 对话分析
    QUESTIONNAIRE,    // 问卷评估
    MEMORY_ANALYSIS,  // 记忆内容分析
    USER_FEEDBACK     // 用户反馈校准
}

/**
 * 评分更新记录
 */
data class ScoreUpdateRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val sourceType: ScoreSourceType,
    val dimension: String,          // 维度名称
    val previousScore: Float,
    val newScore: Float,
    val sincerityDelta: Float,      // 真诚度变化
    val confidenceDelta: Float,     // 置信度变化
    val completenessDelta: Float,   // 完整度变化
    val reason: String              // 更新原因
)

/**
 * 评分更新器
 * 
 * 负责根据不同来源更新人格评分
 */
object PersonaScoreUpdater {
    
    /**
     * 基于 AI 对话分析更新评分
     * 
     * @param currentTraits 当前人格特征
     * @param analysisResult AI 分析结果
     * @return 更新后的人格特征
     */
    fun updateFromAIAnalysis(
        currentTraits: PersonaTraits,
        analysisResult: AIAnalysisResult
    ): PersonaTraits {
        val currentTime = System.currentTimeMillis()
        
        // 更新各维度
        val updatedOpenness = updateDimension(
            currentTraits.openness,
            analysisResult.openness,
            analysisResult.sincerity,
            analysisResult.confidence,
            currentTime
        )
        
        val updatedConscientiousness = updateDimension(
            currentTraits.conscientiousness,
            analysisResult.conscientiousness,
            analysisResult.sincerity,
            analysisResult.confidence,
            currentTime
        )
        
        val updatedExtraversion = updateDimension(
            currentTraits.extraversion,
            analysisResult.extraversion,
            analysisResult.sincerity,
            analysisResult.confidence,
            currentTime
        )
        
        val updatedAgreeableness = updateDimension(
            currentTraits.agreeableness,
            analysisResult.agreeableness,
            analysisResult.sincerity,
            analysisResult.confidence,
            currentTime
        )
        
        val updatedEmotionalStability = updateDimension(
            currentTraits.emotionalStability,
            analysisResult.emotionalStability,
            analysisResult.sincerity,
            analysisResult.confidence,
            currentTime
        )
        
        // 计算整体评分
        val overallSincerity = (currentTraits.overallSincerity * 0.7f + analysisResult.sincerity * 0.3f)
        val overallConfidence = (currentTraits.overallConfidence * 0.7f + analysisResult.confidence * 0.3f)
        val overallCompleteness = calculateCompleteness(
            updatedOpenness, updatedConscientiousness, updatedExtraversion,
            updatedAgreeableness, updatedEmotionalStability
        )
        
        Timber.d("📊 人格评分已更新 - 真诚度: $overallSincerity, 置信度: $overallConfidence, 完整度: $overallCompleteness")
        
        return currentTraits.copy(
            openness = updatedOpenness,
            conscientiousness = updatedConscientiousness,
            extraversion = updatedExtraversion,
            agreeableness = updatedAgreeableness,
            emotionalStability = updatedEmotionalStability,
            overallSincerity = overallSincerity,
            overallConfidence = overallConfidence,
            overallCompleteness = overallCompleteness,
            lastAnalyzedAt = currentTime,
            totalSampleCount = currentTraits.totalSampleCount + 1
        )
    }
    
    private fun updateDimension(
        current: DimensionScore,
        newScore: Float,
        sincerity: Float,
        confidence: Float,
        currentTime: Long
    ): DimensionScore {
        // 使用指数移动平均更新分数
        val alpha = 0.3f  // 学习率
        val updatedBaseScore = current.baseScore * (1 - alpha) + newScore * alpha
        val updatedSincerity = current.sincerity * (1 - alpha) + sincerity * alpha
        val updatedConfidence = current.confidence * (1 - alpha) + confidence * alpha
        
        // 完整度基于样本数量
        val newSampleCount = current.sampleCount + 1
        val updatedCompleteness = min(1.0f, newSampleCount / 10f)  // 10 个样本达到完全完整
        
        return current.copy(
            baseScore = updatedBaseScore,
            sincerity = updatedSincerity,
            confidence = updatedConfidence,
            completeness = updatedCompleteness,
            lastUpdated = currentTime,
            sampleCount = newSampleCount
        )
    }
    
    private fun calculateCompleteness(vararg dimensions: DimensionScore): Float {
        return dimensions.map { it.completeness }.average().toFloat()
    }
}

/**
 * AI 分析结果
 */
data class AIAnalysisResult(
    val openness: Float,
    val conscientiousness: Float,
    val extraversion: Float,
    val agreeableness: Float,
    val emotionalStability: Float,
    val sincerity: Float,    // AI 评估的真诚度
    val confidence: Float,   // AI 对分析的置信度
    val analysisNotes: String = ""
)
