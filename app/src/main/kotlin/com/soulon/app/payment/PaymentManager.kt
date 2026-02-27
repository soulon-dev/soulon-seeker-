package com.soulon.app.payment

import android.content.Context
import com.soulon.app.BuildConfig
import com.soulon.app.wallet.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 支付管理器
 * 
 * 主要功能：
 * - 费用估算：计算 Irys 存储和 cNFT 铸造的预估费用
 * - 余额检查：验证用户钱包是否有足够余额
 * - 支付流程：展示费用确认界面
 * 
 * 重要说明：
 * 实际的支付发生在以下场景：
 * 1. Irys 上传：通过 StorageManager.uploadToIrys() 使用会话密钥或钱包签名
 *    - 会话密钥模式：自动签名，无需用户确认
 *    - 钱包签名模式：需要用户在钱包应用中确认
 * 2. 费用由 Irys 节点在上传时直接收取（或使用预付费余额）
 * 
 * 此管理器主要用于：
 * - 提前估算费用供用户参考
 * - 检查余额是否充足
 * - 提供支付确认 UI 的数据
 */
class PaymentManager(
    private val context: Context,
    private val walletManager: WalletManager
) {
    
    companion object {
        // 费用常量 (lamports)
        private const val LAMPORTS_PER_SOL = 1_000_000_000L
        
        // Irys 上传费用 (每 KB)
        private const val IRYS_COST_PER_KB = 100L  // 约 0.0000001 SOL/KB
        
        // cNFT 铸造费用（固定）
        private const val CNFT_MINT_COST = 0L
        
        // 交易费用（Solana 网络费）
        private const val TRANSACTION_FEE = 5000L  // 约 0.000005 SOL
        
        // 最小余额保留（防止账户被关闭）
        private const val MINIMUM_BALANCE_RESERVE = 10_000_000L  // 0.01 SOL
    }
    
    /**
     * 费用估算结果
     */
    data class CostEstimate(
        val irysUploadCost: Long,           // Irys 上传费用 (lamports)
        val cnftMintCost: Long,             // cNFT 铸造费用 (lamports)
        val transactionFee: Long,           // 交易费用 (lamports)
        val totalCost: Long,                // 总费用 (lamports)
        val totalCostInSol: Double,         // 总费用 (SOL)
        val contentSize: Int,               // 内容大小 (bytes)
        val contentSizeInKB: Double         // 内容大小 (KB)
    ) {
        /**
         * 格式化显示 SOL 金额
         */
        fun formatSol(lamports: Long): String {
            val sol = lamports.toDouble() / LAMPORTS_PER_SOL
            return "%.8f SOL".format(sol)
        }
        
        /**
         * 格式化显示内容大小
         */
        fun formatSize(): String {
            return when {
                contentSize < 1024 -> "$contentSize B"
                contentSize < 1024 * 1024 -> "%.2f KB".format(contentSizeInKB)
                else -> "%.2f MB".format(contentSize.toDouble() / (1024 * 1024))
            }
        }
    }
    
    /**
     * 支付结果
     */
    sealed class PaymentResult {
        data class Success(
            val transactionId: String,
            val finalCost: Long,
            val timestamp: Long = System.currentTimeMillis()
        ) : PaymentResult()
        
        data class Failed(
            val reason: String,
            val errorCode: PaymentErrorCode
        ) : PaymentResult()
        
        object Cancelled : PaymentResult()
        
        data class InsufficientBalance(
            val required: Long,
            val available: Long,
            val shortfall: Long
        ) : PaymentResult()
    }
    
    /**
     * 支付错误码
     */
    enum class PaymentErrorCode {
        WALLET_NOT_CONNECTED,
        INSUFFICIENT_BALANCE,
        TRANSACTION_FAILED,
        SIGNATURE_REJECTED,
        NETWORK_ERROR,
        UNKNOWN_ERROR
    }
    
    /**
     * 估算存储费用
     * 
     * @param contentSize 内容大小（字节）
     * @return 费用估算结果
     */
    fun estimateStorageCost(contentSize: Int): CostEstimate {
        // 计算 Irys 上传费用（按 KB）
        val contentSizeInKB = contentSize.toDouble() / 1024.0
        val irysUploadCost = (contentSizeInKB * IRYS_COST_PER_KB).toLong()
        
        val cnftMintCost = CNFT_MINT_COST
        
        // 交易费用（固定）
        val transactionFee = TRANSACTION_FEE
        
        // 总费用
        val totalCost = irysUploadCost + cnftMintCost + transactionFee
        val totalCostInSol = totalCost.toDouble() / LAMPORTS_PER_SOL
        
        Timber.d("费用估算: 内容大小=${contentSize}B, Irys=${irysUploadCost}, cNFT=${cnftMintCost}, 交易=${transactionFee}, 总计=${totalCost} lamports")
        
        return CostEstimate(
            irysUploadCost = irysUploadCost,
            cnftMintCost = cnftMintCost,
            transactionFee = transactionFee,
            totalCost = totalCost,
            totalCostInSol = totalCostInSol,
            contentSize = contentSize,
            contentSizeInKB = contentSizeInKB
        )
    }
    
    /**
     * 获取当前钱包余额
     * 
     * @return 余额 (lamports)
     */
    suspend fun getCurrentBalance(): Long = withContext(Dispatchers.IO) {
        return@withContext walletManager.getBalance()
    }
    
    /**
     * 检查余额是否足够
     * 
     * @param requiredAmount 需要的金额 (lamports)
     * @return 是否足够
     */
    suspend fun checkBalance(requiredAmount: Long): BalanceCheckResult = withContext(Dispatchers.IO) {
        try {
            if (!walletManager.isConnected()) {
                return@withContext BalanceCheckResult.WalletNotConnected
            }
            
            val currentBalance = walletManager.getBalance()
            
            // 检查是否足够（包括保留最小余额）
            val totalRequired = requiredAmount + MINIMUM_BALANCE_RESERVE
            
            if (currentBalance < totalRequired) {
                val shortfall = totalRequired - currentBalance
                Timber.w("余额不足: 需要=${totalRequired}, 当前=${currentBalance}, 缺少=${shortfall}")
                return@withContext BalanceCheckResult.InsufficientBalance(
                    required = totalRequired,
                    available = currentBalance,
                    shortfall = shortfall
                )
            }
            
            Timber.d("余额充足: 需要=${totalRequired}, 当前=${currentBalance}")
            return@withContext BalanceCheckResult.Sufficient(currentBalance)
            
        } catch (e: Exception) {
            Timber.e(e, "检查余额失败")
            return@withContext BalanceCheckResult.Error(e.message ?: "未知错误")
        }
    }
    
    /**
     * 余额检查结果
     */
    sealed class BalanceCheckResult {
        data class Sufficient(val balance: Long) : BalanceCheckResult()
        data class InsufficientBalance(
            val required: Long,
            val available: Long,
            val shortfall: Long
        ) : BalanceCheckResult()
        object WalletNotConnected : BalanceCheckResult()
        data class Error(val message: String) : BalanceCheckResult()
    }
    
    /**
     * 执行支付
     * 
     * @param operation 操作描述
     * @param cost 费用估算
     * @return 支付结果
     */
    suspend fun executePayment(
        operation: String,
        cost: CostEstimate
    ): PaymentResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("开始执行支付: 操作=$operation, 总费用=${cost.totalCost} lamports")
            
            // Step 1: 检查钱包连接
            if (!walletManager.isConnected()) {
                Timber.e("钱包未连接")
                return@withContext PaymentResult.Failed(
                    reason = "钱包未连接",
                    errorCode = PaymentErrorCode.WALLET_NOT_CONNECTED
                )
            }
            
            // Step 2: 检查余额
            val balanceCheck = checkBalance(cost.totalCost)
            if (balanceCheck is BalanceCheckResult.InsufficientBalance) {
                Timber.e("余额不足")
                return@withContext PaymentResult.InsufficientBalance(
                    required = balanceCheck.required,
                    available = balanceCheck.available,
                    shortfall = balanceCheck.shortfall
                )
            }
            
            // Step 3: 构建交易
            Timber.d("构建交易...")
            val transaction = buildPaymentTransaction(operation, cost)
            
            // Step 4: 请求钱包签名
            Timber.d("请求钱包签名...")
            val signedTransaction = requestWalletSignature(transaction)
            
            // Step 5: 发送交易
            Timber.d("发送交易...")
            val txId = sendTransaction(signedTransaction)
            
            // Step 6: 等待确认
            Timber.d("等待交易确认: $txId")
            val confirmed = waitForConfirmation(txId)
            
            if (confirmed) {
                Timber.i("✅ 支付成功: 交易ID=$txId, 费用=${cost.totalCost} lamports")
                return@withContext PaymentResult.Success(
                    transactionId = txId,
                    finalCost = cost.totalCost
                )
            } else {
                Timber.e("❌ 交易确认失败: $txId")
                return@withContext PaymentResult.Failed(
                    reason = "交易确认超时",
                    errorCode = PaymentErrorCode.TRANSACTION_FAILED
                )
            }
            
        } catch (e: SignatureRejectedException) {
            Timber.e(e, "用户拒绝签名")
            return@withContext PaymentResult.Cancelled
        } catch (e: Exception) {
            Timber.e(e, "支付失败")
            return@withContext PaymentResult.Failed(
                reason = e.message ?: "未知错误",
                errorCode = PaymentErrorCode.UNKNOWN_ERROR
            )
        }
    }
    
    /**
     * 构建支付交易
     */
    private fun buildPaymentTransaction(operation: String, cost: CostEstimate): PaymentTransaction {
        // 在实际实现中，这里会构建真实的 Solana 交易
        // 目前返回模拟交易
        return PaymentTransaction(
            operation = operation,
            amount = cost.totalCost,
            recipient = "Irys Node",  // Irys 节点地址
            memo = "Soulon: $operation"
        )
    }
    
    /**
     * 请求钱包签名
     * 
     * @throws SignatureRejectedException 用户拒绝签名
     */
    private suspend fun requestWalletSignature(transaction: PaymentTransaction): SignedTransaction {
        if (!BuildConfig.DEBUG) {
            throw IllegalStateException("Release 构建不允许使用模拟支付签名")
        }
        // 在实际实现中，这里会调用 Mobile Wallet Adapter 请求签名
        // 目前返回模拟签名
        
        // 模拟用户确认过程
        Timber.d("等待用户在钱包中确认...")
        kotlinx.coroutines.delay(1000)
        
        // 模拟签名成功
        return SignedTransaction(
            transaction = transaction,
            signature = "mock_signature_${System.currentTimeMillis()}"
        )
    }
    
    /**
     * 发送交易
     */
    private suspend fun sendTransaction(signedTransaction: SignedTransaction): String {
        if (!BuildConfig.DEBUG) {
            throw IllegalStateException("Release 构建不允许使用模拟交易发送")
        }
        // 在实际实现中，这里会通过 RPC 发送交易到 Solana 网络
        // 目前返回模拟交易 ID
        
        Timber.d("发送交易到 Solana 网络...")
        kotlinx.coroutines.delay(500)
        
        val txId = "mock_tx_${System.currentTimeMillis()}"
        Timber.d("交易已提交: $txId")
        
        return txId
    }
    
    /**
     * 等待交易确认
     */
    private suspend fun waitForConfirmation(txId: String, timeoutMs: Long = 30000): Boolean {
        // 在实际实现中，这里会轮询交易状态
        // 目前返回模拟确认
        
        Timber.d("等待交易确认...")
        kotlinx.coroutines.delay(2000)
        
        Timber.d("✅ 交易已确认")
        return true
    }
    
    /**
     * 支付交易
     */
    data class PaymentTransaction(
        val operation: String,
        val amount: Long,
        val recipient: String,
        val memo: String
    )
    
    /**
     * 已签名交易
     */
    data class SignedTransaction(
        val transaction: PaymentTransaction,
        val signature: String
    )
    
    /**
     * 签名被拒绝异常
     */
    class SignatureRejectedException(message: String) : Exception(message)
}
