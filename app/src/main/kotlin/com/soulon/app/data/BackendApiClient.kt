package com.soulon.app.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.soulon.app.BuildConfig
import com.soulon.app.auth.BackendAuthManager
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * 后端 API 客户端
 * 用于与 Cloudflare Workers 后端进行数据交互
 */
class BackendApiClient(private val context: Context) {
    
    companion object {
        private const val TAG = "BackendApiClient"
        private val BASE_URL = BuildConfig.BACKEND_BASE_URL
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
        
        @Volatile
        private var INSTANCE: BackendApiClient? = null
        
        fun getInstance(context: Context): BackendApiClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackendApiClient(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val expectedBaseUrl: URL by lazy { URL(BASE_URL) }
    
    // ============================================
    // 用户数据 API
    // ============================================
    
    /**
     * 获取完整用户数据（一次性拉取）
     */
    suspend fun getFullUserProfile(walletAddress: String): FullUserProfile? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/users/full-profile?wallet=$walletAddress")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                if (!json.optBoolean("exists", false)) {
                    Log.d(TAG, "用户在后端不存在")
                    return@withContext null
                }
                
                parseFullUserProfile(json)
            } else {
                Log.e(TAG, "获取完整用户数据失败: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取完整用户数据异常", e)
            null
        }
    }
    
    /**
     * 同步用户基本数据到后端
     */
    suspend fun syncUserData(
        walletAddress: String,
        memoBalance: Int,
        currentTier: Int,
        subscriptionType: String,
        subscriptionExpiry: Long?,
        stakedAmount: Long,
        totalTokensUsed: Int,
        memoriesCount: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/users/sync")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("memoBalance", memoBalance)
                put("currentTier", currentTier)
                put("subscriptionType", subscriptionType)
                put("subscriptionExpiry", subscriptionExpiry)
                put("stakedAmount", stakedAmount / 1_000_000_000.0) // lamports to SOL
                put("totalTokensUsed", totalTokensUsed)
                put("memoriesCount", memoriesCount)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "同步用户数据失败", e)
            false
        }
    }

    suspend fun storeMemoryBlob(
        walletAddress: String,
        memoryId: String,
        encryptedBytes: ByteArray,
        contentHash: String,
        metadata: Map<String, String> = emptyMap()
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/memories/blob")
            val connection = createConnection(url, "POST")

            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("memoryId", memoryId)
                put("contentBase64", Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                put("contentHash", contentHash)
                put("metadata", JSONObject(metadata))
            }

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                Log.e(TAG, "后端存储记忆失败: HTTP ${connection.responseCode}${if (!errorBody.isNullOrBlank()) " $errorBody" else ""}")
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val path = json.optString("path", "").trim()
            if (path.isBlank()) return@withContext null
            "$BASE_URL$path"
        } catch (e: Exception) {
            Log.e(TAG, "后端存储记忆异常", e)
            null
        }
    }

    suspend fun fetchMemoryBlob(
        memoryId: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/memories/blob/$memoryId")
            val connection = createConnection(url, "GET")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "获取后端记忆失败: ${connection.responseCode}")
                return@withContext null
            }
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "获取后端记忆异常", e)
            null
        }
    }

    suspend fun markMemoryBlobMigrated(
        memoryId: String,
        irysId: String,
        deleteBlob: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/memories/blob/$memoryId/migrated")
            val connection = createConnection(url, "POST")
            val payload = JSONObject().apply {
                put("irysId", irysId)
                put("deleteBlob", deleteBlob)
            }
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                Log.e(TAG, "标记后端记忆迁移失败: HTTP ${connection.responseCode}${if (!errorBody.isNullOrBlank()) " $errorBody" else ""}")
                return@withContext false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "标记后端记忆迁移异常", e)
            false
        }
    }

    suspend fun syncSubscription(
        walletAddress: String,
        planId: String,
        startDate: Long,
        endDate: Long,
        amount: Double,
        transactionId: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/subscriptions/sync")
            val connection = createConnection(url, "POST")

            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("planId", planId)
                put("startDate", startDate)
                put("endDate", endDate)
                put("amount", amount)
                put("transactionId", transactionId)
            }

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "同步订阅状态失败", e)
            false
        }
    }

    suspend fun getTransactionHistory(
        walletAddress: String,
        limit: Int = 200,
        offset: Int = 0
    ): TransactionHistoryResult? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/user/$walletAddress/transactions?limit=$limit&offset=$offset")
            val connection = createConnection(url, "GET")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "获取交易历史失败: ${connection.responseCode}")
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val total = json.optInt("total", 0)
            val transactions = json.optJSONArray("transactions")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val t = arr.optJSONObject(i) ?: return@mapNotNull null
                    val createdAtSeconds = t.optLong("created_at", 0L)
                    MemoTransactionLogData(
                        id = t.optLong("id", 0L),
                        walletAddress = t.optString("wallet_address", walletAddress),
                        transactionType = t.optString("transaction_type", "unknown"),
                        amount = t.optInt("amount", 0),
                        description = t.optString("description", ""),
                        createdAt = if (createdAtSeconds > 0) createdAtSeconds * 1000 else 0L,
                        metadataJson = if (t.isNull("metadata")) null else t.opt("metadata")?.toString()
                    )
                }.filter { it.id > 0L }
            } ?: emptyList()

            TransactionHistoryResult(
                total = total,
                limit = json.optInt("limit", limit),
                offset = json.optInt("offset", offset),
                transactions = transactions
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取交易历史异常", e)
            null
        }
    }

    suspend fun getAiQuotaStatus(walletAddress: String): AiQuotaStatus? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/ai/quota/status?wallet=$walletAddress")
            val connection = createConnection(url, "GET")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                if (connection.responseCode != HttpURLConnection.HTTP_NOT_FOUND) {
                    Log.e(TAG, "获取 AI 配额失败: ${connection.responseCode}")
                }
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            AiQuotaStatus(
                walletAddress = json.optString("walletAddress", walletAddress),
                dailyUsed = json.optInt("dailyUsed", 0),
                dailyLimit = json.optInt("dailyLimit", 6000),
                monthlyUsed = json.optInt("monthlyUsed", 0),
                monthlyLimit = json.optInt("monthlyLimit", 140000)
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取 AI 配额异常", e)
            null
        }
    }
    
    // ============================================
    // 聊天数据 API
    // ============================================
    
    /**
     * 获取聊天会话列表
     */
    suspend fun getChatSessions(walletAddress: String, limit: Int = 50, offset: Int = 0): List<ChatSessionData>? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/chat/sessions?wallet=$walletAddress&limit=$limit&offset=$offset")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val sessionsArray = json.optJSONArray("sessions") ?: return@withContext emptyList()
                
                (0 until sessionsArray.length()).map { i ->
                    val s = sessionsArray.getJSONObject(i)
                    ChatSessionData(
                        id = s.getString("id"),
                        title = s.optString("title", "新对话"),
                        createdAt = s.getLong("createdAt"),
                        updatedAt = s.getLong("updatedAt"),
                        messageCount = s.optInt("messageCount", 0),
                        totalTokens = s.optInt("totalTokens", 0),
                        totalMemoEarned = s.optInt("totalMemoEarned", 0)
                    )
                }
            } else {
                Log.e(TAG, "获取会话列表失败: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取会话列表异常", e)
            null
        }
    }
    
    /**
     * 创建聊天会话
     */
    suspend fun createChatSession(walletAddress: String, title: String = "新对话"): ChatSessionData? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/chat/sessions")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("title", title)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val session = json.getJSONObject("session")
                
                ChatSessionData(
                    id = session.getString("id"),
                    title = session.optString("title", "新对话"),
                    createdAt = session.getLong("createdAt"),
                    updatedAt = session.getLong("updatedAt"),
                    messageCount = 0,
                    totalTokens = 0,
                    totalMemoEarned = 0
                )
            } else {
                Log.e(TAG, "创建会话失败: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建会话异常", e)
            null
        }
    }
    
    /**
     * 获取会话消息
     */
    suspend fun getChatMessages(sessionId: String, limit: Int = 100, afterTimestamp: Long? = null): List<ChatMessageData>? = withContext(Dispatchers.IO) {
        try {
            var urlStr = "$BASE_URL/api/v1/chat/sessions/$sessionId/messages?limit=$limit"
            if (afterTimestamp != null) {
                urlStr += "&after=$afterTimestamp"
            }
            val url = URL(urlStr)
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val messagesArray = json.optJSONArray("messages") ?: return@withContext emptyList()
                
                (0 until messagesArray.length()).map { i ->
                    val m = messagesArray.getJSONObject(i)
                    ChatMessageData(
                        id = m.getString("id"),
                        sessionId = m.getString("sessionId"),
                        text = m.getString("text"),
                        isUser = m.getBoolean("isUser"),
                        timestamp = m.getLong("timestamp"),
                        tokensUsed = m.optInt("tokensUsed", 0),
                        rewardedMemo = m.optInt("rewardedMemo", 0),
                        isPersonaRelevant = m.optBoolean("isPersonaRelevant", false),
                        relevanceScore = m.optDouble("relevanceScore", 0.0).toFloat(),
                        isError = m.optBoolean("isError", false)
                    )
                }
            } else {
                Log.e(TAG, "获取消息失败: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取消息异常", e)
            null
        }
    }
    
    /**
     * 批量同步消息到后端
     */
    suspend fun syncChatMessages(
        walletAddress: String,
        sessionId: String,
        messages: List<ChatMessageData>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/chat/messages")
            val connection = createConnection(url, "POST")
            
            val messagesArray = JSONArray()
            messages.forEach { m ->
                messagesArray.put(JSONObject().apply {
                    put("id", m.id)
                    put("text", m.text)
                    put("isUser", m.isUser)
                    put("timestamp", m.timestamp)
                    put("tokensUsed", m.tokensUsed)
                    put("rewardedMemo", m.rewardedMemo)
                    put("isPersonaRelevant", m.isPersonaRelevant)
                    put("relevanceScore", m.relevanceScore)
                    put("isError", m.isError)
                })
            }
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("sessionId", sessionId)
                put("messages", messagesArray)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "同步消息失败", e)
            false
        }
    }
    
    // ============================================
    // 人格数据 API
    // ============================================
    
    /**
     * 获取人格数据
     */
    suspend fun getPersona(walletAddress: String): PersonaData? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/persona?wallet=$walletAddress")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val persona = json.optJSONObject("persona") ?: return@withContext null
                val questionnaireAnswersObj = persona.optJSONObject("questionnaireAnswers")
                val questionnaireAnswers = questionnaireAnswersObj?.let { obj ->
                    buildMap<String, String> {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = obj.opt(key)
                            put(key, value?.toString() ?: "")
                        }
                    }
                }
                val completedAt = persona.optLong("questionnaireCompletedAt", 0L).takeIf { it > 0L }
                val personaProfileV2Json = persona.opt("personaProfileV2")?.let { v ->
                    when (v) {
                        is JSONObject -> v.toString()
                        else -> v.toString().takeIf { it.isNotBlank() && it != "null" }
                    }
                }
                
                PersonaData(
                    openness = persona.optDouble("openness", 0.5).toFloat(),
                    conscientiousness = persona.optDouble("conscientiousness", 0.5).toFloat(),
                    extraversion = persona.optDouble("extraversion", 0.5).toFloat(),
                    agreeableness = persona.optDouble("agreeableness", 0.5).toFloat(),
                    neuroticism = persona.optDouble("neuroticism", 0.5).toFloat(),
                    sampleSize = persona.optInt("sampleSize", 0),
                    analyzedAt = persona.optLong("analyzedAt", 0),
                    syncRate = persona.optDouble("syncRate", 0.0).toFloat(),
                    questionnaireCompleted = persona.optBoolean("questionnaireCompleted", false),
                    questionnaireCompletedAt = completedAt,
                    questionnaireAnswers = questionnaireAnswers,
                    personaProfileV2Json = personaProfileV2Json
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取人格数据异常", e)
            null
        }
    }

    suspend fun updatePersonaProfileV2(walletAddress: String, personaProfileV2Json: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/persona/profile-v2")
            val connection = createConnection(url, "PUT")

            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("personaProfileV2", JSONObject(personaProfileV2Json))
            }

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "更新人格画像V2失败", e)
            false
        }
    }
    
    /**
     * 更新人格数据
     */
    suspend fun updatePersona(walletAddress: String, persona: PersonaData): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/persona")
            val connection = createConnection(url, "PUT")
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("openness", persona.openness)
                put("conscientiousness", persona.conscientiousness)
                put("extraversion", persona.extraversion)
                put("agreeableness", persona.agreeableness)
                put("neuroticism", persona.neuroticism)
                put("sampleSize", persona.sampleSize)
                put("syncRate", persona.syncRate)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "更新人格数据失败", e)
            false
        }
    }
    
    /**
     * 提交问卷答案
     */
    suspend fun submitQuestionnaire(
        walletAddress: String,
        answers: Map<String, Any>,
        personaScores: PersonaData
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/persona/questionnaire")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("answers", JSONObject(answers))
                put("personaScores", JSONObject().apply {
                    put("openness", personaScores.openness)
                    put("conscientiousness", personaScores.conscientiousness)
                    put("extraversion", personaScores.extraversion)
                    put("agreeableness", personaScores.agreeableness)
                    put("neuroticism", personaScores.neuroticism)
                })
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "提交问卷失败", e)
            false
        }
    }
    
    // ============================================
    // 向量数据 API
    // ============================================
    
    /**
     * 语义搜索
     */
    suspend fun searchVectors(
        walletAddress: String,
        queryVector: FloatArray,
        topK: Int = 5,
        threshold: Float = 0.7f
    ): List<VectorSearchResult>? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/vectors/search")
            val connection = createConnection(url, "POST")
            
            val vectorArray = JSONArray()
            queryVector.forEach { vectorArray.put(it.toDouble()) }
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("queryVector", vectorArray)
                put("topK", topK)
                put("threshold", threshold)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val resultsArray = json.optJSONArray("results") ?: return@withContext emptyList()
                
                (0 until resultsArray.length()).map { i ->
                    val r = resultsArray.getJSONObject(i)
                    VectorSearchResult(
                        memoryId = r.getString("memoryId"),
                        similarity = r.optDouble("similarity", 0.0).toFloat(),
                        textPreview = r.optString("textPreview", ""),
                        memoryType = r.optString("memoryType", "chat")
                    )
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "向量搜索异常", e)
            null
        }
    }
    
    /**
     * 批量上传向量
     */
    suspend fun uploadVectors(
        walletAddress: String,
        vectors: List<VectorUploadData>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/vectors/batch")
            val connection = createConnection(url, "POST")
            
            val vectorsArray = JSONArray()
            vectors.forEach { v ->
                val vectorJson = JSONArray()
                v.vector.forEach { vectorJson.put(it.toDouble()) }
                
                vectorsArray.put(JSONObject().apply {
                    put("memoryId", v.memoryId)
                    put("vector", vectorJson)
                    put("textPreview", v.textPreview)
                    put("textLength", v.textLength)
                    put("memoryType", v.memoryType)
                })
            }
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("vectors", vectorsArray)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "上传向量失败", e)
            false
        }
    }
    
    // ============================================
    // 奇遇问题 API
    // ============================================
    
    /**
     * 获取待回答问题
     */
    suspend fun getPendingQuestions(walletAddress: String): List<ProactiveQuestionData>? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/questions/pending?wallet=$walletAddress")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val questionsArray = json.optJSONArray("questions") ?: return@withContext emptyList()
                
                (0 until questionsArray.length()).map { i ->
                    val q = questionsArray.getJSONObject(i)
                    ProactiveQuestionData(
                        id = q.getString("id"),
                        questionText = q.getString("questionText"),
                        category = q.getString("category"),
                        status = q.optString("status", "PENDING"),
                        priority = q.optInt("priority", 0),
                        createdAt = q.optLong("createdAt", 0),
                        expiresAt = q.optLong("expiresAt", 0)
                    )
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取待回答问题异常", e)
            null
        }
    }
    
    /**
     * 回答问题
     */
    suspend fun answerQuestion(
        questionId: String,
        answerText: String,
        personaImpact: Map<String, Float>?,
        rewardedMemo: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/questions/$questionId/answer")
            val connection = createConnection(url, "PUT")
            
            val payload = JSONObject().apply {
                put("answerText", answerText)
                if (personaImpact != null) {
                    put("personaImpact", JSONObject(personaImpact))
                }
                put("rewardedMemo", rewardedMemo)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "回答问题失败", e)
            false
        }
    }
    
    /**
     * 创建问题
     */
    suspend fun createQuestion(
        walletAddress: String,
        questionText: String,
        category: String,
        priority: Int = 0
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/questions")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("walletAddress", walletAddress)
                put("questionText", questionText)
                put("category", category)
                put("priority", priority)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                json.optJSONObject("question")?.optString("id")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建问题失败", e)
            null
        }
    }
    
    // ============================================
    // 🆕 后端优先架构 - 核心 API
    // ============================================
    
    /**
     * 获取实时余额和用户状态（后端优先架构核心端点）
     * 这是获取用户数据的主要方法
     */
    suspend fun getRealTimeBalance(walletAddress: String): RealTimeBalanceResult? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/user/$walletAddress/balance")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                RealTimeBalanceResult(
                    walletAddress = json.getString("walletAddress"),
                    memoBalance = json.getInt("memoBalance"),
                    currentTier = json.getInt("currentTier"),
                    tierName = json.getString("tierName"),
                    tierMultiplier = json.getDouble("tierMultiplier").toFloat(),
                    totalMemoEarned = json.getInt("totalMemoEarned"),
                    subscriptionType = json.optString("subscriptionType", "FREE"),
                    subscriptionExpiry = json.optLong("subscriptionExpiry", 0).takeIf { it > 0 },
                    onboardingCompleted = json.optBoolean("onboardingCompleted", false),
                    dailyDialogueCount = json.optInt("dailyDialogueCount", 0),
                    hasCheckedInToday = json.optBoolean("hasCheckedInToday", false),
                    hasFirstChatToday = json.optBoolean("hasFirstChatToday", false),
                    consecutiveCheckInDays = json.optInt("consecutiveCheckInDays", 0),
                    weeklyCheckInProgress = json.optInt("weeklyCheckInProgress", 0),
                    totalCheckInDays = json.optInt("totalCheckInDays", 0),
                    syncedAt = json.getString("syncedAt")
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                Log.w(TAG, "获取实时余额认证失败 (401)，清理本地会话。body=$errorBody")
                BackendAuthManager.getInstance(context).clear()
                null
            } else {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                Log.e(TAG, "获取实时余额失败: ${connection.responseCode}, body=$errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取实时余额异常", e)
            null
        }
    }
    
    /**
     * 执行签到（通过后端）
     */
    suspend fun checkIn(walletAddress: String): CheckInApiResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/user/$walletAddress/check-in")
            val connection = createConnection(url, "POST")
            connection.outputStream.bufferedWriter().use { it.write("{}") }
            
            val responseCode = connection.responseCode
            val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            
            val json = JSONObject(response)
            
            if (json.optBoolean("success", false)) {
                CheckInApiResult.Success(
                    checkInDate = json.getString("checkInDate"),
                    consecutiveDays = json.getInt("consecutiveDays"),
                    weeklyProgress = json.getInt("weeklyProgress"),
                    reward = json.getInt("reward"),
                    tierMultiplier = json.getDouble("tierMultiplier").toFloat(),
                    newBalance = json.getInt("newBalance"),
                    secondsUntilReset = json.optInt("secondsUntilReset", 0)
                )
            } else {
                val error = json.optString("error", "unknown")
                if (error == "already_checked_in") {
                    CheckInApiResult.AlreadyCheckedIn(
                        secondsUntilReset = json.optInt("secondsUntilReset", 0)
                    )
                } else {
                    CheckInApiResult.Error(json.optString("message", AppStrings.tr("签到失败", "Check-in failed")))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "签到异常", e)
            CheckInApiResult.Error(networkErrorMessage(e.message))
        }
    }
    
    /**
     * 记录对话奖励（通过后端）
     */
    suspend fun recordDialogueReward(
        walletAddress: String,
        sessionId: String?,
        isFirstChat: Boolean,
        resonanceGrade: String?,
        resonanceScore: Int?
    ): DialogueRewardResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/user/$walletAddress/dialogue-reward")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("sessionId", sessionId ?: "")
                put("isFirstChat", isFirstChat)
                if (resonanceGrade != null) put("resonanceGrade", resonanceGrade)
                if (resonanceScore != null) put("resonanceScore", resonanceScore)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                if (json.optBoolean("success", false)) {
                    val breakdown = json.optJSONObject("breakdown")
                    DialogueRewardResult.Success(
                        dialogueIndex = json.getInt("dialogueIndex"),
                        reward = json.getInt("reward"),
                        baseReward = breakdown?.optInt("base", 0) ?: 0,
                        firstChatBonus = breakdown?.optInt("firstChatBonus", 0) ?: 0,
                        resonanceBonus = breakdown?.optInt("resonanceBonus", 0) ?: 0,
                        tierMultiplier = breakdown?.optDouble("tierMultiplier", 1.0)?.toFloat() ?: 1f,
                        isOverLimit = json.optBoolean("isOverLimit", false),
                        newBalance = json.getInt("newBalance")
                    )
                } else {
                    DialogueRewardResult.Error(json.optString("message", AppStrings.tr("记录奖励失败", "Failed to record reward")))
                }
            } else {
                DialogueRewardResult.Error(serverErrorMessage(connection.responseCode))
            }
        } catch (e: Exception) {
            Log.e(TAG, "记录对话奖励异常", e)
            DialogueRewardResult.Error(networkErrorMessage(e.message))
        }
    }
    
    /**
     * 完成奇遇任务（通过后端）
     */
    suspend fun completeAdventure(
        walletAddress: String,
        questionId: String,
        questionText: String?
    ): AdventureRewardResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/user/$walletAddress/adventure")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("questionId", questionId)
                if (questionText != null) put("questionText", questionText)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            val responseCode = connection.responseCode
            val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            
            val json = JSONObject(response)
            
            if (json.optBoolean("success", false)) {
                AdventureRewardResult.Success(
                    questionId = json.getString("questionId"),
                    reward = json.getInt("reward"),
                    tierMultiplier = json.getDouble("tierMultiplier").toFloat(),
                    newBalance = json.getInt("newBalance")
                )
            } else {
                val error = json.optString("error", "unknown")
                if (error == "already_completed") {
                    AdventureRewardResult.AlreadyCompleted
                } else {
                    AdventureRewardResult.Error(json.optString("message", AppStrings.tr("奇遇完成失败", "Adventure completion failed")))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "完成奇遇异常", e)
            AdventureRewardResult.Error(networkErrorMessage(e.message))
        }
    }
    
    // ============================================
    // 🆕 Solana 链上操作代理
    // ============================================
    
    /**
     * 获取 SOL 余额（通过后端代理）
     */
    suspend fun getSolanaBalance(walletAddress: String): SolanaBalanceResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/solana/balance/$walletAddress")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                SolanaBalanceResult.Success(
                    wallet = json.getString("wallet"),
                    lamports = json.getLong("lamports"),
                    sol = json.getDouble("sol"),
                    lastUpdate = json.getString("lastUpdate")
                )
            } else {
                SolanaBalanceResult.Error(serverErrorMessage(connection.responseCode))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 SOL 余额异常", e)
            SolanaBalanceResult.Error(networkErrorMessage(e.message))
        }
    }
    
    /**
     * 获取 Token 余额（通过后端代理）
     */
    suspend fun getSolanaTokens(walletAddress: String): SolanaTokensResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/solana/tokens/$walletAddress")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val tokensArray = json.optJSONArray("tokens") ?: JSONArray()
                
                val tokens = (0 until tokensArray.length()).map { i ->
                    val t = tokensArray.getJSONObject(i)
                    TokenBalance(
                        mint = t.getString("mint"),
                        balance = t.getDouble("balance"),
                        decimals = t.getInt("decimals"),
                        address = t.getString("address")
                    )
                }
                
                SolanaTokensResult.Success(
                    wallet = json.getString("wallet"),
                    tokens = tokens,
                    lastUpdate = json.getString("lastUpdate")
                )
            } else {
                SolanaTokensResult.Error(serverErrorMessage(connection.responseCode))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 Token 余额异常", e)
            SolanaTokensResult.Error(networkErrorMessage(e.message))
        }
    }
    
    /**
     * 获取质押状态（通过后端代理）
     */
    suspend fun getSolanaStaking(walletAddress: String): SolanaStakingResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/solana/staking/$walletAddress")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                SolanaStakingResult.Success(
                    wallet = json.getString("wallet"),
                    hasStaking = json.getBoolean("hasStaking"),
                    stakedAmount = json.optLong("stakedAmount", 0),
                    stakedSol = json.optDouble("stakedSol", 0.0),
                    stakingBonus = json.optDouble("stakingBonus", 1.0).toFloat(),
                    unlockTime = json.optLong("unlockTime", 0).takeIf { it > 0 },
                    lastUpdate = json.getString("lastUpdate")
                )
            } else {
                SolanaStakingResult.Error(serverErrorMessage(connection.responseCode))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取质押状态异常", e)
            SolanaStakingResult.Error(networkErrorMessage(e.message))
        }
    }
    
    /**
     * 验证 Solana 交易（通过后端代理）
     */
    suspend fun verifySolanaTransaction(signature: String): TransactionVerifyResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/solana/verify-transaction")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("signature", signature)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                TransactionVerifyResult.Success(
                    verified = json.getBoolean("verified"),
                    signature = json.getString("signature"),
                    status = json.getString("status"),
                    slot = json.optLong("slot", 0),
                    blockTime = json.optLong("blockTime", 0),
                    fee = json.optLong("fee", 0)
                )
            } else {
                TransactionVerifyResult.Error(serverErrorMessage(connection.responseCode))
            }
        } catch (e: Exception) {
            Log.e(TAG, "验证交易异常", e)
            TransactionVerifyResult.Error(networkErrorMessage(e.message))
        }
    }

    // ============================================
    // 支持/反馈 API
    // ============================================

    suspend fun submitBugReport(
        description: String,
        contactEmail: String?,
        walletAddress: String?,
        includeDeviceInfo: Boolean,
        deviceInfo: String?,
        appVersion: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/support/bug-report")
            val connection = createConnection(url, "POST")

            val payload = JSONObject().apply {
                put("description", description)
                put("contactEmail", contactEmail ?: "")
                put("walletAddress", walletAddress ?: "")
                put("includeDeviceInfo", includeDeviceInfo)
                put("deviceInfo", deviceInfo ?: "")
                put("appVersion", appVersion)
                put("platform", "android")
            }

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "提交 Bug 报告失败", e)
            false
        }
    }

    // ============================================
    // i18n API
    // ============================================

    suspend fun translateUiStrings(
        targetLang: String,
        items: List<Pair<String, String>>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/i18n/translate")
            val connection = createConnection(url, "POST")

            val payload = JSONObject().apply {
                put("targetLang", targetLang)
                put("items", JSONArray().apply {
                    items.forEach { (key, text) ->
                        put(JSONObject().apply {
                            put("key", key)
                            put("text", text)
                        })
                    }
                })
            }

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "翻译请求失败: ${connection.responseCode}")
                return@withContext emptyMap()
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val translations = json.optJSONObject("translations") ?: return@withContext emptyMap()

            val result = mutableMapOf<String, String>()
            translations.keys().forEach { k ->
                result[k] = translations.optString(k, "")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "翻译请求异常", e)
            emptyMap()
        }
    }
    
    // ============================================
    // 辅助方法
    // ============================================

    private fun serverErrorMessage(status: Int): String {
        return AppStrings.trf("服务器错误: %d", "Server error: %d", status)
    }

    private fun networkErrorMessage(message: String?): String {
        return AppStrings.trf("网络错误: %s", "Network error: %s", message ?: "")
    }
    
    private fun createConnection(url: URL, method: String): HttpURLConnection {
        if (expectedBaseUrl.protocol != "https") {
            throw IllegalStateException("BACKEND_BASE_URL 必须为 https")
        }
        if (url.protocol != "https") {
            throw IllegalArgumentException("不允许非 https 请求: $url")
        }
        if (url.host != expectedBaseUrl.host) {
            throw IllegalArgumentException("不允许跨域请求: $url")
        }
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
        val token = BackendAuthManager.getInstance(context).getAccessToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "请求未携带 Authorization（无本地 access token）: ${url.path}")
        } else {
            connection.setRequestProperty("Authorization", "Bearer $token")
            Log.d(TAG, "请求携带 Authorization: ${url.path}")
        }
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        if (method in listOf("POST", "PUT", "PATCH")) {
            connection.doOutput = true
        }
        return connection
    }
    
    private fun parseFullUserProfile(json: JSONObject): FullUserProfile {
        val profile = json.getJSONObject("profile")
        val persona = json.optJSONObject("persona")
        val todayStats = json.optJSONObject("todayStats")
        val memoTransactions = json.optJSONArray("memoTransactions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                val createdAtSeconds = t.optLong("created_at", 0L)
                val createdAtMs = if (createdAtSeconds > 0) createdAtSeconds * 1000 else 0L
                MemoTransactionLogData(
                    id = t.optLong("id", 0L),
                    walletAddress = t.optString("wallet_address", profile.optString("walletAddress", "")),
                    transactionType = t.optString("transaction_type", "unknown"),
                    amount = t.optInt("amount", 0),
                    description = t.optString("description", ""),
                    createdAt = createdAtMs,
                    metadataJson = if (t.isNull("metadata")) null else t.opt("metadata")?.toString()
                )
            }.filter { it.id > 0L }
        } ?: emptyList()
        
        return FullUserProfile(
            syncedAt = json.getLong("syncedAt"),
            
            // 用户基本信息
            userId = profile.getString("id"),
            walletAddress = profile.getString("walletAddress"),
            memoBalance = profile.optInt("memoBalance", 0),
            currentTier = profile.optInt("currentTier", 1),
            subscriptionType = profile.optString("subscriptionType", "FREE"),
            subscriptionExpiry = profile.optLong("subscriptionExpiry", 0).takeIf { it > 0 },
            stakedAmount = (profile.optDouble("stakedAmount", 0.0) * 1_000_000_000).toLong(),
            isFounder = profile.optBoolean("isFounder", false),
            isExpert = profile.optBoolean("isExpert", false),
            isBanned = profile.optBoolean("isBanned", false),
            totalTokensUsed = profile.optInt("totalTokensUsed", 0),
            memoriesCount = profile.optInt("memoriesCount", 0),
            
            // 人格数据
            persona = persona?.let {
                val personaProfileV2Json = it.opt("personaProfileV2")?.let { v ->
                    when (v) {
                        is JSONObject -> v.toString()
                        else -> v.toString().takeIf { it.isNotBlank() && it != "null" }
                    }
                }
                val completedAt = it.optLong("questionnaireCompletedAt", 0L).takeIf { value -> value > 0L }
                val questionnaireAnswersObj = it.optJSONObject("questionnaireAnswers")
                val questionnaireAnswers = questionnaireAnswersObj?.let { obj ->
                    buildMap<String, String> {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = obj.opt(key)
                            put(key, value?.toString() ?: "")
                        }
                    }
                }
                PersonaData(
                    openness = it.optDouble("openness", 0.5).toFloat(),
                    conscientiousness = it.optDouble("conscientiousness", 0.5).toFloat(),
                    extraversion = it.optDouble("extraversion", 0.5).toFloat(),
                    agreeableness = it.optDouble("agreeableness", 0.5).toFloat(),
                    neuroticism = it.optDouble("neuroticism", 0.5).toFloat(),
                    sampleSize = it.optInt("sampleSize", 0),
                    analyzedAt = it.optLong("analyzedAt", 0),
                    syncRate = it.optDouble("syncRate", 0.0).toFloat(),
                    questionnaireCompleted = it.optBoolean("questionnaireCompleted", false),
                    questionnaireCompletedAt = completedAt,
                    questionnaireAnswers = questionnaireAnswers,
                    personaProfileV2Json = personaProfileV2Json
                )
            },
            
            // 聊天会话
            chatSessions = json.optJSONArray("chatSessions")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    ChatSessionData(
                        id = s.getString("id"),
                        title = s.optString("title", ""),
                        createdAt = s.getLong("createdAt"),
                        updatedAt = s.getLong("updatedAt"),
                        messageCount = s.optInt("messageCount", 0),
                        totalTokens = s.optInt("totalTokens", 0),
                        totalMemoEarned = s.optInt("totalMemoEarned", 0)
                    )
                }
            } ?: emptyList(),
            
            // 今日统计
            todayDialogueCount = todayStats?.optInt("dialogueCount", 0) ?: 0,
            todayTokensUsed = todayStats?.optInt("tokensUsed", 0) ?: 0,
            todayMemoEarned = todayStats?.optInt("memoEarned", 0) ?: 0,
            hasFirstChatToday = todayStats?.optBoolean("hasFirstChat", false) ?: false,
            
            // 向量数量
            vectorCount = json.optInt("vectorCount", 0),
            memoTransactions = memoTransactions,
            
            // AI 服务配置
            aiConfig = json.optJSONObject("aiConfig")?.let { aiJson ->
                // 安全地获取字符串值，处理 JSON null 和空字符串
                fun getStringOrNull(key: String): String? {
                    if (aiJson.isNull(key)) return null
                    val value = aiJson.optString(key, "")
                    // 过滤掉 "null" 字符串和空字符串
                    return if (value.isBlank() || value == "null") null else value
                }
                
                val qwenKey = getStringOrNull("qwenApiKey")
                val embeddingKey = getStringOrNull("embeddingApiKey")
                
                AiServiceConfig(
                    qwenApiKey = qwenKey,
                    qwenEndpoint = getStringOrNull("qwenEndpoint"),
                    embeddingApiKey = embeddingKey,
                    embeddingEndpoint = getStringOrNull("embeddingEndpoint")
                )
            }
        )
    }
}

