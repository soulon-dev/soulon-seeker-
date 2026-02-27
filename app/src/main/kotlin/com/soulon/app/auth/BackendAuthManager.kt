package com.soulon.app.auth

import android.content.Context
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.soulon.app.BuildConfig
import com.soulon.app.security.SecurePrefs
import com.soulon.app.wallet.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class BackendAuthManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "BackendAuthManager"
        private const val PREFS_NAME = "backend_auth_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_WALLET = "wallet"
        private const val KEY_EXPIRES_AT = "expires_at"

        @Volatile
        private var INSTANCE: BackendAuthManager? = null

        fun getInstance(context: Context): BackendAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackendAuthManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs = SecurePrefs.create(context, PREFS_NAME)
    private val expectedBaseUrl: URL by lazy { URL(BuildConfig.BACKEND_BASE_URL) }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    suspend fun ensureSession(activityResultSender: ActivityResultSender, walletManager: WalletManager): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val walletAddress = walletManager.getWalletAddress()
                    ?: return@withContext Result.failure(IllegalStateException("钱包未连接"))

                val existing = getAccessToken()
                val existingWallet = prefs.getString(KEY_WALLET, null)
                val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
                val now = System.currentTimeMillis()
                if (!existing.isNullOrBlank() && existingWallet == walletAddress && now < expiresAt) {
                    return@withContext Result.success(Unit)
                }

                val (challenge, message) = fetchChallenge(walletAddress)
                val signature = walletManager.signMessage(message.toByteArray(Charsets.UTF_8), activityResultSender)
                val publicKey = walletManager.getPublicKeyBytes() ?: return@withContext Result.failure(
                    IllegalStateException("无法获取公钥")
                )

                val loginResp = submitLogin(
                    walletAddress = walletAddress,
                    signatureBase58 = org.bitcoinj.core.Base58.encode(signature),
                    publicKeyBase58 = org.bitcoinj.core.Base58.encode(publicKey),
                )

                val token = loginResp.optString("access_token", loginResp.optString("session_token", ""))
                if (token.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("后端未返回会话令牌"))
                }
                val expiresAtResp = loginResp.optLong("expires_at", now + 7L * 24 * 60 * 60 * 1000)
                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, token)
                    .putString(KEY_WALLET, walletAddress)
                    .putLong(KEY_EXPIRES_AT, expiresAtResp)
                    .apply()

                Timber.i("$TAG: 后端会话建立成功 base=${expectedBaseUrl.host} tokenLen=${token.length} expiresAt=$expiresAtResp")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: 后端会话建立失败")
                Result.failure(e)
            }
        }

    suspend fun fetchChallengeMessage(walletAddress: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { fetchChallenge(walletAddress).second }
    }

    suspend fun loginWithSignedChallenge(
        walletAddress: String,
        signature: ByteArray,
        publicKey: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val loginResp = submitLogin(
                walletAddress = walletAddress,
                signatureBase58 = org.bitcoinj.core.Base58.encode(signature),
                publicKeyBase58 = org.bitcoinj.core.Base58.encode(publicKey),
            )
            val token = loginResp.optString("access_token", loginResp.optString("session_token", ""))
            if (token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("后端未返回会话令牌"))
            }
            val now = System.currentTimeMillis()
            val expiresAtResp = loginResp.optLong("expires_at", now + 7L * 24 * 60 * 60 * 1000)
            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, token)
                .putString(KEY_WALLET, walletAddress)
                .putLong(KEY_EXPIRES_AT, expiresAtResp)
                .apply()
            Timber.i("$TAG: 后端会话建立成功(复用签名) base=${expectedBaseUrl.host} tokenLen=${token.length} expiresAt=$expiresAtResp")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: 后端会话建立失败(复用签名)")
            Result.failure(e)
        }
    }

    suspend fun loginWithSessionAuthorizationMessage(
        walletAddress: String,
        signature: ByteArray,
        publicKey: ByteArray,
        message: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${BuildConfig.BACKEND_BASE_URL}/api/v1/auth/login-session-key")
            val connection = createConnection(url, "POST")
            val payload = JSONObject()
                .put("wallet_address", walletAddress)
                .put("signature", org.bitcoinj.core.Base58.encode(signature))
                .put("public_key", org.bitcoinj.core.Base58.encode(publicKey))
                .put("message", message.toString(Charsets.UTF_8))
                .toString()
            connection.outputStream.bufferedWriter().use { it.write(payload) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                val parsedError = errorBody
                    ?.takeIf { it.isNotBlank() }
                    ?.let { body ->
                        runCatching {
                            val json = JSONObject(body)
                            val err = json.optString("error").trim()
                            if (err.isNotBlank()) err else body
                        }.getOrNull()
                    }
                throw IllegalStateException(
                    "登录失败: HTTP ${connection.responseCode}${if (!parsedError.isNullOrBlank()) " $parsedError" else ""}"
                )
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val loginResp = JSONObject(response)
            val token = loginResp.optString("access_token", loginResp.optString("session_token", ""))
            if (token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("后端未返回会话令牌"))
            }
            val now = System.currentTimeMillis()
            val expiresAtResp = loginResp.optLong("expires_at", now + 7L * 24 * 60 * 60 * 1000)
            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, token)
                .putString(KEY_WALLET, walletAddress)
                .putLong(KEY_EXPIRES_AT, expiresAtResp)
                .apply()
            Timber.i("$TAG: 后端会话建立成功(会话授权) base=${expectedBaseUrl.host} tokenLen=${token.length} expiresAt=$expiresAtResp")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: 后端会话建立失败(会话授权)")
            Result.failure(e)
        }
    }

    private fun createConnection(url: URL, method: String): HttpURLConnection {
        if (expectedBaseUrl.protocol != "https") {
            throw IllegalStateException("BACKEND_BASE_URL 必须为 https")
        }
        if (url.protocol != "https" || url.host != expectedBaseUrl.host) {
            throw IllegalArgumentException("不允许跨域或非 https 请求: $url")
        }
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        if (method != "GET" && method != "HEAD") connection.doOutput = true
        return connection
    }

    private fun fetchChallenge(walletAddress: String): Pair<String, String> {
        val url = URL("${BuildConfig.BACKEND_BASE_URL}/api/v1/auth/challenge")
        val connection = createConnection(url, "POST")
        val payload = JSONObject().put("wallet_address", walletAddress).toString()
        connection.outputStream.bufferedWriter().use { it.write(payload) }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("获取 Challenge 失败: HTTP ${connection.responseCode}")
        }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val challenge = json.getString("challenge")
        val message = json.getString("message")
        return challenge to message
    }

    private fun submitLogin(walletAddress: String, signatureBase58: String, publicKeyBase58: String): JSONObject {
        val url = URL("${BuildConfig.BACKEND_BASE_URL}/api/v1/auth/login")
        val connection = createConnection(url, "POST")
        val payload = JSONObject()
            .put("wallet_address", walletAddress)
            .put("signature", signatureBase58)
            .put("public_key", publicKeyBase58)
            .toString()
        connection.outputStream.bufferedWriter().use { it.write(payload) }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            throw IllegalStateException("登录失败: HTTP ${connection.responseCode}${if (!errorBody.isNullOrBlank()) " $errorBody" else ""}")
        }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(response)
    }
}
