package com.soulon.app.payment

import android.content.Context
import com.funkatronics.encoders.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.soulon.app.i18n.AppStrings
import com.soulon.app.wallet.MobileWalletAdapterClient
import com.soulon.app.wallet.SolanaRpcClient
import com.soulon.app.wallet.SignAndSendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Solana Pay 支付管理器
 * 
 * 实现 Solana Pay 协议，替代 Google Play In-App Purchase
 * 符合 Solana dApp Store 2.0 零佣金支付要求
 * 
 * 支持的支付方式：
 * - SOL 原生代币
 * - USDC 稳定币
 * - $SKR 生态代币
 * 
 * 参考：
 * - https://docs.solanapay.com/
 * - https://github.com/solana-mobile/solana-pay-android-sample
 * 
 * @property context Android 上下文
 * @property mwaClient MWA 客户端
 * @property rpcClient RPC 客户端
 */
class SolanaPayManager(
    private val context: Context,
    private val mwaClient: MobileWalletAdapterClient,
    private val rpcClient: SolanaRpcClient
) {
    companion object {
        private const val TAG = "SolanaPay"
        
        // 默认收款地址（占位符，实际地址从后端配置获取）
        const val RECIPIENT_ADDRESS = ""
        
        // Token Mint 地址
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"  // Mainnet USDC
        const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        
        // Program IDs
        const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"
        const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val ASSOCIATED_TOKEN_PROGRAM_ID = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
        const val MEMO_PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
        
        // 1 SOL = 10^9 lamports
        const val LAMPORTS_PER_SOL = 1_000_000_000L
        
        // USDC 有 6 位小数
        const val USDC_DECIMALS = 6
    }
    
    /**
     * 支付代币类型
     */
    enum class PaymentToken(
        val displayName: String,
        val symbol: String,
        val mint: String?,        // null 表示原生 SOL
        val decimals: Int
    ) {
        SOL("Solana", "SOL", null, 9),
        USDC("USD Coin", "USDC", USDC_MINT, 6),
        SKR("Seeker Token", "SKR", SKR_MINT, 6);
        
        /**
         * 将小数金额转换为原子单位
         */
        fun toAtomicUnits(amount: Double): Long {
            return (amount * Math.pow(10.0, decimals.toDouble())).toLong()
        }
        
        /**
         * 将原子单位转换为小数金额
         */
        fun fromAtomicUnits(amount: Long): Double {
            return amount / Math.pow(10.0, decimals.toDouble())
        }
    }
    
    /**
     * 订阅方案
     */
    data class SubscriptionPlan(
        val id: String,
        val name: String,
        val durationMonths: Int,
        val priceUsd: Double,           // USD 价格
        val priceSol: Double,           // SOL 价格（动态计算）
        val priceSkr: Long,             // SKR 价格（原子单位）
        val benefits: List<String>
    )
    
    /**
     * 预定义的订阅方案
     */
    val subscriptionPlans = listOf(
        SubscriptionPlan(
            id = "monthly",
            name = "月度订阅",
            durationMonths = 1,
            priceUsd = 9.99,
            priceSol = 0.05,            // 约 $9-10
            priceSkr = 100_000_000L, // 100 SKR
            benefits = listOf(
                "无限 AI 对话",
                "2x 积分加速",
                "高级人格分析"
            )
        ),
        SubscriptionPlan(
            id = "yearly",
            name = "年度订阅",
            durationMonths = 12,
            priceUsd = 79.99,
            priceSol = 0.4,             // 约 $80 (20% 折扣)
            priceSkr = 800_000_000L, // 800 SKR (20% 折扣)
            benefits = listOf(
                "包含月度订阅全部权益",
                "3x 积分加速",
                "专属 NFT 徽章",
                "优先客服支持"
            )
        )
    )
    
    /**
     * 创建 SOL 支付交易
     * 
     * @param sender Activity 结果发送器
     * @param amountSol SOL 数量
     * @param memo 备注信息
     * @param senderAddress 发送方钱包地址（可选，用于指定支付来源）
     * @return 支付结果
     */
    suspend fun paySol(
        sender: ActivityResultSender,
        amountSol: Double,
        memo: String,
        senderAddress: String? = null,
        recipientAddress: String? = null,
    ): PaymentResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 创建 SOL 支付: $amountSol SOL, 发送方: ${senderAddress ?: "从缓存"}")
            
            val amountLamports = (amountSol * LAMPORTS_PER_SOL).toLong()
            
            // 构建交易
            val transaction = buildSolTransferTransaction(amountLamports, memo, senderAddress, recipientAddress)
            
            // 签名并发送
            val result = mwaClient.signAndSendTransactions(sender, arrayOf(transaction))
            
            when (result) {
                is SignAndSendResult.Success -> {
                    val signature = result.signatures.firstOrNull()
                    if (signature != null) {
                        Timber.i("$TAG: ✅ SOL 支付成功: $signature")
                        PaymentResult.Success(signature, PaymentToken.SOL, amountLamports)
                    } else {
                        PaymentResult.Error(AppStrings.tr("未获取到交易签名", "Failed to get transaction signature"))
                    }
                }
                is SignAndSendResult.NoWalletFound -> {
                    PaymentResult.NoWalletFound
                }
                is SignAndSendResult.Error -> {
                    PaymentResult.Error(result.message)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: SOL 支付失败")
            PaymentResult.Error(e.message ?: AppStrings.tr("支付失败", "Payment failed"))
        }
    }
    
    /**
     * 创建 USDC/SKR 支付交易
     * 
     * @param sender Activity 结果发送器
     * @param token 支付代币类型
     * @param amount 原子单位数量
     * @param memo 备注信息
     * @param senderAddress 发送方钱包地址（可选，用于指定支付来源）
     * @return 支付结果
     */
    suspend fun payToken(
        sender: ActivityResultSender,
        token: PaymentToken,
        amount: Long,
        memo: String,
        senderAddress: String? = null,
        recipientAddress: String? = null,
    ): PaymentResult = withContext(Dispatchers.IO) {
        try {
            if (token == PaymentToken.SOL) {
                return@withContext paySol(sender, token.fromAtomicUnits(amount), memo, senderAddress, recipientAddress)
            }
            
            Timber.i("$TAG: 创建 ${token.symbol} 支付: ${token.fromAtomicUnits(amount)}, 发送方: ${senderAddress ?: "从缓存"}")
            
            val tokenMint = token.mint
            ?: return@withContext PaymentResult.Error(AppStrings.tr("无效的 Token 类型", "Invalid token type"))
            
            // 构建 SPL Token 转账交易
            val transaction = buildTokenTransferTransaction(tokenMint, amount, memo, senderAddress, recipientAddress)
            
            // 签名并发送
            val result = mwaClient.signAndSendTransactions(sender, arrayOf(transaction))
            
            when (result) {
                is SignAndSendResult.Success -> {
                    val signature = result.signatures.firstOrNull()
                    if (signature != null) {
                        Timber.i("$TAG: ✅ ${token.symbol} 支付成功: $signature")
                        PaymentResult.Success(signature, token, amount)
                    } else {
                        PaymentResult.Error(AppStrings.tr("未获取到交易签名", "Failed to get transaction signature"))
                    }
                }
                is SignAndSendResult.NoWalletFound -> {
                    PaymentResult.NoWalletFound
                }
                is SignAndSendResult.Error -> {
                    PaymentResult.Error(result.message)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: ${token.symbol} 支付失败")
            PaymentResult.Error(e.message ?: AppStrings.tr("支付失败", "Payment failed"))
        }
    }
    
    /**
     * 处理订阅支付
     * 
     * @param sender Activity 结果发送器
     * @param plan 订阅方案
     * @param paymentToken 支付代币
     * @return 订阅结果
     */
    suspend fun processSubscription(
        sender: ActivityResultSender,
        plan: SubscriptionPlan,
        paymentToken: PaymentToken
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 处理订阅: ${plan.name} with ${paymentToken.symbol}")
            
            val memo = "Soulon Subscription: ${plan.id}"
            
            val paymentResult = when (paymentToken) {
                PaymentToken.SOL -> paySol(sender, plan.priceSol, memo)
                PaymentToken.USDC -> payToken(
                    sender, 
                    PaymentToken.USDC, 
                    paymentToken.toAtomicUnits(plan.priceUsd),
                    memo
                )
                PaymentToken.SKR -> payToken(
                    sender,
                    PaymentToken.SKR,
                    plan.priceSkr,
                    memo
                )
            }
            
            when (paymentResult) {
                is PaymentResult.Success -> {
                    SubscriptionResult.Success(
                        signature = paymentResult.signature,
                        plan = plan,
                        paymentToken = paymentToken
                    )
                }
                is PaymentResult.NoWalletFound -> {
                    SubscriptionResult.Error(AppStrings.tr("未找到 MWA 兼容钱包", "No compatible MWA wallet found"))
                }
                is PaymentResult.Error -> {
                    SubscriptionResult.Error(paymentResult.message)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 订阅处理失败")
            SubscriptionResult.Error(e.message ?: AppStrings.tr("订阅失败", "Subscription failed"))
        }
    }
    
    /**
     * 验证支付状态
     * 
     * @param signature 交易签名
     * @return 验证结果
     */
    suspend fun verifyPayment(signature: String): PaymentVerification = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 验证支付: $signature")
            
            val statuses = rpcClient.getSignatureStatuses(listOf(signature))
            val status = statuses.firstOrNull()
            
            if (status != null) {
                when (status.confirmationStatus) {
                    "finalized" -> PaymentVerification.Confirmed(signature)
                    "confirmed" -> PaymentVerification.Pending(
                        signature,
                        AppStrings.tr("等待最终确认", "Waiting for final confirmation")
                    )
                    "processed" -> PaymentVerification.Pending(
                        signature,
                        AppStrings.tr("交易处理中", "Transaction processing")
                    )
                    else -> PaymentVerification.Unknown(signature)
                }
            } else {
                PaymentVerification.NotFound(signature)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 验证支付失败")
            PaymentVerification.Error(e.message ?: AppStrings.tr("验证失败", "Verification failed"))
        }
    }
    
    /**
     * 构建 SOL 转账交易
     * 
     * 手动构建 Solana 交易，避免 web3-solana 的 kborsh 依赖问题
     * 
     * Solana 交易格式:
     * - compact-u16: 签名数量
     * - signatures: 64 字节签名占位符（每个签名者）
     * - Message:
     *   - 1 byte: 签名者数量
     *   - 1 byte: 只读签名账户数量
     *   - 1 byte: 只读非签名账户数量
     *   - compact-u16: 账户数量
     *   - accounts: 32 字节公钥（每个账户）
     *   - 32 bytes: recent blockhash
     *   - compact-u16: 指令数量
     *   - instructions: 指令数据
     * 
     * @param amountLamports SOL 数量（lamports）
     * @param memo 备注信息
     * @param senderAddress 发送方地址（可选，如果不传则从缓存获取）
     */
    private suspend fun buildSolTransferTransaction(
        amountLamports: Long,
        memo: String,
        senderAddress: String? = null,
        recipientOverride: String? = null,
    ): ByteArray {
        // 获取发送者公钥（优先使用传入的地址）
        val senderPubkeyStr = senderAddress ?: mwaClient.getCachedPublicKey()
            ?: throw Exception(AppStrings.tr("未连接钱包，请传入发送方地址", "Wallet not connected. Please provide sender address"))
        
        // 获取收款地址
        val recipientAddress = recipientOverride?.trim().takeUnless { it.isNullOrBlank() } ?: getRecipientAddress()
        
        // 获取最新 blockhash
        val blockhash = rpcClient.getLatestBlockhash()
        
        Timber.d("$TAG: 构建 SOL 转账交易")
        Timber.d("$TAG: sender: ${senderPubkeyStr.take(8)}...")
        Timber.d("$TAG: recipient: ${recipientAddress.take(8)}...")
        Timber.d("$TAG: amount: $amountLamports lamports")
        Timber.d("$TAG: blockhash: ${blockhash.take(8)}...")
        
        // 解码地址
        val senderBytes = Base58.decode(senderPubkeyStr)
        val recipientBytes = Base58.decode(recipientAddress)
        val blockhashBytes = Base58.decode(blockhash)
        
        // System Program ID (全零 32 字节)
        val systemProgramId = ByteArray(32)
        
        // 手动构建交易
        val buffer = ByteBuffer.allocate(512)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // === 签名数组 ===
        // compact-u16: 1 个签名
        buffer.put(1.toByte())
        // 64 字节签名占位符（钱包会替换）
        buffer.put(ByteArray(64))
        
        // === Message ===
        // Header
        buffer.put(1.toByte()) // num_required_signatures: 1
        buffer.put(0.toByte()) // num_readonly_signed_accounts: 0
        buffer.put(1.toByte()) // num_readonly_unsigned_accounts: 1 (System Program)
        
        // Account keys (compact-u16 + keys)
        // 顺序: 签名可写 -> 非签名可写 -> 非签名只读
        buffer.put(3.toByte()) // 3 个账户
        buffer.put(senderBytes)      // 0: sender (签名, 可写)
        buffer.put(recipientBytes)   // 1: recipient (可写)
        buffer.put(systemProgramId)  // 2: System Program (只读)
        
        // Recent blockhash
        buffer.put(blockhashBytes)
        
        // Instructions (compact-u16 + instructions)
        buffer.put(1.toByte()) // 1 个指令
        
        // SystemProgram.Transfer 指令
        buffer.put(2.toByte()) // program_id_index = 2 (System Program)
        
        // Account indices
        buffer.put(2.toByte()) // 2 个账户
        buffer.put(0.toByte()) // from = index 0
        buffer.put(1.toByte()) // to = index 1
        
        // Instruction data: Transfer 指令
        // 格式: u32 instruction_type (2) + u64 lamports
        buffer.put(12.toByte()) // data length = 12 bytes
        buffer.putInt(2) // instruction_type = 2 (Transfer)
        buffer.putLong(amountLamports)
        
        // 提取结果
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        
        Timber.d("$TAG: SOL 转账交易构建完成, 长度: ${result.size} bytes")
        
        return result
    }
    
    /**
     * 获取收款地址
     * 优先从远程配置获取，否则抛出错误
     */
    private fun getRecipientAddress(): String {
        return try {
            val remoteConfig = com.soulon.app.config.RemoteConfigManager.getInstance(context)
            val configAddress = remoteConfig.getRecipientWallet()
            
            Timber.d("$TAG: 远程配置返回的收款地址: '$configAddress'")
            Timber.d("$TAG: 默认收款地址: '$RECIPIENT_ADDRESS'")
            
            if (configAddress.isNotBlank() && isValidSolanaAddress(configAddress)) {
                Timber.i("$TAG: ✅ 使用远程配置的收款地址: ${configAddress.take(8)}...")
                configAddress
            } else if (configAddress.isNotBlank()) {
                Timber.w("$TAG: ⚠️ 远程配置的地址无效: $configAddress (长度: ${try { Base58.decode(configAddress).size } catch (e: Exception) { "解码失败" }})")
                throw IllegalStateException(
                    AppStrings.trf("远程配置的收款地址无效: %s", "Invalid recipient address from remote config: %s", configAddress)
                )
            } else if (isValidSolanaAddress(RECIPIENT_ADDRESS)) {
                Timber.i("$TAG: 使用默认收款地址: ${RECIPIENT_ADDRESS.take(8)}...")
                RECIPIENT_ADDRESS
            } else {
                throw IllegalStateException(
                    AppStrings.tr(
                        "未配置有效的收款地址，请在后台配置 payment.recipient_wallet",
                        "No valid recipient address configured. Please set payment.recipient_wallet in admin."
                    )
                )
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 获取收款地址失败")
            throw IllegalStateException(
                AppStrings.trf("获取收款地址失败: %s", "Failed to load recipient address: %s", e.message ?: "")
            )
        }
    }
    
    /**
     * 验证 Solana 地址是否有效
     * 
     * 有效的 Solana 地址是 Base58 编码的 32 字节公钥
     */
    private fun isValidSolanaAddress(address: String): Boolean {
        return try {
            val decoded = Base58.decode(address)
            decoded.size == 32
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 构建 SPL Token 转账交易
     * 
     * 注意：SPL Token 转账需要复杂的 ATA 派生和可能需要创建 ATA。
     * 当前实现暂时不支持直接 Token 转账，建议使用 SOL 支付。
     * 
     * 如需 USDC/SKR 支付，请先通过 Jupiter 将 SOL 兑换为目标代币。
     * 
     * @param tokenMint Token 的 Mint 地址
     * @param amount 原子单位数量
     * @param memo 备注信息
     * @param senderAddress 发送方地址（可选，如果不传则从缓存获取）
     */
    private suspend fun buildTokenTransferTransaction(
        tokenMint: String,
        amount: Long,
        memo: String,
        senderAddress: String? = null,
        recipientOverride: String? = null,
    ): ByteArray {
        val senderPubkeyStr = senderAddress ?: mwaClient.getCachedPublicKey()
            ?: throw Exception(AppStrings.tr("未连接钱包，请传入发送方地址", "Wallet not connected. Please provide sender address"))
        val recipientOwner = recipientOverride?.trim().takeUnless { it.isNullOrBlank() } ?: getRecipientAddress()
        val blockhash = rpcClient.getLatestBlockhash()

        val tokenAccounts = rpcClient.getTokenAccountsByOwner(senderPubkeyStr, TOKEN_PROGRAM_ID)
        val source = tokenAccounts.firstOrNull { it.mint == tokenMint && it.amount >= amount }
            ?: throw IllegalStateException(AppStrings.tr("未找到足够余额的 Token 账户", "No token account with sufficient balance found"))

        val recipientTokenAccounts = rpcClient.getTokenAccountsByOwner(recipientOwner, TOKEN_PROGRAM_ID)
        val destination = recipientTokenAccounts.firstOrNull { it.mint == tokenMint }
            ?: throw IllegalStateException(AppStrings.tr("收款地址缺少该 Token 账户", "Recipient address has no token account for this token"))

        val senderBytes = Base58.decode(senderPubkeyStr)
        val sourceBytes = Base58.decode(source.address)
        val destinationBytes = Base58.decode(destination.address)
        val tokenProgramBytes = Base58.decode(TOKEN_PROGRAM_ID)
        val blockhashBytes = Base58.decode(blockhash)

        val buffer = ByteBuffer.allocate(512)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(1.toByte())
        buffer.put(ByteArray(64))

        buffer.put(1.toByte())
        buffer.put(0.toByte())
        buffer.put(1.toByte())

        buffer.put(4.toByte())
        buffer.put(senderBytes)
        buffer.put(sourceBytes)
        buffer.put(destinationBytes)
        buffer.put(tokenProgramBytes)

        buffer.put(blockhashBytes)

        buffer.put(1.toByte())

        buffer.put(3.toByte())

        buffer.put(3.toByte())
        buffer.put(1.toByte())
        buffer.put(2.toByte())
        buffer.put(0.toByte())

        buffer.put(9.toByte())
        buffer.put(3.toByte())
        buffer.putLong(amount)

        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }
    
}

/**
 * 支付结果
 */
sealed class PaymentResult {
    data class Success(
        val signature: String,
        val token: SolanaPayManager.PaymentToken,
        val amount: Long
    ) : PaymentResult()
    
    object NoWalletFound : PaymentResult()
    data class Error(val message: String) : PaymentResult()
}

/**
 * 订阅结果
 */
sealed class SubscriptionResult {
    data class Success(
        val signature: String,
        val plan: SolanaPayManager.SubscriptionPlan,
        val paymentToken: SolanaPayManager.PaymentToken
    ) : SubscriptionResult()
    
    data class Error(val message: String) : SubscriptionResult()
}

/**
 * 支付验证结果
 */
sealed class PaymentVerification {
    data class Confirmed(val signature: String) : PaymentVerification()
    data class Pending(val signature: String, val status: String) : PaymentVerification()
    data class NotFound(val signature: String) : PaymentVerification()
    data class Unknown(val signature: String) : PaymentVerification()
    data class Error(val message: String) : PaymentVerification()
}