// ============================================
// 数据类定义
// ============================================

data class FullUserProfile(
    val syncedAt: Long,
    val userId: String,
    val walletAddress: String,
    val memoBalance: Int,
    val currentTier: Int,
    val subscriptionType: String,
    val subscriptionExpiry: Long?,
    val stakedAmount: Long,
    val isFounder: Boolean,
    val isExpert: Boolean,
    val isBanned: Boolean,
    val totalTokensUsed: Int,
    val memoriesCount: Int,
    val persona: PersonaData?,
    val chatSessions: List<ChatSessionData>,
    val todayDialogueCount: Int,
    val todayTokensUsed: Int,
    val todayMemoEarned: Int,
    val hasFirstChatToday: Boolean,
    val vectorCount: Int,
    val memoTransactions: List<MemoTransactionLogData> = emptyList(),
    // AI 服务配置（从后台获取）
    val aiConfig: AiServiceConfig? = null
)

/**
 * AI 服务配置
 * 从后台管理系统获取的 API 密钥和端点
 */
data class AiServiceConfig(
    val qwenApiKey: String?,
    val qwenEndpoint: String?,
    val embeddingApiKey: String?,
    val embeddingEndpoint: String?
)

data class MemoTransactionLogData(
    val id: Long,
    val walletAddress: String,
    val transactionType: String,
    val amount: Int,
    val description: String,
    val createdAt: Long,
    val metadataJson: String? = null
)

