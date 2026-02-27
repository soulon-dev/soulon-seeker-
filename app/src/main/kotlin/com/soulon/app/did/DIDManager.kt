package com.soulon.app.did

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID

/**
 * DID (Decentralized Identity) 管理器
 * 
 * 用于管理用户的去中心化身份，支持：
 * - KYC 验证后创建 DID
 * - 多钱包绑定到同一 DID
 * - 跨钱包数据合并
 * 
 * 高级功能：仅订阅用户或高等级用户可用
 */
class DIDManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // 当前 DID 身份
    private val _currentDID = MutableStateFlow<DIDIdentity?>(null)
    val currentDID: StateFlow<DIDIdentity?> = _currentDID
    
    companion object {
        private const val PREF_NAME = "did_manager"
        private const val KEY_DID_IDENTITY = "did_identity"
        private const val KEY_LINKED_WALLETS = "linked_wallets"
        private const val KEY_PENDING_LINKS = "pending_links"
        
        // DID 前缀
        private const val DID_PREFIX = "did:memory:"
        
        // 最大可绑定钱包数量
        const val MAX_LINKED_WALLETS = 5
    }
    
    init {
        // 加载已保存的 DID
        loadSavedDID()
    }
    
    /**
     * DID 身份数据
     */
    data class DIDIdentity(
        val did: String,                      // did:memory:xxxx
        val primaryWallet: String,            // 主钱包地址
        val linkedWallets: List<String>,      // 所有绑定的钱包地址
        val kycStatus: KYCStatus,             // KYC 状态
        val kycVerifiedAt: Long?,             // KYC 验证时间
        val createdAt: Long,                  // 创建时间
        val lastMergedAt: Long? = null,       // 最后合并时间
        val totalMemoriesMerged: Int = 0,     // 已合并的记忆总数
        val masterKeyHash: String             // DID 主密钥哈希（用于验证）
    )
    
    /**
     * KYC 状态
     */
    enum class KYCStatus {
        NOT_STARTED,    // 未开始
        PENDING,        // 审核中
        VERIFIED,       // 已验证
        REJECTED        // 被拒绝
    }
    
    /**
     * KYC 验证证明
     */
    data class KYCProof(
        val verificationId: String,           // 验证 ID
        val provider: String,                 // KYC 提供商
        val verifiedAt: Long,                 // 验证时间
        val documentType: String,             // 证件类型
        val countryCode: String,              // 国家代码
        val signature: ByteArray              // 提供商签名
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as KYCProof
            return verificationId == other.verificationId
        }
        
        override fun hashCode(): Int = verificationId.hashCode()
    }
    
    /**
     * 钱包绑定请求
     */
    data class WalletLinkRequest(
        val did: String,
        val walletAddress: String,
        val requestedAt: Long,
        val signature: ByteArray,             // 新钱包的签名
        val status: LinkStatus = LinkStatus.PENDING
    ) {
        enum class LinkStatus {
            PENDING,      // 待确认
            CONFIRMED,    // 已确认
            REJECTED      // 已拒绝
        }
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as WalletLinkRequest
            return did == other.did && walletAddress == other.walletAddress
        }
        
        override fun hashCode(): Int = did.hashCode() + walletAddress.hashCode()
    }
    
    /**
     * 检查用户是否有权使用 DID 功能
     * 
     * 仅订阅用户可使用此高级功能
     * 
     * @param isSubscribed 是否订阅
     * @return 是否有权限
     */
    fun hasPermission(isSubscribed: Boolean): Boolean {
        return isSubscribed
    }
    
    /**
     * 检查是否已有 DID
     */
    fun hasDID(): Boolean = _currentDID.value != null
    
    /**
     * 获取当前 DID
     */
    fun getDID(): DIDIdentity? = _currentDID.value
    
    /**
     * KYC 验证通过后创建 DID
     * 
     * @param kycProof KYC 验证证明
     * @param primaryWallet 主钱包地址
     * @return 创建的 DID 身份
     */
    suspend fun createDID(
        kycProof: KYCProof,
        primaryWallet: String
    ): Result<DIDIdentity> = withContext(Dispatchers.IO) {
        try {
            Timber.i("🆔 创建 DID 身份...")
            
            // 检查是否已有 DID
            if (_currentDID.value != null) {
                return@withContext Result.failure(
                    IllegalStateException("已存在 DID 身份，无法重复创建")
                )
            }
            
            // 生成 DID
            val didId = generateDIDId(primaryWallet, kycProof.verificationId)
            val did = "$DID_PREFIX$didId"
            
            // 生成主密钥哈希
            val masterKeyHash = generateMasterKeyHash(did, primaryWallet)
            
            val identity = DIDIdentity(
                did = did,
                primaryWallet = primaryWallet,
                linkedWallets = listOf(primaryWallet),
                kycStatus = KYCStatus.VERIFIED,
                kycVerifiedAt = kycProof.verifiedAt,
                createdAt = System.currentTimeMillis(),
                masterKeyHash = masterKeyHash
            )
            
            // 保存
            saveDID(identity)
            _currentDID.value = identity
            
            Timber.i("✅ DID 创建成功: $did")
            Result.success(identity)
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 创建 DID 失败")
            Result.failure(e)
        }
    }
    
    /**
     * 模拟 KYC 验证通过（用于测试）
     */
    suspend fun simulateKYCVerification(primaryWallet: String): Result<DIDIdentity> {
        if (!BuildConfig.DEBUG) {
            return Result.failure(IllegalStateException("Release 构建不允许模拟 KYC"))
        }
        val mockProof = KYCProof(
            verificationId = UUID.randomUUID().toString(),
            provider = "MemoryAI-Internal",
            verifiedAt = System.currentTimeMillis(),
            documentType = "ID_CARD",
            countryCode = "CN",
            signature = "mock_signature".toByteArray()
        )
        return createDID(mockProof, primaryWallet)
    }
    
    /**
     * 绑定新钱包到 DID
     * 
     * @param newWallet 新钱包地址
     * @param signature 新钱包的签名（证明所有权）
     * @return 绑定结果
     */
    suspend fun linkWallet(
        newWallet: String,
        signature: ByteArray
    ): Result<DIDIdentity> = withContext(Dispatchers.IO) {
        try {
            val currentIdentity = _currentDID.value
                ?: return@withContext Result.failure(
                    IllegalStateException("未创建 DID，请先完成 KYC")
                )
            
            Timber.i("🔗 绑定新钱包: $newWallet")
            
            // 检查是否已绑定
            if (currentIdentity.linkedWallets.contains(newWallet)) {
                return@withContext Result.failure(
                    IllegalArgumentException("该钱包已绑定")
                )
            }
            
            // 检查绑定数量限制
            if (currentIdentity.linkedWallets.size >= MAX_LINKED_WALLETS) {
                return@withContext Result.failure(
                    IllegalStateException("已达到最大绑定钱包数量 ($MAX_LINKED_WALLETS)")
                )
            }
            
            // 验证签名（简化版本）
            // 实际应该验证签名是否由该钱包私钥签署
            if (signature.isEmpty()) {
                return@withContext Result.failure(
                    SecurityException("签名验证失败")
                )
            }
            
            // 更新 DID
            val updatedIdentity = currentIdentity.copy(
                linkedWallets = currentIdentity.linkedWallets + newWallet
            )
            
            saveDID(updatedIdentity)
            _currentDID.value = updatedIdentity
            
            Timber.i("✅ 钱包绑定成功，当前绑定数量: ${updatedIdentity.linkedWallets.size}")
            Result.success(updatedIdentity)
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 绑定钱包失败")
            Result.failure(e)
        }
    }
    
    /**
     * 解绑钱包
     * 
     * @param wallet 要解绑的钱包地址
     * @return 解绑结果
     */
    suspend fun unlinkWallet(wallet: String): Result<DIDIdentity> = withContext(Dispatchers.IO) {
        try {
            val currentIdentity = _currentDID.value
                ?: return@withContext Result.failure(
                    IllegalStateException("未创建 DID")
                )
            
            // 不能解绑主钱包
            if (wallet == currentIdentity.primaryWallet) {
                return@withContext Result.failure(
                    IllegalArgumentException("无法解绑主钱包")
                )
            }
            
            // 检查是否已绑定
            if (!currentIdentity.linkedWallets.contains(wallet)) {
                return@withContext Result.failure(
                    IllegalArgumentException("该钱包未绑定")
                )
            }
            
            val updatedIdentity = currentIdentity.copy(
                linkedWallets = currentIdentity.linkedWallets - wallet
            )
            
            saveDID(updatedIdentity)
            _currentDID.value = updatedIdentity
            
            Timber.i("✅ 钱包解绑成功: $wallet")
            Result.success(updatedIdentity)
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 解绑钱包失败")
            Result.failure(e)
        }
    }
    
    /**
     * 更新合并统计
     */
    suspend fun updateMergeStats(memoriesMerged: Int) = withContext(Dispatchers.IO) {
        val currentIdentity = _currentDID.value ?: return@withContext
        
        val updatedIdentity = currentIdentity.copy(
            lastMergedAt = System.currentTimeMillis(),
            totalMemoriesMerged = currentIdentity.totalMemoriesMerged + memoriesMerged
        )
        
        saveDID(updatedIdentity)
        _currentDID.value = updatedIdentity
    }
    
    /**
     * 获取所有绑定的钱包
     */
    fun getLinkedWallets(): List<String> {
        return _currentDID.value?.linkedWallets ?: emptyList()
    }
    
    /**
     * 检查钱包是否属于当前 DID
     */
    fun isWalletLinked(wallet: String): Boolean {
        return _currentDID.value?.linkedWallets?.contains(wallet) == true
    }
    
    /**
     * 清除 DID（用于测试或重置）
     */
    fun clearDID() {
        prefs.edit().clear().apply()
        _currentDID.value = null
        Timber.i("DID 已清除")
    }
    
    // ==================== 私有方法 ====================
    
    private fun generateDIDId(wallet: String, verificationId: String): String {
        val input = "$wallet:$verificationId:${System.currentTimeMillis()}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }
    
    private fun generateMasterKeyHash(did: String, primaryWallet: String): String {
        val input = "$did:$primaryWallet:master_key_v1"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    private fun saveDID(identity: DIDIdentity) {
        val json = gson.toJson(identity)
        prefs.edit().putString(KEY_DID_IDENTITY, json).apply()
    }
    
    private fun loadSavedDID() {
        val json = prefs.getString(KEY_DID_IDENTITY, null) ?: return
        try {
            val identity = gson.fromJson(json, DIDIdentity::class.java)
            _currentDID.value = identity
            Timber.d("加载已保存的 DID: ${identity.did}")
        } catch (e: Exception) {
            Timber.e(e, "加载 DID 失败")
        }
    }
}
