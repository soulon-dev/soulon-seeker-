package com.soulon.app.wallet

import android.content.Context
import android.net.Uri
import com.soulon.app.BuildConfig
import com.soulon.app.auth.BackendAuthManager
import com.soulon.app.i18n.AppStrings
import com.soulon.app.security.SecurePrefs
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Mobile Wallet Adapter 2.0 钱包管理器
 * 
 * 功能：
 * - 连接 Solana 钱包（Phantom, Solflare 等）
 * - 管理授权会话
 * - 请求交易签名
 * - 获取公钥和账户信息
 * 
 * 基于官方文档：
 * https://docs.solanamobile.com/android-native/using_mobile_wallet_adapter
 * 
 * @property context Android 应用上下文
 */
class WalletManager(private val context: Context) {
    
    companion object {
        // dApp 身份信息 - 使用实际部署的后端域名
        private val IDENTITY_URI = BuildConfig.IDENTITY_URI
        private const val IDENTITY_NAME = "Soulon"
        // iconUri 必须是相对路径或 null
        private const val ICON_URI = "/icon.png"
        
        // SharedPreferences 存储
        private const val PREFS_NAME = "wallet_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_ACCOUNT_LABEL = "account_label"
        private const val KEY_IS_CONNECTED = "is_connected"
    }
    
    private var walletAdapter: MobileWalletAdapter
    private var currentSession: WalletSession? = null
    private val prefs = SecurePrefs.create(context, PREFS_NAME)
    
    init {
        // 根据官方文档创建 MWA 客户端
        val solanaUri = Uri.parse(IDENTITY_URI)
        val iconUri = Uri.parse(ICON_URI)
        
        walletAdapter = MobileWalletAdapter(
            connectionIdentity = ConnectionIdentity(
                identityUri = solanaUri,
                iconUri = iconUri,
                identityName = IDENTITY_NAME
            )
        )
        
        // 尝试恢复之前的钱包连接
        restoreSession()
        
        Timber.i("WalletManager 初始化完成")
    }
    
    /**
     * 从 SharedPreferences 恢复钱包会话
     */
    private fun restoreSession() {
        try {
            val isConnected = prefs.getBoolean(KEY_IS_CONNECTED, false)
            if (!isConnected) {
                Timber.i("无已保存的钱包连接")
                return
            }
            
            val authToken = prefs.getString(KEY_AUTH_TOKEN, null)
            val publicKeyHex = prefs.getString(KEY_PUBLIC_KEY, null)
            val accountLabel = prefs.getString(KEY_ACCOUNT_LABEL, null)
            
            if (authToken != null && publicKeyHex != null) {
                // 将十六进制字符串转换回字节数组
                val publicKey = hexStringToByteArray(publicKeyHex)
                
                currentSession = WalletSession(
                    authToken = authToken,
                    publicKey = publicKey,
                    accountLabel = accountLabel
                )
                
                // 恢复 walletAdapter 的 authToken
                walletAdapter.authToken = authToken
                
                Timber.i("成功恢复钱包连接: ${currentSession?.getPublicKeyBase58()}")
            } else {
                Timber.w("钱包连接数据不完整，无法恢复")
                clearSavedSession()
            }
        } catch (e: Exception) {
            Timber.e(e, "恢复钱包会话失败")
            clearSavedSession()
        }
    }
    
    /**
     * 保存钱包会话到 SharedPreferences
     */
    private fun saveSession(session: WalletSession) {
        try {
            prefs.edit().apply {
                putBoolean(KEY_IS_CONNECTED, true)
                putString(KEY_AUTH_TOKEN, session.authToken)
                // 将字节数组转换为十六进制字符串存储
                putString(KEY_PUBLIC_KEY, byteArrayToHexString(session.publicKey))
                putString(KEY_ACCOUNT_LABEL, session.accountLabel)
                apply()
            }
            Timber.i("钱包会话已保存")
        } catch (e: Exception) {
            Timber.e(e, "保存钱包会话失败")
        }
    }
    
