package com.soulon.app.rag

import android.content.Context
import com.soulon.app.ai.QwenCloudManager
import com.soulon.app.persona.OceanPrompts
import com.soulon.app.persona.PersonaExtractor
import com.soulon.app.rewards.PersonaData
import com.soulon.app.rewards.RewardsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 人格化 RAG (检索增强生成)
 * 
 * 核心功能：
 * 1. 检索相关记忆
 * 2. 注入人格标签
 * 3. 生成个性化回答
 * 
 * Phase 3 Week 3: Task_RAG_Vector
 */
class PersonalizedRAG(private val context: Context) {
    
    private val searchEngine = SemanticSearchEngine(context)
    private val cloudRepo = com.soulon.app.data.CloudDataRepository.getInstance(context)
    private val rewardsRepository = RewardsRepository(context)
    
    // 延迟初始化，从后端获取 API 密钥
    private var qwenManager: QwenCloudManager? = null
    private var personaExtractor: PersonaExtractor? = null
    
    private var isInitialized = false
    
    companion object {
        private const val DEFAULT_TOP_K = 3
        private const val DEFAULT_SIMILARITY_THRESHOLD = 0.7f
        private const val MAX_CONTEXT_LENGTH = 2000 // 最大上下文长度（字符）
    }
    
    /**
     * 初始化 RAG 系统
     * 必须在使用前调用
     * 
     * @throws IllegalStateException 如果后端未配置 AI API 密钥
     */
    suspend fun initialize() {
        if (isInitialized && qwenManager != null) {
            Timber.d("PersonalizedRAG 已初始化，跳过")
            return
        }
        
        Timber.i("初始化 PersonalizedRAG...")

        qwenManager = QwenCloudManager(context)
        qwenManager!!.initialize()
        
        personaExtractor = PersonaExtractor(context, qwenManager!!)
        
        isInitialized = true
        Timber.i("PersonalizedRAG 初始化完成")
    }
    
    /**
     * 检查 AI 服务是否已配置
     */
    fun isAiServiceConfigured(): Boolean = cloudRepo.isAiServiceConfigured()
    
    /**
     * 获取 AI 配置状态描述
     */
    fun getAiConfigStatus(): String = cloudRepo.getAiConfigStatus()
    
    /**
     * 人格化对话
     * 
     * @param userQuery 用户查询
     * @param memoryContents 记忆ID到内容的映射（用于获取检索结果的原文）
     * @param usePersona 是否使用人格化
     * @param topK 检索前 K 个记忆
     * @param similarityThreshold 相似度阈值
     * @return 流式响应
     */
    suspend fun chat(
        userQuery: String,
        memoryContents: Map<String, String>,
        usePersona: Boolean = true,
        topK: Int = DEFAULT_TOP_K,
        similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD
    ): RAGResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("开始 RAG 对话: $userQuery (persona=$usePersona)")
            
            // Step 1: 语义检索相关记忆
            val searchResults = searchEngine.search(userQuery, topK, similarityThreshold)
            
            val retrievedMemories = when (searchResults) {
                is SearchResults.Success -> {
                    Timber.d("检索到 ${searchResults.resultCount} 条相关记忆")
                    searchResults.results
                }
                is SearchResults.Empty -> {
                    Timber.w("未检索到相关记忆: ${searchResults.message}")
                    emptyList()
                }
                is SearchResults.Error -> {
                    Timber.e("检索失败: ${searchResults.message}")
                    return@withContext RAGResult.Error("检索失败: ${searchResults.message}")
                }
            }
            
            // Step 2: 获取记忆内容
            val memoryContexts = retrievedMemories.mapNotNull { result ->
                val content = memoryContents[result.memoryId]
                if (content != null) {
                    MemoryContext(
                        memoryId = result.memoryId,
                        content = content,
                        similarity = result.similarity
                    )
                } else {
                    Timber.w("记忆内容未找到: ${result.memoryId}")
                    null
                }
            }
            
            // 检查是否已初始化
            val manager = qwenManager ?: throw IllegalStateException("PersonalizedRAG 未初始化，请先调用 initialize()")
            val extractor = personaExtractor ?: throw IllegalStateException("PersonalizedRAG 未初始化，请先调用 initialize()")
            
            // Step 3: 构建增强 Prompt
            val personaData = if (usePersona) {
                extractor.getCurrentPersona()
            } else {
                null
            }
            
            val enhancedPrompt = buildEnhancedPrompt(
                userQuery = userQuery,
                memories = memoryContexts,
                personaData = personaData
            )
            
            // Step 4: 获取系统提示词
            val systemPrompt = if (usePersona && personaData != null) {
                OceanPrompts.getPersonalizedChatPrompt(personaData)
            } else {
                getDefaultSystemPrompt()
            }
            
            Timber.d("开始生成回答...")
            
