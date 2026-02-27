package com.soulon.app.subscription

import android.content.Context
import com.soulon.app.BuildConfig
import com.soulon.app.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class GenesisTrialService(private val context: Context) {

    companion object {
        private val BASE_URL = BuildConfig.BACKEND_BASE_URL

        @Volatile
        private var instance: GenesisTrialService? = null

        fun getInstance(context: Context): GenesisTrialService {
            return instance ?: synchronized(this) {
                instance ?: GenesisTrialService(context.applicationContext).also { instance = it }
            }
        }
    }

    data class GenesisStatus(
        val wallet: String,
        val redeemed: Boolean,
        val redeemedAt: Long?
    )

    data class GenesisEligibility(
        val wallet: String,
        val redeemed: Boolean,
        val hasGenesisToken: Boolean,
        val hasSeekerGenesisToken: Boolean,
        val seekerSupported: Boolean,
        val hasSagaGenesisToken: Boolean,
        val dasSupported: Boolean,
        val collection: String?,
        val dasTotal: Int?,
        val rpcConfigured: Boolean,
        val rpcHost: String?
    )

    private fun extractErrorMessage(raw: String): String {
        return try {
            val json = JSONObject(raw)
            json.optString("message").ifBlank {
                json.optString("error").ifBlank { raw }
            }
        } catch (_: Exception) {
            raw
        }
    }

    suspend fun getStatus(wallet: String): Result<GenesisStatus> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/subscription/genesis/status?wallet=$wallet")
            val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }

            if (code in 200..299) {
                val json = JSONObject(body)
                Result.success(
                    GenesisStatus(
                        wallet = json.optString("wallet", wallet),
                        redeemed = json.optBoolean("redeemed", false),
                        redeemedAt = json.optLong("redeemedAt").takeIf { it > 0 }
                    )
                )
            } else {
                Result.failure(Exception(extractErrorMessage(body)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun redeem(
        wallet: String,
        signature: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/subscription/genesis/redeem")
            val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val payload = JSONObject().apply {
                put("wallet", wallet)
                put("signature", signature)
            }

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }

            if (code in 200..299) {
                val json = JSONObject(body)
                Result.success(json.optBoolean("success", false))
            } else {
                Result.failure(Exception(extractErrorMessage(body)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getEligibility(wallet: String): Result<GenesisEligibility> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/subscription/genesis/eligibility?wallet=$wallet")
            val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }

            if (code in 200..299) {
                val json = JSONObject(body)
                Result.success(
                    GenesisEligibility(
                        wallet = json.optString("wallet", wallet),
                        redeemed = json.optBoolean("redeemed", false),
                        hasGenesisToken = json.optBoolean("hasGenesisToken", false),
                        hasSeekerGenesisToken = json.optBoolean("hasSeekerGenesisToken", false),
                        seekerSupported = json.optBoolean("seekerSupported", true),
                        hasSagaGenesisToken = json.optBoolean("hasSagaGenesisToken", false),
                        dasSupported = json.optBoolean("dasSupported", true),
                        collection = json.optString("collection").takeIf { it.isNotBlank() },
                        dasTotal = json.optInt("dasTotal").takeIf { it > 0 },
                        rpcConfigured = json.optBoolean("rpcConfigured", false),
                        rpcHost = json.optString("rpcHost").takeIf { it.isNotBlank() }
                    )
                )
            } else {
                Result.failure(Exception(extractErrorMessage(body)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
