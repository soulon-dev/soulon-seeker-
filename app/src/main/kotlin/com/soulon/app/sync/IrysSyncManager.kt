package com.soulon.app.sync

import android.content.Context
import com.google.gson.Gson
import com.soulon.app.cache.MemoryCache
import com.soulon.app.irys.IrysClient
import com.soulon.app.rewards.PersonaData
import com.soulon.app.rewards.RewardsDatabase
import com.soulon.app.storage.ArweaveDataItem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Irys 数据同步管理器
 * 
 * 功能：
 * 1. 自动从 Irys 查询和同步所有记忆
 * 2. 管理上传队列（并发控制）
 * 3. 处理上传失败和重试
 * 4. 同步人格数据到 Irys
 */
class IrysSyncManager(
    private val context: Context,
    private val irysClient: IrysClient,
    private val gson: Gson
) {
    companion object {
        // 并发控制
        private const val MAX_CONCURRENT_UPLOADS = 3
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 2000L
        
        // 同步状态
        private const val PREF_NAME = "irys_sync"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_WALLET_ADDRESS = "wallet_address"
    }
    
    // 上传队列
    private val uploadQueue = Channel<UploadTask>(Channel.UNLIMITED)
    private val activeUploads = AtomicInteger(0)
    
    // 同步状态 Flow
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    // 上传结果 Flow
    private val _uploadResults = MutableSharedFlow<UploadResult>()
    val uploadResults: SharedFlow<UploadResult> = _uploadResults.asSharedFlow()
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // 启动上传队列处理器
        startUploadProcessor()
    }
    
    /**
     * 启动上传队列处理器
     */
    private fun startUploadProcessor() {
        repeat(MAX_CONCURRENT_UPLOADS) { workerId ->
            scope.launch {
                for (task in uploadQueue) {
                    processUploadTask(task, workerId)
                }
            }
        }
        Timber.i("上传队列处理器已启动 (${MAX_CONCURRENT_UPLOADS} 个工作线程)")
    }
    
    /**
     * 处理单个上传任务
     */
    private suspend fun processUploadTask(task: UploadTask, workerId: Int) {
        val currentActive = activeUploads.incrementAndGet()
        updateSyncState { copy(activeUploads = currentActive) }
        
        var lastError: Exception? = null
        var retryCount = 0
        
        while (retryCount <= MAX_RETRY_COUNT) {
            try {
                Timber.d("工作线程 $workerId 处理上传任务: ${task.id} (尝试 ${retryCount + 1})")
                
                // 执行上传
                val uri = task.uploadAction()
                
                // 上传成功
                Timber.i("✅ 上传成功: ${task.id} -> $uri")
                _uploadResults.emit(UploadResult.Success(task.id, uri, task.type))
                
                val newActiveCount = activeUploads.decrementAndGet()
                updateSyncState { 
                    copy(
                        uploadedCount = uploadedCount + 1,
                        activeUploads = newActiveCount
                    ) 
                }
                return
                
            } catch (e: Exception) {
                lastError = e
                retryCount++
                
                if (retryCount <= MAX_RETRY_COUNT) {
                    Timber.w("上传失败，准备重试 (${retryCount}/${MAX_RETRY_COUNT}): ${task.id}, 错误: ${e.message}")
                    delay(RETRY_DELAY_MS * retryCount) // 指数退避
                }
            }
        }
        
        // 所有重试都失败
        Timber.e(lastError, "❌ 上传最终失败: ${task.id}")
        _uploadResults.emit(UploadResult.Failure(task.id, lastError?.message ?: "未知错误", task.type))
        
        val newActiveCount = activeUploads.decrementAndGet()
        updateSyncState { 
            copy(
                failedCount = failedCount + 1,
                activeUploads = newActiveCount,
                lastError = lastError?.message
            ) 
        }
    }
    
    /**
     * 添加上传任务到队列
     */
    suspend fun enqueueUpload(
        id: String,
        type: UploadType,
        uploadAction: suspend () -> String
    ) {
        val task = UploadTask(id, type, uploadAction)
        uploadQueue.send(task)
        
        updateSyncState { copy(pendingCount = pendingCount + 1) }
        
        Timber.d("添加上传任务到队列: $id ($type), 队列大小: ${syncState.value.pendingCount}")
    }
    
    /**
     * 从 Irys 同步所有记忆
     * 
     * @param walletAddress 钱包地址（Base58）
     * @param decryptFunction 解密函数（用于解密加密数据）
     */
    suspend fun syncFromIrys(
        walletAddress: String,
        decryptFunction: suspend (ByteArray) -> ByteArray
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            updateSyncState { copy(isSyncing = true, lastError = null) }
            
            Timber.i("🔄 开始从 Irys 同步数据: $walletAddress")
            
            // 1. 查询所有记忆
            val items = irysClient.queryByOwner(walletAddress)
            Timber.d("查询到 ${items.size} 条记忆")
            
            if (items.isEmpty()) {
                updateSyncState { copy(isSyncing = false) }
                return@withContext SyncResult(
                    success = true,
                    syncedCount = 0,
                    message = "没有找到记忆数据"
                )
            }
            
            var syncedCount = 0
            var failedCount = 0
            val errors = mutableListOf<String>()
            
            // 2. 下载并解密每条记忆
            for (item in items) {
                try {
                    // 检查是否已经缓存
                    if (MemoryCache.get(item.id) != null) {
                        Timber.d("记忆已缓存，跳过: ${item.id}")
                        syncedCount++
                        continue
                    }
                    
                    // 下载数据
                    val encryptedData = irysClient.downloadData(item.uri)
                    Timber.d("下载记忆: ${item.id}, 大小: ${encryptedData.size} 字节")
                    
                    // 解密数据
                    val decryptedData = decryptFunction(encryptedData)
                    val content = String(decryptedData, Charsets.UTF_8)
                    
                    // 存入本地缓存
                    MemoryCache.put(item.id, content)
                    
                    Timber.d("✅ 同步记忆成功: ${item.id}")
                    syncedCount++
                    
                } catch (e: Exception) {
                    Timber.w(e, "同步记忆失败: ${item.id}")
                    failedCount++
                    errors.add("${item.id}: ${e.message}")
                }
                
                // 更新进度
                updateSyncState { 
                    copy(syncProgress = syncedCount.toFloat() / items.size) 
                }
            }
            
            // 3. 同步人格数据（查询特定类型）
            syncPersonaDataFromIrys(walletAddress, decryptFunction)
            
            // 4. 更新最后同步时间
            saveLastSyncTime(walletAddress)
            
            updateSyncState { 
                copy(
                    isSyncing = false,
                    lastSyncTime = System.currentTimeMillis(),
                    syncProgress = 1f
                ) 
            }
            
            Timber.i("✅ 同步完成: 成功 $syncedCount, 失败 $failedCount")
            
            return@withContext SyncResult(
                success = failedCount == 0,
                syncedCount = syncedCount,
                failedCount = failedCount,
                message = if (failedCount > 0) "部分记忆同步失败" else "同步成功",
                errors = errors
            )
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 同步失败")
            updateSyncState { 
                copy(isSyncing = false, lastError = e.message) 
            }
            return@withContext SyncResult(
                success = false,
                message = "同步失败: ${e.message}"
            )
        }
    }
    
    /**
     * 从 Irys 同步人格数据
     */
    private suspend fun syncPersonaDataFromIrys(
        walletAddress: String,
        decryptFunction: suspend (ByteArray) -> ByteArray
    ) {
        try {
            Timber.d("同步人格数据...")
            
            // 查询人格数据（通过特定标签）
            val items = queryPersonaData(walletAddress)
            
            if (items.isEmpty()) {
                Timber.d("没有找到人格数据")
                return
            }
            
            // 获取最新的人格数据
            val latestItem = items.maxByOrNull { it.timestamp }
            if (latestItem == null) {
                Timber.d("没有有效的人格数据")
                return
            }
            
            // 下载并解密
            val encryptedData = irysClient.downloadData(latestItem.uri)
            val decryptedData = decryptFunction(encryptedData)
            val jsonStr = String(decryptedData, Charsets.UTF_8)
            
            // 解析人格数据
            val personaData = gson.fromJson(jsonStr, PersonaData::class.java)
            
            // 保存到本地数据库
            val database = RewardsDatabase.getInstance(context)
            val currentProfile = database.rewardsDao().getUserProfile()
            if (currentProfile != null) {
                val updatedProfile = currentProfile.copy(
                    personaData = personaData,
                    lastPersonaAnalysis = latestItem.timestamp
                )
                database.rewardsDao().updateUserProfile(updatedProfile)
                Timber.i("✅ 人格数据同步成功")
            }
            
        } catch (e: Exception) {
            Timber.w(e, "人格数据同步失败")
        }
    }
    
    /**
     * 查询人格数据
     */
    private suspend fun queryPersonaData(walletAddress: String): List<IrysClient.IrysDataItem> {
        val byType = irysClient.queryPersonaData(walletAddress)
        if (byType.isNotEmpty()) {
            return byType
        }

        val all = irysClient.queryByOwner(walletAddress)
        val tagged = all.filter { it.tags["Type"] == "PersonaData" }
        if (tagged.isNotEmpty()) {
            return tagged
        }

        return emptyList()
    }
    
    /**
     * 上传人格数据到 Irys
     */
    suspend fun uploadPersonaData(
        personaData: PersonaData,
        walletAddress: String,
        encryptFunction: suspend (ByteArray) -> ByteArray,
        sessionKeyManager: com.soulon.app.auth.SessionKeyManager
    ): String = withContext(Dispatchers.IO) {
        try {
            Timber.i("上传人格数据到 Irys...")
            
            // 序列化人格数据
            val jsonStr = gson.toJson(personaData)
            val plainData = jsonStr.toByteArray(Charsets.UTF_8)
            
            // 加密数据
            val encryptedData = encryptFunction(plainData)
            
            // 构建 tags
            val walletHex = base58ToHex(walletAddress)
            val tags = listOf(
                ArweaveDataItem.Tag("App-Name", "MemoryAI"),
                ArweaveDataItem.Tag("Content-Type", "application/json"),
                ArweaveDataItem.Tag("Type", "PersonaData"),
                ArweaveDataItem.Tag("Main-Wallet", walletHex),
                ArweaveDataItem.Tag("Timestamp", System.currentTimeMillis().toString()),
                ArweaveDataItem.Tag("Version", "1.0")
            )
            
            // 上传到 Irys
            val uri = irysClient.uploadWithSessionKey(encryptedData, sessionKeyManager, tags)
            
            Timber.i("✅ 人格数据上传成功: $uri")
            
            return@withContext uri
            
        } catch (e: Exception) {
            Timber.e(e, "❌ 人格数据上传失败")
            throw e
        }
    }
    
    /**
     * 检查是否需要同步
     */
    fun needsSync(walletAddress: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastSyncTime = prefs.getLong(KEY_LAST_SYNC_TIME, 0)
        val savedWallet = prefs.getString(KEY_WALLET_ADDRESS, null)
        
        // 如果钱包地址变化，需要重新同步
        if (savedWallet != walletAddress) {
            return true
        }
        
        // 如果超过 1 小时没有同步，需要同步
        val oneHour = 60 * 60 * 1000L
        return System.currentTimeMillis() - lastSyncTime > oneHour
    }
    
    /**
     * 保存最后同步时间
     */
    private fun saveLastSyncTime(walletAddress: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .putString(KEY_WALLET_ADDRESS, walletAddress)
            .apply()
    }
    
    /**
     * 获取同步统计
     */
    fun getSyncStats(): SyncStats {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return SyncStats(
            lastSyncTime = prefs.getLong(KEY_LAST_SYNC_TIME, 0),
            cachedMemoryCount = MemoryCache.getAllContents().size
        )
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        uploadQueue.close()
        scope.cancel()
    }
    
    /**
     * 更新同步状态
     */
    private fun updateSyncState(update: SyncState.() -> SyncState) {
        _syncState.update { it.update() }
    }
    
    /**
     * Base58 转 Hex
     */
    private fun base58ToHex(base58: String): String {
        return try {
            val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
            val base = ALPHABET.length.toBigInteger()
            var num = java.math.BigInteger.ZERO
            
            for (char in base58) {
                val digit = ALPHABET.indexOf(char)
                if (digit < 0) {
                    throw IllegalArgumentException("Invalid Base58 character: $char")
                }
                num = num.multiply(base).add(digit.toBigInteger())
            }
            
            val bytes = num.toByteArray()
            val leadingZeros = base58.takeWhile { it == '1' }.length
            (ByteArray(leadingZeros) + bytes.dropWhile { it == 0.toByte() })
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            base58
        }
    }
    
    // 数据类
    
    data class UploadTask(
        val id: String,
        val type: UploadType,
        val uploadAction: suspend () -> String
    )
    
    enum class UploadType {
        MEMORY,
        PERSONA_DATA,
        MIGRATION
    }
    
    sealed class UploadResult {
        data class Success(val id: String, val uri: String, val type: UploadType) : UploadResult()
        data class Failure(val id: String, val error: String, val type: UploadType) : UploadResult()
    }
    
    data class SyncState(
        val isSyncing: Boolean = false,
        val syncProgress: Float = 0f,
        val pendingCount: Int = 0,
        val uploadedCount: Int = 0,
        val failedCount: Int = 0,
        val activeUploads: Int = 0,
        val lastSyncTime: Long = 0,
        val lastError: String? = null
    )
    
    data class SyncResult(
        val success: Boolean,
        val syncedCount: Int = 0,
        val failedCount: Int = 0,
        val message: String,
        val errors: List<String> = emptyList()
    )
    
    data class SyncStats(
        val lastSyncTime: Long,
        val cachedMemoryCount: Int
    )
}