data class TransactionHistoryResult(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val transactions: List<MemoTransactionLogData>
)

data class AiQuotaStatus(
    val walletAddress: String,
    val dailyUsed: Int,
    val dailyLimit: Int,
    val monthlyUsed: Int,
    val monthlyLimit: Int
) {
    fun isDailyOver(): Boolean = dailyUsed >= dailyLimit
    fun monthlyRemainingRatio(): Float {
        if (monthlyLimit <= 0) return 0f
        return ((monthlyLimit - monthlyUsed).coerceAtLeast(0)).toFloat() / monthlyLimit.toFloat()
    }
}

data class ChatSessionData(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val totalTokens: Int,
    val totalMemoEarned: Int
)

data class ChatMessageData(
    val id: String,
    val sessionId: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val tokensUsed: Int = 0,
    val rewardedMemo: Int = 0,
    val isPersonaRelevant: Boolean = false,
    val relevanceScore: Float = 0f,
    val isError: Boolean = false
)

data class PersonaData(
    val openness: Float,
    val conscientiousness: Float,
    val extraversion: Float,
    val agreeableness: Float,
    val neuroticism: Float,
    val sampleSize: Int = 0,
    val analyzedAt: Long = 0,
    val syncRate: Float = 0f,
    val questionnaireCompleted: Boolean = false,
    val questionnaireCompletedAt: Long? = null,
    val questionnaireAnswers: Map<String, String>? = null,
    val personaProfileV2Json: String? = null
)