    /**
     * 清除保存的钱包会话
     */
    private fun clearSavedSession() {
        try {
            prefs.edit().apply {
                remove(KEY_IS_CONNECTED)
                remove(KEY_AUTH_TOKEN)
                remove(KEY_PUBLIC_KEY)
                remove(KEY_ACCOUNT_LABEL)
                apply()
            }
            Timber.i("已清除保存的钱包会话")
        } catch (e: Exception) {
            Timber.e(e, "清除钱包会话失败")
        }
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 十六进制字符串转字节数组
     */
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4)
                    + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
    
    /**
     * 钱包会话信息
     */
    data class WalletSession(
        val authToken: String,
        val publicKey: ByteArray,
        val accountLabel: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as WalletSession
            if (authToken != other.authToken) return false
            if (!publicKey.contentEquals(other.publicKey)) return false
            return true
        }
        
        override fun hashCode(): Int {
            var result = authToken.hashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
        
        fun getPublicKeyBase58(): String {
            // 使用 Base58 编码（Solana 标准）
            // BitcoinJ 的 Base58 类可以直接使用
            return try {
                org.bitcoinj.core.Base58.encode(publicKey)
            } catch (e: Exception) {
                Timber.e(e, "Base58 编码失败")
                // 降级方案：使用十六进制
                publicKey.joinToString("") { "%02x".format(it) }
            }
        }
    }

    private data class ConnectAndSignPayload(
        val walletAddress: String,
        val publicKey: ByteArray,
        val accountLabel: String?,
        val sessionSignature: ByteArray,
        val authMessage: ByteArray,
    )
    
    /**
     * 检查是否已连接钱包
     */
    fun isConnected(): Boolean {
        return currentSession != null
    }
    
    /**
     * 获取当前会话
     */
    fun getSession(): WalletSession? {
        return currentSession
    }
    
    /**
     * 获取钱包地址（Base58 格式）
     */
    fun getWalletAddress(): String? {
        return currentSession?.getPublicKeyBase58()
    }
    
    /**
     * 获取钱包公钥字节数组
     */
    fun getPublicKeyBytes(): ByteArray? {
        return currentSession?.publicKey
    }
    
