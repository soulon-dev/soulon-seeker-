package com.soulon.app.x402

import com.soulon.app.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

sealed class X402Result {
    data class Ok(val statusCode: Int, val body: String, val headers: Map<String, String>) : X402Result()
    data class PaymentRequired(val challenge: X402Challenge) : X402Result()
    data class Error(val statusCode: Int, val body: String, val headers: Map<String, String>) : X402Result()
}

object X402Http {
    suspend fun requestJson(
        context: android.content.Context,
        url: String,
        method: String,
        requestBody: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000,
    ): X402Result = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            if (requestBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        if (requestBody != null) {
            connection.outputStream.bufferedWriter().use { it.write(requestBody.toString()) }
        }

        val status = connection.responseCode
        val rawHeaders = connection.headerFields
            .filterKeys { it != null }
            .mapValues { (_, v) -> v?.joinToString(",") ?: "" }
            .mapKeys { (k, _) -> k ?: "" }

        val body = if (status in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }

        if (status == 402) {
            val bodyJson = X402Parser.tryParseJson(body)
            return@withContext X402Result.PaymentRequired(
                X402Challenge(
                    statusCode = status,
                    headers = rawHeaders,
                    bodyRaw = body,
                    bodyJson = bodyJson,
                )
            )
        }

        return@withContext when (status) {
            in 200..299 -> X402Result.Ok(statusCode = status, body = body, headers = rawHeaders)
            else -> X402Result.Error(statusCode = status, body = body, headers = rawHeaders)
        }
    }
}