data class VectorSearchResult(
    val memoryId: String,
    val similarity: Float,
    val textPreview: String,
    val memoryType: String
)

data class VectorUploadData(
    val memoryId: String,
    val vector: List<Float>,
    val originalText: String = "",
    val category: String = "general",
    val textPreview: String = "",
    val textLength: Int = 0,
    val memoryType: String = "chat"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorUploadData
        return memoryId == other.memoryId
    }
    override fun hashCode(): Int = memoryId.hashCode()
}

data class ProactiveQuestionData(
    val id: String,
    val questionText: String,
    val category: String,
    val status: String,
    val priority: Int,
    val createdAt: Long,
    val expiresAt: Long
) {
    /**
     * 转换为本地实体
     */
    fun toEntity(): com.soulon.app.proactive.ProactiveQuestionEntity {
        return com.soulon.app.proactive.ProactiveQuestionEntity(
            id = id,
            questionText = questionText,
            category = category,
            status = when (status) {
                "pending" -> com.soulon.app.proactive.QuestionStatus.PENDING.name
                "notified" -> com.soulon.app.proactive.QuestionStatus.NOTIFIED.name
                "answered" -> com.soulon.app.proactive.QuestionStatus.ANSWERED.name
                "skipped" -> com.soulon.app.proactive.QuestionStatus.SKIPPED.name
                "expired" -> com.soulon.app.proactive.QuestionStatus.EXPIRED.name
                else -> com.soulon.app.proactive.QuestionStatus.PENDING.name
            },
            priority = priority,
            createdAt = createdAt
        )
    }
}

