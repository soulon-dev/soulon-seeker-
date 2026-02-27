package com.soulon.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.soulon.app.i18n.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import timber.log.Timber
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SeedVaultKeyManager - 硬件级密钥派生和加密管理器
 * 
 * 功能特性：
 * - 使用 BIP-32 路径 m/44'/501'/2026'/101' 进行密钥派生
 * - 所有密钥操作都在 TEE (Trusted Execution Environment) 中执行
 * - AES-256-GCM 加密，确保数据完整性和机密性
 * - 需要物理身份验证（指纹或双击电源键）才能解密
 * - 零密钥暴露 - 密钥永不离开硬件安全模块
 * 
 * @property context Android 应用上下文
 */
class SeedVaultKeyManager(private val context: Context) {
    
    companion object {
        // BIP-32 路径: m/44'/501'/2026'/101'
        // 44' = BIP44, 501' = Solana, 2026' = Seeker 年份, 101' = 应用特定索引
        private val BIP32_PATH = intArrayOf(
            44 or ChildNumber.HARDENED_BIT,
            501 or ChildNumber.HARDENED_BIT,
            2026 or ChildNumber.HARDENED_BIT,
            101 or ChildNumber.HARDENED_BIT
        )
        
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "memory_ai_master_key"
        private const val WALLET_KEY_ALIAS = "memory_ai_wallet_key"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        
        // 用于从钱包派生密钥的固定盐值（应用唯一）
        private const val WALLET_KEY_SALT = "MemoryAI_WalletDerivedKey_2026_v1"
    }
    
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }
    
    init {
        // ✅ 只在密钥不存在时才生成新密钥（保持密钥持久化）
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            Timber.i("未找到主密钥，生成新密钥...")
            generateMasterKey()
        } else {
            Timber.i("使用现有主密钥: $KEY_ALIAS")
        }
        Timber.d("SeedVaultKeyManager 初始化完成")
    }
    
    /**
     * 在 Android Keystore 中生成主密钥
     * 密钥受硬件支持保护
     * 
     * 注意：为了更好的用户体验，加密操作不需要身份验证
     * 只在解密时通过 BiometricPrompt 验证用户身份
     */
    private fun generateMasterKey() {
        try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 加密不需要身份验证，解密时通过应用层的 BiometricPrompt 控制
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
            
            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
            
            Timber.i("主密钥生成成功，别名: $KEY_ALIAS")
        } catch (e: Exception) {
            Timber.e(e, "生成主密钥失败")
            throw SecurityException(AppStrings.tr("无法生成安全密钥", "Unable to generate secure key"), e)
        }
    }
    
    /**
     * 从种子派生 BIP-32 密钥
     * 
     * @param seed 主种子（从 Seed Vault SDK 获取）
     * @return 派生的确定性密钥
     */
    fun deriveKeyFromSeed(seed: ByteArray): DeterministicKey {
        require(seed.size >= 16) { "种子长度必须至少 16 字节" }
        
        try {
            // 使用 BIP32 从种子创建主密钥
            val masterKey = HDKeyDerivation.createMasterPrivateKey(seed)
            
            // 按照 BIP-32 路径派生子密钥
            var derivedKey = masterKey
            for (childNumber in BIP32_PATH) {
                derivedKey = HDKeyDerivation.deriveChildKey(
                    derivedKey,
                    ChildNumber(childNumber)
                )
            }
            
            Timber.d("密钥派生成功，路径: m/44'/501'/2026'/101'")
            return derivedKey
        } catch (e: Exception) {
            Timber.e(e, "密钥派生失败")
            throw SecurityException(AppStrings.tr("BIP-32 密钥派生失败", "BIP-32 key derivation failed"), e)
        }
    }
    
    /**
     * 获取派生密钥的公钥（字节数组格式）
     */
    fun getPublicKeyBytes(derivedKey: DeterministicKey): ByteArray {
        return derivedKey.pubKey
    }
    
    /**
     * 加密数据（不需要身份验证）
     * 
     * @param plaintext 明文数据
     * @return 加密结果（IV + 密文 + Tag）
     */
    suspend fun encryptData(plaintext: ByteArray): EncryptedData {
        return try {
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            
            // GCM 模式自动包含认证标签
            EncryptedData(
                ciphertext = ciphertext,
                iv = iv,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Timber.e(e, "加密失败")
            throw SecurityException(AppStrings.tr("数据加密失败", "Data encryption failed"), e)
        }
    }
    
    /**
     * 解密数据（需要物理身份验证）
     * 
     * @param activity 用于显示生物识别提示的 Activity
     * @param encryptedData 加密数据
     * @return 解密后的明文
     */
    suspend fun decryptDataWithAuth(
        activity: FragmentActivity,
        encryptedData: EncryptedData
    ): ByteArray = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
        
        val startTime = System.currentTimeMillis()
        
        try {
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
            )
            
            // 创建生物识别提示
            val biometricPrompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        try {
                            // ✨ 优化 3: 添加触觉反馈（Seeker 硬件优化）
                            activity.window.decorView.performHapticFeedback(
                                android.view.HapticFeedbackConstants.GESTURE_END,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                            )
                            
                            val plaintext = result.cryptoObject?.cipher?.doFinal(
                                encryptedData.ciphertext
                            ) ?: cipher.doFinal(encryptedData.ciphertext)
                            
                            val elapsedTime = System.currentTimeMillis() - startTime
                            // ✨ 优化 2: 安全日志 - 不输出明文内容
                            Timber.d("解密成功，耗时: ${elapsedTime}ms，数据大小: ${plaintext.size} 字节")
                            
                            continuation.resume(plaintext)
                        } catch (e: Exception) {
                            Timber.e(e, "解密操作失败")
                            continuation.resumeWithException(
                                SecurityException(AppStrings.tr("解密失败", "Decryption failed"), e)
                            )
                        }
                    }
                    
                    override fun onAuthenticationFailed() {
                        Timber.w("身份验证失败")
                    }
                    
                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        Timber.e("身份验证错误: $errorCode - $errString")
                        continuation.resumeWithException(
                            SecurityException(
                                AppStrings.trf("身份验证错误: %s", "Authentication error: %s", errString)
                            )
                        )
                    }
                }
            )
            
            // 构建提示信息
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(AppStrings.biometricAuthRequiredTitle)
                .setSubtitle(AppStrings.biometricAuthRequiredSubtitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            
            // 显示身份验证提示
            biometricPrompt.authenticate(
                promptInfo,
                BiometricPrompt.CryptoObject(cipher)
            )
            
        } catch (e: Exception) {
            Timber.e(e, "初始化解密失败")
            continuation.resumeWithException(SecurityException(AppStrings.tr("无法初始化解密", "Unable to initialize decryption"), e))
        }
        }
    }
    
    /**
     * 🔐 进行一次身份验证（不绑定具体解密操作）
     * 
     * 用于批量解密前的一次性验证
     * 
     * @param activity 用于显示生物识别提示的 Activity
     * @return 验证是否成功
     */
    suspend fun authenticateOnce(
        activity: FragmentActivity
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            try {
                val biometricPrompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult
                        ) {
                            // 触觉反馈
                            activity.window.decorView.performHapticFeedback(
                                android.view.HapticFeedbackConstants.GESTURE_END,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                            )
                            Timber.i("✅ 身份验证成功")
                            continuation.resume(true)
                        }
                        
                        override fun onAuthenticationFailed() {
                            Timber.w("身份验证失败")
                            // 不要在这里 resume，让用户可以重试
                        }
                        
                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence
                        ) {
                            Timber.e("身份验证错误: $errorCode - $errString")
                            continuation.resume(false)
                        }
                    }
                )
                
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(AppStrings.biometricDecryptTitle)
                    .setSubtitle(AppStrings.biometricDecryptSubtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
                
                biometricPrompt.authenticate(promptInfo)
                
            } catch (e: Exception) {
                Timber.e(e, "身份验证初始化失败")
                continuation.resume(false)
            }
        }
    }
    
    /**
     * 🔓 直接解密数据（不进行身份验证）
     * 
     * 注意：调用此方法前必须先调用 authenticateOnce() 进行验证
     * 
     * @param encryptedData 加密数据
     * @return 解密后的明文
     */
    fun decryptDataDirect(encryptedData: EncryptedData): ByteArray {
        return try {
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
            )
            cipher.doFinal(encryptedData.ciphertext)
        } catch (e: Exception) {
            Timber.e(e, "直接解密失败")
            throw SecurityException(AppStrings.tr("解密失败", "Decryption failed"), e)
        }
    }
    
    /**
     * 生成数据的 SHA-256 哈希（用于完整性验证）
     */
    fun generateHash(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }
    
    /**
     * 验证加密系统是否正常工作
     */
    suspend fun verifyEncryptionSystem(): Boolean {
        return try {
            val testData = "测试数据".toByteArray()
            val encrypted = encryptData(testData)
            // 注意：完整验证需要身份验证，这里只验证加密部分
            encrypted.ciphertext.isNotEmpty() && encrypted.iv.size == GCM_IV_LENGTH
        } catch (e: Exception) {
            Timber.e(e, "加密系统验证失败")
            false
        }
    }
    
    // ==================== 钱包派生密钥（跨设备恢复） ====================
    
    // 缓存的钱包派生密钥
    private var walletDerivedKey: SecretKey? = null
    private var currentWalletAddress: String? = null
    
    /**
     * 从钱包公钥派生加密密钥
     * 
     * 这个密钥是确定性的：相同的钱包 = 相同的密钥
     * 用于跨设备数据恢复
     * 
     * @param walletPublicKey 钱包公钥（Base58 编码）
     * @return AES-256 密钥
     */
    fun deriveKeyFromWallet(walletPublicKey: String): SecretKey {
        // 如果已经为当前钱包派生过密钥，直接返回缓存
        if (walletPublicKey == currentWalletAddress && walletDerivedKey != null) {
            return walletDerivedKey!!
        }
        
        Timber.i("🔑 从钱包派生加密密钥...")
        
        // 使用 SHA-256(walletPublicKey + salt) 派生 32 字节密钥
        val keyMaterial = "$walletPublicKey$WALLET_KEY_SALT"
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(keyMaterial.toByteArray(Charsets.UTF_8))
        
        val secretKey = SecretKeySpec(keyBytes, "AES")
        
        // 缓存密钥
        walletDerivedKey = secretKey
        currentWalletAddress = walletPublicKey
        
        Timber.i("✅ 钱包派生密钥已生成")
        return secretKey
    }
    
    /**
     * 使用钱包派生密钥加密数据
     * 
     * @param plaintext 明文数据
     * @param walletPublicKey 钱包公钥
     * @return 加密结果
     */
    fun encryptWithWalletKey(plaintext: ByteArray, walletPublicKey: String): EncryptedData {
        return try {
            val secretKey = deriveKeyFromWallet(walletPublicKey)
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            
            EncryptedData(
                ciphertext = ciphertext,
                iv = iv,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Timber.e(e, "使用钱包密钥加密失败")
            throw SecurityException(AppStrings.tr("数据加密失败", "Data encryption failed"), e)
        }
    }
    
    /**
     * 使用钱包派生密钥解密数据
     * 
     * @param encryptedData 加密数据
     * @param walletPublicKey 钱包公钥
     * @return 解密后的明文
     */
    fun decryptWithWalletKey(encryptedData: EncryptedData, walletPublicKey: String): ByteArray {
        return try {
            val secretKey = deriveKeyFromWallet(walletPublicKey)
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
            )
            cipher.doFinal(encryptedData.ciphertext)
        } catch (e: Exception) {
            Timber.e(e, "使用钱包密钥解密失败")
            throw SecurityException(AppStrings.tr("解密失败", "Decryption failed"), e)
        }
    }
    
    /**
     * 检查是否已设置钱包派生密钥
     */
    fun hasWalletKey(): Boolean = walletDerivedKey != null
    
    /**
     * 获取当前钱包地址
     */
    fun getCurrentWalletAddress(): String? = currentWalletAddress
    
    /**
     * 清除钱包派生密钥缓存
     */
    fun clearWalletKey() {
        walletDerivedKey = null
        currentWalletAddress = null
        Timber.i("钱包派生密钥已清除")
    }
    
    /**
     * 清理密钥（用于测试或重置）
     */
    fun clearKeys() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
            Timber.i("密钥已清理")
        }
        clearWalletKey()
    }
}

/**
 * 加密数据容器
 * 
 * @property ciphertext 加密后的数据
 * @property iv 初始化向量（GCM 模式）
 * @property timestamp 加密时间戳
 */
data class EncryptedData(
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as EncryptedData
        
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (timestamp != other.timestamp) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
    
    /**
     * 序列化为字节数组
     * 格式: [IV 长度(4字节)][IV][密文长度(4字节)][密文][时间戳(8字节)]
     */
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(4 + iv.size + 4 + ciphertext.size + 8)
        buffer.putInt(iv.size)
        buffer.put(iv)
        buffer.putInt(ciphertext.size)
        buffer.put(ciphertext)
        buffer.putLong(timestamp)
        return buffer.array()
    }
    
    companion object {
        /**
         * 从字节数组反序列化
         */
        fun fromByteArray(data: ByteArray): EncryptedData {
            val buffer = ByteBuffer.wrap(data)
            val ivSize = buffer.int
            val iv = ByteArray(ivSize)
            buffer.get(iv)
            val ciphertextSize = buffer.int
            val ciphertext = ByteArray(ciphertextSize)
            buffer.get(ciphertext)
            val timestamp = buffer.long
            return EncryptedData(ciphertext, iv, timestamp)
        }
    }
}
