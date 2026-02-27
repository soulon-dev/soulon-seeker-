package com.soulon.app.wallet

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.soulon.app.BuildConfig
import com.soulon.app.i18n.AppStrings
import com.soulon.app.security.SecurePrefs
import com.funkatronics.encoders.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Mobile Wallet Adapter 2.0 标准客户端封装
 * 
 * 基于 Solana Mobile 官方文档实现：
 * https://docs.solanamobile.com/android-native/using_mobile_wallet_adapter
 * 
 * 功能：
 * - Sign in with Solana (SIWS) - 官方推荐的钱包授权方式
 * - signAndSendTransactions - 签名并发送交易
 * - signMessagesDetached - 消息签名（用于 TEEPIN Attestation）
 * - authToken 持久化 - 跨会话免授权
 * 
 * SDK 版本：com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.3
 * 
 * @property context Android 上下文
 */
class MobileWalletAdapterClient(
    private val context: Context
) {
    companion object {
        private const val TAG = "MWAClient"
        
        // dApp 身份信息
        // 使用实际部署的后端域名，钱包可验证
        private val IDENTITY_URI = BuildConfig.IDENTITY_URI
        private const val IDENTITY_NAME = "Soulon"
        // iconUri 必须是相对路径或 null
        private const val ICON_URI = "/icon.png"
        
        // SharedPreferences 存储
        private const val PREFS_NAME = "mwa_client_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_PUBLIC_KEY = "public_key_base58"
        private const val KEY_ACCOUNT_LABEL = "account_label"
        
        // 默认超时时间
        private const val DEFAULT_TIMEOUT_MS = 60_000L
    }
    
    private val prefs: SharedPreferences = SecurePrefs.create(context, PREFS_NAME)
    
    /**
     * MWA 客户端实例
     * 
     * 官方推荐：定义 dApp 身份让钱包显示应用信息
     */
    private val walletAdapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse(IDENTITY_URI),
            iconUri = Uri.parse(ICON_URI),
            identityName = IDENTITY_NAME
        )
    )
    
    // 缓存的账户信息
    private var cachedPublicKey: String? = null
    private var cachedAccountLabel: String? = null
    
    init {
        // 恢复持久化的 authToken
        restoreAuthToken()
        Timber.i("$TAG: MWA 客户端初始化完成")
    }
    
    /**
     * 恢复持久化的 authToken
     */
    private fun restoreAuthToken() {
        try {
            val savedToken = prefs.getString(KEY_AUTH_TOKEN, null)
            val savedPublicKey = prefs.getString(KEY_PUBLIC_KEY, null)
            val savedLabel = prefs.getString(KEY_ACCOUNT_LABEL, null)
            
            if (savedToken != null) {
                walletAdapter.authToken = savedToken
                cachedPublicKey = savedPublicKey
                cachedAccountLabel = savedLabel
                Timber.i("$TAG: 已恢复 authToken, 公钥: ${savedPublicKey?.take(8)}...")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 恢复 authToken 失败")
        }
    }
    
    /**
     * 持久化 authToken
     * 
     * 官方推荐：持久化 authToken 可让用户跳过后续的连接确认对话框
     */
    fun persistAuthToken() {
        try {
            walletAdapter.authToken?.let { token ->
                prefs.edit().apply {
                    putString(KEY_AUTH_TOKEN, token)
                    cachedPublicKey?.let { putString(KEY_PUBLIC_KEY, it) }
                    cachedAccountLabel?.let { putString(KEY_ACCOUNT_LABEL, it) }
                    apply()
                }
                Timber.i("$TAG: authToken 已持久化")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 持久化 authToken 失败")
        }
    }
    
    /**
     * 清除持久化的 authToken
     */
    fun clearAuthToken() {
        try {
            walletAdapter.authToken = null
            cachedPublicKey = null
            cachedAccountLabel = null
            prefs.edit().apply {
                remove(KEY_AUTH_TOKEN)
                remove(KEY_PUBLIC_KEY)
                remove(KEY_ACCOUNT_LABEL)
                apply()
            }
            Timber.i("$TAG: authToken 已清除")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 清除 authToken 失败")
        }
    }
    
    /**
     * 检查是否有缓存的 authToken
     */
    fun hasAuthToken(): Boolean {
        return walletAdapter.authToken != null
    }
    
    /**
     * 获取缓存的公钥
     */
    fun getCachedPublicKey(): String? = cachedPublicKey
    
    /**
     * Sign in with Solana (SIWS)
     * 
     * 官方推荐的钱包授权方式，同时验证用户对钱包的所有权
     * 注意：signIn 方法在某些 MWA 版本中可能不可用，此时使用 connect 作为替代
     * 
     * @param sender Activity 结果发送器
     * @param statement 可选的签名声明（保留用于未来 SIWS 支持）
     * @return 授权结果
     */
    suspend fun signIn(
        sender: ActivityResultSender,
        statement: String = "使用 Soulon 需要验证您的钱包所有权"
    ): SignInResult = withTimeout(DEFAULT_TIMEOUT_MS) {
        try {
            Timber.i("$TAG: 开始 Sign in with Solana...")
            
            // 使用 connect 方法作为 SIWS 的替代
            // 注意：完整的 SIWS 需要 signIn 方法，但某些钱包可能不支持
            val result = walletAdapter.connect(sender)
            
            when (result) {
                is TransactionResult.Success -> {
                    val authResult = result.authResult
                    val account = authResult.accounts.firstOrNull()
                    
                    if (account != null) {
                        val publicKeyBase58 = Base58.encodeToString(account.publicKey)
                        cachedPublicKey = publicKeyBase58
                        cachedAccountLabel = account.accountLabel
                        
                        // 自动持久化
                        persistAuthToken()
                        
                        Timber.i("$TAG: ✅ 登录成功, 地址: $publicKeyBase58")
                        
                        SignInResult.Success(
                            publicKey = publicKeyBase58,
                            publicKeyBytes = account.publicKey,
                            accountLabel = account.accountLabel,
                            signInResult = null // connect 不返回 signInResult
                        )
                    } else {
                        SignInResult.Error("未获取到账户信息")
                    }
                }
                is TransactionResult.NoWalletFound -> {
                    Timber.e("$TAG: ❌ 未找到 MWA 兼容钱包")
                    SignInResult.NoWalletFound
                }
                is TransactionResult.Failure -> {
                    Timber.e(result.e, "$TAG: ❌ 登录失败")
                    val message = when {
                        result.e.message?.contains("cancel", ignoreCase = true) == true ||
                        result.e.message?.contains("decline", ignoreCase = true) == true ->
                            "用户取消了登录"
                        else -> result.e.message ?: "登录失败"
                    }
                    SignInResult.Error(message)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 登录出错")
            SignInResult.Error(e.message ?: "登录出错")
        }
    }
    
    /**
     * 简单连接钱包（不使用 SIWS）
     * 
     * @param sender Activity 结果发送器
     * @return 连接结果
     */
    suspend fun connect(
        sender: ActivityResultSender
    ): ConnectResult = withTimeout(DEFAULT_TIMEOUT_MS) {
        try {
            Timber.i("$TAG: 开始连接钱包...")
            
            val result = walletAdapter.connect(sender)
            
            when (result) {
                is TransactionResult.Success -> {
                    val authResult = result.authResult
                    val account = authResult.accounts.firstOrNull()
                    
                    if (account != null) {
                        val publicKeyBase58 = Base58.encodeToString(account.publicKey)
                        cachedPublicKey = publicKeyBase58
                        cachedAccountLabel = account.accountLabel
                        
                        persistAuthToken()
                        
                        Timber.i("$TAG: ✅ 连接成功, 地址: $publicKeyBase58")
                        
                        ConnectResult.Success(
                            publicKey = publicKeyBase58,
                            publicKeyBytes = account.publicKey,
                            accountLabel = account.accountLabel
                        )
                    } else {
                        ConnectResult.Error("未获取到账户信息")
                    }
                }
                is TransactionResult.NoWalletFound -> {
                    Timber.e("$TAG: ❌ 未找到钱包")
                    ConnectResult.NoWalletFound
                }
                is TransactionResult.Failure -> {
                    Timber.e(result.e, "$TAG: ❌ 连接失败")
                    ConnectResult.Error(result.e.message ?: "连接失败")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 连接出错")
            ConnectResult.Error(e.message ?: "连接出错")
        }
    }
    
    /**
     * 断开钱包连接
     * 
     * @param sender Activity 结果发送器
     * @return 是否成功
     */
    suspend fun disconnect(sender: ActivityResultSender): Boolean {
        return try {
            Timber.i("$TAG: 断开钱包连接...")
            
            val result = walletAdapter.disconnect(sender)
            clearAuthToken()
            
            when (result) {
                is TransactionResult.Success -> {
                    Timber.i("$TAG: ✅ 已断开连接")
                    true
                }
                else -> {
                    Timber.w("$TAG: 断开连接返回非成功状态")
                    true // 本地已清除
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 断开连接出错")
            clearAuthToken()
            true
        }
    }
    
    /**
     * 签名并发送交易
     * 
     * 官方推荐：使用 signAndSendTransactions 而非已废弃的 signTransactions
     * 
     * @param sender Activity 结果发送器
     * @param transactions 序列化的交易数组
     * @return 签名结果
     */
    suspend fun signAndSendTransactions(
        sender: ActivityResultSender,
        transactions: Array<ByteArray>
    ): SignAndSendResult = withTimeout(DEFAULT_TIMEOUT_MS) {
        try {
            Timber.i("$TAG: 签名并发送 ${transactions.size} 笔交易...")
            
            val result = walletAdapter.transact(sender) { authResult ->
                signAndSendTransactions(transactions)
            }
            
            when (result) {
                is TransactionResult.Success -> {
                    val signatures = result.payload.signatures
                    val signaturesBase58 = signatures.map { Base58.encodeToString(it) }
                    
                    Timber.i("$TAG: ✅ 交易已发送, 签名数: ${signatures.size}")
                    SignAndSendResult.Success(signaturesBase58)
                }
                is TransactionResult.NoWalletFound -> {
                    Timber.e("$TAG: ❌ 未找到钱包")
                    SignAndSendResult.NoWalletFound
                }
                is TransactionResult.Failure -> {
                    Timber.e(result.e, "$TAG: ❌ 交易失败")
                    SignAndSendResult.Error(result.e.message ?: "交易失败")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 交易出错")
            SignAndSendResult.Error(e.message ?: AppStrings.tr("交易出错", "Transaction error"))
        }
    }
    
    /**
     * 签名消息（脱离交易）
     * 
     * 用于 TEEPIN Attestation 等场景
     * 
     * @param sender Activity 结果发送器
     * @param messages 要签名的消息数组
     * @return 签名结果
     */
    suspend fun signMessages(
        sender: ActivityResultSender,
        messages: Array<ByteArray>
    ): SignMessagesResult = withTimeout(DEFAULT_TIMEOUT_MS) {
        try {
            Timber.i("$TAG: 签名 ${messages.size} 条消息...")
            
            val result = walletAdapter.transact(sender) { authResult ->
                val account = authResult.accounts.firstOrNull()
                    ?: throw Exception(AppStrings.tr("未获取到账户", "Failed to get account"))
                
                signMessagesDetached(
                    messages = messages,
                    addresses = Array(messages.size) { account.publicKey }
                )
            }
            
            when (result) {
                is TransactionResult.Success -> {
                    // 提取签名
                    val signResult = result.payload
                    val signatures = extractSignatures(signResult)
                    
                    Timber.i("$TAG: ✅ 消息签名成功, 签名数: ${signatures.size}")
                    SignMessagesResult.Success(signatures)
                }
                is TransactionResult.NoWalletFound -> {
                    Timber.e("$TAG: ❌ 未找到钱包")
                    SignMessagesResult.NoWalletFound
                }
                is TransactionResult.Failure -> {
                    Timber.e(result.e, "$TAG: ❌ 签名失败")
                    SignMessagesResult.Error(result.e.message ?: AppStrings.tr("签名失败", "Signing failed"))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 签名出错")
            SignMessagesResult.Error(e.message ?: AppStrings.tr("签名出错", "Signing error"))
        }
    }
    
    /**
     * 从签名结果中提取签名字节数组
     */
    private fun extractSignatures(signResult: Any): List<ByteArray> {
        return try {
            val messagesField = signResult.javaClass.getDeclaredField("messages")
            messagesField.isAccessible = true
            val messagesArray = messagesField.get(signResult) as? Array<*> ?: return emptyList()
            
            messagesArray.mapNotNull { signedMessage ->
                if (signedMessage == null) return@mapNotNull null
                
                val signaturesField = signedMessage.javaClass.getDeclaredField("signatures")
                signaturesField.isAccessible = true
                val signaturesArray = signaturesField.get(signedMessage) as? Array<*>
                
                signaturesArray?.firstOrNull() as? ByteArray
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 提取签名失败")
            emptyList()
        }
    }
    
    /**
     * 获取底层 MWA 适配器（高级用法）
     * 
     * 允许直接访问 MobileWalletAdapter 进行自定义操作
     */
    fun getAdapter(): MobileWalletAdapter = walletAdapter
}

// ============================================================================
// 结果类型定义
// ============================================================================

/**
 * Sign In 结果
 */
sealed class SignInResult {
    data class Success(
        val publicKey: String,
        val publicKeyBytes: ByteArray,
        val accountLabel: String?,
        val signInResult: Any? // SignInResult from MWA
    ) : SignInResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return publicKey == other.publicKey
        }
        
        override fun hashCode(): Int = publicKey.hashCode()
    }
    
    object NoWalletFound : SignInResult()
    data class Error(val message: String) : SignInResult()
}

/**
 * Connect 结果
 */
sealed class ConnectResult {
    data class Success(
        val publicKey: String,
        val publicKeyBytes: ByteArray,
        val accountLabel: String?
    ) : ConnectResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return publicKey == other.publicKey
        }
        
        override fun hashCode(): Int = publicKey.hashCode()
    }
    
    object NoWalletFound : ConnectResult()
    data class Error(val message: String) : ConnectResult()
}

/**
 * Sign And Send 结果
 */
sealed class SignAndSendResult {
    data class Success(val signatures: List<String>) : SignAndSendResult()
    object NoWalletFound : SignAndSendResult()
    data class Error(val message: String) : SignAndSendResult()
}

/**
 * Sign Messages 结果
 */
sealed class SignMessagesResult {
    data class Success(val signatures: List<ByteArray>) : SignMessagesResult()
    object NoWalletFound : SignMessagesResult()
    data class Error(val message: String) : SignMessagesResult()
}