// ============================================
// 🆕 后端优先架构 - 结果类型
// ============================================

/**
 * 实时余额结果
 */
data class RealTimeBalanceResult(
    val walletAddress: String,
    val memoBalance: Int,
    val currentTier: Int,
    val tierName: String,
    val tierMultiplier: Float,
    val totalMemoEarned: Int,
    val subscriptionType: String,
    val subscriptionExpiry: Long?,
    val onboardingCompleted: Boolean,
    val dailyDialogueCount: Int,
    val hasCheckedInToday: Boolean,
    val hasFirstChatToday: Boolean,
    val consecutiveCheckInDays: Int,
    val weeklyCheckInProgress: Int,
    val totalCheckInDays: Int,
    val syncedAt: String
)

/**
 * 签到 API 结果
 */
sealed class CheckInApiResult {
    data class Success(
        val checkInDate: String,
        val consecutiveDays: Int,
        val weeklyProgress: Int,
        val reward: Int,
        val tierMultiplier: Float,
        val newBalance: Int,
        val secondsUntilReset: Int
    ) : CheckInApiResult()
    
    data class AlreadyCheckedIn(
        val secondsUntilReset: Int
    ) : CheckInApiResult()
    
    data class Error(val message: String) : CheckInApiResult()
}

/**
 * 对话奖励结果
 */
