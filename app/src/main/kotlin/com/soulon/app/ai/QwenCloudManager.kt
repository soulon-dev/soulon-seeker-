package com.soulon.app.ai

import android.content.Context
import com.soulon.app.BuildConfig
import com.soulon.app.auth.BackendAuthManager
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.LocaleManager
import com.soulon.app.wallet.WalletScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import com.soulon.app.x402.PaymentRequiredException
import com.soulon.app.x402.X402Challenge
import com.soulon.app.x402.X402Parser
import java.util.concurrent.TimeUnit

/**
 * Qwen 云 API 管理器
 * 
 * 使用阿里云灵积（DashScope）API 服务
 * 
 * 优势：
 * - 无需下载模型（节省存储）
 * - 零内存占用（云端推理）
 * - 高性能（云端 GPU 集群）
 * - 灵活切换模型（turbo/plus/max）
 * 
 * Phase 3 Week 1: Task_Qwen_Init（云 API 版本）
 */
class QwenCloudManager(
    private val context: Context
) {

    data class Message(
        val role: String,
        val content: String
    )
    
    companion object {
        // DashScope 国际版 API 端点（OpenAI 兼容模式）
        // private const val API_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
        
        // 后端代理端点（更安全，不暴露 API Key）
        private val API_BASE_URL = BuildConfig.BACKEND_BASE_URL + "/api/v1/ai/proxy/completions"
        
        // 模型选择
        const val MODEL_TURBO = "qwen-turbo"  // 速度快，成本低
        const val MODEL_PLUS = "qwen-plus"   // 效果好
        const val MODEL_MAX = "qwen-max"     // 最强（千亿参数）
        const val MODEL_AUTO = "auto"
        
        // 生成参数
        private const val MAX_TOKENS = 2048
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val DEFAULT_TOP_P = 0.9f
        
        // 超时设置
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
        private const val WRITE_TIMEOUT = 60L
        
        // 性能参数
        private const val WARM_UP_PROMPT = "你好"
        private const val WARM_UP_MAX_TOKENS = 10
        
        /**
         * 默认系统提示词
         */
        fun getDefaultSystemPrompt(): String {
            val lang = com.soulon.app.i18n.AppStrings.getCurrentLanguage()
            return if (lang.startsWith("zh")) {
                """你是一个专业的 AI 助手，擅长理解用户需求并提供有价值的回答。
请保持回答简洁、准确、友好。"""
            } else {
                """You are a professional AI assistant, skilled in understanding user needs and providing valuable responses.
Please keep your answers concise, accurate, and friendly.
IMPORTANT: Reply in the same language as the user's input."""
            }
        }
    }
    
    // HTTP 客户端
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("Accept-Language", LocaleManager.getAcceptLanguage(context))
                val token = BackendAuthManager.getInstance(context).getAccessToken()
                if (!token.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $token")
                }
                chain.proceed(builder.build())
            }
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .build()
    }
    
    private var isInitialized = false
    private var isWarmedUp = false
    private var currentModel = MODEL_AUTO
    @Volatile
    private var supportsAutoModel = true
    
    /**
     * 初始化（云 API 无需加载模型）
     * 
     * @return true 如果 API 可用
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.i("════════════════════════════════════════")
            Timber.i("☁️ 初始化 Qwen 云 API...")
            Timber.i("════════════════════════════════════════")
            
            val startTime = System.currentTimeMillis()
            
            // Step 1: 检查 API Key (现在使用后端代理，不需要客户端持有 API Key)
            // Timber.i("Step 1: 验证 API Key...")
            // if (apiKey.isBlank() || apiKey.length < 20) { ... }
            
            // Step 2: 测试网络连接（跳过，直接配置）
            Timber.i("Step 2: 配置 HTTP 客户端...")
            Timber.i("✅ HTTP 客户端配置完成")
            
            // Step 3: 配置完成
            Timber.i("Step 3: 配置云 API (后端代理)...")
            Timber.i("  • 使用模型: $currentModel")
            Timber.i("  • API 端点: $API_BASE_URL")
            Timber.i("  • 安全模式: 已启用 (API Key 不接触客户端)")
            
            isInitialized = true
            
            val elapsedTime = System.currentTimeMillis() - startTime
            Timber.i("════════════════════════════════════════")
            Timber.i("✅ Qwen 云 API 初始化成功！")
            Timber.i("   耗时: ${elapsedTime} ms")
            Timber.i("════════════════════════════════════════")
            
            true
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 初始化 Qwen 云 API 失败")
            Timber.e("错误详情: ${e.message}")
            isInitialized = false
            false
        }
    }
    
    /**
     * 预热（测试 API 调用）
     */
    suspend fun warmUp(): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Timber.w("API 未初始化，无法预热")
            return@withContext false
        }
        
        if (isWarmedUp) {
            Timber.d("API 已预热，跳过")
            return@withContext true
        }
        
        try {
            Timber.i("🔥 开始预热 API...")
            val startTime = System.currentTimeMillis()
            
            // 执行一次简单调用
            var tokenCount = 0
            generateStream(
                prompt = WARM_UP_PROMPT,
                maxNewTokens = WARM_UP_MAX_TOKENS
            ).collect { 
                tokenCount++
            }
            
            val elapsedTime = System.currentTimeMillis() - startTime
            isWarmedUp = true
            
            Timber.i("✅ API 预热完成")
            Timber.i("   生成 Token 数: $tokenCount")
            Timber.i("   耗时: ${elapsedTime} ms")
            
            true
            
        } catch (e: Exception) {
            Timber.e(e, "❌ API 预热失败")
            false
        }
    }
    
    /**
     * 流式推理（调用云 API）
     * 
     * @param prompt 用户输入
     * @param systemPrompt 系统提示词
     * @param maxNewTokens 最大生成 Token 数
     * @param temperature 温度参数
     * @param model 使用的模型（turbo/plus/max）
     * @return 生成文本流
     */
    fun generateStream(
        prompt: String,
        systemPrompt: String? = null,
        maxNewTokens: Int = 512,
        temperature: Float = DEFAULT_TEMPERATURE,
        model: String = currentModel,
        functionType: String = "conversation"
    ): Flow<String> = flow {
        if (!isInitialized) {
            throw IllegalStateException(
                AppStrings.tr("API 未初始化，请先调用 initialize()", "API not initialized. Call initialize() first.")
            )
        }
        
        // 动态获取默认 Prompt
        val actualSystemPrompt = systemPrompt ?: getDefaultSystemPrompt()
        
        try {
            Timber.d("开始调用云 API: prompt='${prompt.take(50)}...', model=$model")
            
            val startTime = System.currentTimeMillis()
            var firstTokenTime = 0L
            var tokenCount = 0
            
            // 构建请求
            val effectiveModel = if (model == MODEL_AUTO && !supportsAutoModel) MODEL_TURBO else model
            val requestBody = buildRequestBody(
                prompt = prompt,
                systemPrompt = actualSystemPrompt,
                maxTokens = maxNewTokens,
                temperature = temperature,
                model = effectiveModel,
                stream = false, // 暂时使用非流式（流式需要 SSE 解析）
                functionType = functionType
            )
            
            Timber.d("📤 Chat API 请求体: $requestBody")
            Timber.d("🔗 Chat API 端点: $API_BASE_URL")
            
            val request = Request.Builder()
                .url(API_BASE_URL)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                // 🔒 安全增强：不再从客户端发送 API Key
                // 后端会从 Secrets 环境变量中自动注入 QWEN_API_KEY
                // .header("X-Qwen-Api-Key", apiKey) 
                .header("Content-Type", "application/json")
                .build()
            
            Timber.d("发送 API 请求...")
            
            // 在 IO 线程执行网络请求
            val responseText = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (!response.isSuccessful) {
                        if (response.code == 402) {
                            val headers = response.headers.toMultimap().mapValues { (_, v) -> v.joinToString(",") }
                            val body = responseBody.orEmpty()
                            throw PaymentRequiredException(
                                X402Challenge(
                                    statusCode = 402,
                                    headers = headers,
                                    bodyRaw = body,
                                    bodyJson = X402Parser.tryParseJson(body),
                                )
                            )
                        }
                        if (effectiveModel == MODEL_AUTO &&
                            response.code == 404 &&
                            responseBody?.contains("model_not_found", ignoreCase = true) == true &&
                            responseBody.contains("`auto`")
                        ) {
                            supportsAutoModel = false
                            currentModel = MODEL_TURBO
                            Timber.w("后端不支持 model=auto，自动回退到 $MODEL_TURBO")
                            val fallbackBody = buildRequestBody(
                                prompt = prompt,
                                systemPrompt = actualSystemPrompt,
                                maxTokens = maxNewTokens,
                                temperature = temperature,
                                model = MODEL_TURBO,
                                stream = false,
                                functionType = functionType
                            )
                            val fallbackRequest = Request.Builder()
                                .url(API_BASE_URL)
                                .post(fallbackBody.toRequestBody("application/json".toMediaType()))
                                .header("Content-Type", "application/json")
                                .build()
                            return@use httpClient.newCall(fallbackRequest).execute().use { fallbackResp ->
                                val fb = fallbackResp.body?.string()
                                if (!fallbackResp.isSuccessful) {
                                    if (fallbackResp.code == 402) {
                                        val headers = fallbackResp.headers.toMultimap().mapValues { (_, v) -> v.joinToString(",") }
                                        val body = fb.orEmpty()
                                        throw PaymentRequiredException(
                                            X402Challenge(
                                                statusCode = 402,
                                                headers = headers,
                                                bodyRaw = body,
                                                bodyJson = X402Parser.tryParseJson(body),
                                            )
                                        )
                                    }
                                    Timber.e("❌ API 调用失败: ${fallbackResp.code}")
                                    Timber.e("响应: $fb")
                                    throw Exception(
                                        AppStrings.trf(
                                            "API 调用失败: %d - %s",
                                            "API request failed: %d - %s",
                                            fallbackResp.code,
                                            fb
                                        )
                                    )
                                }
                                fb ?: throw Exception(AppStrings.tr("响应体为空", "Empty response body"))
                            }
                        }
                        Timber.e("❌ API 调用失败: ${response.code}")
                        Timber.e("响应: $responseBody")
                        throw Exception(
                            AppStrings.trf(
                                "API 调用失败: %d - %s",
                                "API request failed: %d - %s",
                                response.code,
                                responseBody
                            )
                        )
                    }
                    
                    if (responseBody == null) {
                        throw Exception(AppStrings.tr("响应体为空", "Empty response body"))
                    }
                    
                    // 返回响应体供后续处理
                    responseBody
                }
            }
            
            // 记录首 Token 延迟
            firstTokenTime = System.currentTimeMillis() - startTime
            Timber.d("⏱️ 首 Token 延迟: ${firstTokenTime} ms")
            
            // 解析响应
            val result = parseResponse(responseText)
            
            // 逐字发送（模拟流式）
            result.forEach { char ->
                emit(char.toString())
                tokenCount++
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            val tokensPerSecond = if (totalTime > 0) {
                tokenCount / (totalTime / 1000.0)
            } else {
                0.0
            }
            
            Timber.i("API 调用完成:")
            Timber.i("  • 生成字符数: $tokenCount")
            Timber.i("  • 首 Token 延迟: ${firstTokenTime} ms")
            Timber.i("  • 总耗时: ${totalTime} ms")
            Timber.i("  • 生成速度: ${String.format("%.2f", tokensPerSecond)} chars/s")
            
        } catch (e: Exception) {
            Timber.e(e, "API 调用失败")
            throw e
        }
    }

    fun generateStream(
        messages: List<Message>,
        maxNewTokens: Int = 512,
        temperature: Float = DEFAULT_TEMPERATURE,
        model: String = currentModel,
        functionType: String = "conversation"
    ): Flow<String> = flow {
        if (!isInitialized) {
            throw IllegalStateException(
                AppStrings.tr("API 未初始化，请先调用 initialize()", "API not initialized. Call initialize() first.")
            )
        }
        try {
            Timber.d("开始调用云 API: messages=${messages.size}, model=$model")

            val startTime = System.currentTimeMillis()
            var tokenCount = 0

            val requestBody = buildRequestBody(
                messages = messages,
                maxTokens = maxNewTokens,
                temperature = temperature,
                model = if (model == MODEL_AUTO && !supportsAutoModel) MODEL_TURBO else model,
                stream = false,
                functionType = functionType
            )

            val request = Request.Builder()
                .url(API_BASE_URL)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            val responseText = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful) {
                        if (response.code == 402) {
                            val headers = response.headers.toMultimap().mapValues { (_, v) -> v.joinToString(",") }
                            val body = responseBody.orEmpty()
                            throw PaymentRequiredException(
                                X402Challenge(
                                    statusCode = 402,
                                    headers = headers,
                                    bodyRaw = body,
                                    bodyJson = X402Parser.tryParseJson(body),
                                )
                            )
                        }
                        if (model == MODEL_AUTO &&
                            response.code == 404 &&
                            responseBody?.contains("model_not_found", ignoreCase = true) == true &&
                            responseBody.contains("`auto`")
                        ) {
                            supportsAutoModel = false
                            currentModel = MODEL_TURBO
                            Timber.w("后端不支持 model=auto，自动回退到 $MODEL_TURBO")
                            val fallbackBody = buildRequestBody(
                                messages = messages,
                                maxTokens = maxNewTokens,
                                temperature = temperature,
                                model = MODEL_TURBO,
                                stream = false,
                                functionType = functionType
                            )
                            val fallbackRequest = Request.Builder()
                                .url(API_BASE_URL)
                                .post(fallbackBody.toRequestBody("application/json".toMediaType()))
                                .header("Content-Type", "application/json")
                                .build()
                            return@use httpClient.newCall(fallbackRequest).execute().use { fallbackResp ->
                                val fb = fallbackResp.body?.string()
                                if (!fallbackResp.isSuccessful) {
                                    if (fallbackResp.code == 402) {
                                        val headers = fallbackResp.headers.toMultimap().mapValues { (_, v) -> v.joinToString(",") }
                                        val body = fb.orEmpty()
                                        throw PaymentRequiredException(
                                            X402Challenge(
                                                statusCode = 402,
                                                headers = headers,
                                                bodyRaw = body,
                                                bodyJson = X402Parser.tryParseJson(body),
                                            )
                                        )
                                    }
                                    throw Exception(
                                        AppStrings.trf(
                                            "API 调用失败: %d - %s",
                                            "API request failed: %d - %s",
                                            fallbackResp.code,
                                            fb
                                        )
                                    )
                                }
                                fb ?: throw Exception(AppStrings.tr("响应体为空", "Empty response body"))
                            }
                        }
                        throw Exception(
                            AppStrings.trf(
                                "API 调用失败: %d - %s",
                                "API request failed: %d - %s",
                                response.code,
                                responseBody
                            )
                        )
                    }
                    responseBody ?: throw Exception(AppStrings.tr("响应体为空", "Empty response body"))
                }
            }

            val result = parseResponse(responseText)
            result.forEach { char ->
                emit(char.toString())
                tokenCount++
            }

            val totalTime = System.currentTimeMillis() - startTime
            val tokensPerSecond = if (totalTime > 0) tokenCount / (totalTime / 1000.0) else 0.0
            Timber.i("API 调用完成: chars=$tokenCount, timeMs=$totalTime, speed=${String.format("%.2f", tokensPerSecond)} chars/s")
        } catch (e: Exception) {
            Timber.e(e, "API 调用失败")
            throw e
        }
    }
    
    /**
     * 构建 API 请求体
     */
    private fun buildRequestBody(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        model: String,
        stream: Boolean,
        functionType: String
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }
        return buildRequestBody(messages, maxTokens, temperature, model, stream, functionType)
    }

    private fun buildRequestBody(
        messages: List<Message>,
        maxTokens: Int,
        temperature: Float,
        model: String,
        stream: Boolean,
        functionType: String
    ): String {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content)
            })
        }
        return buildRequestBody(arr, maxTokens, temperature, model, stream, functionType)
    }

    private fun buildRequestBody(
        messages: JSONArray,
        maxTokens: Int,
        temperature: Float,
        model: String,
        stream: Boolean,
        functionType: String
    ): String {
        val walletAddress = WalletScope.currentWalletAddress(context)
        val requestJson = JSONObject().apply {
            put("model", model)
            put("walletAddress", walletAddress)
            put("function_type", functionType)
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("top_p", DEFAULT_TOP_P)
            put("stream", stream)
        }
        return requestJson.toString()
    }
    
    /**
     * 构建 API 请求体（旧的 DashScope 格式，已弃用）
     */
    @Deprecated("使用新的 OpenAI 兼容格式")
    private fun buildRequestBodyOld(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        model: String,
        stream: Boolean
    ): String {
        val requestJson = JSONObject().apply {
            put("model", model)
            
            // 输入消息
            put("input", JSONObject().apply {
                put("messages", JSONArray().apply {
                    // 系统消息
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    // 用户消息
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            })
            
            // 参数
            put("parameters", JSONObject().apply {
                put("result_format", "message")
                put("max_tokens", maxTokens)
                put("temperature", temperature.toDouble())
                put("top_p", DEFAULT_TOP_P.toDouble())
                put("enable_search", false) // 禁用联网搜索
                put("incremental_output", stream) // 流式输出
            })
        }
        
        return requestJson.toString()
    }
    
    /**
     * 解析 API 响应
     */
    private fun parseResponse(responseBody: String): String {
        try {
            val json = JSONObject(responseBody)
            
            // 检查错误
            if (json.has("code")) {
                val code = json.getString("code")
                val message = json.optString("message", AppStrings.tr("未知错误", "Unknown error"))
                throw Exception(
                    AppStrings.trf("API 返回错误: %s - %s", "API error: %s - %s", code, message)
                )
            }
            
            // OpenAI 兼容格式：直接从 choices[0].message.content 提取
            val choices = json.getJSONArray("choices")
            
            if (choices.length() == 0) {
                throw Exception(AppStrings.tr("API 未返回生成内容", "API returned no generated content"))
            }
            
            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.getJSONObject("message")
            val content = message.getString("content")
            
            // 记录使用情况
            if (json.has("usage")) {
                val usage = json.getJSONObject("usage")
                val inputTokens = usage.optInt("input_tokens", 0)
                val outputTokens = usage.optInt("output_tokens", 0)
                val totalTokens = usage.optInt("total_tokens", 0)
                
                Timber.d("Token 使用情况:")
                Timber.d("  • 输入: $inputTokens tokens")
                Timber.d("  • 输出: $outputTokens tokens")
                Timber.d("  • 总计: $totalTokens tokens")
            }
            
            return content
            
        } catch (e: Exception) {
            Timber.e(e, "解析 API 响应失败")
            Timber.e("响应内容: $responseBody")
            throw Exception(
                AppStrings.trf("解析 API 响应失败: %s", "Failed to parse API response: %s", e.message ?: "")
            )
        }
    }
    
    /**
     * 切换模型
     */
    fun setModel(model: String) {
        when (model) {
            MODEL_TURBO, MODEL_PLUS, MODEL_MAX -> {
                currentModel = model
                Timber.i("切换模型为: $model")
            }
            else -> {
                Timber.w("未知模型: $model，保持使用 $currentModel")
            }
        }
    }
    
    /**
     * 获取当前模型
     */
    fun getModel(): String = currentModel
    
    /**
     * 释放资源（云 API 无需释放）
     */
    fun release() {
        Timber.i("释放云 API 资源...")
        isInitialized = false
        isWarmedUp = false
        Timber.i("✅ 云 API 资源已释放")
    }
    
    /**
     * 检查是否已初始化
     */
    fun isApiInitialized(): Boolean = isInitialized
    
    /**
     * 检查是否已预热
     */
    fun isApiWarmedUp(): Boolean = isWarmedUp
}