            // Step 5: 流式生成回答
            val responseFlow = manager.generateStream(
                prompt = enhancedPrompt,
                systemPrompt = systemPrompt,
                maxNewTokens = 500,
                functionType = "conversation"
            )
            
            return@withContext RAGResult.Success(
                responseFlow = responseFlow,
                retrievedMemories = memoryContexts,
                personaData = personaData
            )
            
        } catch (e: Exception) {
            Timber.e(e, "RAG 对话失败")
            return@withContext RAGResult.Error("对话失败: ${e.message}")
        }
    }
    
    /**
     * 构建增强型提示词 (动态多语言)
     */
    private fun buildEnhancedPrompt(
        userQuery: String,
        memories: List<MemoryContext>,
        personaData: PersonaData?
    ): String {
        val lang = com.soulon.app.i18n.AppStrings.getCurrentLanguage()
        val isZh = lang.startsWith("zh")
        val promptBuilder = StringBuilder()
        
        // 1. 添加人格信息（如果有）
        if (personaData != null) {
            val (dominantTrait, score) = personaData.getDominantTrait()
            if (isZh) {
                promptBuilder.append("【用户人格特征】\n")
                promptBuilder.append("- 主导特质: $dominantTrait (${(score * 100).toInt()}%)\n")
                promptBuilder.append("- 开放性: ${(personaData.openness * 100).toInt()}%\n")
                promptBuilder.append("- 尽责性: ${(personaData.conscientiousness * 100).toInt()}%\n")
                promptBuilder.append("- 外向性: ${(personaData.extraversion * 100).toInt()}%\n")
                promptBuilder.append("- 宜人性: ${(personaData.agreeableness * 100).toInt()}%\n")
                promptBuilder.append("- 神经质: ${(personaData.neuroticism * 100).toInt()}%\n")
            } else {
                promptBuilder.append("[User Persona]\n")
                promptBuilder.append("- Dominant Trait: $dominantTrait (${(score * 100).toInt()}%)\n")
                promptBuilder.append("- Openness: ${(personaData.openness * 100).toInt()}%\n")
                promptBuilder.append("- Conscientiousness: ${(personaData.conscientiousness * 100).toInt()}%\n")
                promptBuilder.append("- Extraversion: ${(personaData.extraversion * 100).toInt()}%\n")
                promptBuilder.append("- Agreeableness: ${(personaData.agreeableness * 100).toInt()}%\n")
                promptBuilder.append("- Neuroticism: ${(personaData.neuroticism * 100).toInt()}%\n")
            }
            promptBuilder.append("\n")
        }
        
        // 2. 添加相关记忆上下文
        if (memories.isNotEmpty()) {
            promptBuilder.append(if (isZh) "【相关记忆】\n" else "[Relevant Memories]\n")
            memories.forEachIndexed { index, memory ->
                val truncatedContent = if (memory.content.length > 300) {
                    memory.content.take(300) + "..."
                } else {
                    memory.content
                }
                if (isZh) {
                    promptBuilder.append("记忆 ${index + 1} (相似度: ${(memory.similarity * 100).toInt()}%):\n")
                } else {
                    promptBuilder.append("Memory ${index + 1} (Similarity: ${(memory.similarity * 100).toInt()}%):\n")
                }
                promptBuilder.append("$truncatedContent\n\n")
            }
        }
        
        // 3. 添加用户问题
        promptBuilder.append(if (isZh) "【用户问题】\n" else "[User Question]\n")
        promptBuilder.append(userQuery)
        promptBuilder.append("\n\n")
        
        // 4. 添加指令
        if (isZh) {
            promptBuilder.append("请基于以上信息回答用户的问题。")
            if (personaData != null) {
                promptBuilder.append("请用符合用户人格特征的语气和风格回答。")
                promptBuilder.append("默认情况下，请提供简明扼要的总结。仅当用户明确要求详细的人格分析时，才列出具体的五维详情。")
            }
            if (memories.isNotEmpty()) promptBuilder.append("如果相关记忆中有有用的信息，请引用它们。")
        } else {
            promptBuilder.append("Please answer the user's question based on the information above.")
            if (personaData != null) {
                promptBuilder.append(" Please answer in a tone and style that matches the user's persona.")
                promptBuilder.append(" By default, provide a concise summary. Only list the detailed five-factor analysis if the user explicitly asks for it.")
            }
            if (memories.isNotEmpty()) promptBuilder.append(" If there is useful information in the relevant memories, please cite it.")
            promptBuilder.append(" IMPORTANT: Reply in the same language as the user's question.")
        }
        
        return promptBuilder.toString()
    }
    
    /**
     * 简化版对话（无检索，仅人格化）
     */
    suspend fun simpleChat(
        userQuery: String,
        usePersona: Boolean = true,
        history: List<QwenCloudManager.Message> = emptyList(),
        extraSystemContext: String? = null
    ): Flow<String> = flow {
        try {
            // 检查是否已初始化
            val manager = qwenManager ?: throw IllegalStateException("PersonalizedRAG 未初始化，请先调用 initialize()")
            val extractor = personaExtractor ?: throw IllegalStateException("PersonalizedRAG 未初始化，请先调用 initialize()")
            
            val personaData = if (usePersona) {
                extractor.getCurrentPersona()
            } else {
                null
            }
            
            val systemPrompt = if (usePersona && personaData != null) {
                OceanPrompts.getPersonalizedChatPrompt(personaData)
            } else {
                getDefaultSystemPrompt()
            }

            val messages = buildList {
                add(QwenCloudManager.Message("system", systemPrompt + "\n\n" + getQualityGuidelines()))
                if (!extraSystemContext.isNullOrBlank()) {
                    add(QwenCloudManager.Message("system", extraSystemContext))
                }
                addAll(history)
                add(QwenCloudManager.Message("user", userQuery))
            }

            manager.generateStream(
                messages = messages,
                maxNewTokens = 700
            ).collect { token ->
                emit(token)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "简化对话失败")
            throw e
        }
    }
    
    /**
     * 获取 RAG 统计信息
     */
    suspend fun getStats(): RAGStats = withContext(Dispatchers.IO) {
        val searchStats = searchEngine.getSearchStats()
        val profile = rewardsRepository.getUserProfile()
        
        RAGStats(
            totalVectors = searchStats.totalVectors,
            hasPersona = profile.personaData != null,
            personaSyncRate = profile.personaSyncRate ?: 0f,
            totalMemories = searchStats.totalVectors
        )
    }
}

