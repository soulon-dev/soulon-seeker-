package com.soulon.app.rewards

import android.content.Context
import com.soulon.app.onboarding.OnboardingEvaluationStorage
import com.soulon.app.onboarding.OnboardingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 人格共鸣评分器
 * 
 * V1 白皮书核心组件：AI 根据用户问卷建立的画像，对用户的回复进行 0-100 评分
 * 
 * 评分等级：
 * - S 级 (90-100): +100 MEMO (触发特效，极少见)
 * - A 级 (70-89): +30 MEMO (高度符合人格特征)
 * - B 级 (40-69): +10 MEMO (正常互动)
 * - C 级 (<40): +0 (出戏或无效回复)
 * 
 * 评估维度：
 * 1. 语言风格一致性 - 用户的表达方式是否符合其人格画像
 * 2. 情感表达深度 - 回复中的情感是否真实、深入
 * 3. 话题相关性 - 是否围绕有意义的话题展开
 * 4. 自我反思程度 - 是否展现出对自我的思考
 * 5. 价值观表达 - 是否体现了用户的核心价值观
 */
class PersonalityResonanceScorer(private val context: Context) {
    
    private val evaluationStorage by lazy { OnboardingEvaluationStorage(context) }
    
    companion object {
        private const val TAG = "ResonanceScorer"
        
        // 缓存最近的评分结果，避免重复请求
        private var lastUserMessage: String? = null
        private var lastScore: Int = 50
        private var lastScoreTime: Long = 0
        private const val CACHE_DURATION = 5000L // 5秒缓存
    }
    
    /**
     * 评估用户回复的人格共鸣度
     * 
     * 当前实现：基于规则的快速评估
     * 未来可扩展：接入 AI 进行深度评估
     * 
     * @param userMessage 用户的回复消息
     * @param aiQuestion 之前 AI 的提问（上下文）
     * @return 0-100 的共鸣评分
     */
    suspend fun evaluateResonance(
        userMessage: String,
        aiQuestion: String? = null
    ): ResonanceScore = withContext(Dispatchers.IO) {
        
        // 检查缓存
        if (userMessage == lastUserMessage && 
            System.currentTimeMillis() - lastScoreTime < CACHE_DURATION) {
            Timber.d("$TAG: 使用缓存评分: $lastScore")
            return@withContext ResonanceScore(lastScore, getGradeFromScore(lastScore), "缓存")
        }
        
        // 检查用户是否完成问卷
        val isOnboardingComplete = OnboardingState.isCompleted(context)
        if (!isOnboardingComplete) {
            Timber.d("$TAG: 用户尚未完成人格问卷，使用默认评分")
            return@withContext ResonanceScore(50, ResonanceGrade.B, "未完成问卷")
        }
        
        // 获取问卷可信度作为评分基准
        val evaluations = evaluationStorage.getAllEvaluations()
        val baseReliability = if (evaluations.isNotEmpty()) {
            evaluations.map { it.getOverallReliability() }.average().toFloat()
        } else {
            0.5f
        }
        
        // 基于规则进行快速评分
        val score = calculateScore(userMessage, baseReliability)
        val grade = getGradeFromScore(score)
        
        // 更新缓存
        lastUserMessage = userMessage
        lastScore = score
        lastScoreTime = System.currentTimeMillis()
        
        Timber.i("$TAG: 人格共鸣评分 = $score (${grade.displayName})")
        
        return@withContext ResonanceScore(score, grade, "规则评估")
    }
    
