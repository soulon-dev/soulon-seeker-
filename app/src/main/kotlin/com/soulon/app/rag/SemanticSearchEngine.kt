package com.soulon.app.rag

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * 语义搜索引擎
 * 
 * 使用余弦相似度进行向量检索
 * 
 * Phase 3 Week 3: Task_RAG_Vector
 */
class SemanticSearchEngine(private val context: Context) {
    
    private val vectorRepository = VectorRepository(context)
    private val embeddingService = EmbeddingService(context)
    
    companion object {
        private const val DEFAULT_TOP_K = 5
        private const val DEFAULT_THRESHOLD = 0.7f
        private const val TEXT_TYPE_QUERY = "query"
    }
    
    /**
     * 语义搜索
     * 
     * @param query 查询文本
     * @param topK 返回前 K 个结果
     * @param threshold 相似度阈值（0-1）
     * @return 搜索结果列表
     */
    suspend fun search(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        threshold: Float = DEFAULT_THRESHOLD
    ): SearchResults = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) {
                return@withContext SearchResults.Error("查询不能为空")
            }
            
            Timber.d("开始语义搜索: $query (topK=$topK, threshold=$threshold)")
            
            // Step 1: 向量化查询
            val queryVector = when (val result = embeddingService.embed(query, TEXT_TYPE_QUERY)) {
                is EmbeddingResult.Success -> result.vectors.firstOrNull()
                is EmbeddingResult.Error -> {
                    Timber.e("查询向量化失败: ${result.message}")
                    return@withContext SearchResults.Error("查询向量化失败: ${result.message}")
                }
            }
            
            if (queryVector == null) {
                return@withContext SearchResults.Error("查询向量化返回空结果")
            }
            
            Timber.d("查询向量维度: ${queryVector.size}")
            
            // Step 2: 加载所有向量
            val allVectors = vectorRepository.getAllVectors()
            
            if (allVectors.isEmpty()) {
                Timber.w("向量库为空")
                return@withContext SearchResults.Empty("向量库为空，请先添加记忆")
            }
            
            Timber.d("向量库大小: ${allVectors.size}")
            
            // Step 3: 计算相似度并排序（使用 Top-K 堆优化）
            val startTime = System.currentTimeMillis()
            val topResults = findTopK(queryVector, allVectors, topK, threshold)
            val searchTime = System.currentTimeMillis() - startTime
            
            Timber.i("搜索完成: ${topResults.size} 个结果, 耗时 ${searchTime}ms")
            
            if (topResults.isEmpty()) {
                return@withContext SearchResults.Empty("未找到相似度 > $threshold 的结果")
            }
            
            return@withContext SearchResults.Success(
                results = topResults,
                queryTime = searchTime,
                totalVectors = allVectors.size
            )
            
        } catch (e: Exception) {
            Timber.e(e, "语义搜索失败")
            return@withContext SearchResults.Error("搜索失败: ${e.message}")
        }
    }
    
    /**
     * 使用 Top-K 堆查找最相似的向量
     */
    private fun findTopK(
        queryVector: FloatArray,
        allVectors: Map<String, FloatArray>,
        k: Int,
        threshold: Float
    ): List<SearchResult> {
        // 使用最小堆维护 Top-K
        val heap = PriorityQueue<SearchResult>(k) { a, b ->
            a.similarity.compareTo(b.similarity) // 最小堆
        }
        
        allVectors.forEach { (memoryId, vector) ->
            val similarity = cosineSimilarity(queryVector, vector)
            
            if (similarity >= threshold) {
                if (heap.size < k) {
                    heap.offer(SearchResult(memoryId, similarity))
                } else if (similarity > heap.peek()!!.similarity) {
                    heap.poll()
                    heap.offer(SearchResult(memoryId, similarity))
                }
            }
        }
        
        // 转为列表并按相似度降序排序
        return heap.sortedByDescending { it.similarity }
    }
    
    /**
     * 计算余弦相似度
     * 
     * similarity = (A · B) / (||A|| * ||B||)
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            Timber.w("向量维度不匹配: ${a.size} != ${b.size}")
            return 0f
        }
        
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        normA = sqrt(normA)
        normB = sqrt(normB)
        
        return if (normA > 0 && normB > 0) {
            (dotProduct / (normA * normB)).toFloat()
        } else {
            0f
        }
    }
    
    /**
     * 批量计算相似度
     */
    suspend fun batchSimilarity(
        queries: List<String>,
        topK: Int = DEFAULT_TOP_K,
        threshold: Float = DEFAULT_THRESHOLD
    ): List<SearchResults> = withContext(Dispatchers.IO) {
        queries.map { query ->
            search(query, topK, threshold)
        }
    }
    
    /**
     * 获取搜索统计
     */
    suspend fun getSearchStats(): SearchEngineStats = withContext(Dispatchers.IO) {
        val repoStats = vectorRepository.getStats()
        SearchEngineStats(
            totalVectors = repoStats.totalVectors,
            vectorDimension = repoStats.vectorDimension,
            avgTextLength = repoStats.avgTextLength
        )
    }
}

/**
 * 搜索结果
 */
data class SearchResult(
    val memoryId: String,
    val similarity: Float
) {
    /**
     * 获取相似度百分比
     */
    fun getSimilarityPercent(): Int = (similarity * 100).toInt()
    
    /**
     * 获取相似度等级
     */
    fun getSimilarityGrade(): String = when {
        similarity >= 0.95f -> "极度相似"
        similarity >= 0.85f -> "高度相似"
        similarity >= 0.75f -> "相似"
        similarity >= 0.65f -> "部分相似"
        else -> "弱相似"
    }
}

/**
 * 搜索结果集
 */
sealed class SearchResults {
    data class Success(
        val results: List<SearchResult>,
        val queryTime: Long,
        val totalVectors: Int
    ) : SearchResults() {
        val resultCount: Int get() = results.size
        val avgSimilarity: Float get() = if (results.isNotEmpty()) {
            results.map { it.similarity }.average().toFloat()
        } else {
            0f
        }
    }
    
    data class Empty(val message: String) : SearchResults()
    data class Error(val message: String) : SearchResults()
}

/**
 * 搜索引擎统计
 */
data class SearchEngineStats(
    val totalVectors: Int,
    val vectorDimension: Int,
    val avgTextLength: Int
)
