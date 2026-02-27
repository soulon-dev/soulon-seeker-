package com.soulon.app.teepin

import android.content.Context
import android.content.SharedPreferences
import com.funkatronics.encoders.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.soulon.app.security.SecurePrefs
import com.soulon.app.wallet.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * TEEPIN 硬件身份锚定管理器
 * 
 * 实现 Solana Seeker S2 的 TEEPIN (TEE Platform Infrastructure Network) 协议
 * 
 * 功能：
 * - 接收后端 Challenge
 * - 通过 MWA signMessagesDetached 使用硬件密钥签名
 * - 提交签名到后端验证
 * - 验证 Genesis Token 归属权
 * - 管理 Attestation 证书缓存
 * 
 * 流程：
 * 1. App 请求后端 Challenge
 * 2. 后端返回随机 Challenge
 * 3. App 通过 MWA 签名 Challenge（钱包通过 Seed Vault/TEE 签名）
 * 4. App 提交签名 + Genesis Token ID 到后端
 * 5. 后端验证签名并检查 Genesis Token 归属
 * 6. 后端返回 Attestation 证书（包含收益倍数）
 * 
 * @property context Android 上下文
 * @property walletManager 钱包管理器
 * @property apiBaseUrl 后端 API 基础 URL
 */
class TeepinAttestationManager(
    private val context: Context,
    private val walletManager: WalletManager,
    private val apiBaseUrl: String = DEFAULT_API_URL
) {
    companion object {
        private const val TAG = "TeepinAttestation"
        
        // 默认 API URL（需要替换为实际后端）
        private const val DEFAULT_API_URL = "https://api.memoryai.app/v1"
        
        // SharedPreferences 存储
        private const val PREFS_NAME = "teepin_prefs"
        private const val KEY_ATTESTATION_CERTIFICATE = "attestation_certificate"
        private const val KEY_ATTESTATION_EXPIRY = "attestation_expiry"
        private const val KEY_MULTIPLIER = "multiplier"
        
        // Attestation 有效期（默认 24 小时）
        private const val ATTESTATION_VALIDITY_MS = 24 * 60 * 60 * 1000L
        
        // Challenge 长度（32 字节）
        private const val CHALLENGE_LENGTH = 32
    }
    
    private val prefs: SharedPreferences = SecurePrefs.create(context, PREFS_NAME)
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // 缓存的 Attestation 状态
    private var cachedAttestation: AttestationCertificate? = null
    
    init {
        // 从持久化存储恢复 Attestation
        loadCachedAttestation()
    }
    
    /**
     * 从本地存储加载缓存的 Attestation
     */
    private fun loadCachedAttestation() {
        try {
            val expiry = prefs.getLong(KEY_ATTESTATION_EXPIRY, 0L)
            if (expiry > System.currentTimeMillis()) {
                val certificate = prefs.getString(KEY_ATTESTATION_CERTIFICATE, null)
                val multiplier = prefs.getFloat(KEY_MULTIPLIER, 1.0f)
                
                if (certificate != null) {
                    cachedAttestation = AttestationCertificate(
                        certificate = certificate,
                        multiplier = multiplier,
                        validUntil = expiry
                    )
                    Timber.i("$TAG: 已恢复缓存的 Attestation, multiplier=$multiplier")
                }
            } else {
                Timber.i("$TAG: Attestation 已过期，需要重新验证")
                clearCachedAttestation()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 加载缓存的 Attestation 失败")
        }
    }
    
    /**
     * 保存 Attestation 到本地存储
     */
    private fun saveCachedAttestation(certificate: AttestationCertificate) {
        try {
            prefs.edit().apply {
                putString(KEY_ATTESTATION_CERTIFICATE, certificate.certificate)
                putLong(KEY_ATTESTATION_EXPIRY, certificate.validUntil)
                putFloat(KEY_MULTIPLIER, certificate.multiplier)
                apply()
            }
            cachedAttestation = certificate
            Timber.i("$TAG: Attestation 已缓存")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 保存 Attestation 失败")
        }
    }
    
    /**
     * 清除缓存的 Attestation
     */
    private fun clearCachedAttestation() {
        prefs.edit().apply {
            remove(KEY_ATTESTATION_CERTIFICATE)
            remove(KEY_ATTESTATION_EXPIRY)
            remove(KEY_MULTIPLIER)
            apply()
        }
        cachedAttestation = null
    }
    
    /**
     * 检查是否有有效的 Attestation
     */
    fun hasValidAttestation(): Boolean {
        val cert = cachedAttestation ?: return false
        return cert.validUntil > System.currentTimeMillis()
    }
    
    /**
     * 获取当前的收益倍数
     */
    fun getCurrentMultiplier(): Float {
        return if (hasValidAttestation()) {
            cachedAttestation?.multiplier ?: 1.0f
        } else {
            1.0f
        }
    }
    
    /**
     * 获取 Attestation 证书
     */
    fun getAttestationCertificate(): AttestationCertificate? {
        return if (hasValidAttestation()) cachedAttestation else null
    }
    
    /**
     * 从后端获取 Challenge
     * 
     * @param walletAddress 钱包地址
     * @return Challenge 字节数组
     */
    suspend fun receiveChallenge(walletAddress: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 请求 Attestation Challenge...")
            
            val requestBody = JSONObject().apply {
                put("wallet_address", walletAddress)
                put("timestamp", System.currentTimeMillis())
            }.toString()
            
            val request = Request.Builder()
                .url("$apiBaseUrl/attestation/challenge")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw Exception("后端 Challenge 请求失败: HTTP ${response.code}")
            }
            
            val responseBody = response.body?.string()
                ?: throw Exception("响应体为空")
            
            val json = JSONObject(responseBody)
            val challengeBase58 = json.getString("challenge")
            
            Timber.i("$TAG: 获取到 Challenge")
            Base58.decode(challengeBase58)
            
        } catch (e: Exception) {
            Timber.w(e, "$TAG: 获取 Challenge 失败")
            throw e
        }
    }
    
    /**
     * 使用 MWA 签名 Challenge
     * 
     * 这里通过 MWA 的 signMessagesDetached 调用钱包进行签名
     * 钱包内部会使用 Seed Vault/TEE 进行硬件签名
     * 
     * @param sender Activity 结果发送器
     * @param challenge 要签名的 Challenge
     * @return 签名结果
     */
    suspend fun signWithWallet(
        sender: ActivityResultSender,
        challenge: ByteArray
    ): AttestationSignature? {
        return try {
            Timber.i("$TAG: 请求钱包签名 Challenge...")
            
            // 使用 WalletManager 的 signMessage 方法
            val signature = walletManager.signMessage(challenge, sender)
            
            val publicKey = walletManager.getPublicKeyBytes()
                ?: throw Exception("无法获取公钥")
            
            AttestationSignature(
                signature = signature,
                publicKey = publicKey,
                timestamp = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 签名 Challenge 失败")
            null
        }
    }
    
    /**
     * 提交 Attestation 到后端验证
     * 
     * @param signatureResult 签名结果
     * @param genesisTokenId Genesis Token ID
     * @return 验证结果
     */
    suspend fun submitAttestation(
        signatureResult: AttestationSignature,
        genesisTokenId: String
    ): AttestationVerification = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 提交 Attestation 验证...")
            
            val requestBody = JSONObject().apply {
                put("signature", Base58.encodeToString(signatureResult.signature))
                put("public_key", Base58.encodeToString(signatureResult.publicKey))
                put("genesis_token_id", genesisTokenId)
                put("timestamp", signatureResult.timestamp)
            }.toString()
            
            val request = Request.Builder()
                .url("$apiBaseUrl/attestation/verify")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext AttestationVerification(
                    verified = false,
                    multiplier = 1.0f,
                    validUntil = 0L,
                    certificate = null,
                    reason = "后端验证失败: HTTP ${response.code}"
                )
            }
            
            val responseBody = response.body?.string()
                ?: throw Exception("响应体为空")
            
            val json = JSONObject(responseBody)
            
            if (json.getBoolean("verified")) {
                val multiplier = json.optDouble("multiplier", 1.0).toFloat()
                val validUntil = json.optLong("valid_until", 
                    System.currentTimeMillis() + ATTESTATION_VALIDITY_MS)
                val certificate = json.optString("certificate", "")
                
                AttestationVerification(
                    verified = true,
                    multiplier = multiplier,
                    validUntil = validUntil,
                    certificate = certificate,
                    reason = null
                )
            } else {
                AttestationVerification(
                    verified = false,
                    multiplier = 1.0f,
                    validUntil = 0L,
                    certificate = null,
                    reason = json.optString("reason", "验证失败")
                )
            }
            
        } catch (e: Exception) {
            Timber.w(e, "$TAG: 提交验证失败")
            AttestationVerification(
                verified = false,
                multiplier = 1.0f,
                validUntil = 0L,
                certificate = null,
                reason = e.message ?: "验证失败"
            )
        }
    }
    
    /**
     * 执行完整的 Attestation 流程
     * 
     * @param sender Activity 结果发送器
     * @param genesisTokenId Genesis Token ID
     * @return Attestation 状态
     */
    suspend fun performAttestation(
        sender: ActivityResultSender,
        genesisTokenId: String
    ): AttestationStatus {
        // 检查是否有有效的缓存
        if (hasValidAttestation()) {
            Timber.i("$TAG: 使用缓存的 Attestation")
            return AttestationStatus.Verified(
                multiplier = cachedAttestation!!.multiplier,
                validUntil = cachedAttestation!!.validUntil,
                certificate = cachedAttestation!!.certificate
            )
        }
        
        return try {
            // 1. 获取钱包地址
            val walletAddress = walletManager.getWalletAddress()
                ?: return AttestationStatus.Error("钱包未连接")
            
            // 2. 获取 Challenge
            val challenge = receiveChallenge(walletAddress)
            
            // 3. 签名 Challenge
            val signResult = signWithWallet(sender, challenge)
                ?: return AttestationStatus.SigningFailed
            
            // 4. 提交验证
            val verification = submitAttestation(signResult, genesisTokenId)
            
            if (verification.verified && verification.certificate != null) {
                // 5. 缓存结果
                val certificate = AttestationCertificate(
                    certificate = verification.certificate,
                    multiplier = verification.multiplier,
                    validUntil = verification.validUntil
                )
                saveCachedAttestation(certificate)
                
                AttestationStatus.Verified(
                    multiplier = verification.multiplier,
                    validUntil = verification.validUntil,
                    certificate = verification.certificate
                )
            } else {
                AttestationStatus.VerificationFailed(
                    verification.reason ?: "验证失败"
                )
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Attestation 流程失败")
            AttestationStatus.Error(e.message ?: "未知错误")
        }
    }
    
    /**
     * 强制刷新 Attestation（清除缓存并重新验证）
     */
    suspend fun refreshAttestation(
        sender: ActivityResultSender,
        genesisTokenId: String
    ): AttestationStatus {
        clearCachedAttestation()
        return performAttestation(sender, genesisTokenId)
    }
}

/**
 * Attestation 签名数据
 */
data class AttestationSignature(
    val signature: ByteArray,
    val publicKey: ByteArray,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AttestationSignature
        return signature.contentEquals(other.signature) &&
               publicKey.contentEquals(other.publicKey) &&
               timestamp == other.timestamp
    }
    
    override fun hashCode(): Int {
        var result = signature.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Attestation 验证结果
 */
data class AttestationVerification(
    val verified: Boolean,
    val multiplier: Float,
    val validUntil: Long,
    val certificate: String?,
    val reason: String?
)

/**
 * Attestation 证书（缓存）
 */
data class AttestationCertificate(
    val certificate: String,
    val multiplier: Float,
    val validUntil: Long
)

/**
 * Attestation 状态
 */
sealed class AttestationStatus {
    /**
     * 已验证通过
     */
    data class Verified(
        val multiplier: Float,
        val validUntil: Long,
        val certificate: String
    ) : AttestationStatus()
    
    /**
     * 验证失败
     */
    data class VerificationFailed(val reason: String) : AttestationStatus()
    
    /**
     * 签名失败
     */
    object SigningFailed : AttestationStatus()
    
    /**
     * 错误
     */
    data class Error(val message: String) : AttestationStatus()
}