    /**
     * 快速评估（不调用 AI，基于规则）
     * 用于高频场景，减少 API 调用
     */
    fun quickEvaluate(userMessage: String): ResonanceScore {
        val trimmedMessage = userMessage.trim()
        
        // 基于消息长度和内容进行快速评估
        val score = when {
            // 太短的消息 -> C级
            trimmedMessage.length < 5 -> 30
            
            // 纯表情或符号 -> C级
            trimmedMessage.all { !it.isLetterOrDigit() } -> 25
            
            // 简单的"好"、"嗯"等 -> C级
            trimmedMessage in listOf("好", "嗯", "行", "ok", "OK", "哦", "啊", "呢") -> 35
            
            // 包含深度思考关键词 -> A/S级候选
            containsDeepThoughtKeywords(trimmedMessage) -> {
                when {
                    trimmedMessage.length > 100 -> 85 // 长回复 + 深度思考
                    trimmedMessage.length > 50 -> 75
                    else -> 65
                }
            }
            
            // 包含情感表达 -> B/A级
            containsEmotionalExpression(trimmedMessage) -> {
                when {
                    trimmedMessage.length > 80 -> 70
                    trimmedMessage.length > 40 -> 55
                    else -> 50
                }
            }
            
            // 普通长度回复 -> B级
            trimmedMessage.length > 20 -> 50
            
            // 其他 -> 低B级
            else -> 45
        }
        
        val grade = getGradeFromScore(score)
        Timber.d("$TAG: 快速评分 = $score (${grade.displayName})")
        
        return ResonanceScore(score, grade, "快速评估")
    }
    
    /**
     * 计算评分（基于规则）
     */
    private fun calculateScore(userMessage: String, baseReliability: Float): Int {
        val trimmedMessage = userMessage.trim()
        
        // 基础分（根据用户问卷可信度）
        val baseScore = (40 + baseReliability * 20).toInt() // 40-60
        
        // 长度加成
        val lengthBonus = when {
            trimmedMessage.length > 150 -> 15
            trimmedMessage.length > 80 -> 10
            trimmedMessage.length > 40 -> 5
            trimmedMessage.length < 10 -> -15
            else -> 0
        }
        
        // 深度思考加成
        val depthBonus = if (containsDeepThoughtKeywords(trimmedMessage)) {
            when {
                trimmedMessage.length > 100 -> 25
                trimmedMessage.length > 50 -> 15
                else -> 10
            }
        } else 0
        
        // 情感表达加成
        val emotionBonus = if (containsEmotionalExpression(trimmedMessage)) {
            when {
                trimmedMessage.length > 60 -> 10
                else -> 5
            }
        } else 0
        
        // 简短/无效回复惩罚
        val penalty = when {
            trimmedMessage.length < 5 -> -30
            trimmedMessage in listOf("好", "嗯", "行", "ok", "OK", "哦", "啊") -> -20
            trimmedMessage.all { !it.isLetterOrDigit() } -> -25
            else -> 0
        }
        
        // 计算最终分数（0-100）
        return (baseScore + lengthBonus + depthBonus + emotionBonus + penalty).coerceIn(0, 100)
    }
    
    /**
     * 检查是否包含深度思考关键词
     */
    private fun containsDeepThoughtKeywords(text: String): Boolean {
        val keywords = listOf(
            "觉得", "认为", "思考", "反思", "意识到", "感受",
            "理解", "发现", "意义", "价值", "成长", "改变",
            "体会", "领悟", "明白", "原来", "其实", "本质",
            "内心", "真正", "深刻", "重要", "影响", "决定"
        )
        return keywords.any { text.contains(it) }
    }
    
    /**
     * 检查是否包含情感表达
     */
    private fun containsEmotionalExpression(text: String): Boolean {
        val emotionalWords = listOf(
            "开心", "高兴", "难过", "伤心", "担心", "害怕",
            "期待", "兴奋", "感动", "温暖", "幸福", "感激",
            "爱", "喜欢", "讨厌", "生气", "焦虑", "平静",
            "满足", "遗憾", "希望", "失望", "惊喜", "安心"
        )
        return emotionalWords.any { text.contains(it) }
    }
    
    /**
     * 根据分数获取等级
     */
    private fun getGradeFromScore(score: Int): ResonanceGrade {
        return when {
            score >= 90 -> ResonanceGrade.S
            score >= 70 -> ResonanceGrade.A
            score >= 40 -> ResonanceGrade.B
            else -> ResonanceGrade.C
        }
    }
}

/**
 * 人格共鸣评分结果
 */
data class ResonanceScore(
    /** 评分 (0-100) */
    val score: Int,
    /** 等级 */
    val grade: ResonanceGrade,
    /** 评估说明 */
    val note: String
) {
    /** 是否触发 S 级灵魂共鸣特效 */
    val isSoulResonance: Boolean get() = grade == ResonanceGrade.S
    
    /** 获取奖励积分 */
    val bonusPoints: Int get() = grade.bonus
}