sealed class DialogueRewardResult {
    data class Success(
        val dialogueIndex: Int,
        val reward: Int,
        val baseReward: Int,
        val firstChatBonus: Int,
        val resonanceBonus: Int,
        val tierMultiplier: Float,
        val isOverLimit: Boolean,
        val newBalance: Int
    ) : DialogueRewardResult()
    
    data class Error(val message: String) : DialogueRewardResult()
}

/**
 * 奇遇奖励结果
 */
sealed class AdventureRewardResult {
    data class Success(
        val questionId: String,
        val reward: Int,
        val tierMultiplier: Float,
        val newBalance: Int
    ) : AdventureRewardResult()
    
    object AlreadyCompleted : AdventureRewardResult()
    
    data class Error(val message: String) : AdventureRewardResult()
}

/**
 * SOL 余额结果
 */
sealed class SolanaBalanceResult {
    data class Success(
        val wallet: String,
        val lamports: Long,
        val sol: Double,
        val lastUpdate: String
    ) : SolanaBalanceResult()
    
    data class Error(val message: String) : SolanaBalanceResult()
}

/**
 * Token 余额
 */
data class TokenBalance(
    val mint: String,
    val balance: Double,
    val decimals: Int,
    val address: String
)

/**
 * Token 余额结果
 */
sealed class SolanaTokensResult {
    data class Success(
        val wallet: String,
        val tokens: List<TokenBalance>,
        val lastUpdate: String
    ) : SolanaTokensResult()
    
    data class Error(val message: String) : SolanaTokensResult()
}

/**
 * 质押状态结果
 */
sealed class SolanaStakingResult {
    data class Success(
        val wallet: String,
        val hasStaking: Boolean,
        val stakedAmount: Long,
        val stakedSol: Double,
        val stakingBonus: Float,
        val unlockTime: Long?,
        val lastUpdate: String
    ) : SolanaStakingResult()
    
    data class Error(val message: String) : SolanaStakingResult()
}

/**
 * 交易验证结果
 */
sealed class TransactionVerifyResult {
    data class Success(
        val verified: Boolean,
        val signature: String,
        val status: String,
        val slot: Long,
        val blockTime: Long,
        val fee: Long
    ) : TransactionVerifyResult()
    
    data class Error(val message: String) : TransactionVerifyResult()
}
