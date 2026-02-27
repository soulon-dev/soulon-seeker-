package com.soulon.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * 人格相关性分析器
 * 
 * 通过 AI 分析对话内容是否涉及用户人格特征，
 * 用于决定对话是否需要加密上传到 Irys
 * 
 * 分析维度：
 * - 个人偏好（喜好、厌恶）
 * - 性格特征（内向/外向、谨慎/冒险等）
 * - 价值观（重要性排序、道德判断）
 * - 情感倾向（情绪表达、情感反应）
 * - 行为模式（习惯、决策方式）
 */
class PersonaRelevanceAnalyzer(
    private val context: Context,
    private val qwenManager: QwenCloudManager
) {
    companion object {
        // 相关度阈值
        private const val RELEVANCE_THRESHOLD = 0.6f
        private const val UPDATE_THRESHOLD = 0.75f
        
        // 分析系统提示词
        private val ANALYSIS_SYSTEM_PROMPT = """
你是一个人格特征分析专家。你的任务是分析对话内容是否包含可用于理解用户人格的信息。

人格相关的内容包括：
1. **个人偏好**：喜欢什么、讨厌什么、偏好选择
2. **性格特征**：内向/外向、谨慎/冒险、理性/感性等
3. **价值观**：什么对用户重要、道德判断、人生态度
4. **情感表达**：情绪反应、情感倾向、压力应对
5. **行为模式**：决策方式、习惯、社交风格

人格无关的内容：
- 纯知识问答（如"什么是光合作用"）
- 技术咨询（如"如何修复这个bug"）
- 客观事实查询（如"今天天气怎么样"）
- 通用任务请求（如"帮我翻译这段话"）

请严格按照 JSON 格式返回分析结果，不要有任何其他文字。
""".trimIndent()
    }
    
    /**
     * 对话分析结果
     */
    data class ConversationAnalysisResult(
        val isPersonaRelevant: Boolean,      // 是否涉及人格
        val relevanceScore: Float,           // 相关度分数 0.0-1.0
        val detectedTraits: List<String>,    // 检测到的特质类型
        val shouldUpdatePersona: Boolean,    // 是否应更新人格画像
        val reason: String,                  // 分析原因
        val oceanImpact: OceanImpact?        // 对 OCEAN 五维的潜在影响
    ) {
        companion object {
            fun notRelevant(reason: String = "普通对话，不涉及人格特征") = ConversationAnalysisResult(
                isPersonaRelevant = false,
                relevanceScore = 0f,
                detectedTraits = emptyList(),
                shouldUpdatePersona = false,
                reason = reason,
                oceanImpact = null
            )
            
            fun error(message: String) = ConversationAnalysisResult(
                isPersonaRelevant = false,
                relevanceScore = 0f,
                detectedTraits = emptyList(),
                shouldUpdatePersona = false,
                reason = "分析失败: $message",
                oceanImpact = null
            )
        }
    }
    
    /**
     * OCEAN 五维影响评估
     */
    data class OceanImpact(
        val openness: Float?,           // 开放性影响 (-1.0 到 1.0)
        val conscientiousness: Float?,  // 尽责性影响
        val extraversion: Float?,       // 外向性影响
        val agreeableness: Float?,      // 宜人性影响
        val neuroticism: Float?         // 神经质影响
    )
    
    /**
     * 分析对话是否涉及人格
     * 
     * @param userMessage 用户消息
     * @param aiResponse AI 回复
     * @param retrievedMemories 检索到的记忆（可选）
     * @return 分析结果
     */
    suspend fun analyze(
        userMessage: String,
        aiResponse: String,
        retrievedMemories: List<String> = emptyList()
    ): ConversationAnalysisResult = withContext(Dispatchers.IO) {
        try {
            Timber.d("🔍 开始分析对话人格相关性...")
            Timber.d("   用户消息: ${userMessage.take(50)}...")
            
            // 构建分析提示词
            val analysisPrompt = buildAnalysisPrompt(userMessage, aiResponse, retrievedMemories)
            
            // 调用 AI 分析
            val response = StringBuilder()
            qwenManager.generateStream(
                prompt = analysisPrompt,
                systemPrompt = ANALYSIS_SYSTEM_PROMPT,
                maxNewTokens = 300,
                functionType = "analysis"
            ).collect { token ->
                response.append(token)
            }
            
            // 解析结果
            val result = parseAnalysisResult(response.toString())
            
            Timber.i("📊 人格相关性分析完成:")
            Timber.i("   - 是否相关: ${result.isPersonaRelevant}")
            Timber.i("   - 相关度: ${(result.relevanceScore * 100).toInt()}%")
            Timber.i("   - 检测特质: ${result.detectedTraits.joinToString()}")
            Timber.i("   - 原因: ${result.reason}")
            
            result
            
        } catch (e: Exception) {
            Timber.e(e, "人格相关性分析失败")
            ConversationAnalysisResult.error(e.message ?: "未知错误")
        }
    }
    
    /**
     * 快速检查（基于关键词，不调用 AI）
     * 用于初步筛选，减少 API 调用
     */
    fun quickCheck(userMessage: String): Boolean {
        val personaKeywords = listOf(
            // 偏好相关
            "喜欢", "讨厌", "爱", "恨", "偏好", "最爱", "不喜欢",
            "prefer", "like", "love", "hate", "favorite",
            // 性格相关
            "我觉得", "我认为", "我的想法", "对我来说", "我通常",
            "我是那种", "我的性格", "我比较",
            "I think", "I feel", "I believe", "personally",
            // 情感相关
            "开心", "难过", "焦虑", "担心", "害怕", "兴奋",
            "happy", "sad", "anxious", "worried", "excited",
            // 价值观相关
            "重要", "意义", "价值", "应该", "必须",
            "important", "meaningful", "should", "must",
            // 行为模式
            "习惯", "总是", "从不", "经常", "很少",
            "always", "never", "usually", "often", "rarely"
        )
        
        val lowerMessage = userMessage.lowercase()
        return personaKeywords.any { keyword -> 
            lowerMessage.contains(keyword.lowercase()) 
        }
    }
    
    /**
     * 构建分析提示词
     */
    private fun buildAnalysisPrompt(
        userMessage: String,
        aiResponse: String,
        retrievedMemories: List<String>
    ): String {
        val memoryContext = if (retrievedMemories.isNotEmpty()) {
            "\n\n【相关记忆上下文】\n${retrievedMemories.joinToString("\n")}"
        } else ""
        
        return """
请分析以下对话是否涉及用户人格特征：

【用户消息】
$userMessage

【AI回复】
$aiResponse
$memoryContext

请返回 JSON 格式的分析结果：
```json
{
    "isPersonaRelevant": true/false,
    "relevanceScore": 0.0-1.0,
    "detectedTraits": ["偏好", "性格", "价值观", "情感", "行为模式"],
    "shouldUpdatePersona": true/false,
    "reason": "分析原因说明",
    "oceanImpact": {
        "openness": null或-1.0到1.0,
        "conscientiousness": null或-1.0到1.0,
        "extraversion": null或-1.0到1.0,
        "agreeableness": null或-1.0到1.0,
        "neuroticism": null或-1.0到1.0
    }
}
```

只返回 JSON，不要有其他文字。
""".trimIndent()
    }
    
    /**
     * 解析 AI 分析结果
     */
    private fun parseAnalysisResult(response: String): ConversationAnalysisResult {
        try {
            // 提取 JSON 部分
            val jsonStr = extractJson(response)
            val json = JSONObject(jsonStr)
            
            val isRelevant = json.optBoolean("isPersonaRelevant", false)
            val score = json.optDouble("relevanceScore", 0.0).toFloat()
            val reason = json.optString("reason", "")
            
            // 解析检测到的特质
            val traitsArray = json.optJSONArray("detectedTraits")
            val traits = mutableListOf<String>()
            if (traitsArray != null) {
                for (i in 0 until traitsArray.length()) {
                    traits.add(traitsArray.getString(i))
                }
            }
            
            // 解析 OCEAN 影响
            val oceanJson = json.optJSONObject("oceanImpact")
            val oceanImpact = if (oceanJson != null) {
                OceanImpact(
                    openness = oceanJson.optDoubleOrNull("openness"),
                    conscientiousness = oceanJson.optDoubleOrNull("conscientiousness"),
                    extraversion = oceanJson.optDoubleOrNull("extraversion"),
                    agreeableness = oceanJson.optDoubleOrNull("agreeableness"),
                    neuroticism = oceanJson.optDoubleOrNull("neuroticism")
                )
            } else null
            
            // 判断是否应更新人格
            val shouldUpdate = isRelevant && score >= UPDATE_THRESHOLD
            
            return ConversationAnalysisResult(
                isPersonaRelevant = isRelevant && score >= RELEVANCE_THRESHOLD,
                relevanceScore = score,
                detectedTraits = traits,
                shouldUpdatePersona = shouldUpdate,
                reason = reason,
                oceanImpact = oceanImpact
            )
            
        } catch (e: Exception) {
            Timber.e(e, "解析分析结果失败: $response")
            return ConversationAnalysisResult.notRelevant("解析失败")
        }
    }
    
    /**
     * 从响应中提取 JSON
     */
    private fun extractJson(response: String): String {
        // 尝试找到 JSON 块
        val jsonPattern = Regex("""\{[\s\S]*\}""")
        val match = jsonPattern.find(response)
        return match?.value ?: response
    }
    
    /**
     * JSONObject 扩展：安全获取可空 Double
     */
    private fun JSONObject.optDoubleOrNull(key: String): Float? {
        return if (this.isNull(key)) null else this.optDouble(key).toFloat()
    }
}