    /**
     * 连接钱包
     * 
     * 根据官方文档：
     * https://docs.solanamobile.com/android-native/using_mobile_wallet_adapter
     * 
     * @param activityResultSender Activity 结果发送器
     * @return 钱包会话信息
     */
    suspend fun connect(activityResultSender: ActivityResultSender): WalletSession {
        return try {
            Timber.i("开始连接钱包...")
            
            // 使用 MWA 2.0 的 connect() 方法
            val result = withTimeout(60000L) { // 60秒超时
                walletAdapter.connect(activityResultSender)
            }
            
            when (result) {
                is TransactionResult.Success -> {
                    val authResult = result.authResult
                    Timber.i("钱包授权成功")
                    // ✨ 优化 2: 安全日志 - 不输出完整公钥内容
                    val pubKey = authResult.accounts.firstOrNull()?.publicKey
                    Timber.i("公钥长度: ${pubKey?.size ?: 0} 字节")
                    Timber.i("账户标签: ${authResult.accounts.firstOrNull()?.accountLabel}")
                    
                    val publicKey = authResult.accounts.firstOrNull()?.publicKey
                        ?: throw Exception(AppStrings.tr("未获取到公钥", "Failed to get public key"))
                    
                    val session = WalletSession(
                        authToken = authResult.authToken,
                        publicKey = publicKey,
                        accountLabel = authResult.accounts.firstOrNull()?.accountLabel
                    )
                    
                    currentSession = session
                    // 保存会话到持久化存储
                    saveSession(session)
                    Timber.i("钱包连接成功，地址: ${session.getPublicKeyBase58()}")
                    val backendAuth = BackendAuthManager.getInstance(context)
                    val backendSession = backendAuth.ensureSession(activityResultSender, this@WalletManager)
                    if (backendSession.isFailure) {
                        val detail = backendSession.exceptionOrNull()?.message?.trim().orEmpty()
                        val base = AppStrings.tr("后端登录失败，请重试", "Backend sign-in failed. Please retry.")
                        val msg = if (detail.isNotBlank()) "$base\n$detail" else base
                        throw Exception(msg, backendSession.exceptionOrNull())
                    }
                    session
                }
                is TransactionResult.NoWalletFound -> {
                    Timber.e("未找到 MWA 兼容的钱包应用")
                    currentSession = null
                    throw Exception(AppStrings.tr("未找到钱包应用，请先安装 Phantom 或 Solflare", "No wallet app found. Please install Phantom or Solflare."))
                }
                is TransactionResult.Failure -> {
                    Timber.e(result.e, "钱包连接失败")
                    currentSession = null
                    
                    // 检查是否是用户取消
                    val errorMessage = if (result.e.message?.contains("cancel", ignoreCase = true) == true ||
                                          result.e.message?.contains("decline", ignoreCase = true) == true) {
                        AppStrings.tr("用户取消了连接", "User cancelled the connection")
                    } else {
                        AppStrings.trf("钱包连接失败: %s", "Wallet connection failed: %s", result.e.message ?: "")
                    }
                    
                    throw Exception(errorMessage, result.e)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.e(e, "钱包连接超时")
            currentSession = null
            throw Exception(AppStrings.tr("连接超时，请重试", "Connection timed out. Please retry."))
        } catch (e: Exception) {
            Timber.e(e, "连接钱包时出错")
            currentSession = null
            throw e
        }
    }
    
    /**
     * 一站式连接钱包并签名授权消息
     * 
     * 将连接和会话密钥授权合并到一次钱包交互中，用户只需确认一次
     * 
     * @param activityResultSender Activity 结果发送器
     * @param authMessage 会话密钥授权消息
     * @return Pair<钱包会话, 授权签名>
     */
    suspend fun connectAndSign(
        activityResultSender: ActivityResultSender,
        authMessage: ByteArray
    ): Pair<WalletSession, ByteArray> = withTimeout(60_000) {
        Timber.i("🚀 开始一站式连接钱包并签名...")
        
        try {
            val backendAuth = BackendAuthManager.getInstance(context)
            val result = walletAdapter.transact(activityResultSender) { authResult ->
                // 1. 获取账户信息
                val account = authResult.accounts.firstOrNull()
                    ?: throw Exception(AppStrings.tr("未获取到钱包账户", "Failed to get wallet account"))
                val publicKey = account.publicKey
                val walletAddress = org.bitcoinj.core.Base58.encode(publicKey)
                
                Timber.d("✅ 获取到公钥: ${publicKey.size} 字节")
                Timber.d("📝 请求签名会话密钥授权...")
                val signResult = signMessagesDetached(
                    messages = arrayOf(authMessage),
                    addresses = arrayOf(publicKey)
                )
                
                // 3. 提取签名
                val sessionSignature: ByteArray = try {
                    extractFirstSignature(signResult, 0)
                } catch (e: Exception) {
                    Timber.e(e, "❌ 提取会话签名失败")
                    throw Exception(AppStrings.trf("无法提取签名: %s", "Failed to extract signature: %s", e.message ?: ""))
                }
                
                Timber.d("✅ 会话签名成功: ${sessionSignature.size} 字节")
                
                // 4. 返回公钥、标签和签名
                ConnectAndSignPayload(
                    walletAddress = walletAddress,
                    publicKey = publicKey,
                    accountLabel = account.accountLabel,
                    sessionSignature = sessionSignature,
                    authMessage = authMessage,
                )
            }
            
            when (result) {
                is TransactionResult.Success -> {
                    val payload = result.payload as ConnectAndSignPayload
                    
                    val session = WalletSession(
                        authToken = walletAdapter.authToken ?: "",
                        publicKey = payload.publicKey,
                        accountLabel = payload.accountLabel
                    )
                    
                    currentSession = session
                    saveSession(session)

                    val backendSession = backendAuth.loginWithSessionAuthorizationMessage(
                        walletAddress = payload.walletAddress,
                        signature = payload.sessionSignature,
                        publicKey = payload.publicKey,
                        message = payload.authMessage
                    )
                    if (backendSession.isFailure) {
                        disconnect()
                        val detail = backendSession.exceptionOrNull()?.message?.trim().orEmpty()
                        val base = AppStrings.tr("后端登录失败，请重试", "Backend sign-in failed. Please retry.")
                        val msg = if (detail.isNotBlank()) "$base\n$detail" else base
                        throw Exception(msg, backendSession.exceptionOrNull())
                    }

                    Timber.i("🎉 一站式连接成功！地址: ${session.getPublicKeyBase58()}")
                    Pair(session, payload.sessionSignature)
                }
                is TransactionResult.NoWalletFound -> {
                    Timber.e("❌ 未找到钱包应用")
                    throw Exception(AppStrings.tr("未找到钱包应用，请安装 Phantom 或 Solflare", "No wallet app found. Please install Phantom or Solflare."))
                }
                is TransactionResult.Failure -> {
                    Timber.e(result.e, "❌ 连接并签名失败")
                    
                    val errorMessage = when {
                        result.e.message?.contains("cancel", ignoreCase = true) == true ||
                        result.e.message?.contains("decline", ignoreCase = true) == true ->
                            AppStrings.tr("用户取消了连接", "User cancelled the connection")
                        else ->
                            AppStrings.trf("连接失败: %s", "Connection failed: %s", result.e.message ?: "")
                    }
                    
                    throw Exception(errorMessage, result.e)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ 一站式连接出错")
            currentSession = null
            throw e
        }
    }
    
    /**
     * 断开钱包连接（本地操作，不需要钱包应用参与）
     */
    fun disconnect() {
        try {
            Timber.i("断开钱包连接...")
            currentSession = null
            // 清除 authToken
            walletAdapter.authToken = null
            // 清除持久化存储
            clearSavedSession()
            Timber.i("钱包连接已清除")
        } catch (e: Exception) {
            Timber.e(e, "断开连接时出错")
            currentSession = null
            clearSavedSession()
        }
    }
    
    /**
     * 签名任意消息（用于 SIWS 和 Irys 授权）
     * 
     * ⚠️ 重要：每次调用都会重新建立钱包连接
     * MWA 的 transact 会自动处理连接、授权和断开
     * 
     * @param message 要签名的消息
     * @param activityResultSender Activity 结果发送器
     * @return 签名字节数组 (64 bytes Ed25519 signature)
     */
    suspend fun signMessage(
        message: ByteArray,
        activityResultSender: ActivityResultSender
    ): ByteArray = withTimeout(60_000) {
        Timber.i("🔐 请求钱包签名消息: ${message.size} 字节")
        
        try {
            // transact 会自动处理连接、重新授权、签名、断开
            val result = walletAdapter.transact(activityResultSender) { authResult ->
                // 1. 获取公钥
                val account = authResult.accounts.firstOrNull()
                    ?: throw Exception(AppStrings.tr("未获取到钱包账户", "Failed to get wallet account"))
                val publicKey = account.publicKey
                
                Timber.d("✅ 获取到公钥: ${publicKey.size} 字节")
                
                // 2. 使用 signMessagesDetached 签名
                Timber.d("📝 请求钱包签名...")
                val signResult = signMessagesDetached(
                    messages = arrayOf(message),
                    addresses = arrayOf(publicKey)
                )
                
                val signature: ByteArray = try {
                    extractFirstSignature(signResult, 0)
                } catch (e: Exception) {
                    Timber.e(e, "❌ 提取签名失败")
                    throw Exception(AppStrings.trf("无法从钱包结果中提取签名: %s", "Failed to extract signature from wallet result: %s", e.message ?: ""))
                }
                
                // 5. 返回签名（ByteArray）
                signature
            }
            
            // 5. 处理 transact 的结果
            when (result) {
                is TransactionResult.Success -> {
                    val signature = result.payload as? ByteArray
                    
                    if (signature != null && signature.size == 64) {
                        Timber.i("✅ 消息签名成功: ${signature.size} 字节")
                        return@withTimeout signature
                    }
                    
                    Timber.e("❌ 签名格式错误: ${signature?.size ?: 0} 字节")
                    throw Exception(AppStrings.tr("签名格式错误", "Invalid signature format"))
                }
                is TransactionResult.NoWalletFound -> {
                    Timber.e("❌ 未找到钱包应用")
                    throw Exception(AppStrings.tr("未找到钱包应用，请安装 Phantom 或 Solflare", "No wallet app found. Please install Phantom or Solflare."))
                }
                is TransactionResult.Failure -> {
                    Timber.e(result.e, "❌ 钱包签名失败")
                    
                    // 分析错误类型
                    val errorMessage = when {
                        result.e.message?.contains("cancel", ignoreCase = true) == true ||
                        result.e.message?.contains("decline", ignoreCase = true) == true ->
                            AppStrings.tr("用户取消了签名", "User cancelled signing")
                        
                        result.e.message?.contains("websocket", ignoreCase = true) == true ||
                        result.e.message?.contains("connection", ignoreCase = true) == true ->
                            AppStrings.tr(
                                "钱包连接失败，请确保 Phantom 或 Solflare 正在运行，然后重试",
                                "Wallet connection failed. Make sure Phantom or Solflare is running, then retry."
                            )
                        
                        result.e.message?.contains("timeout", ignoreCase = true) == true ->
                            AppStrings.tr("签名请求超时，请重试", "Signing request timed out. Please retry.")
                        
                        else ->
                            AppStrings.trf("签名失败: %s", "Signing failed: %s", result.e.message ?: "")
                    }
                    
                    throw Exception(errorMessage, result.e)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ 签名消息时出错")
            throw e
        }
    }

    private fun extractFirstSignature(signResult: Any, messageIndex: Int): ByteArray {
        val messages = extractMessages(signResult)
        if (messages.isEmpty() || messageIndex !in messages.indices) {
            throw IllegalStateException(AppStrings.tr("messages 数组为空", "messages array is empty"))
        }
        val signedMessage = messages[messageIndex]
        val signatures = extractSignatures(signedMessage)
        if (signatures.isEmpty()) {
            throw IllegalStateException(AppStrings.tr("signatures 数组为空", "signatures array is empty"))
        }
        val sig = signatures[0]
        if (sig.isEmpty()) {
            throw IllegalStateException(AppStrings.tr("签名为空", "signature is empty"))
        }
        return sig
    }

    private fun extractMessages(signResult: Any): List<Any> {
        val messagesAny = runCatching {
            signResult.javaClass.getDeclaredField("messages").apply { isAccessible = true }.get(signResult)
        }.getOrNull()
            ?: runCatching {
                signResult.javaClass.getMethod("getMessages").invoke(signResult)
            }.getOrNull()
            ?: return emptyList()

        return when (messagesAny) {
            is Array<*> -> messagesAny.filterNotNull()
            is List<*> -> messagesAny.filterNotNull()
            else -> emptyList()
        }.map { it as Any }
    }

    private fun extractSignatures(signedMessage: Any): List<ByteArray> {
        val signaturesAny = runCatching {
            signedMessage.javaClass.getDeclaredField("signatures").apply { isAccessible = true }.get(signedMessage)
        }.getOrNull()
            ?: runCatching {
                signedMessage.javaClass.getMethod("getSignatures").invoke(signedMessage)
            }.getOrNull()
            ?: return emptyList()

        val list = when (signaturesAny) {
            is Array<*> -> signaturesAny.toList()
            is List<*> -> signaturesAny
            else -> emptyList<Any?>()
        }
        return list.mapNotNull { it as? ByteArray }
    }
    
    /**
     * 签名并发送交易
     * 
     * 根据官方文档：
     * https://docs.solanamobile.com/android-native/using_mobile_wallet_adapter
     * 
     * @param transaction 序列化的交易数据
     * @param activityResultSender Activity 结果发送器
     * @return 交易签名（Base58 编码）
     */
    suspend fun signAndSendTransaction(
        transaction: ByteArray,
        activityResultSender: ActivityResultSender
    ): String {
        if (currentSession == null) {
            throw IllegalStateException(AppStrings.tr("未连接钱包，请先连接钱包", "Wallet not connected. Please connect first."))
        }

        suspend fun runOnce(): String {
            Timber.i("请求签名并发送交易...")

            val result = walletAdapter.transact(activityResultSender) { authResult ->
                signAndSendTransactions(arrayOf(transaction))
            }

            return when (result) {
                is TransactionResult.Success -> {
                    val signAndSendResult = result.payload
                    val signatures = signAndSendResult.signatures
                    if (signatures.isNotEmpty()) {
                        val firstSignature = signatures.first()
                        val signatureBase58 = base58Encode(firstSignature)
                        Timber.i("✅ 交易已签名并发送: $signatureBase58")
                        signatureBase58
                    } else {
                        Timber.e("签名数组为空")
                        throw Exception(AppStrings.tr("签名数组为空", "Signature array is empty"))
                    }
                }
                is TransactionResult.NoWalletFound -> {
                    Timber.e("未找到钱包应用")
                    throw Exception(AppStrings.tr("未找到钱包应用", "No wallet app found"))
                }
                is TransactionResult.Failure -> {
                    Timber.e(result.e, "签名并发送交易失败")
                    throw Exception(
                        AppStrings.trf("签名并发送交易失败: %s", "Sign & send transaction failed: %s", result.e.message ?: ""),
                        result.e
                    )
                }
            }
        }

        fun isAuthorizationFailed(e: Throwable): Boolean {
            var cur: Throwable? = e
            while (cur != null) {
                val m = cur.message?.lowercase().orEmpty()
                if (m.contains("authorization request failed") || m.contains("authorize") || m.contains("auth")) return true
                cur = cur.cause
            }
            return false
        }

        return try {
            runOnce()
        } catch (e: Exception) {
            if (isAuthorizationFailed(e)) {
                Timber.w(e, "检测到钱包授权失效，尝试重连后重试一次")
                disconnect()
                connect(activityResultSender)
                try {
                    runOnce()
                } catch (e2: Exception) {
                    throw Exception(AppStrings.tr("钱包授权失败，请重新连接钱包后再试", "Wallet authorization failed. Please reconnect and retry."), e2)
                }
            } else {
                Timber.e(e, "签名并发送交易时出错")
                throw e
            }
        }
    }
    
    /**
     * Base58 解码
     */
    private fun base58Decode(input: String): ByteArray {
        val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val base = ALPHABET.length.toBigInteger()
        var num = java.math.BigInteger.ZERO
        
        for (char in input) {
            val digit = ALPHABET.indexOf(char)
            if (digit < 0) {
                throw IllegalArgumentException("Invalid Base58 character: $char")
            }
            num = num.multiply(base).add(digit.toBigInteger())
        }
        
        val bytes = num.toByteArray()
        
        // 处理前导零
        val leadingZeros = input.takeWhile { it == '1' }.length
        return ByteArray(leadingZeros) + bytes.dropWhile { it == 0.toByte() }
    }
    
    /**
     * Base58 编码
     */
    private fun base58Encode(input: ByteArray): String {
        val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val base = ALPHABET.length.toBigInteger()
        
        // 计算前导零的数量
        val leadingZeros = input.takeWhile { it == 0.toByte() }.size
        
        // 转换为 BigInteger（无符号）
        var num = java.math.BigInteger(1, input)
        
        val result = StringBuilder()
        while (num > java.math.BigInteger.ZERO) {
            val (quotient, remainder) = num.divideAndRemainder(base)
            result.append(ALPHABET[remainder.toInt()])
            num = quotient
        }
        
        // 添加前导 '1'（代表前导零字节）
        repeat(leadingZeros) {
            result.append('1')
        }
        
        return result.reverse().toString()
    }
    
    /**
     * 获取钱包余额（真实查询）
     */
    /**
     * 获取钱包公钥（字节数组）
     * 
     * @return 公钥字节数组（32 bytes），如果未连接返回 null
     */
    fun getPublicKey(): ByteArray? {
        return currentSession?.publicKey
    }
    
    suspend fun getBalance(): Long {
        val session = currentSession
            ?: throw IllegalStateException(AppStrings.tr("未连接钱包，请先连接钱包", "Wallet not connected. Please connect first."))
        
        return try {
            val address = session.getPublicKeyBase58()
            Timber.i("查询钱包余额: $address")
            
            // 使用 Solana RPC 客户端查询真实余额
            val rpcClient = SolanaRpcClient().apply { initBackendProxy(context) }
            val balance = rpcClient.getBalance(address)
            
            Timber.i("钱包余额: $balance lamports (${balance / 1_000_000_000.0} SOL)")
            balance
        } catch (e: Exception) {
            Timber.e(e, "获取余额失败，返回 0")
            0L
        }
    }
}
