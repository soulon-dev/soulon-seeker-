package com.soulon.app.onboarding

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 问卷评估系统
 * 
 * 功能：
 * 1. 评估每个问卷答案的真诚度（Sincerity）和置信度（Confidence）
 * 2. 在持续对话中动态更新评分
 * 3. 关联新记忆验证问卷答案
 * 4. 影响 MEMO 积分和等级升级
 */

/**
 * 问卷答案评估
 */
data class OnboardingEvaluation(
    val questionId: Int,
    
    /** 真诚度评分 (0.0 - 1.0) */
    val sincerityScore: Float = 0.5f,
    
    /** 置信度评分 (0.0 - 1.0) */
    val confidenceScore: Float = 0.5f,
    
    /** 原始答案 */
    val originalAnswer: String,
    
    /** 相关记忆 ID 列表 */
    val relatedMemoryIds: String = "",  // 逗号分隔的 ID
    
    /** 验证次数（通过对话验证的次数） */
    val verificationCount: Int = 0,
    
    /** 矛盾次数（发现与答案矛盾的次数） */
    val contradictionCount: Int = 0,
    
    /** 最后更新时间 */
    val lastUpdated: Long = System.currentTimeMillis(),
    
    /** 评估备注（AI 的分析理由） */
    val evaluationNotes: String = ""
) {
    /**
     * 计算综合可信度
     * 考虑真诚度、置信度、验证次数、矛盾次数
     */
    fun getOverallReliability(): Float {
        val verificationBonus = (verificationCount * 0.05f).coerceAtMost(0.3f)
        val contradictionPenalty = (contradictionCount * 0.1f).coerceAtMost(0.5f)
        
        val baseScore = (sincerityScore + confidenceScore) / 2f
        val adjustedScore = baseScore + verificationBonus - contradictionPenalty
        
        return adjustedScore.coerceIn(0f, 1f)
    }
    
    /**
     * 获取相关记忆 ID 列表
     */
    fun getRelatedMemories(): List<String> {
        return if (relatedMemoryIds.isBlank()) {
            emptyList()
        } else {
            relatedMemoryIds.split(",").map { it.trim() }
        }
    }
    
    /**
     * 添加相关记忆
     */
    fun addRelatedMemory(memoryId: String): OnboardingEvaluation {
        val currentIds = getRelatedMemories().toMutableList()
        if (!currentIds.contains(memoryId)) {
            currentIds.add(memoryId)
        }
        return copy(
            relatedMemoryIds = currentIds.joinToString(","),
            lastUpdated = System.currentTimeMillis()
        )
    }
}

/**
 * 评估存储（使用 SharedPreferences 简化实现）
 */
class OnboardingEvaluationStorage(private val context: Context) {
    
    private val prefs = com.soulon.app.wallet.WalletScope.scopedPrefs(context, "onboarding_evaluations")
    private val gson = com.google.gson.Gson()
    
    fun saveEvaluation(evaluation: OnboardingEvaluation) {
        val json = gson.toJson(evaluation)
        prefs.edit().putString("eval_${evaluation.questionId}", json).apply()
    }
    
