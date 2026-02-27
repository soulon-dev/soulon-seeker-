package com.soulon.app.staking

import android.content.Context
import com.funkatronics.encoders.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.soulon.app.wallet.MobileWalletAdapterClient
import com.soulon.app.wallet.SolanaRpcClient
import com.soulon.app.wallet.SignAndSendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 质押交易管理器
 * 
 * 处理链上质押/解押交易
 * 支持 SOL 原生代币和 SPL Token 质押
 */
class StakingTransactionManager(
    private val context: Context,
    private val mwaClient: MobileWalletAdapterClient,
    private val rpcClient: SolanaRpcClient
) {
    companion object {
        private const val TAG = "StakingTx"
        
        // 质押程序地址（需要部署实际的质押合约后替换）
        const val STAKING_PROGRAM_ID = "Stake11111111111111111111111111111111111111"
        
        // 项目收款/质押池地址
        const val STAKING_POOL_ADDRESS = "MemAiStakePoolXXXXXXXXXXXXXXXXXXXXXXXXXXX"
        
        // System Program
        const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"
        
        // Token Program
        const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        
        // 1 SOL = 10^9 lamports
        const val LAMPORTS_PER_SOL = 1_000_000_000L
    }
    
    /**
     * 质押结果
     */
    sealed class StakingResult {
        data class Success(
            val signature: String,
            val amount: Double,
            val token: String
        ) : StakingResult()
        
        object NoWalletFound : StakingResult()
        data class Error(val message: String) : StakingResult()
        data class InsufficientBalance(val required: Double, val available: Double) : StakingResult()
    }
    
    /**
     * 执行 SOL 质押
     * 
     * @param sender Activity 结果发送器
     * @param amount 质押数量（SOL）
     * @param projectId 质押项目 ID
     * @param lockPeriodDays 锁定天数
     * @return 质押结果
     */
    suspend fun stakeSol(
        sender: ActivityResultSender,
        amount: Double,
        projectId: String,
        lockPeriodDays: Int
    ): StakingResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 开始 SOL 质押: $amount SOL, 项目: $projectId")
            
            // 检查余额
            val balance = rpcClient.getBalance(mwaClient.getCachedPublicKey() ?: "")
            val requiredLamports = (amount * LAMPORTS_PER_SOL).toLong()
            
            if (balance < requiredLamports) {
                val availableSol = balance.toDouble() / LAMPORTS_PER_SOL
                return@withContext StakingResult.InsufficientBalance(amount, availableSol)
            }
            
            // 构建质押交易
            val memo = "Stake:$projectId:${lockPeriodDays}d"
            val transaction = buildStakeTransaction(requiredLamports, memo)
            
            // 签名并发送
            val result = mwaClient.signAndSendTransactions(sender, arrayOf(transaction))
            
            when (result) {
                is SignAndSendResult.Success -> {
                    val signature = result.signatures.firstOrNull()
                    if (signature != null) {
                        Timber.i("$TAG: ✅ SOL 质押成功: $signature")
                        StakingResult.Success(signature, amount, "SOL")
                    } else {
                        StakingResult.Error("未获取到交易签名")
                    }
                }
                is SignAndSendResult.NoWalletFound -> {
                    StakingResult.NoWalletFound
                }
                is SignAndSendResult.Error -> {
                    StakingResult.Error(result.message)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: SOL 质押失败")
            StakingResult.Error(e.message ?: "质押失败")
        }
    }
    
    /**
     * 执行 Token 质押
     * 
     * @param sender Activity 结果发送器
     * @param amount 质押数量
     * @param tokenMint Token Mint 地址
     * @param tokenSymbol Token 符号
     * @param projectId 质押项目 ID
     * @param lockPeriodDays 锁定天数
     * @return 质押结果
     */
    suspend fun stakeToken(
        sender: ActivityResultSender,
        amount: Double,
        tokenMint: String,
        tokenSymbol: String,
        projectId: String,
        lockPeriodDays: Int
    ): StakingResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 开始 $tokenSymbol 质押: $amount, 项目: $projectId")
            
            // 构建 Token 质押交易
            val memo = "Stake:$projectId:$tokenSymbol:${lockPeriodDays}d"
            val transaction = buildTokenStakeTransaction(tokenMint, amount, memo)
            
            // 签名并发送
            val result = mwaClient.signAndSendTransactions(sender, arrayOf(transaction))
            
            when (result) {
                is SignAndSendResult.Success -> {
                    val signature = result.signatures.firstOrNull()
                    if (signature != null) {
                        Timber.i("$TAG: ✅ $tokenSymbol 质押成功: $signature")
                        StakingResult.Success(signature, amount, tokenSymbol)
                    } else {
                        StakingResult.Error("未获取到交易签名")
                    }
                }
                is SignAndSendResult.NoWalletFound -> {
                    StakingResult.NoWalletFound
                }
                is SignAndSendResult.Error -> {
                    StakingResult.Error(result.message)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: $tokenSymbol 质押失败")
            StakingResult.Error(e.message ?: "质押失败")
        }
    }
    
    /**
     * 执行解押/提取
     * 
     * @param sender Activity 结果发送器
     * @param amount 提取数量
     * @param tokenSymbol Token 符号
     * @param projectId 质押项目 ID
     * @return 质押结果
     */
    suspend fun unstake(
        sender: ActivityResultSender,
        amount: Double,
        tokenSymbol: String,
        projectId: String
    ): StakingResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 开始解押: $amount $tokenSymbol, 项目: $projectId")
            
            // 构建解押交易
            val memo = "Unstake:$projectId:$tokenSymbol"
            val transaction = buildUnstakeTransaction(amount, tokenSymbol, memo)
            
            // 签名并发送
            val result = mwaClient.signAndSendTransactions(sender, arrayOf(transaction))
            
            when (result) {
                is SignAndSendResult.Success -> {
                    val signature = result.signatures.firstOrNull()
                    if (signature != null) {
                        Timber.i("$TAG: ✅ 解押成功: $signature")
                        StakingResult.Success(signature, amount, tokenSymbol)
                    } else {
                        StakingResult.Error("未获取到交易签名")
                    }
                }
                is SignAndSendResult.NoWalletFound -> {
                    StakingResult.NoWalletFound
                }
                is SignAndSendResult.Error -> {
                    StakingResult.Error(result.message)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 解押失败")
            StakingResult.Error(e.message ?: "解押失败")
        }
    }
    
    /**
     * 领取质押奖励
     */
    suspend fun claimRewards(
        sender: ActivityResultSender,
        projectId: String
    ): StakingResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 领取奖励: 项目 $projectId")
            
            val memo = "ClaimRewards:$projectId"
            val transaction = buildClaimRewardsTransaction(memo)
            
            val result = mwaClient.signAndSendTransactions(sender, arrayOf(transaction))
            
            when (result) {
                is SignAndSendResult.Success -> {
                    val signature = result.signatures.firstOrNull()
                    if (signature != null) {
                        Timber.i("$TAG: ✅ 领取奖励成功: $signature")
                        StakingResult.Success(signature, 0.0, "REWARDS")
                    } else {
                        StakingResult.Error("未获取到交易签名")
                    }
                }
                is SignAndSendResult.NoWalletFound -> {
                    StakingResult.NoWalletFound
                }
                is SignAndSendResult.Error -> {
                    StakingResult.Error(result.message)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 领取奖励失败")
            StakingResult.Error(e.message ?: "领取奖励失败")
        }
    }
    
    /**
     * 验证交易状态
     */
    suspend fun verifyTransaction(signature: String): TransactionStatus = withContext(Dispatchers.IO) {
        try {
            val statuses = rpcClient.getSignatureStatuses(listOf(signature))
            val status = statuses.firstOrNull()
            
            when (status?.confirmationStatus) {
                "finalized" -> TransactionStatus.Confirmed(signature)
                "confirmed" -> TransactionStatus.Processing(signature, "等待最终确认")
                "processed" -> TransactionStatus.Processing(signature, "交易处理中")
                else -> TransactionStatus.Unknown(signature)
            }
        } catch (e: Exception) {
            TransactionStatus.Error(e.message ?: "验证失败")
        }
    }
    
    // ========== 交易构建方法 ==========
    
    /**
     * 构建 SOL 质押交易
     * 
     * 实际实现需要：
     * 1. 调用质押程序的 stake 指令
     * 2. 将 SOL 转入质押池 PDA
     * 3. 创建/更新用户的质押账户
     */
    private suspend fun buildStakeTransaction(
        lamports: Long,
        memo: String
    ): ByteArray {
        val senderPubkey = mwaClient.getCachedPublicKey()
            ?: throw Exception("未连接钱包")
        
        val blockhash = rpcClient.getLatestBlockhash()
        
        // 构建 SOL 转账交易（转入质押池）
        // 在实际部署中，这里应该调用质押程序的指令
        return buildTransferTransaction(
            senderPubkey = senderPubkey,
            recipientPubkey = STAKING_POOL_ADDRESS,
            lamports = lamports,
            blockhash = blockhash,
            memo = memo
        )
    }
    
    /**
     * 构建 Token 质押交易
     */
    private suspend fun buildTokenStakeTransaction(
        tokenMint: String,
        amount: Double,
        memo: String
    ): ByteArray {
        val senderPubkey = mwaClient.getCachedPublicKey()
            ?: throw Exception("未连接钱包")
        
        val blockhash = rpcClient.getLatestBlockhash()
        
        // TODO: 实现 SPL Token 转账到质押池
        // 需要：派生 ATA，创建 Token 转账指令
        
        return buildTransferTransaction(
            senderPubkey = senderPubkey,
            recipientPubkey = STAKING_POOL_ADDRESS,
            lamports = 0,  // Token 转账不需要额外 SOL
            blockhash = blockhash,
            memo = memo
        )
    }
    
    /**
     * 构建解押交易
     */
    private suspend fun buildUnstakeTransaction(
        amount: Double,
        tokenSymbol: String,
        memo: String
    ): ByteArray {
        val senderPubkey = mwaClient.getCachedPublicKey()
            ?: throw Exception("未连接钱包")
        
        val blockhash = rpcClient.getLatestBlockhash()
        
        // 解押交易通常只需要调用程序指令
        // 程序会将资金从池子转回用户
        return buildTransferTransaction(
            senderPubkey = senderPubkey,
            recipientPubkey = STAKING_POOL_ADDRESS,
            lamports = 0,
            blockhash = blockhash,
            memo = memo
        )
    }
    
    /**
     * 构建领取奖励交易
     */
    private suspend fun buildClaimRewardsTransaction(memo: String): ByteArray {
        val senderPubkey = mwaClient.getCachedPublicKey()
            ?: throw Exception("未连接钱包")
        
        val blockhash = rpcClient.getLatestBlockhash()
        
        return buildTransferTransaction(
            senderPubkey = senderPubkey,
            recipientPubkey = STAKING_POOL_ADDRESS,
            lamports = 0,
            blockhash = blockhash,
            memo = memo
        )
    }
    
    /**
     * 构建转账交易
     * 
     * 这是一个简化的实现，实际生产环境需要完整的 Solana 交易序列化
     */
    private fun buildTransferTransaction(
        senderPubkey: String,
        recipientPubkey: String,
        lamports: Long,
        blockhash: String,
        memo: String
    ): ByteArray {
        val senderBytes = Base58.decode(senderPubkey)
        val recipientBytes = Base58.decode(recipientPubkey)
        val blockhashBytes = Base58.decode(blockhash)
        val systemProgramBytes = Base58.decode(SYSTEM_PROGRAM_ID)
        
        // 构建完整的 Solana 交易
        // 交易格式：
        // 1. 签名数量 (1 byte) + 签名 (64 bytes each)
        // 2. 消息头 (3 bytes): 签名数, 只读签名数, 只读非签名数
        // 3. 账户地址数组
        // 4. Recent blockhash (32 bytes)
        // 5. 指令数组
        
        val buffer = ByteBuffer.allocate(1232)  // Solana 交易最大长度
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // 签名数量（稍后填充，先占位）
        val signatureCountPos = buffer.position()
        buffer.put(1.toByte())  // 1 个签名
        
        // 签名占位（64 bytes，钱包会填充）
        buffer.put(ByteArray(64))
        
        // 消息开始
        // Header: num_required_signatures, num_readonly_signed, num_readonly_unsigned
        buffer.put(1.toByte())  // 需要 1 个签名
        buffer.put(0.toByte())  // 0 个只读签名账户
        buffer.put(1.toByte())  // 1 个只读非签名账户（System Program）
        
        // 账户数量
        buffer.put(3.toByte())  // 3 个账户
        
        // 账户地址
        buffer.put(senderBytes)      // 发送者（签名者，可写）
        buffer.put(recipientBytes)   // 接收者（可写）
        buffer.put(systemProgramBytes) // System Program（只读）
        
        // Recent blockhash
        buffer.put(blockhashBytes)
        
        // 指令数量
        buffer.put(1.toByte())
        
        // System Program Transfer 指令
        // 程序 ID 索引
        buffer.put(2.toByte())  // System Program 在账户数组中的索引
        
        // 账户索引数组长度
        buffer.put(2.toByte())
        // 账户索引
        buffer.put(0.toByte())  // 发送者
        buffer.put(1.toByte())  // 接收者
        
        // 指令数据长度
        buffer.put(12.toByte())  // Transfer 指令: 4 bytes 指令标识 + 8 bytes lamports
        
        // 指令数据
        buffer.putInt(2)  // Transfer 指令 = 2
        buffer.putLong(lamports)
        
        // 返回实际使用的字节
        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        
        return result
    }
    
    /**
     * 交易状态
     */
    sealed class TransactionStatus {
        data class Confirmed(val signature: String) : TransactionStatus()
        data class Processing(val signature: String, val status: String) : TransactionStatus()
        data class Unknown(val signature: String) : TransactionStatus()
        data class Error(val message: String) : TransactionStatus()
    }
}
