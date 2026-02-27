package com.soulon.app.persona

import android.content.Context
import com.soulon.app.ai.QwenCloudManager
import com.soulon.app.config.RemoteConfigManager
import com.soulon.app.rewards.EvidenceSourceType
import com.soulon.app.rewards.PersonaData
import com.soulon.app.rewards.RewardsDatabase
import com.soulon.app.rewards.UserProfile
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * 人格提取器
 * 
 * 功能：
 * - 从用户的文本中提取 OCEAN 人格特征
 * - 使用 Qwen AI 进行智能分析
 * - 自动更新用户档案
 * - 计算人格同步率
 * 
 * Phase 3 Week 2: Task_Persona_Analysis
 */
class PersonaExtractor(
    private val context: Context,
    private val qwenManager: QwenCloudManager
) {
    
    private val database = RewardsDatabase.getInstance(context)
    private val dao = database.rewardsDao()
    
    companion object {
        private const val MIN_TEXT_LENGTH = 20 // 最少文本长度（降低以适应单选题答案）
        private const val MIN_SAMPLE_SIZE = 5 // 最少样本数（问卷有20题，至少需要5条）
        private const val DEFAULT_USER_ID = "default_user"
    }
    
    /**
     * 从文本列表中提取人格特征
     * 
     * @param texts 用户的文本列表（如记忆内容）
     * @return 提取的人格数据，失败返回 null
     */
    suspend fun extractPersona(texts: List<String>): PersonaExtractionResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("开始提取人格特征，样本数: ${texts.size}")
            
            // 验证输入
            Timber.d("📝 输入文本统计:")
            Timber.d("  - 总文本数: ${texts.size}")
            texts.forEachIndexed { index, text ->
                Timber.d("  - 文本 $index: 长度=${text.length}, 内容前50字: ${text.take(50)}...")
            }
            
            if (texts.isEmpty()) {
                Timber.e("❌ 没有可分析的文本")
                return@withContext PersonaExtractionResult.Error("没有可分析的文本")
            }
            
            val validTexts = texts.filter { it.length >= MIN_TEXT_LENGTH }
            Timber.d("  - 有效文本数 (长度>=$MIN_TEXT_LENGTH): ${validTexts.size}")
            
            if (validTexts.size < MIN_SAMPLE_SIZE) {
                Timber.e("❌ 有效文本样本不足: ${validTexts.size} < $MIN_SAMPLE_SIZE")
                return@withContext PersonaExtractionResult.Error("文本样本不足（有效: ${validTexts.size} 条，需要: $MIN_SAMPLE_SIZE 条，每条至少 $MIN_TEXT_LENGTH 字符）")
            }
            
            // 合并文本（限制总长度避免超出 API 限制）
            val combinedText = validTexts.take(10).joinToString("\n\n---\n\n")
            
            // 调用 AI 分析
            val analysisPrompt = buildAnalysisPrompt(combinedText, validTexts.size)
            
            Timber.d("调用 Qwen AI 进行人格分析...")
            
            // 使用 generateStream 收集完整响应
            val responseBuilder = StringBuilder()
            qwenManager.generateStream(
                prompt = analysisPrompt,
                systemPrompt = OceanPrompts.PERSONA_ANALYSIS_SYSTEM_PROMPT,
                maxNewTokens = 450,
                functionType = "persona"
            ).collect { token ->
                responseBuilder.append(token)
            }
            val response = responseBuilder.toString()
            
            // 解析响应
            val personaData = parsePersonaResponse(response, validTexts.size)
            
            if (personaData == null) {
                return@withContext PersonaExtractionResult.Error("无法解析 AI 响应")
            }
            
            // 保存到数据库
            val syncRate = calculateSyncRate(personaData, validTexts.size)
            val profile = dao.getUserProfile(DEFAULT_USER_ID)
            val remoteConfig = RemoteConfigManager.getInstance(context)
            val enableV2 = remoteConfig.getBoolean("persona.v2.enabled", true)
            if (profile != null) {
                val updatedAt = System.currentTimeMillis()
                val updatedProfile = profile.copy(
                    personaData = personaData,
                    personaProfileV2 = if (enableV2) {
                        PersonaProfileUpdateEngine.updateFromPointEstimate(
                            existing = profile.personaProfileV2,
                            estimate = personaData.copy(analyzedAt = updatedAt),
                            timestamp = updatedAt,
                            sourceType = EvidenceSourceType.ONBOARDING
                        )
                    } else {
                        profile.personaProfileV2
                    },
                    lastPersonaAnalysis = updatedAt,
                    personaSyncRate = syncRate,
                    lastActiveAt = updatedAt
                )
                dao.updateUserProfile(updatedProfile)

                if (enableV2 && updatedProfile.personaProfileV2 != null) {
                    val wallet = WalletScope.currentWalletAddress(context)
                    if (!wallet.isNullOrBlank()) {
                        com.soulon.app.rewards.RewardsRepository(context).syncPersonaProfileV2ToBackend(wallet, updatedProfile.personaProfileV2)
                    }
                }
            } else {
                val updatedAt = System.currentTimeMillis()
                val baseProfile = UserProfile(userId = DEFAULT_USER_ID)
                val personaProfileV2 = if (enableV2) {
                    PersonaProfileUpdateEngine.updateFromPointEstimate(
                        existing = null,
                        estimate = personaData.copy(analyzedAt = updatedAt),
                        timestamp = updatedAt,
                        sourceType = EvidenceSourceType.ONBOARDING
                    )
                } else {
                    null
                }
                dao.insertOrUpdateUserProfile(
                    baseProfile.copy(
                        personaData = personaData,
                        personaProfileV2 = personaProfileV2,
                        lastPersonaAnalysis = updatedAt,
                        personaSyncRate = syncRate,
                        lastActiveAt = updatedAt
                    )
                )

                if (enableV2 && personaProfileV2 != null) {
                    val wallet = WalletScope.currentWalletAddress(context)
                    if (!wallet.isNullOrBlank()) {
                        com.soulon.app.rewards.RewardsRepository(context).syncPersonaProfileV2ToBackend(wallet, personaProfileV2)
                    }
                }
            }
            
            Timber.i("人格提取成功: ${personaData}")
            Timber.i("人格同步率: ${(syncRate * 100).toInt()}%")
            PersonaTelemetry.increment(context, PersonaTelemetry.KEY_PERSONA_UPDATE_SUCCESS)
            
            return@withContext PersonaExtractionResult.Success(personaData, syncRate)
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "人格提取失败")
            PersonaTelemetry.increment(context, PersonaTelemetry.KEY_PERSONA_UPDATE_FAILURE)
            return@withContext PersonaExtractionResult.Error("提取失败: ${e.message}")
        }
    }
    
    /**
     * 构建分析提示词
     */
    private fun buildAnalysisPrompt(text: String, sampleSize: Int): String {
        return """请分析以下用户文本，提取 OCEAN 人格特征。

**用户文本** (共 $sampleSize 条样本):

$text

---

请严格按照 JSON 格式输出分析结果。"""
    }
    
    /**
     * 解析 AI 响应
     */
    private fun parsePersonaResponse(response: String, sampleSize: Int): PersonaData? {
        try {
            // 尝试提取 JSON（可能包含在 ```json ``` 代码块中）
            val jsonText = extractJson(response)
            val json = JSONObject(jsonText)
            
            return PersonaData(
                openness = json.optDouble("openness", 0.5).toFloat().coerceIn(0f, 1f),
                conscientiousness = json.optDouble("conscientiousness", 0.5).toFloat().coerceIn(0f, 1f),
                extraversion = json.optDouble("extraversion", 0.5).toFloat().coerceIn(0f, 1f),
                agreeableness = json.optDouble("agreeableness", 0.5).toFloat().coerceIn(0f, 1f),
                neuroticism = json.optDouble("neuroticism", 0.5).toFloat().coerceIn(0f, 1f),
                analyzedAt = System.currentTimeMillis(),
                sampleSize = sampleSize
            )
        } catch (e: Exception) {
            Timber.e(e, "解析人格响应失败: $response")
            return null
        }
    }
    
    /**
     * 提取 JSON 字符串（处理代码块）
     */
    private fun extractJson(text: String): String {
        // 如果包含 ```json ... ``` 代码块，提取内容
        val jsonBlockRegex = "```json\\s*([\\s\\S]*?)\\s*```".toRegex()
        val match = jsonBlockRegex.find(text)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        
        // 如果包含 ``` ... ``` 代码块（不带 json 标记）
        val codeBlockRegex = "```\\s*([\\s\\S]*?)\\s*```".toRegex()
        val codeMatch = codeBlockRegex.find(text)
        if (codeMatch != null) {
            return codeMatch.groupValues[1].trim()
        }
        
        // 直接返回原文本
        return text.trim()
    }
    
    /**
     * 计算人格同步率
     * 
     * 基于样本数量和数据完整性
     */
    private fun calculateSyncRate(personaData: PersonaData, sampleSize: Int): Float {
        // 基础同步率（基于样本数量）
        val sampleScore = when {
            sampleSize >= 20 -> 1.0f
            sampleSize >= 10 -> 0.8f
            sampleSize >= 5 -> 0.6f
            else -> 0.4f
        }
        
        // 数据完整性（检查是否所有维度都不是默认值 0.5）
        val values = listOf(
            personaData.openness,
            personaData.conscientiousness,
            personaData.extraversion,
            personaData.agreeableness,
            personaData.neuroticism
        )
        
        val nonDefaultCount = values.count { kotlin.math.abs(it - 0.5f) > 0.1f }
        val completenessScore = nonDefaultCount / 5.0f
        
        // 综合评分
        return ((sampleScore * 0.6f) + (completenessScore * 0.4f)).coerceIn(0f, 1f)
    }
    
    /**
     * 比较两个人格数据，计算相似度
     */
    fun calculateSimilarity(persona1: PersonaData, persona2: PersonaData): Float {
        return persona1.similarity(persona2)
    }
    
    /**
     * 获取当前用户的人格数据
     */
    suspend fun getCurrentPersona(): PersonaData? = withContext(Dispatchers.IO) {
        val profile = dao.getUserProfile(DEFAULT_USER_ID)
        return@withContext profile?.personaData
    }
}

/**
 * 人格提取结果
 */
sealed class PersonaExtractionResult {
    data class Success(
        val personaData: PersonaData,
        val syncRate: Float
    ) : PersonaExtractionResult()
    
    data class Error(
        val message: String
    ) : PersonaExtractionResult()
}
