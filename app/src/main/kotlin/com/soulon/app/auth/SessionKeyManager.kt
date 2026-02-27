package com.soulon.app.auth

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.i18n.AppStrings
import com.soulon.app.security.SecurePrefs
import timber.log.Timber
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import android.util.Base64

/**
 * 会话密钥管理器
 * 
 * 解决频繁钱包签名的问题：
 * 1. 本地生成 Ed25519 会话密钥对
 * 2. 用户用主钱包签名授权会话密钥（只需一次）
 * 3. 后续所有 DataItem 签名自动使用会话密钥
 * 
 * 安全特性：
 * - 会话密钥存储在 EncryptedSharedPreferences 中
 * - 支持设置过期时间
 * - 可随时撤销/重新生成
 * 
 * 使用流程：
 * 1. 检查是否有有效会话 -> hasValidSession()
 * 2. 如果没有，生成新会话密钥 -> generateSessionKey()
 * 3. 用户用主钱包授权 -> authorizeSession()
 * 4. 后续直接签名 -> signData()
 */
class SessionKeyManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "session_key_prefs"
        private const val KEY_PUBLIC = "session_public_key"
        private const val KEY_PRIVATE = "session_private_key"
        private const val KEY_AUTHORIZED = "session_authorized"
        private const val KEY_EXPIRES_AT = "session_expires_at"
        private const val KEY_MAIN_WALLET = "main_wallet_pubkey"
        private const val KEY_AUTHORIZATION_SIG = "authorization_signature"
        
        // 会话有效期：7 天
        private const val SESSION_DURATION_MS = 7 * 24 * 60 * 60 * 1000L
        private const val EXPIRY_SAFETY_MARGIN_MS = 10 * 60 * 1000L
    }
    
    private val prefs: SharedPreferences by lazy { SecurePrefs.create(context, PREFS_NAME) }
    
    // 内存中缓存的密钥对
    private var cachedKeyPair: KeyPair? = null
    
    /**
     * 检查是否有有效的会话密钥
     */
    fun hasValidSession(): Boolean {
        val authorized = prefs.getBoolean(KEY_AUTHORIZED, false)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        val hasKeys = prefs.contains(KEY_PUBLIC) && prefs.contains(KEY_PRIVATE)
        
        val isValid = authorized && hasKeys && System.currentTimeMillis() < expiresAt
        
        Timber.d("会话状态: authorized=$authorized, hasKeys=$hasKeys, expired=${System.currentTimeMillis() >= expiresAt}, isValid=$isValid")
        
        return isValid
    }
    
    /**
     * 获取会话公钥（32 字节）
     */
    fun getSessionPublicKey(): ByteArray? {
        return try {
            val encoded = prefs.getString(KEY_PUBLIC, null) ?: return null
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e(e, "获取会话公钥失败")
            null
        }
    }
    
    /**
     * 获取主钱包公钥（用于 tags 中标识所有者）
     */
    fun getMainWalletPublicKey(): ByteArray? {
        return try {
            val encoded = prefs.getString(KEY_MAIN_WALLET, null) ?: return null
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e(e, "获取主钱包公钥失败")
            null
        }
    }
    
    /**
     * 生成新的会话密钥对
     * 
     * @return 会话公钥（32 字节）
     */
    fun generateSessionKey(): ByteArray {
        Timber.i("🔑 生成新的会话密钥...")
        
        try {
            // 使用 BouncyCastle 生成 Ed25519 密钥对
            val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519", "BC")
            keyPairGenerator.initialize(256, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()
            
            // 提取原始公钥（32 字节）
            val rawPublicKey = extractRawPublicKey(keyPair.public)
            
            // 存储密钥
            prefs.edit()
                .putString(KEY_PUBLIC, Base64.encodeToString(rawPublicKey, Base64.NO_WRAP))
                .putString(KEY_PRIVATE, Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP))
                .putBoolean(KEY_AUTHORIZED, false)
                .apply()
            
            // 缓存到内存
            cachedKeyPair = keyPair
            
            Timber.i("✅ 会话密钥生成成功: ${rawPublicKey.size} 字节")
            Timber.d("会话公钥: ${rawPublicKey.toHexString().take(32)}...")
            
            return rawPublicKey
            
        } catch (e: Exception) {
            Timber.e(e, "生成会话密钥失败")
            throw SessionKeyException(
                AppStrings.trf("生成会话密钥失败: %s", "Failed to generate session key: %s", e.message ?: ""),
                e
            )
        }
    }
    
    /**
     * 授权会话密钥
     * 
     * 用户已用主钱包签名授权消息后调用此方法
     * 
     * @param mainWalletPublicKey 主钱包公钥
     * @param authorizationSignature 主钱包的授权签名
     */
    fun authorizeSession(
        mainWalletPublicKey: ByteArray,
        authorizationSignature: ByteArray
    ) {
        Timber.i("✅ 授权会话密钥...")
        
        prefs.edit()
            .putString(KEY_MAIN_WALLET, Base64.encodeToString(mainWalletPublicKey, Base64.NO_WRAP))
            .putString(KEY_AUTHORIZATION_SIG, Base64.encodeToString(authorizationSignature, Base64.NO_WRAP))
            .putBoolean(KEY_AUTHORIZED, true)
            .putLong(KEY_EXPIRES_AT, computeExpiresAtMs())
            .apply()
        
        Timber.i("🎉 会话密钥授权成功，有效期 7 天")
    }
    
    /**
     * 构建授权消息
     * 
     * 用户需要用主钱包签名此消息来授权会话密钥
     */
    fun buildAuthorizationMessage(): ByteArray {
        val sessionPubKey = getSessionPublicKey()
            ?: throw SessionKeyException(AppStrings.tr("会话密钥未生成", "Session key not generated"))

        val expiresAtMs = computeExpiresAtMs()
        
        val message = """
            MemoryAI Session Authorization
            
            I authorize this session key to sign DataItems on my behalf.
            
            Session Public Key: ${sessionPubKey.toHexString()}
            Expires: $expiresAtMs
            
            This authorization is valid for 7 days.
        """.trimIndent()
        
        Timber.d("授权消息:\n$message")
        
        return message.toByteArray(Charsets.UTF_8)
    }

    private fun computeExpiresAtMs(nowMs: Long = System.currentTimeMillis()): Long {
        val raw = nowMs + SESSION_DURATION_MS - EXPIRY_SAFETY_MARGIN_MS
        return if (raw > nowMs) raw else nowMs + 60_000L
    }
    
    /**
     * 使用会话密钥签名数据
     * 
     * @param data 要签名的数据（通常是 deep-hash 结果）
     * @return Ed25519 签名（64 字节）
     */
    fun signData(data: ByteArray): ByteArray {
        if (!hasValidSession()) {
            throw SessionKeyException(AppStrings.tr("会话无效或已过期，请重新授权", "Session invalid or expired. Please re-authorize."))
        }
        
        try {
            val keyPair = getOrLoadKeyPair()
            
            // 使用 Ed25519 签名
            val signature = Signature.getInstance("Ed25519", "BC")
            signature.initSign(keyPair.private)
            signature.update(data)
            val sig = signature.sign()
            
            Timber.d("✅ 会话密钥签名成功: ${sig.size} 字节")
            
            return sig
            
        } catch (e: Exception) {
            Timber.e(e, "会话密钥签名失败")
            throw SessionKeyException(
                AppStrings.trf("签名失败: %s", "Signing failed: %s", e.message ?: ""),
                e
            )
        }
    }
    
    /**
     * 撤销当前会话
     */
    fun revokeSession() {
        Timber.i("🗑️ 撤销会话密钥...")
        
        prefs.edit()
            .remove(KEY_PUBLIC)
            .remove(KEY_PRIVATE)
            .remove(KEY_AUTHORIZED)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_MAIN_WALLET)
            .remove(KEY_AUTHORIZATION_SIG)
            .apply()
        
        cachedKeyPair = null
        
        Timber.i("✅ 会话已撤销")
    }
    
    /**
     * 获取会话信息
     */
    fun getSessionInfo(): SessionInfo? {
        if (!hasValidSession()) return null
        
        return SessionInfo(
            sessionPublicKey = getSessionPublicKey() ?: return null,
            mainWalletPublicKey = getMainWalletPublicKey() ?: return null,
            expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0),
            remainingTimeMs = prefs.getLong(KEY_EXPIRES_AT, 0) - System.currentTimeMillis()
        )
    }
    
    // ==================== Private Methods ====================
    
    private fun getOrLoadKeyPair(): KeyPair {
        // 先从内存缓存获取
        cachedKeyPair?.let { return it }
        
        // 从存储加载
        val publicKeyEncoded = prefs.getString(KEY_PUBLIC, null)
            ?: throw SessionKeyException(AppStrings.tr("会话公钥不存在", "Session public key not found"))
        val privateKeyEncoded = prefs.getString(KEY_PRIVATE, null)
            ?: throw SessionKeyException(AppStrings.tr("会话私钥不存在", "Session private key not found"))
        
        val publicKeyBytes = Base64.decode(publicKeyEncoded, Base64.NO_WRAP)
        val privateKeyBytes = Base64.decode(privateKeyEncoded, Base64.NO_WRAP)
        
        val keyFactory = KeyFactory.getInstance("Ed25519", "BC")
        
        // 重建公钥（需要添加 X.509 头）
        val publicKey = rebuildPublicKey(publicKeyBytes, keyFactory)
        
        // 重建私钥
        val privateKeySpec = PKCS8EncodedKeySpec(privateKeyBytes)
        val privateKey = keyFactory.generatePrivate(privateKeySpec)
        
        val keyPair = KeyPair(publicKey, privateKey)
        cachedKeyPair = keyPair
        
        return keyPair
    }
    
    /**
     * 从 Java PublicKey 提取原始 32 字节 Ed25519 公钥
     */
    private fun extractRawPublicKey(publicKey: PublicKey): ByteArray {
        val encoded = publicKey.encoded
        // X.509 编码的 Ed25519 公钥格式: 前 12 字节是头，后 32 字节是原始公钥
        return if (encoded.size == 44) {
            encoded.sliceArray(12 until 44)
        } else {
            encoded
        }
    }
    
    /**
     * 从原始 32 字节公钥重建 Java PublicKey
     */
    private fun rebuildPublicKey(rawPublicKey: ByteArray, keyFactory: KeyFactory): PublicKey {
        // Ed25519 X.509 头
        val x509Header = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        )
        val x509Encoded = x509Header + rawPublicKey
        val keySpec = X509EncodedKeySpec(x509Encoded)
        return keyFactory.generatePublic(keySpec)
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
    
    // ==================== Data Classes ====================
    
    data class SessionInfo(
        val sessionPublicKey: ByteArray,
        val mainWalletPublicKey: ByteArray,
        val expiresAt: Long,
        val remainingTimeMs: Long
    ) {
        val remainingHours: Int get() = (remainingTimeMs / (1000 * 60 * 60)).toInt()
        val remainingDays: Int get() = (remainingTimeMs / (1000 * 60 * 60 * 24)).toInt()
        
        fun isExpiringSoon(): Boolean = remainingTimeMs < 24 * 60 * 60 * 1000 // < 24 小时
    }
    
    class SessionKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
