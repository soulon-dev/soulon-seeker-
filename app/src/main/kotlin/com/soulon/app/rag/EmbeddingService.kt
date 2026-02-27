package com.soulon.app.rag

import android.content.Context
import com.soulon.app.BuildConfig
import com.soulon.app.auth.BackendAuthManager
import com.soulon.app.data.CloudDataRepository
import com.soulon.app.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

/**
 * 文本向量化服务
 * 
 * 使用 DashScope Text Embedding API 将文本转换为向量
 * API 密钥从后端管理系统获取
 * 
 * Phase 3 Week 3: Task_RAG_Vector
 */
class EmbeddingService(private val context: Context) {
    
    private val cloudRepo = CloudDataRepository.getInstance(context)
    
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("Accept-Language", LocaleManager.getAcceptLanguage(context))

            val token = BackendAuthManager.getInstance(context).getAccessToken()
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }

            chain.proceed(builder.build())
        }
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    companion object {
        private val API_BASE_URL = BuildConfig.BACKEND_BASE_URL + "/api/v1/ai/proxy/embeddings"
        
        // 模型配置
        // text-embedding-v3 模型输出 1024 维向量
        private const val MODEL_NAME = "text-embedding-v3"
        private const val VECTOR_DIMENSION = 1024
        private const val MAX_BATCH_SIZE = 25  // API 限制
        private const val MAX_TEXT_LENGTH = 2048  // tokens
        
        // 文本类型
        const val TEXT_TYPE_QUERY = "query"      // 查询文本
        const val TEXT_TYPE_DOCUMENT = "document" // 文档文本
    }
    
    /**
     * 将单个文本转换为向量
     * 
     * @param text 输入文本
     * @param textType "query" 或 "document"
     * @return 1536 维向量
     */
    suspend fun embed(
        text: String,
        textType: String = TEXT_TYPE_DOCUMENT
    ): EmbeddingResult = withContext(Dispatchers.IO) {
        try {
            if (text.isBlank()) {
                return@withContext EmbeddingResult.Error("文本不能为空")
            }
            
            // 截断过长文本
            val truncatedText = if (text.length > MAX_TEXT_LENGTH * 4) {
                text.take(MAX_TEXT_LENGTH * 4)
            } else {
                text
            }
            
            Timber.d("开始向量化文本: ${truncatedText.take(50)}...")
            
            // 构建请求
            val requestBody = buildRequestBody(listOf(truncatedText), textType)
            Timber.d("📤 Embedding 请求体: $requestBody")
            
            val request = Request.Builder()
                .url(API_BASE_URL)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()
            
            // 发送请求
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                Timber.e("Embedding API 调用失败: ${response.code} - $responseBody")
                return@withContext EmbeddingResult.Error("API 调用失败: ${response.code}")
            }
            
            // 解析响应
            val vector = parseEmbeddingResponse(responseBody, 0)
            
            if (vector == null) {
                return@withContext EmbeddingResult.Error("无法解析响应")
            }
            
            Timber.i("向量化成功: ${vector.size} 维")
            return@withContext EmbeddingResult.Success(listOf(vector))
            
        } catch (e: IOException) {
            Timber.e(e, "网络错误")
            return@withContext EmbeddingResult.Error("网络错误: ${e.message}")
        } catch (e: Exception) {
            Timber.e(e, "向量化失败")
            return@withContext EmbeddingResult.Error("向量化失败: ${e.message}")
        }
    }
    
    /**
     * 批量向量化多个文本
     * 
     * @param texts 文本列表（最多 25 个）
     * @param textType "query" 或 "document"
     * @return 向量列表
     */
    suspend fun embedBatch(
        texts: List<String>,
        textType: String = TEXT_TYPE_DOCUMENT
    ): EmbeddingResult = withContext(Dispatchers.IO) {
        try {
            if (texts.isEmpty()) {
                return@withContext EmbeddingResult.Error("文本列表不能为空")
            }
            
            if (texts.size > MAX_BATCH_SIZE) {
                Timber.w("文本数量超过限制，自动分批处理")
                // 分批处理
                return@withContext embedBatchChunked(texts, textType)
            }
            
            // 过滤和截断文本
            val processedTexts = texts
                .filter { it.isNotBlank() }
                .map { text ->
                    if (text.length > MAX_TEXT_LENGTH * 4) {
                        text.take(MAX_TEXT_LENGTH * 4)
                    } else {
                        text
                    }
                }
            
            if (processedTexts.isEmpty()) {
                return@withContext EmbeddingResult.Error("没有有效的文本")
            }
            
            Timber.d("批量向量化 ${processedTexts.size} 个文本")
            
            // 构建请求
            val requestBody = buildRequestBody(processedTexts, textType)
            val request = Request.Builder()
                .url(API_BASE_URL)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()
            
            // 发送请求
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                Timber.e("Embedding API 调用失败: ${response.code} - $responseBody")
                return@withContext EmbeddingResult.Error("API 调用失败: ${response.code}")
            }
            
            // 解析所有向量
            val vectors = mutableListOf<FloatArray>()
            for (i in processedTexts.indices) {
                val vector = parseEmbeddingResponse(responseBody, i)
                if (vector != null) {
                    vectors.add(vector)
                } else {
                    Timber.w("第 $i 个文本向量化失败")
                }
            }
            
            if (vectors.isEmpty()) {
                return@withContext EmbeddingResult.Error("所有文本向量化失败")
            }
            
            Timber.i("批量向量化成功: ${vectors.size}/${processedTexts.size}")
            return@withContext EmbeddingResult.Success(vectors)
            
        } catch (e: IOException) {
            Timber.e(e, "网络错误")
            return@withContext EmbeddingResult.Error("网络错误: ${e.message}")
        } catch (e: Exception) {
            Timber.e(e, "批量向量化失败")
            return@withContext EmbeddingResult.Error("批量向量化失败: ${e.message}")
        }
    }
    
    /**
     * 分批处理大量文本
     */
    private suspend fun embedBatchChunked(
        texts: List<String>,
        textType: String
    ): EmbeddingResult {
        val allVectors = mutableListOf<FloatArray>()
        
        texts.chunked(MAX_BATCH_SIZE).forEach { chunk ->
            when (val result = embedBatch(chunk, textType)) {
                is EmbeddingResult.Success -> allVectors.addAll(result.vectors)
                is EmbeddingResult.Error -> {
                    Timber.w("分批向量化部分失败: ${result.message}")
                }
            }
        }
        
        return if (allVectors.isNotEmpty()) {
            EmbeddingResult.Success(allVectors)
        } else {
            EmbeddingResult.Error("所有分批向量化失败")
        }
    }
    
    /**
     * 构建请求体
     */
    private fun buildRequestBody(texts: List<String>, textType: String): String {
        val requestJson = JSONObject().apply {
            put("model", MODEL_NAME)
            put("input", JSONObject().apply {
                put("texts", JSONArray(texts))
            })
            put("parameters", JSONObject().apply {
                put("text_type", textType)
            })
        }
        return requestJson.toString()
    }
    
    /**
     * 解析 Embedding 响应
     */
    private fun parseEmbeddingResponse(responseBody: String, textIndex: Int): FloatArray? {
        try {
            val json = JSONObject(responseBody)
            
            // 检查错误
            if (json.has("code")) {
                val errorCode = json.getString("code")
                val errorMessage = json.optString("message", "未知错误")
                Timber.e("API 返回错误: $errorCode - $errorMessage")
                return null
            }
            
            // 解析 output.embeddings
            val output = json.getJSONObject("output")
            val embeddings = output.getJSONArray("embeddings")
            
            // 找到对应索引的向量
            for (i in 0 until embeddings.length()) {
                val embeddingObj = embeddings.getJSONObject(i)
                val index = embeddingObj.getInt("text_index")
                
                if (index == textIndex) {
                    val embeddingArray = embeddingObj.getJSONArray("embedding")
                    val vector = FloatArray(embeddingArray.length()) { j ->
                        embeddingArray.getDouble(j).toFloat()
                    }
                    
                    if (vector.size != VECTOR_DIMENSION) {
                        Timber.w("向量维度不匹配: ${vector.size} != $VECTOR_DIMENSION")
                    }
                    
                    return vector
                }
            }
            
            Timber.w("未找到索引 $textIndex 的向量")
            return null
            
        } catch (e: Exception) {
            Timber.e(e, "解析响应失败: $responseBody")
            return null
        }
    }
    
    /**
     * 获取向量维度
     */
    fun getVectorDimension(): Int = VECTOR_DIMENSION
}

/**
 * Embedding 结果
 */
sealed class EmbeddingResult {
    data class Success(val vectors: List<FloatArray>) : EmbeddingResult()
    data class Error(val message: String) : EmbeddingResult()
}
