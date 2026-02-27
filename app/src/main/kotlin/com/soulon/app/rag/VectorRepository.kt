package com.soulon.app.rag

import android.content.Context
import com.soulon.app.data.CloudDataRepository
import com.soulon.app.data.VectorUploadData
import com.soulon.app.rewards.RewardsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 向量仓库
 * 
 * 管理记忆向量的存储、检索和更新
 * 
 * 数据流向（云端优先）：
 * - 存储：本地 + 后端（异步）
 * - 搜索：后端优先（支持更高效的向量搜索），后备本地
 * 
 * Phase 3 Week 3: Task_RAG_Vector
 */
class VectorRepository(private val context: Context) {
    
    private val database = RewardsDatabase.getInstance(context)
    private val dao = database.vectorDao()
    private val embeddingService = EmbeddingService(context)
    private val cloudRepo by lazy { CloudDataRepository.getInstance(context) }
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // 当前钱包地址（用于后端同步）
    private var currentWallet: String? = null
    
    companion object {
        private const val DEFAULT_TEXT_TYPE = "document"
    }
    
    /**
     * 设置当前钱包地址
     */
    fun setWalletAddress(walletAddress: String?) {
        currentWallet = walletAddress
    }
    
    /**
     * 保存单个向量
     */
    suspend fun saveVector(
        memoryId: String,
        vector: FloatArray,
        textLength: Int = 0,
        originalText: String = "",
        category: String = "general"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val memoryVector = MemoryVector.fromFloatArray(
                memoryId = memoryId,
                vector = vector,
                textLength = textLength
            )
            
            // 1. 保存到本地
            dao.insertVector(memoryVector)
            Timber.d("保存向量到本地成功: $memoryId (${vector.size} 维)")
            
            // 2. 异步同步到后端
            if (currentWallet != null && originalText.isNotEmpty()) {
                scope.launch {
                    try {
                        val vectorData = VectorUploadData(
                            memoryId = memoryId,
                            vector = vector.toList(),
                            originalText = originalText,
                            category = category
                        )
                        cloudRepo.uploadVectors(listOf(vectorData))
                        Timber.d("向量已同步到后端: $memoryId")
                    } catch (e: Exception) {
                        Timber.w(e, "向量同步到后端失败: $memoryId")
                    }
                }
            }
            
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "保存向量失败: $memoryId")
            return@withContext false
        }
    }
    
    /**
     * 批量保存向量
     */
    suspend fun saveVectors(
        vectors: Map<String, FloatArray>,
        textLengths: Map<String, Int> = emptyMap(),
        originalTexts: Map<String, String> = emptyMap(),
        categories: Map<String, String> = emptyMap()
    ): Int = withContext(Dispatchers.IO) {
        try {
            val memoryVectors = vectors.map { (memoryId, vector) ->
                MemoryVector.fromFloatArray(
                    memoryId = memoryId,
                    vector = vector,
                    textLength = textLengths[memoryId] ?: 0
                )
            }
            
            // 1. 保存到本地
            dao.insertVectors(memoryVectors)
            Timber.i("批量保存向量到本地成功: ${memoryVectors.size} 个")
            
            // 2. 异步同步到后端
            if (currentWallet != null && originalTexts.isNotEmpty()) {
                scope.launch {
                    try {
                        val vectorDataList = vectors.mapNotNull { (memoryId, vector) ->
                            val text = originalTexts[memoryId]
                            if (text != null) {
                                VectorUploadData(
                                    memoryId = memoryId,
                                    vector = vector.toList(),
                                    originalText = text,
                                    category = categories[memoryId] ?: "general"
                                )
                            } else null
                        }
                        if (vectorDataList.isNotEmpty()) {
                            cloudRepo.uploadVectors(vectorDataList)
                            Timber.i("批量向量已同步到后端: ${vectorDataList.size} 个")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "批量向量同步到后端失败")
                    }
                }
            }
            
            return@withContext memoryVectors.size
        } catch (e: Exception) {
            Timber.e(e, "批量保存向量失败")
            return@withContext 0
        }
    }
    
    /**
     * 云端向量搜索（优先使用后端）
     * 
     * @param queryVector 查询向量
     * @param topK 返回数量
     * @param threshold 相似度阈值
     * @return 搜索结果（包含记忆ID、相似度分数、原文）
     */
    suspend fun searchCloud(
        queryVector: FloatArray,
        topK: Int = 5,
        threshold: Float = 0.7f
    ): List<VectorSearchResultWithText> = withContext(Dispatchers.IO) {
        // 优先使用后端搜索
        if (currentWallet != null) {
            try {
                val results = cloudRepo.searchVectors(queryVector, topK, threshold)
                if (results.isNotEmpty()) {
                    return@withContext results.map { result ->
                        VectorSearchResultWithText(
                            memoryId = result.memoryId,
                            similarity = result.similarity,
                            originalText = result.textPreview
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "后端向量搜索失败，使用本地搜索")
            }
        }
        
        // 后备：本地搜索
        return@withContext searchLocal(queryVector, topK, threshold).map { result ->
            VectorSearchResultWithText(
                memoryId = result.memoryId,
                similarity = result.similarity,
                originalText = "" // 本地搜索不包含原文
            )
        }
    }
    
    /**
     * 本地向量搜索（余弦相似度）
     */
    suspend fun searchLocal(
        queryVector: FloatArray,
        topK: Int = 5,
        threshold: Float = 0.7f
    ): List<VectorSearchResult> = withContext(Dispatchers.IO) {
        try {
            val allVectors = dao.getAllVectors()
            
            val results = allVectors.mapNotNull { memoryVector ->
                val vector = memoryVector.toFloatArray()
                val similarity = cosineSimilarity(queryVector, vector)
                
                if (similarity >= threshold) {
                    VectorSearchResult(memoryVector.memoryId, similarity)
                } else null
            }
            .sortedByDescending { it.similarity }
            .take(topK)
            
            return@withContext results
        } catch (e: Exception) {
            Timber.e(e, "本地向量搜索失败")
            return@withContext emptyList()
        }
    }
    
    /**
     * 计算余弦相似度
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }
    
    /**
     * 向量化并保存文本
     */
    suspend fun vectorizeAndSave(
        memoryId: String,
        text: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 检查是否已存在
            if (dao.vectorExists(memoryId)) {
                Timber.d("向量已存在，跳过: $memoryId")
                return@withContext true
            }
            
            // 调用 Embedding API
            Timber.d("开始向量化: $memoryId")
            when (val result = embeddingService.embed(text, DEFAULT_TEXT_TYPE)) {
                is EmbeddingResult.Success -> {
                    val vector = result.vectors.firstOrNull()
                    if (vector != null) {
                        return@withContext saveVector(memoryId, vector, text.length)
                    } else {
                        Timber.w("向量化返回空结果")
                        return@withContext false
                    }
                }
                is EmbeddingResult.Error -> {
                    Timber.e("向量化失败: ${result.message}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "向量化并保存失败: $memoryId")
            return@withContext false
        }
    }
    
    /**
     * 批量向量化并保存
     */
    suspend fun vectorizeAndSaveBatch(
        memories: Map<String, String>
    ): Int = withContext(Dispatchers.IO) {
        try {
            // 过滤已存在的向量
            val memoriesToProcess = mutableMapOf<String, String>()
            memories.forEach { (memoryId, text) ->
                if (!dao.vectorExists(memoryId)) {
                    memoriesToProcess[memoryId] = text
                } else {
                    Timber.d("向量已存在，跳过: $memoryId")
                }
            }
            
            if (memoriesToProcess.isEmpty()) {
                Timber.i("所有记忆已向量化")
                return@withContext 0
            }
            
            Timber.i("开始批量向量化: ${memoriesToProcess.size} 个记忆")
            
            // 调用 Embedding API
            val texts = memoriesToProcess.values.toList()
            val memoryIds = memoriesToProcess.keys.toList()
            
            when (val result = embeddingService.embedBatch(texts, DEFAULT_TEXT_TYPE)) {
                is EmbeddingResult.Success -> {
                    val vectors = result.vectors
                    
                    // 保存向量
                    val vectorsMap = memoryIds.zip(vectors).toMap()
                    val textLengths = memoriesToProcess.mapValues { it.value.length }
                    
                    return@withContext saveVectors(vectorsMap, textLengths)
                }
                is EmbeddingResult.Error -> {
                    Timber.e("批量向量化失败: ${result.message}")
                    return@withContext 0
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "批量向量化并保存失败")
            return@withContext 0
        }
    }
    
    /**
     * 获取向量
     */
    suspend fun getVector(memoryId: String): FloatArray? = withContext(Dispatchers.IO) {
        try {
            val memoryVector = dao.getVector(memoryId)
            return@withContext memoryVector?.toFloatArray()
        } catch (e: Exception) {
            Timber.e(e, "获取向量失败: $memoryId")
            return@withContext null
        }
    }
    
    /**
     * 获取所有向量
     */
    suspend fun getAllVectors(): Map<String, FloatArray> = withContext(Dispatchers.IO) {
        try {
            val memoryVectors = dao.getAllVectors()
            return@withContext memoryVectors.associate { 
                it.memoryId to it.toFloatArray() 
            }
        } catch (e: Exception) {
            Timber.e(e, "获取所有向量失败")
            return@withContext emptyMap()
        }
    }
    
    /**
     * 获取所有向量（Flow）
     */
    fun getAllVectorsFlow(): Flow<List<MemoryVector>> {
        return dao.getAllVectorsFlow()
    }
    
    /**
     * 删除向量
     */
    suspend fun deleteVector(memoryId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.deleteVector(memoryId)
            Timber.d("删除向量成功: $memoryId")
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "删除向量失败: $memoryId")
            return@withContext false
        }
    }
    
    /**
     * 批量删除向量
     */
    suspend fun deleteVectors(memoryIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.deleteVectors(memoryIds)
            Timber.i("批量删除向量成功: ${memoryIds.size} 个")
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "批量删除向量失败")
            return@withContext false
        }
    }
    
    /**
     * 获取向量统计
     */
    suspend fun getStats(): VectorRepositoryStats = withContext(Dispatchers.IO) {
        try {
            val count = dao.getVectorCount()
            val dbStats = dao.getVectorStats()
            
            return@withContext VectorRepositoryStats(
                totalVectors = count,
                avgTextLength = dbStats?.avgLength?.toInt() ?: 0,
                totalTextLength = dbStats?.totalLength ?: 0,
                vectorDimension = embeddingService.getVectorDimension()
            )
        } catch (e: Exception) {
            Timber.e(e, "获取统计失败")
            return@withContext VectorRepositoryStats(0, 0, 0, 0)
        }
    }
    
    /**
     * 检查向量是否存在
     */
    suspend fun exists(memoryId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext dao.vectorExists(memoryId)
        } catch (e: Exception) {
            Timber.e(e, "检查向量存在失败: $memoryId")
            return@withContext false
        }
    }
}

/**
 * 向量仓库统计信息
 */
data class VectorRepositoryStats(
    val totalVectors: Int,
    val avgTextLength: Int,
    val totalTextLength: Int,
    val vectorDimension: Int
)

/**
 * 向量搜索结果
 */
data class VectorSearchResult(
    val memoryId: String,
    val similarity: Float
)

/**
 * 向量搜索结果（包含原文）
 */
data class VectorSearchResultWithText(
    val memoryId: String,
    val similarity: Float,
    val originalText: String
)
