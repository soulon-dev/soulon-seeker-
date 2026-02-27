package com.soulon.app.onboarding

import android.content.Context
import com.soulon.app.ai.QwenCloudManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 对话分析器
 * 
 * 功能：
 * 1. 分析用户对话内容与问卷答案的相关性
 * 2. 检测一致性和矛盾
 * 3. 自动更新问卷评估
 */
class ConversationAnalyzer(
    private val context: Context,
    private val qwenManager: QwenCloudManager
) {
    
    private val evaluationManager = OnboardingEvaluationManager(context)
    
    companion object {
        // 分析提示词
        private const val ANALYSIS_SYSTEM_PROMPT = """你是一个专业的对话分析助手。
你的任务是分析用户的新对话内容是否与之前问卷中的某个答案相关，以及是否一致。

分析时请考虑：
1. 语义相关性：新内容是否涉及问卷问题的主题
2. 一致性：新内容是否支持问卷答案
3. 矛盾性：新内容是否与问卷答案矛盾

请以 JSON 格式输出分析结果：
{
  "isRelated": true/false,
  "relatedQuestionIds": [1, 5, 8],
  "consistency": {
    "questionId": 1,
    "isConsistent": true/false,
    "confidence": 0.8,
    "reason": "分析理由"
  }
}"""
    }
    
    /**
     * 分析对话内容
     * 
     * @param userMessage 用户消息
     * @param aiResponse AI 回复
     * @param newMemoryId 新记忆 ID
     * @param questionnaireAnswers 问卷答案列表
     */
    suspend fun analyzeConversation(
        userMessage: String,
        aiResponse: String,
        newMemoryId: String?,
        questionnaireAnswers: List<Pair<Int, String>>  // (questionId, answer)
    ) = withContext(Dispatchers.IO) {
        try {
            Timber.i("开始分析对话与问卷的相关性...")
            
            // 构建分析提示
            val analysisPrompt = buildAnalysisPrompt(
                userMessage = userMessage,
                questionnaireAnswers = questionnaireAnswers
            )
            
            // 调用 AI 分析（收集流式输出）
            val analysisBuilder = StringBuilder()
            qwenManager.generateStream(
                prompt = analysisPrompt,
                systemPrompt = ANALYSIS_SYSTEM_PROMPT,
                maxNewTokens = 350,
                functionType = "analysis"
            ).collect { token ->
                analysisBuilder.append(token)
            }
            val analysisResult = analysisBuilder.toString()
            
            // 解析分析结果
            val analysis = parseAnalysisResult(analysisResult)
            
            // 更新相关问题的评估
            if (analysis.isRelated && newMemoryId != null) {
                analysis.consistencyChecks.forEach { check ->
                    evaluationManager.updateEvaluationFromConversation(
                        questionId = check.questionId,
                        newMemoryId = newMemoryId,
                        isConsistent = check.isConsistent,
                        aiAnalysis = check.reason
                    )
                    
                    Timber.d(
                        "问题 ${check.questionId} 评估更新：" +
                        "${if (check.isConsistent) "一致" else "矛盾"} (置信度: ${check.confidence})"
                    )
                }
            }
            
            Timber.i("对话分析完成：${analysis.consistencyChecks.size} 个相关问题")
            
        } catch (e: Exception) {
            Timber.e(e, "对话分析失败")
        }
    }
    
    /**
     * 构建分析提示
     */
    private fun buildAnalysisPrompt(
        userMessage: String,
        questionnaireAnswers: List<Pair<Int, String>>
    ): String {
        val promptBuilder = StringBuilder()
        
        promptBuilder.append("【用户新对话】\n")
        promptBuilder.append(userMessage)
        promptBuilder.append("\n\n")
        
        promptBuilder.append("【问卷答案参考】\n")
        questionnaireAnswers.forEachIndexed { index, (questionId, answer) ->
            promptBuilder.append("问题 $questionId: ${answer.take(100)}...\n")
        }
        promptBuilder.append("\n")
        
        promptBuilder.append("请分析这段新对话是否与任何问卷答案相关，以及是否一致。")
        
        return promptBuilder.toString()
    }
    
    /**
     * 解析分析结果
     */
    private fun parseAnalysisResult(result: String): AnalysisResult {
        // 简化版解析（实际应使用 JSON 解析）
        // 这里使用关键词匹配作为后备方案
        
        val isRelated = result.contains("相关", ignoreCase = true) || 
                       result.contains("related", ignoreCase = true)
        
        val consistencyChecks = mutableListOf<ConsistencyCheck>()
        
        // 提取问题 ID 和一致性判断
        val questionIdRegex = Regex("问题\\s*(\\d+)")
        val consistentRegex = Regex("(一致|支持|符合|consistent)")
        val inconsistentRegex = Regex("(矛盾|冲突|不符|inconsistent)")
        
        questionIdRegex.findAll(result).forEach { match ->
            val questionId = match.groupValues[1].toIntOrNull() ?: return@forEach
            val context = result.substring(
                maxOf(0, match.range.first - 50),
                minOf(result.length, match.range.last + 50)
            )
            
            val isConsistent = when {
                consistentRegex.containsMatchIn(context) -> true
                inconsistentRegex.containsMatchIn(context) -> false
                else -> true  // 默认一致
            }
            
            consistencyChecks.add(
                ConsistencyCheck(
                    questionId = questionId,
                    isConsistent = isConsistent,
                    confidence = 0.7f,
                    reason = context.trim()
                )
            )
        }
        
        return AnalysisResult(
            isRelated = isRelated,
            consistencyChecks = consistencyChecks
        )
    }
}

/**
 * 分析结果
 */
data class AnalysisResult(
    val isRelated: Boolean,
    val consistencyChecks: List<ConsistencyCheck>
)

/**
 * 一致性检查
 */
data class ConsistencyCheck(
    val questionId: Int,
    val isConsistent: Boolean,
    val confidence: Float,
    val reason: String
)
