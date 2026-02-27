package com.soulon.app.negotiation

import android.content.Context
import com.soulon.app.BuildConfig
import com.soulon.app.i18n.LocaleManager
import com.soulon.app.x402.MatchmakingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class NegotiationClient(private val context: Context) {
    private val baseUrl = resolveNegotiationBaseUrl().trimEnd('/')

    private fun resolveNegotiationBaseUrl(): String {
        return try {
            val field = BuildConfig::class.java.getDeclaredField("NEGOTIATION_BASE_URL")
            (field.get(null) as? String)?.trim().takeIf { !it.isNullOrBlank() } ?: BuildConfig.BACKEND_BASE_URL
        } catch (_: Exception) {
            BuildConfig.BACKEND_BASE_URL
        }
    }

    fun negotiationWebSocketUrl(sessionId: String): String {
        val http = baseUrl
        val wsBase =
            when {
                http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
                http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
                else -> "ws://$http"
            }
        return "$wsBase/ws/negotiation/$sessionId"
    }

    data class StartResult(
        val sessionId: String,
        val raw: JSONObject,
    )

    data class JsonResult(
        val raw: JSONObject,
    )

    suspend fun start(
        sourceAsset: String,
        targetAsset: String,
        amount: Double,
        personaDigest: String,
        x402: MatchmakingRequest?,
    ): Result<StartResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("source_asset", sourceAsset)
                put("target_asset", targetAsset)
                put("amount", amount)
                put("persona_digest", personaDigest)
                x402?.raw?.let { put("x402_request", it) }
            }
            val json = postJson("/api/v1/negotiation/start", payload).getOrThrow()
            val sessionId = json.optString("session_id").ifBlank { null }
                ?: return@withContext Result.failure(IllegalStateException("missing_session_id"))

            Result.success(StartResult(sessionId = sessionId, raw = json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun next(
        sessionId: String,
        buyerContent: String? = null,
        buyerFeeBps: Int? = null,
    ): Result<JsonResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("session_id", sessionId)
                buyerContent?.takeIf { it.isNotBlank() }?.let { put("buyer_content", it) }
                buyerFeeBps?.let { put("buyer_fee_bps", it) }
            }
            val json = postJson("/api/v1/negotiation/next", payload).getOrThrow()
            Result.success(JsonResult(raw = json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun commit(
        sessionId: String,
        finalOfferId: String? = null,
    ): Result<JsonResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("session_id", sessionId)
                finalOfferId?.takeIf { it.isNotBlank() }?.let { put("final_offer_id", it) }
            }
            val json = postJson("/api/v1/negotiation/commit", payload).getOrThrow()
            Result.success(JsonResult(raw = json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun previewExecution(sessionId: String): Result<JsonResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply { put("session_id", sessionId) }
            val json = postJson("/api/v1/execution/preview", payload).getOrThrow()
            Result.success(JsonResult(raw = json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun execute(orderId: String): Result<JsonResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply { put("order_id", orderId) }
            val json = postJson("/api/v1/execution/execute", payload).getOrThrow()
            Result.success(JsonResult(raw = json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun session(sessionId: String): Result<JsonResult> = withContext(Dispatchers.IO) {
        try {
            val json = getJson("/api/v1/negotiation/session/$sessionId").getOrThrow()
            Result.success(JsonResult(raw = json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun confirmStep(orderId: String, stepId: String, txHash: String): Result<JsonResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("order_id", orderId)
                put("step_id", stepId)
                put("tx_hash", txHash)
                put("status", "CONFIRMED")
            }
            val json = postJson("/api/v1/execution/confirm_step", payload).getOrThrow()
            Result.success(JsonResult(raw = json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun executeStep(orderId: String, stepId: String): Result<JsonResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("order_id", orderId)
                put("step_id", stepId)
            }
            val json = postJson("/api/v1/execution/execute_step", payload).getOrThrow()
            Result.success(JsonResult(raw = json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun authorizeOrder(
        orderId: String,
        chain: String,
        message: String,
        publicKey: String,
        signatureBase64: String,
    ): Result<JsonResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("order_id", orderId)
                put("chain", chain)
                put("message", message)
                put("public_key", publicKey)
                put("signature_base64", signatureBase64)
            }
            val json = postJson("/api/v1/orders/authorize", payload).getOrThrow()
            Result.success(JsonResult(raw = json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun postJson(path: String, payload: JSONObject): Result<JSONObject> {
        return try {
            val url = URL("$baseUrl$path")
            val connection = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (code !in 200..299) {
                return Result.failure(IllegalStateException(body.ifBlank { "HTTP $code" }))
            }
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getJson(path: String): Result<JSONObject> {
        return try {
            val url = URL("$baseUrl$path")
            val connection = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (code !in 200..299) {
                return Result.failure(IllegalStateException(body.ifBlank { "HTTP $code" }))
            }
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