/**
 * 默认系统提示词 (动态生成)
 */
private fun getDefaultSystemPrompt(): String {
    val lang = com.soulon.app.i18n.AppStrings.getCurrentLanguage()
    return if (lang.startsWith("zh")) {
        """你是用户的个人 AI 助手，帮助用户管理和回顾他们的个人记忆。

重要说明：
- 用户提供的"记忆"是用户自己之前记录的内容，不是你的记忆
- 你的角色是帮助用户理解、回顾和分析他们自己的记忆
- 当引用用户的记忆时，使用"您之前记录的..."或"根据您的记忆..."这样的表述

请遵循以下原则：
1. 准确地基于用户的记忆内容回答问题
2. 如果用户的记忆中没有相关信息，诚实地告知用户
3. 区分"用户的记忆内容"和"你的建议/分析"
4. 保持友好、贴心和专业的语气
5. 重要：请始终使用与用户提问相同的语言进行回答"""
    } else {
        """You are the user's personal AI assistant, helping them manage and review their personal memories.

Important Notes:
- The "memories" provided by the user are content they recorded previously, not your memories.
- Your role is to help the user understand, review, and analyze their own memories.
- When referencing user memories, use phrases like "You previously recorded..." or "According to your memory..."

Please follow these principles:
1. Answer questions accurately based on the user's memory content.
2. If there is no relevant information in the user's memories, honestly inform the user.
3. Distinguish between "user's memory content" and "your suggestions/analysis".
4. Maintain a friendly, caring, and professional tone.
5. ALWAYS reply in the same language as the user's input."""
    }
}

private fun getQualityGuidelines(): String {
    val lang = com.soulon.app.i18n.AppStrings.getCurrentLanguage()
    return if (lang.startsWith("zh")) {
        """回答要求：
1) 如果是操作性建议，优先用分步骤列表（1、2、3…）。
2) 如果是描述性回答，请使用自然的段落。
3) 如果信息不足以给出可靠方案，先提出 1–3 个澄清问题。
4) 避免泛泛而谈。
5) 直接回答，不要输出思考过程或复述问题。"""
    } else {
        """Response Requirements:
1) If providing actionable advice, prefer step-by-step lists (1, 2, 3...).
2) If providing descriptive answers, use natural paragraphs.
3) If information is insufficient, ask 1-3 clarifying questions first.
4) Avoid vague generalizations.
5) Answer directly, DO NOT output your thought process or paraphrase the question."""
    }
}

/**
 * 记忆上下文
 */
data class MemoryContext(
    val memoryId: String,
    val content: String,
    val similarity: Float
)

/**
 * RAG 结果
 */
sealed class RAGResult {
    data class Success(
        val responseFlow: Flow<String>,
        val retrievedMemories: List<MemoryContext>,
        val personaData: PersonaData?
    ) : RAGResult() {
        val memoryCount: Int get() = retrievedMemories.size
        val hasPersona: Boolean get() = personaData != null
    }
    
    data class Error(val message: String) : RAGResult()
}

/**
 * RAG 统计信息
 */
data class RAGStats(
    val totalVectors: Int,
    val hasPersona: Boolean,
    val personaSyncRate: Float,
    val totalMemories: Int
)