    fun getEvaluation(questionId: Int): OnboardingEvaluation? {
        val json = prefs.getString("eval_$questionId", null) ?: return null
        return try {
            gson.fromJson(json, OnboardingEvaluation::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    fun getAllEvaluations(): List<OnboardingEvaluation> {
        return prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith("eval_") && value is String) {
                try {
                    gson.fromJson(value, OnboardingEvaluation::class.java)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }
    
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

/**
 * 评估管理器
 */
class OnboardingEvaluationManager(private val context: Context) {
    
    private val storage = OnboardingEvaluationStorage(context)
    
    companion object {
        // 评分阈值
        private const val HIGH_RELIABILITY_THRESHOLD = 0.75f
        private const val MEDIUM_RELIABILITY_THRESHOLD = 0.5f
        
        // MEMO 奖励
        private const val HIGH_RELIABILITY_BONUS = 100  // 高可信度奖励
        private const val MEDIUM_RELIABILITY_BONUS = 50  // 中等可信度奖励
        
        // 升级影响权重
        const val RELIABILITY_WEIGHT_IN_TIER_CALCULATION = 0.2f  // 20% 权重
    }
    
    /**
     * 初始化评估（问卷完成时调用）
     */
    suspend fun initializeEvaluations(answers: List<OnboardingAnswer>) = withContext(Dispatchers.IO) {
        Timber.i("初始化 ${answers.size} 个问卷答案的评估")
        
        answers.forEach { answer ->
            val evaluation = OnboardingEvaluation(
                questionId = answer.questionId,
                sincerityScore = 0.5f,  // 初始中等真诚度
                confidenceScore = 0.5f,  // 初始中等置信度
                originalAnswer = answer.answer
            )
            storage.saveEvaluation(evaluation)
        }
        
        Timber.i("评估初始化完成")
    }
    
    /**
     * 更新评估（在对话中发现相关信息时调用）
     */
    suspend fun updateEvaluationFromConversation(
        questionId: Int,
        newMemoryId: String,
        isConsistent: Boolean,  // 新信息是否与问卷一致
        aiAnalysis: String = ""
    ) = withContext(Dispatchers.IO) {
        val evaluation = storage.getEvaluation(questionId) ?: return@withContext
        
        val updatedEvaluation = if (isConsistent) {
            // 信息一致：提升真诚度和置信度
            evaluation.copy(
                sincerityScore = (evaluation.sincerityScore + 0.05f).coerceAtMost(1.0f),
                confidenceScore = (evaluation.confidenceScore + 0.08f).coerceAtMost(1.0f),
                verificationCount = evaluation.verificationCount + 1,
                evaluationNotes = "${evaluation.evaluationNotes}\n[验证] $aiAnalysis",
                lastUpdated = System.currentTimeMillis()
            ).addRelatedMemory(newMemoryId)
        } else {
            // 信息矛盾：降低真诚度和置信度
            evaluation.copy(
                sincerityScore = (evaluation.sincerityScore - 0.1f).coerceAtLeast(0.0f),
                confidenceScore = (evaluation.confidenceScore - 0.05f).coerceAtLeast(0.0f),
                contradictionCount = evaluation.contradictionCount + 1,
                evaluationNotes = "${evaluation.evaluationNotes}\n[矛盾] $aiAnalysis",
                lastUpdated = System.currentTimeMillis()
            ).addRelatedMemory(newMemoryId)
        }
        
        storage.saveEvaluation(updatedEvaluation)
        
        Timber.i(
            "问题 $questionId 评估已更新：" +
            "真诚度=${updatedEvaluation.sincerityScore}, " +
            "置信度=${updatedEvaluation.confidenceScore}, " +
            "可信度=${updatedEvaluation.getOverallReliability()}"
        )
    }
    
    /**
     * 获取整体评估报告
     */
    suspend fun getOverallReport(): EvaluationReport = withContext(Dispatchers.IO) {
        val evaluations = storage.getAllEvaluations()
        
        if (evaluations.isEmpty()) {
            return@withContext EvaluationReport()
        }
        
        val avgSincerity = evaluations.map { it.sincerityScore }.average().toFloat()
        val avgConfidence = evaluations.map { it.confidenceScore }.average().toFloat()
        val avgReliability = evaluations.map { it.getOverallReliability() }.average().toFloat()
        
        val totalVerifications = evaluations.sumOf { it.verificationCount }
        val totalContradictions = evaluations.sumOf { it.contradictionCount }
        
        val highReliabilityCount = evaluations.count { it.getOverallReliability() >= HIGH_RELIABILITY_THRESHOLD }
        val mediumReliabilityCount = evaluations.count { 
            it.getOverallReliability() >= MEDIUM_RELIABILITY_THRESHOLD && 
            it.getOverallReliability() < HIGH_RELIABILITY_THRESHOLD 
        }
        val lowReliabilityCount = evaluations.size - highReliabilityCount - mediumReliabilityCount
        
        EvaluationReport(
            totalQuestions = evaluations.size,
            averageSincerity = avgSincerity,
            averageConfidence = avgConfidence,
            overallReliability = avgReliability,
            totalVerifications = totalVerifications,
            totalContradictions = totalContradictions,
            highReliabilityCount = highReliabilityCount,
            mediumReliabilityCount = mediumReliabilityCount,
            lowReliabilityCount = lowReliabilityCount
        )
    }
    
    /**
     * 计算可信度对 MEMO 积分的奖励
     */
    suspend fun calculateReliabilityBonus(): Int = withContext(Dispatchers.IO) {
        val report = getOverallReport()
        
        val bonus = when {
            report.overallReliability >= HIGH_RELIABILITY_THRESHOLD -> {
                HIGH_RELIABILITY_BONUS * report.totalQuestions / 20  // 按比例计算
            }
            report.overallReliability >= MEDIUM_RELIABILITY_THRESHOLD -> {
                MEDIUM_RELIABILITY_BONUS * report.totalQuestions / 20
            }
            else -> 0
        }
        
        Timber.i("可信度奖励：$bonus MEMO (基于 ${report.totalQuestions} 个问题)")
        return@withContext bonus
    }
    
    /**
     * 获取可信度对等级升级的影响系数
     */
    suspend fun getReliabilityMultiplier(): Float = withContext(Dispatchers.IO) {
        val report = getOverallReport()
        
        // 可信度影响系数：0.8 - 1.2
        val multiplier = 0.8f + (report.overallReliability * 0.4f)
        
        Timber.d("可信度系数：$multiplier (基于可信度 ${report.overallReliability})")
        return@withContext multiplier
    }
}

/**
 * 评估报告
 */
data class EvaluationReport(
    val totalQuestions: Int = 0,
    val averageSincerity: Float = 0f,
    val averageConfidence: Float = 0f,
    val overallReliability: Float = 0f,
    val totalVerifications: Int = 0,
    val totalContradictions: Int = 0,
    val highReliabilityCount: Int = 0,
    val mediumReliabilityCount: Int = 0,
    val lowReliabilityCount: Int = 0
) {
    /**
     * 获取评估等级
     */
    fun getReliabilityGrade(): String {
        return when {
            overallReliability >= 0.85f -> "优秀"
            overallReliability >= 0.70f -> "良好"
            overallReliability >= 0.50f -> "中等"
            else -> "需改进"
        }
    }
    
    /**
     * 获取详细说明
     */
    fun getDescription(): String {
        return """
            整体评估：${getReliabilityGrade()}
            
            平均真诚度：${(averageSincerity * 100).toInt()}%
            平均置信度：${(averageConfidence * 100).toInt()}%
            整体可信度：${(overallReliability * 100).toInt()}%
            
            已验证答案：$highReliabilityCount 个
            待验证答案：$mediumReliabilityCount 个
            可疑答案：$lowReliabilityCount 个
            
            验证次数：$totalVerifications 次
            矛盾次数：$totalContradictions 次
        """.trimIndent()
    }
}
